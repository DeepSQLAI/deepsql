package com.dbaagent.provider.postgres;

import com.dbaagent.model.*;
import com.dbaagent.provider.api.ExplainPlanProvider;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * PostgreSQL implementation of ExplainPlanProvider.
 * Handles PostgreSQL-specific EXPLAIN (ANALYZE, FORMAT JSON) parsing.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class PostgresExplainPlanProvider implements ExplainPlanProvider {

    private static final String DATABASE_TYPE = "postgres";
    private final ObjectMapper objectMapper;

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<Map<String, Object>> executeExplain(Connection connection, String query, boolean analyze) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();

        String explainQuery = analyze ?
            "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " + query :
            "EXPLAIN (FORMAT JSON) " + query;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(explainQuery)) {

            if (rs.next()) {
                String jsonPlan = rs.getString(1);
                try {
                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> planList = objectMapper.readValue(jsonPlan, List.class);
                    results.addAll(planList);
                } catch (Exception e) {
                    log.error("Error parsing EXPLAIN JSON: {}", e.getMessage());
                    Map<String, Object> raw = new HashMap<>();
                    raw.put("raw", jsonPlan);
                    results.add(raw);
                }
            }
        }

        return results;
    }

    @Override
    @SuppressWarnings("unchecked")
    public ExplainPlanNode parseExplainResult(List<Map<String, Object>> explainResults) {
        if (explainResults.isEmpty()) {
            return new ExplainPlanNode();
        }

        Map<String, Object> planRoot = explainResults.get(0);
        Map<String, Object> plan = (Map<String, Object>) planRoot.get("Plan");

        if (plan == null) {
            return new ExplainPlanNode();
        }

        return parsePlanNode(plan, null);
    }

    @SuppressWarnings("unchecked")
    private ExplainPlanNode parsePlanNode(Map<String, Object> planMap, String parentRelationship) {
        ExplainPlanNode node = new ExplainPlanNode();

        // Basic information
        node.setNodeType((String) planMap.get("Node Type"));
        node.setTableName((String) planMap.get("Relation Name"));
        node.setAliasName((String) planMap.get("Alias"));
        node.setParentRelationship(parentRelationship);

        // Costs
        if (planMap.containsKey("Startup Cost")) {
            node.setStartupCost(((Number) planMap.get("Startup Cost")).doubleValue());
        }
        if (planMap.containsKey("Total Cost")) {
            node.setTotalCost(((Number) planMap.get("Total Cost")).doubleValue());
        }
        if (planMap.containsKey("Plan Rows")) {
            node.setPlanRows(((Number) planMap.get("Plan Rows")).intValue());
        }
        if (planMap.containsKey("Plan Width")) {
            node.setPlanWidth(((Number) planMap.get("Plan Width")).intValue());
        }

        // Actual execution metrics (if ANALYZE was used)
        if (planMap.containsKey("Actual Startup Time")) {
            node.setActualStartupTime(((Number) planMap.get("Actual Startup Time")).doubleValue());
        }
        if (planMap.containsKey("Actual Total Time")) {
            node.setActualTotalTime(((Number) planMap.get("Actual Total Time")).doubleValue());
        }
        if (planMap.containsKey("Actual Rows")) {
            node.setActualRows(((Number) planMap.get("Actual Rows")).longValue());
        }
        if (planMap.containsKey("Actual Loops")) {
            node.setActualLoops(((Number) planMap.get("Actual Loops")).intValue());
        }

        // Filter information
        if (planMap.containsKey("Filter")) {
            node.setFilter((String) planMap.get("Filter"));
        }
        if (planMap.containsKey("Rows Removed by Filter")) {
            node.setRowsRemovedByFilter(((Number) planMap.get("Rows Removed by Filter")).longValue());
        }
        if (planMap.containsKey("Index Cond")) {
            node.setIndexCondition((String) planMap.get("Index Cond"));
        }

        // Join information
        if (planMap.containsKey("Join Type")) {
            node.setJoinType((String) planMap.get("Join Type"));
        }

        // Parse child plans recursively
        if (planMap.containsKey("Plans")) {
            List<Map<String, Object>> childPlans = (List<Map<String, Object>>) planMap.get("Plans");
            for (Map<String, Object> childPlan : childPlans) {
                String relationship = (String) childPlan.get("Parent Relationship");
                ExplainPlanNode childNode = parsePlanNode(childPlan, relationship);
                node.addChild(childNode);
            }
        }

        return node;
    }

    @Override
    public void detectIssues(ExplainPlanNode planTree, ExplainPlanAnalysis analysis) {
        detectIssuesRecursive(planTree, analysis);
    }

    private void detectIssuesRecursive(ExplainPlanNode node, ExplainPlanAnalysis analysis) {
        if (node == null) return;

        // Check for full table scans
        if ("Seq Scan".equals(node.getNodeType())) {
            Long rows = node.getActualRows() != null ? node.getActualRows() :
                       node.getPlanRows() != null ? node.getPlanRows().longValue() : 0L;

            if (rows > 1000) {
                PerformanceIssue issue = PerformanceIssue.createFullTableScanIssue(
                    node.getTableName(),
                    rows,
                    node.getFilter()
                );
                analysis.addIssue(issue);
            }
        }

        // Check for poor filter selectivity
        if (node.getRowsRemovedByFilter() != null && node.getActualRows() != null) {
            Long totalRows = node.getActualRows() + node.getRowsRemovedByFilter();
            if (totalRows > 0 && node.getRowsRemovedByFilter() > totalRows * 0.9) {
                PerformanceIssue issue = PerformanceIssue.createPoorSelectivityIssue(
                    node.getTableName(),
                    totalRows,
                    node.getActualRows()
                );
                analysis.addIssue(issue);
            }
        }

        // Check for hash join with large data
        if ("Hash Join".equals(node.getNodeType()) || "Hash".equals(node.getNodeType())) {
            Long rows = node.getActualRows() != null ? node.getActualRows() :
                       node.getPlanRows() != null ? node.getPlanRows().longValue() : 0L;

            if (rows > 100000) {
                PerformanceIssue issue = PerformanceIssue.builder()
                    .severity(PerformanceIssue.Severity.MEDIUM)
                    .type(PerformanceIssue.IssueType.INEFFICIENT_JOIN)
                    .message(String.format("Large hash operation with %,d rows may cause memory pressure", rows))
                    .tableName(node.getTableName())
                    .affectedRows(rows)
                    .technicalDetails(String.format("Node type: %s", node.getNodeType()))
                    .build();
                analysis.addIssue(issue);
            }
        }

        // Check for nested loop with many iterations
        if ("Nested Loop".equals(node.getNodeType()) && node.getActualLoops() != null) {
            if (node.getActualLoops() > 10000) {
                PerformanceIssue issue = PerformanceIssue.builder()
                    .severity(PerformanceIssue.Severity.HIGH)
                    .type(PerformanceIssue.IssueType.INEFFICIENT_JOIN)
                    .message(String.format("Nested loop with %,d iterations may be inefficient", node.getActualLoops()))
                    .technicalDetails(String.format("Loop iterations: %,d", node.getActualLoops()))
                    .build();
                analysis.addIssue(issue);
            }
        }

        // Recursively check children
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                detectIssuesRecursive(child, analysis);
            }
        }
    }

    @Override
    public List<IndexRecommendation> generateIndexRecommendations(ExplainPlanNode planTree, String query) {
        List<IndexRecommendation> recommendations = new ArrayList<>();
        generateRecommendationsRecursive(planTree, query, recommendations);
        return recommendations;
    }

    private void generateRecommendationsRecursive(ExplainPlanNode node, String query, List<IndexRecommendation> recommendations) {
        if (node == null) return;

        // Recommend index for sequential scans
        if ("Seq Scan".equals(node.getNodeType()) && node.getTableName() != null) {
            Long rows = node.getActualRows() != null ? node.getActualRows() :
                       node.getPlanRows() != null ? node.getPlanRows().longValue() : 0L;

            if (rows > 1000 && node.getFilter() != null) {
                String column = extractColumnFromFilter(node.getFilter());

                if (column != null) {
                    IndexRecommendation rec = IndexRecommendation.builder()
                        .tableName(node.getTableName())
                        .columns(Collections.singletonList(column))
                        .indexType("BTREE")
                        .priority(rows > 100000 ?
                            IndexRecommendation.RecommendationPriority.CRITICAL :
                            IndexRecommendation.RecommendationPriority.HIGH)
                        .reasoning(String.format(
                            "Sequential scan on %,d rows with filter on %s",
                            rows, column))
                        .suggestedSQL(String.format(
                            "CREATE INDEX CONCURRENTLY idx_%s_%s ON %s(%s)",
                            node.getTableName(), column, node.getTableName(), column))
                        .build();
                    recommendations.add(rec);
                }
            }
        }

        // Recursively process children
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                generateRecommendationsRecursive(child, query, recommendations);
            }
        }
    }

    private String extractColumnFromFilter(String filter) {
        if (filter == null) return null;

        // Pattern: (column_name = value) or (column_name > value)
        Pattern pattern = Pattern.compile("\\(([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=<>]");
        Matcher matcher = pattern.matcher(filter);

        if (matcher.find()) {
            return matcher.group(1);
        }

        return null;
    }

    @Override
    public String getExplainFormat(boolean analyze) {
        return analyze ? "EXPLAIN (ANALYZE, BUFFERS, FORMAT JSON) " : "EXPLAIN (FORMAT JSON) ";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void analyzeQuery(Connection connection, String query, boolean analyze, ExplainPlanAnalysis analysis) throws SQLException {
        List<Map<String, Object>> results = executeExplain(connection, query, analyze);

        if (!results.isEmpty()) {
            Map<String, Object> planRoot = results.get(0);

            // Extract timing information
            if (planRoot.containsKey("Planning Time")) {
                analysis.setPlanningTimeMs(((Number) planRoot.get("Planning Time")).doubleValue());
            }
            if (planRoot.containsKey("Execution Time")) {
                analysis.setExecutionTimeMs(((Number) planRoot.get("Execution Time")).doubleValue());
                analysis.setTotalTimeMs(
                    (analysis.getPlanningTimeMs() != null ? analysis.getPlanningTimeMs() : 0.0) +
                    analysis.getExecutionTimeMs()
                );
            }
        }

        ExplainPlanNode planTree = parseExplainResult(results);
        analysis.setPlanTree(planTree);

        // Calculate costs
        analysis.setEstimatedCost(planTree.getTotalCost());
        if (planTree.getActualTotalTime() != null) {
            analysis.setActualCost(planTree.getActualTotalTime());
        }

        // Count nodes
        analysis.setNodeCount(countNodes(planTree));

        // Detect issues
        detectIssues(planTree, analysis);

        // Generate recommendations
        List<IndexRecommendation> recommendations = generateIndexRecommendations(planTree, query);
        for (IndexRecommendation rec : recommendations) {
            analysis.addIndexRecommendation(rec);
        }
    }

    private int countNodes(ExplainPlanNode node) {
        if (node == null) return 0;

        int count = 1;
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                count += countNodes(child);
            }
        }
        return count;
    }
}
