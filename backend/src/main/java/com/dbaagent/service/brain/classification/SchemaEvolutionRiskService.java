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
 * Service for assessing schema evolution risk.
 *
 * Risk Levels:
 * - CRITICAL: Very risky to modify (large table, many dependencies, critical path)
 * - HIGH: Significant risk (large or heavily used, some dependencies)
 * - MEDIUM: Moderate risk (medium size, some considerations)
 * - LOW: Low risk (small table, few dependencies)
 * - MINIMAL: Very safe to modify (tiny table, no dependencies)
 *
 * Factors:
 * - Table size (rows, GB)
 * - Number of dependent tables (FK references to this table)
 * - Number of indexes
 * - Column count
 * - Estimated downtime for DDL operations
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class SchemaEvolutionRiskService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Thresholds
    private static final long CRITICAL_ROW_COUNT = 100_000_000;  // 100M rows
    private static final long HIGH_ROW_COUNT = 10_000_000;       // 10M rows
    private static final long MEDIUM_ROW_COUNT = 1_000_000;      // 1M rows
    private static final int CRITICAL_DEPENDENCY_COUNT = 20;
    private static final int HIGH_DEPENDENCY_COUNT = 10;
    private static final int CRITICAL_INDEX_COUNT = 15;
    private static final int HIGH_INDEX_COUNT = 8;

    /**
     * Assess schema evolution risk for all tables in a connection.
     */
    public Map<String, SchemaEvolutionRisk> assessSchemaEvolutionRisk(String connectionId) {
        Map<String, SchemaEvolutionRisk> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                results = assessPostgresRisk(conn);
            } else if ("mysql".equalsIgnoreCase(dbType)) {
                results = assessMysqlRisk(conn);
            } else {
                log.warn("Unsupported database type for schema evolution risk: {}", dbType);
            }
        } catch (Exception e) {
            log.error("Error assessing schema evolution risk for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, SchemaEvolutionRisk> assessPostgresRisk(Connection conn) {
        Map<String, SchemaEvolutionRisk> results = new HashMap<>();

        String query = """
            WITH table_info AS (
                SELECT
                    c.oid,
                    n.nspname || '.' || c.relname as table_name,
                    c.reltuples::bigint as row_count,
                    pg_table_size(c.oid) as table_size_bytes,
                    (SELECT COUNT(*) FROM pg_attribute WHERE attrelid = c.oid AND attnum > 0 AND NOT attisdropped) as column_count
                FROM pg_class c
                JOIN pg_namespace n ON n.oid = c.relnamespace
                WHERE c.relkind = 'r'
                AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            ),
            index_counts AS (
                SELECT
                    indrelid,
                    COUNT(*) as index_count
                FROM pg_index
                GROUP BY indrelid
            ),
            fk_references AS (
                SELECT
                    confrelid as referenced_table,
                    COUNT(*) as reference_count
                FROM pg_constraint
                WHERE contype = 'f'
                GROUP BY confrelid
            )
            SELECT
                t.table_name,
                t.row_count,
                t.table_size_bytes,
                t.column_count,
                COALESCE(i.index_count, 0) as index_count,
                COALESCE(f.reference_count, 0) as dependency_count
            FROM table_info t
            LEFT JOIN index_counts i ON i.indrelid = t.oid
            LEFT JOIN fk_references f ON f.referenced_table = t.oid
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long rowCount = rs.getLong("row_count");
                long sizeBytes = rs.getLong("table_size_bytes");
                int columnCount = rs.getInt("column_count");
                int indexCount = rs.getInt("index_count");
                int dependencyCount = rs.getInt("dependency_count");

                SchemaEvolutionRisk risk = calculateRisk(
                    tableName, rowCount, sizeBytes, columnCount, indexCount, dependencyCount);
                results.put(tableName.toLowerCase(), risk);
            }
        } catch (Exception e) {
            log.error("Error querying PostgreSQL schema risk: {}", e.getMessage(), e);
        }

        return results;
    }

    private Map<String, SchemaEvolutionRisk> assessMysqlRisk(Connection conn) {
        Map<String, SchemaEvolutionRisk> results = new HashMap<>();

        String query = """
            SELECT
                t.TABLE_NAME as table_name,
                t.TABLE_ROWS as row_count,
                t.DATA_LENGTH + t.INDEX_LENGTH as table_size_bytes,
                (SELECT COUNT(*) FROM information_schema.COLUMNS c
                 WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME) as column_count,
                (SELECT COUNT(*) FROM information_schema.STATISTICS s
                 WHERE s.TABLE_SCHEMA = t.TABLE_SCHEMA AND s.TABLE_NAME = t.TABLE_NAME
                 AND s.SEQ_IN_INDEX = 1) as index_count,
                (SELECT COUNT(*) FROM information_schema.KEY_COLUMN_USAGE k
                 WHERE k.REFERENCED_TABLE_SCHEMA = t.TABLE_SCHEMA
                 AND k.REFERENCED_TABLE_NAME = t.TABLE_NAME) as dependency_count
            FROM information_schema.TABLES t
            WHERE t.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            AND t.TABLE_TYPE = 'BASE TABLE'
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long rowCount = rs.getLong("row_count");
                long sizeBytes = rs.getLong("table_size_bytes");
                int columnCount = rs.getInt("column_count");
                int indexCount = rs.getInt("index_count");
                int dependencyCount = rs.getInt("dependency_count");

                SchemaEvolutionRisk risk = calculateRisk(
                    tableName, rowCount, sizeBytes, columnCount, indexCount, dependencyCount);
                results.put(tableName.toLowerCase(), risk);
            }
        } catch (Exception e) {
            log.error("Error querying MySQL schema risk: {}", e.getMessage(), e);
        }

        return results;
    }

    private SchemaEvolutionRisk calculateRisk(String tableName, long rowCount, long sizeBytes,
                                               int columnCount, int indexCount, int dependencyCount) {
        Map<String, Double> factors = new HashMap<>();

        // Size factor (0-100, larger = higher risk)
        double sizeFactor;
        if (rowCount >= CRITICAL_ROW_COUNT) {
            sizeFactor = 100;
        } else if (rowCount >= HIGH_ROW_COUNT) {
            sizeFactor = 70 + (double) (rowCount - HIGH_ROW_COUNT) / (CRITICAL_ROW_COUNT - HIGH_ROW_COUNT) * 30;
        } else if (rowCount >= MEDIUM_ROW_COUNT) {
            sizeFactor = 40 + (double) (rowCount - MEDIUM_ROW_COUNT) / (HIGH_ROW_COUNT - MEDIUM_ROW_COUNT) * 30;
        } else {
            sizeFactor = (double) rowCount / MEDIUM_ROW_COUNT * 40;
        }
        factors.put("size_factor", round(sizeFactor));

        // Dependency factor (0-100)
        double dependencyFactor;
        if (dependencyCount >= CRITICAL_DEPENDENCY_COUNT) {
            dependencyFactor = 100;
        } else if (dependencyCount >= HIGH_DEPENDENCY_COUNT) {
            dependencyFactor = 70 + (double) (dependencyCount - HIGH_DEPENDENCY_COUNT) / (CRITICAL_DEPENDENCY_COUNT - HIGH_DEPENDENCY_COUNT) * 30;
        } else {
            dependencyFactor = (double) dependencyCount / HIGH_DEPENDENCY_COUNT * 70;
        }
        factors.put("dependency_factor", round(dependencyFactor));

        // Index factor (0-100, more indexes = more risk during DDL)
        double indexFactor;
        if (indexCount >= CRITICAL_INDEX_COUNT) {
            indexFactor = 100;
        } else if (indexCount >= HIGH_INDEX_COUNT) {
            indexFactor = 60 + (double) (indexCount - HIGH_INDEX_COUNT) / (CRITICAL_INDEX_COUNT - HIGH_INDEX_COUNT) * 40;
        } else {
            indexFactor = (double) indexCount / HIGH_INDEX_COUNT * 60;
        }
        factors.put("index_factor", round(indexFactor));

        // Column factor (0-50, more columns = more complexity)
        double columnFactor = Math.min(50, columnCount * 1.5);
        factors.put("column_factor", round(columnFactor));

        // Calculate overall risk score (weighted)
        double overallScore = (sizeFactor * 0.4) + (dependencyFactor * 0.3) + (indexFactor * 0.2) + (columnFactor * 0.1);

        // Determine risk level
        String riskLevel;
        List<String> recommendations = new ArrayList<>();
        List<String> migrationStrategies = new ArrayList<>();

        if (overallScore >= 80) {
            riskLevel = "CRITICAL";
            recommendations.add("Requires extensive testing and rollback plan");
            recommendations.add("Consider blue-green deployment strategy");
            recommendations.add("Plan maintenance window for DDL operations");
            recommendations.add("Notify all dependent application teams");
            migrationStrategies.add("Use pt-online-schema-change (MySQL) or pg_repack (PostgreSQL)");
            migrationStrategies.add("Consider zero-downtime migration tools");
        } else if (overallScore >= 60) {
            riskLevel = "HIGH";
            recommendations.add("Test thoroughly in staging environment");
            recommendations.add("Plan for potential performance impact");
            recommendations.add("Have rollback procedure ready");
            migrationStrategies.add("Use online DDL if supported");
            migrationStrategies.add("Consider off-peak hours for changes");
        } else if (overallScore >= 40) {
            riskLevel = "MEDIUM";
            recommendations.add("Test in development environment");
            recommendations.add("Monitor table during migration");
            migrationStrategies.add("Standard DDL should work with minimal impact");
        } else if (overallScore >= 20) {
            riskLevel = "LOW";
            recommendations.add("Standard testing sufficient");
            migrationStrategies.add("Direct DDL operations are safe");
        } else {
            riskLevel = "MINIMAL";
            recommendations.add("Safe to modify with basic validation");
            migrationStrategies.add("Simple DDL operations");
        }

        // Estimate DDL time based on size
        long sizeMb = sizeBytes / (1024 * 1024);
        String estimatedDdlImpact;
        if (rowCount >= CRITICAL_ROW_COUNT) {
            estimatedDdlImpact = "Hours (may require maintenance window)";
        } else if (rowCount >= HIGH_ROW_COUNT) {
            estimatedDdlImpact = "10-60 minutes";
        } else if (rowCount >= MEDIUM_ROW_COUNT) {
            estimatedDdlImpact = "1-10 minutes";
        } else {
            estimatedDdlImpact = "Seconds to minutes";
        }

        String reasoning = String.format(
            "Risk assessment: %,d rows, %d indexes, %d dependent tables, %d columns",
            rowCount, indexCount, dependencyCount, columnCount);

        return SchemaEvolutionRisk.builder()
            .tableName(tableName)
            .riskLevel(riskLevel)
            .riskScore(BigDecimal.valueOf(overallScore).setScale(2, RoundingMode.HALF_UP))
            .factors(factors)
            .rowCount(rowCount)
            .tableSizeMb(sizeMb)
            .columnCount(columnCount)
            .indexCount(indexCount)
            .dependencyCount(dependencyCount)
            .estimatedDdlImpact(estimatedDdlImpact)
            .reasoning(reasoning)
            .recommendations(recommendations)
            .migrationStrategies(migrationStrategies)
            .build();
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SchemaEvolutionRisk {
        private String tableName;
        private String riskLevel;  // CRITICAL, HIGH, MEDIUM, LOW, MINIMAL
        private BigDecimal riskScore;
        private Map<String, Double> factors;
        private Long rowCount;
        private Long tableSizeMb;
        private Integer columnCount;
        private Integer indexCount;
        private Integer dependencyCount;
        private String estimatedDdlImpact;
        private String reasoning;
        private List<String> recommendations;
        private List<String> migrationStrategies;
    }
}
