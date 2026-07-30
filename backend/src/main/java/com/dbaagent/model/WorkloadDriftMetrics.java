package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Workload Drift Metrics Entity
 * Captures workload patterns and invisible growth indicators
 * Detects when row counts remain stable but I/O or size increases (bloat/fragmentation)
 */
@Entity
@Table(name = "workload_drift_metrics", indexes = {
    @Index(name = "idx_drift_connection_table", columnList = "connection_id, table_name"),
    @Index(name = "idx_drift_timestamp", columnList = "measurement_timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkloadDriftMetrics {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "measurement_timestamp", nullable = false)
    private LocalDateTime measurementTimestamp;

    // Workload metrics
    @Column(name = "query_count_per_hour")
    private Long queryCountPerHour;

    @Column(name = "avg_query_latency_ms")
    private Double avgQueryLatencyMs;

    @Column(name = "p95_query_latency_ms")
    private Double p95QueryLatencyMs;

    @Column(name = "p99_query_latency_ms")
    private Double p99QueryLatencyMs;

    // I/O metrics
    @Column(name = "read_iops")
    private Integer readIops;

    @Column(name = "write_iops")
    private Integer writeIops;

    @Column(name = "read_throughput_mb")
    private Double readThroughputMb;

    @Column(name = "write_throughput_mb")
    private Double writeThroughputMb;

    // Drift indicators (Invisible Growth)
    @Column(name = "size_to_row_ratio")
    private Double sizeToRowRatio;  // Bytes per row - increases indicate bloat

    @Column(name = "index_to_data_ratio")
    private Double indexToDataRatio;  // Index size / Data size

    @Column(name = "dead_tuple_percent")
    private Double deadTuplePercent;  // For Postgres bloat detection

    @Column(name = "fragmentation_percent")
    private Double fragmentationPercent;  // For MySQL/SQL Server

    // Invisible growth
    @Column(name = "wal_binlog_size_bytes")
    private Long walBinlogSizeBytes;  // Write-ahead logs / Binary logs

    @Column(name = "temp_table_size_bytes")
    private Long tempTableSizeBytes;

    @Column(name = "system_catalog_size_bytes")
    private Long systemCatalogSizeBytes;

    // Cache efficiency
    @Column(name = "cache_hit_ratio")
    private Double cacheHitRatio;

    @Column(name = "buffer_pool_hit_ratio")
    private Double bufferPoolHitRatio;

    // Metadata
    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (measurementTimestamp == null) {
            measurementTimestamp = LocalDateTime.now();
        }
    }

    /**
     * Detect if workload drift is present
     * (Performance degradation without proportional row count increase)
     */
    public boolean hasWorkloadDrift(WorkloadDriftMetrics previous) {
        if (previous == null) {
            return false;
        }

        // Check for latency increase without query count increase
        if (avgQueryLatencyMs != null && previous.avgQueryLatencyMs != null) {
            double latencyIncrease = ((avgQueryLatencyMs - previous.avgQueryLatencyMs) / previous.avgQueryLatencyMs) * 100;

            if (latencyIncrease > 20) {  // >20% latency increase
                if (queryCountPerHour != null && previous.queryCountPerHour != null) {
                    double queryIncrease = ((queryCountPerHour - previous.queryCountPerHour) / (double) previous.queryCountPerHour) * 100;
                    if (queryIncrease < 5) {  // But <5% query increase
                        return true;  // Drift detected
                    }
                }
            }
        }

        return false;
    }

    /**
     * Detect bloat based on size-to-row ratio increase
     */
    public boolean hasBloat(WorkloadDriftMetrics previous) {
        if (previous == null || sizeToRowRatio == null || previous.sizeToRowRatio == null) {
            return false;
        }

        double ratioIncrease = ((sizeToRowRatio - previous.sizeToRowRatio) / previous.sizeToRowRatio) * 100;
        return ratioIncrease > 25;  // >25% increase in bytes-per-row = bloat
    }

    /**
     * Detect fragmentation issues
     */
    public boolean hasFragmentation() {
        return fragmentationPercent != null && fragmentationPercent > 30.0;  // >30% fragmentation
    }

    /**
     * Detect index sprawl (too many indexes)
     */
    public boolean hasIndexSprawl() {
        return indexToDataRatio != null && indexToDataRatio > 1.5;  // Indexes > 1.5x data size
    }

    /**
     * Check cache efficiency
     */
    public boolean hasPoorCachePerformance() {
        return (cacheHitRatio != null && cacheHitRatio < 0.9) ||  // <90% cache hit ratio
               (bufferPoolHitRatio != null && bufferPoolHitRatio < 0.9);
    }

    /**
     * Get overall drift score (0-100, higher = worse)
     */
    public Integer getDriftScore() {
        int score = 0;

        // Fragmentation penalty
        if (fragmentationPercent != null) {
            score += Math.min((int) (fragmentationPercent / 2), 30);
        }

        // Index sprawl penalty
        if (indexToDataRatio != null && indexToDataRatio > 1.0) {
            score += Math.min((int) ((indexToDataRatio - 1.0) * 20), 25);
        }

        // Dead tuples penalty (Postgres)
        if (deadTuplePercent != null) {
            score += Math.min((int) (deadTuplePercent / 2), 20);
        }

        // Cache performance penalty
        if (cacheHitRatio != null && cacheHitRatio < 0.9) {
            score += (int) ((0.9 - cacheHitRatio) * 100);
        }

        // Latency penalty
        if (p99QueryLatencyMs != null && p99QueryLatencyMs > 1000) {  // >1 second P99
            score += Math.min((int) (p99QueryLatencyMs / 100), 25);
        }

        return Math.min(score, 100);
    }

    /**
     * Get human-readable drift summary
     */
    public String getDriftSummary() {
        StringBuilder summary = new StringBuilder();

        if (hasFragmentation()) {
            summary.append(String.format("Fragmentation: %.1f%%. ", fragmentationPercent));
        }

        if (hasIndexSprawl()) {
            summary.append(String.format("Index sprawl: %.1fx data size. ", indexToDataRatio));
        }

        if (deadTuplePercent != null && deadTuplePercent > 10) {
            summary.append(String.format("Dead tuples: %.1f%%. ", deadTuplePercent));
        }

        if (hasPoorCachePerformance()) {
            summary.append("Low cache hit ratio. ");
        }

        return summary.length() > 0 ? summary.toString().trim() : "No significant drift detected";
    }
}
