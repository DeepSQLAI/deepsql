package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.QueryPlanCache;
import com.dbaagent.model.QueryPlanCache.PlanType;
import com.dbaagent.model.QueryPlanComparison;
import com.dbaagent.model.QueryPlanComparison.Severity;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.QueryPlanCacheRepository;
import com.dbaagent.repository.QueryPlanComparisonRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.Statement;
import java.time.LocalDateTime;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Service for caching and comparing query execution plans.
 * Captures EXPLAIN output, stores it, and detects plan changes/regressions.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueryPlanCacheService {

    private final QueryPlanCacheRepository planCacheRepository;
    private final QueryPlanComparisonRepository comparisonRepository;
    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final ObjectMapper objectMapper;
    private final DatabaseProviderRegistry providerRegistry;

    private static final double SIGNIFICANT_COST_CHANGE_THRESHOLD = 20.0; // 20% change
    private static final double CRITICAL_COST_CHANGE_THRESHOLD = 100.0; // 100% change

    /**
     * Capture and store the execution plan for a query
     */
    @Transactional
    public QueryPlanCache capturePlan(String connectionId, String query, boolean analyze) {
        log.info("Capturing {} plan for query on connection {}",
                analyze ? "EXPLAIN ANALYZE" : "EXPLAIN", connectionId);

        try {
            ConnectionRequest connReq = credentialService.getDecryptedConnection(connectionId);
            String dbType = providerRegistry.getCanonicalName(connReq.getDbType());

            Map<String, Object> planResult;
            try (Connection connection = connectionService.getConnection(connectionId, connReq)) {
                planResult = executePlan(connection, query, dbType, analyze);
            }

            if (planResult == null || planResult.isEmpty()) {
                throw new RuntimeException("Failed to get execution plan");
            }

            // Normalize query and calculate hash
            String normalizedQuery = normalizeQuery(query);
            String queryHash = calculateHash(normalizedQuery);

            // Calculate plan hash
            String planJson = objectMapper.writeValueAsString(planResult);
            String planHash = calculateHash(planJson);

            // Check if this exact plan already exists
            if (planCacheRepository.existsByConnectionIdAndQueryHashAndPlanHash(connectionId, queryHash, planHash)) {
                log.info("Plan already exists for query hash {} - returning existing", queryHash);
                return planCacheRepository.findFirstByConnectionIdAndQueryHashOrderByCapturedAtDesc(connectionId, queryHash)
                        .orElse(null);
            }

            // Analyze plan characteristics
            PlanAnalysis analysis = analyzePlan(planResult, dbType);

            // Create plan cache entry
            QueryPlanCache plan = QueryPlanCache.builder()
                    .id(UUID.randomUUID().toString())
                    .connectionId(connectionId)
                    .queryHash(queryHash)
                    .queryText(query)
                    .normalizedQuery(normalizedQuery)
                    .planType(analyze ? PlanType.EXPLAIN_ANALYZE : PlanType.EXPLAIN)
                    .planJson(planResult)
                    .planHash(planHash)
                    .planSummary(analysis.summary)
                    .estimatedCost(analysis.estimatedCost)
                    .estimatedRows(analysis.estimatedRows)
                    .actualTimeMs(analysis.actualTimeMs)
                    .actualRows(analysis.actualRows)
                    .usesSeqScan(analysis.usesSeqScan)
                    .usesIndexScan(analysis.usesIndexScan)
                    .usesBitmapScan(analysis.usesBitmapScan)
                    .usesNestedLoop(analysis.usesNestedLoop)
                    .usesHashJoin(analysis.usesHashJoin)
                    .usesMergeJoin(analysis.usesMergeJoin)
                    .usesSort(analysis.usesSort)
                    .usesMaterialize(analysis.usesMaterialize)
                    .tablesInvolved(analysis.tablesInvolved.toArray(new String[0]))
                    .indexesUsed(analysis.indexesUsed.toArray(new String[0]))
                    .warnings(analysis.warnings.toArray(new String[0]))
                    .recommendations(analysis.recommendations.toArray(new String[0]))
                    .isBaseline(false)
                    .capturedAt(LocalDateTime.now())
                    .build();

            plan = planCacheRepository.save(plan);
            log.info("Saved plan cache entry {} for query hash {}", plan.getId(), queryHash);

            // Compare with baseline if exists
            Optional<QueryPlanCache> baselineOpt = planCacheRepository
                    .findFirstByConnectionIdAndQueryHashAndIsBaselineTrueOrderByCapturedAtDesc(connectionId, queryHash);

            if (baselineOpt.isPresent()) {
                QueryPlanComparison comparison = comparePlans(baselineOpt.get(), plan);
                comparisonRepository.save(comparison);

                if (Boolean.TRUE.equals(comparison.getIsRegression())) {
                    log.warn("Plan regression detected for query hash {}: {}", queryHash, comparison.getSummary());
                }
            } else {
                // If no baseline exists and this is the first plan, set it as baseline
                long existingCount = planCacheRepository.countByConnectionIdAndQueryHash(connectionId, queryHash);
                if (existingCount == 1) {
                    plan.setIsBaseline(true);
                    planCacheRepository.save(plan);
                    log.info("Set initial plan as baseline for query hash {}", queryHash);
                }
            }

            return plan;

        } catch (Exception e) {
            log.error("Failed to capture plan for query on connection {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Failed to capture execution plan: " + e.getMessage(), e);
        }
    }

    /**
     * Execute EXPLAIN command and return the plan
     */
    private Map<String, Object> executePlan(Connection connection, String query, String dbType, boolean analyze)
            throws Exception {

        // dbType is already normalized to canonical name by caller
        String explainQuery;
        if ("postgres".equals(dbType)) {
            explainQuery = analyze
                    ? "EXPLAIN (ANALYZE, COSTS, VERBOSE, BUFFERS, FORMAT JSON) " + query
                    : "EXPLAIN (COSTS, VERBOSE, FORMAT JSON) " + query;
        } else if ("mysql".equals(dbType)) {
            explainQuery = analyze
                    ? "EXPLAIN ANALYZE " + query
                    : "EXPLAIN FORMAT=JSON " + query;
        } else {
            throw new UnsupportedOperationException("Database type not supported: " + dbType);
        }

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(explainQuery)) {

            StringBuilder planText = new StringBuilder();
            while (rs.next()) {
                planText.append(rs.getString(1));
            }

            String planString = planText.toString();
            if (planString.isBlank()) {
                return Collections.emptyMap();
            }

            // Parse JSON
            if (planString.trim().startsWith("[")) {
                // PostgreSQL returns array
                List<Map<String, Object>> planArray = objectMapper.readValue(planString,
                        new TypeReference<List<Map<String, Object>>>() {});
                if (!planArray.isEmpty()) {
                    return planArray.get(0);
                }
            } else if (planString.trim().startsWith("{")) {
                return objectMapper.readValue(planString, new TypeReference<Map<String, Object>>() {});
            } else {
                // MySQL EXPLAIN ANALYZE returns text format
                return Map.of("plan_text", planString);
            }

            return Collections.emptyMap();
        }
    }

    /**
     * Analyze plan characteristics
     */
    private PlanAnalysis analyzePlan(Map<String, Object> planJson, String dbType) {
        PlanAnalysis analysis = new PlanAnalysis();

        try {
            if ("postgres".equals(dbType)) {
                analyzePostgresPlan(planJson, analysis);
            } else if ("mysql".equals(dbType)) {
                analyzeMySQLPlan(planJson, analysis);
            }
        } catch (Exception e) {
            log.warn("Error analyzing plan: {}", e.getMessage());
        }

        // Generate summary
        analysis.summary = generatePlanSummary(analysis);

        // Generate warnings and recommendations
        generateWarningsAndRecommendations(analysis);

        return analysis;
    }

    @SuppressWarnings("unchecked")
    private void analyzePostgresPlan(Map<String, Object> planJson, PlanAnalysis analysis) {
        Map<String, Object> plan = (Map<String, Object>) planJson.get("Plan");
        if (plan == null) {
            plan = planJson;
        }

        // Extract top-level metrics
        if (plan.get("Total Cost") != null) {
            analysis.estimatedCost = ((Number) plan.get("Total Cost")).doubleValue();
        }
        if (plan.get("Plan Rows") != null) {
            analysis.estimatedRows = ((Number) plan.get("Plan Rows")).longValue();
        }
        if (plan.get("Actual Total Time") != null) {
            analysis.actualTimeMs = ((Number) plan.get("Actual Total Time")).doubleValue();
        }
        if (plan.get("Actual Rows") != null) {
            analysis.actualRows = ((Number) plan.get("Actual Rows")).longValue();
        }

        // Recursively analyze plan nodes
        analyzePlanNode(plan, analysis);
    }

    @SuppressWarnings("unchecked")
    private void analyzePlanNode(Map<String, Object> node, PlanAnalysis analysis) {
        if (node == null) return;

        String nodeType = (String) node.get("Node Type");
        if (nodeType != null) {
            analysis.operations.add(nodeType);

            switch (nodeType) {
                case "Seq Scan" -> {
                    analysis.usesSeqScan = true;
                    String tableName = (String) node.get("Relation Name");
                    if (tableName != null) {
                        analysis.tablesInvolved.add(tableName);
                        analysis.seqScanTables.add(tableName);
                    }
                }
                case "Index Scan", "Index Only Scan" -> {
                    analysis.usesIndexScan = true;
                    String tableName = (String) node.get("Relation Name");
                    String indexName = (String) node.get("Index Name");
                    if (tableName != null) analysis.tablesInvolved.add(tableName);
                    if (indexName != null) analysis.indexesUsed.add(indexName);
                }
                case "Bitmap Heap Scan", "Bitmap Index Scan" -> {
                    analysis.usesBitmapScan = true;
                    String tableName = (String) node.get("Relation Name");
                    String indexName = (String) node.get("Index Name");
                    if (tableName != null) analysis.tablesInvolved.add(tableName);
                    if (indexName != null) analysis.indexesUsed.add(indexName);
                }
                case "Nested Loop" -> analysis.usesNestedLoop = true;
                case "Hash Join" -> analysis.usesHashJoin = true;
                case "Merge Join" -> analysis.usesMergeJoin = true;
                case "Sort" -> analysis.usesSort = true;
                case "Materialize" -> analysis.usesMaterialize = true;
            }
        }

        // Process child plans
        Object plans = node.get("Plans");
        if (plans instanceof List) {
            for (Object child : (List<?>) plans) {
                if (child instanceof Map) {
                    analyzePlanNode((Map<String, Object>) child, analysis);
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void analyzeMySQLPlan(Map<String, Object> planJson, PlanAnalysis analysis) {
        // Handle text format from EXPLAIN ANALYZE
        if (planJson.containsKey("plan_text")) {
            String planText = (String) planJson.get("plan_text");
            // Parse text-based plan
            if (planText.contains("table scan")) analysis.usesSeqScan = true;
            if (planText.contains("index lookup")) analysis.usesIndexScan = true;
            if (planText.contains("nested loop")) analysis.usesNestedLoop = true;
            if (planText.contains("hash join")) analysis.usesHashJoin = true;

            // Extract actual time if present
            Pattern timePattern = Pattern.compile("actual time=(\\d+\\.?\\d*)");
            Matcher matcher = timePattern.matcher(planText);
            if (matcher.find()) {
                analysis.actualTimeMs = Double.parseDouble(matcher.group(1));
            }
            return;
        }

        // Handle JSON format
        Map<String, Object> queryBlock = (Map<String, Object>) planJson.get("query_block");
        if (queryBlock == null) return;

        Object costInfo = queryBlock.get("cost_info");
        if (costInfo instanceof Map) {
            Map<String, Object> cost = (Map<String, Object>) costInfo;
            if (cost.get("query_cost") != null) {
                analysis.estimatedCost = Double.parseDouble(cost.get("query_cost").toString());
            }
        }

        // Analyze table access
        Object table = queryBlock.get("table");
        if (table instanceof Map) {
            analyzeMySQLTable((Map<String, Object>) table, analysis);
        }

        // Analyze nested loop (if present)
        Object nestedLoop = queryBlock.get("nested_loop");
        if (nestedLoop instanceof List) {
            analysis.usesNestedLoop = true;
            for (Object item : (List<?>) nestedLoop) {
                if (item instanceof Map) {
                    Map<String, Object> loopItem = (Map<String, Object>) item;
                    if (loopItem.get("table") != null) {
                        analyzeMySQLTable((Map<String, Object>) loopItem.get("table"), analysis);
                    }
                }
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void analyzeMySQLTable(Map<String, Object> table, PlanAnalysis analysis) {
        String tableName = (String) table.get("table_name");
        if (tableName != null) {
            analysis.tablesInvolved.add(tableName);
        }

        String accessType = (String) table.get("access_type");
        if (accessType != null) {
            switch (accessType.toUpperCase()) {
                case "ALL" -> {
                    analysis.usesSeqScan = true;
                    if (tableName != null) analysis.seqScanTables.add(tableName);
                }
                case "INDEX", "REF", "EQ_REF", "RANGE" -> analysis.usesIndexScan = true;
            }
        }

        String key = (String) table.get("key");
        if (key != null && !key.isEmpty()) {
            analysis.indexesUsed.add(key);
        }

        Object rowsExamined = table.get("rows_examined_per_scan");
        if (rowsExamined != null) {
            analysis.estimatedRows = ((Number) rowsExamined).longValue();
        }
    }

    private String generatePlanSummary(PlanAnalysis analysis) {
        List<String> parts = new ArrayList<>();

        if (analysis.usesSeqScan && !analysis.seqScanTables.isEmpty()) {
            parts.add("Sequential scan on: " + String.join(", ", analysis.seqScanTables));
        }
        if (analysis.usesIndexScan) {
            parts.add("Uses index scan");
        }
        if (analysis.usesHashJoin) {
            parts.add("Hash join");
        }
        if (analysis.usesNestedLoop) {
            parts.add("Nested loop");
        }
        if (analysis.usesMergeJoin) {
            parts.add("Merge join");
        }
        if (analysis.usesSort) {
            parts.add("Sort operation");
        }

        if (analysis.estimatedCost != null) {
            parts.add(String.format("Cost: %.2f", analysis.estimatedCost));
        }
        if (analysis.actualTimeMs != null) {
            parts.add(String.format("Time: %.2fms", analysis.actualTimeMs));
        }

        return parts.isEmpty() ? "No plan details available" : String.join("; ", parts);
    }

    private void generateWarningsAndRecommendations(PlanAnalysis analysis) {
        // Warnings for sequential scans on potentially large tables
        if (analysis.usesSeqScan && !analysis.seqScanTables.isEmpty()) {
            analysis.warnings.add("Sequential scan detected on: " + String.join(", ", analysis.seqScanTables));
            analysis.recommendations.add("Consider adding indexes on frequently filtered columns");
        }

        // Warning for nested loop with seq scan
        if (analysis.usesNestedLoop && analysis.usesSeqScan) {
            analysis.warnings.add("Nested loop with sequential scan may be slow for large datasets");
        }

        // Warning for sort operations
        if (analysis.usesSort && analysis.estimatedRows != null && analysis.estimatedRows > 10000) {
            analysis.warnings.add("Sort operation on large result set (" + analysis.estimatedRows + " rows)");
            analysis.recommendations.add("Consider adding index on ORDER BY columns");
        }
    }

    /**
     * Compare two plans and generate comparison result
     */
    public QueryPlanComparison comparePlans(QueryPlanCache baseline, QueryPlanCache current) {
        QueryPlanComparison comparison = QueryPlanComparison.builder()
                .id(UUID.randomUUID().toString())
                .connectionId(current.getConnectionId())
                .queryHash(current.getQueryHash())
                .baselinePlanId(baseline.getId())
                .currentPlanId(current.getId())
                .comparedAt(LocalDateTime.now())
                .build();

        // Check if plan structure changed
        boolean planChanged = !Objects.equals(baseline.getPlanHash(), current.getPlanHash());
        comparison.setPlanChanged(planChanged);

        // Calculate cost change
        if (baseline.getEstimatedCost() != null && current.getEstimatedCost() != null
                && baseline.getEstimatedCost() > 0) {
            double costChange = ((current.getEstimatedCost() - baseline.getEstimatedCost())
                    / baseline.getEstimatedCost()) * 100;
            comparison.setCostChangePercent(costChange);
            comparison.setCostChanged(Math.abs(costChange) > 5.0); // 5% threshold
        }

        // Calculate rows change
        if (baseline.getEstimatedRows() != null && current.getEstimatedRows() != null
                && baseline.getEstimatedRows() > 0) {
            double rowsChange = ((current.getEstimatedRows() - baseline.getEstimatedRows())
                    / (double) baseline.getEstimatedRows()) * 100;
            comparison.setEstimatedRowsChangePercent(rowsChange);
        }

        // Detect new sequential scans
        List<String> newSeqScans = new ArrayList<>();
        if (current.getTablesInvolved() != null && Boolean.TRUE.equals(current.getUsesSeqScan())
                && !Boolean.TRUE.equals(baseline.getUsesSeqScan())) {
            // Current uses seq scan but baseline didn't
            Set<String> baselineIndexTables = baseline.getTablesInvolved() != null
                    ? new HashSet<>(Arrays.asList(baseline.getTablesInvolved()))
                    : Collections.emptySet();
            for (String table : current.getTablesInvolved()) {
                if (baselineIndexTables.contains(table)) {
                    newSeqScans.add(table);
                }
            }
        }
        comparison.setNewSeqScans(newSeqScans.toArray(new String[0]));

        // Detect lost index scans
        List<String> lostIndexScans = new ArrayList<>();
        if (baseline.getIndexesUsed() != null && current.getIndexesUsed() != null) {
            Set<String> currentIndexes = new HashSet<>(Arrays.asList(current.getIndexesUsed()));
            for (String idx : baseline.getIndexesUsed()) {
                if (!currentIndexes.contains(idx)) {
                    lostIndexScans.add(idx);
                }
            }
        }
        comparison.setLostIndexScans(lostIndexScans.toArray(new String[0]));

        // Determine severity and regression status
        Severity severity = Severity.INFO;
        boolean isRegression = false;

        Double costChange = comparison.getCostChangePercent();
        if (costChange != null) {
            if (costChange >= CRITICAL_COST_CHANGE_THRESHOLD) {
                severity = Severity.CRITICAL;
                isRegression = true;
            } else if (costChange >= SIGNIFICANT_COST_CHANGE_THRESHOLD) {
                severity = Severity.WARNING;
                isRegression = true;
            }
        }

        if (!newSeqScans.isEmpty()) {
            severity = severity == Severity.INFO ? Severity.WARNING : severity;
            isRegression = true;
        }

        if (!lostIndexScans.isEmpty()) {
            severity = severity == Severity.INFO ? Severity.WARNING : severity;
            isRegression = true;
        }

        comparison.setSeverity(severity);
        comparison.setIsRegression(isRegression);
        comparison.setIsAcknowledged(false);

        // Generate analysis notes
        comparison.setAnalysisNotes(comparison.getSummary());

        // Generate suggested actions
        List<String> actions = new ArrayList<>();
        if (!newSeqScans.isEmpty()) {
            actions.add("Review indexes on tables: " + String.join(", ", newSeqScans));
        }
        if (!lostIndexScans.isEmpty()) {
            actions.add("Check why indexes are no longer used: " + String.join(", ", lostIndexScans));
        }
        if (Boolean.TRUE.equals(isRegression)) {
            actions.add("Compare query execution patterns and data distribution");
            actions.add("Check for recent schema changes or statistics updates");
        }
        comparison.setSuggestedActions(actions.toArray(new String[0]));

        return comparison;
    }

    /**
     * Set a plan as the baseline for a query
     */
    @Transactional
    /**
     * The connection a cached plan belongs to, for authorizing an endpoint keyed
     * only on a plan id. Empty when the id does not exist.
     */
    public java.util.Optional<String> findConnectionIdForPlan(String planId) {
        return planCacheRepository.findById(planId).map(p -> p.getConnectionId());
    }

    public void setBaseline(String planId) {
        planCacheRepository.findById(planId).ifPresent(plan -> {
            // Clear existing baseline
            planCacheRepository.clearBaseline(plan.getConnectionId(), plan.getQueryHash());
            // Set new baseline
            plan.setIsBaseline(true);
            planCacheRepository.save(plan);
            log.info("Set plan {} as baseline for query hash {}", planId, plan.getQueryHash());
        });
    }

    /**
     * Normalize a query for consistent hashing
     */
    private String normalizeQuery(String query) {
        if (query == null) return "";

        // Remove extra whitespace
        String normalized = query.replaceAll("\\s+", " ").trim();

        // Convert to lowercase (for case-insensitive comparison)
        normalized = normalized.toLowerCase();

        // Replace literals with placeholders
        // Numbers
        normalized = normalized.replaceAll("\\b\\d+\\.?\\d*\\b", "?");
        // Quoted strings
        normalized = normalized.replaceAll("'[^']*'", "?");
        normalized = normalized.replaceAll("\"[^\"]*\"", "?");

        return normalized;
    }

    private String calculateHash(String content) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(content.getBytes(StandardCharsets.UTF_8));
            StringBuilder hexString = new StringBuilder();
            for (byte b : hash) {
                String hex = Integer.toHexString(0xff & b);
                if (hex.length() == 1) hexString.append('0');
                hexString.append(hex);
            }
            return hexString.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    // ==================== Query Methods ====================

    /**
     * Get recent plans for a connection
     */
    public List<QueryPlanCache> getRecentPlans(String connectionId) {
        return planCacheRepository.findTop50ByConnectionIdOrderByCapturedAtDesc(connectionId);
    }

    /**
     * Get plans for a specific query
     */
    public List<QueryPlanCache> getPlansForQuery(String connectionId, String queryHash) {
        return planCacheRepository.findByConnectionIdAndQueryHashOrderByCapturedAtDesc(connectionId, queryHash);
    }

    /**
     * Get plan regressions
     */
    public List<QueryPlanComparison> getRegressions(String connectionId) {
        return comparisonRepository.findByConnectionIdAndIsRegressionTrueOrderByComparedAtDesc(connectionId);
    }

    /**
     * Get unacknowledged regressions
     */
    public List<QueryPlanComparison> getUnacknowledgedRegressions(String connectionId) {
        return comparisonRepository.findByConnectionIdAndIsRegressionTrueAndIsAcknowledgedFalseOrderByComparedAtDesc(connectionId);
    }

    /**
     * Acknowledge regressions
     */
    @Transactional
    public int acknowledgeRegressions(List<String> comparisonIds, String acknowledgedBy) {
        return comparisonRepository.acknowledgeByIds(comparisonIds, acknowledgedBy, LocalDateTime.now());
    }

    /**
     * True when every given comparison id belongs to {@code connectionId}. The ids arrive
     * in the request body, so the caller's authorization on the path connection does not
     * constrain them — without this a caller could acknowledge another connection's plan
     * regressions. An id that resolves to nothing fails too, so unknown ids cannot be
     * mixed into an otherwise valid batch.
     */
    public boolean allComparisonsBelongTo(String connectionId, List<String> comparisonIds) {
        if (comparisonIds == null || comparisonIds.isEmpty()) {
            return true;
        }
        List<QueryPlanComparison> found = comparisonRepository.findAllById(comparisonIds);
        return found.size() == comparisonIds.stream().distinct().count()
            && found.stream().allMatch(c -> java.util.Objects.equals(connectionId, c.getConnectionId()));
    }

    /**
     * Get plan statistics
     */
    public Map<String, Object> getPlanStats(String connectionId) {
        Map<String, Object> stats = new LinkedHashMap<>();
        stats.put("totalRegressions", comparisonRepository.countByConnectionIdAndIsRegressionTrue(connectionId));
        stats.put("unacknowledgedRegressions",
                comparisonRepository.countByConnectionIdAndIsRegressionTrueAndIsAcknowledgedFalse(connectionId));
        return stats;
    }

    /**
     * Helper class for plan analysis
     */
    private static class PlanAnalysis {
        Double estimatedCost;
        Long estimatedRows;
        Double actualTimeMs;
        Long actualRows;
        boolean usesSeqScan = false;
        boolean usesIndexScan = false;
        boolean usesBitmapScan = false;
        boolean usesNestedLoop = false;
        boolean usesHashJoin = false;
        boolean usesMergeJoin = false;
        boolean usesSort = false;
        boolean usesMaterialize = false;
        Set<String> tablesInvolved = new LinkedHashSet<>();
        Set<String> indexesUsed = new LinkedHashSet<>();
        Set<String> seqScanTables = new LinkedHashSet<>();
        Set<String> operations = new LinkedHashSet<>();
        List<String> warnings = new ArrayList<>();
        List<String> recommendations = new ArrayList<>();
        String summary;
    }
}
