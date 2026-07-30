package com.dbaagent.provider.mysql;

import com.dbaagent.provider.api.LockProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * MySQL implementation of LockProvider.
 * Uses performance_schema and INFORMATION_SCHEMA for lock information.
 */
@Slf4j
@Component
public class MySQLLockProvider implements LockProvider {

    private static final String DATABASE_TYPE = "mysql";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<Map<String, Object>> getCurrentLocks(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> locks = new ArrayList<>();

        String query = """
            SELECT
                r.trx_id AS requesting_trx_id,
                r.trx_mysql_thread_id AS requesting_thread_id,
                r.trx_query AS requesting_query,
                b.trx_id AS blocking_trx_id,
                b.trx_mysql_thread_id AS blocking_thread_id,
                b.trx_query AS blocking_query
            FROM performance_schema.data_lock_waits w
            JOIN information_schema.innodb_trx r ON w.REQUESTING_ENGINE_TRANSACTION_ID = r.trx_id
            JOIN information_schema.innodb_trx b ON w.BLOCKING_ENGINE_TRANSACTION_ID = b.trx_id
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> lock = new HashMap<>();
                lock.put("requesting_trx_id", rs.getString("requesting_trx_id"));
                lock.put("requesting_thread_id", rs.getLong("requesting_thread_id"));
                lock.put("requesting_query", rs.getString("requesting_query"));
                lock.put("blocking_trx_id", rs.getString("blocking_trx_id"));
                lock.put("blocking_thread_id", rs.getLong("blocking_thread_id"));
                lock.put("blocking_query", rs.getString("blocking_query"));
                locks.add(lock);
            }
        } catch (SQLException e) {
            // Fall back to older INNODB_LOCK_WAITS table for older MySQL versions
            log.debug("data_lock_waits not available, trying innodb_lock_waits: {}", e.getMessage());
            try {
                locks = getCurrentLocksLegacy(connection);
            } catch (SQLException ex) {
                log.debug("innodb_lock_waits also not available: {}", ex.getMessage());
            }
        }

        return locks;
    }

    private List<Map<String, Object>> getCurrentLocksLegacy(Connection connection) throws SQLException {
        List<Map<String, Object>> locks = new ArrayList<>();

        String query = """
            SELECT
                r.trx_id AS requesting_trx_id,
                r.trx_mysql_thread_id AS requesting_thread_id,
                r.trx_query AS requesting_query,
                b.trx_id AS blocking_trx_id,
                b.trx_mysql_thread_id AS blocking_thread_id,
                b.trx_query AS blocking_query
            FROM information_schema.innodb_lock_waits w
            JOIN information_schema.innodb_trx r ON w.requesting_trx_id = r.trx_id
            JOIN information_schema.innodb_trx b ON w.blocking_trx_id = b.trx_id
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> lock = new HashMap<>();
                lock.put("requesting_trx_id", rs.getString("requesting_trx_id"));
                lock.put("requesting_thread_id", rs.getLong("requesting_thread_id"));
                lock.put("requesting_query", rs.getString("requesting_query"));
                lock.put("blocking_trx_id", rs.getString("blocking_trx_id"));
                lock.put("blocking_thread_id", rs.getLong("blocking_thread_id"));
                lock.put("blocking_query", rs.getString("blocking_query"));
                locks.add(lock);
            }
        }

        return locks;
    }

    @Override
    public List<Map<String, Object>> getBlockingQueries(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> blockers = new ArrayList<>();

        String query = """
            SELECT
                b.trx_mysql_thread_id AS blocking_pid,
                b.trx_query AS blocking_query,
                b.trx_started AS blocking_started,
                b.trx_wait_started AS wait_started,
                COUNT(r.trx_id) AS blocked_count
            FROM information_schema.innodb_trx b
            LEFT JOIN information_schema.innodb_trx r ON b.trx_id != r.trx_id
            WHERE b.trx_state = 'RUNNING'
            GROUP BY b.trx_id, b.trx_mysql_thread_id, b.trx_query, b.trx_started, b.trx_wait_started
            HAVING blocked_count > 0
            ORDER BY blocked_count DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> blocker = new HashMap<>();
                blocker.put("blocking_pid", rs.getLong("blocking_pid"));
                blocker.put("blocking_query", rs.getString("blocking_query"));
                blocker.put("blocking_started", rs.getTimestamp("blocking_started"));
                blocker.put("blocked_count", rs.getInt("blocked_count"));
                blockers.add(blocker);
            }
        }

        return blockers;
    }

    @Override
    public Map<String, Object> getLockWaitStats(Connection connection, String database) throws SQLException {
        Map<String, Object> stats = new HashMap<>();

        String query = "SHOW GLOBAL STATUS WHERE Variable_name IN (" +
            "'Innodb_row_lock_waits', 'Innodb_row_lock_time', " +
            "'Innodb_row_lock_time_avg', 'Innodb_row_lock_time_max', " +
            "'Table_locks_waited', 'Table_locks_immediate')";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                stats.put(rs.getString(1), rs.getLong(2));
            }
        }

        // Calculate lock wait ratio
        Object waited = stats.get("Table_locks_waited");
        Object immediate = stats.get("Table_locks_immediate");
        if (waited != null && immediate != null) {
            long w = (Long) waited;
            long i = (Long) immediate;
            if (w + i > 0) {
                stats.put("lock_wait_ratio", (double) w / (w + i));
            }
        }

        return stats;
    }

    @Override
    public List<Map<String, Object>> detectDeadlocks(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> deadlocks = new ArrayList<>();

        // Get latest deadlock info from SHOW ENGINE INNODB STATUS
        String query = "SHOW ENGINE INNODB STATUS";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                String status = rs.getString("Status");
                if (status != null && status.contains("LATEST DETECTED DEADLOCK")) {
                    Map<String, Object> deadlock = new HashMap<>();
                    deadlock.put("status", "Deadlock detected");
                    deadlock.put("details", extractDeadlockInfo(status));
                    deadlocks.add(deadlock);
                }
            }
        }

        return deadlocks;
    }

    private String extractDeadlockInfo(String innodbStatus) {
        int start = innodbStatus.indexOf("LATEST DETECTED DEADLOCK");
        if (start == -1) return "";

        int end = innodbStatus.indexOf("------------", start + 100);
        if (end == -1) end = Math.min(start + 2000, innodbStatus.length());

        return innodbStatus.substring(start, end);
    }
}
