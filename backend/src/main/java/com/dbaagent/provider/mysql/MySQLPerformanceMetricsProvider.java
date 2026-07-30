package com.dbaagent.provider.mysql;

import com.dbaagent.model.ActiveQuery;
import com.dbaagent.model.IndexDetail;
import com.dbaagent.provider.api.PerformanceMetricsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * MySQL implementation of PerformanceMetricsProvider.
 * Uses INFORMATION_SCHEMA and performance_schema for metrics collection.
 */
@Slf4j
@Component
public class MySQLPerformanceMetricsProvider implements PerformanceMetricsProvider {

    private static final String DATABASE_TYPE = "mysql";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<ActiveQuery> getActiveQueries(Connection connection, String database) throws SQLException {
        List<ActiveQuery> queries = new ArrayList<>();

        String query = """
            SELECT
                ID AS pid,
                USER AS user,
                DB AS database,
                COMMAND AS state,
                TIME AS duration_seconds,
                INFO AS query_text,
                STATE AS wait_event,
                HOST AS client_address
            FROM INFORMATION_SCHEMA.PROCESSLIST
            WHERE DB = ?
              AND COMMAND != 'Sleep'
              AND ID != CONNECTION_ID()
            ORDER BY TIME DESC
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String host = rs.getString("client_address");
                    String clientAddr = host != null && host.contains(":") ?
                        host.substring(0, host.indexOf(":")) : host;
                    Integer clientPort = null;
                    if (host != null && host.contains(":")) {
                        try {
                            clientPort = Integer.parseInt(host.substring(host.indexOf(":") + 1));
                        } catch (NumberFormatException ignored) {}
                    }

                    long durationSeconds = rs.getLong("duration_seconds");
                    String queryText = rs.getString("query_text");

                    ActiveQuery aq = ActiveQuery.builder()
                        .pid(rs.getString("pid"))
                        .user(rs.getString("user"))
                        .database(rs.getString("database"))
                        .state(rs.getString("state"))
                        .durationSeconds(durationSeconds)
                        .queryText(queryText)
                        .waitEvent(rs.getString("wait_event"))
                        .clientAddress(clientAddr)
                        .clientPort(clientPort)
                        .capturedAt(LocalDateTime.now())
                        .isBlocked(false)
                        .queryType(ActiveQuery.classifyQueryType(queryText))
                        .priority(ActiveQuery.calculatePriority(durationSeconds, "active"))
                        .build();

                    queries.add(aq);
                }
            }
        }

        return queries;
    }

    @Override
    public List<IndexDetail> getUnusedIndexes(Connection connection, String database) throws SQLException {
        List<IndexDetail> indexes = new ArrayList<>();

        // Check if performance_schema is enabled
        try {
            String query = """
                SELECT
                    t.TABLE_NAME,
                    s.INDEX_NAME,
                    s.INDEX_TYPE,
                    s.NON_UNIQUE,
                    GROUP_CONCAT(s.COLUMN_NAME ORDER BY s.SEQ_IN_INDEX) AS columns
                FROM INFORMATION_SCHEMA.STATISTICS s
                JOIN INFORMATION_SCHEMA.TABLES t ON s.TABLE_SCHEMA = t.TABLE_SCHEMA AND s.TABLE_NAME = t.TABLE_NAME
                LEFT JOIN performance_schema.table_io_waits_summary_by_index_usage io
                    ON s.TABLE_SCHEMA = io.OBJECT_SCHEMA
                    AND s.TABLE_NAME = io.OBJECT_NAME
                    AND s.INDEX_NAME = io.INDEX_NAME
                WHERE s.TABLE_SCHEMA = ?
                  AND s.INDEX_NAME != 'PRIMARY'
                  AND (io.COUNT_READ = 0 OR io.COUNT_READ IS NULL)
                GROUP BY t.TABLE_NAME, s.INDEX_NAME, s.INDEX_TYPE, s.NON_UNIQUE
                """;

            try (PreparedStatement stmt = connection.prepareStatement(query)) {
                stmt.setString(1, database);
                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        IndexDetail idx = IndexDetail.builder()
                            .indexName(rs.getString("INDEX_NAME"))
                            .indexType(rs.getString("INDEX_TYPE"))
                            .columns(Arrays.asList(rs.getString("columns").split(",")))
                            .isUnique(!rs.getBoolean("NON_UNIQUE"))
                            .isPrimary(false)
                            .build();
                        indexes.add(idx);
                    }
                }
            }
        } catch (SQLException e) {
            log.debug("Performance schema not available for unused index detection: {}", e.getMessage());
        }

        return indexes;
    }

    @Override
    public List<IndexDetail> getDuplicateIndexes(Connection connection, String database) throws SQLException {
        List<IndexDetail> indexes = new ArrayList<>();

        String query = """
            SELECT
                s1.TABLE_NAME,
                s1.INDEX_NAME AS duplicate_index,
                s2.INDEX_NAME AS redundant_with,
                GROUP_CONCAT(s1.COLUMN_NAME ORDER BY s1.SEQ_IN_INDEX) AS columns
            FROM INFORMATION_SCHEMA.STATISTICS s1
            JOIN INFORMATION_SCHEMA.STATISTICS s2
                ON s1.TABLE_SCHEMA = s2.TABLE_SCHEMA
                AND s1.TABLE_NAME = s2.TABLE_NAME
                AND s1.INDEX_NAME != s2.INDEX_NAME
                AND s1.SEQ_IN_INDEX = s2.SEQ_IN_INDEX
                AND s1.COLUMN_NAME = s2.COLUMN_NAME
            WHERE s1.TABLE_SCHEMA = ?
              AND s1.INDEX_NAME != 'PRIMARY'
              AND s2.INDEX_NAME != 'PRIMARY'
            GROUP BY s1.TABLE_NAME, s1.INDEX_NAME, s2.INDEX_NAME
            HAVING COUNT(*) = (
                SELECT COUNT(*) FROM INFORMATION_SCHEMA.STATISTICS
                WHERE TABLE_SCHEMA = s1.TABLE_SCHEMA
                  AND TABLE_NAME = s1.TABLE_NAME
                  AND INDEX_NAME = s1.INDEX_NAME
            )
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    IndexDetail idx = IndexDetail.builder()
                        .indexName(rs.getString("duplicate_index"))
                        .columns(Arrays.asList(rs.getString("columns").split(",")))
                        .isPrimary(false)
                        .build();
                    indexes.add(idx);
                }
            }
        }

        return indexes;
    }

    @Override
    public Map<String, Object> getPerformanceMetrics(Connection connection, String database) throws SQLException {
        Map<String, Object> metrics = new HashMap<>();

        // Get status variables
        String query = "SHOW GLOBAL STATUS WHERE Variable_name IN (" +
            "'Queries', 'Slow_queries', 'Threads_connected', 'Threads_running', " +
            "'Questions', 'Uptime', 'Innodb_buffer_pool_read_requests', " +
            "'Innodb_buffer_pool_reads', 'Table_locks_waited', 'Table_locks_immediate')";

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                metrics.put(rs.getString(1), rs.getString(2));
            }
        }

        // Calculate QPS
        Object queries = metrics.get("Questions");
        Object uptime = metrics.get("Uptime");
        if (queries != null && uptime != null) {
            long q = Long.parseLong(queries.toString());
            long u = Long.parseLong(uptime.toString());
            if (u > 0) {
                metrics.put("queries_per_second", (double) q / u);
            }
        }

        return metrics;
    }

    @Override
    public Double getCacheHitRatio(Connection connection) throws SQLException {
        String query = "SHOW GLOBAL STATUS WHERE Variable_name IN (" +
            "'Innodb_buffer_pool_read_requests', 'Innodb_buffer_pool_reads')";

        long readRequests = 0;
        long reads = 0;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String name = rs.getString(1);
                long value = rs.getLong(2);
                if ("Innodb_buffer_pool_read_requests".equals(name)) {
                    readRequests = value;
                } else if ("Innodb_buffer_pool_reads".equals(name)) {
                    reads = value;
                }
            }
        }

        if (readRequests > 0) {
            return (double) (readRequests - reads) / readRequests;
        }
        return 1.0;
    }
}
