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
 * Brain 2.0: Workload Profile
 *
 * Characterization of a connection's workload based on collected metrics.
 * Used for knowledge transfer between similar workloads (OtterTune approach).
 */
@Entity
@Table(name = "workload_profile", indexes = {
    @Index(name = "idx_workload_profile_conn", columnList = "connectionId"),
    @Index(name = "idx_workload_profile_type", columnList = "workloadType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadProfile {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false, unique = true)
    private String connectionId;

    /**
     * Workload classification.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private WorkloadType workloadType;

    /**
     * More specific workload subtype.
     */
    @Column(length = 100)
    private String workloadSubtype;

    /**
     * Confidence in the classification (0-100).
     */
    @Column
    private Double classificationConfidence;

    /**
     * Normalized fingerprint vector for similarity matching.
     * Vector of 8-12 reduced metrics from factor analysis.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<Double> fingerprintVector;

    /**
     * Best-performing configuration discovered for this workload.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, String> optimalConfig;

    /**
     * Performance score achieved with optimal config (0-100).
     */
    @Column
    private Double performanceScore;

    /**
     * Latency metrics achieved.
     */
    @Column
    private Double latencyP50Ms;

    @Column
    private Double latencyP99Ms;

    /**
     * Throughput achieved (queries per second).
     */
    @Column
    private Double throughputQps;

    /**
     * Factor analysis results - which original metrics contribute to each factor.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, List<String>> factorLoadings;

    /**
     * Representative metrics selected after k-means clustering.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> selectedMetrics;

    /**
     * Actual values of key metrics used for classification.
     * Maps metric name to its value (e.g., "read_write_ratio" -> 0.85)
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> keyMetricValues;

    /**
     * Human-readable explanation of why this workload was classified this way.
     */
    @Column(columnDefinition = "text")
    private String classificationReasoning;

    @Column(nullable = false)
    private LocalDateTime profiledAt;

    @Column
    private LocalDateTime lastUpdatedAt;

    @PrePersist
    protected void onCreate() {
        if (profiledAt == null) {
            profiledAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        lastUpdatedAt = LocalDateTime.now();
    }

    /**
     * Workload type classification.
     */
    public enum WorkloadType {
        OLTP,           // Transaction processing (high concurrency, short queries)
        OLAP,           // Analytical processing (complex queries, aggregations)
        MIXED,          // Combination of OLTP and OLAP
        WRITE_HEAVY,    // Predominantly write operations
        READ_HEAVY,     // Predominantly read operations
        BATCH,          // Batch processing workloads
        REAL_TIME,      // Real-time streaming workloads
        UNKNOWN         // Not yet classified
    }
}
