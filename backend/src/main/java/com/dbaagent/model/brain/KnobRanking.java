package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Brain 2.0: Knob Ranking
 *
 * Ranked list of configuration parameters (knobs) by their impact on performance.
 * Uses Lasso regression to identify which knobs matter most for specific workloads.
 *
 * Inspired by OtterTune's knob identification component.
 */
@Entity
@Table(name = "knob_ranking", indexes = {
    @Index(name = "idx_knob_ranking_conn_metric", columnList = "connectionId,targetMetric,rank")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KnobRanking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    /**
     * Name of the configuration parameter (e.g., shared_buffers, innodb_buffer_pool_size).
     */
    @Column(nullable = false)
    private String knobName;

    /**
     * Database type.
     */
    @Column(nullable = false, length = 50)
    private String dbType;

    /**
     * Rank of this knob (1 = most impactful).
     */
    @Column(nullable = false)
    private Integer rank;

    /**
     * Impact score from Lasso coefficient magnitude.
     */
    @Column(nullable = false)
    private Double impactScore;

    /**
     * Target metric this ranking is for.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private TargetMetric targetMetric;

    /**
     * Current value of the knob.
     */
    @Column
    private String currentValue;

    /**
     * Default value of the knob.
     */
    @Column
    private String defaultValue;

    /**
     * Minimum allowed value.
     */
    @Column
    private String minValue;

    /**
     * Maximum allowed value.
     */
    @Column
    private String maxValue;

    /**
     * Type of the knob value.
     */
    @Enumerated(EnumType.STRING)
    @Column(length = 50)
    private KnobValueType valueType;

    /**
     * Whether changing this knob requires a restart.
     */
    @Column
    @Builder.Default
    private Boolean requiresRestart = false;

    /**
     * Confidence in the ranking.
     */
    @Column
    private Double confidenceScore;

    /**
     * Number of observations used for this ranking.
     */
    @Column
    private Integer sampleCount;

    @Column(nullable = false)
    private LocalDateTime calculatedAt;

    @PrePersist
    protected void onCreate() {
        if (calculatedAt == null) {
            calculatedAt = LocalDateTime.now();
        }
    }

    public enum TargetMetric {
        LATENCY,
        THROUGHPUT,
        CACHE_HIT_RATIO,
        IO_THROUGHPUT,
        MEMORY_USAGE
    }

    public enum KnobValueType {
        INTEGER,
        FLOAT,
        BOOLEAN,
        ENUM,
        MEMORY,     // Memory size (e.g., 256MB, 1GB)
        TIME,       // Time duration (e.g., 1s, 30min)
        STRING
    }
}
