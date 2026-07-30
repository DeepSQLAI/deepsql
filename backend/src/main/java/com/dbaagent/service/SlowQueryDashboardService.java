package com.dbaagent.service;

import com.dbaagent.dto.SlowQueryHistorySummary;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.QueryPerformanceRegression;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.QueryPerformanceRegressionRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for generating dashboard widget data for slow query analysis.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryDashboardService {

    private final SlowQueryHistoryRepository historyRepository;
    private final QueryFingerprintRepository fingerprintRepository;
    private final QueryPerformanceRegressionRepository regressionRepository;

    @Data
    @Builder
    public static class DashboardWidgetData {
        private OverviewWidget overview;
        private TrendWidget trend;
        private TopOffendersWidget topOffenders;
        private RegressionWidget regressions;
        private HealthWidget health;
    }

    @Data
    @Builder
    public static class OverviewWidget {
        private long totalSlowQueries;
        private long criticalQueries;
        private long highSeverityQueries;
        private double totalDatabaseTimeMs;
        private int analysisCount;
        private LocalDateTime lastAnalysisAt;
        private String overallHealth;
    }

    @Data
    @Builder
    public static class TrendWidget {
        private List<TrendDataPoint> dataPoints;
        private double avgQueriesPerAnalysis;
        private double avgDatabaseTimeMs;
        private String trendDirection;  // IMPROVING, STABLE, DEGRADING
        private double trendPercentage;
    }

    @Data
    @Builder
    public static class TrendDataPoint {
        private LocalDateTime timestamp;
        private long slowQueryCount;
        private double totalDatabaseTimeMs;
        private String health;
    }

    @Data
    @Builder
    public static class TopOffendersWidget {
        private List<TopQuery> topByTime;
        private List<TopQuery> topByFrequency;
        private List<TopQuery> topByImpact;
    }

    @Data
    @Builder
    public static class TopQuery {
        private String queryId;
        private String queryPreview;
        private double avgTimeMs;
        private long callCount;
        private double totalTimeMs;
        private double impactScore;
        private String severity;
    }

    @Data
    @Builder
    public static class RegressionWidget {
        private int activeRegressions;
        private int criticalRegressions;
        private int severeRegressions;
        private List<RegressionItem> recentRegressions;
    }

    @Data
    @Builder
    public static class RegressionItem {
        private String id;
        private String queryPreview;
        private double previousAvgMs;
        private double currentAvgMs;
        private double regressionFactor;
        private String severity;
        private LocalDateTime detectedAt;
    }

    @Data
    @Builder
    public static class HealthWidget {
        private String currentHealth;
        private int healthScore;  // 0-100
        private List<HealthHistoryPoint> history;
        private Map<String, Integer> healthDistribution;
    }

    @Data
    @Builder
    public static class HealthHistoryPoint {
        private LocalDateTime timestamp;
        private String health;
        private int score;
    }

    /**
     * Get all widget data for a connection
     */
    public DashboardWidgetData getWidgetData(String connectionId) {
        return DashboardWidgetData.builder()
            .overview(getOverviewWidget(connectionId))
            .trend(getTrendWidget(connectionId))
            .topOffenders(getTopOffendersWidget(connectionId))
            .regressions(getRegressionWidget(connectionId))
            .health(getHealthWidget(connectionId))
            .build();
    }

    /**
     * Overview widget
     */
    public OverviewWidget getOverviewWidget(String connectionId) {
        List<SlowQueryHistorySummary> summaries = historyRepository
            .findRecentSummariesByConnectionId(connectionId, PageRequest.of(0, 30));

        if (summaries.isEmpty()) {
            return OverviewWidget.builder()
                .totalSlowQueries(0)
                .criticalQueries(0)
                .highSeverityQueries(0)
                .totalDatabaseTimeMs(0)
                .analysisCount(0)
                .overallHealth("UNKNOWN")
                .build();
        }

        long totalSlow = summaries.stream()
            .mapToLong(s -> s.getTotalSlowQueries() != null ? s.getTotalSlowQueries() : 0)
            .sum();

        long critical = summaries.stream()
            .mapToLong(s -> s.getCriticalCount() != null ? s.getCriticalCount() : 0)
            .sum();

        long high = summaries.stream()
            .mapToLong(s -> s.getHighCount() != null ? s.getHighCount() : 0)
            .sum();

        double totalTime = summaries.stream()
            .mapToDouble(s -> s.getTotalDatabaseTimeMs() != null ? s.getTotalDatabaseTimeMs() : 0)
            .sum();

        SlowQueryHistorySummary latest = summaries.get(0);

        return OverviewWidget.builder()
            .totalSlowQueries(totalSlow)
            .criticalQueries(critical)
            .highSeverityQueries(high)
            .totalDatabaseTimeMs(totalTime)
            .analysisCount(summaries.size())
            .lastAnalysisAt(latest.getCreatedAt())
            .overallHealth(latest.getOverallHealth())
            .build();
    }

    /**
     * Trend widget with time series data
     */
    public TrendWidget getTrendWidget(String connectionId) {
        List<SlowQueryHistorySummary> summaries = historyRepository
            .findRecentSummariesByConnectionId(connectionId, PageRequest.of(0, 50));

        if (summaries.isEmpty()) {
            return TrendWidget.builder()
                .dataPoints(Collections.emptyList())
                .avgQueriesPerAnalysis(0)
                .avgDatabaseTimeMs(0)
                .trendDirection("STABLE")
                .trendPercentage(0)
                .build();
        }

        // Reverse to get chronological order
        List<SlowQueryHistorySummary> chronological = new ArrayList<>(summaries);
        Collections.reverse(chronological);

        List<TrendDataPoint> dataPoints = chronological.stream()
            .map(s -> TrendDataPoint.builder()
                .timestamp(s.getCreatedAt())
                .slowQueryCount(s.getTotalSlowQueries() != null ? s.getTotalSlowQueries() : 0)
                .totalDatabaseTimeMs(s.getTotalDatabaseTimeMs() != null ? s.getTotalDatabaseTimeMs() : 0)
                .health(s.getOverallHealth())
                .build())
            .toList();

        double avgQueries = chronological.stream()
            .mapToLong(s -> s.getTotalSlowQueries() != null ? s.getTotalSlowQueries() : 0)
            .average()
            .orElse(0);

        double avgTime = chronological.stream()
            .mapToDouble(s -> s.getTotalDatabaseTimeMs() != null ? s.getTotalDatabaseTimeMs() : 0)
            .average()
            .orElse(0);

        // Calculate trend
        String trendDirection = "STABLE";
        double trendPercentage = 0;

        if (chronological.size() >= 5) {
            // Compare last 5 to previous 5
            int midPoint = chronological.size() / 2;
            double recentAvg = chronological.subList(midPoint, chronological.size()).stream()
                .mapToDouble(s -> s.getTotalDatabaseTimeMs() != null ? s.getTotalDatabaseTimeMs() : 0)
                .average()
                .orElse(0);

            double olderAvg = chronological.subList(0, midPoint).stream()
                .mapToDouble(s -> s.getTotalDatabaseTimeMs() != null ? s.getTotalDatabaseTimeMs() : 0)
                .average()
                .orElse(0);

            if (olderAvg > 0) {
                trendPercentage = ((recentAvg - olderAvg) / olderAvg) * 100;
                if (trendPercentage < -10) {
                    trendDirection = "IMPROVING";
                } else if (trendPercentage > 20) {
                    trendDirection = "DEGRADING";
                }
            }
        }

        return TrendWidget.builder()
            .dataPoints(dataPoints)
            .avgQueriesPerAnalysis(avgQueries)
            .avgDatabaseTimeMs(avgTime)
            .trendDirection(trendDirection)
            .trendPercentage(trendPercentage)
            .build();
    }

    /**
     * Top offenders widget
     */
    public TopOffendersWidget getTopOffendersWidget(String connectionId) {
        List<QueryFingerprint> fingerprints = fingerprintRepository
            .findTopSlowQueries(connectionId, PageRequest.of(0, 10));

        List<TopQuery> topByTime = fingerprints.stream()
            .sorted(Comparator.comparingDouble(
                fp -> fp.getCurrentAvgTimeMs() != null ? -fp.getCurrentAvgTimeMs() : 0))
            .limit(5)
            .map(this::toTopQuery)
            .toList();

        List<TopQuery> topByFrequency = fingerprints.stream()
            .sorted(Comparator.comparingLong(
                fp -> fp.getCurrentCallCount() != null ? -fp.getCurrentCallCount() : 0))
            .limit(5)
            .map(this::toTopQuery)
            .toList();

        List<TopQuery> topByImpact = fingerprints.stream()
            .sorted(Comparator.comparingDouble(fp -> {
                double avgTime = fp.getCurrentAvgTimeMs() != null ? fp.getCurrentAvgTimeMs() : 0;
                long calls = fp.getCurrentCallCount() != null ? fp.getCurrentCallCount() : 0;
                return -(avgTime * Math.log10(calls + 1));
            }))
            .limit(5)
            .map(this::toTopQuery)
            .toList();

        return TopOffendersWidget.builder()
            .topByTime(topByTime)
            .topByFrequency(topByFrequency)
            .topByImpact(topByImpact)
            .build();
    }

    /**
     * Regression widget
     */
    public RegressionWidget getRegressionWidget(String connectionId) {
        List<QueryPerformanceRegression> regressions = regressionRepository
            .findByConnectionIdAndAcknowledgedFalseAndResolvedFalseOrderByDetectedAtDesc(connectionId);

        int critical = 0, severe = 0;
        for (QueryPerformanceRegression r : regressions) {
            if (r.getSeverity() == QueryPerformanceRegression.Severity.CRITICAL) {
                critical++;
            } else if (r.getSeverity() == QueryPerformanceRegression.Severity.SEVERE) {
                severe++;
            }
        }

        List<RegressionItem> recentItems = regressions.stream()
            .limit(5)
            .map(r -> RegressionItem.builder()
                .id(String.valueOf(r.getId()))
                .queryPreview(truncate(r.getNormalizedQuery(), 100))
                .previousAvgMs(r.getBaselineAvgMs() != null ? r.getBaselineAvgMs() : 0)
                .currentAvgMs(r.getCurrentAvgMs() != null ? r.getCurrentAvgMs() : 0)
                .regressionFactor(r.getSlowdownFactor() != null ? r.getSlowdownFactor() : 0)
                .severity(r.getSeverity() != null ? r.getSeverity().name() : "UNKNOWN")
                .detectedAt(r.getDetectedAt())
                .build())
            .toList();

        return RegressionWidget.builder()
            .activeRegressions(regressions.size())
            .criticalRegressions(critical)
            .severeRegressions(severe)
            .recentRegressions(recentItems)
            .build();
    }

    /**
     * Health widget
     */
    public HealthWidget getHealthWidget(String connectionId) {
        List<SlowQueryHistorySummary> summaries = historyRepository
            .findRecentSummariesByConnectionId(connectionId, PageRequest.of(0, 30));

        if (summaries.isEmpty()) {
            return HealthWidget.builder()
                .currentHealth("UNKNOWN")
                .healthScore(0)
                .history(Collections.emptyList())
                .healthDistribution(Collections.emptyMap())
                .build();
        }

        String currentHealth = summaries.get(0).getOverallHealth();
        int healthScore = calculateHealthScore(currentHealth);

        List<HealthHistoryPoint> history = summaries.stream()
            .map(s -> HealthHistoryPoint.builder()
                .timestamp(s.getCreatedAt())
                .health(s.getOverallHealth())
                .score(calculateHealthScore(s.getOverallHealth()))
                .build())
            .toList();

        Map<String, Integer> distribution = summaries.stream()
            .filter(s -> s.getOverallHealth() != null)
            .collect(Collectors.groupingBy(
                SlowQueryHistorySummary::getOverallHealth,
                Collectors.summingInt(s -> 1)
            ));

        return HealthWidget.builder()
            .currentHealth(currentHealth)
            .healthScore(healthScore)
            .history(history)
            .healthDistribution(distribution)
            .build();
    }

    private TopQuery toTopQuery(QueryFingerprint fp) {
        double avgTime = fp.getCurrentAvgTimeMs() != null ? fp.getCurrentAvgTimeMs() : 0;
        long calls = fp.getCurrentCallCount() != null ? fp.getCurrentCallCount() : 0;
        double totalTime = avgTime * calls;
        double impact = (avgTime / 1000.0) * Math.log10(calls + 1) * 10;

        String severity = "LOW";
        if (calls > 10000 && avgTime > 1000) severity = "CRITICAL";
        else if (calls > 1000 && avgTime > 500) severity = "HIGH";
        else if (avgTime > 1000) severity = "MEDIUM";

        return TopQuery.builder()
            .queryId(fp.getFingerprint())
            .queryPreview(truncate(fp.getNormalizedQuery(), 100))
            .avgTimeMs(avgTime)
            .callCount(calls)
            .totalTimeMs(totalTime)
            .impactScore(Math.min(100, impact))
            .severity(severity)
            .build();
    }

    private int calculateHealthScore(String health) {
        if (health == null) return 50;
        return switch (health.toUpperCase()) {
            case "EXCELLENT" -> 100;
            case "GOOD" -> 80;
            case "FAIR" -> 60;
            case "POOR" -> 40;
            case "CRITICAL" -> 20;
            default -> 50;
        };
    }

    private String truncate(String str, int maxLength) {
        if (str == null) return "";
        if (str.length() <= maxLength) return str;
        return str.substring(0, maxLength) + "...";
    }
}
