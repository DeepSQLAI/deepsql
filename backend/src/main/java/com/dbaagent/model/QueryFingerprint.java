package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Entity for tracking query fingerprints (normalized query patterns) over time.
 * Enables trend analysis and performance regression detection.
 */
@Entity
@Table(name = "query_fingerprints", indexes = {
    @Index(name = "idx_qf_connection_fingerprint", columnList = "connectionId, fingerprint"),
    @Index(name = "idx_qf_connection_updated", columnList = "connectionId, lastSeenAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryFingerprint {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    /**
     * Hash of the normalized query (query with literals replaced)
     */
    @Column(nullable = false)
    private String fingerprint;

    /**
     * The normalized query text with placeholders
     */
    @Column(name = "normalized_query", columnDefinition = "TEXT")
    private String normalizedQuery;

    /**
     * Version of the normalization ruleset used to derive {@link #fingerprint}.
     * If the {@code QueryNormalizer} rules ever change, bumping this prevents a
     * silently-forked identity: old rows keep version 1, new rows get the new
     * version, and consumers can tell which generation a fingerprint belongs to.
     */
    @Column(name = "normalization_version", nullable = false)
    @Builder.Default
    private Integer normalizationVersion = 1;

    /**
     * Sample of the actual query for reference
     */
    @Column(name = "sample_query", columnDefinition = "TEXT")
    private String sampleQuery;

    /**
     * Tables accessed by this query
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "affected_tables", columnDefinition = "JSON")
    private List<String> affectedTables;

    /**
     * Query type: SELECT, INSERT, UPDATE, DELETE, etc.
     */
    @Column(name = "query_type", length = 20)
    private String queryType;

    // Current metrics
    @Column(name = "current_avg_time_ms")
    private Double currentAvgTimeMs;

    @Column(name = "current_max_time_ms")
    private Double currentMaxTimeMs;

    @Column(name = "current_call_count")
    private Long currentCallCount;

    @Column(name = "current_rows_examined")
    private Long currentRowsExamined;

    @Column(name = "current_rows_sent")
    private Long currentRowsSent;

    // Baseline metrics (for comparison)
    @Column(name = "baseline_avg_time_ms")
    private Double baselineAvgTimeMs;

    @Column(name = "baseline_max_time_ms")
    private Double baselineMaxTimeMs;

    @Column(name = "baseline_call_count")
    private Long baselineCallCount;

    @Column(name = "baseline_date")
    private LocalDateTime baselineDate;

    // Historical metrics (last N data points)
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "performance_history", columnDefinition = "JSON")
    private List<Map<String, Object>> performanceHistory;

    // Trend analysis
    @Column(name = "trend_direction", length = 20)
    @Enumerated(EnumType.STRING)
    private TrendDirection trendDirection;

    @Column(name = "trend_percentage")
    private Double trendPercentage;

    @Column(name = "is_regressing")
    private Boolean isRegressing;

    @Column(name = "regression_detected_at")
    private LocalDateTime regressionDetectedAt;

    // Metadata
    @Column(name = "first_seen_at")
    private LocalDateTime firstSeenAt;

    @Column(name = "last_seen_at")
    private LocalDateTime lastSeenAt;

    @Column(name = "observation_count")
    private Integer observationCount;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum TrendDirection {
        IMPROVING,
        STABLE,
        DEGRADING,
        CRITICAL
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (firstSeenAt == null) {
            firstSeenAt = LocalDateTime.now();
        }
        lastSeenAt = LocalDateTime.now();
        updatedAt = LocalDateTime.now();
        if (observationCount == null) {
            observationCount = 1;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
        lastSeenAt = LocalDateTime.now();
    }

    /**
     * Calculate trend based on current vs baseline performance
     */
    public void calculateTrend() {
        if (baselineAvgTimeMs == null || currentAvgTimeMs == null || baselineAvgTimeMs == 0) {
            this.trendDirection = TrendDirection.STABLE;
            this.trendPercentage = 0.0;
            return;
        }

        double change = ((currentAvgTimeMs - baselineAvgTimeMs) / baselineAvgTimeMs) * 100;
        this.trendPercentage = change;

        if (change < -10) {
            this.trendDirection = TrendDirection.IMPROVING;
            this.isRegressing = false;
        } else if (change > 100) {
            this.trendDirection = TrendDirection.CRITICAL;
            this.isRegressing = true;
            if (this.regressionDetectedAt == null) {
                this.regressionDetectedAt = LocalDateTime.now();
            }
        } else if (change > 50) {
            this.trendDirection = TrendDirection.DEGRADING;
            this.isRegressing = true;
            if (this.regressionDetectedAt == null) {
                this.regressionDetectedAt = LocalDateTime.now();
            }
        } else {
            this.trendDirection = TrendDirection.STABLE;
            this.isRegressing = false;
            this.regressionDetectedAt = null;
        }
    }

    /**
     * Update metrics from a slow query observation
     */
    public void updateFromSlowQuery(SlowQuery query) {
        this.currentAvgTimeMs = query.getAvgExecutionTimeMs();
        this.currentMaxTimeMs = query.getMaxExecutionTimeMs();
        this.currentCallCount = query.getCallCount();
        this.currentRowsExamined = query.getRowsExamined();
        this.currentRowsSent = query.getRowsSent();
        this.sampleQuery = query.getSampleQuery() != null ? query.getSampleQuery() : query.getQueryText();
        this.observationCount = (this.observationCount != null ? this.observationCount : 0) + 1;

        // Set baseline on first observation
        if (this.baselineAvgTimeMs == null) {
            this.baselineAvgTimeMs = query.getAvgExecutionTimeMs();
            this.baselineMaxTimeMs = query.getMaxExecutionTimeMs();
            this.baselineCallCount = query.getCallCount();
            this.baselineDate = LocalDateTime.now();
        }

        calculateTrend();
    }
}
