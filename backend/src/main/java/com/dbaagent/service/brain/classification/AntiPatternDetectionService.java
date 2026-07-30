package com.dbaagent.service.brain.classification;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for detecting table anti-patterns.
 *
 * Detects:
 * - GOD_TABLE: Tables with too many columns (>50) or too many FKs pointing to it (>20)
 * - SPARSE_TABLE: Tables with >30% NULL values across columns
 * - POLYMORPHIC_ASSOCIATION: Has *_type + *_id columns without proper FKs
 * - EAV_PATTERN: Entity-Attribute-Value pattern (entity_id, attribute_name, attribute_value)
 * - MISSING_TIMESTAMP: No created_at/updated_at on transactional tables
 * - OVER_INDEXED: Index count > column count × 0.5
 * - UNDER_INDEXED: High seq_scan rate on large tables with few/no indexes
 * - DUPLICATE_INDEXES: Multiple indexes with same leading columns
 * - UNUSED_COLUMNS: Columns with 100% NULL or never queried
 * - WIDE_TABLE: Tables with excessive column count that impact performance
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AntiPatternDetectionService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;

    // Thresholds
    private static final int GOD_TABLE_COLUMN_THRESHOLD = 50;
    private static final int GOD_TABLE_FK_THRESHOLD = 20;
    private static final double SPARSE_TABLE_NULL_THRESHOLD = 0.30;
    private static final double OVER_INDEXED_RATIO = 0.5;
    private static final long LARGE_TABLE_THRESHOLD = 100000;
    private static final double HIGH_SEQ_SCAN_RATIO = 0.8;
    private static final int WIDE_TABLE_THRESHOLD = 30;

    /**
     * Detect anti-patterns for all tables in a connection.
     */
    public Map<String, TableAntiPatterns> detectAntiPatterns(String connectionId) {
        Map<String, TableAntiPatterns> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get table metadata
            Map<String, TableMetadata> tableMetadata = getTableMetadata(conn, dbType);

            // Get index information
            Map<String, List<IndexInfo>> tableIndexes = getTableIndexes(conn, dbType);

            // Get column null ratios
            Map<String, Map<String, Double>> columnNullRatios = getColumnNullRatios(conn, dbType, tableMetadata.keySet());

            // Get key column analysis for FK information
            List<KeyColumnAnalysis> keyColumns = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId);
            Map<String, List<KeyColumnAnalysis>> tableKeyColumns = keyColumns.stream()
                .collect(Collectors.groupingBy(kc -> kc.getTableName().toLowerCase()));

            // Get table scan statistics (PostgreSQL specific)
            Map<String, TableScanStats> scanStats = new HashMap<>();
            if ("postgresql".equalsIgnoreCase(dbType)) {
                scanStats = getPostgresTableScanStats(conn);
            }

            // Analyze each table for anti-patterns
            for (Map.Entry<String, TableMetadata> entry : tableMetadata.entrySet()) {
                String tableName = entry.getKey();
                TableMetadata metadata = entry.getValue();
                List<IndexInfo> indexes = tableIndexes.getOrDefault(tableName.toLowerCase(), List.of());
                Map<String, Double> nullRatios = columnNullRatios.getOrDefault(tableName.toLowerCase(), Map.of());
                List<KeyColumnAnalysis> columns = tableKeyColumns.getOrDefault(tableName.toLowerCase(), List.of());
                TableScanStats stats = scanStats.get(tableName.toLowerCase());

                List<DetectedAntiPattern> antiPatterns = analyzeTable(
                    tableName, metadata, indexes, nullRatios, columns, stats, tableMetadata.size());

                String overallSeverity = calculateOverallSeverity(antiPatterns);

                results.put(tableName.toLowerCase(), TableAntiPatterns.builder()
                    .tableName(tableName)
                    .antiPatterns(antiPatterns)
                    .antiPatternCount(antiPatterns.size())
                    .overallSeverity(overallSeverity)
                    .build());
            }

        } catch (Exception e) {
            log.error("Error detecting anti-patterns for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Analyze a single table for anti-patterns.
     */
    private List<DetectedAntiPattern> analyzeTable(
            String tableName,
            TableMetadata metadata,
            List<IndexInfo> indexes,
            Map<String, Double> nullRatios,
            List<KeyColumnAnalysis> columns,
            TableScanStats scanStats,
            int totalTables) {

        List<DetectedAntiPattern> antiPatterns = new ArrayList<>();

        // 1. GOD_TABLE detection
        if (metadata.getColumnCount() > GOD_TABLE_COLUMN_THRESHOLD) {
            antiPatterns.add(DetectedAntiPattern.builder()
                .type("GOD_TABLE")
                .severity("HIGH")
                .details(Map.of(
                    "column_count", metadata.getColumnCount(),
                    "threshold", GOD_TABLE_COLUMN_THRESHOLD
                ))
                .recommendation("Consider splitting this table into multiple related tables. " +
                    "Identify logical groupings of columns and create separate entities.")
                .impact("Large tables increase memory usage, slow down queries, and make maintenance difficult.")
                .build());
        }

        // 2. WIDE_TABLE detection (less severe than GOD_TABLE)
        if (metadata.getColumnCount() > WIDE_TABLE_THRESHOLD && metadata.getColumnCount() <= GOD_TABLE_COLUMN_THRESHOLD) {
            antiPatterns.add(DetectedAntiPattern.builder()
                .type("WIDE_TABLE")
                .severity("MEDIUM")
                .details(Map.of(
                    "column_count", metadata.getColumnCount(),
                    "threshold", WIDE_TABLE_THRESHOLD
                ))
                .recommendation("Consider if all columns are necessary. " +
                    "Evaluate vertical partitioning for rarely-used columns.")
                .impact("Wide tables can impact cache efficiency and increase I/O for simple queries.")
                .build());
        }

        // 3. SPARSE_TABLE detection
        if (!nullRatios.isEmpty()) {
            double avgNullRatio = nullRatios.values().stream()
                .mapToDouble(Double::doubleValue)
                .average()
                .orElse(0.0);

            long highNullColumns = nullRatios.values().stream()
                .filter(ratio -> ratio > 0.9)
                .count();

            if (avgNullRatio > SPARSE_TABLE_NULL_THRESHOLD) {
                antiPatterns.add(DetectedAntiPattern.builder()
                    .type("SPARSE_TABLE")
                    .severity("MEDIUM")
                    .details(Map.of(
                        "avg_null_ratio", String.format("%.2f%%", avgNullRatio * 100),
                        "high_null_columns", highNullColumns,
                        "total_columns", nullRatios.size()
                    ))
                    .recommendation("Consider using nullable columns sparingly. " +
                        "Evaluate if sparse columns should be moved to a related table or use JSON/JSONB.")
                    .impact("Sparse tables waste storage and can indicate poor data model design.")
                    .build());
            }
        }

        // 4. POLYMORPHIC_ASSOCIATION detection
        List<String> columnNames = columns.stream()
            .map(KeyColumnAnalysis::getColumnName)
            .map(String::toLowerCase)
            .toList();

        boolean hasTypeColumn = columnNames.stream().anyMatch(c -> c.endsWith("_type") || c.equals("type"));
        boolean hasIdColumn = columnNames.stream().anyMatch(c ->
            (c.endsWith("_id") && !c.equals("id")) || c.endsWith("able_id"));

        if (hasTypeColumn && hasIdColumn) {
            // Check if it looks like a polymorphic pattern
            Optional<String> typeCol = columnNames.stream()
                .filter(c -> c.endsWith("_type") || c.equals("type"))
                .findFirst();
            Optional<String> idCol = columnNames.stream()
                .filter(c -> c.endsWith("able_id") ||
                    (typeCol.isPresent() && c.equals(typeCol.get().replace("_type", "_id"))))
                .findFirst();

            if (typeCol.isPresent() && idCol.isPresent()) {
                antiPatterns.add(DetectedAntiPattern.builder()
                    .type("POLYMORPHIC_ASSOCIATION")
                    .severity("MEDIUM")
                    .details(Map.of(
                        "type_column", typeCol.get(),
                        "id_column", idCol.get()
                    ))
                    .recommendation("Polymorphic associations break referential integrity. " +
                        "Consider using separate foreign keys or a bridge table pattern.")
                    .impact("Cannot enforce FK constraints, queries become complex, and data integrity risks increase.")
                    .build());
            }
        }

        // 5. EAV_PATTERN detection
        Set<String> eavIndicators = Set.of("entity_id", "attribute_name", "attribute_value",
            "attribute_key", "meta_key", "meta_value", "property_name", "property_value");
        long eavMatches = columnNames.stream()
            .filter(eavIndicators::contains)
            .count();

        if (eavMatches >= 2 && metadata.getColumnCount() <= 5) {
            antiPatterns.add(DetectedAntiPattern.builder()
                .type("EAV_PATTERN")
                .severity("HIGH")
                .details(Map.of(
                    "detected_columns", columnNames.stream()
                        .filter(eavIndicators::contains)
                        .toList()
                ))
                .recommendation("EAV pattern causes query complexity and performance issues. " +
                    "Consider using JSON/JSONB columns or proper normalized schema.")
                .impact("Queries become complex, indexing is difficult, and type safety is lost.")
                .build());
        }

        // 6. MISSING_TIMESTAMP detection
        boolean hasCreatedAt = columnNames.stream()
            .anyMatch(c -> c.contains("created") || c.contains("inserted") || c.equals("createdat"));
        boolean hasUpdatedAt = columnNames.stream()
            .anyMatch(c -> c.contains("updated") || c.contains("modified") || c.equals("updatedat"));

        // Only flag for tables that look transactional (have FKs or substantial rows)
        boolean isTransactional = metadata.getRowCount() > 1000 || columns.stream()
            .anyMatch(c -> c.getJoinCount() > 0);

        if (isTransactional && !hasCreatedAt && !hasUpdatedAt) {
            antiPatterns.add(DetectedAntiPattern.builder()
                .type("MISSING_TIMESTAMP")
                .severity("LOW")
                .details(Map.of(
                    "has_created_at", hasCreatedAt,
                    "has_updated_at", hasUpdatedAt,
                    "row_count", metadata.getRowCount()
                ))
                .recommendation("Add created_at and updated_at timestamp columns for audit trail and debugging.")
                .impact("Difficult to track data changes, debug issues, or implement soft deletes.")
                .build());
        }

        // 7. OVER_INDEXED detection
        int indexCount = indexes.size();
        if (indexCount > metadata.getColumnCount() * OVER_INDEXED_RATIO && indexCount > 3) {
            antiPatterns.add(DetectedAntiPattern.builder()
                .type("OVER_INDEXED")
                .severity("MEDIUM")
                .details(Map.of(
                    "index_count", indexCount,
                    "column_count", metadata.getColumnCount(),
                    "ratio", String.format("%.2f", (double) indexCount / metadata.getColumnCount())
                ))
                .recommendation("Review indexes for redundancy. Too many indexes slow down writes " +
                    "and increase storage. Use EXPLAIN ANALYZE to identify actually used indexes.")
                .impact("Write performance degradation, increased storage, longer backup times.")
                .build());
        }

        // 8. UNDER_INDEXED detection
        if (scanStats != null && metadata.getRowCount() > LARGE_TABLE_THRESHOLD) {
            double seqScanRatio = scanStats.getSeqScanRatio();
            if (seqScanRatio > HIGH_SEQ_SCAN_RATIO && indexCount <= 1) {
                antiPatterns.add(DetectedAntiPattern.builder()
                    .type("UNDER_INDEXED")
                    .severity("HIGH")
                    .details(Map.of(
                        "row_count", metadata.getRowCount(),
                        "seq_scan_ratio", String.format("%.2f%%", seqScanRatio * 100),
                        "seq_scans", scanStats.getSeqScans(),
                        "idx_scans", scanStats.getIdxScans(),
                        "index_count", indexCount
                    ))
                    .recommendation("Add indexes on frequently queried columns. " +
                        "Analyze slow query logs to identify missing indexes.")
                    .impact("Full table scans on large tables cause severe performance degradation.")
                    .build());
            }
        }

        // 9. DUPLICATE_INDEXES detection
        List<List<IndexInfo>> duplicateGroups = findDuplicateIndexes(indexes);
        if (!duplicateGroups.isEmpty()) {
            for (List<IndexInfo> group : duplicateGroups) {
                antiPatterns.add(DetectedAntiPattern.builder()
                    .type("DUPLICATE_INDEXES")
                    .severity("MEDIUM")
                    .details(Map.of(
                        "indexes", group.stream().map(IndexInfo::getIndexName).toList(),
                        "columns", group.get(0).getColumns()
                    ))
                    .recommendation("Remove duplicate indexes. Keep the most specific one or the one with the best selectivity.")
                    .impact("Wasted storage, slower writes, increased maintenance overhead.")
                    .build());
            }
        }

        // 10. UNUSED_COLUMNS detection
        List<String> unusedColumns = nullRatios.entrySet().stream()
            .filter(e -> e.getValue() >= 0.999) // 99.9%+ NULL
            .map(Map.Entry::getKey)
            .toList();

        if (!unusedColumns.isEmpty() && unusedColumns.size() <= 5) { // Only report if manageable number
            antiPatterns.add(DetectedAntiPattern.builder()
                .type("UNUSED_COLUMNS")
                .severity("LOW")
                .details(Map.of(
                    "columns", unusedColumns,
                    "count", unusedColumns.size()
                ))
                .recommendation("Consider removing columns that are always NULL. " +
                    "They may be deprecated or incorrectly implemented.")
                .impact("Unnecessary storage overhead and schema complexity.")
                .build());
        }

        return antiPatterns;
    }

    /**
     * Find duplicate indexes (indexes with same leading columns).
     */
    private List<List<IndexInfo>> findDuplicateIndexes(List<IndexInfo> indexes) {
        List<List<IndexInfo>> duplicates = new ArrayList<>();
        Set<String> processed = new HashSet<>();

        for (int i = 0; i < indexes.size(); i++) {
            if (processed.contains(indexes.get(i).getIndexName())) continue;

            List<IndexInfo> group = new ArrayList<>();
            group.add(indexes.get(i));

            String leadingCol = indexes.get(i).getColumns().isEmpty() ? "" :
                indexes.get(i).getColumns().get(0);

            for (int j = i + 1; j < indexes.size(); j++) {
                IndexInfo other = indexes.get(j);
                if (!other.getColumns().isEmpty() &&
                    other.getColumns().get(0).equals(leadingCol)) {
                    group.add(other);
                    processed.add(other.getIndexName());
                }
            }

            if (group.size() > 1) {
                duplicates.add(group);
                processed.add(indexes.get(i).getIndexName());
            }
        }

        return duplicates;
    }

    /**
     * Calculate overall severity from list of anti-patterns.
     */
    private String calculateOverallSeverity(List<DetectedAntiPattern> antiPatterns) {
        if (antiPatterns.isEmpty()) return "NONE";

        boolean hasCritical = antiPatterns.stream().anyMatch(p -> "CRITICAL".equals(p.getSeverity()));
        boolean hasHigh = antiPatterns.stream().anyMatch(p -> "HIGH".equals(p.getSeverity()));
        boolean hasMedium = antiPatterns.stream().anyMatch(p -> "MEDIUM".equals(p.getSeverity()));

        if (hasCritical) return "CRITICAL";
        if (hasHigh) return "HIGH";
        if (hasMedium) return "MEDIUM";
        return "LOW";
    }

    /**
     * Get table metadata (column count, row count).
     */
    private Map<String, TableMetadata> getTableMetadata(Connection conn, String dbType) {
        Map<String, TableMetadata> metadata = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT
                    c.relname as table_name,
                    (SELECT count(*) FROM pg_attribute a
                     WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) as column_count,
                    COALESCE(c.reltuples, 0)::bigint as row_count
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind = 'r'
                    AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                ORDER BY c.relname
                """;
        } else {
            sql = """
                SELECT
                    TABLE_NAME as table_name,
                    (SELECT COUNT(*) FROM information_schema.COLUMNS c2
                     WHERE c2.TABLE_SCHEMA = t.TABLE_SCHEMA AND c2.TABLE_NAME = t.TABLE_NAME) as column_count,
                    COALESCE(TABLE_ROWS, 0) as row_count
                FROM information_schema.TABLES t
                WHERE TABLE_SCHEMA = DATABASE()
                    AND TABLE_TYPE = 'BASE TABLE'
                ORDER BY TABLE_NAME
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name");
                metadata.put(tableName.toLowerCase(), TableMetadata.builder()
                    .tableName(tableName)
                    .columnCount(rs.getInt("column_count"))
                    .rowCount(rs.getLong("row_count"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting table metadata: {}", e.getMessage(), e);
        }

        return metadata;
    }

    /**
     * Get index information for all tables.
     */
    private Map<String, List<IndexInfo>> getTableIndexes(Connection conn, String dbType) {
        Map<String, List<IndexInfo>> indexes = new HashMap<>();

        String sql;
        if ("postgresql".equalsIgnoreCase(dbType)) {
            sql = """
                SELECT
                    t.relname as table_name,
                    i.relname as index_name,
                    array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) as columns,
                    ix.indisunique as is_unique,
                    ix.indisprimary as is_primary
                FROM pg_index ix
                JOIN pg_class t ON t.oid = ix.indrelid
                JOIN pg_class i ON i.oid = ix.indexrelid
                JOIN pg_namespace n ON n.oid = t.relnamespace
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
                GROUP BY t.relname, i.relname, ix.indisunique, ix.indisprimary
                ORDER BY t.relname, i.relname
                """;
        } else {
            sql = """
                SELECT
                    TABLE_NAME as table_name,
                    INDEX_NAME as index_name,
                    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) as columns,
                    NOT NON_UNIQUE as is_unique,
                    INDEX_NAME = 'PRIMARY' as is_primary
                FROM information_schema.STATISTICS
                WHERE TABLE_SCHEMA = DATABASE()
                GROUP BY TABLE_NAME, INDEX_NAME, NON_UNIQUE
                ORDER BY TABLE_NAME, INDEX_NAME
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                String indexName = rs.getString("index_name");

                List<String> columns;
                if ("postgresql".equalsIgnoreCase(dbType)) {
                    java.sql.Array arr = rs.getArray("columns");
                    if (arr != null) {
                        columns = Arrays.asList((String[]) arr.getArray());
                    } else {
                        columns = List.of();
                    }
                } else {
                    String colStr = rs.getString("columns");
                    columns = colStr != null ? Arrays.asList(colStr.split(",")) : List.of();
                }

                IndexInfo info = IndexInfo.builder()
                    .indexName(indexName)
                    .columns(columns)
                    .isUnique(rs.getBoolean("is_unique"))
                    .isPrimary(rs.getBoolean("is_primary"))
                    .build();

                indexes.computeIfAbsent(tableName, k -> new ArrayList<>()).add(info);
            }
        } catch (Exception e) {
            log.error("Error getting index information: {}", e.getMessage(), e);
        }

        return indexes;
    }

    /**
     * Get NULL ratio for columns (sample-based for performance).
     */
    private Map<String, Map<String, Double>> getColumnNullRatios(
            Connection conn, String dbType, Set<String> tableNames) {
        Map<String, Map<String, Double>> nullRatios = new HashMap<>();

        // For each table, sample and check NULL ratios
        for (String tableName : tableNames) {
            Map<String, Double> tableNullRatios = new HashMap<>();

            try {
                // Get column names first
                String colSql;
                if ("postgresql".equalsIgnoreCase(dbType)) {
                    colSql = """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_name = ? AND table_schema NOT IN ('pg_catalog', 'information_schema')
                        """;
                } else {
                    colSql = """
                        SELECT COLUMN_NAME as column_name
                        FROM information_schema.COLUMNS
                        WHERE TABLE_NAME = ? AND TABLE_SCHEMA = DATABASE()
                        """;
                }

                List<String> columns = new ArrayList<>();
                try (PreparedStatement stmt = conn.prepareStatement(colSql)) {
                    stmt.setString(1, tableName);
                    try (ResultSet rs = stmt.executeQuery()) {
                        while (rs.next()) {
                            columns.add(rs.getString("column_name"));
                        }
                    }
                }

                // Sample-based NULL ratio calculation (limit to 10000 rows for performance)
                if (!columns.isEmpty()) {
                    StringBuilder nullCheckSql = new StringBuilder("SELECT ");
                    for (int i = 0; i < columns.size(); i++) {
                        if (i > 0) nullCheckSql.append(", ");
                        String col = columns.get(i);
                        nullCheckSql.append(String.format(
                            "CAST(SUM(CASE WHEN \"%s\" IS NULL THEN 1 ELSE 0 END) AS FLOAT) / " +
                            "NULLIF(COUNT(*), 0) as \"%s_null_ratio\"",
                            col, col));
                    }
                    nullCheckSql.append(" FROM (SELECT * FROM \"").append(tableName).append("\" LIMIT 10000) sample");

                    try (PreparedStatement stmt = conn.prepareStatement(nullCheckSql.toString());
                         ResultSet rs = stmt.executeQuery()) {
                        if (rs.next()) {
                            for (String col : columns) {
                                double ratio = rs.getDouble(col + "_null_ratio");
                                if (!rs.wasNull()) {
                                    tableNullRatios.put(col.toLowerCase(), ratio);
                                }
                            }
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Error getting NULL ratios for table {}: {}", tableName, e.getMessage());
            }

            if (!tableNullRatios.isEmpty()) {
                nullRatios.put(tableName.toLowerCase(), tableNullRatios);
            }
        }

        return nullRatios;
    }

    /**
     * Get PostgreSQL table scan statistics.
     */
    private Map<String, TableScanStats> getPostgresTableScanStats(Connection conn) {
        Map<String, TableScanStats> stats = new HashMap<>();

        String sql = """
            SELECT
                relname as table_name,
                COALESCE(seq_scan, 0) as seq_scans,
                COALESCE(idx_scan, 0) as idx_scans
            FROM pg_stat_user_tables
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                long seqScans = rs.getLong("seq_scans");
                long idxScans = rs.getLong("idx_scans");
                long total = seqScans + idxScans;

                double seqScanRatio = total > 0 ? (double) seqScans / total : 0.0;

                stats.put(tableName, TableScanStats.builder()
                    .seqScans(seqScans)
                    .idxScans(idxScans)
                    .seqScanRatio(seqScanRatio)
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting PostgreSQL scan stats: {}", e.getMessage(), e);
        }

        return stats;
    }

    // ==================== Data Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableAntiPatterns {
        private String tableName;
        private List<DetectedAntiPattern> antiPatterns;
        private int antiPatternCount;
        private String overallSeverity;  // CRITICAL, HIGH, MEDIUM, LOW, NONE
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DetectedAntiPattern {
        private String type;       // GOD_TABLE, SPARSE_TABLE, etc.
        private String severity;   // CRITICAL, HIGH, MEDIUM, LOW
        private Map<String, Object> details;
        private String recommendation;
        private String impact;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TableMetadata {
        private String tableName;
        private int columnCount;
        private long rowCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class IndexInfo {
        private String indexName;
        private List<String> columns;
        private boolean isUnique;
        private boolean isPrimary;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class TableScanStats {
        private long seqScans;
        private long idxScans;
        private double seqScanRatio;
    }
}
