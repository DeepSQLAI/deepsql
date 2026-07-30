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
 * Represents a slow query detected in the database
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class SlowQuery {

    public enum Severity {
        CRITICAL,  // Extremely slow, needs immediate attention
        HIGH,      // Very slow, should fix soon
        MEDIUM,    // Moderately slow, plan to optimize
        LOW        // Slow but acceptable
    }

    // Query identification
    private String queryId;                    // Hash of normalized query
    private String queryText;                  // Original query text
    private String sampleQuery;                // Actual query with literals when available
    private String normalizedQuery;            // Query with literals replaced by ?
    private String database;                   // Database name
    private String source;                     // Source of slow query data (e.g., "Performance Schema", "slow_log table")

    /**
     * True when the database server itself truncated this query text before
     * we could see it. Triggered by:
     *   - PostgreSQL: pg_stat_statements.track_activity_query_size (default 1024B).
     *     Long queries are silently truncated at this boundary.
     *   - MySQL: performance_schema_max_sql_text_length (default 1024B). Same
     *     silent truncation behavior.
     *
     * When this flag is true:
     *   - The user should be warned that EXPLAIN against `queryText` will
     *     either fail outright or return a misleading partial plan.
     *   - The product surfacing this query should suggest raising the
     *     server-side limit (and that a server restart is required).
     *   - AI-driven query optimization should refuse to suggest rewrites
     *     from the truncated text alone.
     *
     * Not the same as our own internal length-based truncation for previews —
     * those produce dedicated `*Preview` fields and never overwrite
     * `queryText`.
     */
    private Boolean sourceTruncated;

    /**
     * True when `queryText` was originally truncated by the database server
     * (see `sourceTruncated` above) but DeepSQL successfully recovered the
     * full text from previously-ingested slow-log data stored in
     * `query_lineage`.
     *
     * When this flag is true:
     *   - `sourceTruncated` stays true (it's factually correct about the
     *     live-stats source) AND `queryText` now holds the FULL SQL —
     *     EXPLAIN will work.
     *   - Consumers should drop the "EXPLAIN will fail" warning that fires
     *     on bare sourceTruncated=true.
     *   - The chat / MCP output should note the recovery so the user
     *     understands which source the full text came from.
     *
     * When `sourceTruncated=true` AND this flag is false/null:
     *   - We couldn't recover. EXPLAIN against `queryText` will produce a
     *     partial plan or syntax error. The warning fires.
     */
    private Boolean queryTextRecoveredFromLogs;

    // Execution metrics
    private Double avgExecutionTimeMs;         // Average execution time
    private Double maxExecutionTimeMs;         // Worst case execution time
    private Double minExecutionTimeMs;         // Best case execution time
    private Double totalExecutionTimeMs;       // Total time spent on this query
    private Double stdDevExecutionTimeMs;      // Standard deviation

    // Call statistics
    private Long callCount;                    // Number of times executed
    private Double callsPerSecond;             // Execution frequency

    // Row metrics
    private Long rowsExamined;                 // Total rows scanned
    private Long rowsSent;                     // Total rows returned
    private Long avgRowsExamined;              // Avg rows per execution
    private Long avgRowsSent;                  // Avg rows returned per execution

    // Resource usage
    private Double avgCpuTimeMs;               // CPU time (if available)
    private Long avgMemoryBytes;               // Memory usage (if available)
    private Double cacheHitRatio;              // Buffer cache hit ratio (PostgreSQL)

    // Timing information
    private LocalDateTime firstSeen;           // When first detected
    private LocalDateTime lastSeen;            // Most recent execution
    private Long ageHours;                     // How long this query has been running

    // Analysis
    @Builder.Default
    private List<String> affectedTables = new ArrayList<>();
    private Boolean hasIndex;                  // Whether indexes are being used
    private Severity severity;
    private Double performanceImpact;          // Weighted score based on frequency × time

    // Optimization suggestions
    @Builder.Default
    private List<String> suggestions = new ArrayList<>();
    @Builder.Default
    private List<IndexRecommendation> suggestedIndexes = new ArrayList<>();

    // EXPLAIN plan analysis (if available)
    private ExplainPlanAnalysis explainAnalysis;

    // AI-generated optimization
    private String aiOptimization;

    /**
     * Calculate severity based on execution time and frequency
     */
    public Severity calculateSeverity() {
        if (avgExecutionTimeMs == null || callCount == null) {
            return Severity.LOW;
        }

        // High frequency queries
        if (callCount > 10000) {
            if (avgExecutionTimeMs > 1000) return Severity.CRITICAL;  // >1s, high freq
            if (avgExecutionTimeMs > 500) return Severity.HIGH;       // >500ms, high freq
            if (avgExecutionTimeMs > 100) return Severity.MEDIUM;     // >100ms, high freq
        }
        // Medium frequency queries
        else if (callCount > 1000) {
            if (avgExecutionTimeMs > 5000) return Severity.CRITICAL;  // >5s, med freq
            if (avgExecutionTimeMs > 1000) return Severity.HIGH;      // >1s, med freq
            if (avgExecutionTimeMs > 500) return Severity.MEDIUM;     // >500ms, med freq
        }
        // Low frequency queries
        else {
            if (avgExecutionTimeMs > 10000) return Severity.CRITICAL; // >10s
            if (avgExecutionTimeMs > 5000) return Severity.HIGH;      // >5s
            if (avgExecutionTimeMs > 1000) return Severity.MEDIUM;    // >1s
        }

        return Severity.LOW;
    }

    /**
     * Calculate performance impact score (frequency × avg time)
     */
    public Double calculatePerformanceImpact() {
        if (avgExecutionTimeMs == null || callCount == null) {
            return 0.0;
        }
        // Normalize to 0-100 scale
        double impact = (avgExecutionTimeMs / 1000.0) * Math.log10(callCount + 1);
        return Math.min(100.0, impact * 10);
    }

    /**
     * Get efficiency ratio (rows sent / rows examined)
     */
    public Double getEfficiencyRatio() {
        if (rowsExamined == null || rowsExamined == 0) {
            return 1.0;
        }
        if (rowsSent == null) {
            return 0.0;
        }
        return (double) rowsSent / (double) rowsExamined;
    }

    /**
     * Add an optimization suggestion
     */
    public void addSuggestion(String suggestion) {
        if (this.suggestions == null) {
            this.suggestions = new ArrayList<>();
        }
        this.suggestions.add(suggestion);
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
     * Get formatted summary
     */
    public String getSummary() {
        return String.format(
            "%s - Avg: %.2fms, Calls: %,d, Total: %.2fs, Severity: %s",
            queryId != null ? queryId.substring(0, Math.min(8, queryId.length())) : "unknown",
            avgExecutionTimeMs != null ? avgExecutionTimeMs : 0.0,
            callCount != null ? callCount : 0,
            totalExecutionTimeMs != null ? totalExecutionTimeMs / 1000.0 : 0.0,
            severity != null ? severity : "UNKNOWN"
        );
    }

    /**
     * Check if query is expensive (high impact)
     */
    public boolean isExpensive() {
        Double impact = calculatePerformanceImpact();
        return impact != null && impact > 50.0;
    }

    /**
     * Check if query has poor efficiency
     */
    public boolean hasPoorEfficiency() {
        Double ratio = getEfficiencyRatio();
        return ratio != null && ratio < 0.1;  // Less than 10% efficiency
    }
}
