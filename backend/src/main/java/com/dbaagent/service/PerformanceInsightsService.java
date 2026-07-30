package com.dbaagent.service;

import com.dbaagent.dto.TableUsageDTO;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.PerformanceSnapshot;
import com.dbaagent.model.ResourceLimits;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.PerformanceSnapshotRepository;
import com.dbaagent.repository.ResourceLimitsRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Performance Insights Service
 * Collects comprehensive performance metrics every 5 minutes
 * Similar to AWS RDS Performance Insights
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PerformanceInsightsService {

    private final PerformanceSnapshotRepository snapshotRepository;
    private final CredentialService credentialService;
    private final QueryExecutorService queryExecutorService;
    private final ConnectionService connectionService;
    private final ObjectMapper objectMapper;
    private final ResourceLimitsRepository resourceLimitsRepository;
    private final DatabaseProviderRegistry providerRegistry;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;

    private static final int TOP_QUERIES_LIMIT = 20;
    private static final int DEFAULT_RETENTION_DAYS = 7;  // Default if not configured

    /**
     * Collect performance snapshots every 5 minutes
     * Cron: 0 star/5 star star star star = Every 5 minutes at second 0
     */
    public void collectPerformanceSnapshots() {
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("Starting Performance Insights snapshot collection...");
        log.info("═══════════════════════════════════════════════════════════════");

        try {
            List<DatabaseConnection> connections = credentialService.getAllConnections();
            log.info("Collecting performance data for {} connections", connections.size());

            int successCount = 0;
            int failureCount = 0;

            for (DatabaseConnection connection : connections) {
                try {
                    log.info("📊 Collecting snapshot for: {}", connection.getConnectionName());
                    collectSnapshot(connection);
                    successCount++;
                    log.info("✓ Snapshot collected successfully for: {}", connection.getConnectionName());
                } catch (Exception e) {
                    failureCount++;
                    log.error("✗ Failed to collect snapshot for {}: {}",
                            connection.getConnectionName(), e.getMessage());
                }
            }

            log.info("═══════════════════════════════════════════════════════════════");
            log.info("✓ Performance Insights collection complete");
            log.info("Summary: {} successful, {} failed", successCount, failureCount);
            log.info("═══════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("✗ Error during performance insights collection", e);
        }
    }

    /**
     * Collect snapshot for a single connection
     */
    public PerformanceSnapshot collectSnapshot(DatabaseConnection connection) {
        String connectionId = connection.getId();

        try {
            // Get decrypted connection details
            com.dbaagent.model.ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

            PerformanceSnapshot.PerformanceSnapshotBuilder builder = PerformanceSnapshot.builder()
                    .connectionId(connectionId)
                    .snapshotTime(LocalDateTime.now());

            JdbcTemplate jdbc = createJdbcTemplate(connectionId, connRequest);

            if ("mysql".equals(dbType)) {
                collectMySQLMetrics(jdbc, builder);
            } else if ("postgres".equals(dbType)) {
                collectPostgreSQLMetrics(jdbc, builder);
            } else {
                log.warn("Unsupported database type for Performance Insights: {}", dbType);
                return null;
            }

            PerformanceSnapshot snapshot = builder.build();
            snapshot = snapshotRepository.save(snapshot);

            log.debug("  → Saved snapshot: {} queries, {:.2f}ms total DB time",
                    snapshot.getTotalQueryCount(), snapshot.getTotalDbTimeMs());

            return snapshot;

        } catch (Exception e) {
            log.error("Failed to collect snapshot for {}: {}",
                    connection.getConnectionName(), e.getMessage());
            throw new RuntimeException("Snapshot collection failed", e);
        }
    }

    /**
     * Collect MySQL performance metrics
     */
    private void collectMySQLMetrics(JdbcTemplate jdbc, PerformanceSnapshot.PerformanceSnapshotBuilder builder) {
        log.debug("  → Collecting MySQL metrics...");

        // Top queries from Performance Schema
        try {
            List<Map<String, Object>> topQueries = collectMySQLTopQueries(jdbc);
            builder.topQueries(convertToJson(topQueries));
            builder.totalQueryCount(topQueries.size());

            double totalDbTime = topQueries.stream()
                    .mapToDouble(q -> ((Number) q.getOrDefault("totalTime", 0.0)).doubleValue())
                    .sum();
            builder.totalDbTimeMs(totalDbTime);

            log.debug("    ✓ Top queries: {} (total: {:.2f}ms)", topQueries.size(), totalDbTime);
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect top queries: {}", e.getMessage());
        }

        // Wait events
        try {
            Map<String, Object> waitEvents = collectMySQLWaitEvents(jdbc);
            builder.waitEvents(convertToJson(waitEvents.get("events")));
            builder.cpuWaitMs(((Number) waitEvents.getOrDefault("cpu", 0.0)).doubleValue());
            builder.ioWaitMs(((Number) waitEvents.getOrDefault("io", 0.0)).doubleValue());
            builder.lockWaitMs(((Number) waitEvents.getOrDefault("lock", 0.0)).doubleValue());

            log.debug("    ✓ Wait events collected");
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect wait events: {}", e.getMessage());
        }

        // Database metrics
        try {
            Map<String, Object> metrics = collectMySQLDatabaseMetrics(jdbc);
            builder.activeConnections(((Number) metrics.getOrDefault("activeConnections", 0)).intValue());
            builder.totalConnections(((Number) metrics.getOrDefault("maxConnections", 0)).intValue());

            // CPU usage (if available)
            if (metrics.containsKey("cpuPercent")) {
                builder.cpuPercent(((Number) metrics.get("cpuPercent")).doubleValue());
            }

            log.debug("    ✓ Database metrics collected");
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect database metrics: {}", e.getMessage());
        }

        // CloudWatch-style advanced metrics
        try {
            Map<String, Object> cloudwatchMetrics = collectMySQLCloudWatchMetrics(jdbc);

            // Deadlocks
            if (cloudwatchMetrics.containsKey("deadlocksPerMinute")) {
                builder.deadlocksPerMinute(((Number) cloudwatchMetrics.get("deadlocksPerMinute")).doubleValue());
            }

            // Swap and Disk Usage
            if (cloudwatchMetrics.containsKey("binlogDiskUsageBytes")) {
                builder.binlogDiskUsageBytes(((Number) cloudwatchMetrics.get("binlogDiskUsageBytes")).longValue());
            }

            // Sessions and Connections
            if (cloudwatchMetrics.containsKey("sessionsCount")) {
                builder.sessionsCount(((Number) cloudwatchMetrics.get("sessionsCount")).intValue());
            }
            if (cloudwatchMetrics.containsKey("abortedConnections")) {
                builder.abortedConnections(((Number) cloudwatchMetrics.get("abortedConnections")).intValue());
            }

            // InnoDB Metrics
            if (cloudwatchMetrics.containsKey("innodbHistoryListLength")) {
                builder.innodbHistoryListLength(((Number) cloudwatchMetrics.get("innodbHistoryListLength")).longValue());
            }
            if (cloudwatchMetrics.containsKey("innodbRowLockTimeMs")) {
                builder.innodbRowLockTimeMs(((Number) cloudwatchMetrics.get("innodbRowLockTimeMs")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("innodbRowLockWaits")) {
                builder.innodbRowLockWaits(((Number) cloudwatchMetrics.get("innodbRowLockWaits")).longValue());
            }
            if (cloudwatchMetrics.containsKey("bufferPoolHitRatio")) {
                builder.bufferPoolHitRatio(((Number) cloudwatchMetrics.get("bufferPoolHitRatio")).doubleValue());
            }

            // Query Throughput
            if (cloudwatchMetrics.containsKey("queriesPerSecond")) {
                builder.queriesPerSecond(((Number) cloudwatchMetrics.get("queriesPerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("selectPerSecond")) {
                builder.selectPerSecond(((Number) cloudwatchMetrics.get("selectPerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("insertPerSecond")) {
                builder.insertPerSecond(((Number) cloudwatchMetrics.get("insertPerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("updatePerSecond")) {
                builder.updatePerSecond(((Number) cloudwatchMetrics.get("updatePerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("deletePerSecond")) {
                builder.deletePerSecond(((Number) cloudwatchMetrics.get("deletePerSecond")).doubleValue());
            }

            // DML Rows
            if (cloudwatchMetrics.containsKey("innodbRowsInserted")) {
                builder.innodbRowsInserted(((Number) cloudwatchMetrics.get("innodbRowsInserted")).longValue());
            }
            if (cloudwatchMetrics.containsKey("innodbRowsUpdated")) {
                builder.innodbRowsUpdated(((Number) cloudwatchMetrics.get("innodbRowsUpdated")).longValue());
            }
            if (cloudwatchMetrics.containsKey("innodbRowsDeleted")) {
                builder.innodbRowsDeleted(((Number) cloudwatchMetrics.get("innodbRowsDeleted")).longValue());
            }
            if (cloudwatchMetrics.containsKey("innodbRowsRead")) {
                builder.innodbRowsRead(((Number) cloudwatchMetrics.get("innodbRowsRead")).longValue());
            }

            // Transactions
            if (cloudwatchMetrics.containsKey("activeTransactions")) {
                builder.activeTransactions(((Number) cloudwatchMetrics.get("activeTransactions")).intValue());
            }
            if (cloudwatchMetrics.containsKey("lockedTransactions")) {
                builder.lockedTransactions(((Number) cloudwatchMetrics.get("lockedTransactions")).intValue());
            }

            // IO Cache vs Disk Reads
            if (cloudwatchMetrics.containsKey("cacheHitPagesPerSec")) {
                builder.cacheHitPagesPerSec(((Number) cloudwatchMetrics.get("cacheHitPagesPerSec")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("diskReadPagesPerSec")) {
                builder.diskReadPagesPerSec(((Number) cloudwatchMetrics.get("diskReadPagesPerSec")).doubleValue());
            }

            log.debug("    ✓ CloudWatch metrics collected");
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect CloudWatch metrics: {}", e.getMessage());
        }
    }

    /**
     * Collect top queries from MySQL Performance Schema
     */
    private List<Map<String, Object>> collectMySQLTopQueries(JdbcTemplate jdbc) {
        String sql = """
            SELECT
                DIGEST as queryId,
                DIGEST_TEXT as queryText,
                COUNT_STAR as callCount,
                SUM_TIMER_WAIT/1000000000 as totalTime,
                AVG_TIMER_WAIT/1000000000 as avgTime,
                MAX_TIMER_WAIT/1000000000 as maxTime,
                SUM_ROWS_EXAMINED as rowsExamined,
                SUM_ROWS_SENT as rowsSent
            FROM performance_schema.events_statements_summary_by_digest
            WHERE SCHEMA_NAME IS NOT NULL
            ORDER BY SUM_TIMER_WAIT DESC
            LIMIT ?
        """;

        return jdbc.query(sql, new Object[]{TOP_QUERIES_LIMIT}, (rs, rowNum) -> {
            Map<String, Object> query = new HashMap<>();
            query.put("queryId", rs.getString("queryId"));
            query.put("queryText", rs.getString("queryText"));
            query.put("callCount", rs.getLong("callCount"));
            query.put("totalTime", rs.getDouble("totalTime"));
            query.put("avgTime", rs.getDouble("avgTime"));
            query.put("maxTime", rs.getDouble("maxTime"));
            query.put("rowsExamined", rs.getLong("rowsExamined"));
            query.put("rowsSent", rs.getLong("rowsSent"));
            return query;
        });
    }

    /**
     * Collect wait events from MySQL Performance Schema
     */
    private Map<String, Object> collectMySQLWaitEvents(JdbcTemplate jdbc) {
        Map<String, Object> result = new HashMap<>();
        List<Map<String, Object>> events = new ArrayList<>();

        String sql = """
            SELECT
                EVENT_NAME as eventName,
                SUM_TIMER_WAIT/1000000000 as waitTime,
                COUNT_STAR as waitCount
            FROM performance_schema.events_waits_summary_global_by_event_name
            WHERE COUNT_STAR > 0
            ORDER BY SUM_TIMER_WAIT DESC
            LIMIT 10
        """;

        double cpuWait = 0.0, ioWait = 0.0, lockWait = 0.0;

        List<Map<String, Object>> rawEvents = jdbc.query(sql, (rs, rowNum) -> {
            Map<String, Object> event = new HashMap<>();
            String eventName = rs.getString("eventName");
            double waitTime = rs.getDouble("waitTime");
            long waitCount = rs.getLong("waitCount");

            event.put("eventName", eventName);
            event.put("waitTime", waitTime);
            event.put("waitCount", waitCount);
            event.put("avgWaitTime", waitCount > 0 ? waitTime / waitCount : 0.0);

            return event;
        });

        for (Map<String, Object> event : rawEvents) {
            String name = (String) event.get("eventName");
            double waitTime = ((Number) event.get("waitTime")).doubleValue();

            if (name.contains("io/file") || name.contains("io/table")) {
                ioWait += waitTime;
            } else if (name.contains("lock") || name.contains("wait/synch")) {
                lockWait += waitTime;
            } else {
                cpuWait += waitTime;
            }

            events.add(event);
        }

        result.put("events", events);
        result.put("cpu", cpuWait);
        result.put("io", ioWait);
        result.put("lock", lockWait);

        return result;
    }

    /**
     * Collect database metrics from MySQL
     */
    private Map<String, Object> collectMySQLDatabaseMetrics(JdbcTemplate jdbc) {
        Map<String, Object> metrics = new HashMap<>();

        // Active connections
        Integer connections = jdbc.queryForObject("SHOW STATUS LIKE 'Threads_connected'",
                (rs, rowNum) -> rs.getInt("Value"));
        metrics.put("activeConnections", connections != null ? connections : 0);

        // Max connections
        Integer maxConnections = jdbc.queryForObject("SELECT @@max_connections",
                (rs, rowNum) -> rs.getInt(1));
        metrics.put("maxConnections", maxConnections != null ? maxConnections : 0);

        // Calculate CPU/Database Load approximation
        // Note: MySQL doesn't expose OS-level CPU directly. This calculates database load
        // based on active threads executing queries vs total capacity.
        try {
            // Get running threads from performance_schema
            Integer runningThreads = jdbc.queryForObject(
                """
                SELECT COUNT(*)
                FROM performance_schema.threads
                WHERE TYPE = 'FOREGROUND'
                  AND PROCESSLIST_STATE IS NOT NULL
                  AND PROCESSLIST_STATE != 'Sleep'
                  AND PROCESSLIST_COMMAND != 'Sleep'
                """,
                Integer.class);

            // Calculate database load as percentage of active work
            // This represents "database CPU" - how busy the database is processing queries
            if (runningThreads != null && connections != null && connections > 0) {
                double dbLoad = (runningThreads.doubleValue() / connections.doubleValue()) * 100.0;
                // Cap at 100%
                metrics.put("cpuPercent", Math.min(dbLoad, 100.0));
            } else {
                metrics.put("cpuPercent", 0.0);
            }
        } catch (Exception e) {
            log.debug("    ⚠️  Could not calculate database load: {}", e.getMessage());
            metrics.put("cpuPercent", 0.0);
        }

        return metrics;
    }

    /**
     * Collect CloudWatch-style metrics from MySQL
     */
    private Map<String, Object> collectMySQLCloudWatchMetrics(JdbcTemplate jdbc) {
        Map<String, Object> metrics = new HashMap<>();

        try {
            // Query all status variables at once for efficiency
            Map<String, String> statusVars = new HashMap<>();
            jdbc.query("SHOW GLOBAL STATUS", rs -> {
                statusVars.put(rs.getString(1), rs.getString(2));
            });

            // Deadlocks per minute (calculate from cumulative counter)
            String deadlocks = statusVars.get("Innodb_deadlocks");
            if (deadlocks != null) {
                // Store as per-minute rate (will need to calculate delta between snapshots)
                metrics.put("deadlocksPerMinute", Double.parseDouble(deadlocks) / 60.0);
            }

            // Sessions count (threads connected)
            String threads = statusVars.get("Threads_connected");
            if (threads != null) {
                metrics.put("sessionsCount", Integer.parseInt(threads));
            }

            // Aborted connections
            String aborted = statusVars.get("Aborted_connects");
            if (aborted != null) {
                metrics.put("abortedConnections", Integer.parseInt(aborted));
            }

            // InnoDB History List Length
            String historyList = statusVars.get("Innodb_history_list_length");
            if (historyList != null) {
                metrics.put("innodbHistoryListLength", Long.parseLong(historyList));
            }

            // InnoDB Row Lock Time (convert from ms to seconds for rate calculation)
            String rowLockTime = statusVars.get("Innodb_row_lock_time");
            if (rowLockTime != null) {
                metrics.put("innodbRowLockTimeMs", Double.parseDouble(rowLockTime));
            }

            // InnoDB Row Lock Waits
            String rowLockWaits = statusVars.get("Innodb_row_lock_waits");
            if (rowLockWaits != null) {
                metrics.put("innodbRowLockWaits", Long.parseLong(rowLockWaits));
            }

            // Buffer Pool Hit Ratio and IO Cache vs Disk Reads
            String bufferPoolReads = statusVars.get("Innodb_buffer_pool_reads");
            String bufferPoolReadRequests = statusVars.get("Innodb_buffer_pool_read_requests");
            String uptime = statusVars.get("Uptime");

            if (bufferPoolReads != null && bufferPoolReadRequests != null) {
                long reads = Long.parseLong(bufferPoolReads);
                long requests = Long.parseLong(bufferPoolReadRequests);

                // Buffer pool hit ratio
                if (requests > 0) {
                    double hitRatio = ((requests - reads) / (double) requests) * 100.0;
                    metrics.put("bufferPoolHitRatio", hitRatio);
                }

                // IO cache vs disk reads (pages per second)
                if (uptime != null) {
                    double uptimeSec = Double.parseDouble(uptime);
                    if (uptimeSec > 0) {
                        // Cache hits = read requests that didn't require disk read
                        long cacheHits = requests - reads;
                        double cacheHitPagesPerSec = cacheHits / uptimeSec;
                        double diskReadPagesPerSec = reads / uptimeSec;

                        metrics.put("cacheHitPagesPerSec", cacheHitPagesPerSec);
                        metrics.put("diskReadPagesPerSec", diskReadPagesPerSec);
                    }
                }
            }

            // Query throughput (per second - calculate from cumulative counters)
            String questions = statusVars.get("Questions");
            String comSelect = statusVars.get("Com_select");
            String comInsert = statusVars.get("Com_insert");
            String comUpdate = statusVars.get("Com_update");
            String comDelete = statusVars.get("Com_delete");

            if (uptime != null && Long.parseLong(uptime) > 0) {
                double uptimeSec = Double.parseDouble(uptime);

                if (questions != null) {
                    metrics.put("queriesPerSecond", Double.parseDouble(questions) / uptimeSec);
                }
                if (comSelect != null) {
                    metrics.put("selectPerSecond", Double.parseDouble(comSelect) / uptimeSec);
                }
                if (comInsert != null) {
                    metrics.put("insertPerSecond", Double.parseDouble(comInsert) / uptimeSec);
                }
                if (comUpdate != null) {
                    metrics.put("updatePerSecond", Double.parseDouble(comUpdate) / uptimeSec);
                }
                if (comDelete != null) {
                    metrics.put("deletePerSecond", Double.parseDouble(comDelete) / uptimeSec);
                }
            }

            // InnoDB DML Rows
            String rowsInserted = statusVars.get("Innodb_rows_inserted");
            String rowsUpdated = statusVars.get("Innodb_rows_updated");
            String rowsDeleted = statusVars.get("Innodb_rows_deleted");
            String rowsRead = statusVars.get("Innodb_rows_read");

            if (rowsInserted != null) metrics.put("innodbRowsInserted", Long.parseLong(rowsInserted));
            if (rowsUpdated != null) metrics.put("innodbRowsUpdated", Long.parseLong(rowsUpdated));
            if (rowsDeleted != null) metrics.put("innodbRowsDeleted", Long.parseLong(rowsDeleted));
            if (rowsRead != null) metrics.put("innodbRowsRead", Long.parseLong(rowsRead));

            // BinLog disk usage
            try {
                Long binlogSize = jdbc.queryForObject(
                    "SELECT SUM(FILE_SIZE) FROM information_schema.BINARY_LOGS",
                    Long.class
                );
                if (binlogSize != null) {
                    metrics.put("binlogDiskUsageBytes", binlogSize);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get binlog size: {}", e.getMessage());
            }

            // Active transactions and locks
            try {
                Integer activeTrx = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.INNODB_TRX WHERE trx_state = 'RUNNING'",
                    Integer.class
                );
                if (activeTrx != null) {
                    metrics.put("activeTransactions", activeTrx);
                }

                Integer lockedTrx = jdbc.queryForObject(
                    "SELECT COUNT(*) FROM information_schema.INNODB_LOCKS",
                    Integer.class
                );
                if (lockedTrx != null) {
                    metrics.put("lockedTransactions", lockedTrx);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get transaction info: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("    ⚠️  Error collecting CloudWatch metrics: {}", e.getMessage());
        }

        return metrics;
    }

    /**
     * Collect PostgreSQL performance metrics
     */
    private void collectPostgreSQLMetrics(JdbcTemplate jdbc, PerformanceSnapshot.PerformanceSnapshotBuilder builder) {
        log.debug("  → Collecting PostgreSQL metrics...");

        // Top queries from pg_stat_statements
        try {
            List<Map<String, Object>> topQueries = collectPostgreSQLTopQueries(jdbc);
            builder.topQueries(convertToJson(topQueries));
            builder.totalQueryCount(topQueries.size());

            double totalDbTime = topQueries.stream()
                    .mapToDouble(q -> ((Number) q.getOrDefault("totalTime", 0.0)).doubleValue())
                    .sum();
            builder.totalDbTimeMs(totalDbTime);

            log.debug("    ✓ Top queries: {} (total: {:.2f}ms)", topQueries.size(), totalDbTime);
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect top queries: {}", e.getMessage());
        }

        // Database metrics
        try {
            Map<String, Object> metrics = collectPostgreSQLDatabaseMetrics(jdbc);
            builder.activeConnections(((Number) metrics.getOrDefault("activeConnections", 0)).intValue());
            builder.totalConnections(((Number) metrics.getOrDefault("maxConnections", 0)).intValue());

            // CPU usage (database load approximation)
            if (metrics.containsKey("cpuPercent")) {
                builder.cpuPercent(((Number) metrics.get("cpuPercent")).doubleValue());
            }

            log.debug("    ✓ Database metrics collected");
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect database metrics: {}", e.getMessage());
        }

        // CloudWatch-style advanced metrics
        try {
            Map<String, Object> cloudwatchMetrics = collectPostgreSQLCloudWatchMetrics(jdbc);

            // Deadlocks
            if (cloudwatchMetrics.containsKey("deadlocksPerMinute")) {
                builder.deadlocksPerMinute(((Number) cloudwatchMetrics.get("deadlocksPerMinute")).doubleValue());
            }

            // Sessions
            if (cloudwatchMetrics.containsKey("sessionsCount")) {
                builder.sessionsCount(((Number) cloudwatchMetrics.get("sessionsCount")).intValue());
            }

            // Query throughput
            if (cloudwatchMetrics.containsKey("queriesPerSecond")) {
                builder.queriesPerSecond(((Number) cloudwatchMetrics.get("queriesPerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("selectPerSecond")) {
                builder.selectPerSecond(((Number) cloudwatchMetrics.get("selectPerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("insertPerSecond")) {
                builder.insertPerSecond(((Number) cloudwatchMetrics.get("insertPerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("updatePerSecond")) {
                builder.updatePerSecond(((Number) cloudwatchMetrics.get("updatePerSecond")).doubleValue());
            }
            if (cloudwatchMetrics.containsKey("deletePerSecond")) {
                builder.deletePerSecond(((Number) cloudwatchMetrics.get("deletePerSecond")).doubleValue());
            }

            // Transactions
            if (cloudwatchMetrics.containsKey("activeTransactions")) {
                builder.activeTransactions(((Number) cloudwatchMetrics.get("activeTransactions")).intValue());
            }

            // Buffer cache hit ratio
            if (cloudwatchMetrics.containsKey("bufferPoolHitRatio")) {
                builder.bufferPoolHitRatio(((Number) cloudwatchMetrics.get("bufferPoolHitRatio")).doubleValue());
            }

            log.debug("    ✓ CloudWatch metrics collected");
        } catch (Exception e) {
            log.warn("    ⚠️  Failed to collect CloudWatch metrics: {}", e.getMessage());
        }
    }

    /**
     * Collect top queries from PostgreSQL pg_stat_statements
     */
    private List<Map<String, Object>> collectPostgreSQLTopQueries(JdbcTemplate jdbc) {
        String sql = """
            SELECT
                queryid::text as queryId,
                query as queryText,
                calls as callCount,
                total_exec_time as totalTime,
                mean_exec_time as avgTime,
                max_exec_time as maxTime,
                rows as rowsSent
            FROM pg_stat_statements
            WHERE dbid = (SELECT oid FROM pg_database WHERE datname = current_database())
            ORDER BY total_exec_time DESC
            LIMIT ?
        """;

        return jdbc.query(sql, new Object[]{TOP_QUERIES_LIMIT}, (rs, rowNum) -> {
            Map<String, Object> query = new HashMap<>();
            query.put("queryId", rs.getString("queryId"));
            query.put("queryText", rs.getString("queryText"));
            query.put("callCount", rs.getLong("callCount"));
            query.put("totalTime", rs.getDouble("totalTime"));
            query.put("avgTime", rs.getDouble("avgTime"));
            query.put("maxTime", rs.getDouble("maxTime"));
            query.put("rowsSent", rs.getLong("rowsSent"));
            return query;
        });
    }

    /**
     * Collect database metrics from PostgreSQL
     */
    private Map<String, Object> collectPostgreSQLDatabaseMetrics(JdbcTemplate jdbc) {
        Map<String, Object> metrics = new HashMap<>();

        // Total connections
        Integer totalConnections = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity",
                Integer.class);

        // Active connections (actually executing queries)
        Integer activeConnections = jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active'",
                Integer.class);
        metrics.put("activeConnections", activeConnections != null ? activeConnections : 0);

        // Max connections
        Integer maxConnections = jdbc.queryForObject("SHOW max_connections",
                (rs, rowNum) -> Integer.parseInt(rs.getString(1)));
        metrics.put("maxConnections", maxConnections != null ? maxConnections : 0);

        // Calculate CPU/Database Load approximation
        // Note: PostgreSQL doesn't expose OS-level CPU directly. This calculates database load
        // based on active connections executing queries vs total connections.
        try {
            if (activeConnections != null && totalConnections != null && totalConnections > 0) {
                double dbLoad = (activeConnections.doubleValue() / totalConnections.doubleValue()) * 100.0;
                // Cap at 100%
                metrics.put("cpuPercent", Math.min(dbLoad, 100.0));
            } else {
                metrics.put("cpuPercent", 0.0);
            }
        } catch (Exception e) {
            log.debug("    ⚠️  Could not calculate database load: {}", e.getMessage());
            metrics.put("cpuPercent", 0.0);
        }

        return metrics;
    }

    /**
     * Collect CloudWatch-style metrics from PostgreSQL
     */
    private Map<String, Object> collectPostgreSQLCloudWatchMetrics(JdbcTemplate jdbc) {
        Map<String, Object> metrics = new HashMap<>();

        try {
            // Deadlocks
            try {
                Long deadlocks = jdbc.queryForObject(
                    "SELECT deadlocks FROM pg_stat_database WHERE datname = current_database()",
                    Long.class
                );
                if (deadlocks != null) {
                    metrics.put("deadlocksPerMinute", deadlocks / 60.0);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get deadlocks: {}", e.getMessage());
            }

            // Sessions count
            try {
                Integer sessions = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity",
                    Integer.class
                );
                if (sessions != null) {
                    metrics.put("sessionsCount", sessions);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get sessions count: {}", e.getMessage());
            }

            // Query throughput from pg_stat_database
            try {
                Map<String, Object> dbStats = jdbc.queryForMap(
                    """
                    SELECT
                        xact_commit + xact_rollback as total_transactions,
                        tup_returned as tuples_returned,
                        tup_fetched as tuples_fetched,
                        tup_inserted as tuples_inserted,
                        tup_updated as tuples_updated,
                        tup_deleted as tuples_deleted
                    FROM pg_stat_database
                    WHERE datname = current_database()
                    """
                );

                // Calculate throughput (simplified - using cumulative counters)
                if (dbStats.containsKey("total_transactions")) {
                    Long totalTxn = ((Number) dbStats.get("total_transactions")).longValue();
                    // Approximate queries per second from transaction count
                    metrics.put("queriesPerSecond", totalTxn / 3600.0); // Rough estimate
                }

                if (dbStats.containsKey("tuples_returned")) {
                    Long returned = ((Number) dbStats.get("tuples_returned")).longValue();
                    metrics.put("selectPerSecond", returned / 3600.0);
                }
                if (dbStats.containsKey("tuples_inserted")) {
                    Long inserted = ((Number) dbStats.get("tuples_inserted")).longValue();
                    metrics.put("insertPerSecond", inserted / 3600.0);
                }
                if (dbStats.containsKey("tuples_updated")) {
                    Long updated = ((Number) dbStats.get("tuples_updated")).longValue();
                    metrics.put("updatePerSecond", updated / 3600.0);
                }
                if (dbStats.containsKey("tuples_deleted")) {
                    Long deleted = ((Number) dbStats.get("tuples_deleted")).longValue();
                    metrics.put("deletePerSecond", deleted / 3600.0);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get query throughput: {}", e.getMessage());
            }

            // Active transactions
            try {
                Integer activeTxn = jdbc.queryForObject(
                    "SELECT count(*) FROM pg_stat_activity WHERE state = 'active' AND query NOT LIKE '%pg_stat%'",
                    Integer.class
                );
                if (activeTxn != null) {
                    metrics.put("activeTransactions", activeTxn);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get active transactions: {}", e.getMessage());
            }

            // Buffer cache hit ratio
            try {
                Map<String, Object> cacheStats = jdbc.queryForMap(
                    """
                    SELECT
                        sum(heap_blks_read) as heap_read,
                        sum(heap_blks_hit) as heap_hit,
                        sum(idx_blks_read) as idx_read,
                        sum(idx_blks_hit) as idx_hit
                    FROM pg_statio_user_tables
                    """
                );

                long heapRead = ((Number) cacheStats.getOrDefault("heap_read", 0L)).longValue();
                long heapHit = ((Number) cacheStats.getOrDefault("heap_hit", 0L)).longValue();
                long idxRead = ((Number) cacheStats.getOrDefault("idx_read", 0L)).longValue();
                long idxHit = ((Number) cacheStats.getOrDefault("idx_hit", 0L)).longValue();

                long totalRead = heapRead + idxRead;
                long totalHit = heapHit + idxHit;

                if (totalRead + totalHit > 0) {
                    double hitRatio = (totalHit / (double) (totalRead + totalHit)) * 100.0;
                    metrics.put("bufferPoolHitRatio", hitRatio);
                }
            } catch (Exception e) {
                log.debug("    ⚠️  Could not get cache hit ratio: {}", e.getMessage());
            }

        } catch (Exception e) {
            log.warn("    ⚠️  Error collecting CloudWatch metrics: {}", e.getMessage());
        }

        return metrics;
    }

    /**
     * Clean up old snapshots using connection-specific retention
     * Runs daily at 2 AM
     */
    public void cleanupOldSnapshots() {
        log.info("═══════════════════════════════════════════════════════════════");
        log.info("Starting Performance Insights snapshot cleanup...");
        log.info("═══════════════════════════════════════════════════════════════");

        try {
            List<DatabaseConnection> connections = credentialService.getAllConnections();
            int totalDeleted = 0;

            for (DatabaseConnection connection : connections) {
                try {
                    // Get retention setting for this connection
                    int retentionDays = DEFAULT_RETENTION_DAYS;
                    var limitsOpt = resourceLimitsRepository.findByConnectionId(connection.getId());
                    if (limitsOpt.isPresent()) {
                        ResourceLimits limits = limitsOpt.get();
                        if (limits.getPerformanceInsightsRetentionDays() != null) {
                            retentionDays = limits.getPerformanceInsightsRetentionDays();
                        }
                    }

                    LocalDateTime cutoffTime = LocalDateTime.now().minusDays(retentionDays);

                    // Count snapshots before deletion
                    List<PerformanceSnapshot> toDelete = snapshotRepository
                        .findByConnectionIdAndSnapshotTimeBefore(connection.getId(), cutoffTime);

                    if (!toDelete.isEmpty()) {
                        toDelete.forEach(snapshot -> snapshotRepository.delete(snapshot));
                        totalDeleted += toDelete.size();
                        log.info("🗑️  Deleted {} snapshots older than {} days for: {}",
                            toDelete.size(), retentionDays, connection.getConnectionName());
                    }

                } catch (Exception e) {
                    log.error("✗ Failed to cleanup snapshots for {}: {}",
                        connection.getConnectionName(), e.getMessage());
                }
            }

            log.info("═══════════════════════════════════════════════════════════════");
            log.info("✓ Cleanup complete - deleted {} total snapshots", totalDeleted);
            log.info("═══════════════════════════════════════════════════════════════");

        } catch (Exception e) {
            log.error("✗ Error during snapshot cleanup", e);
        }
    }

    /**
     * Get snapshots for a time range
     */
    public List<PerformanceSnapshot> getSnapshots(String connectionId, LocalDateTime startTime, LocalDateTime endTime) {
        return snapshotRepository.findByConnectionIdAndSnapshotTimeBetweenOrderBySnapshotTimeAsc(
                connectionId, startTime, endTime);
    }

    /**
     * Get recent snapshots
     */
    public List<PerformanceSnapshot> getRecentSnapshots(String connectionId, int limit) {
        return snapshotRepository.findRecentByConnectionId(connectionId, limit);
    }

    /**
     * Create JDBC template using ConnectionService's DataSource.
     * This properly handles SSH tunnels for connections that need them.
     */
    private JdbcTemplate createJdbcTemplate(String connectionId, com.dbaagent.model.ConnectionRequest connRequest) {
        // Use ConnectionService to get the DataSource, which handles SSH tunnels properly
        javax.sql.DataSource dataSource = connectionService.getDataSource(connectionId, connRequest);
        return new JdbcTemplate(dataSource);
    }

    /**
     * Convert object to JSON string
     */
    private String convertToJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.error("Failed to convert to JSON", e);
            return "[]";
        }
    }

    /**
     * Get table usage statistics for a connection.
     * Combines database stats with slow query data to calculate usage scores.
     */
    public List<TableUsageDTO> getTableUsage(String connectionId) {
        try {
            com.dbaagent.model.ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

            JdbcTemplate jdbc = createJdbcTemplate(connectionId, connRequest);

            List<TableUsageDTO> tableStats;
            if ("mysql".equals(dbType)) {
                tableStats = collectMySQLTableUsage(jdbc);
            } else if ("postgres".equals(dbType)) {
                tableStats = collectPostgreSQLTableUsage(jdbc);
            } else {
                log.warn("Unsupported database type for table usage: {}", dbType);
                return Collections.emptyList();
            }

            // Enrich with slow query data
            enrichWithSlowQueryData(connectionId, tableStats);

            // Calculate usage scores and sort by score descending
            return tableStats.stream()
                    .peek(this::calculateUsageScore)
                    .sorted((a, b) -> Integer.compare(b.getUsageScore(), a.getUsageScore()))
                    .limit(15)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Failed to get table usage for connection {}: {}", connectionId, e.getMessage());
            throw new RuntimeException("Failed to get table usage", e);
        }
    }

    /**
     * Collect table usage stats from PostgreSQL
     */
    private List<TableUsageDTO> collectPostgreSQLTableUsage(JdbcTemplate jdbc) {
        String sql = """
            SELECT
                schemaname || '.' || relname AS table_name,
                COALESCE(seq_scan, 0) AS seq_scans,
                COALESCE(idx_scan, 0) AS idx_scans,
                COALESCE(seq_tup_read, 0) + COALESCE(idx_tup_fetch, 0) AS rows_read,
                COALESCE(n_tup_ins, 0) + COALESCE(n_tup_upd, 0) + COALESCE(n_tup_del, 0) AS rows_written,
                COALESCE(GREATEST(last_seq_scan, last_idx_scan)::text, 'N/A') AS last_activity
            FROM pg_stat_user_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            ORDER BY seq_scan + idx_scan DESC
            LIMIT 20
        """;

        return jdbc.query(sql, (rs, rowNum) -> TableUsageDTO.builder()
                .tableName(rs.getString("table_name"))
                .seqScans(rs.getLong("seq_scans"))
                .idxScans(rs.getLong("idx_scans"))
                .rowsRead(rs.getLong("rows_read"))
                .rowsWritten(rs.getLong("rows_written"))
                .lastActivity(rs.getString("last_activity"))
                .slowQueryCount(0) // Will be enriched later
                .usageScore(0) // Will be calculated later
                .build());
    }

    /**
     * Collect table usage stats from MySQL
     */
    private List<TableUsageDTO> collectMySQLTableUsage(JdbcTemplate jdbc) {
        String sql = """
            SELECT
                CONCAT(object_schema, '.', object_name) AS table_name,
                COALESCE(count_read, 0) AS total_reads,
                COALESCE(count_write, 0) AS total_writes,
                COALESCE(count_fetch, 0) AS rows_read,
                COALESCE(count_insert, 0) + COALESCE(count_update, 0) + COALESCE(count_delete, 0) AS rows_written
            FROM performance_schema.table_io_waits_summary_by_table
            WHERE object_schema NOT IN ('mysql', 'performance_schema', 'information_schema', 'sys')
            ORDER BY count_read + count_write DESC
            LIMIT 20
        """;

        return jdbc.query(sql, (rs, rowNum) -> TableUsageDTO.builder()
                .tableName(rs.getString("table_name"))
                .seqScans(rs.getLong("total_reads")) // MySQL doesn't distinguish seq vs idx scans
                .idxScans(0)
                .rowsRead(rs.getLong("rows_read"))
                .rowsWritten(rs.getLong("rows_written"))
                .lastActivity("N/A") // MySQL performance_schema doesn't track last access time directly
                .slowQueryCount(0) // Will be enriched later
                .usageScore(0) // Will be calculated later
                .build());
    }

    /**
     * Enrich table stats with slow query counts
     */
    private void enrichWithSlowQueryData(String connectionId, List<TableUsageDTO> tableStats) {
        try {
            // Get recent slow query history
            List<SlowQueryHistory> recentHistory = slowQueryHistoryRepository
                    .findByConnectionIdSince(connectionId, LocalDateTime.now().minusDays(7), PageRequest.of(0, 5));

            // Count table mentions in slow queries
            Map<String, Integer> tableSlowQueryCounts = new HashMap<>();

            for (SlowQueryHistory history : recentHistory) {
                if (history.getAnalysisData() != null) {
                    extractTableNamesFromAnalysis(history.getAnalysisData(), tableSlowQueryCounts);
                }
            }

            // Update table stats with slow query counts
            for (TableUsageDTO stat : tableStats) {
                String tableName = stat.getTableName().toLowerCase();
                // Check both full name and short name
                int count = tableSlowQueryCounts.getOrDefault(tableName, 0);
                if (count == 0 && tableName.contains(".")) {
                    String shortName = tableName.substring(tableName.indexOf('.') + 1);
                    count = tableSlowQueryCounts.getOrDefault(shortName, 0);
                }
                stat.setSlowQueryCount(count);
            }
        } catch (Exception e) {
            log.warn("Failed to enrich with slow query data: {}", e.getMessage());
        }
    }

    /**
     * Extract table names from slow query analysis JSON
     */
    private void extractTableNamesFromAnalysis(String analysisJson, Map<String, Integer> tableCounts) {
        if (analysisJson == null || analysisJson.isEmpty()) return;

        try {
            // Simple regex to find table names in SQL queries within the JSON
            // Matches FROM/JOIN/INTO/UPDATE table patterns
            Pattern tablePattern = Pattern.compile(
                    "(?i)(?:FROM|JOIN|INTO|UPDATE)\\s+[`\"]?([a-zA-Z_][a-zA-Z0-9_]*(?:\\.[a-zA-Z_][a-zA-Z0-9_]*)?)[`\"]?",
                    Pattern.CASE_INSENSITIVE
            );

            Matcher matcher = tablePattern.matcher(analysisJson);
            while (matcher.find()) {
                String tableName = matcher.group(1).toLowerCase();
                tableCounts.merge(tableName, 1, Integer::sum);
            }
        } catch (Exception e) {
            log.debug("Failed to parse analysis JSON for table names: {}", e.getMessage());
        }
    }

    /**
     * Calculate usage score (0-100) based on various factors
     */
    private void calculateUsageScore(TableUsageDTO stat) {
        long totalScans = stat.getSeqScans() + stat.getIdxScans();
        long totalRows = stat.getRowsRead() + stat.getRowsWritten();

        // Base score from scan activity (0-40 points)
        int scanScore = 0;
        if (totalScans > 10000) scanScore = 40;
        else if (totalScans > 5000) scanScore = 35;
        else if (totalScans > 1000) scanScore = 30;
        else if (totalScans > 500) scanScore = 25;
        else if (totalScans > 100) scanScore = 20;
        else if (totalScans > 50) scanScore = 15;
        else if (totalScans > 10) scanScore = 10;
        else if (totalScans > 0) scanScore = 5;

        // Row activity score (0-30 points)
        int rowScore = 0;
        if (totalRows > 1000000) rowScore = 30;
        else if (totalRows > 500000) rowScore = 25;
        else if (totalRows > 100000) rowScore = 20;
        else if (totalRows > 10000) rowScore = 15;
        else if (totalRows > 1000) rowScore = 10;
        else if (totalRows > 0) rowScore = 5;

        // Slow query presence (0-30 points) - weighted higher as it indicates performance issues
        int slowQueryScore = 0;
        if (stat.getSlowQueryCount() > 10) slowQueryScore = 30;
        else if (stat.getSlowQueryCount() > 5) slowQueryScore = 25;
        else if (stat.getSlowQueryCount() > 2) slowQueryScore = 20;
        else if (stat.getSlowQueryCount() > 0) slowQueryScore = 15;

        stat.setUsageScore(Math.min(100, scanScore + rowScore + slowQueryScore));
    }
}
