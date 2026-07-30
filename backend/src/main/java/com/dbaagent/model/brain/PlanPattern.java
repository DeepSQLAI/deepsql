package com.dbaagent.model.brain;

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
 * Brain 2.0: Plan Pattern
 *
 * Library of query plan patterns with optimization suggestions.
 * Implements persistent memoization - plans and statistics are stored and
 * reused, enabling adaptivity through feedback from prior executions.
 *
 * Inspired by optd's plan memoization approach.
 */
@Entity
@Table(name = "plan_pattern", indexes = {
    @Index(name = "idx_plan_pattern_conn", columnList = "connectionId"),
    @Index(name = "idx_plan_pattern_signature", columnList = "planSignature"),
    @Index(name = "idx_plan_pattern_effectiveness", columnList = "effectivenessScore")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlanPattern {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    /**
     * Connection ID (null for global patterns).
     */
    @Column
    private String connectionId;

    /**
     * Normalized query pattern.
     */
    @Column(columnDefinition = "TEXT", nullable = false)
    private String queryPattern;

    /**
     * Hash of plan structure.
     */
    @Column(nullable = false, length = 64)
    private String planSignature;

    /**
     * Type of execution plan pattern.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private PatternType patternType;

    /**
     * Tables involved in this pattern.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> tablesInvolved;

    /**
     * Join types used in the plan.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> joinTypes;

    /**
     * Cached optimization suggestions.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Map<String, Object>> suggestions;

    /**
     * Optimized query if available.
     */
    @Column(columnDefinition = "TEXT")
    private String optimizedQuery;

    /**
     * Effectiveness score based on feedback (0-100).
     */
    @Column
    private Double effectivenessScore;

    /**
     * Number of times this pattern was matched.
     */
    @Column
    @Builder.Default
    private Integer usageCount = 1;

    /**
     * Number of times suggestions were successfully applied.
     */
    @Column
    @Builder.Default
    private Integer successCount = 0;

    /**
     * AI-generated explanation of the pattern and recommendations.
     */
    @Column(columnDefinition = "TEXT")
    private String explanation;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime lastUsedAt;

    @Column
    private LocalDateTime lastValidatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    /**
     * Record a usage of this pattern.
     */
    public void recordUsage() {
        this.usageCount++;
        this.lastUsedAt = LocalDateTime.now();
    }

    /**
     * Record a successful application.
     */
    public void recordSuccess() {
        this.successCount++;
        updateEffectivenessScore();
    }

    /**
     * Update effectiveness score based on success rate.
     */
    private void updateEffectivenessScore() {
        if (usageCount > 0) {
            this.effectivenessScore = (double) successCount / usageCount * 100;
        }
    }

    /**
     * Check if this pattern is reliable (enough data and good effectiveness).
     */
    public boolean isReliable() {
        return usageCount >= 5 && effectivenessScore != null && effectivenessScore >= 60;
    }

    public enum PatternType {
        SEQUENTIAL_SCAN,    // Full table scan
        INDEX_SCAN,         // Index-based access
        INDEX_ONLY_SCAN,    // Covering index scan
        NESTED_LOOP,        // Nested loop join
        HASH_JOIN,          // Hash-based join
        MERGE_JOIN,         // Sort-merge join
        SORT,               // Explicit sorting
        AGGREGATE,          // Aggregation operation
        SUBQUERY,           // Subquery execution
        CTE,                // Common Table Expression
        MIXED               // Complex mixed pattern
    }
}
