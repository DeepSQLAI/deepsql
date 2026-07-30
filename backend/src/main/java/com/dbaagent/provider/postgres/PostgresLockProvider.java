package com.dbaagent.provider.postgres;

import com.dbaagent.provider.api.LockProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of LockProvider.
 * Uses pg_locks and pg_stat_activity for lock information.
 */
@Slf4j
@Component
public class PostgresLockProvider implements LockProvider {

    private static final String DATABASE_TYPE = "postgres";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<Map<String, Object>> getCurrentLocks(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> locks = new ArrayList<>();

        String query = """
            SELECT
                l.pid,
                l.locktype,
                l.mode,
                l.granted,
                l.relation::regclass AS relation,
                a.usename AS user,
                a.query AS query,
                a.state,
                EXTRACT(EPOCH FROM (now() - a.query_start))::bigint AS duration_seconds,
                a.wait_event_type,
                a.wait_event
            FROM pg_locks l
            JOIN pg_stat_activity a ON l.pid = a.pid
            WHERE a.datname = ?
              AND l.pid != pg_backend_pid()
            ORDER BY l.granted, duration_seconds DESC
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> lock = new HashMap<>();
                    lock.put("pid", rs.getInt("pid"));
                    lock.put("lock_type", rs.getString("locktype"));
                    lock.put("mode", rs.getString("mode"));
                    lock.put("granted", rs.getBoolean("granted"));
                    lock.put("relation", rs.getString("relation"));
                    lock.put("user", rs.getString("user"));
                    lock.put("query", rs.getString("query"));
                    lock.put("state", rs.getString("state"));
                    lock.put("duration_seconds", rs.getLong("duration_seconds"));
                    lock.put("wait_event_type", rs.getString("wait_event_type"));
                    lock.put("wait_event", rs.getString("wait_event"));
                    locks.add(lock);
                }
            }
        }

        return locks;
    }

    @Override
    public List<Map<String, Object>> getBlockingQueries(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> blockers = new ArrayList<>();

        String query = """
            SELECT
                blocked_locks.pid AS blocked_pid,
                blocked_activity.usename AS blocked_user,
                blocked_activity.query AS blocked_query,
                blocking_locks.pid AS blocking_pid,
                blocking_activity.usename AS blocking_user,
                blocking_activity.query AS blocking_query,
                EXTRACT(EPOCH FROM (now() - blocked_activity.query_start))::bigint AS blocked_duration,
                EXTRACT(EPOCH FROM (now() - blocking_activity.query_start))::bigint AS blocking_duration
            FROM pg_locks blocked_locks
            JOIN pg_stat_activity blocked_activity ON blocked_locks.pid = blocked_activity.pid
            JOIN pg_locks blocking_locks ON blocked_locks.locktype = blocking_locks.locktype
                AND blocked_locks.database IS NOT DISTINCT FROM blocking_locks.database
                AND blocked_locks.relation IS NOT DISTINCT FROM blocking_locks.relation
                AND blocked_locks.page IS NOT DISTINCT FROM blocking_locks.page
                AND blocked_locks.tuple IS NOT DISTINCT FROM blocking_locks.tuple
                AND blocked_locks.virtualxid IS NOT DISTINCT FROM blocking_locks.virtualxid
                AND blocked_locks.transactionid IS NOT DISTINCT FROM blocking_locks.transactionid
                AND blocked_locks.classid IS NOT DISTINCT FROM blocking_locks.classid
                AND blocked_locks.objid IS NOT DISTINCT FROM blocking_locks.objid
                AND blocked_locks.objsubid IS NOT DISTINCT FROM blocking_locks.objsubid
                AND blocked_locks.pid != blocking_locks.pid
            JOIN pg_stat_activity blocking_activity ON blocking_locks.pid = blocking_activity.pid
            WHERE NOT blocked_locks.granted
              AND blocking_locks.granted
              AND blocked_activity.datname = ?
            ORDER BY blocked_duration DESC
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> blocker = new HashMap<>();
                    blocker.put("blocked_pid", rs.getInt("blocked_pid"));
                    blocker.put("blocked_user", rs.getString("blocked_user"));
                    blocker.put("blocked_query", rs.getString("blocked_query"));
                    blocker.put("blocking_pid", rs.getInt("blocking_pid"));
                    blocker.put("blocking_user", rs.getString("blocking_user"));
                    blocker.put("blocking_query", rs.getString("blocking_query"));
                    blocker.put("blocked_duration", rs.getLong("blocked_duration"));
                    blocker.put("blocking_duration", rs.getLong("blocking_duration"));
                    blockers.add(blocker);
                }
            }
        }

        return blockers;
    }

    @Override
    public Map<String, Object> getLockWaitStats(Connection connection, String database) throws SQLException {
        Map<String, Object> stats = new HashMap<>();

        // Count locks by type
        String lockCountQuery = """
            SELECT
                locktype,
                mode,
                granted,
                COUNT(*) AS count
            FROM pg_locks l
            JOIN pg_stat_activity a ON l.pid = a.pid
            WHERE a.datname = ?
            GROUP BY locktype, mode, granted
            """;

        try (PreparedStatement stmt = connection.prepareStatement(lockCountQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                List<Map<String, Object>> lockCounts = new ArrayList<>();
                while (rs.next()) {
                    Map<String, Object> lockCount = new HashMap<>();
                    lockCount.put("lock_type", rs.getString("locktype"));
                    lockCount.put("mode", rs.getString("mode"));
                    lockCount.put("granted", rs.getBoolean("granted"));
                    lockCount.put("count", rs.getInt("count"));
                    lockCounts.add(lockCount);
                }
                stats.put("lock_counts", lockCounts);
            }
        }

        // Get deadlock count from pg_stat_database
        String deadlockQuery = """
            SELECT deadlocks FROM pg_stat_database WHERE datname = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(deadlockQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.put("total_deadlocks", rs.getLong("deadlocks"));
                }
            }
        }

        // Count waiting locks
        String waitingQuery = """
            SELECT COUNT(*) AS waiting_count
            FROM pg_locks
            WHERE NOT granted
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(waitingQuery)) {
            if (rs.next()) {
                stats.put("waiting_locks", rs.getInt("waiting_count"));
            }
        }

        return stats;
    }

    @Override
    public List<Map<String, Object>> detectDeadlocks(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> deadlocks = new ArrayList<>();

        // PostgreSQL automatically resolves deadlocks by killing one of the transactions
        // We can check pg_stat_database for deadlock count changes
        String query = """
            SELECT
                deadlocks,
                stats_reset
            FROM pg_stat_database
            WHERE datname = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    long deadlockCount = rs.getLong("deadlocks");
                    if (deadlockCount > 0) {
                        Map<String, Object> info = new HashMap<>();
                        info.put("total_deadlocks", deadlockCount);
                        info.put("stats_reset", rs.getTimestamp("stats_reset"));
                        info.put("message", "Database has recorded " + deadlockCount + " deadlocks since stats reset");
                        deadlocks.add(info);
                    }
                }
            }
        }

        // Check for potential deadlocks (circular waits)
        String circularWaitQuery = """
            WITH RECURSIVE lock_tree AS (
                SELECT
                    l1.pid AS waiter_pid,
                    l2.pid AS holder_pid,
                    ARRAY[l1.pid, l2.pid] AS path,
                    false AS is_cycle
                FROM pg_locks l1
                JOIN pg_locks l2 ON l1.locktype = l2.locktype
                    AND l1.database IS NOT DISTINCT FROM l2.database
                    AND l1.relation IS NOT DISTINCT FROM l2.relation
                    AND l1.pid != l2.pid
                WHERE NOT l1.granted AND l2.granted

                UNION ALL

                SELECT
                    lt.waiter_pid,
                    l2.pid AS holder_pid,
                    lt.path || l2.pid,
                    l2.pid = ANY(lt.path)
                FROM lock_tree lt
                JOIN pg_locks l1 ON lt.holder_pid = l1.pid AND NOT l1.granted
                JOIN pg_locks l2 ON l1.locktype = l2.locktype
                    AND l1.database IS NOT DISTINCT FROM l2.database
                    AND l1.relation IS NOT DISTINCT FROM l2.relation
                    AND l1.pid != l2.pid
                    AND l2.granted
                WHERE NOT lt.is_cycle
            )
            SELECT DISTINCT path FROM lock_tree WHERE is_cycle
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(circularWaitQuery)) {
            while (rs.next()) {
                Map<String, Object> deadlock = new HashMap<>();
                deadlock.put("type", "circular_wait_detected");
                deadlock.put("involved_pids", rs.getArray("path").getArray());
                deadlocks.add(deadlock);
            }
        } catch (SQLException e) {
            // Recursive query may not work on all PostgreSQL versions
            log.debug("Could not check for circular waits: {}", e.getMessage());
        }

        return deadlocks;
    }
}
