package com.dbaagent.provider.postgres;

import com.dbaagent.model.SlowQuery;
import com.dbaagent.provider.api.SlowQueryProvider;
import com.dbaagent.service.QueryFingerprintService;
import com.dbaagent.util.QueryNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;

/**
 * PostgreSQL implementation of SlowQueryProvider.
 * Collects slow queries from pg_stat_statements extension.
 */
@Slf4j
@Component
public class PostgresSlowQueryProvider implements SlowQueryProvider {

    private static final String DATABASE_TYPE = "postgres";

    /**
     * Postgres compile-time default for pg_stat_statements query buffer size.
     * Many production installs leave this at the default — slow queries
     * longer than this come back already cut off at the server, with no
     * indication other than the length. We use it as the fallback when
     * the `SHOW track_activity_query_size` lookup fails.
     */
    private static final int DEFAULT_TRACK_ACTIVITY_QUERY_SIZE = 1024;

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    /**
     * Read the server's `track_activity_query_size` setting. This is the
     * maximum number of bytes pg_stat_statements (and pg_stat_activity)
     * will store per query — anything longer gets silently truncated.
     *
     * Cached per-collection in the caller because postgresql.conf isn't
     * going to flip mid-collection. Returns the documented default
     * (1024) on any failure path so callers always have a sane value to
     * compare against.
     */
    private int readTrackActivityQuerySize(Connection connection) {
        try (PreparedStatement stmt = connection.prepareStatement("SHOW track_activity_query_size");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                String raw = rs.getString(1);
                if (raw != null && !raw.isBlank()) {
                    // Postgres returns this as a string with an optional
                    // unit suffix ("1024", "2kB", "1MB"). Parse defensively.
                    String trimmed = raw.trim().toUpperCase();
                    long multiplier = 1;
                    if (trimmed.endsWith("KB")) {
                        multiplier = 1024L;
                        trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
                    } else if (trimmed.endsWith("MB")) {
                        multiplier = 1024L * 1024L;
                        trimmed = trimmed.substring(0, trimmed.length() - 2).trim();
                    } else if (trimmed.endsWith("B")) {
                        trimmed = trimmed.substring(0, trimmed.length() - 1).trim();
                    }
                    long bytes = Long.parseLong(trimmed) * multiplier;
                    if (bytes > 0 && bytes <= Integer.MAX_VALUE) {
                        return (int) bytes;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not read track_activity_query_size; assuming default {}B: {}",
                DEFAULT_TRACK_ACTIVITY_QUERY_SIZE, e.getMessage());
        }
        return DEFAULT_TRACK_ACTIVITY_QUERY_SIZE;
    }

    /**
     * Best-effort check: did pg_stat_statements truncate this query at the
     * server-side size limit? PostgreSQL doesn't expose a "was-truncated"
     * flag — we infer it from `query.length() >= track_activity_query_size`.
     *
     * False positives are possible (a query that happens to be exactly
     * 1024 bytes), but those should be rare in practice; the worst case is
     * a false-positive truncation warning on a perfectly-valid SQL string,
     * which is much better than the current silent half-query behavior.
     */
    private static boolean looksTruncated(String queryText, int trackSize) {
        if (queryText == null || trackSize <= 0) return false;
        // pg_stat_statements stores up to `track_activity_query_size - 1`
        // bytes plus a null terminator. UTF-8 multi-byte chars at the end
        // may also reduce the visible length by a few, so use >= trackSize-3
        // as the boundary check.
        return queryText.length() >= trackSize - 3;
    }

    @Override
    public List<SlowQuery> collectSlowQueries(Connection connection, String database, double thresholdMs, int limit) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();

        if (!isPgStatStatementsAvailable(connection)) {
            log.debug("pg_stat_statements extension not available");
            return queries;
        }

        // Read once per collection — cheap, and the value can't change
        // mid-collection without a server restart.
        final int trackSize = readTrackActivityQuerySize(connection);

        String query = """
            SELECT
                queryid::text AS query_id,
                query AS query_text,
                calls AS call_count,
                total_exec_time AS total_time_ms,
                mean_exec_time AS avg_time_ms,
                max_exec_time AS max_time_ms,
                min_exec_time AS min_time_ms,
                stddev_exec_time AS stddev_time_ms,
                rows AS total_rows,
                rows / NULLIF(calls, 0) AS avg_rows,
                shared_blks_hit AS cache_hits,
                shared_blks_read AS cache_misses,
                CASE
                    WHEN shared_blks_hit + shared_blks_read > 0
                    THEN shared_blks_hit::float / (shared_blks_hit + shared_blks_read)
                    ELSE 1.0
                END AS cache_hit_ratio
            FROM pg_stat_statements pss
            JOIN pg_database pd ON pss.dbid = pd.oid
            WHERE pd.datname = ?
              AND mean_exec_time >= ?
              AND query NOT LIKE '%pg_stat_statements%'
            ORDER BY total_exec_time DESC
            LIMIT ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setDouble(2, thresholdMs);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String queryText = rs.getString("query_text");
                    // pg_stat_statements yields normalized text with $N
                    // placeholders. Key the row on the canonical fingerprint so
                    // it lines up with the analytics tables and CloudWatch
                    // slow-log samples for the same query (the raw pg_stat
                    // queryid bigint never matched those).
                    String normalized = QueryNormalizer.normalize(queryText);
                    SlowQuery sq = SlowQuery.builder()
                        .queryId(QueryFingerprintService.computeCanonicalFingerprint(normalized))
                        .queryText(queryText)
                        .normalizedQuery(normalized)
                        .database(database)
                        .source("pg_stat_statements")
                        .sourceTruncated(looksTruncated(queryText, trackSize))
                        .callCount(rs.getLong("call_count"))
                        .totalExecutionTimeMs(rs.getDouble("total_time_ms"))
                        .avgExecutionTimeMs(rs.getDouble("avg_time_ms"))
                        .maxExecutionTimeMs(rs.getDouble("max_time_ms"))
                        .minExecutionTimeMs(rs.getDouble("min_time_ms"))
                        .stdDevExecutionTimeMs(rs.getDouble("stddev_time_ms"))
                        .rowsSent(rs.getLong("total_rows"))
                        .avgRowsSent(rs.getLong("avg_rows"))
                        .cacheHitRatio(rs.getDouble("cache_hit_ratio"))
                        .build();

                    sq.setSeverity(sq.calculateSeverity());
                    sq.setPerformanceImpact(sq.calculatePerformanceImpact());
                    queries.add(sq);
                }
            }
        } catch (SQLException e) {
            // Handle case where column names differ between PostgreSQL versions
            log.debug("Error with pg_stat_statements query, trying legacy format: {}", e.getMessage());
            queries = collectSlowQueriesLegacy(connection, database, thresholdMs, limit, trackSize);
        }

        return queries;
    }

    private List<SlowQuery> collectSlowQueriesLegacy(Connection connection, String database, double thresholdMs, int limit, int trackSize) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();

        // PostgreSQL versions before 13 use total_time instead of total_exec_time
        String query = """
            SELECT
                queryid::text AS query_id,
                query AS query_text,
                calls AS call_count,
                total_time AS total_time_ms,
                mean_time AS avg_time_ms,
                max_time AS max_time_ms,
                min_time AS min_time_ms,
                stddev_time AS stddev_time_ms,
                rows AS total_rows,
                shared_blks_hit AS cache_hits,
                shared_blks_read AS cache_misses
            FROM pg_stat_statements pss
            JOIN pg_database pd ON pss.dbid = pd.oid
            WHERE pd.datname = ?
              AND mean_time >= ?
              AND query NOT LIKE '%pg_stat_statements%'
            ORDER BY total_time DESC
            LIMIT ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setDouble(2, thresholdMs);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    long cacheHits = rs.getLong("cache_hits");
                    long cacheMisses = rs.getLong("cache_misses");
                    double cacheHitRatio = (cacheHits + cacheMisses) > 0 ?
                        (double) cacheHits / (cacheHits + cacheMisses) : 1.0;

                    String queryText = rs.getString("query_text");
                    // pg_stat_statements yields normalized text with $N
                    // placeholders. Key the row on the canonical fingerprint so
                    // it lines up with the analytics tables and CloudWatch
                    // slow-log samples for the same query (the raw pg_stat
                    // queryid bigint never matched those).
                    String normalized = QueryNormalizer.normalize(queryText);
                    SlowQuery sq = SlowQuery.builder()
                        .queryId(QueryFingerprintService.computeCanonicalFingerprint(normalized))
                        .queryText(queryText)
                        .normalizedQuery(normalized)
                        .database(database)
                        .source("pg_stat_statements")
                        .sourceTruncated(looksTruncated(queryText, trackSize))
                        .callCount(rs.getLong("call_count"))
                        .totalExecutionTimeMs(rs.getDouble("total_time_ms"))
                        .avgExecutionTimeMs(rs.getDouble("avg_time_ms"))
                        .maxExecutionTimeMs(rs.getDouble("max_time_ms"))
                        .minExecutionTimeMs(rs.getDouble("min_time_ms"))
                        .stdDevExecutionTimeMs(rs.getDouble("stddev_time_ms"))
                        .rowsSent(rs.getLong("total_rows"))
                        .cacheHitRatio(cacheHitRatio)
                        .build();

                    sq.setSeverity(sq.calculateSeverity());
                    sq.setPerformanceImpact(sq.calculatePerformanceImpact());
                    queries.add(sq);
                }
            }
        }

        return queries;
    }

    @Override
    public boolean isSlowQueryMonitoringAvailable(Connection connection) throws SQLException {
        return isPgStatStatementsAvailable(connection);
    }

    @Override
    public List<String> getAvailableSources(Connection connection) throws SQLException {
        List<String> sources = new ArrayList<>();

        if (isPgStatStatementsAvailable(connection)) {
            sources.add("pg_stat_statements");
        }

        return sources;
    }

    private boolean isPgStatStatementsAvailable(Connection connection) throws SQLException {
        String query = """
            SELECT EXISTS (
                SELECT 1 FROM pg_extension WHERE extname = 'pg_stat_statements'
            )
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            return rs.next() && rs.getBoolean(1);
        }
    }
}
