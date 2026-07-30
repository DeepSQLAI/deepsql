package com.dbaagent.provider.postgres;

import com.dbaagent.model.ActiveQuery;
import com.dbaagent.model.IndexDetail;
import com.dbaagent.provider.api.PerformanceMetricsProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;

/**
 * PostgreSQL implementation of PerformanceMetricsProvider.
 * Uses pg_stat_activity and pg_stat_user_indexes for metrics collection.
 */
@Slf4j
@Component
public class PostgresPerformanceMetricsProvider implements PerformanceMetricsProvider {

    private static final String DATABASE_TYPE = "postgres";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<ActiveQuery> getActiveQueries(Connection connection, String database) throws SQLException {
        List<ActiveQuery> queries = new ArrayList<>();

        String query = """
            SELECT
                pid::text AS pid,
                usename AS user,
                datname AS database,
                application_name,
                client_addr::text AS client_address,
                client_port,
                state,
                query AS query_text,
                query_start,
                state_change,
                backend_start,
                xact_start AS transaction_start,
                EXTRACT(EPOCH FROM (now() - query_start))::bigint AS duration_seconds,
                EXTRACT(EPOCH FROM (now() - xact_start))::bigint AS transaction_duration_seconds,
                wait_event,
                wait_event_type
            FROM pg_stat_activity
            WHERE datname = ?
              AND pid != pg_backend_pid()
              AND state != 'idle'
            ORDER BY duration_seconds DESC NULLS LAST
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String queryText = rs.getString("query_text");
                    long durationSeconds = rs.getLong("duration_seconds");
                    String state = rs.getString("state");

                    ActiveQuery aq = ActiveQuery.builder()
                        .pid(rs.getString("pid"))
                        .user(rs.getString("user"))
                        .database(rs.getString("database"))
                        .applicationName(rs.getString("application_name"))
                        .clientAddress(rs.getString("client_address"))
                        .clientPort(rs.getObject("client_port") != null ?
                            rs.getInt("client_port") : null)
                        .state(state)
                        .queryText(queryText)
                        .queryStart(rs.getTimestamp("query_start") != null ?
                            rs.getTimestamp("query_start").toLocalDateTime() : null)
                        .stateChange(rs.getTimestamp("state_change") != null ?
                            rs.getTimestamp("state_change").toLocalDateTime() : null)
                        .backendStart(rs.getTimestamp("backend_start") != null ?
                            rs.getTimestamp("backend_start").toLocalDateTime() : null)
                        .transactionStart(rs.getTimestamp("transaction_start") != null ?
                            rs.getTimestamp("transaction_start").toLocalDateTime() : null)
                        .durationSeconds(durationSeconds)
                        .transactionDurationSeconds(rs.getLong("transaction_duration_seconds"))
                        .waitEvent(rs.getString("wait_event"))
                        .waitEventType(rs.getString("wait_event_type"))
                        .capturedAt(LocalDateTime.now())
                        .isBlocked(rs.getString("wait_event_type") != null &&
                            rs.getString("wait_event_type").equals("Lock"))
                        .queryType(ActiveQuery.classifyQueryType(queryText))
                        .priority(ActiveQuery.calculatePriority(durationSeconds, state))
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

        String query = """
            SELECT
                schemaname,
                relname AS table_name,
                indexrelname AS index_name,
                idx_scan,
                idx_tup_read,
                idx_tup_fetch,
                pg_relation_size(indexrelid) AS index_size
            FROM pg_stat_user_indexes
            WHERE schemaname = 'public'
              AND idx_scan = 0
              AND indexrelname NOT LIKE '%_pkey'
            ORDER BY pg_relation_size(indexrelid) DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                IndexDetail idx = IndexDetail.builder()
                    .indexName(rs.getString("index_name"))
                    .indexSize(rs.getLong("index_size"))
                    .isPrimary(false)
                    .build();
                indexes.add(idx);
            }
        }

        return indexes;
    }

    @Override
    public List<IndexDetail> getDuplicateIndexes(Connection connection, String database) throws SQLException {
        List<IndexDetail> indexes = new ArrayList<>();

        String query = """
            SELECT
                a.indexrelid::regclass AS index1,
                b.indexrelid::regclass AS index2,
                pg_relation_size(a.indexrelid) AS index1_size,
                pg_relation_size(b.indexrelid) AS index2_size
            FROM pg_index a
            JOIN pg_index b ON a.indrelid = b.indrelid
                AND a.indexrelid != b.indexrelid
                AND a.indkey::text = b.indkey::text
            WHERE a.indexrelid::regclass::text < b.indexrelid::regclass::text
            ORDER BY pg_relation_size(a.indexrelid) DESC
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                IndexDetail idx = IndexDetail.builder()
                    .indexName(rs.getString("index1") + " (duplicate of " + rs.getString("index2") + ")")
                    .indexSize(rs.getLong("index1_size"))
                    .isPrimary(false)
                    .build();
                indexes.add(idx);
            }
        }

        return indexes;
    }

    @Override
    public Map<String, Object> getPerformanceMetrics(Connection connection, String database) throws SQLException {
        Map<String, Object> metrics = new HashMap<>();

        // Database statistics
        String dbStatsQuery = """
            SELECT
                numbackends,
                xact_commit,
                xact_rollback,
                blks_read,
                blks_hit,
                tup_returned,
                tup_fetched,
                tup_inserted,
                tup_updated,
                tup_deleted,
                conflicts,
                deadlocks,
                temp_files,
                temp_bytes
            FROM pg_stat_database
            WHERE datname = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(dbStatsQuery)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    metrics.put("active_connections", rs.getInt("numbackends"));
                    metrics.put("transactions_committed", rs.getLong("xact_commit"));
                    metrics.put("transactions_rolled_back", rs.getLong("xact_rollback"));
                    metrics.put("blocks_read", rs.getLong("blks_read"));
                    metrics.put("blocks_hit", rs.getLong("blks_hit"));
                    metrics.put("tuples_returned", rs.getLong("tup_returned"));
                    metrics.put("tuples_fetched", rs.getLong("tup_fetched"));
                    metrics.put("tuples_inserted", rs.getLong("tup_inserted"));
                    metrics.put("tuples_updated", rs.getLong("tup_updated"));
                    metrics.put("tuples_deleted", rs.getLong("tup_deleted"));
                    metrics.put("conflicts", rs.getLong("conflicts"));
                    metrics.put("deadlocks", rs.getLong("deadlocks"));
                    metrics.put("temp_files", rs.getLong("temp_files"));
                    metrics.put("temp_bytes", rs.getLong("temp_bytes"));
                }
            }
        }

        // Cache hit ratio
        metrics.put("cache_hit_ratio", getCacheHitRatio(connection));

        return metrics;
    }

    @Override
    public Double getCacheHitRatio(Connection connection) throws SQLException {
        String query = """
            SELECT
                CASE
                    WHEN (blks_hit + blks_read) = 0 THEN 1.0
                    ELSE blks_hit::float / (blks_hit + blks_read)
                END AS cache_hit_ratio
            FROM pg_stat_database
            WHERE datname = current_database()
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                return rs.getDouble("cache_hit_ratio");
            }
        }

        return 1.0;
    }
}
