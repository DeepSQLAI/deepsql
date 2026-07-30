package com.dbaagent.service.brain.classification;

import com.dbaagent.model.ConnectionRequest;
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
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.HashMap;
import java.util.Map;

/**
 * Service for classifying table access patterns.
 * Analyzes read/write ratios to determine how tables are used.
 *
 * Access Patterns:
 * - READ_HEAVY: SELECT >> INSERT/UPDATE (ratio > 10:1)
 * - WRITE_HEAVY: INSERT/UPDATE >> SELECT (ratio < 0.1:1)
 * - APPEND_ONLY: High inserts, near-zero updates/deletes
 * - UPDATE_INTENSIVE: High update rate, low insert rate
 * - MIXED_WORKLOAD: Balanced read/write ratio (0.5:1 to 2:1)
 * - RARELY_ACCESSED: Low activity overall
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AccessPatternClassificationService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;

    // Thresholds for classification
    private static final double READ_HEAVY_THRESHOLD = 10.0;   // reads/writes > 10
    private static final double WRITE_HEAVY_THRESHOLD = 0.1;   // reads/writes < 0.1
    private static final double APPEND_ONLY_UPDATE_RATIO = 0.05; // updates/inserts < 5%
    private static final double UPDATE_INTENSIVE_RATIO = 2.0;    // updates/inserts > 2
    private static final long RARELY_ACCESSED_THRESHOLD = 100;   // total ops < 100

    /**
     * Classify access patterns for all tables in a connection.
     */
    public Map<String, TableAccessPattern> classifyAccessPatterns(String connectionId) {
        Map<String, TableAccessPattern> patterns = new HashMap<>();

        ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
        if (connRequest == null) {
            log.warn("Connection not found: {}", connectionId);
            return patterns;
        }

        String dbType = connRequest.getDbType();

        try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
            if ("postgresql".equalsIgnoreCase(dbType) || "postgres".equalsIgnoreCase(dbType)) {
                patterns = classifyPostgresAccessPatterns(conn);
            } else if ("mysql".equalsIgnoreCase(dbType)) {
                patterns = classifyMysqlAccessPatterns(conn);
            } else {
                log.warn("Unsupported database type for access pattern classification: {}", dbType);
            }
        } catch (Exception e) {
            log.error("Error classifying access patterns for connection {}: {}", connectionId, e.getMessage(), e);
        }

        return patterns;
    }

    /**
     * Classify access patterns for PostgreSQL using pg_stat_user_tables.
     */
    private Map<String, TableAccessPattern> classifyPostgresAccessPatterns(Connection conn) {
        Map<String, TableAccessPattern> patterns = new HashMap<>();

        String sql = """
            SELECT
                schemaname,
                relname as table_name,
                COALESCE(seq_scan, 0) + COALESCE(idx_scan, 0) as total_reads,
                COALESCE(n_tup_ins, 0) as inserts,
                COALESCE(n_tup_upd, 0) as updates,
                COALESCE(n_tup_del, 0) as deletes,
                COALESCE(n_live_tup, 0) as live_tuples,
                COALESCE(n_dead_tup, 0) as dead_tuples,
                COALESCE(last_vacuum, '1970-01-01'::timestamp) as last_vacuum,
                COALESCE(last_analyze, '1970-01-01'::timestamp) as last_analyze
            FROM pg_stat_user_tables
            WHERE schemaname NOT IN ('pg_catalog', 'information_schema', 'pg_toast')
            ORDER BY relname
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long reads = rs.getLong("total_reads");
                long inserts = rs.getLong("inserts");
                long updates = rs.getLong("updates");
                long deletes = rs.getLong("deletes");
                long liveTuples = rs.getLong("live_tuples");
                long deadTuples = rs.getLong("dead_tuples");

                TableAccessPattern pattern = classifyPattern(tableName, reads, inserts, updates, deletes, liveTuples, deadTuples);
                patterns.put(tableName.toLowerCase(), pattern);
            }
        } catch (Exception e) {
            log.error("Error querying PostgreSQL stats: {}", e.getMessage(), e);
        }

        return patterns;
    }

    /**
     * Classify access patterns for MySQL using information_schema and performance_schema.
     */
    private Map<String, TableAccessPattern> classifyMysqlAccessPatterns(Connection conn) {
        Map<String, TableAccessPattern> patterns = new HashMap<>();

        // Try performance_schema first (more accurate)
        String sql = """
            SELECT
                t.TABLE_NAME as table_name,
                COALESCE(s.ROWS_READ, 0) as total_reads,
                COALESCE(s.ROWS_INSERTED, 0) as inserts,
                COALESCE(s.ROWS_UPDATED, 0) as updates,
                COALESCE(s.ROWS_DELETED, 0) as deletes,
                COALESCE(t.TABLE_ROWS, 0) as live_tuples
            FROM information_schema.TABLES t
            LEFT JOIN performance_schema.table_io_waits_summary_by_table s
                ON t.TABLE_NAME = s.OBJECT_NAME
                AND t.TABLE_SCHEMA = s.OBJECT_SCHEMA
            WHERE t.TABLE_SCHEMA = DATABASE()
                AND t.TABLE_TYPE = 'BASE TABLE'
            ORDER BY t.TABLE_NAME
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long reads = rs.getLong("total_reads");
                long inserts = rs.getLong("inserts");
                long updates = rs.getLong("updates");
                long deletes = rs.getLong("deletes");
                long liveTuples = rs.getLong("live_tuples");

                TableAccessPattern pattern = classifyPattern(tableName, reads, inserts, updates, deletes, liveTuples, 0);
                patterns.put(tableName.toLowerCase(), pattern);
            }
        } catch (Exception e) {
            log.warn("Error querying MySQL performance_schema, falling back to basic stats: {}", e.getMessage());
            // Fallback: Use basic information_schema if performance_schema is not available
            patterns = classifyMysqlBasicPatterns(conn);
        }

        return patterns;
    }

    /**
     * Fallback MySQL classification using only information_schema.
     */
    private Map<String, TableAccessPattern> classifyMysqlBasicPatterns(Connection conn) {
        Map<String, TableAccessPattern> patterns = new HashMap<>();

        String sql = """
            SELECT
                TABLE_NAME as table_name,
                TABLE_ROWS as live_tuples,
                AUTO_INCREMENT,
                UPDATE_TIME
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = DATABASE()
                AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """;

        try (PreparedStatement stmt = conn.prepareStatement(sql);
             ResultSet rs = stmt.executeQuery()) {

            while (rs.next()) {
                String tableName = rs.getString("table_name");
                long liveTuples = rs.getLong("live_tuples");

                // Without performance_schema, we can only make rough estimates
                TableAccessPattern pattern = TableAccessPattern.builder()
                    .tableName(tableName)
                    .accessPattern("UNKNOWN")
                    .readCount(0L)
                    .writeCount(0L)
                    .updateCount(0L)
                    .deleteCount(0L)
                    .readWriteRatio(BigDecimal.ZERO)
                    .confidence(BigDecimal.valueOf(20)) // Low confidence without stats
                    .reasoning("MySQL performance_schema not available; limited statistics")
                    .build();

                patterns.put(tableName.toLowerCase(), pattern);
            }
        } catch (Exception e) {
            log.error("Error querying MySQL basic stats: {}", e.getMessage(), e);
        }

        return patterns;
    }

    /**
     * Classify a table's access pattern based on its statistics.
     */
    private TableAccessPattern classifyPattern(
            String tableName,
            long reads,
            long inserts,
            long updates,
            long deletes,
            long liveTuples,
            long deadTuples) {

        long totalWrites = inserts + updates + deletes;
        long totalOps = reads + totalWrites;

        String pattern;
        double confidence;
        String reasoning;

        // Calculate read/write ratio (avoid division by zero)
        BigDecimal readWriteRatio;
        if (totalWrites > 0) {
            readWriteRatio = BigDecimal.valueOf(reads)
                .divide(BigDecimal.valueOf(totalWrites), 4, RoundingMode.HALF_UP);
        } else if (reads > 0) {
            readWriteRatio = BigDecimal.valueOf(999.0); // Effectively infinite reads
        } else {
            readWriteRatio = BigDecimal.ZERO;
        }

        // Classification logic
        if (totalOps < RARELY_ACCESSED_THRESHOLD) {
            pattern = "RARELY_ACCESSED";
            confidence = 90.0;
            reasoning = String.format("Very low activity: %d total operations", totalOps);
        } else if (totalWrites == 0 && reads > 0) {
            pattern = "READ_HEAVY";
            confidence = 95.0;
            reasoning = String.format("Read-only table: %,d reads, 0 writes", reads);
        } else if (readWriteRatio.doubleValue() > READ_HEAVY_THRESHOLD) {
            pattern = "READ_HEAVY";
            confidence = 85.0 + Math.min(10, readWriteRatio.doubleValue() / 10);
            reasoning = String.format("High read ratio: %.2f:1 (reads=%,d, writes=%,d)",
                readWriteRatio.doubleValue(), reads, totalWrites);
        } else if (readWriteRatio.doubleValue() < WRITE_HEAVY_THRESHOLD && totalWrites > 0) {
            pattern = "WRITE_HEAVY";
            confidence = 85.0;
            reasoning = String.format("High write ratio: %.4f:1 (reads=%,d, writes=%,d)",
                readWriteRatio.doubleValue(), reads, totalWrites);
        } else if (inserts > 0 && updates > 0) {
            double updateInsertRatio = (double) updates / inserts;
            if (updateInsertRatio < APPEND_ONLY_UPDATE_RATIO && deletes < inserts * 0.01) {
                pattern = "APPEND_ONLY";
                confidence = 80.0;
                reasoning = String.format("Append-only: %,d inserts, %,d updates (%.2f%%), %,d deletes",
                    inserts, updates, updateInsertRatio * 100, deletes);
            } else if (updateInsertRatio > UPDATE_INTENSIVE_RATIO) {
                pattern = "UPDATE_INTENSIVE";
                confidence = 80.0;
                reasoning = String.format("Update-intensive: %,d updates vs %,d inserts (%.1fx)",
                    updates, inserts, updateInsertRatio);
            } else {
                pattern = "MIXED_WORKLOAD";
                confidence = 75.0;
                reasoning = String.format("Mixed workload: read/write=%.2f:1, update/insert=%.2f:1",
                    readWriteRatio.doubleValue(), updateInsertRatio);
            }
        } else if (inserts > 0 && updates == 0 && deletes < inserts * 0.01) {
            pattern = "APPEND_ONLY";
            confidence = 90.0;
            reasoning = String.format("Pure append-only: %,d inserts, 0 updates, %,d deletes", inserts, deletes);
        } else {
            pattern = "MIXED_WORKLOAD";
            confidence = 70.0;
            reasoning = String.format("Default mixed: reads=%,d, inserts=%,d, updates=%,d, deletes=%,d",
                reads, inserts, updates, deletes);
        }

        // Adjust confidence based on data volume
        if (totalOps > 10000) {
            confidence = Math.min(100, confidence + 5);
        }

        return TableAccessPattern.builder()
            .tableName(tableName)
            .accessPattern(pattern)
            .readCount(reads)
            .writeCount(inserts)
            .updateCount(updates)
            .deleteCount(deletes)
            .totalOperations(totalOps)
            .readWriteRatio(readWriteRatio)
            .liveTuples(liveTuples)
            .deadTuples(deadTuples)
            .confidence(BigDecimal.valueOf(confidence).setScale(2, RoundingMode.HALF_UP))
            .reasoning(reasoning)
            .build();
    }

    /**
     * Get access pattern for a specific table.
     */
    public TableAccessPattern getTableAccessPattern(String connectionId, String tableName) {
        Map<String, TableAccessPattern> patterns = classifyAccessPatterns(connectionId);
        return patterns.get(tableName.toLowerCase());
    }

    /**
     * Data class for table access pattern classification results.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class TableAccessPattern {
        private String tableName;
        private String accessPattern;  // READ_HEAVY, WRITE_HEAVY, APPEND_ONLY, UPDATE_INTENSIVE, MIXED_WORKLOAD, RARELY_ACCESSED
        private Long readCount;
        private Long writeCount;       // inserts
        private Long updateCount;
        private Long deleteCount;
        private Long totalOperations;
        private BigDecimal readWriteRatio;
        private Long liveTuples;
        private Long deadTuples;
        private BigDecimal confidence;
        private String reasoning;
    }
}
