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

/**
 * Service for attributing storage and compute costs to tables.
 *
 * Calculates:
 * - Storage cost based on table size (data + indexes)
 * - Estimated compute cost based on query frequency
 * - Cost per row for efficiency analysis
 * - Cost growth trend
 *
 * Uses configurable cost rates that can be adjusted per cloud provider.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CostAttributionService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Default cost rates (can be made configurable)
    private static final BigDecimal STORAGE_COST_PER_GB_MONTH = new BigDecimal("0.115"); // AWS RDS typical
    private static final BigDecimal IOPS_COST_PER_MILLION = new BigDecimal("0.20");
    private static final BigDecimal COMPUTE_COST_PER_CPU_HOUR = new BigDecimal("0.05");

    /**
     * Calculate cost attribution for all tables in a connection.
     */
    public Map<String, CostAttribution> calculateCostAttribution(String connectionId) {
        Map<String, CostAttribution> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                results = calculatePostgresCost(conn);
            } else if ("mysql".equalsIgnoreCase(dbType)) {
                results = calculateMysqlCost(conn);
            } else {
                log.warn("Unsupported database type for cost attribution: {}", dbType);
            }
        } catch (Exception e) {
            log.error("Error calculating cost attribution for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, CostAttribution> calculatePostgresCost(Connection conn) {
        Map<String, CostAttribution> results = new HashMap<>();

        String query = """
            SELECT
                relname as table_name,
                pg_table_size(schemaname || '.' || relname) as data_size_bytes,
                pg_indexes_size(schemaname || '.' || relname) as index_size_bytes,
                pg_total_relation_size(schemaname || '.' || relname) as total_size_bytes,
                COALESCE(n_live_tup, 0) as row_count,
                COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0) as total_reads,
                COALESCE(n_tup_ins, 0) + COALESCE(n_tup_upd, 0) + COALESCE(n_tup_del, 0) as total_writes,
                COALESCE(seq_tup_read, 0) + COALESCE(idx_tup_fetch, 0) as tuples_fetched
            FROM pg_stat_user_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            """;

        // Get total database size for percentage calculation
        long totalDbSize = getTotalDbSize(conn, "postgresql");

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long dataSize = rs.getLong("data_size_bytes");
                long indexSize = rs.getLong("index_size_bytes");
                long totalSize = rs.getLong("total_size_bytes");
                long rowCount = rs.getLong("row_count");
                long totalReads = rs.getLong("total_reads");
                long totalWrites = rs.getLong("total_writes");
                long tuplesFetched = rs.getLong("tuples_fetched");

                CostAttribution cost = calculateTableCost(
                    tableName, dataSize, indexSize, totalSize, rowCount,
                    totalReads, totalWrites, tuplesFetched, totalDbSize);
                results.put(tableName.toLowerCase(), cost);
            }
        } catch (Exception e) {
            log.error("Error querying PostgreSQL cost data: {}", e.getMessage(), e);
        }

        return results;
    }

    private Map<String, CostAttribution> calculateMysqlCost(Connection conn) {
        Map<String, CostAttribution> results = new HashMap<>();

        String query = """
            SELECT
                t.TABLE_NAME as table_name,
                t.DATA_LENGTH as data_size_bytes,
                t.INDEX_LENGTH as index_size_bytes,
                t.DATA_LENGTH + t.INDEX_LENGTH as total_size_bytes,
                t.TABLE_ROWS as row_count
            FROM information_schema.TABLES t
            WHERE t.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            AND t.TABLE_TYPE = 'BASE TABLE'
            """;

        long totalDbSize = getTotalDbSize(conn, "mysql");

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long dataSize = rs.getLong("data_size_bytes");
                long indexSize = rs.getLong("index_size_bytes");
                long totalSize = rs.getLong("total_size_bytes");
                long rowCount = rs.getLong("row_count");

                // MySQL doesn't expose read/write stats easily without performance_schema
                CostAttribution cost = calculateTableCost(
                    tableName, dataSize, indexSize, totalSize, rowCount,
                    0, 0, 0, totalDbSize);
                results.put(tableName.toLowerCase(), cost);
            }
        } catch (Exception e) {
            log.error("Error querying MySQL cost data: {}", e.getMessage(), e);
        }

        return results;
    }

    private long getTotalDbSize(Connection conn, String dbType) {
        String query;
        if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
            query = "SELECT SUM(pg_total_relation_size(schemaname || '.' || relname)) FROM pg_stat_user_tables WHERE schemaname NOT IN ('pg_catalog', 'information_schema')";
        } else {
            query = "SELECT SUM(DATA_LENGTH + INDEX_LENGTH) FROM information_schema.TABLES WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')";
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            if (rs.next()) {
                return rs.getLong(1);
            }
        } catch (Exception e) {
            log.debug("Error getting total DB size: {}", e.getMessage());
        }
        return 1; // Avoid division by zero
    }

    private CostAttribution calculateTableCost(String tableName, long dataSize, long indexSize,
                                                long totalSize, long rowCount, long totalReads,
                                                long totalWrites, long tuplesFetched, long totalDbSize) {
        // Convert to GB
        double dataSizeGb = dataSize / (1024.0 * 1024.0 * 1024.0);
        double indexSizeGb = indexSize / (1024.0 * 1024.0 * 1024.0);
        double totalSizeGb = totalSize / (1024.0 * 1024.0 * 1024.0);

        // Calculate storage cost (monthly)
        BigDecimal storageCost = STORAGE_COST_PER_GB_MONTH.multiply(BigDecimal.valueOf(totalSizeGb));

        // Estimate IOPS cost (based on operations, normalized to 30 days)
        long totalOps = totalReads + totalWrites;
        double opsPerMonth = totalOps; // Assuming stats are cumulative since last reset
        BigDecimal iopsCost = IOPS_COST_PER_MILLION.multiply(BigDecimal.valueOf(opsPerMonth / 1_000_000.0));

        // Estimate compute cost (rough approximation based on data accessed)
        double computeUnits = tuplesFetched / 1_000_000.0; // Normalize tuple access
        BigDecimal computeCost = COMPUTE_COST_PER_CPU_HOUR.multiply(BigDecimal.valueOf(computeUnits));

        // Total estimated monthly cost
        BigDecimal totalMonthlyCost = storageCost.add(iopsCost).add(computeCost);

        // Cost per row
        BigDecimal costPerRow = rowCount > 0
            ? totalMonthlyCost.divide(BigDecimal.valueOf(rowCount), 10, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        // Cost per GB (efficiency metric)
        BigDecimal costPerGb = totalSizeGb > 0
            ? totalMonthlyCost.divide(BigDecimal.valueOf(totalSizeGb), 4, RoundingMode.HALF_UP)
            : BigDecimal.ZERO;

        // Percentage of total database cost
        double costPercentage = totalDbSize > 0 ? (double) totalSize / totalDbSize * 100 : 0;

        // Determine cost tier
        String costTier;
        List<String> recommendations = new ArrayList<>();

        if (totalMonthlyCost.compareTo(new BigDecimal("100")) > 0) {
            costTier = "HIGH_COST";
            recommendations.add("Review data retention policies");
            recommendations.add("Consider archiving old data");
            recommendations.add("Evaluate partitioning for better data lifecycle management");
        } else if (totalMonthlyCost.compareTo(new BigDecimal("10")) > 0) {
            costTier = "MEDIUM_COST";
            recommendations.add("Monitor growth trends");
            recommendations.add("Consider compression if not already applied");
        } else if (totalMonthlyCost.compareTo(new BigDecimal("1")) > 0) {
            costTier = "LOW_COST";
            recommendations.add("Standard monitoring sufficient");
        } else {
            costTier = "MINIMAL_COST";
            recommendations.add("No cost optimization needed");
        }

        // Check for inefficiencies
        if (rowCount > 0 && costPerRow.compareTo(new BigDecimal("0.0001")) > 0) {
            recommendations.add("High cost per row - review if all columns are needed");
        }
        if (indexSizeGb > dataSizeGb * 0.5) {
            recommendations.add("Index size is >50% of data size - review index necessity");
        }

        Map<String, BigDecimal> breakdown = new LinkedHashMap<>();
        breakdown.put("storage_cost", storageCost.setScale(4, RoundingMode.HALF_UP));
        breakdown.put("iops_cost", iopsCost.setScale(4, RoundingMode.HALF_UP));
        breakdown.put("compute_cost", computeCost.setScale(4, RoundingMode.HALF_UP));

        String reasoning = String.format(
            "%.2f GB total (%.2f data + %.2f index), %,d rows, %.1f%% of database",
            totalSizeGb, dataSizeGb, indexSizeGb, rowCount, costPercentage);

        return CostAttribution.builder()
            .tableName(tableName)
            .costTier(costTier)
            .estimatedMonthlyCost(totalMonthlyCost.setScale(4, RoundingMode.HALF_UP))
            .storageCost(storageCost.setScale(4, RoundingMode.HALF_UP))
            .computeCost(computeCost.add(iopsCost).setScale(4, RoundingMode.HALF_UP))
            .costBreakdown(breakdown)
            .costPerRow(costPerRow.setScale(10, RoundingMode.HALF_UP))
            .costPerGb(costPerGb.setScale(4, RoundingMode.HALF_UP))
            .dataSizeMb(dataSize / (1024 * 1024))
            .indexSizeMb(indexSize / (1024 * 1024))
            .totalSizeMb(totalSize / (1024 * 1024))
            .rowCount(rowCount)
            .costPercentageOfDb(BigDecimal.valueOf(costPercentage).setScale(2, RoundingMode.HALF_UP))
            .reasoning(reasoning)
            .recommendations(recommendations)
            .build();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CostAttribution {
        private String tableName;
        private String costTier;  // HIGH_COST, MEDIUM_COST, LOW_COST, MINIMAL_COST
        private BigDecimal estimatedMonthlyCost;
        private BigDecimal storageCost;
        private BigDecimal computeCost;
        private Map<String, BigDecimal> costBreakdown;
        private BigDecimal costPerRow;
        private BigDecimal costPerGb;
        private Long dataSizeMb;
        private Long indexSizeMb;
        private Long totalSizeMb;
        private Long rowCount;
        private BigDecimal costPercentageOfDb;
        private String reasoning;
        private List<String> recommendations;
    }
}
