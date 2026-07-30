package com.dbaagent.service.brain.classification;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.brain.classification.AccessPatternClassificationService.TableAccessPattern;
import com.dbaagent.service.brain.classification.AntiPatternDetectionService.TableAntiPatterns;
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
 * Service for calculating table health scores.
 *
 * Table Health Score (0-100) combines:
 * - Index Efficiency (20%): Proper indexes for access patterns
 * - Data Quality (20%): Low NULL rate, referential integrity
 * - Schema Design (20%): Proper normalization, no anti-patterns
 * - Access Efficiency (20%): Low seq_scan ratio, cache hit rate
 * - Maintenance Health (20%): Low bloat, recent VACUUM/ANALYZE
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class TableHealthScoreService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final AccessPatternClassificationService accessPatternService;
    private final AntiPatternDetectionService antiPatternService;
    private final DatabaseProviderRegistry providerRegistry;

    // Weight constants
    private static final double INDEX_EFFICIENCY_WEIGHT = 0.20;
    private static final double DATA_QUALITY_WEIGHT = 0.20;
    private static final double SCHEMA_DESIGN_WEIGHT = 0.20;
    private static final double ACCESS_EFFICIENCY_WEIGHT = 0.20;
    private static final double MAINTENANCE_HEALTH_WEIGHT = 0.20;

    /**
     * Calculate health scores for all tables in a connection.
     */
    public Map<String, TableHealthScore> calculateHealthScores(String connectionId) {
        Map<String, TableHealthScore> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            // Get access patterns and anti-patterns (already computed by other services)
            Map<String, TableAccessPattern> accessPatterns = accessPatternService.classifyAccessPatterns(connectionId);
            Map<String, TableAntiPatterns> antiPatterns = antiPatternService.detectAntiPatterns(connectionId);

            // Get index efficiency metrics
            Map<String, IndexEfficiencyMetrics> indexMetrics = getIndexEfficiencyMetrics(conn, dbType);

            // Get data quality metrics
            Map<String, DataQualityMetrics> dataQualityMetrics = getDataQualityMetrics(conn, dbType);

            // Get access efficiency metrics
            Map<String, AccessEfficiencyMetrics> accessMetrics = getAccessEfficiencyMetrics(conn, dbType);

            // Get maintenance health metrics
            Map<String, MaintenanceHealthMetrics> maintenanceMetrics = getMaintenanceHealthMetrics(conn, dbType);

            // Get all table names
            Set<String> allTables = new HashSet<>();
            allTables.addAll(accessPatterns.keySet());
            allTables.addAll(antiPatterns.keySet());
            allTables.addAll(indexMetrics.keySet());

            // Calculate health score for each table
            for (String tableName : allTables) {
                TableAccessPattern accessPattern = accessPatterns.get(tableName);
                TableAntiPatterns tableAntiPatterns = antiPatterns.get(tableName);
                IndexEfficiencyMetrics indexMetric = indexMetrics.get(tableName);
                DataQualityMetrics dataQuality = dataQualityMetrics.get(tableName);
                AccessEfficiencyMetrics accessMetric = accessMetrics.get(tableName);
                MaintenanceHealthMetrics maintenanceMetric = maintenanceMetrics.get(tableName);

                TableHealthScore healthScore = calculateTableHealthScore(
                    tableName, accessPattern, tableAntiPatterns, indexMetric,
                    dataQuality, accessMetric, maintenanceMetric);

                results.put(tableName, healthScore);
            }

        } catch (Exception e) {
            log.error("Error calculating health scores for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    /**
     * Calculate health score for a single table.
     */
    private TableHealthScore calculateTableHealthScore(
            String tableName,
            TableAccessPattern accessPattern,
            TableAntiPatterns antiPatterns,
            IndexEfficiencyMetrics indexMetrics,
            DataQualityMetrics dataQuality,
            AccessEfficiencyMetrics accessMetrics,
            MaintenanceHealthMetrics maintenanceMetrics) {

        Map<String, Object> breakdown = new HashMap<>();

        // 1. Index Efficiency Score (0-100)
        double indexScore = calculateIndexEfficiencyScore(indexMetrics, accessPattern);
        breakdown.put("index_efficiency", Map.of(
            "score", indexScore,
            "weight", INDEX_EFFICIENCY_WEIGHT * 100,
            "details", indexMetrics != null ? Map.of(
                "index_count", indexMetrics.getIndexCount(),
                "unused_indexes", indexMetrics.getUnusedIndexCount(),
                "missing_indexes_estimated", indexMetrics.getMissingIndexesEstimate()
            ) : Map.of()
        ));

        // 2. Data Quality Score (0-100)
        double dataQualityScore = calculateDataQualityScore(dataQuality);
        breakdown.put("data_quality", Map.of(
            "score", dataQualityScore,
            "weight", DATA_QUALITY_WEIGHT * 100,
            "details", dataQuality != null ? Map.of(
                "avg_null_ratio", dataQuality.getAvgNullRatio(),
                "has_primary_key", dataQuality.isHasPrimaryKey(),
                "orphan_fk_count", dataQuality.getOrphanFkCount()
            ) : Map.of()
        ));

        // 3. Schema Design Score (0-100)
        double schemaDesignScore = calculateSchemaDesignScore(antiPatterns);
        breakdown.put("schema_design", Map.of(
            "score", schemaDesignScore,
            "weight", SCHEMA_DESIGN_WEIGHT * 100,
            "details", antiPatterns != null ? Map.of(
                "anti_pattern_count", antiPatterns.getAntiPatternCount(),
                "overall_severity", antiPatterns.getOverallSeverity()
            ) : Map.of()
        ));

        // 4. Access Efficiency Score (0-100)
        double accessEfficiencyScore = calculateAccessEfficiencyScore(accessMetrics);
        breakdown.put("access_efficiency", Map.of(
            "score", accessEfficiencyScore,
            "weight", ACCESS_EFFICIENCY_WEIGHT * 100,
            "details", accessMetrics != null ? Map.of(
                "seq_scan_ratio", accessMetrics.getSeqScanRatio(),
                "cache_hit_ratio", accessMetrics.getCacheHitRatio()
            ) : Map.of()
        ));

        // 5. Maintenance Health Score (0-100)
        double maintenanceScore = calculateMaintenanceHealthScore(maintenanceMetrics);
        breakdown.put("maintenance_health", Map.of(
            "score", maintenanceScore,
            "weight", MAINTENANCE_HEALTH_WEIGHT * 100,
            "details", maintenanceMetrics != null ? Map.of(
                "dead_tuple_ratio", maintenanceMetrics.getDeadTupleRatio(),
                "days_since_vacuum", maintenanceMetrics.getDaysSinceVacuum(),
                "days_since_analyze", maintenanceMetrics.getDaysSinceAnalyze()
            ) : Map.of()
        ));

        // Calculate weighted overall score
        double overallScore = (indexScore * INDEX_EFFICIENCY_WEIGHT) +
                              (dataQualityScore * DATA_QUALITY_WEIGHT) +
                              (schemaDesignScore * SCHEMA_DESIGN_WEIGHT) +
                              (accessEfficiencyScore * ACCESS_EFFICIENCY_WEIGHT) +
                              (maintenanceScore * MAINTENANCE_HEALTH_WEIGHT);

        // Determine health status
        String healthStatus;
        if (overallScore >= 80) healthStatus = "HEALTHY";
        else if (overallScore >= 60) healthStatus = "WARNING";
        else if (overallScore >= 40) healthStatus = "DEGRADED";
        else healthStatus = "CRITICAL";

        // Generate recommendations
        List<String> recommendations = generateRecommendations(
            indexScore, dataQualityScore, schemaDesignScore, accessEfficiencyScore, maintenanceScore,
            indexMetrics, antiPatterns, accessMetrics, maintenanceMetrics);

        return TableHealthScore.builder()
            .tableName(tableName)
            .healthScore(BigDecimal.valueOf(overallScore).setScale(2, RoundingMode.HALF_UP))
            .healthStatus(healthStatus)
            .breakdown(breakdown)
            .recommendations(recommendations)
            .build();
    }

    private double calculateIndexEfficiencyScore(IndexEfficiencyMetrics metrics, TableAccessPattern accessPattern) {
        if (metrics == null) return 75.0; // Default score if no metrics

        double score = 100.0;

        // Penalize unused indexes
        if (metrics.getUnusedIndexCount() > 0) {
            score -= metrics.getUnusedIndexCount() * 5;
        }

        // Penalize if high read activity but low index usage (suggests missing indexes)
        if (accessPattern != null && "READ_HEAVY".equals(accessPattern.getAccessPattern())) {
            if (metrics.getIndexCount() <= 1) {
                score -= 20;
            }
        }

        // Penalize estimated missing indexes
        score -= metrics.getMissingIndexesEstimate() * 10;

        return Math.max(0, Math.min(100, score));
    }

    private double calculateDataQualityScore(DataQualityMetrics metrics) {
        if (metrics == null) return 75.0;

        double score = 100.0;

        // Penalize high NULL ratio
        if (metrics.getAvgNullRatio() > 0.3) {
            score -= (metrics.getAvgNullRatio() - 0.3) * 50;
        }

        // Penalize missing primary key
        if (!metrics.isHasPrimaryKey()) {
            score -= 20;
        }

        // Penalize orphan FKs (referential integrity issues)
        if (metrics.getOrphanFkCount() > 0) {
            score -= Math.min(30, metrics.getOrphanFkCount() * 5);
        }

        return Math.max(0, Math.min(100, score));
    }

    private double calculateSchemaDesignScore(TableAntiPatterns antiPatterns) {
        if (antiPatterns == null || antiPatterns.getAntiPatternCount() == 0) {
            return 100.0;
        }

        double score = 100.0;

        // Severity-based penalties
        String severity = antiPatterns.getOverallSeverity();
        switch (severity) {
            case "CRITICAL" -> score -= 40;
            case "HIGH" -> score -= 25;
            case "MEDIUM" -> score -= 15;
            case "LOW" -> score -= 5;
        }

        // Additional penalty per anti-pattern
        score -= antiPatterns.getAntiPatternCount() * 5;

        return Math.max(0, Math.min(100, score));
    }

    private double calculateAccessEfficiencyScore(AccessEfficiencyMetrics metrics) {
        if (metrics == null) return 75.0;

        double score = 100.0;

        // Penalize high sequential scan ratio
        if (metrics.getSeqScanRatio() > 0.5) {
            score -= (metrics.getSeqScanRatio() - 0.5) * 60;
        }

        // Reward high cache hit ratio
        if (metrics.getCacheHitRatio() < 0.9) {
            score -= (0.9 - metrics.getCacheHitRatio()) * 50;
        }

        return Math.max(0, Math.min(100, score));
    }

    private double calculateMaintenanceHealthScore(MaintenanceHealthMetrics metrics) {
        if (metrics == null) return 75.0;

        double score = 100.0;

        // Penalize high dead tuple ratio (bloat)
        if (metrics.getDeadTupleRatio() > 0.1) {
            score -= (metrics.getDeadTupleRatio() - 0.1) * 100;
        }

        // Penalize tables not vacuumed recently
        if (metrics.getDaysSinceVacuum() > 7) {
            score -= Math.min(20, (metrics.getDaysSinceVacuum() - 7) * 2);
        }

        // Penalize tables not analyzed recently
        if (metrics.getDaysSinceAnalyze() > 7) {
            score -= Math.min(15, (metrics.getDaysSinceAnalyze() - 7) * 1.5);
        }

        return Math.max(0, Math.min(100, score));
    }

    private List<String> generateRecommendations(
            double indexScore, double dataQualityScore, double schemaDesignScore,
            double accessEfficiencyScore, double maintenanceScore,
            IndexEfficiencyMetrics indexMetrics, TableAntiPatterns antiPatterns,
            AccessEfficiencyMetrics accessMetrics, MaintenanceHealthMetrics maintenanceMetrics) {

        List<String> recommendations = new ArrayList<>();

        if (indexScore < 70 && indexMetrics != null) {
            if (indexMetrics.getUnusedIndexCount() > 0) {
                recommendations.add("Remove " + indexMetrics.getUnusedIndexCount() + " unused indexes to improve write performance");
            }
            if (indexMetrics.getMissingIndexesEstimate() > 0) {
                recommendations.add("Add indexes on frequently queried columns (estimated " + indexMetrics.getMissingIndexesEstimate() + " missing)");
            }
        }

        if (dataQualityScore < 70) {
            recommendations.add("Review columns with high NULL ratios - consider defaults or schema changes");
        }

        if (schemaDesignScore < 70 && antiPatterns != null && antiPatterns.getAntiPatternCount() > 0) {
            recommendations.add("Address " + antiPatterns.getAntiPatternCount() + " detected anti-patterns (severity: " + antiPatterns.getOverallSeverity() + ")");
        }

        if (accessEfficiencyScore < 70 && accessMetrics != null) {
            if (accessMetrics.getSeqScanRatio() > 0.5) {
                recommendations.add("High sequential scan ratio (" + String.format("%.0f%%", accessMetrics.getSeqScanRatio() * 100) + ") - consider adding indexes");
            }
            if (accessMetrics.getCacheHitRatio() < 0.9) {
                recommendations.add("Low cache hit ratio (" + String.format("%.0f%%", accessMetrics.getCacheHitRatio() * 100) + ") - consider increasing shared_buffers");
            }
        }

        if (maintenanceScore < 70 && maintenanceMetrics != null) {
            if (maintenanceMetrics.getDeadTupleRatio() > 0.1) {
                recommendations.add("High dead tuple ratio (" + String.format("%.1f%%", maintenanceMetrics.getDeadTupleRatio() * 100) + ") - run VACUUM");
            }
            if (maintenanceMetrics.getDaysSinceVacuum() > 7) {
                recommendations.add("Table not vacuumed in " + maintenanceMetrics.getDaysSinceVacuum() + " days - run VACUUM ANALYZE");
            }
        }

        return recommendations;
    }

    // ==================== Metrics Gathering Methods ====================

    private Map<String, IndexEfficiencyMetrics> getIndexEfficiencyMetrics(Connection conn, String dbType) {
        Map<String, IndexEfficiencyMetrics> metrics = new HashMap<>();

        if (!"postgres".equals(dbType)) {
            return metrics; // Limited support for MySQL
        }

        String sql = """
            SELECT
                schemaname,
                relname as table_name,
                (SELECT COUNT(*) FROM pg_indexes pi WHERE pi.tablename = relname) as index_count,
                (SELECT COUNT(*) FROM pg_stat_user_indexes psi
                 WHERE psi.relname = s.relname AND psi.idx_scan = 0) as unused_index_count
            FROM pg_stat_user_tables s
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                metrics.put(tableName, IndexEfficiencyMetrics.builder()
                    .indexCount(rs.getInt("index_count"))
                    .unusedIndexCount(rs.getInt("unused_index_count"))
                    .missingIndexesEstimate(0) // Would need slow query log analysis
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting index efficiency metrics: {}", e.getMessage(), e);
        }

        return metrics;
    }

    private Map<String, DataQualityMetrics> getDataQualityMetrics(Connection conn, String dbType) {
        Map<String, DataQualityMetrics> metrics = new HashMap<>();

        if (!"postgres".equals(dbType)) {
            return metrics;
        }

        // Get tables with/without primary keys
        String sql = """
            SELECT
                t.relname as table_name,
                EXISTS (
                    SELECT 1 FROM pg_constraint c
                    WHERE c.conrelid = t.oid AND c.contype = 'p'
                ) as has_primary_key
            FROM pg_class t
            JOIN pg_namespace n ON n.oid = t.relnamespace
            WHERE t.relkind = 'r'
                AND n.nspname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                metrics.put(tableName, DataQualityMetrics.builder()
                    .hasPrimaryKey(rs.getBoolean("has_primary_key"))
                    .avgNullRatio(0.0) // Would need column-level analysis
                    .orphanFkCount(0)  // Would need FK validation query
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting data quality metrics: {}", e.getMessage(), e);
        }

        return metrics;
    }

    private Map<String, AccessEfficiencyMetrics> getAccessEfficiencyMetrics(Connection conn, String dbType) {
        Map<String, AccessEfficiencyMetrics> metrics = new HashMap<>();

        if (!"postgres".equals(dbType)) {
            return metrics;
        }

        String sql = """
            SELECT
                relname as table_name,
                CASE WHEN (seq_scan + idx_scan) > 0
                    THEN seq_scan::float / (seq_scan + idx_scan)
                    ELSE 0 END as seq_scan_ratio,
                CASE WHEN (heap_blks_hit + heap_blks_read) > 0
                    THEN heap_blks_hit::float / (heap_blks_hit + heap_blks_read)
                    ELSE 1 END as cache_hit_ratio
            FROM pg_stat_user_tables
            JOIN pg_statio_user_tables USING (relid)
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                metrics.put(tableName, AccessEfficiencyMetrics.builder()
                    .seqScanRatio(rs.getDouble("seq_scan_ratio"))
                    .cacheHitRatio(rs.getDouble("cache_hit_ratio"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting access efficiency metrics: {}", e.getMessage(), e);
        }

        return metrics;
    }

    private Map<String, MaintenanceHealthMetrics> getMaintenanceHealthMetrics(Connection conn, String dbType) {
        Map<String, MaintenanceHealthMetrics> metrics = new HashMap<>();

        if (!"postgres".equals(dbType)) {
            return metrics;
        }

        String sql = """
            SELECT
                relname as table_name,
                CASE WHEN n_live_tup > 0
                    THEN n_dead_tup::float / n_live_tup
                    ELSE 0 END as dead_tuple_ratio,
                COALESCE(EXTRACT(DAY FROM (NOW() - last_vacuum)), 999) as days_since_vacuum,
                COALESCE(EXTRACT(DAY FROM (NOW() - last_analyze)), 999) as days_since_analyze
            FROM pg_stat_user_tables
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {
            while (rs.next()) {
                String tableName = rs.getString("table_name").toLowerCase();
                metrics.put(tableName, MaintenanceHealthMetrics.builder()
                    .deadTupleRatio(rs.getDouble("dead_tuple_ratio"))
                    .daysSinceVacuum((int) rs.getDouble("days_since_vacuum"))
                    .daysSinceAnalyze((int) rs.getDouble("days_since_analyze"))
                    .build());
            }
        } catch (Exception e) {
            log.error("Error getting maintenance health metrics: {}", e.getMessage(), e);
        }

        return metrics;
    }

    // ==================== Data Classes ====================

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableHealthScore {
        private String tableName;
        private BigDecimal healthScore;
        private String healthStatus;  // HEALTHY, WARNING, DEGRADED, CRITICAL
        private Map<String, Object> breakdown;
        private List<String> recommendations;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class IndexEfficiencyMetrics {
        private int indexCount;
        private int unusedIndexCount;
        private int missingIndexesEstimate;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class DataQualityMetrics {
        private boolean hasPrimaryKey;
        private double avgNullRatio;
        private int orphanFkCount;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class AccessEfficiencyMetrics {
        private double seqScanRatio;
        private double cacheHitRatio;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    private static class MaintenanceHealthMetrics {
        private double deadTupleRatio;
        private int daysSinceVacuum;
        private int daysSinceAnalyze;
    }
}
