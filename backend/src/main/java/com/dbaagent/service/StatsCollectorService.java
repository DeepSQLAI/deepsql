package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

@Service
@RequiredArgsConstructor
@Slf4j
public class StatsCollectorService {
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    public DbaStats collectStats(String connectionId) throws SQLException {
        ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
        try (Connection connection = connectionService.getConnection(connectionId, request)) {
            DbaStats stats = new DbaStats();
            String dbType = providerRegistry.getCanonicalName(request.getDbType());

            if ("mysql".equals(dbType)) {
                stats.setConnectionStats(collectMySQLConnectionStats(connection));
                stats.setTableStats(collectMySQLTableStats(connection, request.getDatabase()));
                stats.setQueryPerformance(collectMySQLQueryPerformance(connection));
                stats.setHealth(collectMySQLHealth(connection));
            } else if ("postgres".equals(dbType)) {
                stats.setConnectionStats(collectPostgreSQLConnectionStats(connection));
                stats.setTableStats(collectPostgreSQLTableStats(connection));
                stats.setQueryPerformance(collectPostgreSQLQueryPerformance(connection));
                stats.setHealth(collectPostgreSQLHealth(connection));
            }

            return stats;
        }
    }

    private ConnectionStats collectMySQLConnectionStats(Connection connection) throws SQLException {
        ConnectionStats stats = new ConnectionStats();
        
        try (Statement stmt = connection.createStatement()) {
            // Get connection stats
            try (ResultSet rs = stmt.executeQuery(
                "SHOW STATUS WHERE Variable_name IN ('Threads_connected', 'Max_used_connections', 'Max_connections')")) {
                int threadsConnected = 0;
                int maxConnections = 0;
                
                while (rs.next()) {
                    String varName = rs.getString("Variable_name");
                    int value = rs.getInt("Value");
                    
                    if ("Threads_connected".equals(varName)) {
                        threadsConnected = value;
                    } else if ("Max_connections".equals(varName)) {
                        maxConnections = value;
                    }
                }
                
                stats.setActiveConnections(threadsConnected);
                stats.setMaxConnections(maxConnections);
                stats.setPoolUtilization(maxConnections > 0 ? 
                    (double) threadsConnected / maxConnections * 100 : 0.0);
            }
        } catch (SQLException e) {
            log.warn("Could not collect MySQL connection stats: {}", e.getMessage());
        }
        
        return stats;
    }

    private List<TableStats> collectMySQLTableStats(Connection connection, String database) throws SQLException {
        List<TableStats> tableStats = new ArrayList<>();
        
        String query = "SELECT TABLE_NAME, " +
            "ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024) as SIZE_MB, " +
            "ROUND(INDEX_LENGTH / 1024 / 1024) as INDEX_SIZE_MB, " +
            "TABLE_ROWS " +
            "FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE' " +
            "ORDER BY (DATA_LENGTH + INDEX_LENGTH) DESC " +
            "LIMIT 20";
        
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableStats stat = new TableStats();
                    stat.setTableName(rs.getString("TABLE_NAME"));
                    stat.setSizeBytes(rs.getLong("SIZE_MB") * 1024 * 1024);
                    stat.setIndexSizeBytes(rs.getLong("INDEX_SIZE_MB") * 1024 * 1024);
                    stat.setRowCount(rs.getLong("TABLE_ROWS"));
                    stat.setGrowthRate(0.0); // Would need historical data
                    tableStats.add(stat);
                }
            }
        }
        
        return tableStats;
    }

    private QueryPerformanceStats collectMySQLQueryPerformance(Connection connection) throws SQLException {
        QueryPerformanceStats stats = new QueryPerformanceStats();
        List<SlowQuery> slowQueries = new ArrayList<>();
        
        try {
            // Try to query performance_schema for slow queries
            String query = "SELECT DIGEST_TEXT as query_text, avg_timer_wait/1000000000000 as avg_latency_ms, " +
                "count_star as execution_count, sum_rows_examined as rows_examined " +
                "FROM performance_schema.events_statements_summary_by_digest " +
                "WHERE avg_timer_wait > 1000000000000 " + // > 1 second
                "ORDER BY avg_timer_wait DESC " +
                "LIMIT 10";

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    SlowQuery slowQuery = SlowQuery.builder()
                        .queryText(rs.getString("query_text"))
                        .avgExecutionTimeMs(rs.getDouble("avg_latency_ms"))
                        .callCount(rs.getLong("execution_count"))
                        .rowsExamined(rs.getLong("rows_examined"))
                        .build();
                    slowQueries.add(slowQuery);
                }
            }
        } catch (SQLException e) {
            log.warn("Performance schema not available or not enabled: {}", e.getMessage());
        }
        
        stats.setSlowQueries(slowQueries);
        return stats;
    }

    private DatabaseHealth collectMySQLHealth(Connection connection) throws SQLException {
        DatabaseHealth health = new DatabaseHealth();
        
        try (Statement stmt = connection.createStatement()) {
            // Buffer pool hit ratio
            try (ResultSet rs = stmt.executeQuery(
                "SHOW STATUS WHERE Variable_name IN ('Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads')")) {
                long readRequests = 0;
                long reads = 0;
                
                while (rs.next()) {
                    String varName = rs.getString("Variable_name");
                    long value = rs.getLong("Value");
                    
                    if ("Innodb_buffer_pool_read_requests".equals(varName)) {
                        readRequests = value;
                    } else if ("Innodb_buffer_pool_reads".equals(varName)) {
                        reads = value;
                    }
                }
                
                if (readRequests > 0) {
                    health.setBufferPoolHitRatio(
                        (double) (readRequests - reads) / readRequests * 100);
                }
            }
        } catch (SQLException e) {
            log.warn("Could not collect MySQL health stats: {}", e.getMessage());
        }
        
        return health;
    }

    private ConnectionStats collectPostgreSQLConnectionStats(Connection connection) throws SQLException {
        ConnectionStats stats = new ConnectionStats();
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(
                "SELECT count(*) as active, " +
                "(SELECT setting::int FROM pg_settings WHERE name = 'max_connections') as max_conns " +
                "FROM pg_stat_activity WHERE state = 'active'")) {
            if (rs.next()) {
                int active = rs.getInt("active");
                int maxConnections = rs.getInt("max_conns");
                stats.setActiveConnections(active);
                stats.setMaxConnections(maxConnections);
                stats.setPoolUtilization(maxConnections > 0 ? 
                    (double) active / maxConnections * 100 : 0.0);
            }
        } catch (SQLException e) {
            log.warn("Could not collect PostgreSQL connection stats: {}", e.getMessage());
        }
        
        return stats;
    }

    private List<TableStats> collectPostgreSQLTableStats(Connection connection) throws SQLException {
        List<TableStats> tableStats = new ArrayList<>();
        
        String query = "SELECT schemaname, tablename, " +
            "pg_total_relation_size(schemaname||'.'||tablename) as size_bytes, " +
            "pg_relation_size(schemaname||'.'||tablename) as table_size_bytes, " +
            "(SELECT reltuples::bigint FROM pg_class WHERE relname = tablename) as row_count " +
            "FROM pg_tables " +
            "WHERE schemaname = 'public' " +
            "ORDER BY pg_total_relation_size(schemaname||'.'||tablename) DESC " +
            "LIMIT 20";
        
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                TableStats stat = new TableStats();
                stat.setTableName(rs.getString("tablename"));
                stat.setSizeBytes(rs.getLong("size_bytes"));
                long tableSize = rs.getLong("table_size_bytes");
                stat.setIndexSizeBytes(rs.getLong("size_bytes") - tableSize);
                stat.setRowCount(rs.getLong("row_count"));
                stat.setGrowthRate(0.0);
                tableStats.add(stat);
            }
        }
        
        return tableStats;
    }

    private QueryPerformanceStats collectPostgreSQLQueryPerformance(Connection connection) throws SQLException {
        QueryPerformanceStats stats = new QueryPerformanceStats();
        List<SlowQuery> slowQueries = new ArrayList<>();
        
        try {
            // Query pg_stat_statements for slow queries
            String query = "SELECT query, mean_exec_time/1000 as avg_latency, " +
                "calls as execution_count, rows as rows_examined " +
                "FROM pg_stat_statements " +
                "WHERE mean_exec_time > 1000 " + // > 1 second
                "ORDER BY mean_exec_time DESC " +
                "LIMIT 10";
            
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    SlowQuery slowQuery = SlowQuery.builder()
                        .queryText(rs.getString("query"))
                        .avgExecutionTimeMs(rs.getDouble("avg_latency"))
                        .callCount(rs.getLong("execution_count"))
                        .rowsExamined(rs.getLong("rows_examined"))
                        .build();
                    slowQueries.add(slowQuery);
                }
            }
        } catch (SQLException e) {
            log.warn("pg_stat_statements extension not available: {}", e.getMessage());
        }
        
        stats.setSlowQueries(slowQueries);
        return stats;
    }

    private DatabaseHealth collectPostgreSQLHealth(Connection connection) throws SQLException {
        DatabaseHealth health = new DatabaseHealth();
        
        try (Statement stmt = connection.createStatement()) {
            // Cache hit ratio
            try (ResultSet rs = stmt.executeQuery(
                "SELECT sum(heap_blks_hit) / nullif(sum(heap_blks_hit) + sum(heap_blks_read), 0) * 100 as cache_hit_ratio " +
                "FROM pg_statio_user_tables")) {
                if (rs.next()) {
                    health.setCacheHitRatio(rs.getDouble("cache_hit_ratio"));
                }
            }
            
            // Lock wait time
            try (ResultSet rs = stmt.executeQuery(
                "SELECT COALESCE(SUM(EXTRACT(EPOCH FROM (now() - wait_start))), 0) as lock_wait_time " +
                "FROM pg_locks " +
                "WHERE NOT granted")) {
                if (rs.next()) {
                    health.setLockWaitTime((long) (rs.getDouble("lock_wait_time") * 1000));
                }
            }
        } catch (SQLException e) {
            log.warn("Could not collect PostgreSQL health stats: {}", e.getMessage());
        }
        
        return health;
    }
}



