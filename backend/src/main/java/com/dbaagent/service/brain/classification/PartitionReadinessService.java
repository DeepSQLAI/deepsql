package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for evaluating table partitioning readiness.
 *
 * Partition Readiness Levels:
 * - PARTITION_CANDIDATE_TIME: Large table suitable for time-based partitioning
 * - PARTITION_CANDIDATE_RANGE: Large table suitable for range partitioning (numeric)
 * - PARTITION_CANDIDATE_LIST: Large table suitable for list partitioning (categorical)
 * - ALREADY_PARTITIONED: Native partitioning already detected
 * - NOT_PARTITION_CANDIDATE: Small table or no clear partition key
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PartitionReadinessService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Thresholds
    private static final long PARTITION_ROW_THRESHOLD = 1_000_000;  // 1M rows minimum
    private static final long PARTITION_SIZE_THRESHOLD_MB = 1024;    // 1GB minimum
    private static final long IDEAL_PARTITION_ROW_COUNT = 10_000_000; // 10M rows ideal for partitioning
    private static final double MIN_BENEFIT_THRESHOLD = 0.3;         // 30% minimum estimated benefit

    // Patterns for time-based partition keys
    private static final Pattern TIME_COLUMN_PATTERN = Pattern.compile(
        "(?i)(created_at|createdat|created_date|date|timestamp|event_time|event_date|" +
        "transaction_date|order_date|log_date|recorded_at|inserted_at|modified_at|updated_at)");

    // Patterns for range partition keys
    private static final Pattern RANGE_COLUMN_PATTERN = Pattern.compile(
        "(?i)(id|_id|sequence|seq|order_num|invoice_num|batch_id|version)$");

    // Patterns for list partition keys
    private static final Pattern LIST_COLUMN_PATTERN = Pattern.compile(
        "(?i)(status|state|type|category|region|country|tenant_id|org_id|company_id|location_id)$");

    /**
     * Evaluate partition readiness for all tables in a connection.
     */
    public Map<String, PartitionReadiness> evaluatePartitionReadiness(String connectionId) {
        Map<String, PartitionReadiness> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get table sizes and row counts
            Map<String, TableSizeInfo> tableSizes = getTableSizes(conn, dbType);

            // Get already partitioned tables
            Set<String> partitionedTables = getPartitionedTables(conn, dbType);

            // Get table columns for partition key analysis
            Map<String, List<ColumnInfo>> tableColumns = getTableColumnsWithStats(conn, dbType);

            // Evaluate each table
            for (Map.Entry<String, TableSizeInfo> entry : tableSizes.entrySet()) {
                String tableName = entry.getKey();
                TableSizeInfo sizeInfo = entry.getValue();
                List<ColumnInfo> columns = tableColumns.getOrDefault(tableName.toLowerCase(), List.of());

                PartitionReadiness readiness = evaluateTable(
                    tableName, sizeInfo, columns, partitionedTables.contains(tableName.toLowerCase()));
                results.put(tableName.toLowerCase(), readiness);
            }

        } catch (Exception e) {
            log.error("Error evaluating partition readiness for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Evaluate partition readiness for a single table.
     */
    private PartitionReadiness evaluateTable(String tableName, TableSizeInfo sizeInfo,
                                              List<ColumnInfo> columns, boolean alreadyPartitioned) {

        // Check if already partitioned
        if (alreadyPartitioned) {
            return PartitionReadiness.builder()
                .tableName(tableName)
                .readinessLevel("ALREADY_PARTITIONED")
                .rowCount(sizeInfo.getRowCount())
                .tableSizeMb(sizeInfo.getSizeMb())
                .estimatedBenefit(BigDecimal.ZERO)
                .partitionKeyCandidates(List.of())
                .reasoning("Table is already partitioned")
                .recommendations(List.of("Review existing partition strategy for optimization opportunities"))
                .build();
        }

        // Check if table is large enough to benefit from partitioning
        boolean isLargeEnough = sizeInfo.getRowCount() >= PARTITION_ROW_THRESHOLD ||
                                sizeInfo.getSizeMb() >= PARTITION_SIZE_THRESHOLD_MB;

        if (!isLargeEnough) {
            return PartitionReadiness.builder()
                .tableName(tableName)
                .readinessLevel("NOT_PARTITION_CANDIDATE")
                .rowCount(sizeInfo.getRowCount())
                .tableSizeMb(sizeInfo.getSizeMb())
                .estimatedBenefit(BigDecimal.ZERO)
                .partitionKeyCandidates(List.of())
                .reasoning(String.format("Table too small for partitioning benefit (rows=%,d, size=%.1f MB)",
                    sizeInfo.getRowCount(), sizeInfo.getSizeMb()))
                .recommendations(List.of())
                .build();
        }

        // Find potential partition key candidates
        List<PartitionKeyCandidate> candidates = new ArrayList<>();

        for (ColumnInfo col : columns) {
            String colName = col.getColumnName().toLowerCase();
            String dataType = col.getDataType().toLowerCase();

            // Check for time-based partition keys
            if (TIME_COLUMN_PATTERN.matcher(colName).find() &&
                (dataType.contains("timestamp") || dataType.contains("date") || dataType.contains("time"))) {
                double benefit = calculateTimeBenefit(sizeInfo, col);
                candidates.add(PartitionKeyCandidate.builder()
                    .column(col.getColumnName())
                    .partitionType("TIME")
                    .dataType(dataType)
                    .cardinality(col.getDistinctCount())
                    .benefitEstimate(benefit)
                    .reasoning("Time-based column suitable for range partitioning by date")
                    .build());
            }

            // Check for range partition keys (numeric with high cardinality)
            if (RANGE_COLUMN_PATTERN.matcher(colName).find() &&
                (dataType.contains("int") || dataType.contains("bigint") || dataType.contains("serial"))) {
                if (col.getDistinctCount() > 1000) {
                    double benefit = calculateRangeBenefit(sizeInfo, col);
                    candidates.add(PartitionKeyCandidate.builder()
                        .column(col.getColumnName())
                        .partitionType("RANGE")
                        .dataType(dataType)
                        .cardinality(col.getDistinctCount())
                        .benefitEstimate(benefit)
                        .reasoning("Numeric column with high cardinality suitable for range partitioning")
                        .build());
                }
            }

            // Check for list partition keys (categorical with moderate cardinality)
            if (LIST_COLUMN_PATTERN.matcher(colName).find()) {
                if (col.getDistinctCount() >= 2 && col.getDistinctCount() <= 100) {
                    double benefit = calculateListBenefit(sizeInfo, col);
                    candidates.add(PartitionKeyCandidate.builder()
                        .column(col.getColumnName())
                        .partitionType("LIST")
                        .dataType(dataType)
                        .cardinality(col.getDistinctCount())
                        .benefitEstimate(benefit)
                        .reasoning("Categorical column with " + col.getDistinctCount() + " distinct values suitable for list partitioning")
                        .build());
                }
            }
        }

        // Sort candidates by benefit estimate
        candidates.sort((a, b) -> Double.compare(b.getBenefitEstimate(), a.getBenefitEstimate()));

        // Determine readiness level based on best candidate
        String readinessLevel;
        double bestBenefit = 0;
        if (!candidates.isEmpty()) {
            PartitionKeyCandidate best = candidates.get(0);
            bestBenefit = best.getBenefitEstimate();
            if (bestBenefit >= MIN_BENEFIT_THRESHOLD) {
                readinessLevel = "PARTITION_CANDIDATE_" + best.getPartitionType();
            } else {
                readinessLevel = "NOT_PARTITION_CANDIDATE";
            }
        } else {
            readinessLevel = "NOT_PARTITION_CANDIDATE";
        }

        // Generate recommendations
        List<String> recommendations = generateRecommendations(readinessLevel, sizeInfo, candidates);

        // Generate reasoning
        String reasoning;
        if (candidates.isEmpty()) {
            reasoning = "No suitable partition key columns found";
        } else if (bestBenefit < MIN_BENEFIT_THRESHOLD) {
            reasoning = String.format("Partition candidates found but estimated benefit (%.0f%%) below threshold",
                bestBenefit * 100);
        } else {
            reasoning = String.format("Table is a good partitioning candidate with estimated %.0f%% query improvement",
                bestBenefit * 100);
        }

        return PartitionReadiness.builder()
            .tableName(tableName)
            .readinessLevel(readinessLevel)
            .rowCount(sizeInfo.getRowCount())
            .tableSizeMb(sizeInfo.getSizeMb())
            .estimatedBenefit(BigDecimal.valueOf(bestBenefit).setScale(2, RoundingMode.HALF_UP))
            .partitionKeyCandidates(candidates)
            .reasoning(reasoning)
            .recommendations(recommendations)
            .build();
    }

    private double calculateTimeBenefit(TableSizeInfo sizeInfo, ColumnInfo col) {
        // Time-based partitioning benefit depends on query patterns and data age distribution
        // Higher benefit for larger tables and columns that are frequently filtered
        double sizeFactor = Math.min(1.0, sizeInfo.getRowCount() / (double) IDEAL_PARTITION_ROW_COUNT);
        double indexedBonus = col.isIndexed() ? 0.1 : 0;

        // Time columns typically provide good partition pruning (60-90% benefit)
        return Math.min(0.9, 0.6 * sizeFactor + 0.2 + indexedBonus);
    }

    private double calculateRangeBenefit(TableSizeInfo sizeInfo, ColumnInfo col) {
        // Range partitioning benefit depends on how evenly data is distributed
        double sizeFactor = Math.min(1.0, sizeInfo.getRowCount() / (double) IDEAL_PARTITION_ROW_COUNT);

        // Range columns typically provide moderate benefit (40-70%)
        return Math.min(0.7, 0.4 * sizeFactor + 0.2);
    }

    private double calculateListBenefit(TableSizeInfo sizeInfo, ColumnInfo col) {
        // List partitioning benefit depends on cardinality and query patterns
        double sizeFactor = Math.min(1.0, sizeInfo.getRowCount() / (double) IDEAL_PARTITION_ROW_COUNT);

        // Optimal cardinality is 5-20 partitions
        double cardinalityFactor;
        if (col.getDistinctCount() >= 5 && col.getDistinctCount() <= 20) {
            cardinalityFactor = 1.0;
        } else if (col.getDistinctCount() < 5) {
            cardinalityFactor = 0.7;
        } else {
            cardinalityFactor = Math.max(0.5, 1.0 - (col.getDistinctCount() - 20) * 0.01);
        }

        // List columns typically provide moderate benefit (40-70%)
        return Math.min(0.7, 0.4 * sizeFactor * cardinalityFactor + 0.2);
    }

    private List<String> generateRecommendations(String readinessLevel, TableSizeInfo sizeInfo,
                                                  List<PartitionKeyCandidate> candidates) {
        List<String> recommendations = new ArrayList<>();

        if (readinessLevel.equals("NOT_PARTITION_CANDIDATE")) {
            return recommendations;
        }

        if (readinessLevel.contains("TIME")) {
            recommendations.add("Implement time-based range partitioning (e.g., monthly or quarterly partitions)");
            recommendations.add("Consider retention policies - older partitions can be archived or dropped");
        } else if (readinessLevel.contains("RANGE")) {
            recommendations.add("Implement range partitioning based on the numeric key");
            recommendations.add("Plan partition boundaries to ensure even data distribution");
        } else if (readinessLevel.contains("LIST")) {
            recommendations.add("Implement list partitioning by the categorical column");
            recommendations.add("Create one partition per distinct value or group related values");
        }

        if (sizeInfo.getRowCount() > 100_000_000) {
            recommendations.add("Consider sub-partitioning for very large table (composite partitioning)");
        }

        recommendations.add("Test partition pruning with EXPLAIN ANALYZE on common queries");
        recommendations.add("Plan partition maintenance (adding new partitions, archiving old ones)");

        return recommendations;
    }

    // ==================== Data Gathering Methods ====================

    private Map<String, TableSizeInfo> getTableSizes(Connection conn, String dbType) {
        Map<String, TableSizeInfo> sizes = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT
                    relname as table_name,
                    reltuples::bigint as row_count,
                    pg_total_relation_size(c.oid) / (1024 * 1024.0) as size_mb
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind = 'r'
                    AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                """;
        } else {
            sql = """
                SELECT
                    TABLE_NAME as table_name,
                    TABLE_ROWS as row_count,
                    (DATA_LENGTH + INDEX_LENGTH) / (1024 * 1024.0) as size_mb
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_TYPE = 'BASE TABLE'
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                sizes.put(tableName, TableSizeInfo.builder()
                    .rowCount(Math.max(0, rs.getLong("row_count")))
                    .sizeMb(rs.getDouble("size_mb"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting table sizes: {}", e.getMessage(), e);
        }

        return sizes;
    }

    private Set<String> getPartitionedTables(Connection conn, String dbType) {
        Set<String> partitioned = new HashSet<>();

        if (!"postgresql".equalsIgnoreCase(dbType)) {
            // MySQL partitioning detection would require different approach
            return partitioned;
        }

        String sql = """
            SELECT relname as table_name
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'p'  -- 'p' = partitioned table
                AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                partitioned.add(rs.getString("table_name").toLowerCase());
            }
        } catch (Exception e) {
            log.debug("Error checking partitioned tables (may not be supported): {}", e.getMessage());
        }

        return partitioned;
    }

    private Map<String, List<ColumnInfo>> getTableColumnsWithStats(Connection conn, String dbType) {
        Map<String, List<ColumnInfo>> tableColumns = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT
                    c.table_name,
                    c.column_name,
                    c.data_type,
                    COALESCE(s.n_distinct, 0) as distinct_count,
                    EXISTS (
                        SELECT 1 FROM pg_indexes pi
                        WHERE pi.tablename = c.table_name
                        AND pi.indexdef LIKE '%' || c.column_name || '%'
                    ) as is_indexed
                FROM information_schema.columns c
                LEFT JOIN pg_stats s ON s.tablename = c.table_name AND s.attname = c.column_name
                WHERE c.table_schema NOT IN ('pg_catalog', 'information_schema')
                ORDER BY c.table_name, c.ordinal_position
                """;
        } else {
            sql = """
                SELECT
                    c.TABLE_NAME as table_name,
                    c.COLUMN_NAME as column_name,
                    c.DATA_TYPE as data_type,
                    0 as distinct_count,
                    EXISTS (
                        SELECT 1 FROM information_schema.STATISTICS s
                        WHERE s.TABLE_SCHEMA = c.TABLE_SCHEMA
                        AND s.TABLE_NAME = c.TABLE_NAME
                        AND s.COLUMN_NAME = c.COLUMN_NAME
                    ) as is_indexed
                FROM information_schema.COLUMNS c
                WHERE c.TABLE_SCHEMA = DATABASE()
                ORDER BY c.TABLE_NAME, c.ORDINAL_POSITION
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                long distinctCount = rs.getLong("distinct_count");
                // PostgreSQL stores negative values for estimated distinct counts
                if (distinctCount < 0) {
                    distinctCount = Math.abs(distinctCount);
                }

                ColumnInfo col = ColumnInfo.builder()
                    .columnName(rs.getString("column_name"))
                    .dataType(rs.getString("data_type"))
                    .distinctCount(distinctCount)
                    .indexed(rs.getBoolean("is_indexed"))
                    .build();

                tableColumns.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
            }
        } catch (Exception e) {
            log.error("Error getting table columns: {}", e.getMessage(), e);
        }

        return tableColumns;
    }

    // ==================== Data Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionReadiness {
        private String tableName;
        private String readinessLevel;  // PARTITION_CANDIDATE_TIME, PARTITION_CANDIDATE_RANGE, PARTITION_CANDIDATE_LIST, ALREADY_PARTITIONED, NOT_PARTITION_CANDIDATE
        private long rowCount;
        private double tableSizeMb;
        private BigDecimal estimatedBenefit;
        private List<PartitionKeyCandidate> partitionKeyCandidates;
        private String reasoning;
        private List<String> recommendations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PartitionKeyCandidate {
        private String column;
        private String partitionType;  // TIME, RANGE, LIST
        private String dataType;
        private long cardinality;
        private double benefitEstimate;
        private String reasoning;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TableSizeInfo {
        private long rowCount;
        private double sizeMb;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class ColumnInfo {
        private String columnName;
        private String dataType;
        private long distinctCount;
        private boolean indexed;
    }
}
