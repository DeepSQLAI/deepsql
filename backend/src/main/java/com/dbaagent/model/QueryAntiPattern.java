package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * Entity for storing query quality anti-patterns.
 * Implements BRAIN-design.md Section 8: "Query Quality Analysis"
 *
 * Detected patterns:
 * - FUNCTION_ON_FILTER: Functions on indexed columns (e.g., WHERE DATE(created_at) = ...)
 * - SELECT_STAR: SELECT * in analytical queries
 * - DISTINCT_ABUSE: DISTINCT masking JOIN errors
 * - JOIN_WITHOUT_PREDICATE: Cartesian products
 * - HIGH_EXAMINATION_RATIO: Too many rows examined vs returned
 * - MISSING_INDEX: Full table scan when index would help
 * - SUBQUERY_IN_SELECT: Correlated subquery in SELECT list
 */
@Entity
@Table(name = "query_anti_pattern", indexes = {
    @Index(name = "idx_query_anti_pattern_connection", columnList = "connectionId"),
    @Index(name = "idx_query_anti_pattern_type", columnList = "connectionId,patternType"),
    @Index(name = "idx_query_anti_pattern_severity", columnList = "connectionId,severity"),
    @Index(name = "idx_query_anti_pattern_impact", columnList = "connectionId,totalExecutionTimeMs")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryAntiPattern {

    public enum Severity {
        CRITICAL, HIGH, MEDIUM, LOW
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String queryFingerprint;  // Normalized pattern

    @Column(columnDefinition = "TEXT")
    private String queryText;  // Sample query

    @Column(nullable = false, length = 100)
    private String patternType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Severity severity;

    // Occurrence stats
    @Column
    @Builder.Default
    private Integer occurrences = 1;

    @Column
    private Long avgExecutionTimeMs;

    @Column
    private Long totalExecutionTimeMs;

    // Impact metrics
    @Column
    private Long rowsExamined;

    @Column
    private Long rowsReturned;

    @Column(precision = 10, scale = 2)
    private BigDecimal examinationRatio;  // rowsExamined / rowsReturned

    // Detection details
    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String recommendation;

    @Column(columnDefinition = "TEXT")
    private String exampleQuery;

    // Timestamps
    @Column
    private LocalDateTime firstDetectedAt;

    @Column
    private LocalDateTime lastDetectedAt;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (firstDetectedAt == null) {
            firstDetectedAt = LocalDateTime.now();
        }
        if (lastDetectedAt == null) {
            lastDetectedAt = LocalDateTime.now();
        }
    }
}
