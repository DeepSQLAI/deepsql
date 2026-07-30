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
 * Complete EXPLAIN plan analysis result
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExplainPlanAnalysis {

    // Query information
    private String connectionId;
    private String query;
    private String normalizedQuery;
    private String dbType;              // "mysql" or "postgresql"

    // Execution plan
    private ExplainPlanNode planTree;
    private String planText;           // Raw plan text when available
    private String planJson;           // Raw plan JSON when available
    private String planSignature;      // Stable hash for plan drift tracking
    private String planParseError;     // Parsing error details, if any

    // Analysis results
    @Builder.Default
    private List<PerformanceIssue> issues = new ArrayList<>();

    @Builder.Default
    private List<String> optimizationSuggestions = new ArrayList<>();

    // Cost metrics
    private Double estimatedCost;
    private Double actualCost;
    private Double estimatedRows;
    private Long actualRows;

    // Timing (when using EXPLAIN ANALYZE)
    private Double planningTimeMs;
    private Double executionTimeMs;
    private Double totalTimeMs;

    // Index recommendations
    @Builder.Default
    private List<IndexRecommendation> indexRecommendations = new ArrayList<>();

    // AI-generated summary and suggestions
    private String aiSummary;
    private String aiOptimization;

    // Metadata
    private LocalDateTime analyzedAt;
    private Boolean wasExecuted;        // True if EXPLAIN ANALYZE was used
    private Integer nodeCount;          // Total nodes in plan tree

    /**
     * Calculate overall performance score (0-100)
     * Higher is better
     */
    public int getPerformanceScore() {
        if (issues == null || issues.isEmpty()) {
            return 100;
        }

        int score = 100;

        for (PerformanceIssue issue : issues) {
            switch (issue.getSeverity()) {
                case CRITICAL:
                    score -= 25;
                    break;
                case HIGH:
                    score -= 15;
                    break;
                case MEDIUM:
                    score -= 10;
                    break;
                case LOW:
                    score -= 5;
                    break;
                case INFO:
                    score -= 2;
                    break;
            }
        }

        return Math.max(0, score);
    }

    /**
     * Get overall severity level
     */
    public PerformanceIssue.Severity getOverallSeverity() {
        if (issues == null || issues.isEmpty()) {
            return PerformanceIssue.Severity.INFO;
        }

        boolean hasCritical = issues.stream()
            .anyMatch(i -> i.getSeverity() == PerformanceIssue.Severity.CRITICAL);
        if (hasCritical) {
            return PerformanceIssue.Severity.CRITICAL;
        }

        boolean hasHigh = issues.stream()
            .anyMatch(i -> i.getSeverity() == PerformanceIssue.Severity.HIGH);
        if (hasHigh) {
            return PerformanceIssue.Severity.HIGH;
        }

        boolean hasMedium = issues.stream()
            .anyMatch(i -> i.getSeverity() == PerformanceIssue.Severity.MEDIUM);
        if (hasMedium) {
            return PerformanceIssue.Severity.MEDIUM;
        }

        return PerformanceIssue.Severity.LOW;
    }

    /**
     * Check if plan has any full table scans
     */
    public boolean hasFullTableScans() {
        if (issues == null) {
            return false;
        }
        return issues.stream()
            .anyMatch(i -> i.getType() == PerformanceIssue.IssueType.FULL_TABLE_SCAN);
    }

    /**
     * Get count of issues by severity
     */
    public long getIssueCountBySeverity(PerformanceIssue.Severity severity) {
        if (issues == null) {
            return 0;
        }
        return issues.stream()
            .filter(i -> i.getSeverity() == severity)
            .count();
    }

    /**
     * Add a performance issue
     */
    public void addIssue(PerformanceIssue issue) {
        if (this.issues == null) {
            this.issues = new ArrayList<>();
        }
        this.issues.add(issue);
    }

    /**
     * Add an optimization suggestion
     */
    public void addOptimizationSuggestion(String suggestion) {
        if (this.optimizationSuggestions == null) {
            this.optimizationSuggestions = new ArrayList<>();
        }
        this.optimizationSuggestions.add(suggestion);
    }

    /**
     * Add an index recommendation
     */
    public void addIndexRecommendation(IndexRecommendation recommendation) {
        if (this.indexRecommendations == null) {
            this.indexRecommendations = new ArrayList<>();
        }
        this.indexRecommendations.add(recommendation);
    }

    /**
     * Get total estimated improvement percentage
     */
    public Double getTotalEstimatedImprovement() {
        if (issues == null || issues.isEmpty()) {
            return 0.0;
        }

        return issues.stream()
            .filter(i -> i.getEstimatedImpact() != null)
            .mapToDouble(PerformanceIssue::getEstimatedImpact)
            .average()
            .orElse(0.0);
    }

    /**
     * Get summary statistics
     */
    public String getSummaryStats() {
        return String.format(
            "Score: %d/100 | Issues: %d (%d critical, %d high) | Est. Improvement: %.1f%%",
            getPerformanceScore(),
            issues != null ? issues.size() : 0,
            getIssueCountBySeverity(PerformanceIssue.Severity.CRITICAL),
            getIssueCountBySeverity(PerformanceIssue.Severity.HIGH),
            getTotalEstimatedImprovement()
        );
    }
}
