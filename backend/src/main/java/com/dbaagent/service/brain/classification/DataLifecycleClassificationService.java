package com.dbaagent.service.brain.classification;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
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
import java.time.LocalDateTime;
import java.util.*;

/**
 * Service for classifying data lifecycle (Hot/Warm/Cold/Frozen).
 *
 * Lifecycle Levels:
 * - HOT: Accessed in last 7 days, high frequency (>100 accesses/day)
 * - WARM: Accessed in last 30 days, moderate frequency (10-100 accesses/day)
 * - COLD: No access in 30+ days, low frequency (<10 accesses/day)
 * - FROZEN: No access in 90+ days, archive/deletion candidate
 *
 * Based on:
 * - Last access timestamp
 * - Access frequency (reads + writes per day)
 * - Data age distribution
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class DataLifecycleClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;

    // Thresholds
    private static final int HOT_DAYS_THRESHOLD = 7;
    private static final int WARM_DAYS_THRESHOLD = 30;
    private static final int COLD_DAYS_THRESHOLD = 90;
    private static final double HOT_FREQUENCY_THRESHOLD = 100.0;
    private static final double WARM_FREQUENCY_THRESHOLD = 10.0;

    /**
     * Classify data lifecycle for all tables in a connection.
     */
    public Map<String, DataLifecycle> classifyDataLifecycle(String connectionId) {
        Map<String, DataLifecycle> results = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return results;
        }

        String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgres".equals(dbType)) {
                results = classifyPostgresLifecycle(conn);
            } else if ("mysql".equals(dbType)) {
                results = classifyMysqlLifecycle(conn);
            } else {
                log.warn("Unsupported database type for lifecycle classification: {}", connRequest.getDbType());
            }
        } catch (Exception e) {
            log.error("Error classifying data lifecycle for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return results;
    }

    private Map<String, DataLifecycle> classifyPostgresLifecycle(Connection conn) {
        Map<String, DataLifecycle> results = new HashMap<>();

        String query = """
            SELECT
                relname as table_name,
                COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0) as total_reads,
                COALESCE(n_tup_ins, 0) + COALESCE(n_tup_upd, 0) + COALESCE(n_tup_del, 0) as total_writes,
                last_vacuum,
                last_autovacuum,
                last_analyze,
                last_autoanalyze,
                COALESCE(n_live_tup, 0) as row_count
            FROM pg_stat_user_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long totalReads = rs.getLong("total_reads");
                long totalWrites = rs.getLong("total_writes");
                long rowCount = rs.getLong("row_count");

                // Estimate last access from vacuum/analyze times
                LocalDateTime lastAccess = getLatestTimestamp(
                    rs.getTimestamp("last_vacuum"),
                    rs.getTimestamp("last_autovacuum"),
                    rs.getTimestamp("last_analyze"),
                    rs.getTimestamp("last_autoanalyze")
                );

                DataLifecycle lifecycle = classifyTable(tableName, totalReads, totalWrites, lastAccess, rowCount);
                results.put(tableName.toLowerCase(), lifecycle);
            }
        } catch (Exception e) {
            log.error("Error querying PostgreSQL lifecycle stats: {}", e.getMessage(), e);
        }

        return results;
    }

    private Map<String, DataLifecycle> classifyMysqlLifecycle(Connection conn) {
        Map<String, DataLifecycle> results = new HashMap<>();

        // Get table statistics from information_schema
        String query = """
            SELECT
                t.TABLE_NAME as table_name,
                t.TABLE_ROWS as row_count,
                t.UPDATE_TIME as last_update,
                t.CREATE_TIME as create_time
            FROM information_schema.TABLES t
            WHERE t.TABLE_SCHEMA NOT IN ('mysql', 'information_schema', 'performance_schema', 'sys')
            AND t.TABLE_TYPE = 'BASE TABLE'
            """;

        try (PreparedStatement stmt = conn.prepareStatement(query);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long rowCount = rs.getLong("row_count");
                LocalDateTime lastUpdate = rs.getTimestamp("last_update") != null
                    ? rs.getTimestamp("last_update").toLocalDateTime()
                    : null;

                // For MySQL, we estimate access based on update time and table size
                DataLifecycle lifecycle = classifyTable(tableName, 0, 0, lastUpdate, rowCount);
                results.put(tableName.toLowerCase(), lifecycle);
            }
        } catch (Exception e) {
            log.error("Error querying MySQL lifecycle stats: {}", e.getMessage(), e);
        }

        return results;
    }

    private DataLifecycle classifyTable(String tableName, long reads, long writes,
                                         LocalDateTime lastAccess, long rowCount) {
        LocalDateTime now = LocalDateTime.now();
        long daysSinceAccess = 0;
        double accessFrequency = 0;

        if (lastAccess != null) {
            daysSinceAccess = java.time.temporal.ChronoUnit.DAYS.between(lastAccess, now);
        } else {
            // If no access info, assume based on row count
            daysSinceAccess = rowCount > 0 ? 30 : 90;
        }

        // Calculate access frequency (operations per day, estimated over 30 days)
        accessFrequency = (reads + writes) / 30.0;

        // Determine lifecycle stage
        String lifecycleStage;
        String reasoning;
        List<String> recommendations = new ArrayList<>();

        if (daysSinceAccess <= HOT_DAYS_THRESHOLD && accessFrequency >= HOT_FREQUENCY_THRESHOLD) {
            lifecycleStage = "HOT";
            reasoning = String.format("Active table: accessed within %d days, %.1f ops/day",
                daysSinceAccess, accessFrequency);
            recommendations.add("Keep in primary storage tier");
            recommendations.add("Consider caching for frequently accessed data");
        } else if (daysSinceAccess <= WARM_DAYS_THRESHOLD || accessFrequency >= WARM_FREQUENCY_THRESHOLD) {
            lifecycleStage = "WARM";
            reasoning = String.format("Moderately active: %d days since access, %.1f ops/day",
                daysSinceAccess, accessFrequency);
            recommendations.add("Good candidate for read replica offloading");
            recommendations.add("Monitor for transition to cold");
        } else if (daysSinceAccess <= COLD_DAYS_THRESHOLD) {
            lifecycleStage = "COLD";
            reasoning = String.format("Low activity: %d days since access, %.1f ops/day",
                daysSinceAccess, accessFrequency);
            recommendations.add("Consider moving to cold storage tier");
            recommendations.add("Evaluate archival policies");
            recommendations.add("May benefit from compression");
        } else {
            lifecycleStage = "FROZEN";
            reasoning = String.format("Inactive: %d days since access, minimal activity", daysSinceAccess);
            recommendations.add("Strong candidate for archival");
            recommendations.add("Consider moving to archive storage");
            recommendations.add("Evaluate data retention policy - may be deletable");
        }

        return DataLifecycle.builder()
            .tableName(tableName)
            .lifecycleStage(lifecycleStage)
            .lastAccessAt(lastAccess)
            .daysSinceLastAccess((int) daysSinceAccess)
            .accessFrequencyPerDay(BigDecimal.valueOf(accessFrequency).setScale(2, RoundingMode.HALF_UP))
            .totalReads(reads)
            .totalWrites(writes)
            .rowCount(rowCount)
            .reasoning(reasoning)
            .recommendations(recommendations)
            .confidence(lastAccess != null ? 80.0 : 50.0)
            .build();
    }

    private LocalDateTime getLatestTimestamp(java.sql.Timestamp... timestamps) {
        LocalDateTime latest = null;
        for (java.sql.Timestamp ts : timestamps) {
            if (ts != null) {
                LocalDateTime ldt = ts.toLocalDateTime();
                if (latest == null || ldt.isAfter(latest)) {
                    latest = ldt;
                }
            }
        }
        return latest;
    }

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class DataLifecycle {
        private String tableName;
        private String lifecycleStage;  // HOT, WARM, COLD, FROZEN
        private LocalDateTime lastAccessAt;
        private Integer daysSinceLastAccess;
        private BigDecimal accessFrequencyPerDay;
        private Long totalReads;
        private Long totalWrites;
        private Long rowCount;
        private String reasoning;
        private List<String> recommendations;
        private Double confidence;
    }
}
