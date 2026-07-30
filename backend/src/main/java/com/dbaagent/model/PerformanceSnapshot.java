package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Performance Snapshot Entity
 * Stores database performance metrics collected every 5 minutes
 * Similar to AWS RDS Performance Insights snapshots
 */
@Entity
@Table(name = "performance_snapshots", indexes = {
    @Index(name = "idx_perf_connection_time", columnList = "connection_id, snapshot_time"),
    @Index(name = "idx_perf_time", columnList = "snapshot_time")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(name = "snapshot_time", nullable = false)
    private LocalDateTime snapshotTime;

    // === TOP SQL METRICS ===

    /**
     * Top queries data as JSON
     * Array of: { queryId, normalizedQuery, totalTime, callCount, avgTime, maxTime, rowsExamined }
     */
    @Column(name = "top_queries", columnDefinition = "TEXT")
    private String topQueries;

    @Column(name = "total_query_count")
    private Integer totalQueryCount;

    @Column(name = "total_db_time_ms")
    private Double totalDbTimeMs;

    // === WAIT EVENTS ===

    /**
     * Wait events data as JSON
     * Array of: { eventName, waitTime, waitCount, avgWaitTime }
     */
    @Column(name = "wait_events", columnDefinition = "TEXT")
    private String waitEvents;

    @Column(name = "cpu_wait_ms")
    private Double cpuWaitMs;

    @Column(name = "io_wait_ms")
    private Double ioWaitMs;

    @Column(name = "lock_wait_ms")
    private Double lockWaitMs;

    // === DATABASE METRICS ===

    @Column(name = "cpu_percent")
    private Double cpuPercent;

    @Column(name = "memory_used_mb")
    private Double memoryUsedMb;

    @Column(name = "memory_total_mb")
    private Double memoryTotalMb;

    @Column(name = "active_connections")
    private Integer activeConnections;

    @Column(name = "total_connections")
    private Integer totalConnections;

    @Column(name = "iops_read")
    private Double iopsRead;

    @Column(name = "iops_write")
    private Double iopsWrite;

    @Column(name = "network_receive_mb")
    private Double networkReceiveMb;

    @Column(name = "network_transmit_mb")
    private Double networkTransmitMb;

    // === QUERY LOAD BREAKDOWN ===

    @Column(name = "select_load_ms")
    private Double selectLoadMs;

    @Column(name = "insert_load_ms")
    private Double insertLoadMs;

    @Column(name = "update_load_ms")
    private Double updateLoadMs;

    @Column(name = "delete_load_ms")
    private Double deleteLoadMs;

    // === AWS CLOUDWATCH STYLE METRICS ===

    // Deadlocks
    @Column(name = "deadlocks_per_minute")
    private Double deadlocksPerMinute;

    // Swap and Disk Usage
    @Column(name = "swap_usage_bytes")
    private Long swapUsageBytes;

    @Column(name = "binlog_disk_usage_bytes")
    private Long binlogDiskUsageBytes;

    // Sessions and Connections
    @Column(name = "sessions_count")
    private Integer sessionsCount;

    @Column(name = "logins_count")
    private Integer loginsCount;

    @Column(name = "aborted_connections")
    private Integer abortedConnections;

    // InnoDB Metrics
    @Column(name = "innodb_history_list_length")
    private Long innodbHistoryListLength;

    @Column(name = "innodb_row_lock_time_ms")
    private Double innodbRowLockTimeMs;

    @Column(name = "innodb_row_lock_waits")
    private Long innodbRowLockWaits;

    @Column(name = "buffer_pool_hit_ratio")
    private Double bufferPoolHitRatio;

    @Column(name = "buffer_pool_pages_total")
    private Long bufferPoolPagesTotal;

    @Column(name = "buffer_pool_pages_free")
    private Long bufferPoolPagesFree;

    @Column(name = "buffer_pool_pages_dirty")
    private Long bufferPoolPagesDirty;

    // Query Throughput
    @Column(name = "queries_per_second")
    private Double queriesPerSecond;

    @Column(name = "select_per_second")
    private Double selectPerSecond;

    @Column(name = "insert_per_second")
    private Double insertPerSecond;

    @Column(name = "update_per_second")
    private Double updatePerSecond;

    @Column(name = "delete_per_second")
    private Double deletePerSecond;

    // DML Rows Affected
    @Column(name = "innodb_rows_inserted")
    private Long innodbRowsInserted;

    @Column(name = "innodb_rows_updated")
    private Long innodbRowsUpdated;

    @Column(name = "innodb_rows_deleted")
    private Long innodbRowsDeleted;

    @Column(name = "innodb_rows_read")
    private Long innodbRowsRead;

    // Transactions and Locks
    @Column(name = "active_transactions")
    private Integer activeTransactions;

    @Column(name = "locked_transactions")
    private Integer lockedTransactions;

    // IO Statistics
    @Column(name = "innodb_buffer_pool_reads")
    private Long innodbBufferPoolReads;

    @Column(name = "innodb_buffer_pool_read_requests")
    private Long innodbBufferPoolReadRequests;

    @Column(name = "innodb_data_reads")
    private Long innodbDataReads;

    @Column(name = "innodb_data_writes")
    private Long innodbDataWrites;

    // IO Cache vs Disk Reads (Pages per second)
    @Column(name = "cache_hit_pages_per_sec")
    private Double cacheHitPagesPerSec;

    @Column(name = "disk_read_pages_per_sec")
    private Double diskReadPagesPerSec;

    // === METADATA ===

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (snapshotTime == null) {
            snapshotTime = LocalDateTime.now();
        }
    }

    /**
     * Calculate memory utilization percentage
     */
    public Double getMemoryPercent() {
        if (memoryUsedMb == null || memoryTotalMb == null || memoryTotalMb == 0) {
            return null;
        }
        return (memoryUsedMb / memoryTotalMb) * 100.0;
    }

    /**
     * Calculate connection utilization percentage
     */
    public Double getConnectionPercent() {
        if (activeConnections == null || totalConnections == null || totalConnections == 0) {
            return null;
        }
        return (activeConnections.doubleValue() / totalConnections) * 100.0;
    }

    /**
     * Get total IOPS
     */
    public Double getTotalIops() {
        double read = iopsRead != null ? iopsRead : 0.0;
        double write = iopsWrite != null ? iopsWrite : 0.0;
        return read + write;
    }

    /**
     * Get total network throughput in MB
     */
    public Double getTotalNetworkMb() {
        double receive = networkReceiveMb != null ? networkReceiveMb : 0.0;
        double transmit = networkTransmitMb != null ? networkTransmitMb : 0.0;
        return receive + transmit;
    }
}
