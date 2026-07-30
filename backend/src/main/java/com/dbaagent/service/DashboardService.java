package com.dbaagent.service;

import com.dbaagent.model.AnalysisHistory;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for aggregating performance data across analysis history
 * Provides dashboard metrics and trends
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DashboardService {

    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;

    @Value("${analysis.history.max-batch:1000}")
    private int analysisHistoryBatch;

    @Value("${slow-query.history.max-batch:1000}")
    private int slowQueryHistoryBatch;

    /**
     * Get performance dashboard data for a connection
     */
    @Transactional(readOnly = true)
    public DashboardData getDashboardData(String connectionId, Integer days) {
        LocalDateTime since = days != null ? LocalDateTime.now().minusDays(days) : LocalDateTime.now().minusDays(30);

        // Get EXPLAIN Plan history
        List<AnalysisHistory> explainHistory = analysisHistoryRepository
            .findByConnectionIdSince(connectionId, since, PageRequest.of(0, analysisHistoryBatch));

        // Get Slow Query history
        List<SlowQueryHistory> slowQueryHistory = slowQueryHistoryRepository
            .findByConnectionIdSince(connectionId, since, PageRequest.of(0, slowQueryHistoryBatch));

        DashboardData data = new DashboardData();
        data.setConnectionId(connectionId);
        data.setTimeRange(days != null ? days + " days" : "30 days");

        // Calculate EXPLAIN Plan metrics
        data.setTotalExplainAnalyses((long) explainHistory.size());
        data.setExplainScoreTrend(calculateExplainScoreTrend(explainHistory));
        data.setExplainIssuesSummary(summarizeExplainIssues(explainHistory));

        // Calculate Slow Query metrics
        data.setTotalSlowQueryAnalyses((long) slowQueryHistory.size());
        data.setSlowQueryTrend(calculateSlowQueryTrend(slowQueryHistory));
        data.setHealthDistribution(calculateHealthDistribution(slowQueryHistory));

        // Calculate overall health
        data.setOverallHealth(calculateOverallHealth(explainHistory, slowQueryHistory));

        // Get top issues
        data.setTopIssues(getTopIssues(explainHistory, slowQueryHistory));

        return data;
    }

    /**
     * Calculate EXPLAIN Plan performance score trend over time
     */
    private List<TrendPoint> calculateExplainScoreTrend(List<AnalysisHistory> history) {
        return history.stream()
            .sorted(Comparator.comparing(AnalysisHistory::getCreatedAt))
            .map(h -> new TrendPoint(
                h.getCreatedAt().toString(),
                h.getPerformanceScore() != null ? h.getPerformanceScore().doubleValue() : 0.0,
                "EXPLAIN"
            ))
            .collect(Collectors.toList());
    }

    /**
     * Summarize EXPLAIN Plan issues
     */
    private Map<String, Long> summarizeExplainIssues(List<AnalysisHistory> history) {
        Map<String, Long> summary = new HashMap<>();
        summary.put("totalAnalyses", (long) history.size());
        summary.put("avgScore", (long) history.stream()
            .filter(h -> h.getPerformanceScore() != null)
            .mapToInt(AnalysisHistory::getPerformanceScore)
            .average()
            .orElse(0.0));
        summary.put("avgIssueCount", (long) history.stream()
            .filter(h -> h.getIssueCount() != null)
            .mapToInt(AnalysisHistory::getIssueCount)
            .average()
            .orElse(0.0));
        return summary;
    }

    /**
     * Calculate slow query trend over time
     */
    private List<TrendPoint> calculateSlowQueryTrend(List<SlowQueryHistory> history) {
        return history.stream()
            .sorted(Comparator.comparing(SlowQueryHistory::getCreatedAt))
            .map(h -> new TrendPoint(
                h.getCreatedAt().toString(),
                h.getTotalSlowQueries() != null ? h.getTotalSlowQueries().doubleValue() : 0.0,
                "Slow Queries"
            ))
            .collect(Collectors.toList());
    }

    /**
     * Calculate health status distribution
     */
    private Map<String, Long> calculateHealthDistribution(List<SlowQueryHistory> history) {
        return history.stream()
            .filter(h -> h.getOverallHealth() != null)
            .collect(Collectors.groupingBy(
                SlowQueryHistory::getOverallHealth,
                Collectors.counting()
            ));
    }

    /**
     * Calculate overall database health based on recent history
     */
    private String calculateOverallHealth(List<AnalysisHistory> explainHistory,
                                         List<SlowQueryHistory> slowQueryHistory) {

        // Get most recent entries
        Optional<AnalysisHistory> latestExplain = explainHistory.stream()
            .max(Comparator.comparing(AnalysisHistory::getCreatedAt));

        Optional<SlowQueryHistory> latestSlowQuery = slowQueryHistory.stream()
            .max(Comparator.comparing(SlowQueryHistory::getCreatedAt));

        // Calculate average score
        double avgExplainScore = latestExplain
            .map(h -> h.getPerformanceScore() != null ? h.getPerformanceScore().doubleValue() : 0.0)
            .orElse(0.0);

        String slowQueryHealth = latestSlowQuery
            .map(SlowQueryHistory::getOverallHealth)
            .orElse("UNKNOWN");

        // Determine overall health
        if (avgExplainScore >= 80 && "EXCELLENT".equals(slowQueryHealth)) {
            return "EXCELLENT";
        } else if (avgExplainScore >= 70 && ("EXCELLENT".equals(slowQueryHealth) || "GOOD".equals(slowQueryHealth))) {
            return "GOOD";
        } else if (avgExplainScore >= 50) {
            return "FAIR";
        } else if (avgExplainScore >= 30) {
            return "POOR";
        } else {
            return "CRITICAL";
        }
    }

    /**
     * Get top issues from recent analyses
     */
    private List<IssueItem> getTopIssues(List<AnalysisHistory> explainHistory,
                                        List<SlowQueryHistory> slowQueryHistory) {
        List<IssueItem> issues = new ArrayList<>();

        // Add EXPLAIN Plan issues
        explainHistory.stream()
            .sorted(Comparator.comparing(AnalysisHistory::getCreatedAt).reversed())
            .limit(5)
            .forEach(h -> {
                if (h.getIssueCount() != null && h.getIssueCount() > 0) {
                    issues.add(new IssueItem(
                        "EXPLAIN",
                        "Query has " + h.getIssueCount() + " performance issues",
                        h.getPerformanceScore() != null ? h.getPerformanceScore() : 0,
                        h.getCreatedAt().toString()
                    ));
                }
            });

        // Add Slow Query issues
        slowQueryHistory.stream()
            .sorted(Comparator.comparing(SlowQueryHistory::getCreatedAt).reversed())
            .limit(5)
            .forEach(h -> {
                if (h.getCriticalCount() != null && h.getCriticalCount() > 0) {
                    issues.add(new IssueItem(
                        "SLOW_QUERY",
                        h.getCriticalCount() + " critical slow queries detected",
                        0,
                        h.getCreatedAt().toString()
                    ));
                }
            });

        return issues.stream()
            .limit(10)
            .collect(Collectors.toList());
    }

    // DTOs
    public static class DashboardData {
        private String connectionId;
        private String timeRange;
        private Long totalExplainAnalyses;
        private Long totalSlowQueryAnalyses;
        private List<TrendPoint> explainScoreTrend;
        private List<TrendPoint> slowQueryTrend;
        private Map<String, Long> explainIssuesSummary;
        private Map<String, Long> healthDistribution;
        private String overallHealth;
        private List<IssueItem> topIssues;

        // Getters and setters
        public String getConnectionId() { return connectionId; }
        public void setConnectionId(String connectionId) { this.connectionId = connectionId; }
        public String getTimeRange() { return timeRange; }
        public void setTimeRange(String timeRange) { this.timeRange = timeRange; }
        public Long getTotalExplainAnalyses() { return totalExplainAnalyses; }
        public void setTotalExplainAnalyses(Long totalExplainAnalyses) { this.totalExplainAnalyses = totalExplainAnalyses; }
        public Long getTotalSlowQueryAnalyses() { return totalSlowQueryAnalyses; }
        public void setTotalSlowQueryAnalyses(Long totalSlowQueryAnalyses) { this.totalSlowQueryAnalyses = totalSlowQueryAnalyses; }
        public List<TrendPoint> getExplainScoreTrend() { return explainScoreTrend; }
        public void setExplainScoreTrend(List<TrendPoint> explainScoreTrend) { this.explainScoreTrend = explainScoreTrend; }
        public List<TrendPoint> getSlowQueryTrend() { return slowQueryTrend; }
        public void setSlowQueryTrend(List<TrendPoint> slowQueryTrend) { this.slowQueryTrend = slowQueryTrend; }
        public Map<String, Long> getExplainIssuesSummary() { return explainIssuesSummary; }
        public void setExplainIssuesSummary(Map<String, Long> explainIssuesSummary) { this.explainIssuesSummary = explainIssuesSummary; }
        public Map<String, Long> getHealthDistribution() { return healthDistribution; }
        public void setHealthDistribution(Map<String, Long> healthDistribution) { this.healthDistribution = healthDistribution; }
        public String getOverallHealth() { return overallHealth; }
        public void setOverallHealth(String overallHealth) { this.overallHealth = overallHealth; }
        public List<IssueItem> getTopIssues() { return topIssues; }
        public void setTopIssues(List<IssueItem> topIssues) { this.topIssues = topIssues; }
    }

    public static class TrendPoint {
        private String timestamp;
        private Double value;
        private String label;

        public TrendPoint(String timestamp, Double value, String label) {
            this.timestamp = timestamp;
            this.value = value;
            this.label = label;
        }

        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
        public Double getValue() { return value; }
        public void setValue(Double value) { this.value = value; }
        public String getLabel() { return label; }
        public void setLabel(String label) { this.label = label; }
    }

    public static class IssueItem {
        private String type;
        private String description;
        private Integer severity;
        private String timestamp;

        public IssueItem(String type, String description, Integer severity, String timestamp) {
            this.type = type;
            this.description = description;
            this.severity = severity;
            this.timestamp = timestamp;
        }

        public String getType() { return type; }
        public void setType(String type) { this.type = type; }
        public String getDescription() { return description; }
        public void setDescription(String description) { this.description = description; }
        public Integer getSeverity() { return severity; }
        public void setSeverity(Integer severity) { this.severity = severity; }
        public String getTimestamp() { return timestamp; }
        public void setTimestamp(String timestamp) { this.timestamp = timestamp; }
    }
}
