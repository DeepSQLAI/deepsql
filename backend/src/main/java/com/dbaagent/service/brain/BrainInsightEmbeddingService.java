package com.dbaagent.service.brain;

import com.dbaagent.model.TrainingDataSearchDocument;
import com.dbaagent.model.brain.PlanPattern;
import com.dbaagent.model.brain.WorkloadProfile;
import com.dbaagent.repository.brain.PlanPatternRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.service.VectorSearchService;
import com.dbaagent.service.EmbeddingService;
import com.dbaagent.service.brain.query.AdaptivePlanScoringService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Brain Insight Embedding Service
 *
 * Embeds Brain insights into Azure AI Search for semantic RAG retrieval.
 * This enables the chat agent to find relevant optimization insights
 * when users ask about performance tuning, query optimization, etc.
 *
 * Document Types:
 * - QUERY_PATTERN: Reliable plan patterns with optimization suggestions
 * - WORKLOAD_INSIGHT: Workload characterization and recommendations
 * - CARDINALITY_INSIGHT: Cardinality accuracy recommendations
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainInsightEmbeddingService {

    private final VectorSearchService azureSearchService;
    private final EmbeddingService embeddingService;
    private final PlanPatternRepository planPatternRepository;
    private final WorkloadProfileRepository workloadProfileRepository;
    private final AdaptivePlanScoringService adaptivePlanScoringService;
    private final ObjectMapper objectMapper;

    @Value("${brain.embedding.delay-between-ms:500}")
    private int delayBetweenEmbeddingsMs;

    @Value("${brain.embedding.batch-size:50}")
    private int batchSize;

    // ==================== Plan Pattern Embedding ====================

    /**
     * Embed all reliable plan patterns for a connection.
     * Reliable patterns have >= 5 uses and >= 60% effectiveness.
     *
     * @param connectionId Connection ID
     * @return Number of patterns embedded
     */
    public int embedPlanPatterns(String connectionId) {
        if (!azureSearchService.isEnabled()) {
            log.debug("Azure Search disabled, skipping plan pattern embedding");
            return 0;
        }

        List<PlanPattern> reliablePatterns = planPatternRepository
            .findReliablePatterns(connectionId, 60.0, 5);

        if (reliablePatterns.isEmpty()) {
            log.info("No reliable plan patterns to embed for connection {}", connectionId);
            return 0;
        }

        log.info("Embedding {} reliable plan patterns for connection {}", reliablePatterns.size(), connectionId);

        int embedded = 0;
        List<PlanPattern> toProcess = reliablePatterns.stream().limit(batchSize).toList();

        for (PlanPattern pattern : toProcess) {
            try {
                if (embedPlanPattern(pattern)) {
                    embedded++;
                    if (delayBetweenEmbeddingsMs > 0) {
                        Thread.sleep(delayBetweenEmbeddingsMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Plan pattern embedding interrupted");
                break;
            } catch (Exception e) {
                log.warn("Failed to embed plan pattern {}: {}", pattern.getId(), e.getMessage());
            }
        }

        log.info("Embedded {}/{} plan patterns for connection {}", embedded, toProcess.size(), connectionId);
        return embedded;
    }

    /**
     * Embed a single plan pattern.
     */
    private boolean embedPlanPattern(PlanPattern pattern) {
        try {
            // Build semantic text for embedding
            String embeddingText = buildPlanPatternEmbeddingText(pattern);

            // Create embedding
            List<Double> embedding = embeddingService.createEmbedding(embeddingText);
            if (embedding.isEmpty()) {
                log.warn("Failed to create embedding for plan pattern {}", pattern.getId());
                return false;
            }

            List<Float> contentVector = embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            // Build document
            String documentId = buildPlanPatternDocumentId(pattern);
            Map<String, Object> metadata = buildPlanPatternMetadata(pattern);

            TrainingDataSearchDocument document = TrainingDataSearchDocument.builder()
                .id(documentId)
                .connectionId(pattern.getConnectionId())
                .type("QUERY_PATTERN")
                .content(embeddingText)
                .sql(pattern.getQueryPattern())
                .objectName(pattern.getPatternType() != null ? pattern.getPatternType().name() : "MIXED")
                .description(buildPlanPatternDescription(pattern))
                .tablesUsed(pattern.getTablesInvolved() != null ?
                    String.join(",", pattern.getTablesInvolved()) : null)
                .contentVector(contentVector)
                .metadata(toJson(metadata))
                .build();

            azureSearchService.indexDocument(document);

            log.debug("Embedded plan pattern {} ({} tables, {} suggestions)",
                pattern.getId(),
                pattern.getTablesInvolved() != null ? pattern.getTablesInvolved().size() : 0,
                pattern.getSuggestions() != null ? pattern.getSuggestions().size() : 0);

            return true;

        } catch (Exception e) {
            log.error("Failed to embed plan pattern {}: {}", pattern.getId(), e.getMessage(), e);
            return false;
        }
    }

    private String buildPlanPatternEmbeddingText(PlanPattern pattern) {
        StringBuilder sb = new StringBuilder();

        sb.append("Query optimization pattern for ");
        if (pattern.getPatternType() != null) {
            sb.append(formatPatternType(pattern.getPatternType())).append(" queries");
        } else {
            sb.append("database queries");
        }
        sb.append(".\n\n");

        // Tables involved
        if (pattern.getTablesInvolved() != null && !pattern.getTablesInvolved().isEmpty()) {
            sb.append("Tables: ").append(String.join(", ", pattern.getTablesInvolved())).append("\n");
        }

        // Join types
        if (pattern.getJoinTypes() != null && !pattern.getJoinTypes().isEmpty()) {
            sb.append("Join types: ").append(String.join(", ", pattern.getJoinTypes())).append("\n");
        }

        // Query pattern
        if (pattern.getQueryPattern() != null) {
            sb.append("\nQuery pattern:\n").append(pattern.getQueryPattern()).append("\n");
        }

        // Optimization suggestions
        if (pattern.getSuggestions() != null && !pattern.getSuggestions().isEmpty()) {
            sb.append("\nOptimization suggestions:\n");
            for (Map<String, Object> suggestion : pattern.getSuggestions()) {
                String type = (String) suggestion.get("type");
                String message = (String) suggestion.get("message");
                String recommendation = (String) suggestion.get("recommendation");

                if (type != null) sb.append("- ").append(type);
                if (message != null) sb.append(": ").append(message);
                if (recommendation != null) sb.append(" → ").append(recommendation);
                sb.append("\n");
            }
        }

        // Optimized query if available
        if (pattern.getOptimizedQuery() != null) {
            sb.append("\nOptimized query:\n").append(pattern.getOptimizedQuery()).append("\n");
        }

        // Effectiveness
        if (pattern.getEffectivenessScore() != null) {
            sb.append("\nEffectiveness: ").append(String.format("%.0f%%", pattern.getEffectivenessScore()));
            sb.append(" (based on ").append(pattern.getUsageCount()).append(" observations)\n");
        }

        return sb.toString();
    }

    private String buildPlanPatternDescription(PlanPattern pattern) {
        StringBuilder sb = new StringBuilder();
        sb.append("Query pattern: ");
        if (pattern.getPatternType() != null) {
            sb.append(formatPatternType(pattern.getPatternType()));
        }
        if (pattern.getTablesInvolved() != null && !pattern.getTablesInvolved().isEmpty()) {
            sb.append(" on ").append(String.join(", ", pattern.getTablesInvolved()));
        }
        if (pattern.getSuggestions() != null) {
            sb.append(" (").append(pattern.getSuggestions().size()).append(" optimization tips)");
        }
        return sb.toString();
    }

    private String buildPlanPatternDocumentId(PlanPattern pattern) {
        return String.format("%s::QUERY_PATTERN::%s", pattern.getConnectionId(), pattern.getId());
    }

    private Map<String, Object> buildPlanPatternMetadata(PlanPattern pattern) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("patternId", pattern.getId());
        metadata.put("patternType", pattern.getPatternType() != null ? pattern.getPatternType().name() : null);
        metadata.put("tablesInvolved", pattern.getTablesInvolved());
        metadata.put("joinTypes", pattern.getJoinTypes());
        metadata.put("effectivenessScore", pattern.getEffectivenessScore());
        metadata.put("usageCount", pattern.getUsageCount());
        metadata.put("suggestionCount", pattern.getSuggestions() != null ? pattern.getSuggestions().size() : 0);
        metadata.put("embeddedAt", LocalDateTime.now().toString());
        return metadata;
    }

    private String formatPatternType(PlanPattern.PatternType type) {
        return switch (type) {
            case SEQUENTIAL_SCAN -> "full table scan";
            case INDEX_SCAN -> "index scan";
            case INDEX_ONLY_SCAN -> "covering index scan";
            case NESTED_LOOP -> "nested loop join";
            case HASH_JOIN -> "hash join";
            case MERGE_JOIN -> "merge join";
            case SORT -> "sorting";
            case AGGREGATE -> "aggregation";
            case SUBQUERY -> "subquery";
            case CTE -> "common table expression";
            case MIXED -> "mixed operations";
        };
    }

    // ==================== Workload Insight Embedding ====================

    /**
     * Embed workload profile and recommendations for a connection.
     *
     * @param connectionId Connection ID
     * @return true if embedded successfully
     */
    public boolean embedWorkloadInsight(String connectionId) {
        if (!azureSearchService.isEnabled()) {
            log.debug("Azure Search disabled, skipping workload insight embedding");
            return false;
        }

        Optional<WorkloadProfile> profileOpt = workloadProfileRepository.findByConnectionId(connectionId);
        if (profileOpt.isEmpty()) {
            log.info("No workload profile to embed for connection {}", connectionId);
            return false;
        }

        WorkloadProfile profile = profileOpt.get();
        try {
            String embeddingText = buildWorkloadEmbeddingText(profile);

            List<Double> embedding = embeddingService.createEmbedding(embeddingText);
            if (embedding.isEmpty()) {
                log.warn("Failed to create embedding for workload profile {}", connectionId);
                return false;
            }

            List<Float> contentVector = embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            String documentId = String.format("%s::WORKLOAD_INSIGHT", connectionId);
            Map<String, Object> metadata = buildWorkloadMetadata(profile);

            TrainingDataSearchDocument document = TrainingDataSearchDocument.builder()
                .id(documentId)
                .connectionId(connectionId)
                .type("WORKLOAD_INSIGHT")
                .content(embeddingText)
                .objectName(profile.getWorkloadType().name())
                .description(buildWorkloadDescription(profile))
                .contentVector(contentVector)
                .metadata(toJson(metadata))
                .build();

            azureSearchService.indexDocument(document);

            log.info("Embedded workload insight for connection {} (type: {}, confidence: {}%)",
                connectionId, profile.getWorkloadType(),
                profile.getClassificationConfidence() != null ?
                    String.format("%.0f", profile.getClassificationConfidence()) : "N/A");

            return true;

        } catch (Exception e) {
            log.error("Failed to embed workload insight for {}: {}", connectionId, e.getMessage(), e);
            return false;
        }
    }

    private String buildWorkloadEmbeddingText(WorkloadProfile profile) {
        StringBuilder sb = new StringBuilder();

        sb.append("Database workload analysis and optimization recommendations.\n\n");

        // Workload type
        sb.append("Workload Type: ").append(profile.getWorkloadType().name());
        if (profile.getClassificationConfidence() != null) {
            sb.append(" (").append(String.format("%.0f%%", profile.getClassificationConfidence()))
              .append(" confidence)");
        }
        sb.append("\n\n");

        // Classification reasoning
        if (profile.getClassificationReasoning() != null) {
            sb.append("Analysis: ").append(profile.getClassificationReasoning()).append("\n\n");
        }

        // Performance metrics
        sb.append("Performance Metrics:\n");
        if (profile.getThroughputQps() != null && profile.getThroughputQps() > 0) {
            sb.append("- Throughput: ").append(String.format("%.1f", profile.getThroughputQps()))
              .append(" queries/second\n");
        }
        if (profile.getLatencyP50Ms() != null && profile.getLatencyP50Ms() > 0) {
            sb.append("- P50 Latency: ").append(String.format("%.1f", profile.getLatencyP50Ms())).append(" ms\n");
        }
        if (profile.getLatencyP99Ms() != null && profile.getLatencyP99Ms() > 0) {
            sb.append("- P99 Latency: ").append(String.format("%.1f", profile.getLatencyP99Ms())).append(" ms\n");
        }

        // Key metrics
        if (profile.getKeyMetricValues() != null && !profile.getKeyMetricValues().isEmpty()) {
            sb.append("\nKey Metrics:\n");
            for (Map.Entry<String, Double> entry : profile.getKeyMetricValues().entrySet()) {
                sb.append("- ").append(formatMetricName(entry.getKey())).append(": ");
                sb.append(String.format("%.2f", entry.getValue())).append("\n");
            }
        }

        // Workload-specific recommendations
        sb.append("\nOptimization Recommendations:\n");
        sb.append(getWorkloadRecommendations(profile.getWorkloadType()));

        return sb.toString();
    }

    private String getWorkloadRecommendations(WorkloadProfile.WorkloadType type) {
        return switch (type) {
            case OLTP -> """
                - Optimize for low latency and high concurrency
                - Use connection pooling to handle many short-lived connections
                - Keep transactions short to minimize lock contention
                - Index primary key lookups and frequent filter columns
                - Consider read replicas for scaling read traffic
                - Tune innodb_buffer_pool_size for hot data caching
                """;
            case OLAP -> """
                - Optimize for throughput and complex query processing
                - Enable parallel query execution (max_parallel_workers)
                - Increase work_mem for large sorts and hash operations
                - Consider columnar storage or materialized views for aggregations
                - Use covering indexes for analytical queries
                - Partition large fact tables by date for query pruning
                """;
            case MIXED -> """
                - Balance between transactional and analytical workloads
                - Consider read replicas to offload analytical queries
                - Monitor query patterns to identify hot spots
                - Use query routing to separate OLTP and OLAP traffic
                - Tune buffer pools for both random and sequential access
                """;
            case WRITE_HEAVY -> """
                - Optimize for write throughput
                - Batch inserts where possible (bulk loading)
                - Minimize indexes on write-heavy tables
                - Increase innodb_log_buffer_size and wal_buffers
                - Consider async replication for durability trade-offs
                - Monitor table bloat and schedule regular maintenance
                """;
            case READ_HEAVY -> """
                - Optimize for read performance and caching
                - Maximize buffer pool utilization
                - Create covering indexes for frequent queries
                - Consider query result caching (Redis/application cache)
                - Use read replicas to scale horizontally
                - Analyze and optimize slow SELECT queries
                """;
            case BATCH -> """
                - Optimize for large batch operations
                - Increase checkpoint_completion_target for smoother I/O
                - Use COPY or bulk insert statements
                - Disable indexes during bulk loads, rebuild after
                - Monitor and tune autovacuum for post-batch cleanup
                - Consider partitioning for easier data management
                """;
            case REAL_TIME, UNKNOWN -> """
                - Monitor query patterns to better classify workload
                - Collect more metrics for accurate characterization
                - Start with balanced configuration
                - Use Brain workload analysis to refine recommendations
                """;
        };
    }

    private String buildWorkloadDescription(WorkloadProfile profile) {
        return String.format("%s workload with %.0f%% confidence. %s",
            profile.getWorkloadType().name(),
            profile.getClassificationConfidence() != null ? profile.getClassificationConfidence() : 0,
            profile.getThroughputQps() != null ?
                String.format("%.1f QPS", profile.getThroughputQps()) : "");
    }

    private Map<String, Object> buildWorkloadMetadata(WorkloadProfile profile) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("workloadType", profile.getWorkloadType().name());
        metadata.put("confidence", profile.getClassificationConfidence());
        metadata.put("throughputQps", profile.getThroughputQps());
        metadata.put("latencyP50Ms", profile.getLatencyP50Ms());
        metadata.put("latencyP99Ms", profile.getLatencyP99Ms());
        metadata.put("keyMetrics", profile.getKeyMetricValues());
        metadata.put("profiledAt", profile.getProfiledAt() != null ? profile.getProfiledAt().toString() : null);
        metadata.put("embeddedAt", LocalDateTime.now().toString());
        return metadata;
    }

    private String formatMetricName(String metric) {
        return metric.replace("_", " ").toLowerCase();
    }

    // ==================== Cardinality Insight Embedding ====================

    /**
     * Embed cardinality accuracy recommendations for a connection.
     *
     * @param connectionId Connection ID
     * @return Number of recommendations embedded
     */
    @SuppressWarnings("unchecked")
    public int embedCardinalityInsights(String connectionId) {
        if (!azureSearchService.isEnabled()) {
            log.debug("Azure Search disabled, skipping cardinality insight embedding");
            return 0;
        }

        try {
            Map<String, Object> accuracyStats = adaptivePlanScoringService.getCardinalityAccuracyStats(connectionId);
            List<Map<String, Object>> recommendations =
                (List<Map<String, Object>>) accuracyStats.get("recommendations");

            if (recommendations == null || recommendations.isEmpty()) {
                log.info("No cardinality recommendations to embed for connection {}", connectionId);
                return 0;
            }

            // Build combined embedding text for all recommendations
            String embeddingText = buildCardinalityEmbeddingText(connectionId, accuracyStats, recommendations);

            List<Double> embedding = embeddingService.createEmbedding(embeddingText);
            if (embedding.isEmpty()) {
                log.warn("Failed to create embedding for cardinality insights {}", connectionId);
                return 0;
            }

            List<Float> contentVector = embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            String documentId = String.format("%s::CARDINALITY_INSIGHT", connectionId);
            Map<String, Object> metadata = buildCardinalityMetadata(accuracyStats, recommendations);

            TrainingDataSearchDocument document = TrainingDataSearchDocument.builder()
                .id(documentId)
                .connectionId(connectionId)
                .type("CARDINALITY_INSIGHT")
                .content(embeddingText)
                .objectName("CARDINALITY_ACCURACY")
                .description(buildCardinalityDescription(accuracyStats, recommendations))
                .contentVector(contentVector)
                .metadata(toJson(metadata))
                .build();

            azureSearchService.indexDocument(document);

            log.info("Embedded {} cardinality recommendations for connection {}", recommendations.size(), connectionId);
            return recommendations.size();

        } catch (Exception e) {
            log.error("Failed to embed cardinality insights for {}: {}", connectionId, e.getMessage(), e);
            return 0;
        }
    }

    @SuppressWarnings("unchecked")
    private String buildCardinalityEmbeddingText(String connectionId,
                                                  Map<String, Object> stats,
                                                  List<Map<String, Object>> recommendations) {
        StringBuilder sb = new StringBuilder();

        sb.append("Database cardinality estimation accuracy analysis and recommendations.\n\n");

        // Overall accuracy
        Double overallAccuracy = (Double) stats.get("overallAccuracy");
        Long totalEstimates = stats.get("totalEstimates") instanceof Number n ? n.longValue() : 0L;
        Long overestimates = stats.get("overestimates") instanceof Number n ? n.longValue() : 0L;
        Long underestimates = stats.get("underestimates") instanceof Number n ? n.longValue() : 0L;

        sb.append("Overall Accuracy: ");
        if (overallAccuracy != null) {
            sb.append(String.format("%.1f%%", overallAccuracy * 100));
        } else {
            sb.append("N/A");
        }
        sb.append("\n");

        sb.append("Estimation Breakdown:\n");
        sb.append("- Total estimates analyzed: ").append(totalEstimates).append("\n");
        sb.append("- Overestimates: ").append(overestimates).append("\n");
        sb.append("- Underestimates: ").append(underestimates).append("\n\n");

        // Per-table accuracy
        Map<String, Double> perTableAccuracy = (Map<String, Double>) stats.get("perTableAccuracy");
        if (perTableAccuracy != null && !perTableAccuracy.isEmpty()) {
            sb.append("Tables with Low Accuracy:\n");
            perTableAccuracy.entrySet().stream()
                .filter(e -> e.getValue() < 0.5)
                .sorted(Map.Entry.comparingByValue())
                .limit(5)
                .forEach(e -> sb.append("- ").append(e.getKey())
                    .append(": ").append(String.format("%.1f%%", e.getValue() * 100)).append("\n"));
            sb.append("\n");
        }

        // Recommendations
        sb.append("Recommendations:\n\n");
        for (Map<String, Object> rec : recommendations) {
            String priority = (String) rec.get("priority");
            String title = (String) rec.get("title");
            String description = (String) rec.get("description");
            String sql = (String) rec.get("sql");
            String impact = (String) rec.get("impact");

            sb.append("[").append(priority).append("] ").append(title).append("\n");
            if (description != null) {
                sb.append(description).append("\n");
            }
            if (sql != null) {
                sb.append("SQL: ").append(sql).append("\n");
            }
            if (impact != null) {
                sb.append("Impact: ").append(impact).append("\n");
            }
            sb.append("\n");
        }

        // General guidance
        sb.append("When to use ANALYZE TABLE:\n");
        sb.append("- After bulk data changes (large inserts, deletes, updates)\n");
        sb.append("- When query plans suddenly become suboptimal\n");
        sb.append("- Periodically as part of maintenance (weekly/monthly)\n");
        sb.append("- When cardinality accuracy drops below 50%\n\n");

        sb.append("When to use histograms (MySQL 8.0+):\n");
        sb.append("- Columns with skewed data distribution\n");
        sb.append("- Columns consistently underestimated by optimizer\n");
        sb.append("- Enum-like columns with non-uniform value frequencies\n");

        return sb.toString();
    }

    private String buildCardinalityDescription(Map<String, Object> stats, List<Map<String, Object>> recommendations) {
        Double overallAccuracy = (Double) stats.get("overallAccuracy");
        return String.format("Cardinality accuracy: %.1f%%. %d recommendations for optimizer statistics.",
            overallAccuracy != null ? overallAccuracy * 100 : 0,
            recommendations.size());
    }

    private Map<String, Object> buildCardinalityMetadata(Map<String, Object> stats,
                                                          List<Map<String, Object>> recommendations) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("overallAccuracy", stats.get("overallAccuracy"));
        metadata.put("totalEstimates", stats.get("totalEstimates"));
        metadata.put("overestimates", stats.get("overestimates"));
        metadata.put("underestimates", stats.get("underestimates"));
        metadata.put("recommendationCount", recommendations.size());
        metadata.put("recommendationTypes", recommendations.stream()
            .map(r -> r.get("type"))
            .distinct()
            .toList());
        metadata.put("embeddedAt", LocalDateTime.now().toString());
        return metadata;
    }

    // ==================== Batch Operations ====================

    /**
     * Embed all Brain insights for a connection.
     *
     * @param connectionId Connection ID
     * @return Summary of what was embedded
     */
    public Map<String, Object> embedAllInsights(String connectionId) {
        Map<String, Object> result = new HashMap<>();

        int patterns = embedPlanPatterns(connectionId);
        result.put("planPatterns", patterns);

        boolean workload = embedWorkloadInsight(connectionId);
        result.put("workloadInsight", workload);

        int cardinality = embedCardinalityInsights(connectionId);
        result.put("cardinalityRecommendations", cardinality);

        result.put("success", true);
        result.put("connectionId", connectionId);
        result.put("embeddedAt", LocalDateTime.now().toString());

        log.info("Embedded all Brain insights for {}: {} patterns, workload={}, {} cardinality recs",
            connectionId, patterns, workload, cardinality);

        return result;
    }

    // ==================== Utilities ====================

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "{}";
        }
    }
}
