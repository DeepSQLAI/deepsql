package com.dbaagent.provider.mysql;

import com.dbaagent.model.SlowQuery;
import com.dbaagent.provider.api.SlowQueryProvider;
import com.dbaagent.util.QueryNormalizer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.BufferedReader;
import java.io.FileReader;
import java.sql.*;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * MySQL implementation of SlowQueryProvider.
 * Collects slow queries from Performance Schema and slow_log table.
 */
@Slf4j
@Component
public class MySQLSlowQueryProvider implements SlowQueryProvider {

    private static final String DATABASE_TYPE = "mysql";

    /**
     * MySQL compile-time default for performance_schema_max_sql_text_length —
     * the maximum number of bytes performance_schema digest text and
     * statement-history rows will hold per query. Anything longer is
     * silently truncated by the server before DeepSQL ever sees it.
     */
    private static final int DEFAULT_PS_MAX_SQL_TEXT_LENGTH = 1024;

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    /**
     * Read MySQL's `performance_schema_max_sql_text_length` setting.
     * Caller uses this to flag queries whose stored text was cut at the
     * server before we could see it. The variable is read-only at the
     * server level (set in my.cnf, applied on restart); we don't need to
     * worry about it changing mid-collection.
     */
    private int readPerformanceSchemaMaxSqlTextLength(Connection connection) {
        try (PreparedStatement stmt = connection.prepareStatement(
                "SELECT @@performance_schema_max_sql_text_length");
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                long bytes = rs.getLong(1);
                if (bytes > 0 && bytes <= Integer.MAX_VALUE) {
                    return (int) bytes;
                }
            }
        } catch (Exception e) {
            log.debug("Could not read performance_schema_max_sql_text_length; "
                + "assuming default {}B: {}", DEFAULT_PS_MAX_SQL_TEXT_LENGTH, e.getMessage());
        }
        return DEFAULT_PS_MAX_SQL_TEXT_LENGTH;
    }

    /**
     * Heuristic for "performance_schema cut this query at the configured
     * server-side limit." Same shape as the Postgres detector — we accept
     * a small fudge factor (3 bytes) to absorb UTF-8 boundary effects.
     */
    private static boolean looksTruncated(String queryText, int maxLength) {
        if (queryText == null || maxLength <= 0) return false;
        return queryText.length() >= maxLength - 3;
    }

    @Override
    public List<SlowQuery> collectSlowQueries(Connection connection, String database, double thresholdMs, int limit) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();

        // Try Performance Schema first (more detailed)
        if (isPerformanceSchemaEnabled(connection)) {
            queries.addAll(collectFromPerformanceSchema(connection, database, thresholdMs, limit));
        }

        // Try slow_log table as supplement
        if (isSlowLogTableEnabled(connection)) {
            List<SlowQuery> slowLogQueries = collectFromSlowLogTable(connection, database, thresholdMs, limit);
            // Deduplicate by query signature
            for (SlowQuery sq : slowLogQueries) {
                if (queries.stream().noneMatch(q ->
                    q.getNormalizedQuery() != null &&
                    q.getNormalizedQuery().equals(sq.getNormalizedQuery()))) {
                    queries.add(sq);
                }
            }
        }

        // Try slow query log file (if enabled and accessible)
        try {
            String logFile = getSlowQueryLogFile(connection);
            if (logFile != null && !logFile.isBlank() && !logFile.equalsIgnoreCase("TABLE")) {
                List<SlowQuery> fileQueries = parseSlowQueryLogFile(logFile, database, thresholdMs, limit);
                for (SlowQuery sq : fileQueries) {
                    if (queries.stream().noneMatch(q ->
                        q.getNormalizedQuery() != null &&
                        q.getNormalizedQuery().equals(sq.getNormalizedQuery()))) {
                        queries.add(sq);
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse slow query log file: {}", e.getMessage());
        }

        return queries;
    }

    private List<SlowQuery> collectFromPerformanceSchema(Connection connection, String database, double thresholdMs, int limit) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();
        final int maxSqlTextLength = readPerformanceSchemaMaxSqlTextLength(connection);

        String query = """
            SELECT
                DIGEST AS query_id,
                DIGEST_TEXT AS query_text,
                SCHEMA_NAME AS db_name,
                COUNT_STAR AS call_count,
                SUM_TIMER_WAIT / 1000000000 AS total_time_ms,
                AVG_TIMER_WAIT / 1000000000 AS avg_time_ms,
                MAX_TIMER_WAIT / 1000000000 AS max_time_ms,
                MIN_TIMER_WAIT / 1000000000 AS min_time_ms,
                SUM_ROWS_EXAMINED AS rows_examined,
                SUM_ROWS_SENT AS rows_sent,
                SUM_ROWS_EXAMINED / NULLIF(COUNT_STAR, 0) AS avg_rows_examined,
                SUM_ROWS_SENT / NULLIF(COUNT_STAR, 0) AS avg_rows_sent,
                FIRST_SEEN AS first_seen,
                LAST_SEEN AS last_seen
            FROM performance_schema.events_statements_summary_by_digest
            WHERE SCHEMA_NAME = ?
              AND AVG_TIMER_WAIT / 1000000000 >= ?
              AND DIGEST_TEXT IS NOT NULL
            ORDER BY total_time_ms DESC
            LIMIT ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setDouble(2, thresholdMs);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String digestText = rs.getString("query_text");
                    String sampleQuery = null;
                    if (digestText != null && digestText.contains("?")) {
                        String digest = rs.getString("query_id");
                        String resolved = fetchSampleQuery(connection, digest);
                        if (resolved != null && !resolved.isBlank()) {
                            sampleQuery = resolved;
                        }
                    }
                    String queryText = sampleQuery != null && !sampleQuery.isBlank() ? sampleQuery : digestText;

                    // Source-truncation: digestText is stored in
                    // performance_schema bounded by
                    // performance_schema_max_sql_text_length. The
                    // sampleQuery from events_statements_history has the
                    // same limit. If either looks cut, flag it — EXPLAIN
                    // against a truncated query will be wrong or fail.
                    boolean truncated = looksTruncated(digestText, maxSqlTextLength)
                        || looksTruncated(sampleQuery, maxSqlTextLength);

                    SlowQuery sq = SlowQuery.builder()
                        .queryId(rs.getString("query_id"))
                        .queryText(queryText)
                        .normalizedQuery(digestText)
                        .sampleQuery(sampleQuery)
                        .database(rs.getString("db_name"))
                        .source("Performance Schema")
                        .sourceTruncated(truncated)
                        .callCount(rs.getLong("call_count"))
                        .totalExecutionTimeMs(rs.getDouble("total_time_ms"))
                        .avgExecutionTimeMs(rs.getDouble("avg_time_ms"))
                        .maxExecutionTimeMs(rs.getDouble("max_time_ms"))
                        .minExecutionTimeMs(rs.getDouble("min_time_ms"))
                        .rowsExamined(rs.getLong("rows_examined"))
                        .rowsSent(rs.getLong("rows_sent"))
                        .avgRowsExamined(rs.getLong("avg_rows_examined"))
                        .avgRowsSent(rs.getLong("avg_rows_sent"))
                        .firstSeen(rs.getTimestamp("first_seen") != null ?
                            rs.getTimestamp("first_seen").toLocalDateTime() : null)
                        .lastSeen(rs.getTimestamp("last_seen") != null ?
                            rs.getTimestamp("last_seen").toLocalDateTime() : null)
                        .build();

                    sq.setSeverity(sq.calculateSeverity());
                    sq.setPerformanceImpact(sq.calculatePerformanceImpact());
                    queries.add(sq);
                }
            }
        }

        return queries;
    }

    private List<SlowQuery> collectFromSlowLogTable(Connection connection, String database, double thresholdMs, int limit) throws SQLException {
        List<SlowQuery> queries = new ArrayList<>();

        String query = """
            SELECT
                sql_text AS query_text,
                db AS db_name,
                query_time,
                rows_examined,
                rows_sent,
                start_time
            FROM mysql.slow_log
            WHERE db = ?
              AND TIME_TO_SEC(query_time) * 1000 >= ?
            ORDER BY start_time DESC
            LIMIT ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setDouble(2, thresholdMs);
            stmt.setInt(3, limit);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Time queryTime = rs.getTime("query_time");
                    double timeMs = queryTime != null ?
                        (queryTime.getHours() * 3600 + queryTime.getMinutes() * 60 + queryTime.getSeconds()) * 1000.0 : 0;

                    SlowQuery sq = SlowQuery.builder()
                        .queryText(rs.getString("query_text"))
                        .sampleQuery(rs.getString("query_text"))
                        .database(rs.getString("db_name"))
                        .source("slow_log table")
                        .avgExecutionTimeMs(timeMs)
                        .maxExecutionTimeMs(timeMs)
                        .callCount(1L)
                        .rowsExamined(rs.getLong("rows_examined"))
                        .rowsSent(rs.getLong("rows_sent"))
                        .lastSeen(rs.getTimestamp("start_time") != null ?
                            rs.getTimestamp("start_time").toLocalDateTime() : null)
                        .build();

                    sq.setSeverity(sq.calculateSeverity());
                    queries.add(sq);
                }
            }
        } catch (SQLException e) {
            // slow_log table may not exist or not be accessible
            log.debug("Could not query slow_log table: {}", e.getMessage());
        }

        return queries;
    }

    private String getSlowQueryLogFile(Connection connection) throws SQLException {
        String query = "SELECT @@slow_query_log_file, @@slow_query_log";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                String logFile = rs.getString(1);
                String logEnabled = rs.getString(2);
                if ("1".equals(logEnabled) || "ON".equalsIgnoreCase(logEnabled)) {
                    return logFile;
                }
            }
        }
        return null;
    }

    private List<SlowQuery> parseSlowQueryLogFile(
        String logFilePath,
        String database,
        double thresholdMs,
        int limit
    ) {
        Map<String, SlowQueryBuilder> queryMap = new HashMap<>();

        try {
            if (!Files.exists(Paths.get(logFilePath))) {
                log.debug("Slow query log file not accessible: {}", logFilePath);
                return new ArrayList<>();
            }

            try (BufferedReader reader = new BufferedReader(new FileReader(logFilePath))) {
                String line;
                StringBuilder currentQuery = new StringBuilder();
                Double currentQueryTime = null;
                Long currentRowsSent = null;
                Long currentRowsExamined = null;
                LocalDateTime currentTimestamp = null;

                Pattern timePattern = Pattern.compile("# Time: (.+)");
                Pattern queryTimePattern = Pattern.compile("# Query_time: ([\\d.]+)\\s+Lock_time: [\\d.]+\\s+Rows_sent: (\\d+)\\s+Rows_examined: (\\d+)");

                while ((line = reader.readLine()) != null) {
                    Matcher timeMatcher = timePattern.matcher(line);
                    if (timeMatcher.find()) {
                        currentTimestamp = parseTimestamp(timeMatcher.group(1));
                        continue;
                    }

                    Matcher queryTimeMatcher = queryTimePattern.matcher(line);
                    if (queryTimeMatcher.find()) {
                        currentQueryTime = Double.parseDouble(queryTimeMatcher.group(1)) * 1000;
                        currentRowsSent = Long.parseLong(queryTimeMatcher.group(2));
                        currentRowsExamined = Long.parseLong(queryTimeMatcher.group(3));
                        continue;
                    }

                    if (line.startsWith("#") || line.trim().isEmpty()) {
                        continue;
                    }

                    currentQuery.append(line).append(" ");

                    if (line.trim().endsWith(";")) {
                        String queryText = currentQuery.toString().trim();

                        if (currentQueryTime != null && currentQueryTime >= thresholdMs) {
                            String fingerprint = QueryNormalizer.generateFingerprint(queryText);
                            SlowQueryBuilder builder = queryMap.computeIfAbsent(fingerprint, k -> new SlowQueryBuilder());

                            builder.addExecution(queryText, currentQueryTime,
                                currentRowsSent != null ? currentRowsSent : 0,
                                currentRowsExamined != null ? currentRowsExamined : 0,
                                currentTimestamp);
                        }

                        currentQuery.setLength(0);
                        currentQueryTime = null;
                        currentRowsSent = null;
                        currentRowsExamined = null;
                    }
                }
            }
        } catch (Exception e) {
            log.debug("Error parsing slow query log file: {}", e.getMessage());
        }

        List<SlowQuery> results = new ArrayList<>();
        queryMap.forEach((fingerprint, builder) -> {
            SlowQuery query = builder.build(fingerprint, database, "slow_log file");
            if (query != null) {
                results.add(query);
            }
        });

        results.sort((a, b) -> {
            Double impactA = a.calculatePerformanceImpact();
            Double impactB = b.calculatePerformanceImpact();
            return Double.compare(impactB != null ? impactB : 0.0, impactA != null ? impactA : 0.0);
        });

        return results.size() > limit ? results.subList(0, limit) : results;
    }

    @Override
    public boolean isSlowQueryMonitoringAvailable(Connection connection) throws SQLException {
        return isPerformanceSchemaEnabled(connection) || isSlowLogTableEnabled(connection);
    }

    @Override
    public List<String> getAvailableSources(Connection connection) throws SQLException {
        List<String> sources = new ArrayList<>();

        if (isPerformanceSchemaEnabled(connection)) {
            sources.add("Performance Schema");
        }
        if (isSlowLogTableEnabled(connection)) {
            sources.add("slow_log table");
        }

        return sources;
    }

    private boolean isPerformanceSchemaEnabled(Connection connection) throws SQLException {
        String query = "SELECT @@performance_schema";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            return rs.next() && rs.getInt(1) == 1;
        }
    }

    private boolean isSlowLogTableEnabled(Connection connection) throws SQLException {
        String query = "SELECT @@log_output, @@slow_query_log";
        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            if (rs.next()) {
                String logOutput = rs.getString(1);
                int slowQueryLog = rs.getInt(2);
                return slowQueryLog == 1 && logOutput != null && logOutput.contains("TABLE");
            }
        }
        return false;
    }

    private LocalDateTime parseTimestamp(String timestamp) {
        try {
            DateTimeFormatter[] formatters = {
                DateTimeFormatter.ofPattern("yyMMdd HH:mm:ss"),
                DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"),
                DateTimeFormatter.ISO_DATE_TIME
            };

            for (DateTimeFormatter formatter : formatters) {
                try {
                    return LocalDateTime.parse(timestamp.trim(), formatter);
                } catch (DateTimeParseException ignored) {
                }
            }
        } catch (Exception e) {
            log.debug("Could not parse timestamp: {}", timestamp);
        }
        return LocalDateTime.now();
    }

    private static class SlowQueryBuilder {
        private String queryText;
        private final List<Double> executionTimes = new ArrayList<>();
        private long totalRowsSent = 0;
        private long totalRowsExamined = 0;
        private LocalDateTime firstSeen;
        private LocalDateTime lastSeen;

        public void addExecution(String query, double timeMs, long rowsSent, long rowsExamined, LocalDateTime timestamp) {
            if (queryText == null || queryText.length() < query.length()) {
                queryText = query;
            }

            executionTimes.add(timeMs);
            totalRowsSent += rowsSent;
            totalRowsExamined += rowsExamined;

            if (timestamp != null) {
                if (firstSeen == null || timestamp.isBefore(firstSeen)) {
                    firstSeen = timestamp;
                }
                if (lastSeen == null || timestamp.isAfter(lastSeen)) {
                    lastSeen = timestamp;
                }
            }
        }

        public SlowQuery build(String fingerprint, String database, String source) {
            if (executionTimes.isEmpty()) {
                return null;
            }

            double avgTime = executionTimes.stream().mapToDouble(Double::doubleValue).average().orElse(0.0);
            double maxTime = executionTimes.stream().mapToDouble(Double::doubleValue).max().orElse(0.0);
            double minTime = executionTimes.stream().mapToDouble(Double::doubleValue).min().orElse(0.0);
            double totalTime = executionTimes.stream().mapToDouble(Double::doubleValue).sum();

            double variance = executionTimes.stream()
                .mapToDouble(t -> Math.pow(t - avgTime, 2))
                .average()
                .orElse(0.0);
            double stdDev = Math.sqrt(variance);

            String normalized = QueryNormalizer.normalize(queryText);

            SlowQuery query = SlowQuery.builder()
                .queryId(fingerprint)
                .queryText(queryText)
                .normalizedQuery(normalized)
                .sampleQuery(queryText)
                .database(database)
                .source(source)
                .callCount((long) executionTimes.size())
                .totalExecutionTimeMs(totalTime)
                .avgExecutionTimeMs(avgTime)
                .maxExecutionTimeMs(maxTime)
                .minExecutionTimeMs(minTime)
                .stdDevExecutionTimeMs(stdDev)
                .rowsSent(totalRowsSent)
                .rowsExamined(totalRowsExamined)
                .firstSeen(firstSeen)
                .lastSeen(lastSeen)
                .affectedTables(Arrays.asList(QueryNormalizer.extractTableNames(queryText)))
                .build();

            query.setSeverity(query.calculateSeverity());
            query.setPerformanceImpact(query.calculatePerformanceImpact());

            return query;
        }
    }

    private String fetchSampleQuery(Connection connection, String digest) {
        if (digest == null || digest.isBlank()) {
            return null;
        }

        String sampleFromSummary = queryForDigest(connection,
            "SELECT QUERY_SAMPLE_TEXT FROM performance_schema.events_statements_summary_by_digest " +
                "WHERE DIGEST = ? AND QUERY_SAMPLE_TEXT IS NOT NULL LIMIT 1",
            digest);
        if (sampleFromSummary != null && !sampleFromSummary.isBlank()) {
            return sampleFromSummary;
        }

        String sampleFromHistoryLong = queryForDigest(connection,
            "SELECT SQL_TEXT FROM performance_schema.events_statements_history_long " +
                "WHERE DIGEST = ? AND SQL_TEXT IS NOT NULL ORDER BY TIMER_START DESC LIMIT 1",
            digest);
        if (sampleFromHistoryLong != null && !sampleFromHistoryLong.isBlank()) {
            return sampleFromHistoryLong;
        }

        return queryForDigest(connection,
            "SELECT SQL_TEXT FROM performance_schema.events_statements_history " +
                "WHERE DIGEST = ? AND SQL_TEXT IS NOT NULL ORDER BY TIMER_START DESC LIMIT 1",
            digest);
    }

    private String queryForDigest(Connection connection, String sql, String digest) {
        try (PreparedStatement stmt = connection.prepareStatement(sql)) {
            stmt.setString(1, digest);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        } catch (SQLException e) {
            // performance_schema history tables/columns might be unavailable
            log.debug("Could not query performance_schema sample SQL: {}", e.getMessage());
        }
        return null;
    }
}
