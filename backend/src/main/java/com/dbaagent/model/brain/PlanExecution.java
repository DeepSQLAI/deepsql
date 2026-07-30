package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Brain 2.0: Plan Execution
 *
 * Tracks actual execution vs estimated for adaptive plan learning.
 * Inspired by optd's approach of capturing runtime information to guide
 * subsequent plan space searches and cost model estimations.
 */
@Entity
@Table(name = "plan_execution", indexes = {
    @Index(name = "idx_plan_exec_conn_fingerprint", columnList = "connectionId,queryFingerprint"),
    @Index(name = "idx_plan_exec_signature", columnList = "planSignature"),
    @Index(name = "idx_plan_exec_time", columnList = "executedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanExecution {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    /**
     * Normalized query hash for grouping similar queries.
     */
    @Column(nullable = false, length = 64)
    private String queryFingerprint;

    /**
     * Original query text.
     */
    @Column(columnDefinition = "TEXT")
    private String queryText;

    /**
     * Normalized query (literals replaced with ?).
     */
    @Column(columnDefinition = "TEXT")
    private String normalizedQuery;

    /**
     * Hash of plan structure for plan comparison.
     */
    @Column(nullable = false, length = 64)
    private String planSignature;

    /**
     * Full plan as JSON.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> planJson;

    // Estimated values from EXPLAIN
    @Column
    private Double estimatedCost;

    @Column
    private Long estimatedRows;

    @Column
    private Double estimatedStartupCost;

    // Actual values from execution
    @Column
    private Double actualExecutionMs;

    @Column
    private Long actualRows;

    @Column
    private Integer actualLoops;

    // Buffer statistics
    @Column
    private Long sharedHitBlocks;

    @Column
    private Long sharedReadBlocks;

    @Column
    private Long tempReadBlocks;

    @Column
    private Long tempWrittenBlocks;

    /**
     * Cardinality estimation error ratio (actual/estimated).
     * > 1 means underestimate, < 1 means overestimate.
     */
    @Column
    private Double cardinalityErrorRatio;

    @Column(nullable = false)
    private LocalDateTime executedAt;

    /**
     * Source of execution data.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private ExecutionSource executionSource;

    @PrePersist
    protected void onCreate() {
        if (executedAt == null) {
            executedAt = LocalDateTime.now();
        }
        calculateCardinalityError();
    }

    /**
     * Calculate cardinality error ratio.
     */
    public void calculateCardinalityError() {
        if (actualRows != null && estimatedRows != null && estimatedRows > 0) {
            this.cardinalityErrorRatio = (double) actualRows / estimatedRows;
        }
    }

    /**
     * Check if there's a significant cardinality estimation error.
     */
    public boolean hasSignificantError() {
        if (cardinalityErrorRatio == null) {
            return false;
        }
        // Error is significant if off by more than 10x
        return cardinalityErrorRatio > 10 || cardinalityErrorRatio < 0.1;
    }

    /**
     * Get the cost estimation accuracy.
     */
    public Double getCostAccuracy() {
        if (estimatedCost == null || actualExecutionMs == null || estimatedCost == 0) {
            return null;
        }
        // Normalize: lower ratio means better correlation
        return actualExecutionMs / estimatedCost;
    }

    public enum ExecutionSource {
        EXPLAIN_ANALYZE,    // From EXPLAIN ANALYZE
        SLOW_LOG,           // From slow query log
        MONITORING,         // From pg_stat_statements or performance_schema
        MANUAL              // Manual observation
    }
}
