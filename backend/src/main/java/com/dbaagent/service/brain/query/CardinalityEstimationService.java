package com.dbaagent.service.brain.query;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.brain.ColumnStatistics;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brain 2.0: Cardinality Estimation Service
 *
 * Provides independent cardinality estimation using statistical sketches
 * and learned statistics. Inspired by optd-gungnir's approach using
 * TDigest and HyperLogLog for precise cardinality estimation.
 *
 * This service enables better cardinality estimates than the database
 * optimizer, which often has stale or inaccurate statistics.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CardinalityEstimationService {

    private final ColumnStatisticsRepository statisticsRepository;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    // Configuration
    private static final int DEFAULT_SAMPLE_SIZE = 10000;
    private static final int MCV_LIMIT = 10;
    private static final int HISTOGRAM_BUCKETS = 100;
    private static final int STALE_THRESHOLD_DAYS = 7;

    /**
     * Collect statistics for all columns in a table.
     */
    @Transactional
    public List<ColumnStatistics> collectTableStatistics(String connectionId, String tableName) {
        log.info("Collecting column statistics for table: {} in connection: {}", tableName, connectionId);

        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, connection);

            // Get column information
            List<Map<String, Object>> columns = getColumns(jdbc, dbType, tableName);

            List<ColumnStatistics> statistics = new ArrayList<>();
            for (Map<String, Object> column : columns) {
                String columnName = (String) column.get("column_name");
                String dataType = (String) column.get("data_type");

                ColumnStatistics stats = collectColumnStatistics(
                    jdbc, dbType, connectionId, tableName, columnName, dataType);
                if (stats != null) {
                    statistics.add(stats);
                }
            }

            log.info("Collected statistics for {} columns in table {}", statistics.size(), tableName);
            return statistics;

        } catch (Exception e) {
            log.error("Error collecting statistics for table {}: {}", tableName, e.getMessage(), e);
            throw new RuntimeException("Failed to collect table statistics: " + e.getMessage(), e);
        }
    }

    /**
     * Collect statistics for a specific column.
     */
    @Transactional
    public ColumnStatistics collectColumnStatistics(
            String connectionId, String tableName, String columnName) {

        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());
            JdbcTemplate jdbc = connectionService.getJdbcTemplate(connectionId, connection);

            // Get column data type
            String dataType = getColumnDataType(jdbc, dbType, tableName, columnName);

            return collectColumnStatistics(jdbc, dbType, connectionId, tableName, columnName, dataType);

        } catch (Exception e) {
            log.error("Error collecting statistics for column {}.{}: {}",
                tableName, columnName, e.getMessage(), e);
            throw new RuntimeException("Failed to collect column statistics: " + e.getMessage(), e);
        }
    }

    /**
     * Internal method to collect column statistics.
     */
    private ColumnStatistics collectColumnStatistics(
            JdbcTemplate jdbc,
            String dbType,
            String connectionId,
            String tableName,
            String columnName,
            String dataType) {

        try {
            // Resolve both names against information_schema before any of the
            // collect* helpers interpolate them into SQL. Every interpolated
            // identifier below is therefore a value the catalog returned, not
            // caller input (java/sql-injection).
            String safeTable = requireKnownIdentifier(jdbc, dbType, tableName, null);
            String safeColumn = requireKnownIdentifier(jdbc, dbType, tableName, columnName);

            // Find or create statistics record
            ColumnStatistics stats = statisticsRepository
                .findByConnectionIdAndTableNameAndColumnName(connectionId, tableName, columnName)
                .orElse(ColumnStatistics.builder()
                    .connectionId(connectionId)
                    .tableName(tableName)
                    .columnName(columnName)
                    .build());

            stats.setDataType(dataType);
            stats.setSampleSize(DEFAULT_SAMPLE_SIZE);
            stats.setCollectionMethod(ColumnStatistics.CollectionMethod.SAMPLE);

            // Collect basic statistics
            collectBasicStats(jdbc, dbType, safeTable, safeColumn, stats);

            // Collect MCV (Most Common Values)
            collectMCVStats(jdbc, dbType, safeTable, safeColumn, stats);

            // Collect histogram for numeric columns
            if (isNumericType(dataType)) {
                collectHistogramStats(jdbc, dbType, safeTable, safeColumn, stats);
            }

            // Collect string length stats for text columns
            if (isTextType(dataType)) {
                collectStringStats(jdbc, dbType, safeTable, safeColumn, stats);
            }

            stats.setCollectedAt(LocalDateTime.now());
            return statisticsRepository.save(stats);

        } catch (Exception e) {
            log.warn("Could not collect statistics for {}.{}: {}", tableName, columnName, e.getMessage());
            return null;
        }
    }

    /**
     * Collect basic statistics: row count, distinct count, null count.
     */
    private void collectBasicStats(
            JdbcTemplate jdbc, String dbType, String tableName,
            String columnName, ColumnStatistics stats) {

        String quotedTable = quoteIdentifier(dbType, tableName);
        String quotedColumn = quoteIdentifier(dbType, columnName);

        // Row count
        String countQuery = String.format("SELECT COUNT(*) FROM %s", quotedTable);
        Long rowCount = jdbc.queryForObject(countQuery, Long.class);
        stats.setRowCount(rowCount != null ? rowCount : 0L);

        // Distinct count and null count
        String distinctQuery = String.format(
            "SELECT COUNT(DISTINCT %s), COUNT(*) - COUNT(%s) FROM %s",
            quotedColumn, quotedColumn, quotedTable);

        List<Map<String, Object>> result = jdbc.queryForList(distinctQuery);
        if (!result.isEmpty()) {
            Map<String, Object> row = result.get(0);
            Object[] values = row.values().toArray();
            if (values.length >= 2) {
                stats.setDistinctCount(toLong(values[0]));
                stats.setNullCount(toLong(values[1]));
            }
        }

        // Calculate null fraction
        if (stats.getRowCount() > 0 && stats.getNullCount() != null) {
            stats.setNullFraction((double) stats.getNullCount() / stats.getRowCount());
        }
    }

    /**
     * Collect Most Common Values (MCV) statistics.
     */
    private void collectMCVStats(
            JdbcTemplate jdbc, String dbType, String tableName,
            String columnName, ColumnStatistics stats) {

        String quotedTable = quoteIdentifier(dbType, tableName);
        String quotedColumn = quoteIdentifier(dbType, columnName);

        String mcvQuery = String.format(
            "SELECT %s as val, COUNT(*) as cnt FROM %s WHERE %s IS NOT NULL " +
            "GROUP BY %s ORDER BY cnt DESC LIMIT %d",
            quotedColumn, quotedTable, quotedColumn, quotedColumn, MCV_LIMIT);

        try {
            List<Map<String, Object>> results = jdbc.queryForList(mcvQuery);

            if (!results.isEmpty() && stats.getRowCount() > 0) {
                List<String> mcvValues = new ArrayList<>();
                List<Double> mcvFrequencies = new ArrayList<>();

                for (Map<String, Object> row : results) {
                    Object val = row.get("val");
                    Object cnt = row.get("cnt");
                    if (val != null && cnt != null) {
                        mcvValues.add(String.valueOf(val));
                        mcvFrequencies.add(toLong(cnt).doubleValue() / stats.getRowCount());
                    }
                }

                stats.setMcvValues(mcvValues);
                stats.setMcvFrequencies(mcvFrequencies);
            }
        } catch (Exception e) {
            log.debug("Could not collect MCV for {}.{}: {}", tableName, columnName, e.getMessage());
        }
    }

    /**
     * Collect histogram statistics for numeric columns.
     */
    private void collectHistogramStats(
            JdbcTemplate jdbc, String dbType, String tableName,
            String columnName, ColumnStatistics stats) {

        String quotedTable = quoteIdentifier(dbType, tableName);
        String quotedColumn = quoteIdentifier(dbType, columnName);

        // Get min, max, avg, stddev
        String statsQuery = String.format(
            "SELECT MIN(%s), MAX(%s), AVG(%s), STDDEV(%s) FROM %s WHERE %s IS NOT NULL",
            quotedColumn, quotedColumn, quotedColumn, quotedColumn, quotedTable, quotedColumn);

        try {
            List<Map<String, Object>> result = jdbc.queryForList(statsQuery);
            if (!result.isEmpty()) {
                Map<String, Object> row = result.get(0);
                Object[] values = row.values().toArray();
                if (values.length >= 4) {
                    stats.setMinValue(values[0] != null ? String.valueOf(values[0]) : null);
                    stats.setMaxValue(values[1] != null ? String.valueOf(values[1]) : null);
                    stats.setAvgValue(toDouble(values[2]));
                    stats.setStdDeviation(toDouble(values[3]));
                }
            }

            // Build histogram using percentiles
            if (stats.getMinValue() != null && stats.getMaxValue() != null) {
                buildHistogram(jdbc, dbType, tableName, columnName, stats);
            }

        } catch (Exception e) {
            log.debug("Could not collect histogram for {}.{}: {}", tableName, columnName, e.getMessage());
        }
    }

    /**
     * Build histogram using equi-width buckets.
     */
    private void buildHistogram(
            JdbcTemplate jdbc, String dbType, String tableName,
            String columnName, ColumnStatistics stats) {

        try {
            double min = Double.parseDouble(stats.getMinValue());
            double max = Double.parseDouble(stats.getMaxValue());
            double range = max - min;

            if (range <= 0) return;

            double bucketWidth = range / HISTOGRAM_BUCKETS;
            List<String> bounds = new ArrayList<>();
            List<Long> counts = new ArrayList<>();

            String quotedTable = quoteIdentifier(dbType, tableName);
            String quotedColumn = quoteIdentifier(dbType, columnName);

            for (int i = 0; i <= HISTOGRAM_BUCKETS; i++) {
                bounds.add(String.valueOf(min + i * bucketWidth));
            }

            // Count rows in each bucket
            for (int i = 0; i < HISTOGRAM_BUCKETS; i++) {
                double low = min + i * bucketWidth;
                double high = min + (i + 1) * bucketWidth;

                String countQuery = String.format(
                    "SELECT COUNT(*) FROM %s WHERE %s >= %f AND %s < %f",
                    quotedTable, quotedColumn, low, quotedColumn, high);

                Long count = jdbc.queryForObject(countQuery, Long.class);
                counts.add(count != null ? count : 0L);
            }

            stats.setHistogramBounds(bounds);
            stats.setHistogramCounts(counts);

        } catch (Exception e) {
            log.debug("Could not build histogram for {}.{}: {}", tableName, columnName, e.getMessage());
        }
    }

    /**
     * Collect string statistics for text columns.
     */
    private void collectStringStats(
            JdbcTemplate jdbc, String dbType, String tableName,
            String columnName, ColumnStatistics stats) {

        String quotedTable = quoteIdentifier(dbType, tableName);
        String quotedColumn = quoteIdentifier(dbType, columnName);

        String lengthQuery;
        if ("postgres".equals(dbType)) {
            lengthQuery = String.format(
                "SELECT AVG(LENGTH(%s)) FROM %s WHERE %s IS NOT NULL",
                quotedColumn, quotedTable, quotedColumn);
        } else {
            lengthQuery = String.format(
                "SELECT AVG(CHAR_LENGTH(%s)) FROM %s WHERE %s IS NOT NULL",
                quotedColumn, quotedTable, quotedColumn);
        }

        try {
            Double avgLength = jdbc.queryForObject(lengthQuery, Double.class);
            stats.setAvgLength(avgLength);
        } catch (Exception e) {
            log.debug("Could not collect string stats for {}.{}: {}", tableName, columnName, e.getMessage());
        }
    }

    /**
     * Estimate cardinality for an equality predicate.
     */
    public long estimateEquality(String connectionId, String tableName, String columnName, String value) {
        Optional<ColumnStatistics> statsOpt = statisticsRepository
            .findByConnectionIdAndTableNameAndColumnName(connectionId, tableName, columnName);

        if (statsOpt.isEmpty()) {
            log.debug("No statistics found for {}.{}, returning default estimate", tableName, columnName);
            return 1;
        }

        return statsOpt.get().estimateEquality(value);
    }

    /**
     * Estimate cardinality for a range predicate.
     */
    public long estimateRange(String connectionId, String tableName, String columnName,
                              String lowBound, String highBound) {
        Optional<ColumnStatistics> statsOpt = statisticsRepository
            .findByConnectionIdAndTableNameAndColumnName(connectionId, tableName, columnName);

        if (statsOpt.isEmpty()) {
            log.debug("No statistics found for {}.{}, returning default estimate", tableName, columnName);
            return 1;
        }

        return statsOpt.get().estimateRange(lowBound, highBound);
    }

    /**
     * Estimate join cardinality between two columns.
     */
    public long estimateJoinCardinality(
            String connectionId,
            String leftTable, String leftColumn,
            String rightTable, String rightColumn) {

        Optional<ColumnStatistics> leftStatsOpt = statisticsRepository
            .findByConnectionIdAndTableNameAndColumnName(connectionId, leftTable, leftColumn);
        Optional<ColumnStatistics> rightStatsOpt = statisticsRepository
            .findByConnectionIdAndTableNameAndColumnName(connectionId, rightTable, rightColumn);

        if (leftStatsOpt.isEmpty() || rightStatsOpt.isEmpty()) {
            return 1;
        }

        ColumnStatistics leftStats = leftStatsOpt.get();
        ColumnStatistics rightStats = rightStatsOpt.get();

        // Use foreign key assumption: result size is max(left, right) / min(distinct_left, distinct_right)
        long leftRows = leftStats.getRowCount() != null ? leftStats.getRowCount() : 0;
        long rightRows = rightStats.getRowCount() != null ? rightStats.getRowCount() : 0;
        long leftDistinct = leftStats.getDistinctCount() != null ? leftStats.getDistinctCount() : 1;
        long rightDistinct = rightStats.getDistinctCount() != null ? rightStats.getDistinctCount() : 1;

        // Simple join estimation: |R ⋈ S| ≈ (|R| × |S|) / max(V(R,a), V(S,b))
        long maxDistinct = Math.max(leftDistinct, rightDistinct);
        if (maxDistinct == 0) maxDistinct = 1;

        return Math.max(1, (leftRows * rightRows) / maxDistinct);
    }

    /**
     * Get statistics for a connection.
     */
    public List<ColumnStatistics> getStatistics(String connectionId) {
        return statisticsRepository.findByConnectionId(connectionId);
    }

    /**
     * Get statistics for a table.
     */
    public List<ColumnStatistics> getTableStatistics(String connectionId, String tableName) {
        return statisticsRepository.findByConnectionIdAndTableName(connectionId, tableName);
    }

    /**
     * Find columns that need statistics refresh.
     */
    public List<ColumnStatistics> findStaleStatistics(String connectionId) {
        LocalDateTime threshold = LocalDateTime.now().minusDays(STALE_THRESHOLD_DAYS);
        return statisticsRepository.findStaleStatistics(connectionId, threshold);
    }

    /**
     * Refresh stale statistics for a connection.
     */
    @Transactional
    public int refreshStaleStatistics(String connectionId) {
        List<ColumnStatistics> staleStats = findStaleStatistics(connectionId);
        log.info("Found {} stale statistics to refresh for connection {}", staleStats.size(), connectionId);

        int refreshed = 0;
        for (ColumnStatistics stats : staleStats) {
            try {
                collectColumnStatistics(connectionId, stats.getTableName(), stats.getColumnName());
                refreshed++;
            } catch (Exception e) {
                log.warn("Failed to refresh statistics for {}.{}: {}",
                    stats.getTableName(), stats.getColumnName(), e.getMessage());
            }
        }

        return refreshed;
    }

    /**
     * Get high cardinality columns (potential index candidates).
     */
    public List<ColumnStatistics> getHighCardinalityColumns(String connectionId, long minDistinct) {
        return statisticsRepository.findHighCardinalityColumns(connectionId, minDistinct);
    }

    /**
     * Get columns with skewed distributions.
     */
    public List<ColumnStatistics> getSkewedColumns(String connectionId) {
        List<ColumnStatistics> columnsWithMCV = statisticsRepository.findColumnsWithMCV(connectionId);

        // Filter to columns where top MCV has > 10% frequency
        return columnsWithMCV.stream()
            .filter(stats -> {
                List<Double> freqs = stats.getMcvFrequencies();
                return freqs != null && !freqs.isEmpty() && freqs.get(0) > 0.1;
            })
            .collect(Collectors.toList());
    }

    // Helper methods

    private List<Map<String, Object>> getColumns(JdbcTemplate jdbc, String dbType, String tableName) {
        if ("postgres".equals(dbType)) {
            return jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_name = ? ORDER BY ordinal_position", tableName.toLowerCase());
        } else {
            return jdbc.queryForList(
                "SELECT column_name, data_type FROM information_schema.columns " +
                "WHERE table_name = ? ORDER BY ordinal_position", tableName);
        }
    }

    private String getColumnDataType(JdbcTemplate jdbc, String dbType, String tableName, String columnName) {
        if ("postgres".equals(dbType)) {
            return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = ?",
                String.class, tableName.toLowerCase(), columnName.toLowerCase());
        } else {
            return jdbc.queryForObject(
                "SELECT data_type FROM information_schema.columns " +
                "WHERE table_name = ? AND column_name = ?",
                String.class, tableName, columnName);
        }
    }

    // Delegates to the dialect's SamplingProvider: it doubles an embedded quote
    // character, which this method previously did not do at all — a table named
    // `x" ; DROP TABLE users; --` escaped the quoting entirely (java/sql-injection).
    // The if/else on dbType it replaced also violated the provider-registry rule.
    private String quoteIdentifier(String dbType, String identifier) {
        return providerRegistry.getDialect(dbType).sampling().quoteIdentifier(identifier);
    }

    /**
     * Resolves an identifier to the spelling recorded in information_schema.
     * Quoting alone makes injection inert; this makes the value non-arbitrary,
     * so a name that is not a real table/column never reaches interpolated SQL.
     */
    private String requireKnownIdentifier(JdbcTemplate jdbc, String dbType,
                                          String tableName, String columnName) {
        boolean isColumn = columnName != null;
        String sql = isColumn
            ? "SELECT column_name FROM information_schema.columns "
              + "WHERE table_name = ? AND column_name = ? LIMIT 1"
            : "SELECT table_name FROM information_schema.tables WHERE table_name = ? LIMIT 1";

        boolean lower = "postgres".equals(dbType);
        List<String> found = isColumn
            ? jdbc.queryForList(sql, String.class,
                  lower ? tableName.toLowerCase() : tableName,
                  lower ? columnName.toLowerCase() : columnName)
            : jdbc.queryForList(sql, String.class, lower ? tableName.toLowerCase() : tableName);

        if (found.isEmpty()) {
            throw new IllegalArgumentException(
                "Unknown " + (isColumn ? "column " + tableName + "." + columnName : "table " + tableName));
        }
        return found.get(0);
    }

    private boolean isNumericType(String dataType) {
        if (dataType == null) return false;
        String lower = dataType.toLowerCase();
        return lower.contains("int") || lower.contains("numeric") || lower.contains("decimal") ||
               lower.contains("float") || lower.contains("double") || lower.contains("real") ||
               lower.contains("serial") || lower.contains("money");
    }

    private boolean isTextType(String dataType) {
        if (dataType == null) return false;
        String lower = dataType.toLowerCase();
        return lower.contains("char") || lower.contains("text") || lower.contains("varchar") ||
               lower.contains("string");
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private Double toDouble(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).doubleValue();
        try {
            return Double.parseDouble(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
