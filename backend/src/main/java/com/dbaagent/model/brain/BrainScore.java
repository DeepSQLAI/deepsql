package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.Map;

/**
 * Entity for storing Brain health scores.
 * Implements BRAIN-design.md Section 11: "Scoring Model"
 *
 * Brain Score = Weighted aggregate of 4 sub-scores:
 * - Schema Design Score (25% weight)
 * - Query Quality Score (25% weight)
 * - Index & Access Score (30% weight) ← Heavily weighted
 * - Scalability Score (20% weight)
 */
@Entity
@Table(name = "brain_score", indexes = {
    @Index(name = "idx_brain_score_connection", columnList = "connectionId"),
    @Index(name = "idx_brain_score_date", columnList = "connectionId,calculatedAt"),
    @Index(name = "idx_brain_score_overall", columnList = "connectionId,overallScore")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainScore {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, length = 255)
    private String connectionId;

    /**
     * Overall Brain Score (0-100).
     * Weighted aggregate of all sub-scores.
     */
    @Column(nullable = false, precision = 5, scale = 2)
    private BigDecimal overallScore;

    /**
     * Schema Design Score (0-100).
     * Measures structural soundness, normalization, constraint usage.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal schemaDesignScore;

    /**
     * Query Quality Score (0-100).
     * Measures query efficiency, anti-patterns, optimization.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal queryQualityScore;

    /**
     * Index & Access Score (0-100).
     * Measures index coverage, workload alignment, key columns optimization.
     * Key Columns Analysis feeds into this score.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal indexAccessScore;

    /**
     * Scalability Score (0-100).
     * Measures readiness for future growth, partitioning, query complexity.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal scalabilityScore;

    // Brain 2.0 Score Components

    /**
     * Config Tuning Score (0-100).
     * Measures optimization level of database configuration.
     * Based on workload characterization, knob identification, and experiment results.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal configTuningScore;

    /**
     * Query Intelligence Score (0-100).
     * Measures cardinality estimation accuracy and plan pattern effectiveness.
     * Based on adaptive learning from query executions.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal queryIntelligenceScore;

    /**
     * Workload Understanding Score (0-100).
     * Measures how well we understand the database workload.
     * Based on workload characterization confidence and fingerprint stability.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal workloadUnderstandingScore;

    /**
     * Learning Progress Score (0-100).
     * Measures overall Brain 2.0 learning progress.
     * Based on data collection, model training, and feedback incorporation.
     */
    @Column(precision = 5, scale = 2)
    private BigDecimal learningProgressScore;

    /**
     * When this score was calculated.
     */
    @Column
    private LocalDateTime calculatedAt;

    /**
     * Number of tables analyzed.
     */
    @Column
    private Integer tablesAnalyzed;

    /**
     * Number of columns analyzed.
     */
    @Column
    private Integer columnsAnalyzed;

    /**
     * Number of queries analyzed.
     */
    @Column
    private Integer queriesAnalyzed;

    /**
     * JSON breakdown explaining how scores were calculated.
     * Enables transparency and explainability.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> scoreBreakdown;

    @Column
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (calculatedAt == null) {
            calculatedAt = LocalDateTime.now();
        }
    }
}
