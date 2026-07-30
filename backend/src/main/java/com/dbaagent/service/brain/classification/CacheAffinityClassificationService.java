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
 * Service for classifying cache affinity of tables.
 *
 * Cache Affinity Levels:
 * - HIGH_CACHE_VALUE: Frequently read, rarely updated, small-medium size
 * - MEDIUM_CACHE_VALUE: Moderate read/write ratio, acceptable size
 * - LOW_CACHE_VALUE: Write-heavy or large tables
 * - NO_CACHE_BENEFIT: Very large, frequently updated, or rarely accessed
 *
 * Factors considered:
 * - Read/write ratio (higher read = better cache candidate)
 * - Table size (smaller = easier to cache)
 * - Update frequency (less updates = better cache hit rate)
 * - Access pattern (frequent access = higher cache value)
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CacheAffinityClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Thresholds
    private static final long MAX_CACHEABLE_ROWS = 1_000_000;  // 1M rows max
    private static final long IDEAL_CACHE_ROWS = 100_000;       // 100K rows ideal
    private static final double HIGH_READ_RATIO = 0.9;          // 90% reads
    private static final double MEDIUM_READ_RATIO = 0.7;        // 70% reads

    /**
     * Classify cache affinity for all tables in a connection.
     */
    public Map<String, CacheAffinity> classifyCacheAffinity(String connectionId) {
        Map<String, CacheAffinity> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                results = classifyPostgresCacheAffinity(conn);
            } else if ("mysql".equalsIgnoreCase(dbType)) {
                results = classifyMysqlCacheAffinity(conn);
            } else {
                log.warn("Unsupported database type for cache affinity: {}", dbType);
            }
        } catch (Exception e) {
            log.error("Error classifying cache affinity for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, CacheAffinity> classifyPostgresCacheAffinity(Connection conn) {
        Map<String, CacheAffinity> results = new HashMap<>();

        String query = """
            SELECT
                relname as table_name,
                COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0) as total_reads,
                COALESCE(n_tup_ins, 0) + COALESCE(n_tup_upd, 0) + COALESCE(n_tup_del, 0) as total_writes,
                COALESCE(n_live_tup, 0) as row_count,
                COALESCE(n_tup_upd, 0) as update_count,
                pg_table_size(schemaname || '.' || relname) as table_size_bytes
            FROM pg_stat_user_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long reads = rs.getLong("total_reads");
                long writes = rs.getLong("total_writes");
                long rowCount = rs.getLong("row_count");
                long updateCount = rs.getLong("update_count");
                long tableSizeBytes = rs.getLong("table_size_bytes");

                CacheAffinity affinity = calculateCacheAffinity(
                    tableName, reads, writes, rowCount, updateCount, tableSizeBytes);
                results.put(tableName.toLowerCase(), affinity);
            }
        } catch (Exception e) {
            log.error("Error querying PostgreSQL cache stats: {}", e.getMessage(), e);
        }

        return results;
    }

    private Map<String, CacheAffinity> classifyMysqlCacheAffinity(Connection conn) {
        Map<String, CacheAffinity> results = new HashMap<>();

        String query = """
            SELECT
                t.TABLE_NAME as table_name,
                t.TABLE_ROWS as row_count,
                t.DATA_LENGTH as table_size_bytes
            FROM information_schema.TABLES t
            WHERE t.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            AND t.TABLE_TYPE = 'BASE TABLE'
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long rowCount = rs.getLong("row_count");
                long tableSizeBytes = rs.getLong("table_size_bytes");

                // Without performance_schema, estimate based on size
                CacheAffinity affinity = calculateCacheAffinity(
                    tableName, 0, 0, rowCount, 0, tableSizeBytes);
                results.put(tableName.toLowerCase(), affinity);
            }
        } catch (Exception e) {
            log.error("Error querying MySQL cache stats: {}", e.getMessage(), e);
        }

        return results;
    }

    private CacheAffinity calculateCacheAffinity(String tableName, long reads, long writes,
                                                   long rowCount, long updates, long sizeBytes) {
        Map<String, Double> breakdown = new HashMap<>();

        // Calculate read frequency score (0-100)
        double readRatio = (reads + writes) > 0 ? (double) reads / (reads + writes) : 0.5;
        double readScore = Math.min(100, readRatio * 100);
        breakdown.put("read_frequency", round(readScore));

        // Calculate write/volatility score (0-100, inverted - less writes = higher score)
        double writeScore = 100 - Math.min(100, (writes / Math.max(1, reads)) * 100);
        breakdown.put("write_volatility", round(writeScore));

        // Calculate size factor (0-100, smaller = higher)
        double sizeFactor;
        if (rowCount <= IDEAL_CACHE_ROWS) {
            sizeFactor = 100;
        } else if (rowCount <= MAX_CACHEABLE_ROWS) {
            sizeFactor = 100 - (((double) rowCount - IDEAL_CACHE_ROWS) / (MAX_CACHEABLE_ROWS - IDEAL_CACHE_ROWS)) * 50;
        } else {
            sizeFactor = Math.max(0, 50 - ((double) (rowCount - MAX_CACHEABLE_ROWS) / MAX_CACHEABLE_ROWS) * 50);
        }
        breakdown.put("size_factor", round(sizeFactor));

        // Calculate update frequency score (0-100, less updates = higher)
        double updateRatio = rowCount > 0 ? (double) updates / rowCount : 0;
        double updateScore = Math.max(0, 100 - updateRatio * 1000);
        breakdown.put("update_frequency", round(updateScore));

        // Calculate overall cache affinity score
        double overallScore = (readScore * 0.35) + (writeScore * 0.25) + (sizeFactor * 0.25) + (updateScore * 0.15);

        // Determine cache affinity level
        String affinityLevel;
        String reasoning;
        List<String> recommendations = new ArrayList<>();

        if (overallScore >= 75 && rowCount <= MAX_CACHEABLE_ROWS) {
            affinityLevel = "HIGH_CACHE_VALUE";
            reasoning = String.format("Excellent cache candidate: %.0f%% reads, %,d rows, low volatility",
                readRatio * 100, rowCount);
            recommendations.add("Implement Redis/Memcached caching");
            recommendations.add("Consider application-level caching");
            recommendations.add("Use cache-aside pattern for lookups");
        } else if (overallScore >= 50 && rowCount <= MAX_CACHEABLE_ROWS * 2) {
            affinityLevel = "MEDIUM_CACHE_VALUE";
            reasoning = String.format("Moderate cache candidate: %.0f%% reads, %,d rows",
                readRatio * 100, rowCount);
            recommendations.add("Consider selective caching of hot keys");
            recommendations.add("Use short TTL due to update frequency");
        } else if (overallScore >= 25) {
            affinityLevel = "LOW_CACHE_VALUE";
            reasoning = String.format("Limited cache benefit: %.0f%% reads, %,d rows, high volatility",
                readRatio * 100, rowCount);
            recommendations.add("Cache only specific frequently-accessed records");
            recommendations.add("Focus on query optimization instead");
        } else {
            affinityLevel = "NO_CACHE_BENEFIT";
            reasoning = String.format("Not suitable for caching: %,d rows, write-heavy or rarely accessed", rowCount);
            recommendations.add("Focus on database-level optimization");
            recommendations.add("Consider partitioning for large tables");
        }

        long sizeMb = sizeBytes / (1024 * 1024);

        return CacheAffinity.builder()
            .tableName(tableName)
            .affinityLevel(affinityLevel)
            .affinityScore(BigDecimal.valueOf(overallScore).setScale(2, RoundingMode.HALF_UP))
            .breakdown(breakdown)
            .rowCount(rowCount)
            .tableSizeMb(sizeMb)
            .readRatio(BigDecimal.valueOf(readRatio).setScale(4, RoundingMode.HALF_UP))
            .reasoning(reasoning)
            .recommendations(recommendations)
            .estimatedCacheHitRate(estimateCacheHitRate(overallScore, readRatio))
            .build();
    }

    private double estimateCacheHitRate(double affinityScore, double readRatio) {
        // Estimate potential cache hit rate based on affinity and read pattern
        return Math.min(99, affinityScore * readRatio);
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(2, RoundingMode.HALF_UP).doubleValue();
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CacheAffinity {
        private String tableName;
        private String affinityLevel;  // HIGH_CACHE_VALUE, MEDIUM_CACHE_VALUE, LOW_CACHE_VALUE, NO_CACHE_BENEFIT
        private BigDecimal affinityScore;
        private Map<String, Double> breakdown;
        private Long rowCount;
        private Long tableSizeMb;
        private BigDecimal readRatio;
        private String reasoning;
        private List<String> recommendations;
        private Double estimatedCacheHitRate;
    }
}
