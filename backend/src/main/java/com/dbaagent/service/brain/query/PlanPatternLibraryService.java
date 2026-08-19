package com.dbaagent.service.brain.query;

import com.dbaagent.model.ExplainPlanAnalysis;
import com.dbaagent.model.ExplainPlanNode;
import com.dbaagent.model.brain.PlanExecution;
import com.dbaagent.model.brain.PlanPattern;
import com.dbaagent.repository.brain.PlanExecutionRepository;
import com.dbaagent.repository.brain.PlanPatternRepository;
import com.dbaagent.service.ExplainPlanService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brain 2.0: Plan Pattern Library Service
 *
 * Manages a library of query plan patterns with cached optimization suggestions.
 * Implements persistent memoization - plans and statistics are stored and reused,
 * enabling adaptivity through feedback from prior executions.
 *
 * Inspired by optd's plan memoization approach for adaptive query optimization.
 */
@Service
@Slf4j
public class PlanPatternLibraryService {

    private final PlanPatternRepository patternRepository;
    private final PlanExecutionRepository executionRepository;
    private final ExplainPlanService explainPlanService;
    private final ChatClient chatClient;

    @Autowired
    public PlanPatternLibraryService(
            PlanPatternRepository patternRepository,
            PlanExecutionRepository executionRepository,
            ExplainPlanService explainPlanService,
            ChatClient.Builder chatClientBuilder) {
        this.patternRepository = patternRepository;
        this.executionRepository = executionRepository;
        this.explainPlanService = explainPlanService;
        this.chatClient = chatClientBuilder.build();
        log.info("PlanPatternLibraryService initialized with Spring AI ChatClient");
    }

    // Configuration
    private static final int MIN_USAGE_FOR_RELIABILITY = 5;
    private static final double MIN_EFFECTIVENESS_SCORE = 60.0;
    private static final int MAX_SUGGESTIONS_PER_PATTERN = 5;

    /**
     * Add or update a plan pattern from an EXPLAIN analysis.
     */
    @Transactional
    public PlanPattern addPattern(String connectionId, String query, ExplainPlanAnalysis analysis) {
        if (analysis.getPlanSignature() == null) {
            log.warn("Cannot add pattern without plan signature");
            return null;
        }

        try {
            // Find or create pattern
            PlanPattern pattern = patternRepository
                .findByConnectionIdAndPlanSignature(connectionId, analysis.getPlanSignature())
                .orElse(PlanPattern.builder()
                    .connectionId(connectionId)
                    .planSignature(analysis.getPlanSignature())
                    .createdAt(LocalDateTime.now())
                    .build());

            // Update pattern details
            pattern.setQueryPattern(normalizeQuery(query));
            pattern.setPatternType(classifyPatternType(analysis.getPlanTree()));
            pattern.setTablesInvolved(extractTables(analysis.getPlanTree()));
            pattern.setJoinTypes(extractJoinTypes(analysis.getPlanTree()));

            // Generate suggestions if not present
            if (pattern.getSuggestions() == null || pattern.getSuggestions().isEmpty()) {
                List<Map<String, Object>> suggestions = generateSuggestions(query, analysis);
                pattern.setSuggestions(suggestions);

                // Generate optimized query if possible
                String optimizedQuery = generateOptimizedQuery(query, analysis, suggestions);
                pattern.setOptimizedQuery(optimizedQuery);

                // Generate explanation
                pattern.setExplanation(generateExplanation(query, analysis, suggestions));
            }

            pattern.recordUsage();

            return patternRepository.save(pattern);

        } catch (Exception e) {
            log.error("Error adding plan pattern: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to add plan pattern: " + e.getMessage(), e);
        }
    }

    /**
     * Find matching pattern for a query.
     */
    public Optional<PlanPattern> findMatchingPattern(String connectionId, String query) {
        try {
            // Get EXPLAIN for the query
            ExplainPlanAnalysis analysis = explainPlanService.analyzeQuery(connectionId, query, false);
            if (analysis.getPlanSignature() == null) {
                return Optional.empty();
            }

            // Look for pattern by plan signature
            Optional<PlanPattern> pattern = patternRepository
                .findByConnectionIdAndPlanSignature(connectionId, analysis.getPlanSignature());

            if (pattern.isPresent()) {
                PlanPattern p = pattern.get();
                p.recordUsage();
                patternRepository.save(p);
                return pattern;
            }

            // Try to find similar pattern by query pattern
            String normalizedQuery = normalizeQuery(query);
            List<PlanPattern> similarPatterns = patternRepository
                .findByQueryPatternContaining(connectionId, "%" + extractQuerySignature(normalizedQuery) + "%");

            if (!similarPatterns.isEmpty()) {
                // Return the most effective similar pattern
                return similarPatterns.stream()
                    .max(Comparator.comparingDouble(p ->
                        p.getEffectivenessScore() != null ? p.getEffectivenessScore() : 0.0));
            }

            return Optional.empty();

        } catch (Exception e) {
            log.debug("Error finding matching pattern: {}", e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Get suggestions for a query based on patterns.
     */
    public List<Map<String, Object>> getSuggestions(String connectionId, String query) {
        Optional<PlanPattern> patternOpt = findMatchingPattern(connectionId, query);

        if (patternOpt.isPresent()) {
            PlanPattern pattern = patternOpt.get();
            if (pattern.getSuggestions() != null && !pattern.getSuggestions().isEmpty()) {
                log.info("Found cached suggestions for query pattern {}", pattern.getPlanSignature());
                return pattern.getSuggestions();
            }
        }

        // Generate new suggestions
        try {
            ExplainPlanAnalysis analysis = explainPlanService.analyzeQuery(connectionId, query, false);
            List<Map<String, Object>> suggestions = generateSuggestions(query, analysis);

            // Store as new pattern
            addPattern(connectionId, query, analysis);

            return suggestions;

        } catch (Exception e) {
            log.error("Error getting suggestions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Record feedback on a suggestion (success or failure).
     */
    @Transactional
    public void recordFeedback(String patternId, boolean wasSuccessful) {
        Optional<PlanPattern> patternOpt = patternRepository.findById(patternId);

        if (patternOpt.isPresent()) {
            PlanPattern pattern = patternOpt.get();
            if (wasSuccessful) {
                pattern.recordSuccess();
            }
            pattern.setLastValidatedAt(LocalDateTime.now());
            patternRepository.save(pattern);
        }
    }

    /**
     * Get reliable patterns for a connection.
     */
    public List<PlanPattern> getReliablePatterns(String connectionId) {
        return patternRepository.findReliablePatterns(
            connectionId, MIN_EFFECTIVENESS_SCORE, MIN_USAGE_FOR_RELIABILITY);
    }

    /**
     * Get patterns involving a specific table.
     */
    public List<PlanPattern> getPatternsForTable(String connectionId, String tableName) {
        String tableJson = "[\"" + tableName + "\"]";
        return patternRepository.findPatternsWithTable(connectionId, tableJson);
    }

    /**
     * Get most used patterns.
     */
    public List<PlanPattern> getMostUsedPatterns(String connectionId, int limit) {
        return patternRepository.findMostUsedPatterns(connectionId, PageRequest.of(0, limit));
    }

    /**
     * Get patterns with optimization suggestions.
     */
    public List<PlanPattern> getPatternsWithOptimizations(String connectionId) {
        return patternRepository.findPatternsWithOptimizations(connectionId, MIN_EFFECTIVENESS_SCORE);
    }

    /**
     * Get pattern statistics for a connection.
     */
    public Map<String, Object> getPatternStatistics(String connectionId) {
        Map<String, Object> stats = new HashMap<>();

        long totalPatterns = patternRepository.countByConnectionId(connectionId);
        stats.put("totalPatterns", totalPatterns);

        List<PlanPattern> reliablePatterns = getReliablePatterns(connectionId);
        stats.put("reliablePatterns", reliablePatterns.size());

        List<PlanPattern> withOptimizations = getPatternsWithOptimizations(connectionId);
        stats.put("patternsWithOptimizations", withOptimizations.size());

        // Count by pattern type
        List<PlanPattern> allPatterns = patternRepository
            .findByConnectionIdOrderByEffectivenessScoreDesc(connectionId, PageRequest.of(0, 1000));

        Map<PlanPattern.PatternType, Long> typeDistribution = allPatterns.stream()
            .filter(p -> p.getPatternType() != null)
            .collect(Collectors.groupingBy(PlanPattern::getPatternType, Collectors.counting()));
        stats.put("patternTypeDistribution", typeDistribution);

        // Average effectiveness
        double avgEffectiveness = allPatterns.stream()
            .filter(p -> p.getEffectivenessScore() != null)
            .mapToDouble(PlanPattern::getEffectivenessScore)
            .average()
            .orElse(0.0);
        stats.put("averageEffectiveness", avgEffectiveness);

        return stats;
    }

    /**
     * Clean up ineffective patterns.
     */
    @Transactional
    public int cleanupIneffectivePatterns(String connectionId) {
        // Get patterns with low usage and low effectiveness
        List<PlanPattern> allPatterns = patternRepository
            .findByConnectionIdOrderByEffectivenessScoreDesc(connectionId, PageRequest.of(0, 10000));

        int deleted = 0;
        for (PlanPattern pattern : allPatterns) {
            if (pattern.getUsageCount() < 2 &&
                (pattern.getEffectivenessScore() == null || pattern.getEffectivenessScore() < 30)) {
                patternRepository.delete(pattern);
                deleted++;
            }
        }

        log.info("Cleaned up {} ineffective patterns for connection {}", deleted, connectionId);
        return deleted;
    }

    // Pattern generation methods

    private List<Map<String, Object>> generateSuggestions(String query, ExplainPlanAnalysis analysis) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        // Add issues as suggestions
        if (analysis.getIssues() != null) {
            for (var issue : analysis.getIssues()) {
                Map<String, Object> suggestion = new HashMap<>();
                suggestion.put("type", "ISSUE");
                suggestion.put("severity", issue.getSeverity().toString());
                suggestion.put("message", issue.getMessage());
                suggestion.put("recommendation", issue.getRecommendation());
                suggestions.add(suggestion);
            }
        }

        // Add index recommendations
        if (analysis.getIndexRecommendations() != null) {
            for (var rec : analysis.getIndexRecommendations()) {
                Map<String, Object> suggestion = new HashMap<>();
                suggestion.put("type", "INDEX");
                suggestion.put("priority", rec.getPriority().toString());
                suggestion.put("table", rec.getTableName());
                suggestion.put("columns", rec.getColumns());
                suggestion.put("sql", rec.getSuggestedSQL());
                suggestion.put("reasoning", rec.getReasoning());
                suggestions.add(suggestion);
            }
        }

        // Try to get AI suggestions
        try {
            List<Map<String, Object>> aiSuggestions = generateAISuggestions(query, analysis);
            suggestions.addAll(aiSuggestions);
        } catch (Exception e) {
            log.debug("Could not generate AI suggestions: {}", e.getMessage());
        }

        // Limit suggestions
        if (suggestions.size() > MAX_SUGGESTIONS_PER_PATTERN) {
            suggestions = suggestions.subList(0, MAX_SUGGESTIONS_PER_PATTERN);
        }

        return suggestions;
    }

    private List<Map<String, Object>> generateAISuggestions(String query, ExplainPlanAnalysis analysis) {
        List<Map<String, Object>> suggestions = new ArrayList<>();

        String prompt = buildSuggestionPrompt(query, analysis);

        String systemPrompt = """
            You are a database query optimization expert. Analyze the query and its execution plan,
            then provide specific, actionable suggestions for optimization.

            For each suggestion, provide:
            1. TYPE: QUERY_REWRITE, INDEX, SCHEMA, or CONFIG
            2. PRIORITY: HIGH, MEDIUM, or LOW
            3. SUGGESTION: Brief description
            4. EXAMPLE: Code example if applicable
            5. ESTIMATED_IMPROVEMENT: Percentage

            Format each suggestion as:
            TYPE: <type>
            PRIORITY: <priority>
            SUGGESTION: <description>
            EXAMPLE: <code>
            ESTIMATED_IMPROVEMENT: <percentage>
            ---
            """;

        try {
            String response = chatClient.prompt()
                .messages(List.of(
                    new SystemMessage(systemPrompt),
                    new UserMessage(prompt)
                ))
                .call()
                .content();

            // Parse response
            String[] blocks = response.split("---");
            for (String block : blocks) {
                if (block.trim().isEmpty()) continue;

                Map<String, Object> suggestion = new HashMap<>();
                suggestion.put("type", extractValue(block, "TYPE:"));
                suggestion.put("priority", extractValue(block, "PRIORITY:"));
                suggestion.put("message", extractValue(block, "SUGGESTION:"));
                suggestion.put("example", extractValue(block, "EXAMPLE:"));

                String improvement = extractValue(block, "ESTIMATED_IMPROVEMENT:");
                if (improvement != null) {
                    try {
                        suggestion.put("estimatedImprovement",
                            Double.parseDouble(improvement.replaceAll("[^0-9.]", "")));
                    } catch (NumberFormatException ignored) {}
                }

                suggestions.add(suggestion);
            }

        } catch (Exception e) {
            log.debug("AI suggestion generation failed: {}", e.getMessage());
        }

        return suggestions;
    }

    private String buildSuggestionPrompt(String query, ExplainPlanAnalysis analysis) {
        StringBuilder prompt = new StringBuilder();
        prompt.append("## Query\n```sql\n").append(query).append("\n```\n\n");

        prompt.append("## Database Type\n").append(analysis.getDbType()).append("\n\n");

        if (analysis.getExecutionTimeMs() != null) {
            prompt.append("## Execution Time\n").append(analysis.getExecutionTimeMs()).append(" ms\n\n");
        }

        if (analysis.getEstimatedCost() != null) {
            prompt.append("## Estimated Cost\n").append(analysis.getEstimatedCost()).append("\n\n");
        }

        prompt.append("## Issues Detected\n");
        if (analysis.getIssues() != null && !analysis.getIssues().isEmpty()) {
            for (var issue : analysis.getIssues()) {
                prompt.append("- ").append(issue.getSeverity()).append(": ")
                    .append(issue.getMessage()).append("\n");
            }
        } else {
            prompt.append("None detected\n");
        }

        prompt.append("\n## Task\n");
        prompt.append("Provide up to 3 specific optimization suggestions.\n");

        return prompt.toString();
    }

    private String generateOptimizedQuery(String query, ExplainPlanAnalysis analysis,
                                          List<Map<String, Object>> suggestions) {
        // Try to find a query rewrite suggestion
        for (Map<String, Object> suggestion : suggestions) {
            if ("QUERY_REWRITE".equals(suggestion.get("type"))) {
                Object example = suggestion.get("example");
                if (example != null && !example.toString().isEmpty()) {
                    return example.toString();
                }
            }
        }

        return null;
    }

    private String generateExplanation(String query, ExplainPlanAnalysis analysis,
                                       List<Map<String, Object>> suggestions) {
        StringBuilder explanation = new StringBuilder();

        explanation.append("Query pattern analysis:\n");

        if (analysis.getPlanTree() != null) {
            explanation.append("- Primary operation: ").append(analysis.getPlanTree().getNodeType()).append("\n");
        }

        if (analysis.getEstimatedCost() != null) {
            explanation.append("- Estimated cost: ").append(analysis.getEstimatedCost()).append("\n");
        }

        if (analysis.getIssues() != null && !analysis.getIssues().isEmpty()) {
            explanation.append("- Issues found: ").append(analysis.getIssues().size()).append("\n");
        }

        explanation.append("- Suggestions available: ").append(suggestions.size()).append("\n");

        return explanation.toString();
    }

    // Helper methods

    private PlanPattern.PatternType classifyPatternType(ExplainPlanNode node) {
        if (node == null || node.getNodeType() == null) {
            return PlanPattern.PatternType.MIXED;
        }

        String nodeType = node.getNodeType().toUpperCase();

        if (nodeType.contains("SEQ SCAN") || nodeType.equals("ALL")) {
            return PlanPattern.PatternType.SEQUENTIAL_SCAN;
        } else if (nodeType.contains("INDEX ONLY")) {
            return PlanPattern.PatternType.INDEX_ONLY_SCAN;
        } else if (nodeType.contains("INDEX")) {
            return PlanPattern.PatternType.INDEX_SCAN;
        } else if (nodeType.contains("NESTED LOOP")) {
            return PlanPattern.PatternType.NESTED_LOOP;
        } else if (nodeType.contains("HASH JOIN")) {
            return PlanPattern.PatternType.HASH_JOIN;
        } else if (nodeType.contains("MERGE JOIN")) {
            return PlanPattern.PatternType.MERGE_JOIN;
        } else if (nodeType.contains("SORT")) {
            return PlanPattern.PatternType.SORT;
        } else if (nodeType.contains("AGGREGATE") || nodeType.contains("GROUP")) {
            return PlanPattern.PatternType.AGGREGATE;
        } else if (nodeType.contains("SUBQUERY") || nodeType.contains("SUBPLAN")) {
            return PlanPattern.PatternType.SUBQUERY;
        } else if (nodeType.contains("CTE")) {
            return PlanPattern.PatternType.CTE;
        }

        return PlanPattern.PatternType.MIXED;
    }

    private List<String> extractTables(ExplainPlanNode node) {
        Set<String> tables = new HashSet<>();
        collectTables(node, tables);
        return new ArrayList<>(tables);
    }

    private void collectTables(ExplainPlanNode node, Set<String> tables) {
        if (node == null) return;

        if (node.getTableName() != null && !node.getTableName().isEmpty()) {
            tables.add(node.getTableName());
        }

        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                collectTables(child, tables);
            }
        }
    }

    private List<String> extractJoinTypes(ExplainPlanNode node) {
        Set<String> joinTypes = new HashSet<>();
        collectJoinTypes(node, joinTypes);
        return new ArrayList<>(joinTypes);
    }

    private void collectJoinTypes(ExplainPlanNode node, Set<String> joinTypes) {
        if (node == null) return;

        if (node.getJoinType() != null) {
            joinTypes.add(node.getJoinType());
        }

        String nodeType = node.getNodeType();
        if (nodeType != null && (nodeType.contains("Join") || nodeType.contains("Loop"))) {
            joinTypes.add(nodeType);
        }

        if (node.getChildren() != null) {
            for (ExplainPlanNode child : node.getChildren()) {
                collectJoinTypes(child, joinTypes);
            }
        }
    }

    private String normalizeQuery(String query) {
        if (query == null) return "";

        return query.replaceAll("\\s+", " ")
            .replaceAll("'[^']*'", "?")
            .replaceAll("\\b\\d+\\b", "?")
            .trim()
            .toLowerCase();
    }

    private String extractQuerySignature(String normalizedQuery) {
        // Extract key parts of the query for pattern matching
        String signature = normalizedQuery;

        // Remove column lists. `[^from]+` was a character class ("not f/r/o/m"),
        // so any column containing one of those letters (order_id, from_date)
        // defeated it; a bounded lazy scan to the FROM keyword is what was meant.
        signature = signature.replaceAll("select\\s++[^\\n]{0,500}?\\s++from", "select ... from");

        // Simplify WHERE clause
        signature = signature.replaceAll("where\\s++[^\\n]{0,500}?(\\s++order|\\s++group|\\s++limit|$)", "where ... $1");

        return signature.substring(0, Math.min(100, signature.length()));
    }

    private String extractValue(String block, String prefix) {
        for (String line : block.split("\n")) {
            if (line.trim().toUpperCase().startsWith(prefix.toUpperCase())) {
                return line.substring(line.indexOf(":") + 1).trim();
            }
        }
        return null;
    }
}
