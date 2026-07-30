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
 * Service for calculating data quality scores for tables.
 *
 * Quality Dimensions:
 * - Completeness: NULL ratio analysis
 * - Uniqueness: Duplicate detection
 * - Validity: Constraint coverage (PK, FK, CHECK)
 * - Consistency: Data type appropriateness
 * - Timeliness: Data freshness indicators
 *
 * Scores are aggregated into an overall Data Quality Score (0-100).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataQualityScoreService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    /**
     * Calculate data quality scores for all tables in a connection.
     */
    public Map<String, DataQualityScore> calculateDataQuality(String connectionId) {
        Map<String, DataQualityScore> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                results = calculatePostgresQuality(conn);
            } else if ("mysql".equalsIgnoreCase(dbType)) {
                results = calculateMysqlQuality(conn);
            } else {
                log.warn("Unsupported database type for data quality: {}", dbType);
            }
        } catch (Exception e) {
            log.error("Error calculating data quality for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, DataQualityScore> calculatePostgresQuality(Connection conn) {
        Map<String, DataQualityScore> results = new HashMap<>();

        // Get table-level statistics
        String tableQuery = """
            SELECT
                c.relname as table_name,
                c.reltuples::bigint as row_count,
                (SELECT COUNT(*) FROM pg_attribute a WHERE a.attrelid = c.oid AND a.attnum > 0 AND NOT a.attisdropped) as column_count,
                (SELECT COUNT(*) FROM pg_constraint con WHERE con.conrelid = c.oid AND con.contype = 'p') as has_pk,
                (SELECT COUNT(*) FROM pg_constraint con WHERE con.conrelid = c.oid AND con.contype = 'f') as fk_count,
                (SELECT COUNT(*) FROM pg_constraint con WHERE con.conrelid = c.oid AND con.contype = 'c') as check_count,
                (SELECT COUNT(*) FROM pg_constraint con WHERE con.conrelid = c.oid AND con.contype = 'u') as unique_count,
                (SELECT COUNT(*) FROM pg_index i WHERE i.indrelid = c.oid) as index_count
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            WHERE c.relkind = 'r'
            AND n.nspname NOT IN ('pg_catalog', 'information_schema')
            """;

        // Get null statistics per column
        Map<String, NullStats> nullStats = getNullStatsPostgres(conn);

        try (PreparedStatement stmt = conn.prepareStatement(tableQuery);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                long rowCount = rs.getLong("row_count");
                int columnCount = rs.getInt("column_count");
                boolean hasPk = rs.getInt("has_pk") > 0;
                int fkCount = rs.getInt("fk_count");
                int checkCount = rs.getInt("check_count");
                int uniqueCount = rs.getInt("unique_count");
                int indexCount = rs.getInt("index_count");

                NullStats stats = nullStats.getOrDefault(tableName, new NullStats());

                DataQualityScore score = calculateQualityScore(
                    tableName, rowCount, columnCount, hasPk, fkCount,
                    checkCount, uniqueCount, indexCount, stats);
                results.put(tableName, score);
            }
        } catch (Exception e) {
            log.error("Error querying PostgreSQL quality data: {}", e.getMessage(), e);
        }

        return results;
    }

    private Map<String, NullStats> getNullStatsPostgres(Connection conn) {
        Map<String, NullStats> results = new HashMap<>();

        String query = """
            SELECT
                tablename as table_name,
                attname as column_name,
                null_frac
            FROM pg_stats
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                double nullFrac = rs.getDouble("null_frac");

                NullStats stats = results.computeIfAbsent(tableName, k -> new NullStats());
                stats.addColumn(nullFrac);
            }
        } catch (Exception e) {
            log.debug("Error getting null stats: {}", e.getMessage());
        }

        return results;
    }

    private Map<String, DataQualityScore> calculateMysqlQuality(Connection conn) {
        Map<String, DataQualityScore> results = new HashMap<>();

        String tableQuery = """
            SELECT
                t.TABLE_NAME as table_name,
                t.TABLE_ROWS as row_count,
                (SELECT COUNT(*) FROM information_schema.COLUMNS c
                 WHERE c.TABLE_SCHEMA = t.TABLE_SCHEMA AND c.TABLE_NAME = t.TABLE_NAME) as column_count,
                (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
                 WHERE tc.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc.TABLE_NAME = t.TABLE_NAME
                 AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY') as has_pk,
                (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
                 WHERE tc.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc.TABLE_NAME = t.TABLE_NAME
                 AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY') as fk_count,
                (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
                 WHERE tc.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc.TABLE_NAME = t.TABLE_NAME
                 AND tc.CONSTRAINT_TYPE = 'CHECK') as check_count,
                (SELECT COUNT(*) FROM information_schema.TABLE_CONSTRAINTS tc
                 WHERE tc.TABLE_SCHEMA = t.TABLE_SCHEMA AND tc.TABLE_NAME = t.TABLE_NAME
                 AND tc.CONSTRAINT_TYPE = 'UNIQUE') as unique_count,
                (SELECT COUNT(DISTINCT INDEX_NAME) FROM information_schema.STATISTICS s
                 WHERE s.TABLE_SCHEMA = t.TABLE_SCHEMA AND s.TABLE_NAME = t.TABLE_NAME) as index_count
            FROM information_schema.TABLES t
            WHERE t.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            AND t.TABLE_TYPE = 'BASE TABLE'
            """;

        // Get null statistics
        Map<String, NullStats> nullStats = getNullStatsMysql(conn);

        try (PreparedStatement stmt = conn.prepareStatement(tableQuery);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                long rowCount = rs.getLong("row_count");
                int columnCount = rs.getInt("column_count");
                boolean hasPk = rs.getInt("has_pk") > 0;
                int fkCount = rs.getInt("fk_count");
                int checkCount = rs.getInt("check_count");
                int uniqueCount = rs.getInt("unique_count");
                int indexCount = rs.getInt("index_count");

                NullStats stats = nullStats.getOrDefault(tableName, new NullStats());

                DataQualityScore score = calculateQualityScore(
                    tableName, rowCount, columnCount, hasPk, fkCount,
                    checkCount, uniqueCount, indexCount, stats);
                results.put(tableName, score);
            }
        } catch (Exception e) {
            log.error("Error querying MySQL quality data: {}", e.getMessage(), e);
        }

        return results;
    }

    private Map<String, NullStats> getNullStatsMysql(Connection conn) {
        Map<String, NullStats> results = new HashMap<>();

        String query = """
            SELECT
                TABLE_NAME as table_name,
                COLUMN_NAME as column_name,
                IS_NULLABLE
            FROM information_schema.COLUMNS
            WHERE TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                boolean isNullable = "YES".equals(rs.getString("IS_NULLABLE"));

                NullStats stats = results.computeIfAbsent(tableName, k -> new NullStats());
                // For MySQL, we estimate based on nullability definition
                stats.addColumn(isNullable ? 0.1 : 0.0);
            }
        } catch (Exception e) {
            log.debug("Error getting null stats: {}", e.getMessage());
        }

        return results;
    }

    private DataQualityScore calculateQualityScore(String tableName, long rowCount, int columnCount,
                                                     boolean hasPk, int fkCount, int checkCount,
                                                     int uniqueCount, int indexCount, NullStats nullStats) {
        Map<String, Double> dimensions = new LinkedHashMap<>();
        List<String> issues = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();

        // 1. Completeness Score (based on null ratios)
        double avgNullRatio = nullStats.getAverageNullRatio();
        double completenessScore = Math.max(0, 100 - (avgNullRatio * 100));
        dimensions.put("completeness", round(completenessScore));
        if (avgNullRatio > 0.3) {
            issues.add(String.format("High null ratio: %.1f%% average across columns", avgNullRatio * 100));
            recommendations.add("Review columns with high null rates for data collection issues");
        }

        // 2. Validity Score (constraint coverage)
        double validityScore = 0;
        if (hasPk) validityScore += 40;
        else {
            issues.add("Missing primary key");
            recommendations.add("Add primary key for entity identification");
        }
        if (fkCount > 0) validityScore += 20;
        if (checkCount > 0) validityScore += 20;
        if (uniqueCount > 0) validityScore += 10;
        validityScore += Math.min(10, indexCount * 2);
        dimensions.put("validity", round(validityScore));

        // 3. Uniqueness Score (based on constraints)
        double uniquenessScore = 50; // Base score
        if (hasPk) uniquenessScore += 30;
        uniquenessScore += Math.min(20, uniqueCount * 10);
        dimensions.put("uniqueness", round(uniquenessScore));

        // 4. Consistency Score (based on FK relationships)
        double consistencyScore = 50; // Base score
        if (fkCount > 0) {
            consistencyScore += Math.min(40, fkCount * 10);
        }
        if (checkCount > 0) {
            consistencyScore += Math.min(10, checkCount * 5);
        }
        consistencyScore = Math.min(100, consistencyScore);
        dimensions.put("consistency", round(consistencyScore));

        // 5. Timeliness Score (estimated - without timestamp column analysis)
        double timelinessScore = rowCount > 0 ? 70 : 50; // Base estimate
        dimensions.put("timeliness", round(timelinessScore));

        // Calculate overall score (weighted average)
        double overallScore = (completenessScore * 0.25) + (validityScore * 0.25) +
            (uniquenessScore * 0.20) + (consistencyScore * 0.15) + (timelinessScore * 0.15);

        // Determine quality level
        String qualityLevel;
        if (overallScore >= 80) {
            qualityLevel = "EXCELLENT";
        } else if (overallScore >= 60) {
            qualityLevel = "GOOD";
        } else if (overallScore >= 40) {
            qualityLevel = "FAIR";
            recommendations.add("Consider adding constraints to improve data integrity");
        } else {
            qualityLevel = "POOR";
            recommendations.add("Urgent: Add primary key and review data collection processes");
            recommendations.add("Consider data cleansing initiatives");
        }

        // Count high-null columns
        int highNullColumns = nullStats.getHighNullColumnCount();
        if (highNullColumns > 0) {
            issues.add(String.format("%d columns have >50%% null values", highNullColumns));
        }

        String reasoning = String.format(
            "%,d rows, %d columns, PK: %s, %d FKs, %d CHECK constraints",
            rowCount, columnCount, hasPk ? "Yes" : "No", fkCount, checkCount);

        return DataQualityScore.builder()
            .tableName(tableName)
            .qualityLevel(qualityLevel)
            .overallScore(BigDecimal.valueOf(overallScore).setScale(2, RoundingMode.HALF_UP))
            .dimensions(dimensions)
            .completenessScore(BigDecimal.valueOf(completenessScore).setScale(2, RoundingMode.HALF_UP))
            .validityScore(BigDecimal.valueOf(validityScore).setScale(2, RoundingMode.HALF_UP))
            .uniquenessScore(BigDecimal.valueOf(uniquenessScore).setScale(2, RoundingMode.HALF_UP))
            .consistencyScore(BigDecimal.valueOf(consistencyScore).setScale(2, RoundingMode.HALF_UP))
            .timelinessScore(BigDecimal.valueOf(timelinessScore).setScale(2, RoundingMode.HALF_UP))
            .rowCount(rowCount)
            .columnCount(columnCount)
            .constraintCount(fkCount + checkCount + uniqueCount + (hasPk ? 1 : 0))
            .nullableColumnRatio(BigDecimal.valueOf(avgNullRatio).setScale(4, RoundingMode.HALF_UP))
            .issues(issues)
            .recommendations(recommendations)
            .reasoning(reasoning)
            .build();
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    private static class NullStats {
        private double totalNullRatio = 0;
        private int columnCount = 0;
        private int highNullColumns = 0;

        void addColumn(double nullRatio) {
            totalNullRatio += nullRatio;
            columnCount++;
            if (nullRatio > 0.5) highNullColumns++;
        }

        double getAverageNullRatio() {
            return columnCount > 0 ? totalNullRatio / columnCount : 0;
        }

        int getHighNullColumnCount() {
            return highNullColumns;
        }
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataQualityScore {
        private String tableName;
        private String qualityLevel;  // EXCELLENT, GOOD, FAIR, POOR
        private BigDecimal overallScore;
        private Map<String, Double> dimensions;
        private BigDecimal completenessScore;
        private BigDecimal validityScore;
        private BigDecimal uniquenessScore;
        private BigDecimal consistencyScore;
        private BigDecimal timelinessScore;
        private Long rowCount;
        private Integer columnCount;
        private Integer constraintCount;
        private BigDecimal nullableColumnRatio;
        private List<String> issues;
        private List<String> recommendations;
        private String reasoning;
    }
}
