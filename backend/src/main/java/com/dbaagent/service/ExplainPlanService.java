package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.ExplainPlanProvider;
import com.dbaagent.util.CacheKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.Locale;
import java.util.*;
import java.util.regex.Pattern;

/**
 * Service for analyzing SQL query execution plans
 */
@Service
@Slf4j
public class ExplainPlanService {

    private final ConnectionService connectionService;
    private final CredentialService credentialService;
    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final CacheMetricsService cacheMetricsService;
    private final DatabaseProviderRegistry providerRegistry;

    @Autowired
    public ExplainPlanService(
            ConnectionService connectionService,
            CredentialService credentialService,
            ChatClient.Builder chatClientBuilder,
            ObjectMapper objectMapper,
            CacheManager cacheManager,
            CacheMetricsService cacheMetricsService,
            DatabaseProviderRegistry providerRegistry) {
        this.connectionService = connectionService;
        this.credentialService = credentialService;
        this.chatClient = chatClientBuilder.build();
        this.objectMapper = objectMapper;
        this.cacheManager = cacheManager;
        this.cacheMetricsService = cacheMetricsService;
        this.providerRegistry = providerRegistry;
        log.info("ExplainPlanService initialized with Spring AI ChatClient");
    }

    /**
     * Analyze a query's execution plan
     */
    public ExplainPlanAnalysis analyzeQuery(
        String connectionId,
        String query,
        boolean useAnalyze
    ) {
        try {
            String sanitizedQuery = sanitizeExplainQuery(query);
            if (sanitizedQuery == null || sanitizedQuery.isBlank()) {
                throw new IllegalArgumentException("SQL query is required.");
            }

            Cache cache = cacheManager.getCache("explainAnalysis");
            String cacheKey = null;
            if (!useAnalyze && cache != null) {
                cacheKey = CacheKeyUtil.explainKey(connectionId, sanitizedQuery, false);
                Cache.ValueWrapper wrapper = cache.get(cacheKey);
                if (wrapper != null && wrapper.get() != null) {
                    Object cachedValue = wrapper.get();
                    ExplainPlanAnalysis cached = cachedValue instanceof ExplainPlanAnalysis
                        ? (ExplainPlanAnalysis) cachedValue
                        : objectMapper.convertValue(cachedValue, ExplainPlanAnalysis.class);
                    cacheMetricsService.recordGet("explainAnalysis", true);
                    return cached;
                }
                cacheMetricsService.recordGet("explainAnalysis", false);
            }

            // Get connection with credentials
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);

            // Detect database type
            String dbType = providerRegistry.getCanonicalName(connection.getDbType());

            ExplainPlanAnalysis analysis = ExplainPlanAnalysis.builder()
                .connectionId(connectionId)
                .query(sanitizedQuery)
                .normalizedQuery(normalizeQuery(sanitizedQuery))
                .dbType(dbType)
                .analyzedAt(LocalDateTime.now())
                .wasExecuted(useAnalyze)
                .build();

            // Execute EXPLAIN using database-specific provider
            DatabaseDialect dialect = providerRegistry.getDialect(dbType);
            ExplainPlanProvider provider = dialect.explainPlan();
            if (provider == null) {
                throw new IllegalArgumentException("ExplainPlanProvider not available for: " + dbType);
            }

            if (hasBindParameters(sanitizedQuery)) {
                log.warn("Skipping EXPLAIN for parameterized query");
                analysis.setPlanParseError("EXPLAIN skipped for parameterized query (bind parameters present)");
                return analysis;
            }

            try (Connection jdbcConn = connectionService.getConnection(connectionId, connection)) {
                List<Map<String, Object>> results = provider.executeExplain(jdbcConn, sanitizedQuery, useAnalyze);
                if (results == null || results.isEmpty()) {
                    throw new RuntimeException("EXPLAIN returned no results");
                }

                Map<String, Object> planRoot = results.get(0);

                // MySQL EXPLAIN ANALYZE comes back as a TREE-format text blob
                // under "raw" — show it verbatim rather than JSON-wrapping it.
                if (planRoot.size() == 1 && planRoot.containsKey("raw")) {
                    analysis.setPlanText(String.valueOf(planRoot.get("raw")));
                    analysis.setPlanJson(null);
                } else {
                    analysis.setPlanJson(objectMapper.writeValueAsString(results));
                    analysis.setPlanText(objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(results));
                }

                Number planningTime = getNumber(planRoot, "Planning Time");
                if (planningTime != null) {
                    analysis.setPlanningTimeMs(planningTime.doubleValue());
                }
                Number executionTime = getNumber(planRoot, "Execution Time");
                if (executionTime != null) {
                    analysis.setExecutionTimeMs(executionTime.doubleValue());
                    analysis.setTotalTimeMs(
                        (analysis.getPlanningTimeMs() != null ? analysis.getPlanningTimeMs() : 0.0) +
                        analysis.getExecutionTimeMs()
                    );
                }

                ExplainPlanNode planTree = provider.parseExplainResult(results);
                if (planTree == null) {
                    planTree = new ExplainPlanNode();
                }
                analysis.setPlanTree(planTree);

                analysis.setEstimatedCost(planTree.getTotalCost());
                if (planTree.getActualTotalTime() != null) {
                    analysis.setActualCost(planTree.getActualTotalTime());
                }
                // MySQL EXPLAIN ANALYZE has no top-level "Execution Time" key
                // (Postgres does) — derive it from the root node's actual time
                // so the Plan Summary still shows a real execution time.
                if (analysis.getExecutionTimeMs() == null && planTree.getActualTotalTime() != null) {
                    analysis.setExecutionTimeMs(planTree.getActualTotalTime());
                }
                if (analysis.getEstimatedRows() == null && planTree.getPlanRows() != null) {
                    analysis.setEstimatedRows(planTree.getPlanRows().doubleValue());
                }
                analysis.setNodeCount(countNodes(planTree));

                provider.detectIssues(planTree, analysis);
                List<IndexRecommendation> recommendations = provider.generateIndexRecommendations(planTree, sanitizedQuery);
                if (recommendations != null) {
                    recommendations.forEach(analysis::addIndexRecommendation);
                }
            } catch (SQLException e) {
                log.error("Error analyzing query execution plan", e);
                throw new RuntimeException("Failed to analyze query: " + e.getMessage(), e);
            }

            // Generate AI summary
            generateAISummary(analysis);

            if (analysis.getPlanTree() != null) {
                markCriticalPath(analysis.getPlanTree());
                analysis.setPlanSignature(buildPlanSignature(analysis.getPlanTree()));
            }

            if (!useAnalyze && cache != null && cacheKey != null) {
                cache.put(cacheKey, analysis);
                cacheMetricsService.recordPut("explainAnalysis");
            }
            log.info("EXPLAIN analysis completed: {}", analysis.getSummaryStats());
            return analysis;

        } catch (Exception e) {
            log.error("Error analyzing query execution plan", e);
            throw new RuntimeException("Failed to analyze query: " + e.getMessage(), e);
        }
    }

    private String sanitizeExplainQuery(String query) {
        if (query == null) {
            return null;
        }
        String trimmed = query.trim();
        if (trimmed.isEmpty()) {
            return trimmed;
        }

        // Remove MySQL slow query log prefixes like "SET timestamp=1234567890;"
        // These are added by MySQL when logging slow queries
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.startsWith("set timestamp=")) {
            int semicolonIndex = trimmed.indexOf(';');
            if (semicolonIndex != -1 && semicolonIndex < trimmed.length() - 1) {
                trimmed = trimmed.substring(semicolonIndex + 1).trim();
                lower = trimmed.toLowerCase(Locale.ROOT);
                log.debug("Removed SET timestamp prefix from query for EXPLAIN");
            }
        }

        // Remove "use database;" prefix if present
        if (lower.startsWith("use ")) {
            int semicolonIndex = trimmed.indexOf(';');
            if (semicolonIndex != -1 && semicolonIndex < trimmed.length() - 1) {
                trimmed = trimmed.substring(semicolonIndex + 1).trim();
                lower = trimmed.toLowerCase(Locale.ROOT);
                log.debug("Removed USE database prefix from query for EXPLAIN");
            }
        }

        // Fix missing leading 's' in "elect" -> "select"
        if (lower.startsWith("elect") && (lower.length() == 5 || Character.isWhitespace(lower.charAt(5)))) {
            log.warn("Query appears to be missing leading 's'; auto-correcting for EXPLAIN.");
            return "s" + trimmed;
        }

        return trimmed;
    }

    private boolean hasBindParameters(String query) {
        if (query == null || query.isBlank()) {
            return false;
        }
        return Pattern.compile("\\$\\d+").matcher(query).find()
            || Pattern.compile("\\?").matcher(query).find();
    }

    /**
     * Generate AI summary of the execution plan
     */
    private void generateAISummary(ExplainPlanAnalysis analysis) {
        try {
            boolean actual = Boolean.TRUE.equals(analysis.getWasExecuted());
            StringBuilder prompt = new StringBuilder();
            prompt.append("Analyze this SQL query execution plan and explain it to a developer.\n\n");
            prompt.append("Database: ").append(analysis.getDbType().toUpperCase()).append("\n");
            prompt.append("Plan type: ").append(actual
                ? "ACTUAL (EXPLAIN ANALYZE — the query was executed, so row counts and timings are real)"
                : "ESTIMATED (plain EXPLAIN — planner estimates only, query was not run)").append("\n");
            prompt.append("Query: ").append(analysis.getQuery()).append("\n\n");

            if (analysis.getExecutionTimeMs() != null) {
                prompt.append(String.format("Total execution time: %.2f ms%n", analysis.getExecutionTimeMs()));
            }
            if (analysis.getPlanningTimeMs() != null) {
                prompt.append(String.format("Planning time: %.2f ms%n", analysis.getPlanningTimeMs()));
            }

            // Feed the actual execution tree so the model can walk it node by
            // node (scan types, indexes used vs available, estimate-vs-actual
            // row skew, filter waste) instead of guessing from the SQL text.
            if (analysis.getPlanTree() != null) {
                prompt.append("\nExecution plan tree (parent → children, indented):\n");
                appendPlanTree(prompt, analysis.getPlanTree(), 0);
            }

            prompt.append(String.format("%nDetected issues: %d%n", analysis.getIssues().size()));
            for (PerformanceIssue issue : analysis.getIssues()) {
                prompt.append(String.format("- %s: %s%n", issue.getSeverity(), issue.getMessage()));
            }

            prompt.append("\nWrite a clear, concise explanation with these sections (use the exact headers):\n");
            prompt.append("**What this query does** — one or two sentences in plain English.\n");
            prompt.append("**How the plan executes** — walk the tree from the bottom-most (first-executed) "
                + "nodes up to the root. For each significant node say what it does (scan/join/sort/aggregate), "
                + "on which table, and where the rows and time go. Call out the single most expensive step.\n");
            prompt.append("**Indexes** — for every scan, state whether an index is used (and which one). "
                + "If a Seq Scan / full table scan is reading a large table, or an index is available but "
                + "not chosen, say so plainly. ");
            if (actual) {
                prompt.append("Flag any node where the actual row count is wildly different from the estimate "
                    + "(a sign of stale statistics) and any large 'rows removed by filter' (a sign the predicate "
                    + "isn't index-served).");
            }
            prompt.append("\n**Bottom line** — the single highest-impact thing to fix, if any.\n");
            prompt.append("\nBe specific and reference real node/table/index names from the plan. "
                + "Do not invent numbers. Keep it tight — no preamble.\n");

            List<Message> messages = new ArrayList<>();
            messages.add(new SystemMessage(
                "You are a senior database performance engineer. You read execution plans and explain, in plain "
                + "language, exactly how a query runs and why — walking the plan tree, naming the indexes used or "
                + "missing, and pointing at the one thing worth fixing. You never fabricate numbers."));
            messages.add(new UserMessage(prompt.toString()));

            String summary = chatClient.prompt()
                .messages(messages)
                .call()
                .content();

            analysis.setAiSummary(summary);

        } catch (Exception e) {
            log.warn("Failed to generate AI summary", e);
            analysis.setAiSummary("AI summary unavailable");
        }
    }

    /**
     * Render the plan tree as compact indented text for the AI prompt — one
     * line per node with the fields that matter for explaining execution:
     * node/scan type, table, index used vs available, estimated vs actual
     * rows, cost/time, and filter waste.
     */
    private void appendPlanTree(StringBuilder out, ExplainPlanNode node, int depth) {
        if (node == null) return;
        String indent = "  ".repeat(depth);
        StringBuilder line = new StringBuilder(indent).append("- ");
        line.append(node.getNodeType() != null ? node.getNodeType() : "Node");
        if (node.getTableName() != null) line.append(" on ").append(node.getTableName());
        if (node.getKey() != null) line.append(" [index used: ").append(node.getKey()).append("]");
        else if (node.getAccessType() != null) line.append(" [access: ").append(node.getAccessType()).append("]");
        if (node.getPossibleKeys() != null) line.append(" [indexes available: ").append(node.getPossibleKeys()).append("]");
        if (node.getPlanRows() != null) line.append(" est_rows=").append(node.getPlanRows());
        if (node.getActualRows() != null) line.append(" actual_rows=").append(node.getActualRows());
        if (node.getTotalCost() != null) line.append(String.format(" cost=%.0f", node.getTotalCost()));
        if (node.getActualTotalTime() != null) line.append(String.format(" time=%.2fms", node.getActualTotalTime()));
        if (node.getRowsRemovedByFilter() != null && node.getRowsRemovedByFilter() > 0) {
            line.append(" rows_removed_by_filter=").append(node.getRowsRemovedByFilter());
        }
        if (node.getFilter() != null) line.append(" filter=").append(truncate(node.getFilter(), 120));
        if (node.getExtra() != null && !node.getExtra().isBlank()) line.append(" extra=").append(node.getExtra());
        out.append(line).append('\n');
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                appendPlanTree(out, child, depth + 1);
            }
        }
    }

    private static String truncate(String s, int max) {
        if (s == null) return null;
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }

    /**
     * Normalize query for comparison
     */
    private String normalizeQuery(String query) {
        // Remove extra whitespace
        String normalized = query.replaceAll("\\s+", " ").trim();
        // Remove literal values for comparison
        normalized = normalized.replaceAll("'[^']*'", "?");
        normalized = normalized.replaceAll("\\d+", "?");
        return normalized;
    }

    /**
     * Count total nodes in plan tree
     */
    private int countNodes(ExplainPlanNode node) {
        if (node == null) {
            return 0;
        }

        int count = 1;
        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                count += countNodes(child);
            }
        }
        return count;
    }

    private Number getNumber(Map<String, Object> planMap, String key) {
        Object value = planMap.get(key);
        if (value instanceof Number) {
            return (Number) value;
        }
        return null;
    }

    private void markCriticalPath(ExplainPlanNode root) {
        if (root == null) {
            return;
        }
        PathResult result = computeCriticalPath(root);
        if (result == null || result.nodes == null) {
            return;
        }
        for (ExplainPlanNode node : result.nodes) {
            node.setIsSlowNode(true);
            node.setSlowReason("Critical path");
        }
    }

    private PathResult computeCriticalPath(ExplainPlanNode node) {
        if (node == null) {
            return new PathResult(0.0, new ArrayList<>());
        }

        double nodeWeight = getNodeWeight(node);
        if (node.getChildren() == null || node.getChildren().isEmpty()) {
            List<ExplainPlanNode> path = new ArrayList<>();
            path.add(node);
            return new PathResult(nodeWeight, path);
        }

        PathResult bestChild = null;
        for (ExplainPlanNode child : node.getChildren()) {
            PathResult childResult = computeCriticalPath(child);
            if (bestChild == null || childResult.totalWeight > bestChild.totalWeight) {
                bestChild = childResult;
            }
        }

        List<ExplainPlanNode> path = new ArrayList<>();
        path.add(node);
        if (bestChild != null && bestChild.nodes != null) {
            path.addAll(bestChild.nodes);
        }
        double totalWeight = nodeWeight + (bestChild != null ? bestChild.totalWeight : 0.0);
        return new PathResult(totalWeight, path);
    }

    private double getNodeWeight(ExplainPlanNode node) {
        if (node.getActualTotalTime() != null) {
            return node.getActualTotalTime();
        }
        if (node.getTotalCost() != null) {
            return node.getTotalCost();
        }
        return 0.0;
    }

    private String buildPlanSignature(ExplainPlanNode node) {
        if (node == null) {
            return null;
        }
        StringBuilder builder = new StringBuilder();
        appendSignature(node, builder);
        return hashSignature(builder.toString());
    }

    private void appendSignature(ExplainPlanNode node, StringBuilder builder) {
        if (node == null) {
            return;
        }
        builder.append('{')
            .append(safeLower(node.getNodeType())).append('|')
            .append(safeLower(node.getTableName())).append('|')
            .append(safeLower(node.getAccessType())).append('|')
            .append(safeLower(node.getJoinType())).append('|')
            .append(safeLower(node.getParentRelationship()))
            .append('}');
        if (node.getChildren() != null && !node.getChildren().isEmpty()) {
            builder.append('[');
            for (ExplainPlanNode child : node.getChildren()) {
                appendSignature(child, builder);
            }
            builder.append(']');
        }
    }

    private String hashSignature(String value) {
        try {
            java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            return String.valueOf(value.hashCode());
        }
    }

    private String safeLower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private record PathResult(double totalWeight, List<ExplainPlanNode> nodes) {}
}
