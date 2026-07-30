package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.TableStats;
import com.dbaagent.provider.DatabaseProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * Performance monitoring and diagnostics service
 * Provides access to database performance statistics and slow query analysis
 * Supports both PostgreSQL and MySQL databases (including Aurora, MariaDB aliases)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PerformanceMonitoringService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    @Value("${db.query-timeout-seconds:30}")
    private int queryTimeoutSeconds;

    /**
     * Get slow queries from pg_stat_statements (PostgreSQL) or performance_schema (MySQL)
     */
    public List<SlowQuery> getSlowQueries(String connectionId, int limit) {
        List<SlowQuery> slowQueries = new ArrayList<>();
        int queryLimit = limit > 0 ? limit : 50;

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    slowQueries = getSlowQueriesPostgres(connection, queryLimit);
                } else if ("mysql".equals(dbType)) {
                    slowQueries = getSlowQueriesMySQL(connection, queryLimit);
                } else {
                    log.warn("Unsupported database type for slow queries: {}", connReq.getDbType());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching slow queries for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return slowQueries;
    }

    private void applyStatementSettings(PreparedStatement pstmt) throws SQLException {
        if (queryTimeoutSeconds > 0) {
            pstmt.setQueryTimeout(queryTimeoutSeconds);
        }
    }

    private List<SlowQuery> getSlowQueriesPostgres(Connection connection, int limit) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();

        // Check if pg_stat_statements extension is available
        String checkExtension = "SELECT COUNT(*) FROM pg_extension WHERE extname = 'pg_stat_statements'";
        boolean hasExtension = false;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(checkExtension)) {
            if (rs.next()) {
                hasExtension = rs.getInt(1) > 0;
            }
        }

        if (!hasExtension) {
            log.warn("pg_stat_statements extension not available - slow query data limited");
            return queries;
        }

        String sql = """
            SELECT
                queryid::text as query_id,
                query as query_text,
                calls as call_count,
                (total_exec_time / NULLIF(calls, 0)) as avg_exec_time_ms,
                max_exec_time as max_exec_time_ms,
                min_exec_time as min_exec_time_ms,
                total_exec_time as total_exec_time_ms,
                stddev_exec_time as stddev_exec_time_ms,
                rows as total_rows,
                (rows / NULLIF(calls, 0)) as avg_rows,
                shared_blks_hit,
                shared_blks_read,
                CASE WHEN (shared_blks_hit + shared_blks_read) > 0
                    THEN shared_blks_hit::float / (shared_blks_hit + shared_blks_read)
                    ELSE 1.0
                END as cache_hit_ratio
            FROM pg_stat_statements
            WHERE query NOT LIKE 'COMMIT%'
              AND query NOT LIKE 'BEGIN%'
              AND query NOT LIKE 'ROLLBACK%'
            ORDER BY total_exec_time DESC
            LIMIT ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            applyStatementSettings(pstmt);
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    SlowQuery sq = SlowQuery.builder()
                            .queryId(rs.getString("query_id"))
                            .queryText(rs.getString("query_text"))
                            .callCount(rs.getLong("call_count"))
                            .avgExecutionTimeMs(rs.getDouble("avg_exec_time_ms"))
                            .maxExecutionTimeMs(rs.getDouble("max_exec_time_ms"))
                            .minExecutionTimeMs(rs.getDouble("min_exec_time_ms"))
                            .totalExecutionTimeMs(rs.getDouble("total_exec_time_ms"))
                            .stdDevExecutionTimeMs(rs.getDouble("stddev_exec_time_ms"))
                            .rowsSent(rs.getLong("total_rows"))
                            .avgRowsSent(rs.getLong("avg_rows"))
                            .cacheHitRatio(rs.getDouble("cache_hit_ratio"))
                            .source("pg_stat_statements")
                            .build();

                    // Calculate severity and impact
                    sq.setSeverity(sq.calculateSeverity());
                    sq.setPerformanceImpact(sq.calculatePerformanceImpact());

                    queries.add(sq);
                }
            }
        } catch (SQLException e) {
            // pg_stat_statements might have different column names in older versions
            log.warn("Error querying pg_stat_statements (may be older version): {}", e.getMessage());
        }

        return queries;
    }

    private List<SlowQuery> getSlowQueriesMySQL(Connection connection, int limit) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();

        // Use performance_schema.events_statements_summary_by_digest
        String sql = """
            SELECT
                DIGEST as query_id,
                DIGEST_TEXT as query_text,
                COUNT_STAR as call_count,
                (SUM_TIMER_WAIT / NULLIF(COUNT_STAR, 0) / 1000000000) as avg_exec_time_ms,
                (MAX_TIMER_WAIT / 1000000000) as max_exec_time_ms,
                (MIN_TIMER_WAIT / 1000000000) as min_exec_time_ms,
                (SUM_TIMER_WAIT / 1000000000) as total_exec_time_ms,
                SUM_ROWS_EXAMINED as rows_examined,
                SUM_ROWS_SENT as rows_sent,
                (SUM_ROWS_EXAMINED / NULLIF(COUNT_STAR, 0)) as avg_rows_examined,
                (SUM_ROWS_SENT / NULLIF(COUNT_STAR, 0)) as avg_rows_sent,
                FIRST_SEEN as first_seen,
                LAST_SEEN as last_seen
            FROM performance_schema.events_statements_summary_by_digest
            WHERE DIGEST_TEXT NOT LIKE 'COMMIT%'
              AND DIGEST_TEXT NOT LIKE 'BEGIN%'
              AND DIGEST_TEXT IS NOT NULL
            ORDER BY SUM_TIMER_WAIT DESC
            LIMIT ?
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            applyStatementSettings(pstmt);
            pstmt.setInt(1, limit);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Timestamp firstSeen = rs.getTimestamp("first_seen");
                    Timestamp lastSeen = rs.getTimestamp("last_seen");

                    SlowQuery sq = SlowQuery.builder()
                            .queryId(rs.getString("query_id"))
                            .queryText(rs.getString("query_text"))
                            .normalizedQuery(rs.getString("query_text"))
                            .callCount(rs.getLong("call_count"))
                            .avgExecutionTimeMs(rs.getDouble("avg_exec_time_ms"))
                            .maxExecutionTimeMs(rs.getDouble("max_exec_time_ms"))
                            .minExecutionTimeMs(rs.getDouble("min_exec_time_ms"))
                            .totalExecutionTimeMs(rs.getDouble("total_exec_time_ms"))
                            .rowsExamined(rs.getLong("rows_examined"))
                            .rowsSent(rs.getLong("rows_sent"))
                            .avgRowsExamined(rs.getLong("avg_rows_examined"))
                            .avgRowsSent(rs.getLong("avg_rows_sent"))
                            .firstSeen(firstSeen != null ? firstSeen.toLocalDateTime() : null)
                            .lastSeen(lastSeen != null ? lastSeen.toLocalDateTime() : null)
                            .source("performance_schema")
                            .build();

                    // Calculate severity and impact
                    sq.setSeverity(sq.calculateSeverity());
                    sq.setPerformanceImpact(sq.calculatePerformanceImpact());

                    queries.add(sq);
                }
            }
        } catch (SQLException e) {
            log.warn("Error querying performance_schema: {}", e.getMessage());
        }

        return queries;
    }

    /**
     * Get current database activity (active connections and their queries)
     */
    public List<Map<String, Object>> getCurrentActivity(String connectionId) {
        List<Map<String, Object>> activity = new ArrayList<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    activity = getCurrentActivityPostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    activity = getCurrentActivityMySQL(connection);
                } else {
                    log.warn("Unsupported database type for activity: {}", connReq.getDbType());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching current activity for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return activity;
    }

    private List<Map<String, Object>> getCurrentActivityPostgres(Connection connection) throws SQLException {
        List<Map<String, Object>> activity = new ArrayList<>();

        String sql = """
            SELECT
                pid,
                usename as username,
                application_name,
                client_addr,
                client_hostname,
                datname as database_name,
                state,
                COALESCE(query, '') as query,
                EXTRACT(EPOCH FROM (now() - query_start)) as query_duration_seconds,
                EXTRACT(EPOCH FROM (now() - backend_start)) as connection_duration_seconds,
                wait_event_type,
                wait_event,
                query_start,
                backend_start
            FROM pg_stat_activity
            WHERE pid != pg_backend_pid()
              AND state IS NOT NULL
            ORDER BY query_start DESC NULLS LAST
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("pid", rs.getInt("pid"));
                row.put("username", rs.getString("username"));
                row.put("applicationName", rs.getString("application_name"));
                row.put("clientAddr", rs.getString("client_addr"));
                row.put("clientHostname", rs.getString("client_hostname"));
                row.put("databaseName", rs.getString("database_name"));
                row.put("state", rs.getString("state"));
                row.put("query", rs.getString("query"));
                row.put("queryDurationSeconds", rs.getDouble("query_duration_seconds"));
                row.put("connectionDurationSeconds", rs.getDouble("connection_duration_seconds"));
                row.put("waitEventType", rs.getString("wait_event_type"));
                row.put("waitEvent", rs.getString("wait_event"));

                Timestamp queryStart = rs.getTimestamp("query_start");
                Timestamp backendStart = rs.getTimestamp("backend_start");
                row.put("queryStart", queryStart != null ? queryStart.toLocalDateTime() : null);
                row.put("backendStart", backendStart != null ? backendStart.toLocalDateTime() : null);

                activity.add(row);
            }
        }

        return activity;
    }

    private List<Map<String, Object>> getCurrentActivityMySQL(Connection connection) throws SQLException {
        List<Map<String, Object>> activity = new ArrayList<>();

        String sql = """
            SELECT
                ID as connection_id,
                USER as username,
                HOST as client_host,
                DB as database_name,
                COMMAND as command,
                TIME as query_duration_seconds,
                STATE as state,
                INFO as query
            FROM information_schema.processlist
            WHERE ID != CONNECTION_ID()
            ORDER BY TIME DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("connectionId", rs.getLong("connection_id"));
                row.put("username", rs.getString("username"));
                row.put("clientHost", rs.getString("client_host"));
                row.put("databaseName", rs.getString("database_name"));
                row.put("command", rs.getString("command"));
                row.put("queryDurationSeconds", rs.getLong("query_duration_seconds"));
                row.put("state", rs.getString("state"));
                row.put("query", rs.getString("query"));

                activity.add(row);
            }
        }

        return activity;
    }

    /**
     * Get active locks in the database
     */
    public List<Map<String, Object>> getActiveLocks(String connectionId) {
        List<Map<String, Object>> locks = new ArrayList<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    locks = getActiveLocksPostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    locks = getActiveLocksMySQL(connection);
                } else {
                    log.warn("Unsupported database type for locks: {}", connReq.getDbType());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching active locks for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return locks;
    }

    private List<Map<String, Object>> getActiveLocksPostgres(Connection connection) throws SQLException {
        List<Map<String, Object>> locks = new ArrayList<>();

        String sql = """
            SELECT
                l.locktype,
                l.relation::regclass as table_name,
                l.mode as lock_mode,
                l.granted,
                l.pid,
                a.usename as username,
                a.query as current_query,
                a.state,
                EXTRACT(EPOCH FROM (now() - a.query_start)) as duration_seconds,
                l.page,
                l.tuple
            FROM pg_locks l
            LEFT JOIN pg_stat_activity a ON l.pid = a.pid
            WHERE l.relation IS NOT NULL
              AND l.pid != pg_backend_pid()
            ORDER BY l.granted, a.query_start
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lockType", rs.getString("locktype"));
                row.put("tableName", rs.getString("table_name"));
                row.put("lockMode", rs.getString("lock_mode"));
                row.put("granted", rs.getBoolean("granted"));
                row.put("pid", rs.getInt("pid"));
                row.put("username", rs.getString("username"));
                row.put("currentQuery", rs.getString("current_query"));
                row.put("state", rs.getString("state"));
                row.put("durationSeconds", rs.getDouble("duration_seconds"));
                row.put("page", rs.getObject("page"));
                row.put("tuple", rs.getObject("tuple"));

                locks.add(row);
            }
        }

        return locks;
    }

    private List<Map<String, Object>> getActiveLocksMySQL(Connection connection) throws SQLException {
        List<Map<String, Object>> locks = new ArrayList<>();

        // Try performance_schema.data_locks (MySQL 8.0+)
        String sql = """
            SELECT
                dl.ENGINE as engine,
                dl.ENGINE_LOCK_ID as lock_id,
                dl.ENGINE_TRANSACTION_ID as transaction_id,
                dl.THREAD_ID as thread_id,
                dl.OBJECT_SCHEMA as schema_name,
                dl.OBJECT_NAME as table_name,
                dl.INDEX_NAME as index_name,
                dl.LOCK_TYPE as lock_type,
                dl.LOCK_MODE as lock_mode,
                dl.LOCK_STATUS as lock_status,
                dl.LOCK_DATA as lock_data,
                t.PROCESSLIST_ID as process_id,
                t.PROCESSLIST_USER as username,
                t.PROCESSLIST_TIME as duration_seconds,
                t.PROCESSLIST_STATE as state,
                t.PROCESSLIST_INFO as current_query
            FROM performance_schema.data_locks dl
            LEFT JOIN performance_schema.threads t ON dl.THREAD_ID = t.THREAD_ID
            WHERE t.PROCESSLIST_ID IS NOT NULL
            ORDER BY dl.LOCK_STATUS, t.PROCESSLIST_TIME DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("engine", rs.getString("engine"));
                row.put("lockId", rs.getString("lock_id"));
                row.put("transactionId", rs.getString("transaction_id"));
                row.put("threadId", rs.getLong("thread_id"));
                row.put("schemaName", rs.getString("schema_name"));
                row.put("tableName", rs.getString("table_name"));
                row.put("indexName", rs.getString("index_name"));
                row.put("lockType", rs.getString("lock_type"));
                row.put("lockMode", rs.getString("lock_mode"));
                row.put("lockStatus", rs.getString("lock_status"));
                row.put("lockData", rs.getString("lock_data"));
                row.put("processId", rs.getObject("process_id"));
                row.put("username", rs.getString("username"));
                row.put("durationSeconds", rs.getObject("duration_seconds"));
                row.put("state", rs.getString("state"));
                row.put("currentQuery", rs.getString("current_query"));

                locks.add(row);
            }
        } catch (SQLException e) {
            log.debug("data_locks table not available (MySQL < 8.0), trying INNODB_LOCKS");
            // Fallback for older MySQL versions
            locks = getActiveLocksMySQL57(connection);
        }

        return locks;
    }

    private List<Map<String, Object>> getActiveLocksMySQL57(Connection connection) throws SQLException {
        List<Map<String, Object>> locks = new ArrayList<>();

        String sql = """
            SELECT
                lock_id,
                lock_trx_id as transaction_id,
                lock_mode,
                lock_type,
                lock_table as table_name,
                lock_index as index_name,
                lock_space,
                lock_page,
                lock_rec,
                lock_data
            FROM information_schema.innodb_locks
            ORDER BY lock_trx_id
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("lockId", rs.getString("lock_id"));
                row.put("transactionId", rs.getString("transaction_id"));
                row.put("lockMode", rs.getString("lock_mode"));
                row.put("lockType", rs.getString("lock_type"));
                row.put("tableName", rs.getString("table_name"));
                row.put("indexName", rs.getString("index_name"));
                row.put("lockSpace", rs.getObject("lock_space"));
                row.put("lockPage", rs.getObject("lock_page"));
                row.put("lockRec", rs.getObject("lock_rec"));
                row.put("lockData", rs.getString("lock_data"));

                locks.add(row);
            }
        } catch (SQLException e) {
            log.warn("Could not fetch lock information: {}", e.getMessage());
        }

        return locks;
    }

    /**
     * Get detailed statistics for a specific table
     */
    public TableStats getTableStats(String connectionId, String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return null;
        }

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    return getTableStatsPostgres(connection, tableName);
                } else if ("mysql".equals(dbType)) {
                    return getTableStatsMySQL(connection, tableName);
                } else {
                    log.warn("Unsupported database type for table stats: {}", connReq.getDbType());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching table stats for connection {}, table {}: {}",
                    connectionId, tableName, e.getMessage(), e);
        }

        return null;
    }

    private TableStats getTableStatsPostgres(Connection connection, String tableName) throws SQLException {
        TableStats stats = new TableStats();
        stats.setTableName(tableName);

        // Get size and row count
        String sizeSql = """
            SELECT
                pg_total_relation_size(c.oid) as total_size,
                pg_table_size(c.oid) as table_size,
                pg_indexes_size(c.oid) as index_size,
                COALESCE(s.n_live_tup, 0) as row_count,
                COALESCE(s.n_dead_tup, 0) as dead_tuples,
                COALESCE(s.seq_scan, 0) as seq_scans,
                COALESCE(s.idx_scan, 0) as idx_scans
            FROM pg_class c
            LEFT JOIN pg_stat_user_tables s ON c.relname = s.relname AND c.relnamespace = s.schemaname::regnamespace
            WHERE c.relname = ? AND c.relkind = 'r'
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sizeSql)) {
            applyStatementSettings(pstmt);
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    stats.setSizeBytes(rs.getLong("total_size"));
                    stats.setDataSize(rs.getLong("table_size"));
                    stats.setIndexSize(rs.getLong("index_size"));
                    stats.setIndexSizeBytes(rs.getLong("index_size"));
                    stats.setRowCount(rs.getLong("row_count"));

                    // Calculate bloat from dead tuples
                    long deadTuples = rs.getLong("dead_tuples");
                    long liveTuples = rs.getLong("row_count");
                    if (liveTuples > 0) {
                        stats.setBloatPercent(100.0 * deadTuples / (liveTuples + deadTuples));
                    }
                }
            }
        }

        // Get bloat estimate using pgstattuple extension if available
        try {
            String bloatSql = "SELECT * FROM pgstattuple(?)";
            try (PreparedStatement pstmt = connection.prepareStatement(bloatSql)) {
                applyStatementSettings(pstmt);
                pstmt.setString(1, tableName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        long tableLen = rs.getLong("table_len");
                        long deadTupleLen = rs.getLong("dead_tuple_len");
                        long freeSpace = rs.getLong("free_space");

                        stats.setAllocatedBytes(tableLen);
                        stats.setBloatBytes(deadTupleLen + freeSpace);
                        if (tableLen > 0) {
                            stats.setBloatPercent(100.0 * (deadTupleLen + freeSpace) / tableLen);
                        }
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("pgstattuple extension not available, using basic bloat estimate");
        }

        return stats;
    }

    private TableStats getTableStatsMySQL(Connection connection, String tableName) throws SQLException {
        TableStats stats = new TableStats();
        stats.setTableName(tableName);

        String sql = """
            SELECT
                TABLE_NAME,
                ENGINE,
                TABLE_COLLATION,
                TABLE_ROWS,
                AVG_ROW_LENGTH,
                DATA_LENGTH,
                INDEX_LENGTH,
                DATA_FREE,
                TABLE_COMMENT
            FROM information_schema.TABLES
            WHERE TABLE_NAME = ?
              AND TABLE_SCHEMA = DATABASE()
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            applyStatementSettings(pstmt);
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                if (rs.next()) {
                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setCollation(rs.getString("TABLE_COLLATION"));
                    stats.setRowCount(rs.getLong("TABLE_ROWS"));
                    stats.setDataSize(rs.getLong("DATA_LENGTH"));
                    stats.setIndexSize(rs.getLong("INDEX_LENGTH"));
                    stats.setIndexSizeBytes(rs.getLong("INDEX_LENGTH"));
                    stats.setSizeBytes(rs.getLong("DATA_LENGTH") + rs.getLong("INDEX_LENGTH"));
                    stats.setComment(rs.getString("TABLE_COMMENT"));

                    // DATA_FREE represents fragmented/unused space (bloat)
                    long dataFree = rs.getLong("DATA_FREE");
                    long dataLength = rs.getLong("DATA_LENGTH");
                    stats.setBloatBytes(dataFree);
                    stats.setAllocatedBytes(dataLength + dataFree);
                    if (dataLength > 0) {
                        stats.setBloatPercent(100.0 * dataFree / dataLength);
                    }
                }
            }
        }

        return stats;
    }

    /**
     * Get index usage statistics for a table
     */
    public List<Map<String, Object>> getIndexUsageStats(String connectionId, String tableName) {
        List<Map<String, Object>> stats = new ArrayList<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    stats = getIndexUsageStatsPostgres(connection, tableName);
                } else if ("mysql".equals(dbType)) {
                    stats = getIndexUsageStatsMySQL(connection, tableName);
                } else {
                    log.warn("Unsupported database type for index stats: {}", connReq.getDbType());
                }
            }
        } catch (Exception e) {
            log.error("Error fetching index usage stats for connection {}, table {}: {}",
                    connectionId, tableName, e.getMessage(), e);
        }

        return stats;
    }

    private List<Map<String, Object>> getIndexUsageStatsPostgres(Connection connection, String tableName) throws SQLException {
        List<Map<String, Object>> stats = new ArrayList<>();

        String sql = """
            SELECT
                i.indexrelname as index_name,
                i.relname as table_name,
                i.idx_scan as index_scans,
                i.idx_tup_read as tuples_read,
                i.idx_tup_fetch as tuples_fetched,
                pg_relation_size(i.indexrelid) as index_size_bytes,
                pg_size_pretty(pg_relation_size(i.indexrelid)) as index_size,
                idx.indisunique as is_unique,
                idx.indisprimary as is_primary,
                pg_get_indexdef(i.indexrelid) as index_definition
            FROM pg_stat_user_indexes i
            JOIN pg_index idx ON i.indexrelid = idx.indexrelid
            WHERE i.relname = ?
            ORDER BY i.idx_scan DESC
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            applyStatementSettings(pstmt);
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("indexName", rs.getString("index_name"));
                    row.put("tableName", rs.getString("table_name"));
                    row.put("indexScans", rs.getLong("index_scans"));
                    row.put("tuplesRead", rs.getLong("tuples_read"));
                    row.put("tuplesFetched", rs.getLong("tuples_fetched"));
                    row.put("indexSizeBytes", rs.getLong("index_size_bytes"));
                    row.put("indexSize", rs.getString("index_size"));
                    row.put("isUnique", rs.getBoolean("is_unique"));
                    row.put("isPrimary", rs.getBoolean("is_primary"));
                    row.put("indexDefinition", rs.getString("index_definition"));

                    // Flag unused indexes
                    row.put("isUnused", rs.getLong("index_scans") == 0);

                    stats.add(row);
                }
            }
        }

        return stats;
    }

    private List<Map<String, Object>> getIndexUsageStatsMySQL(Connection connection, String tableName) throws SQLException {
        List<Map<String, Object>> stats = new ArrayList<>();

        // Try sys.schema_index_statistics first (MySQL 5.7+)
        String sql = """
            SELECT
                s.index_name,
                s.table_name,
                s.table_schema,
                s.rows_selected,
                s.rows_inserted,
                s.rows_updated,
                s.rows_deleted,
                COALESCE(i.stat_value * @@innodb_page_size, 0) as index_size_bytes,
                st.NON_UNIQUE = 0 as is_unique,
                st.INDEX_NAME = 'PRIMARY' as is_primary
            FROM sys.schema_index_statistics s
            LEFT JOIN mysql.innodb_index_stats i
                ON i.database_name = s.table_schema
                AND i.table_name = s.table_name
                AND i.index_name = s.index_name
                AND i.stat_name = 'size'
            LEFT JOIN information_schema.STATISTICS st
                ON st.TABLE_SCHEMA = s.table_schema
                AND st.TABLE_NAME = s.table_name
                AND st.INDEX_NAME = s.index_name
                AND st.SEQ_IN_INDEX = 1
            WHERE s.table_name = ?
              AND s.table_schema = DATABASE()
            ORDER BY s.rows_selected DESC
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            applyStatementSettings(pstmt);
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("indexName", rs.getString("index_name"));
                    row.put("tableName", rs.getString("table_name"));
                    row.put("schemaName", rs.getString("table_schema"));
                    row.put("rowsSelected", rs.getLong("rows_selected"));
                    row.put("rowsInserted", rs.getLong("rows_inserted"));
                    row.put("rowsUpdated", rs.getLong("rows_updated"));
                    row.put("rowsDeleted", rs.getLong("rows_deleted"));
                    row.put("indexSizeBytes", rs.getLong("index_size_bytes"));
                    row.put("isUnique", rs.getBoolean("is_unique"));
                    row.put("isPrimary", rs.getBoolean("is_primary"));

                    // Flag unused indexes
                    row.put("isUnused", rs.getLong("rows_selected") == 0);

                    stats.add(row);
                }
            }
        } catch (SQLException e) {
            log.debug("sys.schema_index_statistics not available, using basic index info");
            stats = getBasicIndexStatsMySQL(connection, tableName);
        }

        return stats;
    }

    private List<Map<String, Object>> getBasicIndexStatsMySQL(Connection connection, String tableName) throws SQLException {
        List<Map<String, Object>> stats = new ArrayList<>();

        String sql = """
            SELECT
                INDEX_NAME as index_name,
                TABLE_NAME as table_name,
                NON_UNIQUE = 0 as is_unique,
                INDEX_NAME = 'PRIMARY' as is_primary,
                GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) as columns,
                INDEX_TYPE as index_type
            FROM information_schema.STATISTICS
            WHERE TABLE_NAME = ?
              AND TABLE_SCHEMA = DATABASE()
            GROUP BY INDEX_NAME, TABLE_NAME, NON_UNIQUE, INDEX_TYPE
            ORDER BY INDEX_NAME
            """;

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            applyStatementSettings(pstmt);
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("indexName", rs.getString("index_name"));
                    row.put("tableName", rs.getString("table_name"));
                    row.put("isUnique", rs.getBoolean("is_unique"));
                    row.put("isPrimary", rs.getBoolean("is_primary"));
                    row.put("columns", rs.getString("columns"));
                    row.put("indexType", rs.getString("index_type"));

                    stats.add(row);
                }
            }
        }

        return stats;
    }

    /**
     * Get database cache hit ratios
     */
    public Map<String, Double> getCacheHitRatios(String connectionId) {
        Map<String, Double> ratios = new LinkedHashMap<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    ratios = getCacheHitRatiosPostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    ratios = getCacheHitRatiosMySQL(connection);
                } else {
                    log.warn("Unsupported database type for cache ratios: {}", connReq.getDbType());
                    ratios.put("cache_hit_ratio", 0.0);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching cache hit ratios for connection {}: {}", connectionId, e.getMessage(), e);
            ratios.put("cache_hit_ratio", 0.0);
        }

        return ratios;
    }

    private Map<String, Double> getCacheHitRatiosPostgres(Connection connection) throws SQLException {
        Map<String, Double> ratios = new LinkedHashMap<>();

        // Overall buffer cache hit ratio
        String overallSql = """
            SELECT
                sum(heap_blks_hit) / NULLIF(sum(heap_blks_hit) + sum(heap_blks_read), 0) as heap_hit_ratio,
                sum(idx_blks_hit) / NULLIF(sum(idx_blks_hit) + sum(idx_blks_read), 0) as idx_hit_ratio,
                sum(toast_blks_hit) / NULLIF(sum(toast_blks_hit) + sum(toast_blks_read), 0) as toast_hit_ratio,
                sum(tidx_blks_hit) / NULLIF(sum(tidx_blks_hit) + sum(tidx_blks_read), 0) as tidx_hit_ratio
            FROM pg_statio_user_tables
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(overallSql)) {
            if (rs.next()) {
                Double heapRatio = rs.getDouble("heap_hit_ratio");
                if (!rs.wasNull()) ratios.put("heap_cache_hit_ratio", heapRatio);

                Double idxRatio = rs.getDouble("idx_hit_ratio");
                if (!rs.wasNull()) ratios.put("index_cache_hit_ratio", idxRatio);

                Double toastRatio = rs.getDouble("toast_hit_ratio");
                if (!rs.wasNull()) ratios.put("toast_cache_hit_ratio", toastRatio);

                Double tidxRatio = rs.getDouble("tidx_hit_ratio");
                if (!rs.wasNull()) ratios.put("toast_index_cache_hit_ratio", tidxRatio);
            }
        }

        // Database-level cache hit ratio
        String dbSql = """
            SELECT
                datname,
                blks_hit::float / NULLIF(blks_hit + blks_read, 0) as cache_hit_ratio
            FROM pg_stat_database
            WHERE datname = current_database()
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(dbSql)) {
            if (rs.next()) {
                Double ratio = rs.getDouble("cache_hit_ratio");
                if (!rs.wasNull()) {
                    ratios.put("database_cache_hit_ratio", ratio);
                    ratios.put("cache_hit_ratio", ratio); // Overall metric
                }
            }
        }

        // Calculate overall if not set
        if (!ratios.containsKey("cache_hit_ratio")) {
            Double heapRatio = ratios.getOrDefault("heap_cache_hit_ratio", 0.0);
            Double idxRatio = ratios.getOrDefault("index_cache_hit_ratio", 0.0);
            ratios.put("cache_hit_ratio", (heapRatio + idxRatio) / 2);
        }

        return ratios;
    }

    private Map<String, Double> getCacheHitRatiosMySQL(Connection connection) throws SQLException {
        Map<String, Double> ratios = new LinkedHashMap<>();

        // InnoDB buffer pool hit ratio
        String sql = """
            SELECT
                VARIABLE_NAME, VARIABLE_VALUE
            FROM performance_schema.global_status
            WHERE VARIABLE_NAME IN (
                'Innodb_buffer_pool_read_requests',
                'Innodb_buffer_pool_reads',
                'Key_read_requests',
                'Key_reads',
                'Qcache_hits',
                'Com_select'
            )
            """;

        Map<String, Long> statusVars = new HashMap<>();

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String name = rs.getString("VARIABLE_NAME");
                String value = rs.getString("VARIABLE_VALUE");
                try {
                    statusVars.put(name, Long.parseLong(value));
                } catch (NumberFormatException e) {
                    log.debug("Could not parse status variable {}: {}", name, value);
                }
            }
        } catch (SQLException e) {
            // Fallback to SHOW GLOBAL STATUS for older MySQL versions
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("SHOW GLOBAL STATUS WHERE Variable_name IN ('Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads', 'Key_read_requests', 'Key_reads')")) {
                while (rs.next()) {
                    String name = rs.getString(1);
                    String value = rs.getString(2);
                    try {
                        statusVars.put(name, Long.parseLong(value));
                    } catch (NumberFormatException ex) {
                        log.debug("Could not parse status variable {}: {}", name, value);
                    }
                }
            }
        }

        // Calculate InnoDB buffer pool hit ratio
        Long readRequests = statusVars.get("Innodb_buffer_pool_read_requests");
        Long reads = statusVars.get("Innodb_buffer_pool_reads");
        if (readRequests != null && reads != null && readRequests > 0) {
            double hitRatio = 1.0 - (reads.doubleValue() / readRequests.doubleValue());
            ratios.put("innodb_buffer_pool_hit_ratio", hitRatio);
            ratios.put("cache_hit_ratio", hitRatio); // Use as overall metric
        }

        // Calculate key cache hit ratio (MyISAM)
        Long keyReadRequests = statusVars.get("Key_read_requests");
        Long keyReads = statusVars.get("Key_reads");
        if (keyReadRequests != null && keyReads != null && keyReadRequests > 0) {
            double keyHitRatio = 1.0 - (keyReads.doubleValue() / keyReadRequests.doubleValue());
            ratios.put("key_cache_hit_ratio", keyHitRatio);
        }

        // Set default if nothing calculated
        if (!ratios.containsKey("cache_hit_ratio")) {
            ratios.put("cache_hit_ratio", 0.0);
        }

        return ratios;
    }

    /**
     * Get all unused indexes across all tables
     */
    public List<Map<String, Object>> getUnusedIndexes(String connectionId) {
        List<Map<String, Object>> unused = new ArrayList<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    unused = getUnusedIndexesPostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    unused = getUnusedIndexesMySQL(connection);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching unused indexes for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return unused;
    }

    private List<Map<String, Object>> getUnusedIndexesPostgres(Connection connection) throws SQLException {
        List<Map<String, Object>> unused = new ArrayList<>();

        String sql = """
            SELECT
                schemaname,
                relname as table_name,
                indexrelname as index_name,
                idx_scan as index_scans,
                pg_relation_size(indexrelid) as index_size_bytes,
                pg_size_pretty(pg_relation_size(indexrelid)) as index_size
            FROM pg_stat_user_indexes
            WHERE idx_scan = 0
              AND indexrelname NOT LIKE 'pg_%'
            ORDER BY pg_relation_size(indexrelid) DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schemaName", rs.getString("schemaname"));
                row.put("tableName", rs.getString("table_name"));
                row.put("indexName", rs.getString("index_name"));
                row.put("indexScans", rs.getLong("index_scans"));
                row.put("indexSizeBytes", rs.getLong("index_size_bytes"));
                row.put("indexSize", rs.getString("index_size"));

                unused.add(row);
            }
        }

        return unused;
    }

    private List<Map<String, Object>> getUnusedIndexesMySQL(Connection connection) throws SQLException {
        List<Map<String, Object>> unused = new ArrayList<>();

        String sql = """
            SELECT
                s.table_schema as schema_name,
                s.table_name,
                s.index_name,
                s.rows_selected,
                COALESCE(i.stat_value * @@innodb_page_size, 0) as index_size_bytes
            FROM sys.schema_index_statistics s
            LEFT JOIN mysql.innodb_index_stats i
                ON i.database_name = s.table_schema
                AND i.table_name = s.table_name
                AND i.index_name = s.index_name
                AND i.stat_name = 'size'
            WHERE s.rows_selected = 0
              AND s.index_name != 'PRIMARY'
              AND s.table_schema = DATABASE()
            ORDER BY COALESCE(i.stat_value * @@innodb_page_size, 0) DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schemaName", rs.getString("schema_name"));
                row.put("tableName", rs.getString("table_name"));
                row.put("indexName", rs.getString("index_name"));
                row.put("rowsSelected", rs.getLong("rows_selected"));
                row.put("indexSizeBytes", rs.getLong("index_size_bytes"));

                unused.add(row);
            }
        } catch (SQLException e) {
            log.debug("sys.schema_index_statistics not available for unused index detection");
        }

        return unused;
    }

    /**
     * Get duplicate indexes
     */
    public List<Map<String, Object>> getDuplicateIndexes(String connectionId) {
        List<Map<String, Object>> duplicates = new ArrayList<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    duplicates = getDuplicateIndexesPostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    duplicates = getDuplicateIndexesMySQL(connection);
                }
            }
        } catch (Exception e) {
            log.error("Error fetching duplicate indexes for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return duplicates;
    }

    private List<Map<String, Object>> getDuplicateIndexesPostgres(Connection connection) throws SQLException {
        List<Map<String, Object>> duplicates = new ArrayList<>();

        // Find indexes with identical column sets
        String sql = """
            WITH index_cols AS (
                SELECT
                    n.nspname as schema_name,
                    t.relname as table_name,
                    i.relname as index_name,
                    pg_get_indexdef(idx.indexrelid) as index_def,
                    array_agg(a.attname ORDER BY x.n) as columns,
                    pg_relation_size(idx.indexrelid) as index_size
                FROM pg_index idx
                JOIN pg_class i ON i.oid = idx.indexrelid
                JOIN pg_class t ON t.oid = idx.indrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                CROSS JOIN LATERAL unnest(idx.indkey) WITH ORDINALITY AS x(attnum, n)
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.attnum
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                GROUP BY n.nspname, t.relname, i.relname, idx.indexrelid
            )
            SELECT
                a.schema_name,
                a.table_name,
                a.index_name as index1_name,
                b.index_name as index2_name,
                a.columns::text as columns,
                a.index_size as index1_size,
                b.index_size as index2_size,
                a.index_def as index1_def,
                b.index_def as index2_def
            FROM index_cols a
            JOIN index_cols b ON a.table_name = b.table_name
                AND a.schema_name = b.schema_name
                AND a.columns = b.columns
                AND a.index_name < b.index_name
            ORDER BY a.index_size + b.index_size DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schemaName", rs.getString("schema_name"));
                row.put("tableName", rs.getString("table_name"));
                row.put("index1Name", rs.getString("index1_name"));
                row.put("index2Name", rs.getString("index2_name"));
                row.put("columns", rs.getString("columns"));
                row.put("index1Size", rs.getLong("index1_size"));
                row.put("index2Size", rs.getLong("index2_size"));
                row.put("index1Definition", rs.getString("index1_def"));
                row.put("index2Definition", rs.getString("index2_def"));

                duplicates.add(row);
            }
        }

        return duplicates;
    }

    private List<Map<String, Object>> getDuplicateIndexesMySQL(Connection connection) throws SQLException {
        List<Map<String, Object>> duplicates = new ArrayList<>();

        // Use sys.schema_redundant_indexes if available
        String sql = """
            SELECT
                table_schema as schema_name,
                table_name,
                redundant_index_name as index1_name,
                dominant_index_name as index2_name,
                redundant_index_columns as index1_columns,
                dominant_index_columns as index2_columns,
                sql_drop_index as drop_statement
            FROM sys.schema_redundant_indexes
            WHERE table_schema = DATABASE()
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("schemaName", rs.getString("schema_name"));
                row.put("tableName", rs.getString("table_name"));
                row.put("redundantIndexName", rs.getString("index1_name"));
                row.put("dominantIndexName", rs.getString("index2_name"));
                row.put("redundantIndexColumns", rs.getString("index1_columns"));
                row.put("dominantIndexColumns", rs.getString("index2_columns"));
                row.put("dropStatement", rs.getString("drop_statement"));

                duplicates.add(row);
            }
        } catch (SQLException e) {
            log.debug("sys.schema_redundant_indexes not available");
        }

        return duplicates;
    }

    // ------------------------------------------------------------------
    // DBA-grade index-advisor helpers: write-cost stats + reset-time guard.
    // ------------------------------------------------------------------

    /**
     * Per-table write activity, used to penalise indexes on write-heavy tables.
     * Returns a map of {@code lower(table_name) → row}. Each row contains:
     * {@code inserts}, {@code updates}, {@code deletes} (all {@link Long}),
     * plus {@code totalWrites = inserts + updates + deletes}.
     *
     * Source: {@code pg_stat_user_tables} on Postgres,
     * {@code performance_schema.table_io_waits_summary_by_table} on MySQL.
     * Returns an empty map if the probe is unavailable (graceful — write-cost
     * defaults to 0 in that case, equivalent to no penalty).
     */
    public Map<String, Map<String, Object>> getTableWriteStats(String connectionId) {
        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());
            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    return getTableWriteStatsPostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    return getTableWriteStatsMySQL(connection, connReq.getDatabase());
                }
            }
        } catch (Exception e) {
            log.warn("getTableWriteStats failed for {}: {}", connectionId, e.getMessage());
        }
        return Map.of();
    }

    private Map<String, Map<String, Object>> getTableWriteStatsPostgres(Connection connection) throws SQLException {
        Map<String, Map<String, Object>> out = new HashMap<>();
        String sql = """
            SELECT relname,
                   COALESCE(n_tup_ins, 0)  AS inserts,
                   COALESCE(n_tup_upd, 0)  AS updates,
                   COALESCE(n_tup_del, 0)  AS deletes
            FROM pg_stat_user_tables
            """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                long ins = rs.getLong("inserts");
                long upd = rs.getLong("updates");
                long del = rs.getLong("deletes");
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("inserts", ins);
                row.put("updates", upd);
                row.put("deletes", del);
                row.put("totalWrites", ins + upd + del);
                out.put(rs.getString("relname").toLowerCase(Locale.ROOT), row);
            }
        }
        return out;
    }

    private Map<String, Map<String, Object>> getTableWriteStatsMySQL(Connection connection, String database) throws SQLException {
        Map<String, Map<String, Object>> out = new HashMap<>();
        String sql = """
            SELECT object_name AS table_name,
                   COUNT_INSERT AS inserts,
                   COUNT_UPDATE AS updates,
                   COUNT_DELETE AS deletes
            FROM performance_schema.table_io_waits_summary_by_table
            WHERE object_schema = ?
              AND object_type = 'TABLE'
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long ins = rs.getLong("inserts");
                    long upd = rs.getLong("updates");
                    long del = rs.getLong("deletes");
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("inserts", ins);
                    row.put("updates", upd);
                    row.put("deletes", del);
                    row.put("totalWrites", ins + upd + del);
                    out.put(rs.getString("table_name").toLowerCase(Locale.ROOT), row);
                }
            }
        }
        return out;
    }

    /**
     * Full index-coverage map for a connection, keyed by lowercase table name.
     * Each value is the list of indexes on that table; each index is an ordered
     * list of column names (lowercased).
     *
     * Coverage includes EVERY index — PRIMARY KEY, UNIQUE constraints, and
     * plain secondary indexes — because the recommendation pipeline must not
     * suggest creating an index whose leading-column prefix is already served
     * by an existing one. The previous {@code getMySQL/PostgreSQLIndexedColumns}
     * helpers (still on {@link IndexRecommendationService}) explicitly excluded
     * the primary key, which produced the {@code ERECEIPT_BOOKING(booking_id)}
     * regression: {@code booking_id} was the PK, so the schema walker thought
     * it was unindexed.
     *
     * Returns an empty map when the probe is unavailable; callers should treat
     * an empty map as "no opinion" and not skip filtering.
     */
    public Map<String, List<List<String>>> getIndexCoverage(String connectionId) {
        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());
            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    return getIndexCoveragePostgres(connection);
                } else if ("mysql".equals(dbType)) {
                    return getIndexCoverageMySQL(connection, connReq.getDatabase());
                }
            }
        } catch (Exception e) {
            log.warn("getIndexCoverage failed for {}: {}", connectionId, e.getMessage());
        }
        return Map.of();
    }

    private Map<String, List<List<String>>> getIndexCoveragePostgres(Connection connection) throws SQLException {
        // pg_index.indkey is an int2vector of column attnums in column ORDER.
        // We need the ordering preserved for leading-prefix matching.
        Map<String, List<List<String>>> out = new HashMap<>();
        String sql = """
            SELECT t.relname AS table_name,
                   ARRAY(
                       SELECT a.attname
                       FROM unnest(i.indkey) WITH ORDINALITY AS k(attnum, ord)
                       JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = k.attnum
                       ORDER BY k.ord
                   ) AS columns
            FROM pg_index i
            JOIN pg_class t ON t.oid = i.indrelid
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
              AND i.indisvalid
              AND array_length(i.indkey, 1) > 0
            """;
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            while (rs.next()) {
                String table = rs.getString("table_name");
                if (table == null) continue;
                java.sql.Array arr = rs.getArray("columns");
                if (arr == null) continue;
                String[] cols = (String[]) arr.getArray();
                if (cols == null || cols.length == 0) continue;
                List<String> ordered = new ArrayList<>(cols.length);
                for (String c : cols) {
                    if (c != null) ordered.add(c.toLowerCase(Locale.ROOT));
                }
                if (ordered.isEmpty()) continue;
                out.computeIfAbsent(table.toLowerCase(Locale.ROOT), k -> new ArrayList<>()).add(ordered);
            }
        }
        return out;
    }

    private Map<String, List<List<String>>> getIndexCoverageMySQL(Connection connection, String database) throws SQLException {
        // INFORMATION_SCHEMA.STATISTICS.SEQ_IN_INDEX is 1-based column position
        // within the index. We bucket per (table, indexName), preserving order.
        Map<String, Map<String, List<String>>> byTableAndIndex = new HashMap<>();
        String sql = """
            SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, SEQ_IN_INDEX
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String table = rs.getString("TABLE_NAME");
                    String indexName = rs.getString("INDEX_NAME");
                    String col = rs.getString("COLUMN_NAME");
                    if (table == null || indexName == null || col == null) continue;
                    byTableAndIndex
                        .computeIfAbsent(table.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>())
                        .computeIfAbsent(indexName, k -> new ArrayList<>())
                        .add(col.toLowerCase(Locale.ROOT));
                }
            }
        }
        Map<String, List<List<String>>> out = new HashMap<>();
        for (Map.Entry<String, Map<String, List<String>>> tableEntry : byTableAndIndex.entrySet()) {
            out.put(tableEntry.getKey(), new ArrayList<>(tableEntry.getValue().values()));
        }
        return out;
    }

    /**
     * Returns how long ago the stats counters used by the unused-index probe
     * were reset, in seconds. An index "unused" verdict 2 days after a reset
     * is meaningless — callers should demote DROP_INDEX recommendations to
     * LOW priority when this returns {@code < 7 * 86400}.
     *
     * Postgres: {@code pg_stat_database.stats_reset} for the current DB.
     * MySQL: best-effort proxy via {@code Uptime_since_flush_status} global
     * status variable. Returns {@link Optional#empty()} when unavailable.
     */
    public Optional<Long> getStatsResetAgeSeconds(String connectionId) {
        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());
            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    try (Statement stmt = connection.createStatement();
                         ResultSet rs = stmt.executeQuery(
                             "SELECT EXTRACT(EPOCH FROM (NOW() - stats_reset))::BIGINT AS age " +
                             "FROM pg_stat_database WHERE datname = current_database()")) {
                        if (rs.next()) {
                            long v = rs.getLong("age");
                            if (!rs.wasNull()) return Optional.of(v);
                        }
                    }
                } else if ("mysql".equals(dbType)) {
                    try (Statement stmt = connection.createStatement();
                         ResultSet rs = stmt.executeQuery(
                             "SHOW GLOBAL STATUS LIKE 'Uptime_since_flush_status'")) {
                        if (rs.next()) {
                            try {
                                return Optional.of(Long.parseLong(rs.getString(2)));
                            } catch (NumberFormatException ignored) {
                                return Optional.empty();
                            }
                        }
                    }
                }
            }
        } catch (Exception e) {
            log.debug("getStatsResetAgeSeconds unavailable for {}: {}", connectionId, e.getMessage());
        }
        return Optional.empty();
    }
}
