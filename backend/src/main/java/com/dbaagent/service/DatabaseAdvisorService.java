package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Database Advisor Service - Provides DBA-level analysis and recommendations
 * Works with both MySQL and PostgreSQL (including Aurora, MariaDB aliases)
 */
@Service
@Slf4j
public class DatabaseAdvisorService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final ChatClient chatClient;
    private final DatabaseProviderRegistry providerRegistry;

    @Autowired
    public DatabaseAdvisorService(
            ConnectionService connectionService,
            CredentialService credentialService,
            ChatClient.Builder chatClientBuilder,
            DatabaseProviderRegistry providerRegistry) {
        this.connectionService = connectionService;
        this.credentialService = credentialService;
        this.chatClient = chatClientBuilder.build();
        this.providerRegistry = providerRegistry;
        log.info("DatabaseAdvisorService initialized with Spring AI ChatClient");
    }

    /**
     * Perform comprehensive performance analysis
     */
    public PerformanceAnalysis analyzePerformance(String connectionId) {
        log.info("Starting performance analysis for connection: {}", connectionId);

        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

            PerformanceAnalysis.PerformanceAnalysisBuilder analysis = PerformanceAnalysis.builder()
                .connectionId(connectionId)
                .dbType(dbType);

            // Detect missing indexes
            List<IndexRecommendation> indexRecommendations = detectMissingIndexes(connectionId, connRequest);
            analysis.indexRecommendations(indexRecommendations);

            // General recommendations
            List<DatabaseRecommendation> generalRecs = new ArrayList<>();

            if ("postgres".equals(dbType)) {
                generalRecs.addAll(analyzePostgresSpecific(connectionId, connRequest));
            } else if ("mysql".equals(dbType)) {
                generalRecs.addAll(analyzeMySQLSpecific(connectionId, connRequest));
            }

            analysis.generalRecommendations(generalRecs);

            // Analyze slow queries
            List<PerformanceAnalysis.SlowQueryAnalysis> slowQueries = analyzeSlowQueries(connectionId, connRequest);
            analysis.slowQueries(slowQueries);

            // Collect database metrics
            Map<String, Object> metrics = collectDatabaseMetrics(connectionId, connRequest);
            analysis.databaseMetrics(metrics);

            // Determine overall health
            PerformanceAnalysis.OverallHealth health = determineOverallHealth(
                indexRecommendations,
                generalRecs,
                slowQueries
            );
            analysis.overallHealth(health);

            PerformanceAnalysis result = analysis.build();

            // Generate AI summary
            String aiSummary = generateAISummary(result);
            result.setAiSummary(aiSummary);

            log.info("Performance analysis complete. Health: {}, Recommendations: {}",
                health, indexRecommendations.size() + generalRecs.size());

            return result;
        } catch (Exception e) {
            log.error("Error analyzing performance", e);
            throw new RuntimeException("Failed to analyze performance: " + e.getMessage(), e);
        }
    }

    /**
     * Detect missing indexes for both MySQL and PostgreSQL
     */
    public List<IndexRecommendation> detectMissingIndexes(String connectionId, ConnectionRequest connRequest) {
        String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

        if ("postgres".equals(dbType)) {
            return detectPostgresMissingIndexes(connectionId, connRequest);
        } else if ("mysql".equals(dbType)) {
            return detectMySQLMissingIndexes(connectionId, connRequest);
        }

        return new ArrayList<>();
    }

    /**
     * Detect missing indexes for MySQL
     */
    private List<IndexRecommendation> detectMySQLMissingIndexes(String connectionId, ConnectionRequest connRequest) {
        log.info("Detecting missing indexes for MySQL");
        List<IndexRecommendation> recommendations = new ArrayList<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            String database = connRequest.getDatabase();

            // Query 1: Tables with high sequential scans and no indexes
            String query1 = """
                SELECT
                    t.TABLE_NAME as table_name,
                    t.TABLE_ROWS as row_count,
                    COALESCE(s.INDEX_LENGTH, 0) as index_size,
                    t.DATA_LENGTH as table_size
                FROM information_schema.TABLES t
                LEFT JOIN (
                    SELECT TABLE_NAME, SUM(INDEX_LENGTH) as INDEX_LENGTH
                    FROM information_schema.TABLES
                    WHERE TABLE_SCHEMA = ?
                    GROUP BY TABLE_NAME
                ) s ON t.TABLE_NAME = s.TABLE_NAME
                WHERE t.TABLE_SCHEMA = ?
                  AND t.TABLE_TYPE = 'BASE TABLE'
                  AND t.TABLE_ROWS > 1000
                  AND (s.INDEX_LENGTH IS NULL OR s.INDEX_LENGTH = 0)
                ORDER BY t.TABLE_ROWS DESC
                LIMIT 10
                """;

            try (PreparedStatement stmt = connection.prepareStatement(query1)) {
                stmt.setString(1, database);
                stmt.setString(2, database);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        long rowCount = rs.getLong("row_count");
                        long tableSize = rs.getLong("table_size");

                        // Get columns that might benefit from indexing
                        List<String> candidateColumns = getMySQLCandidateColumns(
                            connection,
                            database,
                            tableName
                        );

                        if (!candidateColumns.isEmpty()) {
                            IndexRecommendation rec = IndexRecommendation.builder()
                                .id(UUID.randomUUID().toString())
                                .connectionId(connectionId)
                                .tableName(tableName)
                                .schemaName(database)
                                .columns(candidateColumns)
                                .indexType("BTREE")
                                .priority(rowCount > 100000 ?
                                    IndexRecommendation.RecommendationPriority.HIGH :
                                    IndexRecommendation.RecommendationPriority.MEDIUM)
                                .reasoning(String.format(
                                    "Table '%s' has %,d rows with no indexes. " +
                                    "Queries on this table will perform full table scans.",
                                    tableName, rowCount
                                ))
                                .suggestedSQL(String.format(
                                    "CREATE INDEX idx_%s_%s ON %s(%s)",
                                    tableName,
                                    String.join("_", candidateColumns),
                                    tableName,
                                    String.join(", ", candidateColumns)
                                ))
                                .metrics(IndexRecommendation.IndexRecommendationMetrics.builder()
                                    .sequentialScans(0L) // Not tracked in MySQL
                                    .rowsScanned(rowCount)
                                    .tableSizeBytes(tableSize)
                                    .estimatedImprovementPercent(70)
                                    .build())
                                .detectedAt(LocalDateTime.now())
                                .applied(false)
                                .build();

                            recommendations.add(rec);
                        }
                    }
                }
            }

            // Query 2: Foreign keys without indexes
            String query2 = """
                SELECT
                    kcu.TABLE_NAME as table_name,
                    kcu.COLUMN_NAME as column_name,
                    kcu.CONSTRAINT_NAME as fk_name,
                    kcu.REFERENCED_TABLE_NAME as ref_table
                FROM information_schema.KEY_COLUMN_USAGE kcu
                WHERE kcu.TABLE_SCHEMA = ?
                  AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
                  AND NOT EXISTS (
                      SELECT 1
                      FROM information_schema.STATISTICS s
                      WHERE s.TABLE_SCHEMA = kcu.TABLE_SCHEMA
                        AND s.TABLE_NAME = kcu.TABLE_NAME
                        AND s.COLUMN_NAME = kcu.COLUMN_NAME
                        AND s.SEQ_IN_INDEX = 1
                  )
                ORDER BY kcu.TABLE_NAME, kcu.COLUMN_NAME
                """;

            try (PreparedStatement stmt = connection.prepareStatement(query2)) {
                stmt.setString(1, database);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("table_name");
                        String columnName = rs.getString("column_name");
                        String refTable = rs.getString("ref_table");

                        IndexRecommendation rec = IndexRecommendation.builder()
                            .id(UUID.randomUUID().toString())
                            .connectionId(connectionId)
                            .tableName(tableName)
                            .schemaName(database)
                            .columns(Collections.singletonList(columnName))
                            .indexType("BTREE")
                            .priority(IndexRecommendation.RecommendationPriority.HIGH)
                            .reasoning(String.format(
                                "Foreign key column '%s' (references %s) has no index. " +
                                "This can cause slow JOIN operations and referential integrity checks.",
                                columnName, refTable
                            ))
                            .suggestedSQL(String.format(
                                "CREATE INDEX idx_%s_%s ON %s(%s)",
                                tableName, columnName, tableName, columnName
                            ))
                            .metrics(IndexRecommendation.IndexRecommendationMetrics.builder()
                                .estimatedImprovementPercent(60)
                                .build())
                            .detectedAt(LocalDateTime.now())
                            .applied(false)
                            .build();

                        recommendations.add(rec);
                    }
                }
            }

            log.info("Found {} index recommendations for MySQL", recommendations.size());
        } catch (Exception e) {
            log.error("Error detecting MySQL missing indexes", e);
        }

        return recommendations;
    }

    /**
     * Detect missing indexes for PostgreSQL
     */
    private List<IndexRecommendation> detectPostgresMissingIndexes(String connectionId, ConnectionRequest connRequest) {
        log.info("Detecting missing indexes for PostgreSQL");
        List<IndexRecommendation> recommendations = new ArrayList<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {

            // Query 1: Tables with high sequential scans
            String query1 = """
                SELECT
                    schemaname,
                    tablename,
                    seq_scan,
                    seq_tup_read,
                    idx_scan,
                    COALESCE(idx_tup_fetch, 0) as idx_tup_fetch,
                    n_live_tup,
                    CASE
                        WHEN seq_scan > 0 THEN seq_tup_read / seq_scan
                        ELSE 0
                    END as avg_seq_tup_read
                FROM pg_stat_user_tables
                WHERE schemaname = 'public'
                  AND seq_scan > 1000
                  AND n_live_tup > 10000
                  AND (idx_scan IS NULL OR seq_scan > idx_scan * 2)
                ORDER BY seq_scan DESC, n_live_tup DESC
                LIMIT 10
                """;

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(query1)) {

                while (rs.next()) {
                    String tableName = rs.getString("tablename");
                    long seqScans = rs.getLong("seq_scan");
                    long seqTupRead = rs.getLong("seq_tup_read");
                    long liveRows = rs.getLong("n_live_tup");
                    double avgSeqRead = rs.getDouble("avg_seq_tup_read");

                    // Get candidate columns
                    List<String> candidateColumns = getPostgresCandidateColumns(
                        connection,
                        tableName
                    );

                    if (!candidateColumns.isEmpty()) {
                        IndexRecommendation rec = IndexRecommendation.builder()
                            .id(UUID.randomUUID().toString())
                            .connectionId(connectionId)
                            .tableName(tableName)
                            .schemaName("public")
                            .columns(candidateColumns)
                            .indexType("BTREE")
                            .priority(seqScans > 10000 ?
                                IndexRecommendation.RecommendationPriority.CRITICAL :
                                IndexRecommendation.RecommendationPriority.HIGH)
                            .reasoning(String.format(
                                "Table '%s' has %,d sequential scans reading %,d rows (avg %.0f rows/scan). " +
                                "Current row count: %,d. An index would significantly improve query performance.",
                                tableName, seqScans, seqTupRead, avgSeqRead, liveRows
                            ))
                            .suggestedSQL(String.format(
                                "CREATE INDEX CONCURRENTLY idx_%s_%s ON %s(%s)",
                                tableName,
                                String.join("_", candidateColumns),
                                tableName,
                                String.join(", ", candidateColumns)
                            ))
                            .metrics(IndexRecommendation.IndexRecommendationMetrics.builder()
                                .sequentialScans(seqScans)
                                .rowsScanned(seqTupRead)
                                .averageScanTime(avgSeqRead * 0.001) // Rough estimate
                                .estimatedImprovementPercent(80)
                                .build())
                            .detectedAt(LocalDateTime.now())
                            .applied(false)
                            .build();

                        recommendations.add(rec);
                    }
                }
            }

            // Query 2: Foreign keys without indexes
            String query2 = """
                SELECT
                    tc.table_name,
                    kcu.column_name,
                    ccu.table_name AS foreign_table_name
                FROM information_schema.table_constraints AS tc
                JOIN information_schema.key_column_usage AS kcu
                  ON tc.constraint_name = kcu.constraint_name
                  AND tc.table_schema = kcu.table_schema
                JOIN information_schema.constraint_column_usage AS ccu
                  ON ccu.constraint_name = tc.constraint_name
                WHERE tc.constraint_type = 'FOREIGN KEY'
                  AND tc.table_schema = 'public'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM pg_indexes
                      WHERE schemaname = 'public'
                        AND tablename = tc.table_name
                        AND indexdef LIKE '%' || kcu.column_name || '%'
                  )
                """;

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(query2)) {

                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    String columnName = rs.getString("column_name");
                    String foreignTable = rs.getString("foreign_table_name");

                    IndexRecommendation rec = IndexRecommendation.builder()
                        .id(UUID.randomUUID().toString())
                        .connectionId(connectionId)
                        .tableName(tableName)
                        .schemaName("public")
                        .columns(Collections.singletonList(columnName))
                        .indexType("BTREE")
                        .priority(IndexRecommendation.RecommendationPriority.HIGH)
                        .reasoning(String.format(
                            "Foreign key column '%s' (references %s) lacks an index. " +
                            "This causes slow JOINs and UPDATE/DELETE cascades.",
                            columnName, foreignTable
                        ))
                        .suggestedSQL(String.format(
                            "CREATE INDEX CONCURRENTLY idx_%s_%s ON %s(%s)",
                            tableName, columnName, tableName, columnName
                        ))
                        .metrics(IndexRecommendation.IndexRecommendationMetrics.builder()
                            .estimatedImprovementPercent(70)
                            .build())
                        .detectedAt(LocalDateTime.now())
                        .applied(false)
                        .build();

                    recommendations.add(rec);
                }
            }

            log.info("Found {} index recommendations for PostgreSQL", recommendations.size());
        } catch (Exception e) {
            log.error("Error detecting PostgreSQL missing indexes", e);
        }

        return recommendations;
    }

    /**
     * Get candidate columns for indexing (MySQL)
     */
    private List<String> getMySQLCandidateColumns(Connection conn, String database, String tableName) {
        List<String> candidates = new ArrayList<>();

        try {
            // Get columns that are likely to be used in WHERE clauses
            String query = """
                SELECT COLUMN_NAME, DATA_TYPE, COLUMN_KEY
                FROM information_schema.COLUMNS
                WHERE TABLE_SCHEMA = ?
                  AND TABLE_NAME = ?
                  AND COLUMN_KEY = ''
                  AND DATA_TYPE IN ('int', 'bigint', 'varchar', 'datetime', 'date', 'timestamp')
                ORDER BY ORDINAL_POSITION
                LIMIT 3
                """;

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, database);
                stmt.setString(2, tableName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        candidates.add(rs.getString("COLUMN_NAME"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error getting MySQL candidate columns", e);
        }

        return candidates;
    }

    /**
     * Get candidate columns for indexing (PostgreSQL)
     */
    private List<String> getPostgresCandidateColumns(Connection conn, String tableName) {
        List<String> candidates = new ArrayList<>();

        try {
            // Get columns that are likely to be used in WHERE clauses
            String query = """
                SELECT column_name, data_type
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name NOT IN (
                      SELECT a.attname
                      FROM pg_index i
                      JOIN pg_attribute a ON a.attrelid = i.indrelid AND a.attnum = ANY(i.indkey)
                      WHERE i.indrelid = ?::regclass
                  )
                  AND data_type IN ('integer', 'bigint', 'character varying', 'timestamp without time zone', 'date')
                ORDER BY ordinal_position
                LIMIT 3
                """;

            try (PreparedStatement stmt = conn.prepareStatement(query)) {
                stmt.setString(1, tableName);
                stmt.setString(2, tableName);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        candidates.add(rs.getString("column_name"));
                    }
                }
            }
        } catch (Exception e) {
            log.warn("Error getting PostgreSQL candidate columns", e);
        }

        return candidates;
    }

    /**
     * Analyze PostgreSQL-specific issues
     */
    private List<DatabaseRecommendation> analyzePostgresSpecific(String connectionId, ConnectionRequest connRequest) {
        List<DatabaseRecommendation> recommendations = new ArrayList<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {

            // Check for tables needing VACUUM
            String vacuumQuery = """
                SELECT
                    schemaname,
                    tablename,
                    n_dead_tup,
                    n_live_tup,
                    ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup + n_dead_tup, 0), 2) as dead_ratio,
                    last_vacuum,
                    last_autovacuum
                FROM pg_stat_user_tables
                WHERE schemaname = 'public'
                  AND n_dead_tup > 1000
                  AND (n_dead_tup::float / NULLIF(n_live_tup + n_dead_tup, 0) > 0.1)
                ORDER BY n_dead_tup DESC
                LIMIT 5
                """;

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(vacuumQuery)) {

                while (rs.next()) {
                    String tableName = rs.getString("tablename");
                    long deadTuples = rs.getLong("n_dead_tup");
                    double deadRatio = rs.getDouble("dead_ratio");

                    DatabaseRecommendation rec = DatabaseRecommendation.builder()
                        .id(UUID.randomUUID().toString())
                        .connectionId(connectionId)
                        .type(DatabaseRecommendation.RecommendationType.VACUUM)
                        .title(String.format("Table '%s' needs VACUUM", tableName))
                        .description(String.format(
                            "Table has %,d dead tuples (%.1f%% dead ratio). " +
                            "This causes bloat and slower queries.",
                            deadTuples, deadRatio
                        ))
                        .reasoning("Dead tuples accumulate from UPDATEs and DELETEs. " +
                            "VACUUM reclaims this space and updates statistics.")
                        .suggestedAction("Run VACUUM ANALYZE or wait for autovacuum")
                        .suggestedSQL(String.format("VACUUM ANALYZE %s", tableName))
                        .priority(deadRatio > 20 ?
                            DatabaseRecommendation.RecommendationPriority.HIGH :
                            DatabaseRecommendation.RecommendationPriority.MEDIUM)
                        .impact(String.format("Expected to reclaim space and improve query performance by %.0f%%",
                            Math.min(deadRatio, 30)))
                        .riskLevel("LOW")
                        .detectedAt(LocalDateTime.now())
                        .applied(false)
                        .build();

                    recommendations.add(rec);
                }
            }

            // Check for outdated statistics
            String statsQuery = """
                SELECT
                    schemaname,
                    tablename,
                    last_analyze,
                    last_autoanalyze,
                    n_mod_since_analyze
                FROM pg_stat_user_tables
                WHERE schemaname = 'public'
                  AND n_mod_since_analyze > 1000
                  AND (last_analyze IS NULL OR last_analyze < NOW() - INTERVAL '7 days')
                ORDER BY n_mod_since_analyze DESC
                LIMIT 5
                """;

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(statsQuery)) {

                while (rs.next()) {
                    String tableName = rs.getString("tablename");
                    long modsSinceAnalyze = rs.getLong("n_mod_since_analyze");

                    DatabaseRecommendation rec = DatabaseRecommendation.builder()
                        .id(UUID.randomUUID().toString())
                        .connectionId(connectionId)
                        .type(DatabaseRecommendation.RecommendationType.STATISTICS)
                        .title(String.format("Table '%s' has outdated statistics", tableName))
                        .description(String.format(
                            "%,d rows modified since last ANALYZE. " +
                            "Query planner may make suboptimal decisions.",
                            modsSinceAnalyze
                        ))
                        .reasoning("PostgreSQL query planner relies on statistics to choose optimal execution plans. " +
                            "Outdated statistics lead to slow queries.")
                        .suggestedAction("Run ANALYZE to update statistics")
                        .suggestedSQL(String.format("ANALYZE %s", tableName))
                        .priority(DatabaseRecommendation.RecommendationPriority.MEDIUM)
                        .impact("Improved query plan selection, potentially 20-50% faster queries")
                        .riskLevel("VERY_LOW")
                        .detectedAt(LocalDateTime.now())
                        .applied(false)
                        .build();

                    recommendations.add(rec);
                }
            }

        } catch (Exception e) {
            log.error("Error analyzing PostgreSQL-specific issues", e);
        }

        return recommendations;
    }

    /**
     * Analyze MySQL-specific issues
     */
    private List<DatabaseRecommendation> analyzeMySQLSpecific(String connectionId, ConnectionRequest connRequest) {
        List<DatabaseRecommendation> recommendations = new ArrayList<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            String database = connRequest.getDatabase();

            // Check for tables without primary keys
            String noPkQuery = """
                SELECT TABLE_NAME
                FROM information_schema.TABLES t
                WHERE t.TABLE_SCHEMA = ?
                  AND t.TABLE_TYPE = 'BASE TABLE'
                  AND NOT EXISTS (
                      SELECT 1
                      FROM information_schema.TABLE_CONSTRAINTS tc
                      WHERE tc.TABLE_SCHEMA = t.TABLE_SCHEMA
                        AND tc.TABLE_NAME = t.TABLE_NAME
                        AND tc.CONSTRAINT_TYPE = 'PRIMARY KEY'
                  )
                LIMIT 10
                """;

            try (PreparedStatement stmt = connection.prepareStatement(noPkQuery)) {
                stmt.setString(1, database);

                try (ResultSet rs = stmt.executeQuery()) {
                    while (rs.next()) {
                        String tableName = rs.getString("TABLE_NAME");

                        DatabaseRecommendation rec = DatabaseRecommendation.builder()
                            .id(UUID.randomUUID().toString())
                            .connectionId(connectionId)
                            .type(DatabaseRecommendation.RecommendationType.INDEX)
                            .title(String.format("Table '%s' lacks primary key", tableName))
                            .description("Table has no primary key. This can cause replication issues and poor performance.")
                            .reasoning("Primary keys ensure row uniqueness, improve query performance, " +
                                "and are required for efficient replication.")
                            .suggestedAction("Add a primary key column (auto-increment ID or composite key)")
                            .suggestedSQL(String.format(
                                "ALTER TABLE %s ADD COLUMN id BIGINT AUTO_INCREMENT PRIMARY KEY FIRST",
                                tableName
                            ))
                            .priority(DatabaseRecommendation.RecommendationPriority.HIGH)
                            .impact("Essential for data integrity and replication")
                            .riskLevel("MEDIUM")
                            .detectedAt(LocalDateTime.now())
                            .applied(false)
                            .build();

                        recommendations.add(rec);
                    }
                }
            }

        } catch (Exception e) {
            log.error("Error analyzing MySQL-specific issues", e);
        }

        return recommendations;
    }

    /**
     * Analyze slow queries (simplified - would normally use slow query log)
     */
    private List<PerformanceAnalysis.SlowQueryAnalysis> analyzeSlowQueries(
        String connectionId,
        ConnectionRequest connRequest
    ) {
        // Placeholder - in production, parse slow query log
        return new ArrayList<>();
    }

    /**
     * Collect general database metrics
     */
    private Map<String, Object> collectDatabaseMetrics(String connectionId, ConnectionRequest connRequest) {
        Map<String, Object> metrics = new HashMap<>();

        try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
            String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

            if ("postgres".equals(dbType)) {
                // PostgreSQL metrics
                metrics.put("database_size", getDatabaseSize(connection, "postgres"));
                metrics.put("active_connections", getActiveConnections(connection, "postgres"));
            } else if ("mysql".equals(dbType)) {
                // MySQL metrics
                metrics.put("database_size", getDatabaseSize(connection, "mysql"));
                metrics.put("active_connections", getActiveConnections(connection, "mysql"));
            }
        } catch (Exception e) {
            log.error("Error collecting database metrics", e);
        }

        return metrics;
    }

    private long getDatabaseSize(Connection conn, String dbType) {
        try {
            String query = "postgres".equals(dbType) ?
                "SELECT pg_database_size(current_database())" :
                "SELECT SUM(data_length + index_length) FROM information_schema.TABLES";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                if (rs.next()) {
                    return rs.getLong(1);
                }
            }
        } catch (Exception e) {
            log.warn("Error getting database size", e);
        }
        return 0;
    }

    private int getActiveConnections(Connection conn, String dbType) {
        try {
            String query = "postgres".equals(dbType) ?
                "SELECT count(*) FROM pg_stat_activity WHERE state = 'active'" :
                "SELECT count(*) FROM information_schema.PROCESSLIST WHERE COMMAND != 'Sleep'";

            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery(query)) {
                if (rs.next()) {
                    return rs.getInt(1);
                }
            }
        } catch (Exception e) {
            log.warn("Error getting active connections", e);
        }
        return 0;
    }

    /**
     * Determine overall health based on recommendations
     */
    private PerformanceAnalysis.OverallHealth determineOverallHealth(
        List<IndexRecommendation> indexRecs,
        List<DatabaseRecommendation> generalRecs,
        List<PerformanceAnalysis.SlowQueryAnalysis> slowQueries
    ) {
        long criticalCount = indexRecs.stream()
            .filter(r -> r.getPriority() == IndexRecommendation.RecommendationPriority.CRITICAL)
            .count();

        criticalCount += generalRecs.stream()
            .filter(r -> r.getPriority() == DatabaseRecommendation.RecommendationPriority.CRITICAL)
            .count();

        if (criticalCount > 0) {
            return PerformanceAnalysis.OverallHealth.CRITICAL;
        }

        long highCount = indexRecs.stream()
            .filter(r -> r.getPriority() == IndexRecommendation.RecommendationPriority.HIGH)
            .count();

        highCount += generalRecs.stream()
            .filter(r -> r.getPriority() == DatabaseRecommendation.RecommendationPriority.HIGH)
            .count();

        if (highCount >= 5) {
            return PerformanceAnalysis.OverallHealth.POOR;
        } else if (highCount >= 2) {
            return PerformanceAnalysis.OverallHealth.FAIR;
        } else if (indexRecs.size() + generalRecs.size() > 0) {
            return PerformanceAnalysis.OverallHealth.GOOD;
        }

        return PerformanceAnalysis.OverallHealth.EXCELLENT;
    }

    /**
     * Generate AI-powered summary of analysis
     */
    private String generateAISummary(PerformanceAnalysis analysis) {
        try {
            StringBuilder prompt = new StringBuilder();
            prompt.append("Analyze this database performance report and provide a concise executive summary:\n\n");
            prompt.append(String.format("Database Type: %s\n", analysis.getDbType()));
            prompt.append(String.format("Overall Health: %s\n\n", analysis.getOverallHealth()));

            prompt.append(String.format("Index Recommendations: %d\n", analysis.getIndexRecommendations().size()));
            for (IndexRecommendation rec : analysis.getIndexRecommendations().stream().limit(3).toList()) {
                prompt.append(String.format("- %s: %s\n", rec.getPriority(), rec.getReasoning()));
            }

            prompt.append(String.format("\nGeneral Recommendations: %d\n", analysis.getGeneralRecommendations().size()));
            for (DatabaseRecommendation rec : analysis.getGeneralRecommendations().stream().limit(3).toList()) {
                prompt.append(String.format("- %s (%s): %s\n", rec.getTitle(), rec.getPriority(), rec.getDescription()));
            }

            prompt.append("\nProvide:\n");
            prompt.append("1. Executive summary (2-3 sentences)\n");
            prompt.append("2. Top 3 priorities\n");
            prompt.append("3. Expected impact if recommendations are implemented\n");

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(
                "You are an expert Database Administrator providing performance analysis summaries."
            ));
            messages.add(new UserMessage(prompt.toString()));

            return chatClient.prompt()
                .messages(messages)
                .call()
                .content();
        } catch (Exception e) {
            log.warn("Error generating AI summary", e);
            return "Performance analysis completed. See detailed recommendations below.";
        }
    }
}
