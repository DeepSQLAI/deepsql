package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.IndexRecommendationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.*;

/**
 * Enhanced Index Advisor Service
 * Provides cost-benefit analysis, impact estimation, and comprehensive index health reporting.
 * Works alongside IndexRecommendationService to provide actionable insights.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IndexAdvisorService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final PerformanceMonitoringService performanceMonitoringService;
    private final IndexRecommendationRepository recommendationRepository;
    private final DatabaseProviderRegistry providerRegistry;

    /**
     * Get comprehensive index health report for a connection.
     *
     * Intentionally NOT @Transactional. The catch below degrades gracefully
     * (logs + records the cause under "error") so batch callers — the Slack
     * digest and the agent PerformanceExecutor — still get a partial report
     * for healthy sub-checks. Wrapping the method in a read-only transaction
     * would defeat that: a swallowed persistence failure (e.g. schema drift,
     * "column ... does not exist") marks the transaction rollback-only, and
     * the commit then throws an opaque UnexpectedRollbackException that masks
     * the real cause. Each repository read manages its own transaction.
     */
    public Map<String, Object> getIndexHealthReport(String connectionId) {
        log.info("Generating index health report for connection {}", connectionId);

        Map<String, Object> report = new LinkedHashMap<>();

        try {
            // Get unused indexes
            List<Map<String, Object>> unusedIndexes = performanceMonitoringService.getUnusedIndexes(connectionId);
            report.put("unusedIndexes", unusedIndexes);
            report.put("unusedIndexCount", unusedIndexes.size());

            // Calculate wasted space from unused indexes
            long wastedBytes = unusedIndexes.stream()
                    .mapToLong(idx -> {
                        Object size = idx.get("indexSizeBytes");
                        return size != null ? ((Number) size).longValue() : 0L;
                    })
                    .sum();
            report.put("unusedIndexWastedBytes", wastedBytes);
            report.put("unusedIndexWastedFormatted", formatBytes(wastedBytes));

            // Get duplicate/redundant indexes
            List<Map<String, Object>> duplicateIndexes = performanceMonitoringService.getDuplicateIndexes(connectionId);
            report.put("duplicateIndexes", duplicateIndexes);
            report.put("duplicateIndexCount", duplicateIndexes.size());

            // Get pending recommendations
            List<IndexRecommendationEntity> pendingRecommendations = recommendationRepository
                    .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
                            connectionId, IndexRecommendationEntity.Status.PENDING);
            report.put("pendingRecommendations", pendingRecommendations);
            report.put("pendingRecommendationCount", pendingRecommendations.size());

            // Get overall index statistics
            Map<String, Object> stats = getOverallIndexStats(connectionId);
            report.put("overallStats", stats);

            // Calculate health score (0-100)
            int healthScore = calculateHealthScore(unusedIndexes.size(), duplicateIndexes.size(),
                    pendingRecommendations.size(), stats);
            report.put("healthScore", healthScore);
            report.put("healthGrade", getHealthGrade(healthScore));

            // Generate summary
            report.put("summary", generateHealthSummary(healthScore, unusedIndexes.size(),
                    duplicateIndexes.size(), pendingRecommendations.size()));

        } catch (Exception e) {
            log.error("Error generating index health report: {}", e.getMessage(), e);
            report.put("error", e.getMessage());
        }

        return report;
    }

    /**
     * Estimate the impact of creating a new index
     */
    public Map<String, Object> estimateIndexCreationImpact(String connectionId, String tableName,
                                                           String[] columns, boolean unique) {
        log.info("Estimating index creation impact for {}.{}", tableName, String.join(",", columns));

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("tableName", tableName);
        impact.put("columns", columns);
        impact.put("unique", unique);

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                // Get table statistics
                Map<String, Object> tableStats = getTableStatsForImpact(connection, tableName, dbType);
                impact.put("tableStats", tableStats);

                long rowCount = ((Number) tableStats.getOrDefault("rowCount", 0L)).longValue();
                long tableSize = ((Number) tableStats.getOrDefault("tableSize", 0L)).longValue();

                // Estimate index size
                long estimatedIndexSize = estimateIndexSize(rowCount, columns.length, dbType);
                impact.put("estimatedIndexSizeBytes", estimatedIndexSize);
                impact.put("estimatedIndexSizeFormatted", formatBytes(estimatedIndexSize));

                // Estimate as percentage of table size
                if (tableSize > 0) {
                    double sizePercentage = (estimatedIndexSize * 100.0) / tableSize;
                    impact.put("sizePercentageOfTable", Math.round(sizePercentage * 100) / 100.0);
                }

                // Estimate creation time (rough estimate based on row count)
                String estimatedCreationTime = estimateCreationTime(rowCount, dbType);
                impact.put("estimatedCreationTime", estimatedCreationTime);

                // Check for existing similar indexes
                List<Map<String, Object>> similarIndexes = findSimilarIndexes(connection, tableName, columns, dbType);
                impact.put("similarExistingIndexes", similarIndexes);
                impact.put("hasSimilarIndex", !similarIndexes.isEmpty());

                // Estimate maintenance overhead
                Map<String, Object> maintenanceOverhead = estimateMaintenanceOverhead(rowCount, columns.length);
                impact.put("maintenanceOverhead", maintenanceOverhead);

                // Generate recommendation
                impact.put("recommendation", generateCreationRecommendation(impact));

                // Benefits analysis
                Map<String, Object> benefits = estimateBenefits(connection, tableName, columns, dbType);
                impact.put("estimatedBenefits", benefits);

            }
        } catch (Exception e) {
            log.error("Error estimating index creation impact: {}", e.getMessage(), e);
            impact.put("error", e.getMessage());
        }

        return impact;
    }

    /**
     * Estimate the impact of dropping an index
     */
    public Map<String, Object> estimateIndexDropImpact(String connectionId, String tableName, String indexName) {
        log.info("Estimating index drop impact for {}.{}", tableName, indexName);

        Map<String, Object> impact = new LinkedHashMap<>();
        impact.put("tableName", tableName);
        impact.put("indexName", indexName);

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                // Get index details
                Map<String, Object> indexDetails = getIndexDetails(connection, tableName, indexName, dbType);
                impact.put("indexDetails", indexDetails);

                // Get index usage statistics
                Map<String, Object> usageStats = getIndexUsageForDrop(connection, tableName, indexName, dbType);
                impact.put("usageStats", usageStats);

                long indexScans = usageStats.containsKey("indexScans")
                        ? ((Number) usageStats.get("indexScans")).longValue() : 0;

                // Determine if index is safe to drop
                boolean safeToDropByUsage = indexScans == 0;
                impact.put("safeToDropByUsage", safeToDropByUsage);

                // Check if index is unique constraint
                boolean isUnique = indexDetails.containsKey("isUnique")
                        && Boolean.TRUE.equals(indexDetails.get("isUnique"));
                boolean isPrimary = indexDetails.containsKey("isPrimary")
                        && Boolean.TRUE.equals(indexDetails.get("isPrimary"));

                impact.put("isUnique", isUnique);
                impact.put("isPrimary", isPrimary);

                if (isPrimary) {
                    impact.put("safeToDropByUsage", false);
                    impact.put("warning", "PRIMARY KEY index cannot be dropped directly");
                }

                if (isUnique && !isPrimary) {
                    impact.put("warning", "Dropping UNIQUE index will remove uniqueness constraint");
                }

                // Calculate space to be reclaimed
                long indexSize = indexDetails.containsKey("indexSizeBytes")
                        ? ((Number) indexDetails.get("indexSizeBytes")).longValue() : 0;
                impact.put("spaceToReclaim", indexSize);
                impact.put("spaceToReclaimFormatted", formatBytes(indexSize));

                // Check for dependent queries (if possible)
                impact.put("potentialQueryImpact", indexScans > 0
                        ? "Queries may need to use table scans or other indexes"
                        : "Low risk - index not being used");

                // Generate recommendation
                String recommendation = generateDropRecommendation(indexScans, isUnique, isPrimary, indexSize);
                impact.put("recommendation", recommendation);

            }
        } catch (Exception e) {
            log.error("Error estimating index drop impact: {}", e.getMessage(), e);
            impact.put("error", e.getMessage());
        }

        return impact;
    }

    /**
     * Get overall index statistics for a connection
     */
    private Map<String, Object> getOverallIndexStats(String connectionId) {
        Map<String, Object> stats = new LinkedHashMap<>();

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                if ("postgres".equals(dbType)) {
                    stats = getPostgresIndexStats(connection);
                } else if ("mysql".equals(dbType)) {
                    stats = getMySQLIndexStats(connection);
                }
            }
        } catch (Exception e) {
            log.error("Error getting overall index stats: {}", e.getMessage());
        }

        return stats;
    }

    private Map<String, Object> getPostgresIndexStats(Connection connection) throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();

        String sql = """
            SELECT
                COUNT(*) as total_indexes,
                SUM(pg_relation_size(indexrelid)) as total_index_size,
                COUNT(*) FILTER (WHERE idx_scan = 0) as unused_indexes,
                AVG(idx_scan) as avg_scans_per_index
            FROM pg_stat_user_indexes
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                stats.put("totalIndexes", rs.getInt("total_indexes"));
                stats.put("totalIndexSizeBytes", rs.getLong("total_index_size"));
                stats.put("totalIndexSizeFormatted", formatBytes(rs.getLong("total_index_size")));
                stats.put("unusedIndexCount", rs.getInt("unused_indexes"));
                stats.put("avgScansPerIndex", Math.round(rs.getDouble("avg_scans_per_index")));
            }
        }

        return stats;
    }

    private Map<String, Object> getMySQLIndexStats(Connection connection) throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();

        String sql = """
            SELECT
                COUNT(DISTINCT INDEX_NAME) as total_indexes,
                SUM(stat_value * @@innodb_page_size) as total_index_size
            FROM information_schema.STATISTICS s
            LEFT JOIN mysql.innodb_index_stats i ON i.table_name = s.TABLE_NAME
                AND i.index_name = s.INDEX_NAME AND i.stat_name = 'size'
            WHERE s.TABLE_SCHEMA = DATABASE()
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(sql)) {
            if (rs.next()) {
                stats.put("totalIndexes", rs.getInt("total_indexes"));
                long totalSize = rs.getLong("total_index_size");
                stats.put("totalIndexSizeBytes", totalSize);
                stats.put("totalIndexSizeFormatted", formatBytes(totalSize));
            }
        }

        return stats;
    }

    private Map<String, Object> getTableStatsForImpact(Connection connection, String tableName, String dbType) throws Exception {
        Map<String, Object> stats = new LinkedHashMap<>();

        if ("postgres".equals(dbType)) {
            String sql = """
                SELECT
                    reltuples::bigint as row_count,
                    pg_total_relation_size(oid) as table_size
                FROM pg_class
                WHERE relname = ?
                """;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, tableName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        stats.put("rowCount", rs.getLong("row_count"));
                        stats.put("tableSize", rs.getLong("table_size"));
                    }
                }
            }
        } else {
            String sql = """
                SELECT TABLE_ROWS, DATA_LENGTH + INDEX_LENGTH as total_size
                FROM information_schema.TABLES
                WHERE TABLE_NAME = ? AND TABLE_SCHEMA = DATABASE()
                """;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, tableName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        stats.put("rowCount", rs.getLong("TABLE_ROWS"));
                        stats.put("tableSize", rs.getLong("total_size"));
                    }
                }
            }
        }

        return stats;
    }

    private long estimateIndexSize(long rowCount, int columnCount, String dbType) {
        // Rough estimation based on typical B-tree index overhead
        // Each index entry is approximately: pointer (8 bytes) + key data
        // Key data varies but average ~50 bytes per column
        long bytesPerEntry = 8 + (columnCount * 50L);

        // B-tree has overhead (internal nodes, ~10-15% extra)
        long rawSize = rowCount * bytesPerEntry;
        return (long) (rawSize * 1.15);
    }

    private String estimateCreationTime(long rowCount, String dbType) {
        // Very rough estimates based on typical hardware
        if (rowCount < 10000) return "< 1 second";
        if (rowCount < 100000) return "1-10 seconds";
        if (rowCount < 1000000) return "10 seconds - 1 minute";
        if (rowCount < 10000000) return "1-5 minutes";
        if (rowCount < 100000000) return "5-30 minutes";
        return "> 30 minutes (consider creating during maintenance window)";
    }

    private List<Map<String, Object>> findSimilarIndexes(Connection connection, String tableName,
                                                          String[] columns, String dbType) throws Exception {
        List<Map<String, Object>> similar = new ArrayList<>();
        Set<String> targetColumns = new HashSet<>(Arrays.asList(columns));

        String sql;
        if ("postgres".equals(dbType)) {
            sql = """
                SELECT
                    i.relname as index_name,
                    array_agg(a.attname ORDER BY x.n) as columns
                FROM pg_index idx
                JOIN pg_class i ON i.oid = idx.indexrelid
                JOIN pg_class t ON t.oid = idx.indrelid
                CROSS JOIN LATERAL unnest(idx.indkey) WITH ORDINALITY AS x(attnum, n)
                JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = x.attnum
                WHERE t.relname = ?
                GROUP BY i.relname, idx.indexrelid
                """;
        } else {
            sql = """
                SELECT INDEX_NAME, GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) as columns
                FROM information_schema.STATISTICS
                WHERE TABLE_NAME = ? AND TABLE_SCHEMA = DATABASE()
                GROUP BY INDEX_NAME
                """;
        }

        try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
            pstmt.setString(1, tableName);
            try (ResultSet rs = pstmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString(1);
                    String columnsStr = rs.getString(2);
                    Set<String> indexColumns = new HashSet<>(Arrays.asList(columnsStr.split(",")));

                    // Check for overlap
                    Set<String> overlap = new HashSet<>(indexColumns);
                    overlap.retainAll(targetColumns);

                    if (!overlap.isEmpty()) {
                        Map<String, Object> info = new LinkedHashMap<>();
                        info.put("indexName", indexName);
                        info.put("columns", columnsStr);
                        info.put("overlapColumns", overlap);
                        info.put("isExactMatch", indexColumns.equals(targetColumns));
                        info.put("isPrefix", indexColumns.containsAll(targetColumns));
                        similar.add(info);
                    }
                }
            }
        }

        return similar;
    }

    private Map<String, Object> estimateMaintenanceOverhead(long rowCount, int columnCount) {
        Map<String, Object> overhead = new LinkedHashMap<>();

        // INSERT overhead: index must be updated
        overhead.put("insertImpact", rowCount > 1000000 ? "Moderate" : "Low");

        // UPDATE overhead: depends on whether indexed columns change
        overhead.put("updateImpact", "Low (only if indexed columns are updated)");

        // DELETE overhead: index entry must be removed
        overhead.put("deleteImpact", "Low");

        // Disk space
        overhead.put("diskSpaceImpact", columnCount > 3 ? "Moderate" : "Low");

        // Memory (buffer pool)
        overhead.put("memoryImpact", rowCount > 10000000 ? "Moderate" : "Low");

        return overhead;
    }

    private Map<String, Object> estimateBenefits(Connection connection, String tableName,
                                                  String[] columns, String dbType) throws Exception {
        Map<String, Object> benefits = new LinkedHashMap<>();

        benefits.put("querySpeedup", "10x-1000x for queries filtering on these columns");
        benefits.put("joinOptimization", columns.length == 1
                ? "Can speed up JOINs on " + columns[0]
                : "Can optimize multi-column conditions");
        benefits.put("sortingBenefit", "Can eliminate sorting for ORDER BY on indexed columns");
        benefits.put("coveringQuery", "May enable index-only scans for queries using only these columns");

        return benefits;
    }

    private String generateCreationRecommendation(Map<String, Object> impact) {
        StringBuilder rec = new StringBuilder();

        boolean hasSimilar = Boolean.TRUE.equals(impact.get("hasSimilarIndex"));
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> similarIndexes = (List<Map<String, Object>>) impact.get("similarExistingIndexes");

        if (hasSimilar && similarIndexes != null) {
            for (Map<String, Object> similar : similarIndexes) {
                if (Boolean.TRUE.equals(similar.get("isExactMatch"))) {
                    return "NOT RECOMMENDED: An identical index already exists: " + similar.get("indexName");
                }
                if (Boolean.TRUE.equals(similar.get("isPrefix"))) {
                    rec.append("CAUTION: Existing index '").append(similar.get("indexName"))
                            .append("' covers these columns. New index may be redundant. ");
                }
            }
        }

        long estimatedSize = ((Number) impact.getOrDefault("estimatedIndexSizeBytes", 0L)).longValue();
        if (estimatedSize > 1073741824) { // > 1GB
            rec.append("WARNING: Estimated index size > 1GB. Consider impact on storage and backup times. ");
        }

        if (rec.isEmpty()) {
            rec.append("RECOMMENDED: Index creation should improve query performance with acceptable overhead.");
        }

        return rec.toString();
    }

    private Map<String, Object> getIndexDetails(Connection connection, String tableName,
                                                 String indexName, String dbType) throws Exception {
        Map<String, Object> details = new LinkedHashMap<>();

        if ("postgres".equals(dbType)) {
            String sql = """
                SELECT
                    i.relname as index_name,
                    pg_relation_size(i.oid) as index_size,
                    idx.indisunique as is_unique,
                    idx.indisprimary as is_primary,
                    pg_get_indexdef(idx.indexrelid) as definition
                FROM pg_index idx
                JOIN pg_class i ON i.oid = idx.indexrelid
                JOIN pg_class t ON t.oid = idx.indrelid
                WHERE t.relname = ? AND i.relname = ?
                """;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, tableName);
                pstmt.setString(2, indexName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        details.put("indexName", rs.getString("index_name"));
                        details.put("indexSizeBytes", rs.getLong("index_size"));
                        details.put("isUnique", rs.getBoolean("is_unique"));
                        details.put("isPrimary", rs.getBoolean("is_primary"));
                        details.put("definition", rs.getString("definition"));
                    }
                }
            }
        } else {
            String sql = """
                SELECT
                    INDEX_NAME,
                    NON_UNIQUE = 0 as is_unique,
                    INDEX_NAME = 'PRIMARY' as is_primary,
                    GROUP_CONCAT(COLUMN_NAME ORDER BY SEQ_IN_INDEX) as columns
                FROM information_schema.STATISTICS
                WHERE TABLE_NAME = ? AND INDEX_NAME = ? AND TABLE_SCHEMA = DATABASE()
                GROUP BY INDEX_NAME, NON_UNIQUE
                """;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, tableName);
                pstmt.setString(2, indexName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        details.put("indexName", rs.getString("INDEX_NAME"));
                        details.put("isUnique", rs.getBoolean("is_unique"));
                        details.put("isPrimary", rs.getBoolean("is_primary"));
                        details.put("columns", rs.getString("columns"));
                    }
                }
            }
        }

        return details;
    }

    private Map<String, Object> getIndexUsageForDrop(Connection connection, String tableName,
                                                      String indexName, String dbType) throws Exception {
        Map<String, Object> usage = new LinkedHashMap<>();

        if ("postgres".equals(dbType)) {
            String sql = """
                SELECT idx_scan, idx_tup_read, idx_tup_fetch
                FROM pg_stat_user_indexes
                WHERE relname = ? AND indexrelname = ?
                """;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, tableName);
                pstmt.setString(2, indexName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        usage.put("indexScans", rs.getLong("idx_scan"));
                        usage.put("tuplesRead", rs.getLong("idx_tup_read"));
                        usage.put("tuplesFetched", rs.getLong("idx_tup_fetch"));
                    }
                }
            }
        } else {
            // MySQL - check sys.schema_index_statistics
            String sql = """
                SELECT rows_selected, rows_inserted, rows_updated, rows_deleted
                FROM sys.schema_index_statistics
                WHERE table_name = ? AND index_name = ? AND table_schema = DATABASE()
                """;
            try (PreparedStatement pstmt = connection.prepareStatement(sql)) {
                pstmt.setString(1, tableName);
                pstmt.setString(2, indexName);
                try (ResultSet rs = pstmt.executeQuery()) {
                    if (rs.next()) {
                        usage.put("indexScans", rs.getLong("rows_selected"));
                        usage.put("rowsInserted", rs.getLong("rows_inserted"));
                        usage.put("rowsUpdated", rs.getLong("rows_updated"));
                        usage.put("rowsDeleted", rs.getLong("rows_deleted"));
                    }
                }
            } catch (Exception e) {
                // sys.schema_index_statistics may not be available
                usage.put("indexScans", -1L);
                usage.put("note", "Usage statistics not available");
            }
        }

        return usage;
    }

    private String generateDropRecommendation(long indexScans, boolean isUnique, boolean isPrimary, long indexSize) {
        if (isPrimary) {
            return "NOT SAFE: Cannot drop PRIMARY KEY index directly. Modify table constraints instead.";
        }

        if (isUnique) {
            return "CAUTION: Dropping this index will remove UNIQUE constraint. Verify no business logic depends on uniqueness.";
        }

        if (indexScans == 0) {
            return "SAFE TO DROP: Index has never been used. Space reclaimed: " + formatBytes(indexSize);
        }

        if (indexScans < 100) {
            return "LOW RISK: Index rarely used (" + indexScans + " scans). Consider monitoring before dropping.";
        }

        return "NOT RECOMMENDED: Index is actively used (" + indexScans + " scans). Dropping may impact query performance.";
    }

    private int calculateHealthScore(int unusedCount, int duplicateCount, int pendingCount,
                                     Map<String, Object> stats) {
        int score = 100;

        // Deduct for unused indexes (max -30)
        score -= Math.min(unusedCount * 5, 30);

        // Deduct for duplicate indexes (max -20)
        score -= Math.min(duplicateCount * 10, 20);

        // Deduct for pending recommendations (max -20)
        score -= Math.min(pendingCount * 2, 20);

        return Math.max(0, score);
    }

    private String getHealthGrade(int score) {
        if (score >= 90) return "A";
        if (score >= 80) return "B";
        if (score >= 70) return "C";
        if (score >= 60) return "D";
        return "F";
    }

    private String generateHealthSummary(int healthScore, int unusedCount, int duplicateCount, int pendingCount) {
        StringBuilder summary = new StringBuilder();

        if (healthScore >= 90) {
            summary.append("Index health is excellent. ");
        } else if (healthScore >= 70) {
            summary.append("Index health is good with some opportunities for improvement. ");
        } else {
            summary.append("Index health needs attention. ");
        }

        if (unusedCount > 0) {
            summary.append(String.format("%d unused index(es) found - consider dropping to save space. ", unusedCount));
        }

        if (duplicateCount > 0) {
            summary.append(String.format("%d duplicate/redundant index(es) detected. ", duplicateCount));
        }

        if (pendingCount > 0) {
            summary.append(String.format("%d new index recommendation(s) available. ", pendingCount));
        }

        if (unusedCount == 0 && duplicateCount == 0 && pendingCount == 0) {
            summary.append("No immediate action required.");
        }

        return summary.toString();
    }

    private String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1048576) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1073741824) return String.format("%.1f MB", bytes / 1048576.0);
        return String.format("%.2f GB", bytes / 1073741824.0);
    }
}
