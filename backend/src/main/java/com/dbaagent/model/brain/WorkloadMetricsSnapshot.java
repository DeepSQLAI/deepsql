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
 * Brain 2.0: Workload Metrics Snapshot
 *
 * Stores raw metrics collected from the database for workload characterization.
 * Inspired by OtterTune's approach of collecting 100+ internal metrics.
 *
 * PostgreSQL sources: pg_stat_statements, pg_stat_bgwriter, pg_stat_database, etc.
 * MySQL sources: performance_schema, information_schema, SHOW STATUS, etc.
 */
@Entity
@Table(name = "workload_metrics_snapshot", indexes = {
    @Index(name = "idx_workload_metrics_conn_time", columnList = "connectionId,collectedAt"),
    @Index(name = "idx_workload_metrics_fingerprint", columnList = "workloadFingerprint")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadMetricsSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private LocalDateTime collectedAt;

    /**
     * Raw metrics as JSON (131+ dimensions for PostgreSQL, 100+ for MySQL).
     * Contains all collected internal database metrics.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> rawMetrics;

    /**
     * Reduced metrics after factor analysis (typically 8-12 dimensions).
     * These are the most important metrics for workload characterization.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Double> reducedMetrics;

    /**
     * Workload fingerprint - hash of reduced metrics for similarity matching.
     */
    @Column(length = 64)
    private String workloadFingerprint;

    /**
     * Database type (postgres, mysql).
     */
    @Column(nullable = false, length = 50)
    private String dbType;

    /**
     * Time taken to collect metrics in milliseconds.
     */
    @Column
    private Integer collectionDurationMs;

    /**
     * Number of metrics collected.
     */
    @Column
    private Integer metricsCount;

    @PrePersist
    protected void onCreate() {
        if (collectedAt == null) {
            collectedAt = LocalDateTime.now();
        }
    }
}
