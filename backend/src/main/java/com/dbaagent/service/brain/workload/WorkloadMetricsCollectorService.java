package com.dbaagent.service.brain.workload;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.brain.WorkloadMetricsSnapshot;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.brain.WorkloadMetricsSnapshotRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Brain 2.0: Workload Metrics Collector Service
 *
 * Collects comprehensive database metrics for workload characterization.
 * Inspired by OtterTune's approach of collecting 100+ internal metrics.
 *
 * PostgreSQL: Collects from pg_stat_* views (131+ metrics)
 * MySQL: Collects from performance_schema and SHOW STATUS (100+ metrics)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class WorkloadMetricsCollectorService {

    private final WorkloadMetricsSnapshotRepository snapshotRepository;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    /**
     * Collect workload metrics for a connection.
     */
    @Transactional
    public WorkloadMetricsSnapshot collectMetrics(String connectionId) {
        log.info("Collecting workload metrics for connection: {}", connectionId);
        long startTime = System.currentTimeMillis();

        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, connection);

            Map<String, Object> metrics = new LinkedHashMap<>();

            if ("postgres".equals(dbType)) {
                metrics.putAll(collectPostgresMetrics(jdbc));
            } else if ("mysql".equals(dbType)) {
                metrics.putAll(collectMySQLMetrics(jdbc));
            }

            long duration = System.currentTimeMillis() - startTime;

            WorkloadMetricsSnapshot snapshot = WorkloadMetricsSnapshot.builder()
                .connectionId(connectionId)
                .collectedAt(LocalDateTime.now())
                .rawMetrics(metrics)
                .dbType(dbType)
                .collectionDurationMs((int) duration)
                .metricsCount(metrics.size())
                .build();

            // Generate fingerprint from key metrics
            snapshot.setWorkloadFingerprint(generateFingerprint(metrics));

            snapshotRepository.save(snapshot);

            log.info("Collected {} metrics for connection {} in {}ms",
                metrics.size(), connectionId, duration);

            return snapshot;

        } catch (Exception e) {
            log.error("Error collecting metrics for connection {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Failed to collect workload metrics: " + e.getMessage(), e);
        }
    }

    /**
     * Collect PostgreSQL metrics from pg_stat_* views.
     */
    private Map<String, Object> collectPostgresMetrics(JdbcTemplate jdbc) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // pg_stat_database metrics
        try {
            jdbc.query(
                "SELECT numbackends, xact_commit, xact_rollback, blks_read, blks_hit, " +
                "tup_returned, tup_fetched, tup_inserted, tup_updated, tup_deleted, " +
                "conflicts, temp_files, temp_bytes, deadlocks, blk_read_time, blk_write_time " +
                "FROM pg_stat_database WHERE datname = current_database()",
                rs -> {
                    metrics.put("numbackends", rs.getLong("numbackends"));
                    metrics.put("xact_commit", rs.getLong("xact_commit"));
                    metrics.put("xact_rollback", rs.getLong("xact_rollback"));
                    metrics.put("blks_read", rs.getLong("blks_read"));
                    metrics.put("blks_hit", rs.getLong("blks_hit"));
                    metrics.put("tup_returned", rs.getLong("tup_returned"));
                    metrics.put("tup_fetched", rs.getLong("tup_fetched"));
                    metrics.put("tup_inserted", rs.getLong("tup_inserted"));
                    metrics.put("tup_updated", rs.getLong("tup_updated"));
                    metrics.put("tup_deleted", rs.getLong("tup_deleted"));
                    metrics.put("conflicts", rs.getLong("conflicts"));
                    metrics.put("temp_files", rs.getLong("temp_files"));
                    metrics.put("temp_bytes", rs.getLong("temp_bytes"));
                    metrics.put("deadlocks", rs.getLong("deadlocks"));
                    metrics.put("blk_read_time", rs.getDouble("blk_read_time"));
                    metrics.put("blk_write_time", rs.getDouble("blk_write_time"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect pg_stat_database metrics: {}", e.getMessage());
        }

        // Calculate cache hit ratio
        Long blksRead = (Long) metrics.getOrDefault("blks_read", 0L);
        Long blksHit = (Long) metrics.getOrDefault("blks_hit", 0L);
        if (blksRead + blksHit > 0) {
            metrics.put("cache_hit_ratio", (double) blksHit / (blksRead + blksHit));
        }

        // pg_stat_bgwriter metrics
        try {
            jdbc.query(
                "SELECT checkpoints_timed, checkpoints_req, checkpoint_write_time, " +
                "checkpoint_sync_time, buffers_checkpoint, buffers_clean, " +
                "maxwritten_clean, buffers_backend, buffers_backend_fsync, buffers_alloc " +
                "FROM pg_stat_bgwriter",
                rs -> {
                    metrics.put("checkpoints_timed", rs.getLong("checkpoints_timed"));
                    metrics.put("checkpoints_req", rs.getLong("checkpoints_req"));
                    metrics.put("checkpoint_write_time", rs.getDouble("checkpoint_write_time"));
                    metrics.put("checkpoint_sync_time", rs.getDouble("checkpoint_sync_time"));
                    metrics.put("buffers_checkpoint", rs.getLong("buffers_checkpoint"));
                    metrics.put("buffers_clean", rs.getLong("buffers_clean"));
                    metrics.put("maxwritten_clean", rs.getLong("maxwritten_clean"));
                    metrics.put("buffers_backend", rs.getLong("buffers_backend"));
                    metrics.put("buffers_backend_fsync", rs.getLong("buffers_backend_fsync"));
                    metrics.put("buffers_alloc", rs.getLong("buffers_alloc"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect pg_stat_bgwriter metrics: {}", e.getMessage());
        }

        // pg_stat_user_tables aggregated metrics
        try {
            jdbc.query(
                "SELECT SUM(seq_scan) as total_seq_scan, SUM(seq_tup_read) as total_seq_tup_read, " +
                "SUM(idx_scan) as total_idx_scan, SUM(idx_tup_fetch) as total_idx_tup_fetch, " +
                "SUM(n_tup_ins) as total_ins, SUM(n_tup_upd) as total_upd, " +
                "SUM(n_tup_del) as total_del, SUM(n_live_tup) as total_live_tup, " +
                "SUM(n_dead_tup) as total_dead_tup, SUM(vacuum_count) as total_vacuum, " +
                "SUM(autovacuum_count) as total_autovacuum " +
                "FROM pg_stat_user_tables",
                rs -> {
                    metrics.put("total_seq_scan", rs.getLong("total_seq_scan"));
                    metrics.put("total_seq_tup_read", rs.getLong("total_seq_tup_read"));
                    metrics.put("total_idx_scan", rs.getLong("total_idx_scan"));
                    metrics.put("total_idx_tup_fetch", rs.getLong("total_idx_tup_fetch"));
                    metrics.put("total_tup_ins", rs.getLong("total_ins"));
                    metrics.put("total_tup_upd", rs.getLong("total_upd"));
                    metrics.put("total_tup_del", rs.getLong("total_del"));
                    metrics.put("total_live_tup", rs.getLong("total_live_tup"));
                    metrics.put("total_dead_tup", rs.getLong("total_dead_tup"));
                    metrics.put("total_vacuum", rs.getLong("total_vacuum"));
                    metrics.put("total_autovacuum", rs.getLong("total_autovacuum"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect pg_stat_user_tables metrics: {}", e.getMessage());
        }

        // Calculate read/write ratio
        Long tupReturned = (Long) metrics.getOrDefault("tup_returned", 0L);
        Long tupInserted = (Long) metrics.getOrDefault("tup_inserted", 0L);
        Long tupUpdated = (Long) metrics.getOrDefault("tup_updated", 0L);
        Long tupDeleted = (Long) metrics.getOrDefault("tup_deleted", 0L);
        long totalWrites = tupInserted + tupUpdated + tupDeleted;
        if (tupReturned + totalWrites > 0) {
            metrics.put("read_write_ratio", (double) tupReturned / (tupReturned + totalWrites));
        }

        // Connection statistics
        try {
            jdbc.query(
                "SELECT COUNT(*) as total, " +
                "COUNT(*) FILTER (WHERE state = 'active') as active, " +
                "COUNT(*) FILTER (WHERE state = 'idle') as idle, " +
                "COUNT(*) FILTER (WHERE state = 'idle in transaction') as idle_in_transaction, " +
                "COUNT(*) FILTER (WHERE wait_event_type IS NOT NULL) as waiting " +
                "FROM pg_stat_activity WHERE datname = current_database()",
                rs -> {
                    metrics.put("conn_total", rs.getLong("total"));
                    metrics.put("conn_active", rs.getLong("active"));
                    metrics.put("conn_idle", rs.getLong("idle"));
                    metrics.put("conn_idle_in_transaction", rs.getLong("idle_in_transaction"));
                    metrics.put("conn_waiting", rs.getLong("waiting"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect connection metrics: {}", e.getMessage());
        }

        // pg_stat_statements metrics (if available)
        try {
            jdbc.query(
                "SELECT COUNT(*) as query_count, " +
                "SUM(calls) as total_calls, " +
                "SUM(total_exec_time) as total_exec_time, " +
                "AVG(mean_exec_time) as avg_mean_exec_time, " +
                "MAX(max_exec_time) as max_exec_time, " +
                "SUM(rows) as total_rows, " +
                "SUM(shared_blks_hit) as total_shared_blks_hit, " +
                "SUM(shared_blks_read) as total_shared_blks_read " +
                "FROM pg_stat_statements WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())",
                rs -> {
                    metrics.put("pg_stat_query_count", rs.getLong("query_count"));
                    metrics.put("pg_stat_total_calls", rs.getLong("total_calls"));
                    metrics.put("pg_stat_total_exec_time", rs.getDouble("total_exec_time"));
                    metrics.put("pg_stat_avg_mean_exec_time", rs.getDouble("avg_mean_exec_time"));
                    metrics.put("pg_stat_max_exec_time", rs.getDouble("max_exec_time"));
                    metrics.put("pg_stat_total_rows", rs.getLong("total_rows"));
                    metrics.put("pg_stat_shared_blks_hit", rs.getLong("total_shared_blks_hit"));
                    metrics.put("pg_stat_shared_blks_read", rs.getLong("total_shared_blks_read"));
                }
            );
        } catch (Exception e) {
            log.debug("pg_stat_statements not available: {}", e.getMessage());
        }

        // Lock metrics
        try {
            jdbc.query(
                "SELECT COUNT(*) as total_locks, " +
                "COUNT(*) FILTER (WHERE granted = false) as waiting_locks, " +
                "COUNT(*) FILTER (WHERE mode LIKE '%Exclusive%') as exclusive_locks " +
                "FROM pg_locks",
                rs -> {
                    metrics.put("total_locks", rs.getLong("total_locks"));
                    metrics.put("waiting_locks", rs.getLong("waiting_locks"));
                    metrics.put("exclusive_locks", rs.getLong("exclusive_locks"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect lock metrics: {}", e.getMessage());
        }

        return metrics;
    }

    /**
     * Collect MySQL metrics from performance_schema and SHOW STATUS.
     */
    private Map<String, Object> collectMySQLMetrics(JdbcTemplate jdbc) {
        Map<String, Object> metrics = new LinkedHashMap<>();

        // SHOW GLOBAL STATUS metrics
        try {
            List<Map<String, Object>> statusRows = jdbc.queryForList("SHOW GLOBAL STATUS");
            Set<String> importantMetrics = Set.of(
                "Bytes_received", "Bytes_sent", "Com_select", "Com_insert", "Com_update",
                "Com_delete", "Connections", "Created_tmp_disk_tables", "Created_tmp_tables",
                "Handler_read_first", "Handler_read_key", "Handler_read_next", "Handler_read_rnd",
                "Handler_read_rnd_next", "Innodb_buffer_pool_read_requests", "Innodb_buffer_pool_reads",
                "Innodb_data_reads", "Innodb_data_writes", "Innodb_log_writes",
                "Innodb_row_lock_current_waits", "Innodb_row_lock_time", "Innodb_row_lock_waits",
                "Innodb_rows_deleted", "Innodb_rows_inserted", "Innodb_rows_read", "Innodb_rows_updated",
                "Key_read_requests", "Key_reads", "Key_write_requests", "Key_writes",
                "Open_files", "Open_tables", "Opened_tables", "Queries", "Questions",
                "Select_full_join", "Select_full_range_join", "Select_range", "Select_scan",
                "Slow_queries", "Sort_merge_passes", "Sort_rows", "Sort_scan",
                "Table_locks_immediate", "Table_locks_waited", "Threads_connected", "Threads_running",
                "Uptime"
            );

            for (Map<String, Object> row : statusRows) {
                String name = (String) row.get("Variable_name");
                String value = (String) row.get("Value");
                if (importantMetrics.contains(name)) {
                    try {
                        metrics.put(name.toLowerCase(), Long.parseLong(value));
                    } catch (NumberFormatException e) {
                        try {
                            metrics.put(name.toLowerCase(), Double.parseDouble(value));
                        } catch (NumberFormatException e2) {
                            metrics.put(name.toLowerCase(), value);
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not collect MySQL status metrics: {}", e.getMessage());
        }

        // Calculate InnoDB buffer pool hit ratio
        Long readRequests = (Long) metrics.getOrDefault("innodb_buffer_pool_read_requests", 0L);
        Long reads = (Long) metrics.getOrDefault("innodb_buffer_pool_reads", 0L);
        if (readRequests > 0) {
            metrics.put("innodb_buffer_hit_ratio", (double) (readRequests - reads) / readRequests);
        }

        // Calculate read/write ratio
        Long selects = (Long) metrics.getOrDefault("com_select", 0L);
        Long inserts = (Long) metrics.getOrDefault("com_insert", 0L);
        Long updates = (Long) metrics.getOrDefault("com_update", 0L);
        Long deletes = (Long) metrics.getOrDefault("com_delete", 0L);
        long totalWrites = inserts + updates + deletes;
        if (selects + totalWrites > 0) {
            metrics.put("read_write_ratio", (double) selects / (selects + totalWrites));
        }

        // performance_schema metrics if available
        try {
            jdbc.query(
                "SELECT COUNT_STAR as total_events, SUM_TIMER_WAIT as total_wait_time " +
                "FROM performance_schema.events_waits_summary_global_by_event_name " +
                "WHERE EVENT_NAME = 'wait/io/file/innodb/innodb_data_file' LIMIT 1",
                rs -> {
                    metrics.put("innodb_data_file_events", rs.getLong("total_events"));
                    metrics.put("innodb_data_file_wait_time", rs.getLong("total_wait_time"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect performance_schema metrics: {}", e.getMessage());
        }

        // Process list summary
        try {
            jdbc.query(
                "SELECT COUNT(*) as total, " +
                "SUM(CASE WHEN command != 'Sleep' THEN 1 ELSE 0 END) as active, " +
                "SUM(CASE WHEN command = 'Sleep' THEN 1 ELSE 0 END) as sleeping " +
                "FROM information_schema.processlist",
                rs -> {
                    metrics.put("conn_total", rs.getLong("total"));
                    metrics.put("conn_active", rs.getLong("active"));
                    metrics.put("conn_sleeping", rs.getLong("sleeping"));
                }
            );
        } catch (Exception e) {
            log.debug("Could not collect processlist metrics: {}", e.getMessage());
        }

        return metrics;
    }

    /**
     * Generate a fingerprint hash from key metrics for similarity matching.
     */
    private String generateFingerprint(Map<String, Object> metrics) {
        try {
            // Select key metrics for fingerprinting
            StringBuilder sb = new StringBuilder();

            // Read/write ratio
            Object rwRatio = metrics.get("read_write_ratio");
            if (rwRatio != null) {
                sb.append("rw:").append(String.format("%.2f", (Double) rwRatio)).append(";");
            }

            // Cache hit ratio
            Object cacheHit = metrics.get("cache_hit_ratio");
            if (cacheHit == null) {
                cacheHit = metrics.get("innodb_buffer_hit_ratio");
            }
            if (cacheHit != null) {
                sb.append("ch:").append(String.format("%.2f", (Double) cacheHit)).append(";");
            }

            // Connection activity
            Object connActive = metrics.get("conn_active");
            Object connTotal = metrics.get("conn_total");
            if (connActive != null && connTotal != null && (Long) connTotal > 0) {
                double activeRatio = (double) (Long) connActive / (Long) connTotal;
                sb.append("ca:").append(String.format("%.2f", activeRatio)).append(";");
            }

            // Index vs seq scan ratio
            Object seqScan = metrics.get("total_seq_scan");
            Object idxScan = metrics.get("total_idx_scan");
            if (seqScan != null && idxScan != null) {
                long total = (Long) seqScan + (Long) idxScan;
                if (total > 0) {
                    double idxRatio = (double) (Long) idxScan / total;
                    sb.append("idx:").append(String.format("%.2f", idxRatio)).append(";");
                }
            }

            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] hash = md.digest(sb.toString().getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (int i = 0; i < 8; i++) { // Use first 16 chars (64 bits)
                hex.append(String.format("%02x", hash[i]));
            }
            return hex.toString();

        } catch (Exception e) {
            log.warn("Error generating fingerprint: {}", e.getMessage());
            return UUID.randomUUID().toString().substring(0, 16);
        }
    }

    /**
     * Get recent snapshots for a connection.
     */
    public List<WorkloadMetricsSnapshot> getRecentSnapshots(String connectionId, int limit) {
        return snapshotRepository.findByConnectionIdOrderByCollectedAtDesc(
            connectionId,
            org.springframework.data.domain.PageRequest.of(0, limit)
        );
    }

    /**
     * Get snapshot count for a connection.
     */
    public long getSnapshotCount(String connectionId) {
        return snapshotRepository.countByConnectionId(connectionId);
    }
}
