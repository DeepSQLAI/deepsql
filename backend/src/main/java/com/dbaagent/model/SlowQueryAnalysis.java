package com.dbaagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Complete slow query analysis result
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SlowQueryAnalysis {

    public enum TimeRange {
        LAST_HOUR,
        LAST_24_HOURS,
        LAST_7_DAYS,
        LAST_30_DAYS,
        ALL_TIME
    }

    // Analysis metadata
    private String connectionId;
    private LocalDateTime analysisDate;
    private TimeRange timeRange;
    private Double slowQueryThresholdMs;

    // Query statistics
    @Builder.Default
    private List<SlowQuery> topSlowQueries = new ArrayList<>();
    private Long totalQueriesAnalyzed;
    private Long totalSlowQueries;
    private Double avgQueryTimeMs;
    private Double medianQueryTimeMs;

    // Resource metrics
    private Double totalDatabaseTimeMs;      // Total time spent in slow queries
    private Double percentageOfTotalTime;    // % of total DB time
    private Long peakQueriesPerSecond;

    // Recommendations
    @Builder.Default
    private List<String> generalRecommendations = new ArrayList<>();
    @Builder.Default
    private List<IndexRecommendation> suggestedIndexes = new ArrayList<>();

    // AI summary
    private String aiSummary;

    // Database health
    private String overallHealth;            // "EXCELLENT", "GOOD", "FAIR", "POOR", "CRITICAL"

    // Truncation flag — set when the log was capped before parsing
    private boolean truncated;

    /**
     * Calculate overall health score
     */
    public String calculateOverallHealth() {
        if (topSlowQueries == null || topSlowQueries.isEmpty()) {
            return "EXCELLENT";
        }

        long criticalCount = topSlowQueries.stream()
            .filter(q -> q.getSeverity() == SlowQuery.Severity.CRITICAL)
            .count();

        long highCount = topSlowQueries.stream()
            .filter(q -> q.getSeverity() == SlowQuery.Severity.HIGH)
            .count();

        if (criticalCount >= 5) return "CRITICAL";
        if (criticalCount > 0 || highCount >= 10) return "POOR";
        if (highCount >= 5) return "FAIR";
        if (topSlowQueries.size() >= 10) return "GOOD";

        return "EXCELLENT";
    }

    /**
     * Get count of queries by severity
     */
    public long getCountBySeverity(SlowQuery.Severity severity) {
        if (topSlowQueries == null) {
            return 0;
        }
        return topSlowQueries.stream()
            .filter(q -> q.getSeverity() == severity)
            .count();
    }

    /**
     * Get total time spent in slow queries
     */
    public Double getTotalSlowQueryTimeSeconds() {
        if (topSlowQueries == null) {
            return 0.0;
        }
        return topSlowQueries.stream()
            .mapToDouble(q -> q.getTotalExecutionTimeMs() != null ? q.getTotalExecutionTimeMs() : 0.0)
            .sum() / 1000.0;
    }

    /**
     * Add a slow query
     */
    public void addSlowQuery(SlowQuery query) {
        if (this.topSlowQueries == null) {
            this.topSlowQueries = new ArrayList<>();
        }
        this.topSlowQueries.add(query);
    }

    /**
     * Add a general recommendation
     */
    public void addRecommendation(String recommendation) {
        if (this.generalRecommendations == null) {
            this.generalRecommendations = new ArrayList<>();
        }
        this.generalRecommendations.add(recommendation);
    }

    /**
     * Add an index recommendation
     */
    public void addIndexRecommendation(IndexRecommendation recommendation) {
        if (this.suggestedIndexes == null) {
            this.suggestedIndexes = new ArrayList<>();
        }
        this.suggestedIndexes.add(recommendation);
    }

    /**
     * Get summary statistics
     */
    public String getSummaryStats() {
        return String.format(
            "Health: %s | Unique Patterns: %d | Critical: %d | High: %d | Total Time: %.2fs",
            overallHealth != null ? overallHealth : "UNKNOWN",
            topSlowQueries != null ? topSlowQueries.size() : 0,
            getCountBySeverity(SlowQuery.Severity.CRITICAL),
            getCountBySeverity(SlowQuery.Severity.HIGH),
            getTotalSlowQueryTimeSeconds()
        );
    }
}
