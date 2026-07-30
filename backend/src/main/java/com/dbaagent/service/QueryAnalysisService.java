package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.QueryPlan;
import com.dbaagent.model.QueryValidationResult;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.*;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Query analysis and validation service
 * Provides EXPLAIN plan analysis and safety validation for SQL queries
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class QueryAnalysisService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;
    private final ObjectMapper objectMapper = new ObjectMapper();

    // Dangerous operations that require confirmation
    private static final List<String> DANGEROUS_OPERATIONS = List.of(
        "DROP", "TRUNCATE", "ALTER", "DELETE FROM", "UPDATE"
    );

    // DDL operations
    private static final List<String> DDL_OPERATIONS = List.of(
        "CREATE", "DROP", "ALTER", "RENAME", "TRUNCATE"
    );

    /**
     * Validate query for safety before execution
     */
    public QueryValidationResult validateQuery(String sql) {
        QueryValidationResult result = QueryValidationResult.safe();

        if (sql == null || sql.trim().isEmpty()) {
            result.addError("Query is empty");
            return result;
        }

        String normalizedSql = sql.trim().toUpperCase();

        // Determine query type
        String queryType = determineQueryType(normalizedSql);
        result.setQueryType(queryType);

        // Check for dangerous operations
        if (isDangerousOperation(normalizedSql)) {
            result.setRequiresConfirmation(true);
            result.addWarning("This query performs a potentially dangerous operation: " + queryType);
        }

        // Validate UPDATE/DELETE with WHERE clause
        if (normalizedSql.startsWith("UPDATE") || normalizedSql.startsWith("DELETE")) {
            if (!hasWhereClause(normalizedSql)) {
                result.addError("UPDATE/DELETE without WHERE clause is not allowed. Add a WHERE clause to limit affected rows.");
                result.setRecommendation("Add WHERE clause: e.g., WHERE id = ?");
            }
        }

        // Check for DDL operations
        if (isDDL(normalizedSql)) {
            result.setRequiresConfirmation(true);
            result.addWarning("This is a DDL operation that will modify database structure");
        }

        // Check for multiple statements
        if (hasMultipleStatements(sql)) {
            result.addError("Multiple SQL statements are not allowed for safety reasons");
        }

        // Check for SQL injection patterns
        validateSqlInjectionPatterns(sql, result);
        
        // Check for common syntax errors in dashboard queries
        validateDashboardQuerySyntax(sql, result);

        return result;
    }

    /**
     * Get EXPLAIN plan for a query
     */
    public QueryPlan explainQuery(String connectionId, String sql) {
        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            try (Connection connection = connectionService.getConnection(connectionId, connRequest)) {
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

                if ("postgres".equalsIgnoreCase(dbType) || "postgresql".equalsIgnoreCase(dbType)) {
                    return explainPostgres(connection, sql);
                } else {
                    return explainMySQL(connection, sql);
                }
            }
        } catch (Exception e) {
            log.error("Failed to explain query: {}", e.getMessage());
            return QueryPlan.builder()
                .warnings("Failed to generate query plan: " + e.getMessage())
                .build();
        }
    }

    /**
     * Get EXPLAIN plan for PostgreSQL
     */
    private QueryPlan explainPostgres(Connection connection, String sql) {
        try {
            // Get JSON format explain
            String explainSql = "EXPLAIN (FORMAT JSON, VERBOSE) " + sql;
            String jsonPlan = null;

            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery(explainSql)) {
                if (rs.next()) {
                    jsonPlan = rs.getString(1);
                }
            }

            if (jsonPlan == null) {
                return QueryPlan.builder()
                    .warnings("No explain plan generated")
                    .build();
            }

            // Parse the plan
            JsonNode planNode = objectMapper.readTree(jsonPlan);
            JsonNode plan = planNode.get(0).get("Plan");

            QueryPlan.QueryPlanBuilder builder = QueryPlan.builder();
            builder.planJson(jsonPlan);

            if (plan != null) {
                builder.nodeType(plan.has("Node Type") ? plan.get("Node Type").asText() : null);
                builder.estimatedCost(plan.has("Total Cost") ? plan.get("Total Cost").asDouble() : null);
                builder.estimatedRows(plan.has("Plan Rows") ? plan.get("Plan Rows").asLong() : null);

                // Check if index is used
                String nodeType = plan.has("Node Type") ? plan.get("Node Type").asText() : "";
                builder.usesIndex(nodeType.contains("Index Scan"));

                if (plan.has("Index Name")) {
                    builder.indexName(plan.get("Index Name").asText());
                }

                // Generate warnings
                List<String> warnings = new ArrayList<>();
                if (nodeType.contains("Seq Scan")) {
                    long rows = plan.has("Plan Rows") ? plan.get("Plan Rows").asLong() : 0;
                    if (rows > 10000) {
                        warnings.add("Sequential scan on large table (" + rows + " rows). Consider adding an index.");
                    }
                }

                if (!warnings.isEmpty()) {
                    builder.warnings(String.join("; ", warnings));
                }
            }

            // Get text format for display
            StringBuilder textPlan = new StringBuilder();
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {
                while (rs.next()) {
                    textPlan.append(rs.getString(1)).append("\n");
                }
            }
            builder.planText(textPlan.toString());

            return builder.build();
        } catch (Exception e) {
            log.error("Failed to explain PostgreSQL query: {}", e.getMessage());
            return QueryPlan.builder()
                .warnings("Failed to generate plan: " + e.getMessage())
                .build();
        }
    }

    /**
     * Get EXPLAIN plan for MySQL
     */
    private QueryPlan explainMySQL(Connection connection, String sql) {
        try {
            // Try JSON format first (MySQL 5.7+)
            try {
                String explainSql = "EXPLAIN FORMAT=JSON " + sql;
                String jsonPlan = null;

                try (Statement stmt = connection.createStatement();
                     ResultSet rs = stmt.executeQuery(explainSql)) {
                    if (rs.next()) {
                        jsonPlan = rs.getString(1);
                    }
                }

                if (jsonPlan == null) {
                    throw new SQLException("No JSON plan returned");
                }

                QueryPlan.QueryPlanBuilder builder = QueryPlan.builder();
                builder.planJson(jsonPlan);

                // Parse for basic info
                JsonNode planNode = objectMapper.readTree(jsonPlan);
                JsonNode queryBlock = planNode.get("query_block");

                if (queryBlock != null && queryBlock.has("table")) {
                    JsonNode table = queryBlock.get("table");

                    if (table.has("access_type")) {
                        String accessType = table.get("access_type").asText();
                        builder.nodeType(accessType);
                        builder.usesIndex(!accessType.equals("ALL"));
                    }

                    if (table.has("key")) {
                        builder.indexName(table.get("key").asText());
                    }

                    if (table.has("rows_examined_per_scan")) {
                        builder.estimatedRows(table.get("rows_examined_per_scan").asLong());
                    }

                    if (table.has("filtered")) {
                        builder.estimatedCost(table.get("filtered").asDouble());
                    }
                }

                return builder.build();
            } catch (Exception e) {
                // Fall back to traditional EXPLAIN
                log.debug("JSON explain failed, using traditional format");
            }

            // Traditional EXPLAIN format
            try (Statement stmt = connection.createStatement();
                 ResultSet rs = stmt.executeQuery("EXPLAIN " + sql)) {

                if (rs.next()) {
                    QueryPlan.QueryPlanBuilder builder = QueryPlan.builder();

                    String type = rs.getString("type");
                    String key = rs.getString("key");
                    Long rows = rs.getObject("rows") != null ? rs.getLong("rows") : null;
                    String table = rs.getString("table");

                    builder.nodeType(type);
                    builder.estimatedRows(rows);
                    builder.indexName(key);
                    builder.usesIndex(key != null && !key.isEmpty());

                    // Generate warnings
                    List<String> warnings = new ArrayList<>();
                    if ("ALL".equals(type) && rows != null && rows > 10000) {
                        warnings.add("Full table scan on " + rows + " rows. Consider adding an index.");
                    }

                    if (!warnings.isEmpty()) {
                        builder.warnings(String.join("; ", warnings));
                    }

                    // Format as text
                    StringBuilder planText = new StringBuilder();
                    planText.append("table: ").append(table)
                        .append(", type: ").append(type)
                        .append(", key: ").append(key)
                        .append(", rows: ").append(rows)
                        .append("\n");
                    builder.planText(planText.toString());

                    return builder.build();
                }
            }

            return QueryPlan.builder().build();
        } catch (Exception e) {
            log.error("Failed to explain MySQL query: {}", e.getMessage());
            return QueryPlan.builder()
                .warnings("Failed to generate plan: " + e.getMessage())
                .build();
        }
    }

    /**
     * Determine query type (SELECT, UPDATE, DELETE, etc.)
     */
    private String determineQueryType(String sql) {
        String normalized = sql.trim().toUpperCase();

        if (normalized.startsWith("SELECT")) return "SELECT";
        if (normalized.startsWith("INSERT")) return "INSERT";
        if (normalized.startsWith("UPDATE")) return "UPDATE";
        if (normalized.startsWith("DELETE")) return "DELETE";
        if (normalized.startsWith("CREATE")) return "CREATE";
        if (normalized.startsWith("DROP")) return "DROP";
        if (normalized.startsWith("ALTER")) return "ALTER";
        if (normalized.startsWith("TRUNCATE")) return "TRUNCATE";
        if (normalized.startsWith("SHOW")) return "SHOW";
        if (normalized.startsWith("DESCRIBE") || normalized.startsWith("DESC")) return "DESCRIBE";
        if (normalized.startsWith("EXPLAIN")) return "EXPLAIN";

        return "UNKNOWN";
    }

    /**
     * Check if operation is dangerous
     */
    private boolean isDangerousOperation(String sql) {
        return DANGEROUS_OPERATIONS.stream()
            .anyMatch(op -> sql.contains(op));
    }

    /**
     * Check if operation is DDL
     */
    private boolean isDDL(String sql) {
        return DDL_OPERATIONS.stream()
            .anyMatch(op -> sql.startsWith(op));
    }

    /**
     * Check if query has WHERE clause
     */
    private boolean hasWhereClause(String sql) {
        Pattern wherePattern = Pattern.compile("\\bWHERE\\b", Pattern.CASE_INSENSITIVE);
        return wherePattern.matcher(sql).find();
    }

    /**
     * Check for multiple SQL statements
     */
    private boolean hasMultipleStatements(String sql) {
        // Remove string literals to avoid false positives
        String cleaned = sql.replaceAll("'[^']*'", "");
        // Check for semicolons not at the end
        String trimmed = cleaned.trim();
        if (trimmed.endsWith(";")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed.contains(";");
    }

    /**
     * Basic SQL injection pattern detection
     */
    private void validateSqlInjectionPatterns(String sql, QueryValidationResult result) {
        // Check for common SQL injection patterns
        List<String> suspiciousPatterns = List.of(
            "(?i).*--.*",  // SQL comments
            "(?i).*\\/\\*.*\\*\\/.*",  // Block comments
            "(?i).*\\bOR\\s+1\\s*=\\s*1.*",  // OR 1=1
            "(?i).*\\bOR\\s+'.*'\\s*=\\s*'.*'.*",  // OR 'a'='a'
            "(?i).*\\bUNION\\s+SELECT.*"  // UNION SELECT
        );

        for (String pattern : suspiciousPatterns) {
            if (sql.matches(pattern)) {
                result.addWarning("Query contains potentially suspicious pattern");
                break;
            }
        }
    }

    /**
     * Validate dashboard query syntax for common errors
     * Specifically checks for invalid date range patterns like BETWEEN '1' AND '1'
     */
    private void validateDashboardQuerySyntax(String sql, QueryValidationResult result) {
        if (sql == null || sql.trim().isEmpty()) {
            return;
        }
        
        // Check for invalid BETWEEN patterns that indicate date range substitution failed
        // Pattern: BETWEEN '1' AND '1' or BETWEEN "1" AND "1"
        Pattern invalidBetweenPattern = Pattern.compile(
            "(?i)BETWEEN\\s+['\"]1['\"]\\s+AND\\s+['\"]1['\"]",
            Pattern.CASE_INSENSITIVE
        );
        
        if (invalidBetweenPattern.matcher(sql).find()) {
            result.addError("Invalid date range detected: BETWEEN '1' AND '1'. " +
                "This indicates a date range placeholder was not properly replaced. " +
                "Ensure date range inputs use the format: column BETWEEN {{inputName}}");
        }
        
        // Check for other suspicious BETWEEN patterns with single character values
        Pattern suspiciousBetweenPattern = Pattern.compile(
            "(?i)BETWEEN\\s+['\"](.)['\"]\\s+AND\\s+['\"]\\1['\"]",
            Pattern.CASE_INSENSITIVE
        );
        
        if (suspiciousBetweenPattern.matcher(sql).find()) {
            result.addWarning("Suspicious BETWEEN clause detected with identical start and end values. " +
                "This may indicate a date range substitution issue.");
        }
        
        // Check for unclosed placeholders ({{inputName}} that might not be replaced)
        Pattern unclosedPlaceholderPattern = Pattern.compile("\\{\\{[^}]+\\}\\}");
        if (unclosedPlaceholderPattern.matcher(sql).find()) {
            result.addWarning("Query contains placeholder patterns ({{...}}) that may not be replaced. " +
                "Ensure all placeholders match input names in the dashboard configuration.");
        }
    }

    /**
     * Estimate query cost and suggest optimizations
     */
    public String suggestOptimizations(QueryPlan plan) {
        if (plan == null) {
            return null;
        }

        List<String> suggestions = new ArrayList<>();

        // Check if sequential scan on large table
        if (Boolean.FALSE.equals(plan.getUsesIndex()) && plan.getEstimatedRows() != null && plan.getEstimatedRows() > 10000) {
            suggestions.add("Consider adding an index to avoid sequential scan on " + plan.getEstimatedRows() + " rows");
        }

        // Check estimated vs actual rows
        if (plan.getEstimatedRows() != null && plan.getActualRows() != null) {
            long diff = Math.abs(plan.getEstimatedRows() - plan.getActualRows());
            if (diff > plan.getEstimatedRows() * 0.5) {  // 50% difference
                suggestions.add("Large difference between estimated (" + plan.getEstimatedRows() +
                    ") and actual rows (" + plan.getActualRows() + "). Consider running ANALYZE on the table.");
            }
        }

        return suggestions.isEmpty() ? null : String.join("; ", suggestions);
    }
}
