package com.dbaagent.service.brain.query;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.brain.CostCalibration;
import com.dbaagent.model.brain.PlanExecution;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.brain.CostCalibrationRepository;
import com.dbaagent.repository.brain.PlanExecutionRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.ExplainPlanService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * Brain 2.0: Adaptive Plan Scoring Service
 *
 * Learns from actual query execution to calibrate the database's cost model.
 * Tracks execution vs estimates to identify cardinality estimation errors
 * and adjust cost predictions.
 *
 * Inspired by optd's approach of adaptive query planning with runtime feedback.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AdaptivePlanScoringService {

    private final PlanExecutionRepository executionRepository;
    private final CostCalibrationRepository calibrationRepository;
    private final ExplainPlanService explainPlanService;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper;

    // Configuration
    private static final double SIGNIFICANT_ERROR_THRESHOLD = 10.0; // 10x estimation error
    private static final int MIN_SAMPLES_FOR_CALIBRATION = 10;
    private static final double LEARNING_RATE = 0.1;

    // MySQL slow log prefix patterns to strip (each can appear in any order)
    // Handles both raw (SET timestamp=1234567890;) and normalized (SET timestamp=?;) formats
    private static final Pattern USE_DATABASE_PREFIX = Pattern.compile(
        "^\\s*use\\s+\\S+\\s*;\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SET_TIMESTAMP_PREFIX = Pattern.compile(
        "^\\s*set\\s+timestamp\\s*=\\s*[\\d?]+\\s*;\\s*", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_COMMENT_PREFIX = Pattern.compile(
        "^\\s*/\\*[^*]*\\*/\\s*");

    // Progress tracking for background EXPLAIN jobs
    private final java.util.concurrent.ConcurrentHashMap<String, ExplainJobProgress> explainJobProgress =
        new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Progress tracking for EXPLAIN backfill jobs.
     */
    @lombok.Data
    @lombok.AllArgsConstructor
    @lombok.NoArgsConstructor
    public static class ExplainJobProgress {
        private String connectionId;
        private String status; // RUNNING, COMPLETED, FAILED, CANCELLED
        private int totalQueries;
        private int processedQueries;
        private int successfulExplains;
        private int failedExplains;
        private String currentQuery;
        private LocalDateTime startedAt;
        private LocalDateTime completedAt;
        private String errorMessage;

        // Detailed failure tracking
        private int nonExplainableQueries;  // INSERT/UPDATE/DELETE/etc.
        private int noRowEstimateQueries;   // EXPLAIN worked but no row count
        private int connectionErrors;        // Database connection failures
        private int parseErrors;             // EXPLAIN parsing failures
        private List<String> sampleFailures; // Sample of failure reasons for debugging

        public int getProgressPercent() {
            if (totalQueries == 0) return 0;
            return (int) ((processedQueries * 100.0) / totalQueries);
        }

        public void addSampleFailure(String reason) {
            if (sampleFailures == null) {
                sampleFailures = new ArrayList<>();
            }
            // Keep only first 5 sample failures to avoid bloat
            if (sampleFailures.size() < 5) {
                sampleFailures.add(reason);
            }
        }

        public String getFailureSummary() {
            List<String> parts = new ArrayList<>();
            if (nonExplainableQueries > 0) {
                parts.add(nonExplainableQueries + " non-SELECT queries");
            }
            if (noRowEstimateQueries > 0) {
                parts.add(noRowEstimateQueries + " missing row estimates");
            }
            if (connectionErrors > 0) {
                parts.add(connectionErrors + " connection errors");
            }
            if (parseErrors > 0) {
                parts.add(parseErrors + " parse errors");
            }
            return parts.isEmpty() ? null : String.join(", ", parts);
        }
    }

    /**
     * Record a query execution and learn from it.
     */
    @Transactional
    public PlanExecution recordExecution(
            String connectionId,
            String query,
            Double actualExecutionMs,
            Long actualRows) {

        log.debug("Recording execution for connection {}: {} ms, {} rows",
            connectionId, actualExecutionMs, actualRows);

        try {
            // Normalize query and generate fingerprint
            String normalizedQuery = normalizeQuery(query);
            String queryFingerprint = generateFingerprint(normalizedQuery);

            // Get EXPLAIN plan for the query
            ExplainPlanAnalysis explainAnalysis = null;
            String planSignature = null;
            Map<String, Object> planJson = null;
            Double estimatedCost = null;
            Long estimatedRows = null;

            try {
                explainAnalysis = explainPlanService.analyzeQuery(connectionId, query, false);
                if (explainAnalysis != null) {
                    planSignature = explainAnalysis.getPlanSignature();
                    if (explainAnalysis.getPlanJson() != null) {
                        planJson = objectMapper.readValue(explainAnalysis.getPlanJson(), Map.class);
                    }
                    estimatedCost = explainAnalysis.getEstimatedCost();
                    if (explainAnalysis.getPlanTree() != null) {
                        estimatedRows = explainAnalysis.getPlanTree().getPlanRows() != null ?
                            explainAnalysis.getPlanTree().getPlanRows().longValue() : null;
                    }
                }
            } catch (Exception e) {
                log.debug("Could not get EXPLAIN for query: {}", e.getMessage());
                planSignature = generateFingerprint(query); // Fallback
            }

            // Create execution record
            PlanExecution execution = PlanExecution.builder()
                .connectionId(connectionId)
                .queryFingerprint(queryFingerprint)
                .queryText(query)
                .normalizedQuery(normalizedQuery)
                .planSignature(planSignature != null ? planSignature : queryFingerprint)
                .planJson(planJson)
                .estimatedCost(estimatedCost)
                .estimatedRows(estimatedRows)
                .actualExecutionMs(actualExecutionMs)
                .actualRows(actualRows)
                .executionSource(PlanExecution.ExecutionSource.MONITORING)
                .executedAt(LocalDateTime.now())
                .build();

            execution.calculateCardinalityError();

            PlanExecution saved = executionRepository.save(execution);

            // Update cost calibration if we have enough data
            if (execution.hasSignificantError()) {
                log.info("Significant cardinality error detected for query fingerprint {}: ratio = {}",
                    queryFingerprint, execution.getCardinalityErrorRatio());
            }

            // Learn from this execution
            learnFromExecution(connectionId, execution);

            return saved;

        } catch (Exception e) {
            log.error("Error recording execution: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to record execution: " + e.getMessage(), e);
        }
    }

    /**
     * Record execution from EXPLAIN ANALYZE result.
     */
    @Transactional
    public PlanExecution recordFromExplainAnalyze(
            String connectionId,
            String query,
            ExplainPlanAnalysis analysis) {

        String normalizedQuery = normalizeQuery(query);
        String queryFingerprint = generateFingerprint(normalizedQuery);

        Map<String, Object> planJson = null;
        try {
            if (analysis.getPlanJson() != null) {
                planJson = objectMapper.readValue(analysis.getPlanJson(), Map.class);
            }
        } catch (Exception e) {
            log.debug("Could not parse plan JSON: {}", e.getMessage());
        }

        Long estimatedRows = null;
        Long actualRows = null;
        Double estimatedCost = null;
        Double actualExecutionMs = null;
        Long sharedHitBlocks = null;
        Long sharedReadBlocks = null;

        if (analysis.getPlanTree() != null) {
            estimatedRows = analysis.getPlanTree().getPlanRows() != null ?
                analysis.getPlanTree().getPlanRows().longValue() : null;
            actualRows = analysis.getPlanTree().getActualRows();
            estimatedCost = analysis.getPlanTree().getTotalCost();
            actualExecutionMs = analysis.getPlanTree().getActualTotalTime();

            if (analysis.getPlanTree().getAdditionalInfo() != null) {
                Map<String, Object> info = analysis.getPlanTree().getAdditionalInfo();
                sharedHitBlocks = toLong(info.get("Shared Hit Blocks"));
                sharedReadBlocks = toLong(info.get("Shared Read Blocks"));
            }
        }

        PlanExecution execution = PlanExecution.builder()
            .connectionId(connectionId)
            .queryFingerprint(queryFingerprint)
            .queryText(query)
            .normalizedQuery(normalizedQuery)
            .planSignature(analysis.getPlanSignature() != null ?
                analysis.getPlanSignature() : queryFingerprint)
            .planJson(planJson)
            .estimatedCost(estimatedCost)
            .estimatedRows(estimatedRows)
            .estimatedStartupCost(analysis.getPlanTree() != null ?
                analysis.getPlanTree().getStartupCost() : null)
            .actualExecutionMs(actualExecutionMs)
            .actualRows(actualRows)
            .actualLoops(analysis.getPlanTree() != null ?
                analysis.getPlanTree().getActualLoops() : null)
            .sharedHitBlocks(sharedHitBlocks)
            .sharedReadBlocks(sharedReadBlocks)
            .executionSource(PlanExecution.ExecutionSource.EXPLAIN_ANALYZE)
            .executedAt(LocalDateTime.now())
            .build();

        execution.calculateCardinalityError();

        PlanExecution saved = executionRepository.save(execution);

        // Learn from this execution
        learnFromExecution(connectionId, execution);

        return saved;
    }

    /**
     * Learn from an execution to update cost calibration.
     */
    private void learnFromExecution(String connectionId, PlanExecution execution) {
        if (execution.getEstimatedCost() == null || execution.getActualExecutionMs() == null) {
            return;
        }

        // Get or create calibration
        CostCalibration calibration = calibrationRepository.findByConnectionId(connectionId)
            .orElseGet(() -> CostCalibration.builder()
                .connectionId(connectionId)
                .createdAt(LocalDateTime.now())
                .build());

        // Determine operation type from plan
        String operationType = determineOperationType(execution);

        // Update calibration
        calibration.updateFromObservation(
            execution.getEstimatedCost(),
            execution.getActualExecutionMs(),
            operationType);

        calibrationRepository.save(calibration);
    }

    /**
     * Determine the primary operation type from a plan execution.
     */
    private String determineOperationType(PlanExecution execution) {
        if (execution.getPlanJson() == null) {
            return "UNKNOWN";
        }

        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> plan = execution.getPlanJson();

            // Look for Plan node
            Object planNode = plan.get("Plan");
            if (planNode == null && plan.containsKey("query_block")) {
                // MySQL JSON format
                return "NESTED LOOP"; // Default for MySQL
            }

            if (planNode instanceof Map) {
                @SuppressWarnings("unchecked")
                Map<String, Object> node = (Map<String, Object>) planNode;
                String nodeType = (String) node.get("Node Type");
                if (nodeType != null) {
                    return nodeType.toUpperCase();
                }
            }
        } catch (Exception e) {
            log.debug("Could not determine operation type: {}", e.getMessage());
        }

        return "UNKNOWN";
    }

    /**
     * Get calibrated cost for a query.
     */
    public Double getCalibratedCost(String connectionId, Double originalCost, String operationType) {
        Optional<CostCalibration> calibrationOpt = calibrationRepository.findByConnectionId(connectionId);

        if (calibrationOpt.isEmpty() || originalCost == null) {
            return originalCost;
        }

        CostCalibration calibration = calibrationOpt.get();
        return calibration.calibrate(originalCost, operationType);
    }

    /**
     * Get cost calibration for a connection.
     */
    public Optional<CostCalibration> getCalibration(String connectionId) {
        return calibrationRepository.findByConnectionId(connectionId);
    }

    /**
     * Get executions with significant cardinality errors.
     */
    public List<PlanExecution> getSignificantCardinalityErrors(String connectionId, int limit) {
        return executionRepository.findWithSignificantCardinalityError(
            connectionId, PageRequest.of(0, limit));
    }

    /**
     * Get average cardinality error for a connection.
     */
    public Double getAverageCardinalityError(String connectionId) {
        return executionRepository.calculateAverageCardinalityError(connectionId);
    }

    /**
     * Find queries with multiple different execution plans.
     */
    public Map<String, List<String>> findQueriesWithMultiplePlans(String connectionId) {
        Map<String, List<String>> result = new HashMap<>();

        // Get recent executions
        List<PlanExecution> executions = executionRepository
            .findByConnectionIdOrderByExecutedAtDesc(connectionId, PageRequest.of(0, 1000));

        // Group by query fingerprint
        Map<String, Set<String>> queryPlans = executions.stream()
            .collect(Collectors.groupingBy(
                PlanExecution::getQueryFingerprint,
                Collectors.mapping(PlanExecution::getPlanSignature, Collectors.toSet())));

        // Filter to queries with multiple plans
        for (Map.Entry<String, Set<String>> entry : queryPlans.entrySet()) {
            if (entry.getValue().size() > 1) {
                result.put(entry.getKey(), new ArrayList<>(entry.getValue()));
            }
        }

        return result;
    }

    /**
     * Get the best performing plan for a query.
     */
    public Optional<PlanExecution> getBestPlan(String connectionId, String queryFingerprint) {
        PlanExecution best = executionRepository.findBestPlan(connectionId, queryFingerprint);
        return Optional.ofNullable(best);
    }

    /**
     * Compare plan performance for a query.
     */
    public Map<String, Double> comparePlanPerformance(String connectionId, String queryFingerprint) {
        List<String> planSignatures = executionRepository
            .findDistinctPlanSignatures(connectionId, queryFingerprint);

        Map<String, Double> result = new HashMap<>();
        for (String signature : planSignatures) {
            Double avgTime = executionRepository.calculateAverageExecutionTime(connectionId, signature);
            if (avgTime != null) {
                result.put(signature, avgTime);
            }
        }

        return result;
    }

    /**
     * Get execution history for a query fingerprint.
     */
    public List<PlanExecution> getExecutionHistory(String connectionId, String queryFingerprint, int limit) {
        return executionRepository.findByConnectionIdAndQueryFingerprintOrderByExecutedAtDesc(
            connectionId, queryFingerprint, PageRequest.of(0, limit));
    }

    /**
     * Get recent executions for a connection.
     */
    public List<PlanExecution> getRecentExecutions(String connectionId, int limit) {
        return executionRepository.findByConnectionIdOrderByExecutedAtDesc(
            connectionId, PageRequest.of(0, limit));
    }

    /**
     * Calculate cardinality estimation accuracy score (0-100).
     */
    public double calculateEstimationAccuracy(String connectionId) {
        Double avgError = executionRepository.calculateAverageCardinalityError(connectionId);

        if (avgError == null || avgError == 0) {
            return 100.0; // No data or perfect estimation
        }

        // Convert error ratio to accuracy score
        // Error of 1.0 = 100% accuracy, error of 10 = 50% accuracy, error of 100 = 0%
        double logError = Math.log10(Math.max(avgError, 1.0 / avgError));
        double accuracy = Math.max(0, 100 - logError * 50);

        return accuracy;
    }

    /**
     * Get detailed cardinality accuracy statistics.
     */
    public Map<String, Object> getCardinalityAccuracyStats(String connectionId) {
        Map<String, Object> stats = new HashMap<>();

        // Total executions tracked (regardless of cardinality data)
        long totalExecutions = executionRepository.countByConnectionId(connectionId);
        stats.put("totalExecutions", totalExecutions);

        // Executions with both estimated AND actual rows (needed for accuracy calc)
        long totalEstimates = executionRepository.countWithCardinalityData(connectionId);
        long accurateEstimates = executionRepository.countAccurateEstimates(connectionId);
        long overestimates = executionRepository.countOverestimates(connectionId);
        long underestimates = executionRepository.countUnderestimates(connectionId);

        stats.put("totalEstimates", totalEstimates);
        stats.put("accurateEstimates", accurateEstimates);
        stats.put("overestimates", overestimates);
        stats.put("underestimates", underestimates);

        // Calculate overall accuracy as ratio
        if (totalEstimates > 0) {
            stats.put("overallAccuracy", (double) accurateEstimates / totalEstimates);
        } else {
            stats.put("overallAccuracy", null);
        }

        // Flags to help UI show appropriate message
        stats.put("hasExecutions", totalExecutions > 0);
        stats.put("needsExplainData", totalExecutions > 0 && totalEstimates == 0);

        // Get per-table accuracy from recent executions
        Map<String, double[]> perTableCounts = new HashMap<>(); // [accurate, total]
        List<PlanExecution> recentExecutions = executionRepository.findByConnectionIdOrderByExecutedAtDesc(
            connectionId, org.springframework.data.domain.PageRequest.of(0, 500));

        for (PlanExecution exec : recentExecutions) {
            if (exec.getEstimatedRows() != null && exec.getActualRows() != null) {
                // Extract table name from query if possible
                String tableName = extractMainTable(exec.getQueryText());
                if (tableName != null) {
                    double[] counts = perTableCounts.computeIfAbsent(tableName, k -> new double[]{0, 0});
                    counts[1]++; // total
                    if (exec.getCardinalityErrorRatio() != null &&
                        exec.getCardinalityErrorRatio() >= 0.5 &&
                        exec.getCardinalityErrorRatio() <= 2.0) {
                        counts[0]++; // accurate
                    }
                }
            }
        }

        Map<String, Double> perTableAccuracy = new HashMap<>();
        for (Map.Entry<String, double[]> entry : perTableCounts.entrySet()) {
            double[] counts = entry.getValue();
            if (counts[1] > 0) {
                perTableAccuracy.put(entry.getKey(), counts[0] / counts[1]);
            }
        }
        stats.put("perTableAccuracy", perTableAccuracy);

        // Track per-table over/underestimate patterns for recommendations
        Map<String, long[]> perTablePatterns = new HashMap<>(); // [overestimates, underestimates, total]
        for (PlanExecution exec : recentExecutions) {
            if (exec.getEstimatedRows() != null && exec.getActualRows() != null) {
                String tableName = extractMainTable(exec.getQueryText());
                if (tableName != null) {
                    long[] patterns = perTablePatterns.computeIfAbsent(tableName, k -> new long[]{0, 0, 0});
                    patterns[2]++; // total
                    if (exec.getCardinalityErrorRatio() != null) {
                        if (exec.getCardinalityErrorRatio() > 2.0) {
                            patterns[0]++; // overestimate
                        } else if (exec.getCardinalityErrorRatio() < 0.5) {
                            patterns[1]++; // underestimate
                        }
                    }
                }
            }
        }

        // Generate actionable recommendations
        List<Map<String, Object>> recommendations = generateCardinalityRecommendations(
            connectionId, perTableAccuracy, perTablePatterns, totalEstimates, overestimates, underestimates);
        stats.put("recommendations", recommendations);

        return stats;
    }

    /**
     * Generate actionable recommendations based on cardinality accuracy data.
     * Focus on database statistics maintenance - not query rewrites (that's Slow Queries tab).
     * Supports both MySQL and PostgreSQL with database-specific recommendations.
     */
    private List<Map<String, Object>> generateCardinalityRecommendations(
            String connectionId,
            Map<String, Double> perTableAccuracy,
            Map<String, long[]> perTablePatterns,
            long totalEstimates,
            long overestimates,
            long underestimates) {

        List<Map<String, Object>> recommendations = new ArrayList<>();

        if (totalEstimates < 10) {
            // Not enough data for meaningful recommendations
            return recommendations;
        }

        // Determine database type for appropriate recommendations
        String dbType = "mysql"; // Default
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            dbType = providerRegistry.getCanonicalName(connection.getDbType());
        } catch (Exception e) {
            log.debug("Could not determine database type for recommendations, defaulting to MySQL: {}", e.getMessage());
        }

        boolean isPostgres = "postgres".equals(dbType);

        // 1. Tables with very low accuracy (<20%) - need ANALYZE
        List<String> tablesNeedingAnalyze = perTableAccuracy.entrySet().stream()
            .filter(e -> e.getValue() < 0.2 && perTablePatterns.getOrDefault(e.getKey(), new long[]{0,0,0})[2] >= 3)
            .sorted(Map.Entry.comparingByValue())
            .limit(5)
            .map(Map.Entry::getKey)
            .toList();

        if (!tablesNeedingAnalyze.isEmpty()) {
            String tableList = String.join(", ", tablesNeedingAnalyze);

            if (isPostgres) {
                // PostgreSQL: ANALYZE table_name (no TABLE keyword)
                recommendations.add(Map.of(
                    "type", "ANALYZE_TABLE",
                    "priority", "HIGH",
                    "title", "Run ANALYZE on High-Error Tables",
                    "description", String.format(
                        "Tables %s have very low cardinality accuracy (<20%%). " +
                        "Running ANALYZE updates planner statistics for better query plans.",
                        tableList),
                    "sql", "ANALYZE " + tableList + ";",
                    "impact", "Improved query plans, potentially 10-50% faster queries"
                ));
            } else {
                // MySQL: ANALYZE TABLE table_name
                recommendations.add(Map.of(
                    "type", "ANALYZE_TABLE",
                    "priority", "HIGH",
                    "title", "Refresh Table Statistics",
                    "description", String.format(
                        "Tables %s have very low cardinality accuracy (<20%%). " +
                        "MySQL optimizer is making poor estimates, likely due to stale statistics.",
                        tableList),
                    "sql", "ANALYZE TABLE " + tableList + ";",
                    "impact", "Refreshes statistics so optimizer can make better row estimates and choose optimal query plans."
                ));
            }
        }

        // 2. Tables with consistent overestimation pattern (>70% overestimates)
        List<String> overestimatedTables = perTablePatterns.entrySet().stream()
            .filter(e -> {
                long[] p = e.getValue();
                return p[2] >= 5 && (double) p[0] / p[2] > 0.7;
            })
            .map(Map.Entry::getKey)
            .limit(3)
            .toList();

        if (!overestimatedTables.isEmpty() && !tablesNeedingAnalyze.containsAll(overestimatedTables)) {
            String overestTableList = String.join(", ", overestimatedTables);

            if (isPostgres) {
                recommendations.add(Map.of(
                    "type", "OVERESTIMATE_PATTERN",
                    "priority", "MEDIUM",
                    "title", "Consistent Overestimation Detected",
                    "description", String.format(
                        "Tables %s consistently have overestimated row counts. " +
                        "This may cause the planner to choose sequential scans over index scans.",
                        overestTableList),
                    "sql", "ANALYZE " + overestTableList + ";\n\n" +
                           "-- If overestimation persists after ANALYZE, check for table bloat:\n" +
                           "SELECT schemaname, relname, n_dead_tup, n_live_tup,\n" +
                           "       ROUND(100.0 * n_dead_tup / NULLIF(n_live_tup, 0), 1) AS dead_pct\n" +
                           "FROM pg_stat_user_tables\n" +
                           "WHERE n_dead_tup > 1000\n" +
                           "ORDER BY n_dead_tup DESC;",
                    "impact", "After ANALYZE, if overestimation persists, consider VACUUM FULL for severely bloated tables."
                ));
            } else {
                recommendations.add(Map.of(
                    "type", "OVERESTIMATE_PATTERN",
                    "priority", "MEDIUM",
                    "title", "Consistent Overestimation Detected",
                    "description", String.format(
                        "Tables %s consistently have overestimated row counts. " +
                        "This may cause the optimizer to avoid indexes when it shouldn't.",
                        overestTableList),
                    "sql", "ANALYZE TABLE " + overestTableList + ";",
                    "impact", "After ANALYZE, if overestimation persists, consider if data has recently been deleted in bulk."
                ));
            }
        }

        // 3. Tables with consistent underestimation (>70% underestimates) - histogram/extended stats candidates
        List<String> underestimatedTables = perTablePatterns.entrySet().stream()
            .filter(e -> {
                long[] p = e.getValue();
                return p[2] >= 5 && (double) p[1] / p[2] > 0.7;
            })
            .map(Map.Entry::getKey)
            .limit(3)
            .toList();

        if (!underestimatedTables.isEmpty()) {
            String underestTableList = String.join(", ", underestimatedTables);

            if (isPostgres) {
                // PostgreSQL: Increase statistics_target and create extended statistics
                recommendations.add(Map.of(
                    "type", "STATISTICS_TARGET",
                    "priority", "MEDIUM",
                    "title", "Increase Statistics Target",
                    "description", String.format(
                        "Tables %s consistently have underestimated row counts. " +
                        "Increase statistics target from default 100 to 500-1000 for columns with skewed data.",
                        underestTableList),
                    "sql", "-- For each high-error column, increase statistics:\n" +
                           "ALTER TABLE table_name ALTER COLUMN column_name SET STATISTICS 500;\n" +
                           "ANALYZE table_name (column_name);\n\n" +
                           "-- Check current statistics target:\n" +
                           "SELECT attname, attstattarget\n" +
                           "FROM pg_attribute\n" +
                           "WHERE attrelid = 'table_name'::regclass\n" +
                           "  AND attstattarget > 0;",
                    "impact", "Better handling of skewed data distributions, more accurate row estimates."
                ));

                // PostgreSQL: Extended statistics for correlated columns
                recommendations.add(Map.of(
                    "type", "EXTENDED_STATISTICS",
                    "priority", "MEDIUM",
                    "title", "Create Extended Statistics for Correlated Columns",
                    "description", String.format(
                        "If queries on %s filter on multiple correlated columns, " +
                        "PostgreSQL may underestimate cardinality. Extended statistics help.",
                        underestTableList),
                    "sql", "-- Create multivariate statistics for correlated columns:\n" +
                           "CREATE STATISTICS stat_name (dependencies, ndistinct, mcv)\n" +
                           "    ON column1, column2 FROM table_name;\n" +
                           "ANALYZE table_name;\n\n" +
                           "-- View extended statistics:\n" +
                           "SELECT stxname, stxkeys, stxkind FROM pg_statistic_ext;",
                    "impact", "Better estimates for multi-column predicates (10-100x improvement possible)."
                ));
            } else {
                // MySQL: Histograms
                recommendations.add(Map.of(
                    "type", "HISTOGRAM_CANDIDATE",
                    "priority", "MEDIUM",
                    "title", "Consider Histograms for Skewed Data",
                    "description", String.format(
                        "Tables %s consistently have underestimated row counts. " +
                        "This often indicates skewed data distribution. MySQL 8.0+ supports histograms.",
                        underestTableList),
                    "sql", "-- For each table, identify columns used in WHERE clauses and create histograms:\n" +
                           "-- ANALYZE TABLE table_name UPDATE HISTOGRAM ON column_name WITH 100 BUCKETS;",
                    "impact", "Histograms help optimizer understand data distribution for columns with non-uniform values."
                ));
            }
        }

        // 4. PostgreSQL-specific: Check for table bloat affecting estimates
        if (isPostgres && !tablesNeedingAnalyze.isEmpty()) {
            recommendations.add(Map.of(
                "type", "TABLE_BLOAT_CHECK",
                "priority", "LOW",
                "title", "Check for Table Bloat",
                "description", "Table bloat can cause inaccurate row estimates. " +
                    "Consider VACUUM FULL for severely bloated tables.",
                "sql", "-- Check estimated vs actual rows and bloat:\n" +
                       "SELECT schemaname, relname,\n" +
                       "       n_live_tup AS estimated_rows,\n" +
                       "       n_dead_tup AS dead_rows,\n" +
                       "       pg_size_pretty(pg_relation_size(relid)) AS table_size\n" +
                       "FROM pg_stat_user_tables\n" +
                       "WHERE n_dead_tup > n_live_tup * 0.1\n" +
                       "ORDER BY n_dead_tup DESC\n" +
                       "LIMIT 10;",
                "impact", "VACUUM FULL reclaims space and updates visibility map for accurate estimates."
            ));
        }

        // 5. Overall low accuracy with sufficient data
        if (totalEstimates >= 50) {
            double overallAccuracyPct = (totalEstimates - overestimates - underestimates) * 100.0 / totalEstimates;
            if (overallAccuracyPct < 30) {
                if (isPostgres) {
                    recommendations.add(Map.of(
                        "type", "GLOBAL_STATS_REFRESH",
                        "priority", "HIGH",
                        "title", "Database-Wide Statistics May Be Stale",
                        "description", String.format(
                            "Overall cardinality accuracy is only %.0f%%. " +
                            "Consider running ANALYZE on the entire database or frequently queried tables.",
                            overallAccuracyPct),
                        "sql", "-- Analyze entire database (may take time on large DBs):\n" +
                               "ANALYZE;\n\n" +
                               "-- Or analyze specific tables:\n" +
                               "ANALYZE table1, table2, table3;\n\n" +
                               "-- Check autovacuum settings:\n" +
                               "SHOW autovacuum;",
                        "impact", "Fresh statistics improve query plan selection across all queries."
                    ));
                } else {
                    recommendations.add(Map.of(
                        "type", "GLOBAL_STATS_REFRESH",
                        "priority", "HIGH",
                        "title", "Database-Wide Statistics May Be Stale",
                        "description", String.format(
                            "Overall cardinality accuracy is only %.0f%%. " +
                            "Consider running ANALYZE on frequently queried tables.",
                            overallAccuracyPct),
                        "sql", "-- Run ANALYZE on your most frequently accessed tables:\n" +
                               "-- ANALYZE TABLE table1, table2, table3;",
                        "impact", "Fresh statistics improve query plan selection across all queries."
                    ));
                }
            }
        }

        return recommendations;
    }

    /**
     * Extract main table name from a SQL query (simple heuristic).
     */
    private String extractMainTable(String query) {
        if (query == null) return null;

        String upper = query.toUpperCase();
        // Look for FROM clause
        int fromIdx = upper.indexOf(" FROM ");
        if (fromIdx < 0) return null;

        String afterFrom = query.substring(fromIdx + 6).trim();
        // Get first word (table name)
        String[] parts = afterFrom.split("[\\s,;()]+");
        if (parts.length > 0 && !parts[0].isEmpty()) {
            return parts[0].toLowerCase().replaceAll("[\"'`]", "");
        }
        return null;
    }

    /**
     * Get calibration readiness status.
     */
    public Map<String, Object> getCalibrationStatus(String connectionId) {
        Map<String, Object> status = new HashMap<>();

        long executionCount = executionRepository.countByConnectionId(connectionId);
        status.put("executionCount", executionCount);
        status.put("minSamplesRequired", MIN_SAMPLES_FOR_CALIBRATION);
        status.put("isReady", executionCount >= MIN_SAMPLES_FOR_CALIBRATION);

        Optional<CostCalibration> calibrationOpt = calibrationRepository.findByConnectionId(connectionId);
        if (calibrationOpt.isPresent()) {
            CostCalibration calibration = calibrationOpt.get();
            status.put("hasCalibration", true);
            status.put("calibrationConfidence", calibration.getConfidence());
            status.put("isCalibrationReliable", calibration.isReliable());
            status.put("trainingSamples", calibration.getTrainingSamples());
            status.put("costMultiplier", calibration.getCostMultiplier());
            status.put("seqScanFactor", calibration.getSeqScanCostFactor());
            status.put("indexScanFactor", calibration.getIndexScanCostFactor());
            status.put("joinFactor", calibration.getJoinCostFactor());
            status.put("sortFactor", calibration.getSortCostFactor());
        } else {
            status.put("hasCalibration", false);
        }

        Double avgError = executionRepository.calculateAverageCardinalityError(connectionId);
        status.put("averageCardinalityError", avgError);
        status.put("estimationAccuracy", calculateEstimationAccuracy(connectionId));

        return status;
    }

    /**
     * Reset calibration for a connection.
     */
    @Transactional
    public void resetCalibration(String connectionId) {
        calibrationRepository.deleteByConnectionId(connectionId);
        log.info("Reset calibration for connection {}", connectionId);
    }

    /**
     * Clear all execution data for a connection.
     * Used before re-backfilling with original (non-normalized) queries.
     */
    @Transactional
    public void clearExecutions(String connectionId) {
        executionRepository.deleteByConnectionId(connectionId);
        log.info("Cleared all executions for connection {}", connectionId);
    }

    // Helper methods

    private String normalizeQuery(String query) {
        if (query == null) return "";

        // Remove extra whitespace
        String normalized = query.replaceAll("\\s+", " ").trim();

        // Replace literals with placeholders
        normalized = normalized.replaceAll("'[^']*'", "?");
        normalized = normalized.replaceAll("\\b\\d+\\b", "?");

        // Convert to lowercase for comparison
        return normalized.toLowerCase();
    }

    private String generateFingerprint(String normalizedQuery) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedQuery.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                String hexPart = Integer.toHexString(0xff & b);
                if (hexPart.length() == 1) {
                    hex.append('0');
                }
                hex.append(hexPart);
            }
            return hex.toString();
        } catch (Exception e) {
            return String.valueOf(normalizedQuery.hashCode());
        }
    }

    /**
     * Backfill Query Intelligence from slow query history.
     * Processes existing slow queries to populate cardinality accuracy and plan pattern data.
     *
     * @param connectionId The connection ID
     * @param slowQueries List of slow queries to process
     * @return Number of executions recorded
     */
    @Transactional
    public int backfillFromSlowQueries(String connectionId, List<com.dbaagent.model.SlowQuery> slowQueries) {
        if (slowQueries == null || slowQueries.isEmpty()) {
            return 0;
        }

        log.info("Backfilling Query Intelligence with {} slow queries for connection {}",
            slowQueries.size(), connectionId);

        int recordedCount = 0;
        for (com.dbaagent.model.SlowQuery slowQuery : slowQueries) {
            try {
                Double execTimeMs = slowQuery.getAvgExecutionTimeMs();
                if (execTimeMs == null && slowQuery.getTotalExecutionTimeMs() != null &&
                    slowQuery.getCallCount() != null && slowQuery.getCallCount() > 0) {
                    execTimeMs = slowQuery.getTotalExecutionTimeMs() / slowQuery.getCallCount();
                }

                Long actualRows = slowQuery.getRowsSent();
                if (actualRows == null) {
                    actualRows = slowQuery.getAvgRowsSent();
                }

                if (execTimeMs != null && execTimeMs > 0) {
                    // Prefer original query text for EXPLAIN (has actual values)
                    // Fall back to normalized only if original is not available
                    String queryText = slowQuery.getQueryText();
                    if (queryText == null || queryText.isBlank()) {
                        queryText = slowQuery.getNormalizedQuery();
                    }

                    if (queryText != null && !queryText.isBlank()) {
                        // Strip MySQL slow log prefixes before storing
                        String cleanQuery = stripSlowLogPrefix(queryText);
                        if (cleanQuery != null && !cleanQuery.isBlank()) {
                            recordExecution(connectionId, cleanQuery, execTimeMs, actualRows);
                            recordedCount++;
                        }
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to backfill query: {}", e.getMessage());
            }
        }

        log.info("Backfilled {} executions for Query Intelligence from connection {}", recordedCount, connectionId);
        return recordedCount;
    }

    /**
     * Start background EXPLAIN processing for executions missing estimated rows.
     * Runs asynchronously and updates progress.
     *
     * @param connectionId The connection ID
     * @param maxQueries Maximum number of queries to process (0 for all)
     * @return Initial progress status
     */
    public ExplainJobProgress startExplainBackfill(String connectionId, int maxQueries) {
        // Check if job is already running
        ExplainJobProgress existing = explainJobProgress.get(connectionId);
        if (existing != null && "RUNNING".equals(existing.getStatus())) {
            return existing;
        }

        // Count queries needing EXPLAIN
        long totalMissing = executionRepository.countMissingEstimatedRows(connectionId);
        int toProcess = maxQueries > 0 ? Math.min(maxQueries, (int) totalMissing) : (int) totalMissing;

        if (toProcess == 0) {
            ExplainJobProgress noWork = new ExplainJobProgress();
            noWork.setConnectionId(connectionId);
            noWork.setStatus("COMPLETED");
            noWork.setTotalQueries(0);
            noWork.setProcessedQueries(0);
            noWork.setCompletedAt(LocalDateTime.now());
            return noWork;
        }

        // Initialize progress
        ExplainJobProgress progress = new ExplainJobProgress();
        progress.setConnectionId(connectionId);
        progress.setStatus("RUNNING");
        progress.setTotalQueries(toProcess);
        progress.setProcessedQueries(0);
        progress.setSuccessfulExplains(0);
        progress.setFailedExplains(0);
        progress.setStartedAt(LocalDateTime.now());

        explainJobProgress.put(connectionId, progress);

        // Run in background thread
        Thread.startVirtualThread(() -> runExplainBackfill(connectionId, toProcess, progress));

        return progress;
    }

    /**
     * Get current progress of EXPLAIN backfill job.
     */
    public ExplainJobProgress getExplainProgress(String connectionId) {
        ExplainJobProgress progress = explainJobProgress.get(connectionId);
        if (progress == null) {
            // Check if there's work to do
            long missing = executionRepository.countMissingEstimatedRows(connectionId);
            ExplainJobProgress status = new ExplainJobProgress();
            status.setConnectionId(connectionId);
            status.setStatus("NOT_STARTED");
            status.setTotalQueries((int) missing);
            return status;
        }
        return progress;
    }

    /**
     * Cancel a running EXPLAIN backfill job.
     */
    public void cancelExplainBackfill(String connectionId) {
        ExplainJobProgress progress = explainJobProgress.get(connectionId);
        if (progress != null && "RUNNING".equals(progress.getStatus())) {
            progress.setStatus("CANCELLED");
            progress.setCompletedAt(LocalDateTime.now());
        }
    }

    /**
     * Background worker to run EXPLAIN on queries.
     * Resource-friendly: small batches, long delays between queries.
     */
    private void runExplainBackfill(String connectionId, int maxQueries, ExplainJobProgress progress) {
        log.info("Starting EXPLAIN backfill for connection {} ({} queries)", connectionId, maxQueries);

        // Resource-friendly settings
        final int BATCH_SIZE = 20;           // Small batches
        final int DELAY_BETWEEN_QUERIES_MS = 200;  // 200ms between each query
        final int DELAY_BETWEEN_BATCHES_MS = 2000; // 2 seconds between batches

        try {
            int offset = 0;

            while (offset < maxQueries && "RUNNING".equals(progress.getStatus())) {
                int remaining = maxQueries - offset;
                int currentBatch = Math.min(BATCH_SIZE, remaining);

                List<PlanExecution> executions = executionRepository.findMissingEstimatedRows(
                    connectionId, PageRequest.of(0, currentBatch));

                if (executions.isEmpty()) {
                    break;
                }

                for (PlanExecution exec : executions) {
                    if (!"RUNNING".equals(progress.getStatus())) {
                        break; // Job cancelled
                    }

                    // Strip MySQL slow log prefixes before running EXPLAIN
                    String queryToExplain = stripSlowLogPrefix(exec.getQueryText());

                    // Skip non-explainable queries (INSERT/UPDATE/DELETE/etc.)
                    if (!isExplainableQuery(queryToExplain)) {
                        String queryType = getQueryType(queryToExplain);
                        log.info("Skipping non-explainable {} query: {}", queryType, truncateQuery(queryToExplain, 50));
                        progress.setNonExplainableQueries(progress.getNonExplainableQueries() + 1);
                        progress.setProcessedQueries(progress.getProcessedQueries() + 1);
                        progress.setFailedExplains(progress.getFailedExplains() + 1);
                        progress.addSampleFailure("Non-SELECT query (" + queryType + "): " + truncateQuery(queryToExplain, 40));
                        continue;
                    }

                    progress.setCurrentQuery(truncateQuery(queryToExplain, 100));

                    try {
                        // Run EXPLAIN to get estimated rows (not ANALYZE - just plan)
                        ExplainPlanAnalysis analysis = explainPlanService.analyzeQuery(
                            connectionId, queryToExplain, false);

                        if (analysis != null && analysis.getPlanTree() != null) {
                            Long estimatedRows = analysis.getPlanTree().getPlanRows() != null ?
                                analysis.getPlanTree().getPlanRows().longValue() : null;

                            if (estimatedRows != null) {
                                // Update the execution record
                                exec.setEstimatedRows(estimatedRows);
                                exec.setEstimatedCost(analysis.getEstimatedCost());
                                exec.calculateCardinalityError();
                                executionRepository.save(exec);
                                progress.setSuccessfulExplains(progress.getSuccessfulExplains() + 1);
                            } else {
                                // EXPLAIN worked but couldn't extract row estimate
                                log.info("EXPLAIN returned no row estimate for query: {}", truncateQuery(queryToExplain, 50));
                                progress.setNoRowEstimateQueries(progress.getNoRowEstimateQueries() + 1);
                                progress.setFailedExplains(progress.getFailedExplains() + 1);
                                progress.addSampleFailure("No row estimate: " + truncateQuery(queryToExplain, 40));
                            }
                        } else {
                            // EXPLAIN returned null or empty plan
                            String reason = analysis == null ? "null analysis" : "null plan tree";
                            log.warn("EXPLAIN returned {} for query: {}", reason, truncateQuery(queryToExplain, 50));
                            progress.setParseErrors(progress.getParseErrors() + 1);
                            progress.setFailedExplains(progress.getFailedExplains() + 1);
                            progress.addSampleFailure("Parse error (" + reason + "): " + truncateQuery(queryToExplain, 40));
                        }
                    } catch (Exception e) {
                        String errorMsg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
                        log.warn("EXPLAIN failed for query [{}]: {}", truncateQuery(queryToExplain, 30), errorMsg);

                        // Categorize the error
                        if (isConnectionError(e)) {
                            progress.setConnectionErrors(progress.getConnectionErrors() + 1);
                            progress.addSampleFailure("Connection error: " + errorMsg);
                        } else {
                            progress.setParseErrors(progress.getParseErrors() + 1);
                            progress.addSampleFailure("Error: " + errorMsg);
                        }
                        progress.setFailedExplains(progress.getFailedExplains() + 1);
                    }

                    progress.setProcessedQueries(progress.getProcessedQueries() + 1);

                    // Delay between queries to be gentle on resources
                    try {
                        Thread.sleep(DELAY_BETWEEN_QUERIES_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }

                offset += currentBatch;

                // Longer pause between batches
                if (offset < maxQueries && "RUNNING".equals(progress.getStatus())) {
                    try {
                        Thread.sleep(DELAY_BETWEEN_BATCHES_MS);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }

            progress.setStatus("COMPLETED");
            progress.setCompletedAt(LocalDateTime.now());
            progress.setCurrentQuery(null);

            // Build detailed completion message
            String failureSummary = progress.getFailureSummary();
            if (failureSummary != null) {
                log.info("EXPLAIN backfill completed for connection {}: {} successful, {} failed ({})",
                    connectionId, progress.getSuccessfulExplains(), progress.getFailedExplains(), failureSummary);
            } else {
                log.info("EXPLAIN backfill completed for connection {}: {} successful, {} failed",
                    connectionId, progress.getSuccessfulExplains(), progress.getFailedExplains());
            }

        } catch (Exception e) {
            log.error("EXPLAIN backfill failed for connection {}: {}", connectionId, e.getMessage(), e);
            progress.setStatus("FAILED");
            progress.setErrorMessage(e.getMessage());
            progress.setCompletedAt(LocalDateTime.now());
        }
    }

    private String truncateQuery(String query, int maxLen) {
        if (query == null) return null;
        if (query.length() <= maxLen) return query;
        return query.substring(0, maxLen) + "...";
    }

    /**
     * Extract query type (SELECT, INSERT, UPDATE, DELETE, etc.) for error reporting.
     */
    private String getQueryType(String query) {
        if (query == null || query.isBlank()) {
            return "EMPTY";
        }
        String upper = query.trim().toUpperCase();
        if (upper.startsWith("INSERT")) return "INSERT";
        if (upper.startsWith("UPDATE")) return "UPDATE";
        if (upper.startsWith("DELETE")) return "DELETE";
        if (upper.startsWith("CREATE")) return "CREATE";
        if (upper.startsWith("ALTER")) return "ALTER";
        if (upper.startsWith("DROP")) return "DROP";
        if (upper.startsWith("TRUNCATE")) return "TRUNCATE";
        if (upper.startsWith("CALL")) return "CALL";
        if (upper.startsWith("SHOW")) return "SHOW";
        if (upper.startsWith("SET")) return "SET";
        if (upper.startsWith("GRANT")) return "GRANT";
        if (upper.startsWith("REVOKE")) return "REVOKE";
        // Return first word as fallback
        int spaceIdx = upper.indexOf(' ');
        return spaceIdx > 0 ? upper.substring(0, Math.min(spaceIdx, 15)) : upper.substring(0, Math.min(15, upper.length()));
    }

    /**
     * Check if an exception is a database connection error.
     */
    private boolean isConnectionError(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";
        String className = e.getClass().getName().toLowerCase();

        return msg.contains("connection") ||
               msg.contains("refused") ||
               msg.contains("timeout") ||
               msg.contains("unreachable") ||
               msg.contains("authentication") ||
               msg.contains("access denied") ||
               msg.contains("cannot connect") ||
               msg.contains("no route to host") ||
               msg.contains("communications link failure") ||
               className.contains("connection") ||
               className.contains("socket");
    }

    private Long toLong(Object value) {
        if (value == null) return null;
        if (value instanceof Number) return ((Number) value).longValue();
        try {
            return Long.parseLong(String.valueOf(value));
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Strip MySQL slow log prefixes from query text.
     * Removes patterns like (in any order):
     * - "SET timestamp=1234567890;" or "SET timestamp=?;"
     * - SQL comments (slash-star ... star-slash)
     * - "use database_name;"
     *
     * @param query The raw query text
     * @return The query with prefixes stripped
     */
    private String stripSlowLogPrefix(String query) {
        if (query == null || query.isBlank()) {
            return query;
        }

        String result = query.trim();
        boolean changed;

        // Iteratively strip prefixes (they can appear in any order)
        do {
            changed = false;
            String before = result;

            result = USE_DATABASE_PREFIX.matcher(result).replaceFirst("");
            if (!result.equals(before)) {
                changed = true;
                result = result.trim();
            }

            before = result;
            result = SET_TIMESTAMP_PREFIX.matcher(result).replaceFirst("");
            if (!result.equals(before)) {
                changed = true;
                result = result.trim();
            }

            before = result;
            result = SQL_COMMENT_PREFIX.matcher(result).replaceFirst("");
            if (!result.equals(before)) {
                changed = true;
                result = result.trim();
            }
        } while (changed && !result.isEmpty());

        return result;
    }

    /**
     * Check if a query is explainable (SELECT, WITH, or EXPLAIN).
     */
    private boolean isExplainableQuery(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        String upper = query.trim().toUpperCase();
        return upper.startsWith("SELECT") ||
               upper.startsWith("WITH") ||
               upper.startsWith("EXPLAIN");
    }
}
