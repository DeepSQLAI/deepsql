package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.model.PerformanceAction.ActionCategory;
import com.dbaagent.model.PerformanceAction.ActionSource;
import com.dbaagent.model.PerformanceAction.ActionStatus;
import com.dbaagent.repository.*;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Aggregates performance recommendations from multiple sources into a unified PerformanceAction list.
 * Each source contributes actions with impact/effort scores that are used to calculate ROI.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class PerformanceActionAggregatorService {

    private final PerformanceActionRepository actionRepository;
    private final IndexRecommendationRepository indexRecommendationRepository;
    private final QueryOptimizationCacheRepository optimizationCacheRepository;
    private final TableClassificationRepository tableClassificationRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ObjectMapper objectMapper;
    private final SchemaIntrospectionService schemaIntrospectionService;

    /**
     * Aggregates all performance actions for a connection.
     * Returns pending actions sorted by ROI.
     */
    public List<PerformanceAction> getAggregatedActions(String connectionId) {
        return actionRepository.findPendingActions(connectionId);
    }

    /**
     * Get top N actions by ROI, reconciled against the live target-DB schema.
     *
     * <p>INDEX-category actions that recommend "create an index on
     * {table}.{column}" but where an index covering that column already exists
     * on the actual database are auto-marked COMPLETED so they stop surfacing.
     * The original key-column analysis caches {@code index_name} per column,
     * but operators routinely create indexes via raw DDL outside DeepSQL — the
     * cached row stays stale, the action stays PENDING, and the digest
     * recommends an already-applied index ("group_id has no index" when it
     * does). Live-schema check fixes this without waiting for the next
     * key-column-analysis refresh.
     *
     * <p>3× overscan lets the caller still get {@code limit} results after
     * auto-completing the stale ones. Failure to read the live schema is
     * treated as "leave the action visible" — better to occasionally show a
     * stale recommendation than to silently hide active ones.
     */
    @Transactional
    public List<PerformanceAction> getTopActions(String connectionId, int limit) {
        int bounded = Math.max(1, limit);
        int overscan = Math.max(bounded, bounded * 3);
        List<PerformanceAction> raw = actionRepository.findTopPendingActions(connectionId, overscan);
        if (raw == null || raw.isEmpty()) return List.of();

        List<PerformanceAction> kept = new ArrayList<>(raw.size());
        java.util.Map<String, java.util.List<com.dbaagent.model.IndexDetail>> indexesByTable =
            new java.util.HashMap<>();
        int autoCompleted = 0;
        for (PerformanceAction action : raw) {
            if (action.getCategory() == ActionCategory.INDEX
                    && action.getTargetObject() != null
                    && action.getTargetSecondary() != null) {
                try {
                    var existing = indexesByTable.computeIfAbsent(
                        action.getTargetObject(),
                        t -> {
                            var details = schemaIntrospectionService.getTableDetails(connectionId, t);
                            return details != null && details.getIndexes() != null
                                ? details.getIndexes()
                                : java.util.List.<com.dbaagent.model.IndexDetail>of();
                        });
                    if (indexCoversColumn(existing, action.getTargetSecondary())) {
                        action.setStatus(ActionStatus.COMPLETED);
                        action.setResolvedAt(java.time.LocalDateTime.now());
                        action.setResolvedBy("system:auto-reconcile");
                        action.setResolutionNotes("Auto-resolved — matching index detected on live schema");
                        actionRepository.save(action);
                        autoCompleted++;
                        continue;
                    }
                } catch (Exception e) {
                    log.debug("Live-schema reconciliation skipped for action {}: {}",
                        action.getId(), e.getMessage());
                    // Fall through — surface the action rather than hide it
                }
            }
            kept.add(action);
            if (kept.size() >= bounded) break;
        }
        if (autoCompleted > 0) {
            log.info("PerformanceAction reconciliation for connection {}: {} index action(s) "
                + "auto-marked COMPLETED against live schema", connectionId, autoCompleted);
        }
        return kept;
    }

    /**
     * Does any index in {@code existing} cover the given column as its leading
     * column? A composite index covers its leading prefix, so an index
     * {@code (group_id, status)} satisfies a "missing index on group_id" action.
     */
    private static boolean indexCoversColumn(
        java.util.List<com.dbaagent.model.IndexDetail> existing,
        String column
    ) {
        if (column == null || column.isBlank()) return false;
        String wanted = column.trim().replace("`", "").replace("\"", "")
            .toLowerCase(java.util.Locale.ROOT);
        for (var idx : existing) {
            java.util.List<String> cols = idx.getColumns();
            if (cols == null || cols.isEmpty()) continue;
            String lead = cols.get(0);
            if (lead == null) continue;
            String norm = lead.trim().replace("`", "").replace("\"", "")
                .toLowerCase(java.util.Locale.ROOT);
            if (wanted.equals(norm)) return true;
        }
        return false;
    }

    /**
     * Get actions filtered by category.
     */
    public List<PerformanceAction> getActionsByCategory(String connectionId, ActionCategory category) {
        return actionRepository.findByConnectionIdAndCategoryOrderByRoiDesc(connectionId, category);
    }

    /**
     * Get actions filtered by source.
     */
    public List<PerformanceAction> getActionsBySource(String connectionId, ActionSource source) {
        return actionRepository.findByConnectionIdAndSourceOrderByRoiDesc(connectionId, source);
    }

    /**
     * Refresh all actions for a connection by re-collecting from all sources.
     */
    @Transactional
    public RefreshResult refreshActions(String connectionId) {
        log.info("Refreshing performance actions for connection: {}", connectionId);

        clearPendingActionsForRefresh(connectionId);

        int indexActions = collectFromIndexAdvisor(connectionId);
        int queryActions = collectFromQueryOptimization(connectionId);
        int schemaActions = collectFromAntiPatterns(connectionId);
        int keyColumnActions = collectFromKeyColumns(connectionId);

        int total = indexActions + queryActions + schemaActions + keyColumnActions;

        log.info("Refreshed {} total actions: {} index, {} query, {} schema, {} key-column",
                total, indexActions, queryActions, schemaActions, keyColumnActions);

        return RefreshResult.builder()
                .totalActions(total)
                .indexActions(indexActions)
                .queryActions(queryActions)
                .schemaActions(schemaActions)
                .keyColumnActions(keyColumnActions)
                .refreshedAt(LocalDateTime.now())
                .build();
    }

    private void clearPendingActionsForRefresh(String connectionId) {
        actionRepository.deleteByConnectionIdAndSourceAndStatus(
            connectionId, ActionSource.INDEX_ADVISOR, ActionStatus.PENDING);
        actionRepository.deleteByConnectionIdAndSourceAndStatus(
            connectionId, ActionSource.SLOW_QUERY_ANALYSIS, ActionStatus.PENDING);
        actionRepository.deleteByConnectionIdAndSourceAndStatus(
            connectionId, ActionSource.ANTI_PATTERN_DETECTION, ActionStatus.PENDING);
        actionRepository.deleteByConnectionIdAndSourceAndStatus(
            connectionId, ActionSource.KEY_COLUMN_ANALYSIS, ActionStatus.PENDING);
    }

    /**
     * Collect index recommendations from IndexRecommendationRepository.
     */
    @Transactional
    public int collectFromIndexAdvisor(String connectionId) {
        List<IndexRecommendationEntity> recommendations =
                indexRecommendationRepository.findByConnectionIdOrderByPriorityAscCreatedAtDesc(connectionId);

        int count = 0;
        for (IndexRecommendationEntity rec : recommendations) {
            // Skip if already exists
            if (actionRepository.findByConnectionIdAndSourceReferenceId(connectionId, rec.getId()).isPresent()) {
                continue;
            }

            int impact = calculateIndexImpact(rec);
            int effort = calculateIndexEffort(rec);

            PerformanceAction action = PerformanceAction.builder()
                    .connectionId(connectionId)
                    .category(ActionCategory.INDEX)
                    .source(ActionSource.INDEX_ADVISOR)
                    .status(ActionStatus.PENDING)
                    .title(generateIndexTitle(rec))
                    .description(rec.getReason())
                    .targetObject(rec.getTableName())
                    .targetSecondary(rec.getColumnNames())
                    .impactScore(impact)
                    .effortScore(effort)
                    .sqlStatement(rec.getCreateStatement())
                    .sourceReferenceId(rec.getId())
                    .queriesAffected(rec.getAffectedQueries() != null ? rec.getAffectedQueries().longValue() : null)
                    .build();

            action.calculateRoi();
            actionRepository.save(action);
            count++;
        }

        return count;
    }

    /**
     * Collect query rewrite suggestions from cached AI optimizations.
     */
    @Transactional
    public int collectFromQueryOptimization(String connectionId) {
        List<QueryOptimizationCache> cached =
                optimizationCacheRepository.findByConnectionIdOrderByUpdatedAtDesc(connectionId);

        int count = 0;
        for (QueryOptimizationCache opt : cached) {
            // Skip if already exists
            if (actionRepository.findByConnectionIdAndSourceReferenceId(connectionId, opt.getId()).isPresent()) {
                continue;
            }

            // Only create action if there's meaningful optimization
            if (opt.getOptimizedQuery() == null && opt.getEstimatedImprovement() == null) {
                continue;
            }

            int impact = opt.getEstimatedImprovement() != null
                    ? Math.min(100, (int) Math.round(opt.getEstimatedImprovement()))
                    : 50;
            int effort = 30; // Query rewrites are typically moderate effort

            String title = "Query Optimization Available";
            String description = opt.getExplanation();
            if (description == null && opt.getSuggestionsJson() != null) {
                description = "AI-powered optimization suggestions available";
            }

            PerformanceAction action = PerformanceAction.builder()
                    .connectionId(connectionId)
                    .category(ActionCategory.QUERY_REWRITE)
                    .source(ActionSource.SLOW_QUERY_ANALYSIS)
                    .status(ActionStatus.PENDING)
                    .title(title)
                    .description(description)
                    .targetObject(opt.getQueryFingerprint())
                    .impactScore(impact)
                    .effortScore(effort)
                    .optimizedQuery(opt.getOptimizedQuery())
                    .sourceReferenceId(opt.getId())
                    .hasCachedAnalysis(true)
                    .metadataJson(buildQueryMetadata(opt))
                    .build();

            action.calculateRoi();
            actionRepository.save(action);
            count++;
        }

        return count;
    }

    /**
     * Collect schema improvements from anti-pattern detection.
     */
    @Transactional
    public int collectFromAntiPatterns(String connectionId) {
        List<TableClassification> tables =
                tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId);

        int count = 0;
        for (TableClassification tc : tables) {
            if (tc.getAntiPatterns() == null || tc.getAntiPatterns().isEmpty()) {
                continue;
            }

            for (Map<String, Object> antiPattern : tc.getAntiPatterns()) {
                String type = (String) antiPattern.get("type");
                String severity = (String) antiPattern.get("severity");
                String recommendation = (String) antiPattern.get("recommendation");

                // Generate unique reference ID
                String refId = "anti:" + tc.getTableName() + ":" + type;

                // Skip if already exists
                if (actionRepository.findByConnectionIdAndSourceReferenceId(connectionId, refId).isPresent()) {
                    continue;
                }

                int impact = calculateAntiPatternImpact(severity);
                int effort = calculateAntiPatternEffort(type);

                PerformanceAction action = PerformanceAction.builder()
                        .connectionId(connectionId)
                        .category(ActionCategory.SCHEMA)
                        .source(ActionSource.ANTI_PATTERN_DETECTION)
                        .status(ActionStatus.PENDING)
                        .title(formatAntiPatternTitle(type))
                        .description(recommendation != null ? recommendation :
                                "Anti-pattern detected: " + type)
                        .targetObject(tc.getTableName())
                        .impactScore(impact)
                        .effortScore(effort)
                        .sourceReferenceId(refId)
                        .metadataJson(toJson(antiPattern))
                        .build();

                action.calculateRoi();
                actionRepository.save(action);
                count++;
            }
        }

        return count;
    }

    /**
     * Collect index recommendations from key column analysis.
     * Focuses on frequently-used columns without indexes.
     */
    @Transactional
    public int collectFromKeyColumns(String connectionId) {
        List<KeyColumnAnalysis> keyColumns =
                keyColumnAnalysisRepository.findByConnectionIdAndHasAntiPatternsTrueOrderByImportanceScoreDesc(
                        connectionId);

        int count = 0;
        for (KeyColumnAnalysis kc : keyColumns) {
            // Skip if column already has an index
            if (kc.getIndexName() != null) {
                continue;
            }

            // Generate unique reference ID
            String refId = "kc:" + kc.getTableName() + ":" + kc.getColumnName();

            // Skip if already exists
            if (actionRepository.findByConnectionIdAndSourceReferenceId(connectionId, refId).isPresent()) {
                continue;
            }

            int impact = kc.getImportanceScore() != null
                    ? Math.min(100, kc.getImportanceScore().intValue())
                    : 50;
            int effort = 15; // Creating a single index is low effort

            String indexSql = String.format("CREATE INDEX idx_%s_%s ON %s(%s);",
                    kc.getTableName().toLowerCase(),
                    kc.getColumnName().toLowerCase(),
                    kc.getTableName(),
                    kc.getColumnName());

            String title = "Missing Index on Key Column";
            String description = String.format(
                    "Column %s.%s is used in %d queries (WHERE: %d, JOIN: %d, ORDER BY: %d) but has no index",
                    kc.getTableName(), kc.getColumnName(),
                    kc.getTotalUsageCount(),
                    kc.getWhereCount(), kc.getJoinCount(), kc.getOrderByCount());

            PerformanceAction action = PerformanceAction.builder()
                    .connectionId(connectionId)
                    .category(ActionCategory.INDEX)
                    .source(ActionSource.KEY_COLUMN_ANALYSIS)
                    .status(ActionStatus.PENDING)
                    .title(title)
                    .description(description)
                    .targetObject(kc.getTableName())
                    .targetSecondary(kc.getColumnName())
                    .impactScore(impact)
                    .effortScore(effort)
                    .sqlStatement(indexSql)
                    .sourceReferenceId(refId)
                    .queriesAffected((long) kc.getDistinctQueriesCount())
                    .build();

            action.calculateRoi();
            actionRepository.save(action);
            count++;
        }

        return count;
    }

    /**
     * Get a single action by ID.
     */
    public Optional<PerformanceAction> getActionById(String actionId) {
        return actionRepository.findById(actionId);
    }

    /**
     * Update action status.
     */
    @Transactional
    public PerformanceAction updateStatus(String actionId, ActionStatus status, String resolvedBy, String notes) {
        PerformanceAction action = actionRepository.findById(actionId)
                .orElseThrow(() -> new IllegalArgumentException("Action not found: " + actionId));

        action.setStatus(status);
        if (status == ActionStatus.COMPLETED || status == ActionStatus.DISMISSED) {
            action.setResolvedAt(LocalDateTime.now());
            action.setResolvedBy(resolvedBy);
            action.setResolutionNotes(notes);
        }

        return actionRepository.save(action);
    }

    /**
     * Get summary statistics for a connection.
     */
    public ActionSummary getSummary(String connectionId) {
        // Use individual count queries for robustness
        long pendingCount = actionRepository.countByConnectionIdAndStatus(connectionId, ActionStatus.PENDING);
        long completedCount = actionRepository.countByConnectionIdAndStatus(connectionId, ActionStatus.COMPLETED);
        long dismissedCount = actionRepository.countByConnectionIdAndStatus(connectionId, ActionStatus.DISMISSED);

        // Get additional stats
        Double avgRoi = actionRepository.getAverageRoiForPending(connectionId);
        Long totalImpact = actionRepository.getTotalPotentialImpact(connectionId);

        // Calculate top ROI from pending actions
        List<PerformanceAction> topActions = actionRepository.findTopPendingActions(connectionId, 1);
        double topRoi = topActions.isEmpty() ? 0 : (topActions.get(0).getRoi() != null ? topActions.get(0).getRoi() : 0);

        // Calculate average impact
        double avgImpact = pendingCount > 0 && totalImpact != null ? (double) totalImpact / pendingCount : 0;

        List<Object[]> categoryBreakdown = actionRepository.countPendingByCategory(connectionId);
        Map<String, Long> byCategory = new HashMap<>();
        for (Object[] row : categoryBreakdown) {
            if (row[0] != null && row[1] != null) {
                byCategory.put(((ActionCategory) row[0]).name(), ((Number) row[1]).longValue());
            }
        }

        return ActionSummary.builder()
                .pendingCount(pendingCount)
                .completedCount(completedCount)
                .dismissedCount(dismissedCount)
                .topRoi(topRoi)
                .avgImpact(avgImpact)
                .byCategory(byCategory)
                .build();
    }

    // ==================== Helper Methods ====================

    private int calculateIndexImpact(IndexRecommendationEntity rec) {
        // Use estimatedImpact if available
        if (rec.getEstimatedImpact() != null) {
            return Math.min(100, rec.getEstimatedImpact());
        }

        // Base impact from priority (Priority is an enum)
        int base = switch (rec.getPriority()) {
            case HIGH -> 75;
            case MEDIUM -> 50;
            case LOW -> 30;
        };

        // Boost based on affected queries count
        if (rec.getAffectedQueries() != null && rec.getAffectedQueries() > 100) {
            base = Math.min(100, base + 10);
        }

        return base;
    }

    private int calculateIndexEffort(IndexRecommendationEntity rec) {
        // Creating an index is generally low effort
        // Use affected queries as proxy for table size
        if (rec.getAffectedQueries() != null && rec.getAffectedQueries() > 1000) {
            return 35; // High-impact index (more queries affected, might be larger table)
        }
        return 15; // Standard index creation effort
    }

    private String generateIndexTitle(IndexRecommendationEntity rec) {
        String columns = rec.getColumnNames();
        if (columns != null && columns.contains(",")) {
            return "Missing Composite Index";
        }
        return "Missing Index";
    }

    private int calculateAntiPatternImpact(String severity) {
        return switch (severity) {
            case "CRITICAL" -> 90;
            case "HIGH" -> 70;
            case "MEDIUM" -> 50;
            case "LOW" -> 30;
            default -> 40;
        };
    }

    private int calculateAntiPatternEffort(String type) {
        // Schema changes are generally high effort
        return switch (type) {
            case "GOD_TABLE" -> 85;         // Requires significant refactoring
            case "WIDE_TABLE" -> 70;        // May need table splitting
            case "EAV_PATTERN" -> 80;       // Complex migration
            case "POLYMORPHIC_ASSOCIATION" -> 75;
            case "MISSING_TIMESTAMP" -> 20; // Easy to add columns
            case "OVER_INDEXED" -> 15;      // Just drop indexes
            case "UNDER_INDEXED" -> 20;     // Add indexes
            case "DUPLICATE_INDEXES" -> 15; // Drop duplicates
            case "UNUSED_COLUMNS" -> 25;    // Remove columns
            default -> 50;
        };
    }

    private String formatAntiPatternTitle(String type) {
        return switch (type) {
            case "GOD_TABLE" -> "God Table Detected";
            case "WIDE_TABLE" -> "Wide Table (Too Many Columns)";
            case "EAV_PATTERN" -> "Entity-Attribute-Value Anti-Pattern";
            case "POLYMORPHIC_ASSOCIATION" -> "Polymorphic Association Detected";
            case "MISSING_TIMESTAMP" -> "Missing Timestamp Columns";
            case "OVER_INDEXED" -> "Over-Indexed Table";
            case "UNDER_INDEXED" -> "Under-Indexed Table";
            case "DUPLICATE_INDEXES" -> "Duplicate Indexes Found";
            case "UNUSED_COLUMNS" -> "Unused Columns Detected";
            case "SPARSE_TABLE" -> "Sparse Table (Many NULLs)";
            default -> "Schema Anti-Pattern: " + type;
        };
    }

    private String buildQueryMetadata(QueryOptimizationCache opt) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("fingerprint", opt.getQueryFingerprint());
        metadata.put("estimatedImprovement", opt.getEstimatedImprovement());
        metadata.put("accessCount", opt.getAccessCount());
        metadata.put("cachedAt", opt.getCreatedAt());
        return toJson(metadata);
    }

    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON", e);
            return "{}";
        }
    }

    // ==================== DTOs ====================

    @Data
    @Builder
    public static class RefreshResult {
        private int totalActions;
        private int indexActions;
        private int queryActions;
        private int schemaActions;
        private int keyColumnActions;
        private int configActions;
        private LocalDateTime refreshedAt;
    }

    @Data
    @Builder
    public static class ActionSummary {
        private long pendingCount;
        private long completedCount;
        private long dismissedCount;
        private double topRoi;
        private double avgImpact;
        private Map<String, Long> byCategory;
    }
}
