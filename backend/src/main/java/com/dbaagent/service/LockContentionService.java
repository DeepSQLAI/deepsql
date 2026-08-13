package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.LockContention;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.LockContentionRepository;
import com.dbaagent.util.SessionKillSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class LockContentionService {

    private final LockContentionRepository lockContentionRepository;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;

    /**
     * Detect current lock contentions in the database
     */
    @Transactional
    public List<LockContention> detectLockContentions(String connectionId) {
        log.info("Detecting lock contentions for connection: {}", connectionId);

        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());
                List<LockContention> currentContentions;

                if ("postgres".equals(dbType)) {
                    currentContentions = detectPostgreSQLLocks(connectionId, conn);
                } else if ("mysql".equals(dbType)) {
                    currentContentions = detectMySQLLocks(connectionId, conn);
                } else {
                    log.warn("Lock detection not supported for database type: {}", dbType);
                    return Collections.emptyList();
                }

                // Auto-resolve contentions that are no longer present
                autoResolveStaleContentions(connectionId, currentContentions);

                // Save new contentions
                if (!currentContentions.isEmpty()) {
                    lockContentionRepository.saveAll(currentContentions);
                    log.info("Detected {} lock contentions", currentContentions.size());
                }

                return currentContentions;
            }
        } catch (Exception e) {
            log.error("Error detecting lock contentions", e);
            throw new RuntimeException("Failed to detect lock contentions: " + e.getMessage(), e);
        }
    }

    /**
     * Detect PostgreSQL lock contentions using pg_locks and pg_stat_activity
     */
    private List<LockContention> detectPostgreSQLLocks(String connectionId, Connection conn) throws SQLException {
        List<LockContention> contentions = new ArrayList<>();

        String query = """
            SELECT
                blocked_locks.pid AS blocked_pid,
                blocked_activity.usename AS blocked_user,
                blocked_activity.application_name AS blocked_app,
                blocked_activity.query AS blocked_query,
                blocked_activity.state AS blocked_state,
                blocked_activity.query_start AS blocked_start,
                blocked_activity.wait_event_type AS wait_event_type,
                blocked_activity.wait_event AS wait_event,
                blocking_locks.pid AS blocking_pid,
                blocking_activity.usename AS blocking_user,
                blocking_activity.application_name AS blocking_app,
                blocking_activity.query AS blocking_query,
                blocking_activity.state AS blocking_state,
                blocking_activity.query_start AS blocking_start,
                blocked_locks.locktype AS lock_type,
                blocked_locks.mode AS lock_mode,
                blocked_locks.database AS database_oid,
                blocked_locks.relation AS relation_oid,
                current_database() AS current_db,
                COALESCE(n.nspname, '') AS schema_name,
                COALESCE(c.relname, '') AS table_name,
                EXTRACT(EPOCH FROM (NOW() - blocked_activity.query_start)) AS wait_seconds
            FROM pg_locks blocked_locks
            JOIN pg_stat_activity blocked_activity ON blocked_activity.pid = blocked_locks.pid
            JOIN pg_locks blocking_locks
                ON blocking_locks.locktype = blocked_locks.locktype
                AND blocking_locks.database IS NOT DISTINCT FROM blocked_locks.database
                AND blocking_locks.relation IS NOT DISTINCT FROM blocked_locks.relation
                AND blocking_locks.page IS NOT DISTINCT FROM blocked_locks.page
                AND blocking_locks.tuple IS NOT DISTINCT FROM blocked_locks.tuple
                AND blocking_locks.virtualxid IS NOT DISTINCT FROM blocked_locks.virtualxid
                AND blocking_locks.transactionid IS NOT DISTINCT FROM blocked_locks.transactionid
                AND blocking_locks.classid IS NOT DISTINCT FROM blocked_locks.classid
                AND blocking_locks.objid IS NOT DISTINCT FROM blocked_locks.objid
                AND blocking_locks.objsubid IS NOT DISTINCT FROM blocked_locks.objsubid
                AND blocking_locks.pid != blocked_locks.pid
            JOIN pg_stat_activity blocking_activity ON blocking_activity.pid = blocking_locks.pid
            LEFT JOIN pg_class c ON c.oid = blocked_locks.relation
            LEFT JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE NOT blocked_locks.granted
                AND blocked_activity.pid != pg_backend_pid()
            ORDER BY wait_seconds DESC
            """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                long waitSeconds = Math.round(rs.getDouble("wait_seconds"));

                LockContention contention = LockContention.builder()
                    .connectionId(connectionId)
                    .blockingPid(String.valueOf(rs.getInt("blocking_pid")))
                    .blockingQuery(truncateQuery(rs.getString("blocking_query")))
                    .blockingUser(rs.getString("blocking_user"))
                    .blockingApplication(rs.getString("blocking_app"))
                    .blockingState(rs.getString("blocking_state"))
                    .blockingStartTime(toLocalDateTime(rs.getTimestamp("blocking_start")))
                    .blockedPid(String.valueOf(rs.getInt("blocked_pid")))
                    .blockedQuery(truncateQuery(rs.getString("blocked_query")))
                    .blockedUser(rs.getString("blocked_user"))
                    .blockedApplication(rs.getString("blocked_app"))
                    .blockedState(rs.getString("blocked_state"))
                    .blockedStartTime(toLocalDateTime(rs.getTimestamp("blocked_start")))
                    .lockType(rs.getString("lock_type"))
                    .lockMode(rs.getString("lock_mode"))
                    .lockTarget(determinePostgreSQLLockTarget(rs))
                    .databaseName(rs.getString("current_db"))
                    .schemaName(rs.getString("schema_name"))
                    .tableName(rs.getString("table_name"))
                    .waitEvent(rs.getString("wait_event"))
                    .waitEventType(rs.getString("wait_event_type"))
                    .waitDurationSeconds(waitSeconds)
                    .resolved(false)
                    .severity(LockContention.calculateSeverity(waitSeconds))
                    .build();

                contentions.add(contention);
            }
        }

        return contentions;
    }

    /**
     * Detect MySQL lock contentions using information_schema and performance_schema
     */
    private List<LockContention> detectMySQLLocks(String connectionId, Connection conn) throws SQLException {
        List<LockContention> contentions = new ArrayList<>();

        // First, try InnoDB lock waits from performance_schema (MySQL 5.7+)
        String lockWaitsQuery = """
            SELECT
                r.trx_id AS blocking_trx_id,
                r.trx_mysql_thread_id AS blocking_pid,
                r.trx_query AS blocking_query,
                r.trx_state AS blocking_state,
                r.trx_started AS blocking_start,
                w.requesting_trx_id AS blocked_trx_id,
                w.requesting_thread_id AS blocked_pid,
                w.requesting_engine_transaction_id AS blocked_engine_trx,
                b.trx_query AS blocked_query,
                b.trx_state AS blocked_state,
                b.trx_started AS blocked_start,
                w.lock_type,
                w.lock_mode,
                w.lock_table AS table_name,
                w.lock_index AS index_name,
                TIMESTAMPDIFF(SECOND, b.trx_started, NOW()) AS wait_seconds
            FROM information_schema.innodb_lock_waits w
            INNER JOIN information_schema.innodb_trx b ON b.trx_id = w.requesting_trx_id
            INNER JOIN information_schema.innodb_trx r ON r.trx_id = w.blocking_trx_id
            ORDER BY wait_seconds DESC
            """;

        try (Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery(lockWaitsQuery)) {
                while (rs.next()) {
                    long waitSeconds = rs.getLong("wait_seconds");
                    String tableName = rs.getString("table_name");

                    // Extract schema and table from full table name
                    String[] parts = tableName != null ? tableName.split("\\.") : new String[]{};
                    String schemaName = parts.length > 1 ? parts[0].replace("`", "") : "";
                    String simpleTableName = parts.length > 1 ? parts[1].replace("`", "") : tableName;

                    LockContention contention = LockContention.builder()
                        .connectionId(connectionId)
                        .blockingPid(String.valueOf(rs.getLong("blocking_pid")))
                        .blockingQuery(truncateQuery(rs.getString("blocking_query")))
                        .blockingState(rs.getString("blocking_state"))
                        .blockingStartTime(toLocalDateTime(rs.getTimestamp("blocking_start")))
                        .blockedPid(String.valueOf(rs.getLong("blocked_pid")))
                        .blockedQuery(truncateQuery(rs.getString("blocked_query")))
                        .blockedState(rs.getString("blocked_state"))
                        .blockedStartTime(toLocalDateTime(rs.getTimestamp("blocked_start")))
                        .lockType(rs.getString("lock_type"))
                        .lockMode(rs.getString("lock_mode"))
                        .lockTarget(rs.getString("index_name") != null ? "INDEX: " + rs.getString("index_name") : "TABLE")
                        .databaseName(schemaName)
                        .schemaName(schemaName)
                        .tableName(simpleTableName)
                        .waitDurationSeconds(waitSeconds)
                        .resolved(false)
                        .severity(LockContention.calculateSeverity(waitSeconds))
                        .build();

                    contentions.add(contention);
                }
            } catch (SQLException e) {
                // Fallback to processlist if lock waits table not available
                log.warn("innodb_lock_waits not available, checking processlist for blocked queries");
                contentions.addAll(detectMySQLBlockedFromProcesslist(connectionId, conn));
            }
        }

        return contentions;
    }

    /**
     * Fallback method to detect blocked queries from MySQL processlist
     */
    private List<LockContention> detectMySQLBlockedFromProcesslist(String connectionId, Connection conn) throws SQLException {
        List<LockContention> contentions = new ArrayList<>();

        String query = """
            SELECT
                p.ID as pid,
                p.USER as user,
                p.DB as db_name,
                p.COMMAND as command,
                p.TIME as time_seconds,
                p.STATE as state,
                p.INFO as query_text
            FROM information_schema.PROCESSLIST p
            WHERE p.STATE LIKE '%lock%'
               OR p.STATE LIKE '%Waiting%'
            ORDER BY p.TIME DESC
            """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                long waitSeconds = rs.getLong("time_seconds");
                String state = rs.getString("state");

                // Only include if it's clearly waiting on a lock
                if (state != null && (state.toLowerCase().contains("lock") ||
                                     state.toLowerCase().contains("waiting for table"))) {

                    LockContention contention = LockContention.builder()
                        .connectionId(connectionId)
                        .blockingPid("UNKNOWN")
                        .blockingQuery("Unknown blocking query")
                        .blockedPid(String.valueOf(rs.getLong("pid")))
                        .blockedQuery(truncateQuery(rs.getString("query_text")))
                        .blockedUser(rs.getString("user"))
                        .blockedState(state)
                        .databaseName(rs.getString("db_name"))
                        .lockType("TABLE")
                        .waitDurationSeconds(waitSeconds)
                        .resolved(false)
                        .severity(LockContention.calculateSeverity(waitSeconds))
                        .build();

                    contentions.add(contention);
                }
            }
        }

        return contentions;
    }

    /**
     * Kill a blocking session to resolve lock contention
     */
    @Transactional
    public void killBlockingSession(String connectionId, String pid) {
        long backendPid = SessionKillSupport.requireNumericPid(pid);
        log.info("Killing blocking session {} on connection {}", backendPid, connectionId);

        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

                if ("postgres".equals(dbType)) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_terminate_backend(?)")) {
                        ps.setLong(1, backendPid);
                        ps.execute();
                    }
                } else if ("mysql".equals(dbType)) {
                    // MySQL KILL is not reliably parameterizable across drivers; pid is digit-only.
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("KILL " + backendPid);
                    }
                } else {
                    throw new IllegalArgumentException("Kill session not supported for database type: " + dbType);
                }

                log.info("Successfully killed session {}", backendPid);

                // Mark related contentions as resolved (store the original digit string)
                lockContentionRepository.markResolvedByPid(
                    connectionId,
                    Long.toString(backendPid),
                    LocalDateTime.now(),
                    "MANUAL_KILL"
                );
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error killing session {}", backendPid, e);
            throw new RuntimeException("Failed to kill session: " + e.getMessage(), e);
        }
    }

    /**
     * Get active lock contentions
     */
    public List<LockContention> getActiveContentions(String connectionId) {
        return lockContentionRepository.findActiveContentions(connectionId);
    }

    /**
     * Get all contentions (active and resolved)
     */
    public List<LockContention> getAllContentions(String connectionId) {
        return lockContentionRepository.findByConnectionId(connectionId);
    }

    /**
     * Get contention statistics
     */
    public Map<String, Object> getContentionStatistics(String connectionId) {
        List<LockContention> active = lockContentionRepository.findActiveContentions(connectionId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", active.size());
        stats.put("critical", active.stream().filter(c -> c.getSeverity() == LockContention.Severity.CRITICAL).count());
        stats.put("high", active.stream().filter(c -> c.getSeverity() == LockContention.Severity.HIGH).count());
        stats.put("medium", active.stream().filter(c -> c.getSeverity() == LockContention.Severity.MEDIUM).count());
        stats.put("low", active.stream().filter(c -> c.getSeverity() == LockContention.Severity.LOW).count());

        OptionalDouble avgWait = active.stream()
            .filter(c -> c.getWaitDurationSeconds() != null)
            .mapToLong(LockContention::getWaitDurationSeconds)
            .average();
        stats.put("avgWaitSeconds", avgWait.isPresent() ? avgWait.getAsDouble() : 0);

        return stats;
    }

    /**
     * Auto-resolve contentions that are no longer present
     */
    private void autoResolveStaleContentions(String connectionId, List<LockContention> currentContentions) {
        List<LockContention> previousActive = lockContentionRepository.findActiveContentions(connectionId);

        Set<String> currentPairs = new HashSet<>();
        for (LockContention c : currentContentions) {
            currentPairs.add(c.getBlockingPid() + ":" + c.getBlockedPid());
        }

        List<String> toResolve = new ArrayList<>();
        for (LockContention prev : previousActive) {
            String pair = prev.getBlockingPid() + ":" + prev.getBlockedPid();
            if (!currentPairs.contains(pair)) {
                toResolve.add(prev.getId());
            }
        }

        if (!toResolve.isEmpty()) {
            lockContentionRepository.markResolvedBatch(toResolve, LocalDateTime.now());
            log.info("Auto-resolved {} stale contentions", toResolve.size());
        }
    }

    /**
     * Cleanup old resolved contentions (older than 7 days)
     */
    @Transactional
    public int cleanupOldContentions(String connectionId) {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(7);
        int deleted = lockContentionRepository.deleteOldResolved(connectionId, cutoff);
        log.info("Deleted {} old resolved contentions", deleted);
        return deleted;
    }

    // Helper methods

    private String determinePostgreSQLLockTarget(ResultSet rs) throws SQLException {
        String lockType = rs.getString("lock_type");
        if ("relation".equals(lockType)) {
            return "TABLE";
        } else if ("tuple".equals(lockType)) {
            return "ROW";
        } else if ("page".equals(lockType)) {
            return "PAGE";
        } else if ("transactionid".equals(lockType)) {
            return "TRANSACTION";
        } else {
            return lockType != null ? lockType.toUpperCase() : "UNKNOWN";
        }
    }

    private String truncateQuery(String query) {
        if (query == null) {
            return "";
        }
        query = query.trim();
        if (query.length() > 500) {
            return query.substring(0, 497) + "...";
        }
        return query;
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();
    }
}
