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
 * Service for assessing sharding readiness of tables.
 *
 * Evaluates:
 * - Natural shard key candidates (tenant_id, region, date columns)
 * - Data distribution analysis
 * - Cross-shard query patterns (joins that would span shards)
 * - Foreign key constraints that complicate sharding
 * - Estimated shard count recommendations
 *
 * Readiness Levels:
 * - READY: Has natural shard key, no cross-shard dependencies
 * - NEEDS_REFACTORING: Possible with schema changes
 * - COMPLEX: Significant refactoring required
 * - NOT_RECOMMENDED: Sharding would cause major issues
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ShardingReadinessService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Thresholds
    private static final long SHARDING_THRESHOLD_ROWS = 10_000_000;  // 10M rows
    private static final long LARGE_TABLE_ROWS = 100_000_000;        // 100M rows
    private static final int MAX_FK_REFERENCES = 5;

    // Common shard key column patterns
    private static final List<String> SHARD_KEY_PATTERNS = List.of(
        "tenant_id", "tenant", "org_id", "organization_id", "company_id",
        "region", "region_id", "country", "country_id",
        "user_id", "customer_id", "account_id",
        "created_at", "created_date", "event_date", "partition_date"
    );

    /**
     * Assess sharding readiness for all tables in a connection.
     */
    public Map<String, ShardingReadiness> assessShardingReadiness(String connectionId) {
        Map<String, ShardingReadiness> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get table metadata
            Map<String, TableInfo> tableInfos = getTableInfo(conn, dbType);

            // Get column information for shard key analysis
            Map<String, List<ColumnInfo>> tableColumns = getColumnInfo(conn, dbType);

            // Get foreign key relationships
            Map<String, List<ForeignKeyInfo>> foreignKeys = getForeignKeyInfo(conn, dbType);

            // Assess each table
            for (Map.Entry<String, TableInfo> entry : tableInfos.entrySet()) {
                String tableName = entry.getKey();
                TableInfo info = entry.getValue();
                List<ColumnInfo> columns = tableColumns.getOrDefault(tableName, List.of());
                List<ForeignKeyInfo> fks = foreignKeys.getOrDefault(tableName, List.of());

                // Count incoming FKs (tables that reference this table)
                long incomingFks = foreignKeys.values().stream()
                    .flatMap(List::stream)
                    .filter(fk -> fk.getReferencedTable().equalsIgnoreCase(tableName))
                    .count();

                ShardingReadiness readiness = assessTableSharding(
                    tableName, info, columns, fks, (int) incomingFks);
                results.put(tableName.toLowerCase(), readiness);
            }
        } catch (Exception e) {
            log.error("Error assessing sharding readiness for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, TableInfo> getTableInfo(Connection conn, String dbType) {
        Map<String, TableInfo> results = new HashMap<>();
        String query;

        if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
            query = """
                SELECT
                    relname as table_name,
                    COALESCE(n_live_tup, 0) as row_count,
                    pg_table_size(schemaname || '.' || relname) as size_bytes
                FROM pg_stat_user_tables
                WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            query = """
                SELECT
                    TABLE_NAME as table_name,
                    TABLE_ROWS as row_count,
                    DATA_LENGTH + INDEX_LENGTH as size_bytes
                FROM information_schema.TABLES
                WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                AND TABLE_TYPE = 'BASE TABLE'
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                results.put(tableName, TableInfo.builder()
                    .rowCount(rs.getLong("row_count"))
                    .sizeBytes(rs.getLong("size_bytes"))
                    .build());
            }
        } catch (Exception e) {
            log.debug("Error getting table info: {}", e.getMessage());
        }

        return results;
    }

    private Map<String, List<ColumnInfo>> getColumnInfo(Connection conn, String dbType) {
        Map<String, List<ColumnInfo>> results = new HashMap<>();
        String query;

        if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
            query = """
                SELECT
                    c.relname as table_name,
                    a.attname as column_name,
                    t.typname as data_type,
                    COALESCE(s.n_distinct, 0) as distinct_count
                FROM pg_attribute a
                JOIN pg_class c ON a.attrelid = c.oid
                JOIN pg_namespace n ON c.relnamespace = n.oid
                JOIN pg_type t ON a.atttypid = t.oid
                LEFT JOIN pg_stats s ON s.schemaname = n.nspname
                    AND s.tablename = c.relname AND s.attname = a.attname
                WHERE c.relkind = 'r'
                AND n.nspname NOT IN ('pg_catalog', 'information_schema')
                AND a.attnum > 0
                AND NOT a.attisdropped
                ORDER BY table_name, a.attnum
                """;
        } else {
            query = """
                SELECT
                    TABLE_NAME as table_name,
                    COLUMN_NAME as column_name,
                    DATA_TYPE as data_type,
                    0 as distinct_count
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                ORDER BY table_name, ORDINAL_POSITION
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                ColumnInfo col = ColumnInfo.builder()
                    .columnName(rs.getString("column_name"))
                    .dataType(rs.getString("data_type"))
                    .distinctCount(rs.getLong("distinct_count"))
                    .build();
                results.computeIfAbsent(tableName, k -> new ArrayList<>()).add(col);
            }
        } catch (Exception e) {
            log.debug("Error getting column info: {}", e.getMessage());
        }

        return results;
    }

    private Map<String, List<ForeignKeyInfo>> getForeignKeyInfo(Connection conn, String dbType) {
        Map<String, List<ForeignKeyInfo>> results = new HashMap<>();
        String query;

        if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
            query = """
                SELECT
                    cl.relname as table_name,
                    conname as constraint_name,
                    clr.relname as referenced_table
                FROM pg_constraint c
                JOIN pg_class cl ON c.conrelid = cl.oid
                JOIN pg_namespace ns ON cl.relnamespace = ns.oid
                JOIN pg_class clr ON c.confrelid = clr.oid
                JOIN pg_namespace nsr ON clr.relnamespace = nsr.oid
                WHERE c.contype = 'f'
                AND ns.nspname NOT IN ('pg_catalog', 'information_schema')
                """;
        } else {
            query = """
                SELECT
                    TABLE_NAME as table_name,
                    CONSTRAINT_NAME as constraint_name,
                    REFERENCED_TABLE_NAME as referenced_table
                FROM information_schema.KEY_COLUMN_USAGE
                WHERE REFERENCED_TABLE_NAME IS NOT NULL
                AND TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
                """;
        }

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                ForeignKeyInfo fk = ForeignKeyInfo.builder()
                    .constraintName(rs.getString("constraint_name"))
                    .referencedTable(rs.getString("referenced_table"))
                    .build();
                results.computeIfAbsent(tableName, k -> new ArrayList<>()).add(fk);
            }
        } catch (Exception e) {
            log.debug("Error getting FK info: {}", e.getMessage());
        }

        return results;
    }

    private ShardingReadiness assessTableSharding(String tableName, TableInfo info,
                                                    List<ColumnInfo> columns,
                                                    List<ForeignKeyInfo> outgoingFks,
                                                    int incomingFkCount) {
        List<ShardKeyCandidate> shardKeyCandidates = new ArrayList<>();
        List<String> challenges = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        int readinessScore = 100;

        // Check if table needs sharding
        boolean needsSharding = info.getRowCount() >= SHARDING_THRESHOLD_ROWS;
        boolean isLarge = info.getRowCount() >= LARGE_TABLE_ROWS;

        // Find potential shard key columns
        for (ColumnInfo col : columns) {
            String colNameLower = col.getColumnName().toLowerCase();
            for (String pattern : SHARD_KEY_PATTERNS) {
                if (colNameLower.contains(pattern) || colNameLower.equals(pattern)) {
                    double suitability = calculateShardKeySuitability(col, pattern);
                    shardKeyCandidates.add(ShardKeyCandidate.builder()
                        .columnName(col.getColumnName())
                        .dataType(col.getDataType())
                        .patternMatch(pattern)
                        .suitabilityScore(BigDecimal.valueOf(suitability).setScale(2, RoundingMode.HALF_UP))
                        .build());
                    break;
                }
            }
        }

        // Evaluate FK complexity
        int totalFkCount = outgoingFks.size() + incomingFkCount;
        if (totalFkCount > MAX_FK_REFERENCES) {
            challenges.add(String.format("%d FK relationships would complicate cross-shard queries", totalFkCount));
            readinessScore -= 20;
        }
        if (incomingFkCount > 3) {
            challenges.add(String.format("%d tables reference this table - sharding would break these relationships", incomingFkCount));
            readinessScore -= 15;
        }

        // Check for shard key presence
        if (shardKeyCandidates.isEmpty()) {
            challenges.add("No natural shard key column found");
            readinessScore -= 30;
            recommendations.add("Add a tenant_id or similar column for multi-tenant sharding");
            recommendations.add("Consider date-based sharding if time-series data");
        } else {
            // Sort by suitability
            shardKeyCandidates.sort((a, b) -> b.getSuitabilityScore().compareTo(a.getSuitabilityScore()));
            ShardKeyCandidate best = shardKeyCandidates.get(0);
            recommendations.add(String.format("Best shard key candidate: %s (score: %.0f%%)",
                best.getColumnName(), best.getSuitabilityScore().doubleValue()));
        }

        // Estimate shard count
        int recommendedShards = calculateRecommendedShards(info.getRowCount());

        // Determine readiness level
        String readinessLevel;
        if (!needsSharding) {
            readinessLevel = "NOT_NEEDED";
            recommendations.clear();
            recommendations.add(String.format("Table has %,d rows - below sharding threshold", info.getRowCount()));
        } else if (readinessScore >= 70 && !shardKeyCandidates.isEmpty()) {
            readinessLevel = "READY";
            recommendations.add("Table is a good candidate for sharding");
            recommendations.add(String.format("Recommend %d shards for optimal distribution", recommendedShards));
        } else if (readinessScore >= 40) {
            readinessLevel = "NEEDS_REFACTORING";
            recommendations.add("Sharding possible with schema modifications");
            recommendations.add("Review and potentially remove some FK constraints");
        } else if (readinessScore >= 20) {
            readinessLevel = "COMPLEX";
            recommendations.add("Significant refactoring required before sharding");
            recommendations.add("Consider alternative scaling strategies first");
        } else {
            readinessLevel = "NOT_RECOMMENDED";
            recommendations.add("Sharding not recommended for this table");
            recommendations.add("Consider read replicas or caching instead");
        }

        String reasoning = String.format(
            "%,d rows, %d outgoing FKs, %d incoming FKs, %d shard key candidates",
            info.getRowCount(), outgoingFks.size(), incomingFkCount, shardKeyCandidates.size());

        return ShardingReadiness.builder()
            .tableName(tableName)
            .readinessLevel(readinessLevel)
            .readinessScore(BigDecimal.valueOf(readinessScore))
            .shardKeyCandidates(shardKeyCandidates)
            .recommendedShardCount(recommendedShards)
            .rowCount(info.getRowCount())
            .outgoingFkCount(outgoingFks.size())
            .incomingFkCount(incomingFkCount)
            .challenges(challenges)
            .recommendations(recommendations)
            .reasoning(reasoning)
            .build();
    }

    private double calculateShardKeySuitability(ColumnInfo col, String patternMatch) {
        double score = 50; // Base score for pattern match

        // Tenant/org IDs are ideal
        if (patternMatch.contains("tenant") || patternMatch.contains("org")) {
            score += 40;
        }
        // User/customer IDs are good
        else if (patternMatch.contains("user") || patternMatch.contains("customer") || patternMatch.contains("account")) {
            score += 30;
        }
        // Region-based is moderate
        else if (patternMatch.contains("region") || patternMatch.contains("country")) {
            score += 25;
        }
        // Date-based requires special handling
        else if (patternMatch.contains("date") || patternMatch.contains("created")) {
            score += 20;
        }

        // Integer types are better for hashing
        String dataType = col.getDataType().toLowerCase();
        if (dataType.contains("int") || dataType.contains("bigint")) {
            score += 10;
        }

        return Math.min(100, score);
    }

    private int calculateRecommendedShards(long rowCount) {
        if (rowCount < SHARDING_THRESHOLD_ROWS) return 0;
        if (rowCount < 50_000_000) return 4;
        if (rowCount < 100_000_000) return 8;
        if (rowCount < 500_000_000) return 16;
        if (rowCount < 1_000_000_000) return 32;
        return 64;
    }

    @Data
    @Builder
    private static class TableInfo {
        private Long rowCount;
        private Long sizeBytes;
    }

    @Data
    @Builder
    private static class ColumnInfo {
        private String columnName;
        private String dataType;
        private Long distinctCount;
    }

    @Data
    @Builder
    private static class ForeignKeyInfo {
        private String constraintName;
        private String referencedTable;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShardKeyCandidate {
        private String columnName;
        private String dataType;
        private String patternMatch;
        private BigDecimal suitabilityScore;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class ShardingReadiness {
        private String tableName;
        private String readinessLevel;  // READY, NEEDS_REFACTORING, COMPLEX, NOT_RECOMMENDED, NOT_NEEDED
        private BigDecimal readinessScore;
        private List<ShardKeyCandidate> shardKeyCandidates;
        private Integer recommendedShardCount;
        private Long rowCount;
        private Integer outgoingFkCount;
        private Integer incomingFkCount;
        private List<String> challenges;
        private List<String> recommendations;
        private String reasoning;
    }
}
