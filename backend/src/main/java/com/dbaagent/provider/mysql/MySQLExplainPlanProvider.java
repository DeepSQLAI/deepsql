package com.dbaagent.provider.mysql;

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
 * MySQL implementation of ExplainPlanProvider.
 * Handles MySQL-specific EXPLAIN parsing (JSON and traditional formats).
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MySQLExplainPlanProvider implements ExplainPlanProvider {

    private static final String DATABASE_TYPE = "mysql";
    private final ObjectMapper objectMapper;

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<Map<String, Object>> executeExplain(Connection connection, String query, boolean analyze) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();

        // Try JSON format first (MySQL 5.7+)
        String explainQuery = analyze ?
            "EXPLAIN ANALYZE " + query :
            "EXPLAIN FORMAT=JSON " + query;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(explainQuery)) {

            if (rs.next()) {
                String jsonPlan = rs.getString(1);
                try {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> planMap = objectMapper.readValue(jsonPlan, Map.class);
                    results.add(planMap);
                } catch (Exception e) {
                    log.warn("Could not parse JSON explain, using text: {}", e.getMessage());
                    // Return raw text
                    Map<String, Object> raw = new HashMap<>();
                    raw.put("raw", jsonPlan);
                    results.add(raw);
                }
            }
        } catch (SQLException e) {
            // Fall back to traditional EXPLAIN for older MySQL or EXPLAIN ANALYZE
            log.debug("JSON explain failed, falling back to traditional: {}", e.getMessage());
            results = executeTraditionalExplain(connection, query);
        }

        return results;
    }

    private List<Map<String, Object>> executeTraditionalExplain(Connection connection, String query) throws SQLException {
        List<Map<String, Object>> results = new ArrayList<>();

        String explainQuery = "EXPLAIN " + query;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(explainQuery)) {

            ResultSetMetaData meta = rs.getMetaData();
            int columnCount = meta.getColumnCount();

            while (rs.next()) {
                Map<String, Object> row = new HashMap<>();
                for (int i = 1; i <= columnCount; i++) {
                    row.put(meta.getColumnName(i), rs.getObject(i));
                }
                results.add(row);
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

        Map<String, Object> first = explainResults.get(0);

        // EXPLAIN ANALYZE returns a TREE-format text blob (not JSON). It was
        // stashed under "raw" by executeExplain when the JSON parse failed.
        if (first.containsKey("raw") && first.get("raw") != null) {
            return parseTreePlan(String.valueOf(first.get("raw")));
        }

        // Check if this is JSON format with query_block
        if (first.containsKey("query_block")) {
            return parseJsonPlan((Map<String, Object>) first.get("query_block"));
        }

        // Traditional EXPLAIN format
        return parseTraditionalPlan(explainResults);
    }

    // ── EXPLAIN ANALYZE (TREE format) parsing ────────────────────────────────
    // MySQL 8's EXPLAIN ANALYZE emits an indented text tree, e.g.:
    //   -> Nested loop left join  (cost=1062 rows=485) (actual time=4.12..35 rows=900 loops=1)
    //       -> Index lookup on hs using hs_bookingid_idx (booking_id=ub.id)  (cost=0.398 rows=1.59) (actual time=...)
    // Nesting is by the column where "->" appears; the trailing parens carry
    // planner cost/rows and (when executed) actual time/rows/loops.
    private static final Pattern TREE_ACTUAL =
        Pattern.compile("\\(actual time=([0-9.]+)\\.\\.([0-9.]+)\\s+rows=([0-9.eE+]+)\\s+loops=([0-9]+)\\)");
    private static final Pattern TREE_COST =
        Pattern.compile("\\(cost=([0-9.eE+\\-]+)\\s+rows=([0-9.eE+\\-]+)\\)");
    // "using <idx>" optionally followed by the lookup/join predicate "(a=b)".
    private static final Pattern TREE_USING = Pattern.compile("\\busing (\\w+)(?:\\s*\\(([^)]+)\\))?");
    private static final Pattern TREE_ON = Pattern.compile("\\bon (\\w+)(?:\\s*\\(([^)]+)\\))?");

    private ExplainPlanNode parseTreePlan(String treeText) {
        ExplainPlanNode root = new ExplainPlanNode();
        root.setNodeType("Query");
        if (treeText == null || treeText.isBlank()) {
            return root;
        }

        Deque<ExplainPlanNode> nodeStack = new ArrayDeque<>();
        Deque<Integer> indentStack = new ArrayDeque<>();
        nodeStack.push(root);
        indentStack.push(-1);

        for (String line : treeText.split("\\r?\\n")) {
            int arrow = line.indexOf("->");
            if (arrow < 0 || line.isBlank()) {
                continue; // skip blank/continuation lines
            }
            int indent = arrow;
            ExplainPlanNode node = parseTreeLine(line.substring(arrow + 2).trim());

            // Pop ancestors until we find one shallower than this node.
            while (indentStack.size() > 1 && indent <= indentStack.peek()) {
                nodeStack.pop();
                indentStack.pop();
            }
            nodeStack.peek().addChild(node);
            nodeStack.push(node);
            indentStack.push(indent);
        }

        // Collapse the synthetic root when the plan has a single real top node.
        if (root.getChildren() != null && root.getChildren().size() == 1) {
            return root.getChildren().get(0);
        }
        return root;
    }

    private ExplainPlanNode parseTreeLine(String desc) {
        ExplainPlanNode node = new ExplainPlanNode();

        Matcher actual = TREE_ACTUAL.matcher(desc);
        if (actual.find()) {
            node.setActualStartupTime(parseDoubleSafe(actual.group(1)));
            node.setActualTotalTime(parseDoubleSafe(actual.group(2)));
            Double rows = parseDoubleSafe(actual.group(3));
            if (rows != null) node.setActualRows(Math.round(rows));
            node.setActualLoops(parseIntSafe(actual.group(4)));
        }

        Matcher cost = TREE_COST.matcher(desc);
        if (cost.find()) {
            node.setTotalCost(parseDoubleSafe(cost.group(1)));
            Double rows = parseDoubleSafe(cost.group(2));
            if (rows != null) {
                node.setPlanRows((int) Math.min(Integer.MAX_VALUE, Math.round(rows)));
            }
        }

        Matcher using = TREE_USING.matcher(desc);
        if (using.find()) {
            node.setKey(using.group(1));
            if (using.group(2) != null) node.setIndexCondition(using.group(2).trim());
        }
        Matcher on = TREE_ON.matcher(desc);
        if (on.find()) {
            node.setTableName(on.group(1));
            // Predicate attached directly to the table access (e.g. a scan
            // filter or join key when no named index is used).
            if (on.group(2) != null && node.getIndexCondition() == null) {
                node.setIndexCondition(on.group(2).trim());
            }
        }

        // Label = everything before the first "(cost=" / "(actual" block.
        int cut = desc.length();
        int ci = desc.indexOf("(cost=");
        int ai = desc.indexOf("(actual");
        if (ci >= 0) cut = Math.min(cut, ci);
        if (ai >= 0) cut = Math.min(cut, ai);
        String label = desc.substring(0, cut).trim();

        // "<op>: <detail>" — keep the detail (aggregate exprs, sort keys, filter
        // condition) so the UI can show what the step actually operates on.
        int colon = label.indexOf(':');
        if (colon > 0) {
            String detail = label.substring(colon + 1).trim();
            if (label.startsWith("Filter:")) {
                node.setFilter(detail);
            } else {
                node.setExtra(detail);
            }
        }
        node.setNodeType(deriveTreeNodeType(label));
        return node;
    }

    /** Leading phrase of a TREE line, e.g. "Index lookup on hs using …" → "Index lookup". */
    private String deriveTreeNodeType(String label) {
        if (label == null || label.isBlank()) return "Node";
        String s = label;
        int colon = s.indexOf(':');
        if (colon > 0) s = s.substring(0, colon);
        int on = s.indexOf(" on ");
        if (on > 0) s = s.substring(0, on);
        int paren = s.indexOf('(');
        if (paren > 0) s = s.substring(0, paren);
        s = s.trim();
        return s.isEmpty() ? "Node" : s;
    }

    private static Double parseDoubleSafe(String s) {
        try { return s == null ? null : Double.parseDouble(s); }
        catch (NumberFormatException e) { return null; }
    }

    private static Integer parseIntSafe(String s) {
        try { return s == null ? null : Integer.parseInt(s); }
        catch (NumberFormatException e) { return null; }
    }

    @SuppressWarnings("unchecked")
    private ExplainPlanNode parseJsonPlan(Map<String, Object> queryBlock) {
        ExplainPlanNode node = new ExplainPlanNode();

        node.setNodeType("Query Block");

        if (queryBlock.containsKey("select_id")) {
            node.setNodeId(((Number) queryBlock.get("select_id")).intValue());
        }

        if (queryBlock.containsKey("cost_info")) {
            Map<String, Object> costInfo = (Map<String, Object>) queryBlock.get("cost_info");
            if (costInfo.containsKey("query_cost")) {
                node.setTotalCost(Double.parseDouble(costInfo.get("query_cost").toString()));
            }
        }

        // Parse nested structure
        if (queryBlock.containsKey("nested_loop")) {
            List<Map<String, Object>> nestedLoop = (List<Map<String, Object>>) queryBlock.get("nested_loop");
            node.setNodeType("Nested Loop");
            for (Map<String, Object> item : nestedLoop) {
                if (item.containsKey("table")) {
                    ExplainPlanNode child = parseTableNode((Map<String, Object>) item.get("table"));
                    node.addChild(child);
                }
            }
        }

        if (queryBlock.containsKey("table")) {
            ExplainPlanNode tableNode = parseTableNode((Map<String, Object>) queryBlock.get("table"));
            node.addChild(tableNode);
        }

        if (queryBlock.containsKey("ordering_operation")) {
            Map<String, Object> ordering = (Map<String, Object>) queryBlock.get("ordering_operation");
            node.setNodeType("Sort");
            if (ordering.containsKey("nested_loop")) {
                List<Map<String, Object>> nestedLoop = (List<Map<String, Object>>) ordering.get("nested_loop");
                for (Map<String, Object> item : nestedLoop) {
                    if (item.containsKey("table")) {
                        ExplainPlanNode child = parseTableNode((Map<String, Object>) item.get("table"));
                        node.addChild(child);
                    }
                }
            }
        }

        return node;
    }

    @SuppressWarnings("unchecked")
    private ExplainPlanNode parseTableNode(Map<String, Object> table) {
        ExplainPlanNode node = new ExplainPlanNode();

        node.setTableName((String) table.get("table_name"));
        node.setAccessType((String) table.get("access_type"));
        node.setKey((String) table.get("key"));
        node.setPossibleKeys(table.get("possible_keys") != null ?
            table.get("possible_keys").toString() : null);

        if (table.containsKey("rows_examined_per_scan")) {
            node.setPlanRows(((Number) table.get("rows_examined_per_scan")).intValue());
        }

        if (table.containsKey("cost_info")) {
            Map<String, Object> costInfo = (Map<String, Object>) table.get("cost_info");
            if (costInfo.containsKey("read_cost")) {
                node.setTotalCost(Double.parseDouble(costInfo.get("read_cost").toString()));
            }
        }

        // Determine node type based on access_type
        String accessType = (String) table.get("access_type");
        if (accessType != null) {
            node.setNodeType(mapAccessTypeToNodeType(accessType));
        }

        return node;
    }

    private ExplainPlanNode parseTraditionalPlan(List<Map<String, Object>> rows) {
        ExplainPlanNode root = new ExplainPlanNode();
        root.setNodeType("Query");

        for (Map<String, Object> row : rows) {
            ExplainPlanNode node = new ExplainPlanNode();

            node.setNodeId(row.get("id") != null ? ((Number) row.get("id")).intValue() : null);
            node.setSelectType((String) row.get("select_type"));
            node.setTableName((String) row.get("table"));
            node.setAccessType((String) row.get("type"));
            node.setPossibleKeys((String) row.get("possible_keys"));
            node.setKey((String) row.get("key"));
            node.setKeyLength((String) row.get("key_len"));
            node.setRef((String) row.get("ref"));
            node.setExtra((String) row.get("Extra"));

            if (row.get("rows") != null) {
                node.setPlanRows(((Number) row.get("rows")).intValue());
            }

            // Set node type based on access type
            String accessType = node.getAccessType();
            if (accessType != null) {
                node.setNodeType(mapAccessTypeToNodeType(accessType));
            }

            root.addChild(node);
        }

        return root;
    }

    private String mapAccessTypeToNodeType(String accessType) {
        if (accessType == null) return "Unknown";
        return switch (accessType.toUpperCase()) {
            case "ALL" -> "Full Table Scan";
            case "INDEX" -> "Full Index Scan";
            case "RANGE" -> "Index Range Scan";
            case "REF" -> "Index Lookup";
            case "EQ_REF" -> "Unique Index Lookup";
            case "CONST", "SYSTEM" -> "Constant";
            case "REF_OR_NULL" -> "Index Lookup (with NULL)";
            case "FULLTEXT" -> "Fulltext Search";
            case "INDEX_MERGE" -> "Index Merge";
            default -> accessType;
        };
    }

    @Override
    public void detectIssues(ExplainPlanNode planTree, ExplainPlanAnalysis analysis) {
        detectIssuesRecursive(planTree, analysis);
    }

    private void detectIssuesRecursive(ExplainPlanNode node, ExplainPlanAnalysis analysis) {
        if (node == null) return;

        // Check for full table scan
        if ("ALL".equalsIgnoreCase(node.getAccessType()) || node.isFullTableScan()) {
            Long rows = node.getEstimatedRows();
            if (rows > 1000) {
                PerformanceIssue issue = PerformanceIssue.createFullTableScanIssue(
                    node.getTableName(),
                    rows,
                    node.getExtra()
                );
                analysis.addIssue(issue);
            }
        }

        // Check for filesort
        if (node.isUsingFilesort()) {
            Long rows = node.getEstimatedRows();
            PerformanceIssue issue = PerformanceIssue.createFilesortIssue(
                node.getTableName(),
                rows,
                "ORDER BY columns"
            );
            analysis.addIssue(issue);
        }

        // Check for temporary table
        if (node.isUsingTemporary()) {
            PerformanceIssue issue = PerformanceIssue.createTemporaryTableIssue(
                node.getTableName(),
                "GROUP BY or DISTINCT operation"
            );
            analysis.addIssue(issue);
        }

        // Check children recursively
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

        // Recommend index for full table scans
        if ("ALL".equalsIgnoreCase(node.getAccessType()) && node.getTableName() != null) {
            Long rows = node.getEstimatedRows();
            if (rows > 1000) {
                String column = extractWhereColumn(query, node.getTableName());
                if (column != null) {
                    IndexRecommendation rec = IndexRecommendation.builder()
                        .tableName(node.getTableName())
                        .columns(Collections.singletonList(column))
                        .indexType("BTREE")
                        .priority(rows > 100000 ?
                            IndexRecommendation.RecommendationPriority.CRITICAL :
                            IndexRecommendation.RecommendationPriority.HIGH)
                        .reasoning(String.format(
                            "Full table scan on %,d rows with filter on %s",
                            rows, column))
                        .suggestedSQL(String.format(
                            "CREATE INDEX idx_%s_%s ON %s(%s)",
                            node.getTableName(), column, node.getTableName(), column))
                        .build();
                    recommendations.add(rec);
                }
            }
        }

        // Process children
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                generateRecommendationsRecursive(child, query, recommendations);
            }
        }
    }

    private String extractWhereColumn(String query, String tableName) {
        // Simple pattern matching for WHERE clause columns
        Pattern pattern = Pattern.compile(
            "WHERE\\s+(?:" + tableName + "\\.)?([a-zA-Z_][a-zA-Z0-9_]*)\\s*[=<>]",
            Pattern.CASE_INSENSITIVE
        );
        Matcher matcher = pattern.matcher(query);
        if (matcher.find()) {
            return matcher.group(1);
        }
        return null;
    }

    @Override
    public String getExplainFormat(boolean analyze) {
        return analyze ? "EXPLAIN ANALYZE " : "EXPLAIN FORMAT=JSON ";
    }

    @Override
    @SuppressWarnings("unchecked")
    public void analyzeQuery(Connection connection, String query, boolean analyze, ExplainPlanAnalysis analysis) throws SQLException {
        List<Map<String, Object>> results = executeExplain(connection, query, analyze);
        analysis.setPlanJson(results.toString());

        ExplainPlanNode planTree = parseExplainResult(results);
        analysis.setPlanTree(planTree);

        // Calculate costs
        analysis.setEstimatedCost(planTree.getTotalCost());

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
