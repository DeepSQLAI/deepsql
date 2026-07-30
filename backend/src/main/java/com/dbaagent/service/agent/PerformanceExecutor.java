package com.dbaagent.service.agent;

import com.dbaagent.model.IndexRecommendation;
import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.ColumnAntiPattern;
import com.dbaagent.model.CompositeIndexRecommendation;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.PerformanceAnalysis;
import com.dbaagent.model.PerformanceAction;
import com.dbaagent.model.PerformanceSnapshot;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.QueryPerformanceRegression;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.ActiveQuery;
import com.dbaagent.model.CapacityForecast;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.SentinelRecommendation;
import com.dbaagent.model.brain.ColumnStatistics;
import com.dbaagent.model.brain.KnobRanking;
import com.dbaagent.model.brain.PlanExecution;
import com.dbaagent.model.brain.WorkloadProfile;
import com.dbaagent.dto.TableUsageDTO;
import com.dbaagent.repository.ActiveQueryRepository;
import com.dbaagent.repository.CapacityForecastRepository;
import com.dbaagent.repository.ColumnAntiPatternRepository;
import com.dbaagent.repository.CompositeIndexRecommendationRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.PerformanceActionRepository;
import com.dbaagent.repository.PerformanceSnapshotRepository;
import com.dbaagent.repository.QueryPerformanceRegressionRepository;
import com.dbaagent.repository.SentinelRecommendationRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.repository.brain.PlanExecutionRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.service.ActiveQueryService;
import com.dbaagent.service.ChatContextAssembler;
import com.dbaagent.service.DatabaseAdvisorService;
import com.dbaagent.service.IndexAdvisorService;
import com.dbaagent.service.PerformanceInsightsService;
import com.dbaagent.service.PerformanceActionAggregatorService;
import com.dbaagent.service.ResolvedConversationContext;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.data.domain.PageRequest;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PerformanceExecutor {

    private static final Pattern SQL_CODE_BLOCK_PATTERN = Pattern.compile("```sql\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);
    private static final Pattern SQL_STATEMENT_PATTERN = Pattern.compile("(?is)\\b(select|with|insert|update|delete)\\b[\\s\\S]*");
    private static final Duration SLOW_QUERY_FRESHNESS_WINDOW = Duration.ofHours(24);
    private static final Duration PERFORMANCE_SNAPSHOT_FRESHNESS_WINDOW = Duration.ofHours(2);

    private final IndexRecommendationRepository indexRecommendationRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnAntiPatternRepository columnAntiPatternRepository;
    private final PerformanceActionRepository performanceActionRepository;
    private final CompositeIndexRecommendationRepository compositeIndexRecommendationRepository;
    private final SlowQueryHistoryRepository slowQueryHistoryRepository;
    private final PerformanceActionAggregatorService performanceActionAggregatorService;
    private final PerformanceSnapshotRepository performanceSnapshotRepository;
    private final QueryPerformanceRegressionRepository queryPerformanceRegressionRepository;
    private final WorkloadProfileRepository workloadProfileRepository;
    private final KnobRankingRepository knobRankingRepository;
    private final ColumnStatisticsRepository columnStatisticsRepository;
    private final PlanExecutionRepository planExecutionRepository;
    private final ActiveQueryRepository activeQueryRepository;
    private final ActiveQueryService activeQueryService;
    private final PerformanceInsightsService performanceInsightsService;
    private final CapacityForecastRepository capacityForecastRepository;
    private final SentinelRecommendationRepository sentinelRecommendationRepository;
    private final GrowthAnomalyRepository growthAnomalyRepository;
    private final DatabaseAdvisorService databaseAdvisorService;
    private final IndexAdvisorService indexAdvisorService;
    private final ChatContextAssembler contextAssembler;
    private final ObjectMapper objectMapper;
    private final AnswerVerificationService answerVerificationService;

    public PerformanceExecutor(
        IndexRecommendationRepository indexRecommendationRepository,
        KeyColumnAnalysisRepository keyColumnAnalysisRepository,
        ColumnAntiPatternRepository columnAntiPatternRepository,
        PerformanceActionRepository performanceActionRepository,
        CompositeIndexRecommendationRepository compositeIndexRecommendationRepository,
        SlowQueryHistoryRepository slowQueryHistoryRepository,
        PerformanceActionAggregatorService performanceActionAggregatorService,
        PerformanceSnapshotRepository performanceSnapshotRepository,
        QueryPerformanceRegressionRepository queryPerformanceRegressionRepository,
        WorkloadProfileRepository workloadProfileRepository,
        KnobRankingRepository knobRankingRepository,
        ColumnStatisticsRepository columnStatisticsRepository,
        PlanExecutionRepository planExecutionRepository,
        ActiveQueryRepository activeQueryRepository,
        ActiveQueryService activeQueryService,
        PerformanceInsightsService performanceInsightsService,
        CapacityForecastRepository capacityForecastRepository,
        SentinelRecommendationRepository sentinelRecommendationRepository,
        GrowthAnomalyRepository growthAnomalyRepository,
        DatabaseAdvisorService databaseAdvisorService,
        IndexAdvisorService indexAdvisorService,
        ChatContextAssembler contextAssembler,
        ObjectMapper objectMapper,
        AnswerVerificationService answerVerificationService
    ) {
        this.indexRecommendationRepository = indexRecommendationRepository;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
        this.columnAntiPatternRepository = columnAntiPatternRepository;
        this.performanceActionRepository = performanceActionRepository;
        this.compositeIndexRecommendationRepository = compositeIndexRecommendationRepository;
        this.slowQueryHistoryRepository = slowQueryHistoryRepository;
        this.performanceActionAggregatorService = performanceActionAggregatorService;
        this.performanceSnapshotRepository = performanceSnapshotRepository;
        this.queryPerformanceRegressionRepository = queryPerformanceRegressionRepository;
        this.workloadProfileRepository = workloadProfileRepository;
        this.knobRankingRepository = knobRankingRepository;
        this.columnStatisticsRepository = columnStatisticsRepository;
        this.planExecutionRepository = planExecutionRepository;
        this.activeQueryRepository = activeQueryRepository;
        this.activeQueryService = activeQueryService;
        this.performanceInsightsService = performanceInsightsService;
        this.capacityForecastRepository = capacityForecastRepository;
        this.sentinelRecommendationRepository = sentinelRecommendationRepository;
        this.growthAnomalyRepository = growthAnomalyRepository;
        this.databaseAdvisorService = databaseAdvisorService;
        this.indexAdvisorService = indexAdvisorService;
        this.contextAssembler = contextAssembler;
        this.objectMapper = objectMapper;
        this.answerVerificationService = answerVerificationService;
    }

    public Optional<VerifiedAnswer> execute(
        PromptIntent promptIntent,
        String question,
        String connectionId,
        SchemaMetadata schema,
        ResolvedConversationContext resolvedConversationContext
    ) {
        if (promptIntent == null || promptIntent.domain() != PromptIntent.Domain.PERFORMANCE || connectionId == null) {
            return Optional.empty();
        }

        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT).trim();
        DraftPerformanceAnswer draft = null;

        if (looksLikePriorQueryDisplayFollowUp(normalized, resolvedConversationContext)) {
            draft = priorQueryFollowUpEvidence(normalized, connectionId, resolvedConversationContext);
        }
        if (draft == null && looksLikeColumnImpactPrompt(normalized, promptIntent)) {
            draft = columnImpactEvidence(connectionId, normalized);
        }
        if (draft == null && looksLikeIndexRecommendationPrompt(normalized, promptIntent)) {
            draft = indexEvidence(promptIntent, normalized, connectionId);
        }
        if (draft == null && looksLikeActiveQueryPrompt(normalized)) {
            draft = activeQueryEvidence(connectionId);
        }
        if (draft == null && looksLikeHotTablePrompt(normalized)) {
            draft = hotTableEvidence(connectionId);
        }
        if (draft == null && looksLikeGrowthRiskPrompt(normalized, promptIntent)) {
            draft = growthRiskEvidence(connectionId);
        }
        if (draft == null && looksLikeCardinalityPrompt(normalized)) {
            draft = cardinalityEvidence(connectionId);
        }
        if (draft == null && looksLikeWorkloadPrompt(normalized, promptIntent)) {
            draft = workloadEvidence(connectionId);
        }
        if (draft == null && looksLikeTuningPrompt(normalized, promptIntent)) {
            draft = tuningEvidence(connectionId);
        }
        if (draft == null && looksLikePerformanceActionPrompt(normalized, promptIntent)) {
            draft = performanceActionsEvidence(connectionId);
        }
        if (draft == null && looksLikeRegressionPrompt(normalized)) {
            draft = regressionEvidence(connectionId);
        }
        if (draft == null && looksLikePerformanceChangePrompt(normalized, promptIntent)) {
            draft = performanceChangeEvidence(connectionId, normalized);
        }
        if (draft == null && looksLikeSlowQueryPrompt(normalized, promptIntent)) {
            draft = slowQueryEvidence(normalized, connectionId);
        }

        if (draft == null) {
            return Optional.empty();
        }

        VerificationReport report = answerVerificationService.verify(promptIntent, draft.evidence(), resolvedConversationContext);
        if (!report.accepted()) {
            return Optional.empty();
        }

        return Optional.of(new VerifiedAnswer(
            promptIntent,
            draft.evidence(),
            report,
            new AnswerContract(
                draft.title(),
                draft.message(),
                List.of(),
                draft.supportingEvidence(),
                draft.executedSql() != null ? draft.executedSql() : draft.evidence().sourceQuery(),
                report.notes(),
                draft.gapsOrCaveats(),
                draft.followUpPrompt()
            ),
            "performance_executor",
            draft.stepTitle(),
            "lookup"
        ));
    }

    private DraftPerformanceAnswer columnImpactEvidence(String connectionId, String normalizedQuestion) {
        try {
            List<KeyColumnAnalysis> keyColumns = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId)
                .stream()
                .limit(25)
                .toList();
            List<ColumnAntiPattern> antiPatterns = columnAntiPatternRepository
                .findByConnectionIdAndStatusOrderBySeverityDescDetectedAtDesc(connectionId, ColumnAntiPattern.Status.ACTIVE)
                .stream()
                .limit(25)
                .toList();
            List<PerformanceAction> indexActions = performanceActionRepository
                .findByConnectionIdAndCategoryAndStatusOrderByRoiDesc(
                    connectionId,
                    PerformanceAction.ActionCategory.INDEX,
                    PerformanceAction.ActionStatus.PENDING
                )
                .stream()
                .limit(25)
                .toList();
            List<IndexRecommendationEntity> indexRecommendations = indexRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(connectionId, IndexRecommendationEntity.Status.PENDING)
                .stream()
                .limit(25)
                .toList();
            List<CompositeIndexRecommendation> compositeRecommendations = compositeIndexRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAsc(connectionId, CompositeIndexRecommendation.Status.PENDING)
                .stream()
                .limit(25)
                .toList();

            if (keyColumns.isEmpty()
                && antiPatterns.isEmpty()
                && indexActions.isEmpty()
                && indexRecommendations.isEmpty()
                && compositeRecommendations.isEmpty()) {
                return insufficiency(
                    "Column Performance Impact",
                    "Scout column performance evidence",
                    "I checked key-column analysis, column anti-patterns, performance actions, index recommendations, and composite-index recommendations, but none are populated for this connection yet. Run the key-column, anti-pattern, lineage, and index-advisor collectors before asking column-impact questions."
                );
            }

            Map<String, ColumnImpactAccumulator> ranked = new LinkedHashMap<>();
            keyColumns.forEach(column -> accumulator(ranked, column.getTableName(), column.getColumnName())
                .addKeyColumn(column));
            antiPatterns.forEach(pattern -> accumulator(ranked, pattern.getTableName(), pattern.getColumnName())
                .addAntiPattern(pattern));
            indexActions.forEach(action -> accumulator(ranked, action.getTargetObject(), action.getTargetSecondary())
                .addAction(action));
            indexRecommendations.forEach(rec -> accumulator(ranked, rec.getTableName(), rec.getColumnNames())
                .addIndexRecommendation(rec));
            compositeRecommendations.forEach(rec -> accumulator(ranked, rec.getTableName(), rec.getColumnNames())
                .addCompositeRecommendation(rec));

            List<ColumnImpactAccumulator> topColumns = ranked.values().stream()
                .filter(ColumnImpactAccumulator::hasColumn)
                .filter(column -> matchesRequestedTableScope(column.tableName(), normalizedQuestion))
                .sorted((left, right) -> Double.compare(right.score(), left.score()))
                .limit(10)
                .toList();

            if (topColumns.isEmpty()) {
                return insufficiency(
                    "Column Performance Impact",
                    "Scout column performance evidence",
                    "The vault has performance evidence for this connection, but it is not currently tied to concrete table columns. Run the key-column and query-lineage enrichment jobs to map workload pressure back to columns."
                );
            }

            StringBuilder sb = new StringBuilder();
            sb.append("Columns most likely impacting query performance");
            requestedTableLabel(normalizedQuestion).ifPresent(label -> sb.append(" for `").append(label).append("`"));
            sb.append(", ranked from vault evidence:\n\n");
            sb.append("**Findings**\n");
            int rank = 1;
            for (ColumnImpactAccumulator column : topColumns) {
                sb.append(rank++).append(". **`").append(column.displayName()).append("`**");
                sb.append(" — impact score ").append(formatDouble(column.score())).append("\n");
                sb.append("   - Why it matters: ").append(column.reason()).append("\n");
                if (!column.actions().isEmpty()) {
                    sb.append("   - Action: ").append(column.actions().getFirst()).append("\n");
                }
            }
            sb.append("\n**How I ranked them**\n");
            sb.append("- Used key-column usage counts, slow-query usage, anti-pattern severity, pending index actions, single-column index recommendations, and composite-index recommendations from the vault.\n");
            sb.append("- Weighted JOIN/WHERE/sort usage and active anti-patterns higher than plain column presence, because those are more directly tied to runtime pressure.\n");

            List<Map<String, Object>> rows = topColumns.stream()
                .map(column -> row(
                    "tableName", column.tableName(),
                    "columnName", column.columnName(),
                    "impactScore", column.score(),
                    "joinCount", column.joinCount(),
                    "whereCount", column.whereCount(),
                    "orderByCount", column.orderByCount(),
                    "slowQueryUsage", column.slowQueryUsage(),
                    "antiPatternCount", column.antiPatternCount(),
                    "pendingActionCount", column.actionCount(),
                    "recommendationCount", column.recommendationCount()
                ))
                .toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            payload.put("sourceFamily", "performance_column_scout");
            payload.put("sourcePlan", List.of(
                "key_column_analysis",
                "column_anti_pattern",
                "performance_action",
                "index_recommendations",
                "composite_index_recommendation"
            ));
            payload.put("sourcesAttempted", Map.of(
                "keyColumnRows", keyColumns.size(),
                "antiPatternRows", antiPatterns.size(),
                "indexActionRows", indexActions.size(),
                "indexRecommendationRows", indexRecommendations.size(),
                "compositeIndexRows", compositeRecommendations.size()
            ));
            payload.put("rankedColumnCount", topColumns.size());

            return draft(
                "Column Performance Impact",
                "Scout column performance evidence",
                sb.toString().trim(),
                "performance_column_impact_ranking",
                EvidenceBundle.Source.PERFORMANCE_VAULT,
                rows,
                payload,
                topColumns.stream().map(ColumnImpactAccumulator::displayName).collect(Collectors.toSet()),
                List.of(
                    "Key-column analysis from workload, lineage, and slow-query usage",
                    "Active column anti-patterns",
                    "Pending performance actions and index recommendations",
                    "Composite-index recommendations"
                ),
                0.96
            );
        } catch (Exception e) {
            return insufficiency(
                "Column Performance Impact",
                "Scout column performance evidence",
                "I checked the column-performance scout sources, but the vault evidence could not be assembled successfully: " + safeCap(e.getMessage(), 160)
            );
        }
    }

    private DraftPerformanceAnswer performanceActionsEvidence(String connectionId) {
        try {
            List<PerformanceAction> actions = performanceActionAggregatorService.getTopActions(connectionId, 5);
            if (actions == null || actions.isEmpty()) {
                return insufficiency(
                    "Performance Actions",
                    "Check ranked performance actions",
                    "I do not have any verified ranked performance actions for this connection yet. Refresh performance actions or run the relevant Brain collectors first."
                );
            }

            StringBuilder sb = new StringBuilder("Top performance actions to take right now, ranked by expected benefit from vault evidence:\n\n");
            int index = 1;
            for (PerformanceAction action : actions) {
                sb.append(index++).append(". **").append(nonBlank(action.getTitle(), "Untitled action")).append("**");
                if (action.getTargetObject() != null && !action.getTargetObject().isBlank()) {
                    sb.append(" on `").append(action.getTargetObject()).append("`");
                }
                sb.append("\n");
                if (action.getDescription() != null && !action.getDescription().isBlank()) {
                    sb.append("   - Why: ").append(action.getDescription()).append("\n");
                }
                sb.append("   - Category: ").append(action.getCategory()).append(" · Source: ").append(action.getSource()).append("\n");
                if (action.getQueriesAffected() != null && action.getQueriesAffected() > 0) {
                    sb.append("   - Blast radius: affects approximately ").append(action.getQueriesAffected()).append(" queries\n");
                }
                if (action.getSqlStatement() != null && !action.getSqlStatement().isBlank()) {
                    sb.append("   - Implementation hint: `").append(safeCap(action.getSqlStatement(), 120)).append("`\n");
                } else if (action.getOptimizedQuery() != null && !action.getOptimizedQuery().isBlank()) {
                    sb.append("   - Implementation hint: cached rewrite is available\n");
                }
                sb.append("\n");
            }
            sb.append("Ranking basis: expected benefit, workload reach, implementation effort, and source confidence from vault enrichment.\n");

            return draft(
                "Performance Actions",
                "Check ranked performance actions",
                sb.toString().trim(),
                "performance_action_recommendations",
                EvidenceBundle.Source.PERFORMANCE_ACTION,
                actions.stream().map(action -> row(
                    "title", action.getTitle(),
                    "category", action.getCategory(),
                    "source", action.getSource(),
                    "targetObject", action.getTargetObject(),
                    "impactScore", action.getImpactScore(),
                    "effortScore", action.getEffortScore(),
                    "roi", action.getRoi(),
                    "queriesAffected", action.getQueriesAffected()
                )).toList(),
                metadata(
                    "count", actions.size(),
                    "topRoi", actions.getFirst().getRoi()
                ),
                actions.stream()
                    .map(PerformanceAction::getTargetObject)
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toSet()),
                List.of("Ranked performance actions aggregated from index, slow-query, anti-pattern, and key-column analysis."),
                0.96
            );
        } catch (Exception e) {
            return insufficiency(
                "Performance Actions",
                "Check ranked performance actions",
                "I couldn't verify the ranked performance actions right now because the action aggregator did not complete successfully."
            );
        }
    }

    private DraftPerformanceAnswer performanceChangeEvidence(String connectionId, String normalized) {
        try {
            int windowHours = extractMonitoringWindowHours(normalized);
            LocalDateTime end = LocalDateTime.now();
            LocalDateTime start = end.minusHours(windowHours);
            List<PerformanceSnapshot> snapshots = performanceSnapshotRepository
                .findByConnectionIdAndSnapshotTimeBetweenOrderBySnapshotTimeAsc(connectionId, start, end);
            if (snapshots == null || snapshots.size() < 2) {
                return insufficiency(
                    "Performance Summary",
                    "Check recent performance snapshots",
                    "I need at least two performance snapshots in the requested time window before I can summarize what changed."
                );
            }

            PerformanceSnapshot first = snapshots.getFirst();
            PerformanceSnapshot latest = snapshots.getLast();
            List<QueryPerformanceRegression> recentRegressions = queryPerformanceRegressionRepository
                .findByConnectionIdAndResolvedFalseOrderByDetectedAtDesc(connectionId)
                .stream()
                .filter(regression -> regression.getDetectedAt() != null && !regression.getDetectedAt().isBefore(start))
                .limit(5)
                .toList();
            List<PerformanceAction> nextActions = performanceActionAggregatorService.getTopActions(connectionId, 3);

            List<String> findings = new java.util.ArrayList<>();
            appendDeltaFinding(findings, "Total DB time", first.getTotalDbTimeMs(), latest.getTotalDbTimeMs(), "ms");
            appendDeltaFinding(findings, "Active connections", asDouble(first.getActiveConnections()), asDouble(latest.getActiveConnections()), "");
            appendDeltaFinding(findings, "CPU", first.getCpuPercent(), latest.getCpuPercent(), "%");
            appendDeltaFinding(findings, "Queries/sec", first.getQueriesPerSecond(), latest.getQueriesPerSecond(), "");
            appendDeltaFinding(findings, "Lock wait", first.getLockWaitMs(), latest.getLockWaitMs(), "ms");

            StringBuilder sb = new StringBuilder();
            sb.append("What changed in database performance over the last ").append(windowHours).append(" hours:\n\n");
            sb.append("**Key findings**\n");
            if (findings.isEmpty()) {
                sb.append("- I found performance snapshots, but the tracked headline metrics stayed roughly stable in this window.\n");
            } else {
                findings.forEach(finding -> sb.append("- ").append(finding).append("\n"));
            }

            sb.append("\n**Current pressure signals**\n");
            sb.append("- Peak DB time in the window: ").append(formatDouble(peakDouble(snapshots, PerformanceSnapshot::getTotalDbTimeMs))).append(" ms\n");
            sb.append("- Peak active connections: ").append(formatDouble(peakDouble(snapshots, s -> asDouble(s.getActiveConnections())))).append("\n");
            sb.append("- Peak CPU: ").append(formatDouble(peakDouble(snapshots, PerformanceSnapshot::getCpuPercent))).append("%\n");
            sb.append("- Peak queries/sec: ").append(formatDouble(peakDouble(snapshots, PerformanceSnapshot::getQueriesPerSecond))).append("\n");
            if (!recentRegressions.isEmpty()) {
                sb.append("- Unresolved regressions detected in this window: ").append(recentRegressions.size()).append("\n");
            } else {
                sb.append("- No unresolved query regressions were detected in this window.\n");
            }

            if (!recentRegressions.isEmpty()) {
                sb.append("\n**Regressions to inspect first**\n");
                recentRegressions.forEach(regression -> sb.append("- `")
                    .append(safeCap(nonBlank(regression.getNormalizedQuery(), regression.getQueryHash()), 100))
                    .append("` slowed by ")
                    .append(formatDouble(regression.getSlowdownPercent()))
                    .append("% (")
                    .append(regression.getSeverity())
                    .append(")\n"));
            }

            if (nextActions != null && !nextActions.isEmpty()) {
                sb.append("\n**Recommended next actions**\n");
                nextActions.forEach(action -> sb.append("- **")
                    .append(nonBlank(action.getTitle(), "Untitled action"))
                    .append("**")
                    .append(action.getTargetObject() != null && !action.getTargetObject().isBlank() ? " on `" + action.getTargetObject() + "`" : "")
                    .append("\n"));
            }

            return draft(
                "Performance Summary",
                "Check recent performance snapshots",
                sb.toString().trim(),
                "performance_change_summary",
                EvidenceBundle.Source.PERFORMANCE_VAULT,
                List.of(
                    row("metric", "dbTimeMs", "start", first.getTotalDbTimeMs(), "end", latest.getTotalDbTimeMs()),
                    row("metric", "activeConnections", "start", first.getActiveConnections(), "end", latest.getActiveConnections()),
                    row("metric", "cpuPercent", "start", first.getCpuPercent(), "end", latest.getCpuPercent()),
                    row("metric", "queriesPerSecond", "start", first.getQueriesPerSecond(), "end", latest.getQueriesPerSecond())
                ),
                row(
                    "snapshotCount", snapshots.size(),
                    "windowHours", windowHours,
                    "regressionCount", recentRegressions.size()
                ),
                nextActions == null ? Set.of() : nextActions.stream()
                    .map(PerformanceAction::getTargetObject)
                    .filter(Objects::nonNull)
                    .filter(value -> !value.isBlank())
                    .collect(Collectors.toSet()),
                List.of("Performance snapshots captured during the requested window", "Unresolved regression records", "Ranked performance actions"),
                0.93
            );
        } catch (Exception e) {
            return insufficiency(
                "Performance Summary",
                "Check recent performance snapshots",
                "I couldn't compare recent performance changes right now because the monitoring data could not be assembled reliably."
            );
        }
    }

    private DraftPerformanceAnswer regressionEvidence(String connectionId) {
        try {
            List<QueryPerformanceRegression> regressions = queryPerformanceRegressionRepository
                .findByConnectionIdAndResolvedFalseOrderByDetectedAtDesc(connectionId);
            if (regressions == null || regressions.isEmpty()) {
                PerformanceSnapshot latest = performanceSnapshotRepository.findFirstByConnectionIdOrderBySnapshotTimeDesc(connectionId);
                List<Map<String, Object>> topQueries = latest == null
                    ? List.of()
                    : parseSnapshotTopQueries(latest.getTopQueries()).stream()
                        .sorted((left, right) -> Double.compare(snapshotQueryTotalTime(right), snapshotQueryTotalTime(left)))
                        .limit(3)
                        .toList();
                StringBuilder sb = new StringBuilder();
                sb.append("No unresolved query plan regressions are recorded for this connection right now.\n\n");
                sb.append("**What that means**\n");
                sb.append("- The regression catalog has no open entries where a query got materially worse versus its baseline.\n");
                if (latest != null && latest.getSnapshotTime() != null) {
                    sb.append("- Latest performance snapshot checked: `").append(latest.getSnapshotTime()).append("`.\n");
                }
                if (!topQueries.isEmpty()) {
                    sb.append("\n**Queries to keep watching despite no recorded regression**\n");
                    int rank = 1;
                    for (Map<String, Object> query : topQueries) {
                        sb.append(rank++).append(". `")
                            .append(safeCap(snapshotQueryText(query), 120))
                            .append("` — current total DB time ")
                            .append(formatMillis(snapshotQueryTotalTime(query)))
                            .append("\n");
                    }
                }
                return draft(
                    "Performance Regressions",
                    "Check unresolved regressions",
                    sb.toString().trim(),
                    "performance_regressions",
                    EvidenceBundle.Source.PLAN_REGRESSION,
                    topQueries.stream().map(query -> row(
                        "query", snapshotQueryText(query),
                        "currentTotalDbTimeMs", snapshotQueryTotalTime(query),
                        "currentAvgTimeMs", snapshotQueryAvgTime(query),
                        "callCount", snapshotQueryLong(query, "callCount", "count", "COUNT_STAR")
                    )).toList(),
                    row(
                        "openRegressionCount", 0,
                        "latestSnapshotTime", latest == null ? null : latest.getSnapshotTime(),
                        "watchedQueryCount", topQueries.size()
                    ),
                    topQueries.stream()
                        .map(this::snapshotQueryText)
                        .map(this::firstRelationFromSql)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()),
                    List.of("Active query performance regression records", "Latest performance snapshot top_queries"),
                    0.9
                );
            }
            StringBuilder sb = new StringBuilder("Open performance regressions:\n\n");
            regressions.stream().limit(5).forEach(regression -> sb.append("- **")
                .append(regression.getSeverity())
                .append("** `")
                .append(safeCap(nonBlank(regression.getNormalizedQuery(), regression.getQueryHash()), 120))
                .append("` slowed by ")
                .append(formatDouble(regression.getSlowdownPercent()))
                .append("%")
                .append(" (")
                .append(formatDouble(regression.getBaselineAvgMs()))
                .append(" ms → ")
                .append(formatDouble(regression.getCurrentAvgMs()))
                .append(" ms)\n"));
            return draft(
                "Performance Regressions",
                "Check unresolved regressions",
                sb.toString().trim(),
                "performance_regressions",
                EvidenceBundle.Source.SLOW_QUERY,
                regressions.stream().limit(10).map(regression -> row(
                    "severity", regression.getSeverity(),
                    "queryHash", regression.getQueryHash(),
                    "slowdownPercent", regression.getSlowdownPercent(),
                    "baselineAvgMs", regression.getBaselineAvgMs(),
                    "currentAvgMs", regression.getCurrentAvgMs()
                )).toList(),
                metadata("count", regressions.size()),
                Set.of(),
                List.of("Active query performance regression records"),
                0.92
            );
        } catch (Exception e) {
            return insufficiency(
                "Performance Regressions",
                "Check unresolved regressions",
                "I couldn't verify query regressions right now because the regression catalog did not load successfully."
            );
        }
    }

    private DraftPerformanceAnswer tuningEvidence(String connectionId) {
        try {
            Optional<WorkloadProfile> profileOpt = workloadProfileRepository.findByConnectionId(connectionId);
            List<KnobRanking> latencyKnobs = knobRankingRepository.findTopKnobs(
                connectionId,
                KnobRanking.TargetMetric.LATENCY,
                PageRequest.of(0, 5)
            );
            if (latencyKnobs == null || latencyKnobs.isEmpty()) {
                latencyKnobs = knobRankingRepository.findHighImpactKnobs(connectionId, PageRequest.of(0, 5));
            }

            if ((latencyKnobs == null || latencyKnobs.isEmpty()) && profileOpt.isEmpty()) {
                return insufficiency(
                    "Latency Tuning",
                    "Check Brain tuning data",
                    "I do not have learned knob rankings or a workload profile for this connection yet. Run Brain workload characterization and knob ranking first."
                );
            }

            StringBuilder sb = new StringBuilder("Config knobs that matter most for reducing latency on this database:\n\n");
            profileOpt.ifPresent(profile -> {
                sb.append("**Workload context**\n");
                sb.append("- Classified as **").append(profile.getWorkloadType()).append("**");
                if (profile.getWorkloadSubtype() != null && !profile.getWorkloadSubtype().isBlank()) {
                    sb.append(" (`").append(profile.getWorkloadSubtype()).append("`)");
                }
                if (profile.getClassificationConfidence() != null) {
                    sb.append(" with ").append(formatDouble(profile.getClassificationConfidence())).append("% confidence");
                }
                sb.append("\n");
                if (profile.getLatencyP99Ms() != null || profile.getThroughputQps() != null) {
                    sb.append("- Latest profiled latency/throughput: p99 ");
                    sb.append(formatDouble(profile.getLatencyP99Ms())).append(" ms");
                    sb.append(", throughput ").append(formatDouble(profile.getThroughputQps())).append(" qps\n");
                }
                if (profile.getClassificationReasoning() != null && !profile.getClassificationReasoning().isBlank()) {
                    sb.append("- Why this workload matters: ").append(profile.getClassificationReasoning()).append("\n");
                }
                sb.append("\n");
            });

            if (latencyKnobs != null && !latencyKnobs.isEmpty()) {
                sb.append("**Highest-impact latency knobs**\n");
                int index = 1;
                for (KnobRanking knob : latencyKnobs) {
                    sb.append(index++).append(". **`").append(knob.getKnobName()).append("`**");
                    sb.append(" — rank ").append(knob.getRank());
                    sb.append(", impact ").append(formatDouble(knob.getImpactScore()));
                    if (knob.getConfidenceScore() != null) {
                        sb.append(", confidence ").append(formatDouble(knob.getConfidenceScore()));
                    }
                    sb.append("\n");
                    if (knob.getCurrentValue() != null || knob.getDefaultValue() != null) {
                        sb.append("   - Current/default: ")
                            .append(nonBlank(knob.getCurrentValue(), "n/a"))
                            .append(" / ")
                            .append(nonBlank(knob.getDefaultValue(), "n/a"))
                            .append("\n");
                    }
                    if (knob.getMinValue() != null || knob.getMaxValue() != null) {
                        sb.append("   - Safe operating range: ")
                            .append(nonBlank(knob.getMinValue(), "?"))
                            .append(" to ")
                            .append(nonBlank(knob.getMaxValue(), "?"))
                            .append("\n");
                    }
                    sb.append("   - Change cost: ")
                        .append(Boolean.TRUE.equals(knob.getRequiresRestart()) ? "requires restart" : "online/dynamic")
                        .append(knob.getSampleCount() != null ? " · samples " + knob.getSampleCount() : "")
                        .append("\n");
                }
            } else {
                sb.append("**Knob ranking status**\n");
                sb.append("- A workload profile exists, but no latency-ranked knobs have been materialized yet.\n");
            }

            profileOpt.ifPresent(profile -> {
                if (profile.getOptimalConfig() != null && !profile.getOptimalConfig().isEmpty()) {
                    sb.append("\n**Observed tuning direction**\n");
                    profile.getOptimalConfig().entrySet().stream().limit(3).forEach(entry -> sb.append("- `")
                        .append(entry.getKey())
                        .append("` → `")
                        .append(entry.getValue())
                        .append("`\n"));
                }
            });

            List<Map<String, Object>> rows = latencyKnobs == null ? List.of() : latencyKnobs.stream().map(knob -> row(
                "knobName", knob.getKnobName(),
                "rank", knob.getRank(),
                "impactScore", knob.getImpactScore(),
                "confidenceScore", knob.getConfidenceScore(),
                "currentValue", knob.getCurrentValue(),
                "defaultValue", knob.getDefaultValue(),
                "requiresRestart", knob.getRequiresRestart(),
                "sampleCount", knob.getSampleCount()
            )).toList();

            Map<String, Object> payload = new LinkedHashMap<>();
            profileOpt.ifPresent(profile -> {
                payload.put("workloadType", profile.getWorkloadType() == null ? null : profile.getWorkloadType().name());
                payload.put("workloadSubtype", profile.getWorkloadSubtype());
                payload.put("classificationConfidence", profile.getClassificationConfidence());
            });
            payload.put("targetMetric", KnobRanking.TargetMetric.LATENCY.name());
            payload.put("knobCount", latencyKnobs == null ? 0 : latencyKnobs.size());

            return draft(
                "Latency Tuning",
                "Check Brain tuning data",
                sb.toString().trim(),
                "tuning_knob_rankings",
                EvidenceBundle.Source.KNOB_RANKING,
                rows,
                payload,
                latencyKnobs == null ? Set.of() : latencyKnobs.stream().map(KnobRanking::getKnobName).collect(Collectors.toSet()),
                List.of("Learned workload profile", "Brain knob rankings targeting latency"),
                0.95
            );
        } catch (Exception e) {
            return insufficiency(
                "Latency Tuning",
                "Check Brain tuning data",
                "I couldn't verify the latency knob rankings right now because the Brain tuning data could not be loaded."
            );
        }
    }

    private DraftPerformanceAnswer workloadEvidence(String connectionId) {
        try {
            Optional<WorkloadProfile> profileOpt = workloadProfileRepository.findByConnectionId(connectionId);
            if (profileOpt.isEmpty()) {
                return insufficiency(
                    "Workload Profile",
                    "Check Brain workload profile",
                    "No workload profile is available for this connection yet. Run workload characterization before asking workload-shape or tuning-fit questions."
                );
            }

            WorkloadProfile profile = profileOpt.get();
            StringBuilder sb = new StringBuilder("Current workload characterization and what it implies for tuning:\n\n");
            sb.append("- Classified workload: **").append(profile.getWorkloadType()).append("**");
            if (profile.getWorkloadSubtype() != null && !profile.getWorkloadSubtype().isBlank()) {
                sb.append(" (`").append(profile.getWorkloadSubtype()).append("`)");
            }
            if (profile.getClassificationConfidence() != null) {
                sb.append(" with ").append(formatDouble(profile.getClassificationConfidence())).append("% confidence");
            }
            sb.append("\n");
            sb.append("- OLTP/OLAP interpretation: ").append(mapWorkloadToOltpOlap(profile)).append("\n");
            if (profile.getClassificationReasoning() != null && !profile.getClassificationReasoning().isBlank()) {
                sb.append("- Why: ").append(profile.getClassificationReasoning()).append("\n");
            }
            if (profile.getKeyMetricValues() != null && !profile.getKeyMetricValues().isEmpty()) {
                sb.append("- Key signals: ");
                sb.append(profile.getKeyMetricValues().entrySet().stream().limit(4)
                    .map(entry -> entry.getKey() + "=" + formatDouble(entry.getValue()))
                    .collect(Collectors.joining(", ")));
                sb.append("\n");
            }
            sb.append("\n**Tuning implication**\n");
            sb.append(switch (profile.getWorkloadType()) {
                case OLTP, WRITE_HEAVY, REAL_TIME -> "- Prioritize low-latency commit, lock, buffer pool, and concurrency knobs over bulk scan throughput.";
                case OLAP, BATCH -> "- Prioritize scan/aggregation throughput, memory for sorts/joins, and plan stability over per-request latency.";
                case MIXED, READ_HEAVY -> "- Balance concurrency and cache knobs carefully because the workload mixes latency-sensitive lookups with heavier read pressure.";
                case UNKNOWN -> "- The workload is not characterized strongly enough yet to make workload-specific tuning commitments.";
            });

            return draft(
                "Workload Profile",
                "Check Brain workload profile",
                sb.toString().trim(),
                "workload_profile",
                EvidenceBundle.Source.WORKLOAD_PROFILE,
                List.of(row(
                    "workloadType", profile.getWorkloadType(),
                    "workloadSubtype", profile.getWorkloadSubtype(),
                    "classificationConfidence", profile.getClassificationConfidence(),
                    "latencyP99Ms", profile.getLatencyP99Ms(),
                    "throughputQps", profile.getThroughputQps()
                )),
                row(
                    "profiledAt", profile.getProfiledAt(),
                    "lastUpdatedAt", profile.getLastUpdatedAt(),
                    "selectedMetricsCount", profile.getSelectedMetrics() == null ? 0 : profile.getSelectedMetrics().size()
                ),
                Set.of(),
                List.of("Learned workload profile"),
                0.94
            );
        } catch (Exception e) {
            return insufficiency(
                "Workload Profile",
                "Check Brain workload profile",
                "I couldn't verify the workload profile right now because the Brain workload data did not load successfully."
            );
        }
    }

    private DraftPerformanceAnswer cardinalityEvidence(String connectionId) {
        try {
            long trackedExecutions = planExecutionRepository.countWithCardinalityData(connectionId);
            Double averageError = planExecutionRepository.calculateAverageCardinalityError(connectionId);
            List<PlanExecution> significantErrors = planExecutionRepository.findWithSignificantCardinalityError(connectionId, PageRequest.of(0, 5));
            long overestimates = planExecutionRepository.countOverestimates(connectionId);
            long underestimates = planExecutionRepository.countUnderestimates(connectionId);
            long accurateEstimates = planExecutionRepository.countAccurateEstimates(connectionId);
            long statsCount = columnStatisticsRepository.countByConnectionId(connectionId);
            List<ColumnStatistics> highCardinalityColumns = columnStatisticsRepository.findHighCardinalityColumns(connectionId, 1000L)
                .stream()
                .limit(5)
                .toList();

            if (trackedExecutions == 0 && statsCount == 0) {
                List<PerformanceAction> actions = performanceActionAggregatorService.getTopActions(connectionId, 5);
                StringBuilder sb = new StringBuilder();
                sb.append("No plan-execution estimate-vs-actual samples are recorded yet, so I used adjacent vault signals to identify likely cardinality/statistics risk.\n\n");
                sb.append("**Estimate-risk readout**\n");
                sb.append("- Tracked plan executions with cardinality data: 0\n");
                sb.append("- Collected column statistics: 0\n");
                if (actions != null && !actions.isEmpty()) {
                    sb.append("- Highest-priority proxy signals are pending performance actions on hot tables/columns.\n\n");
                    sb.append("**Objects to inspect first**\n");
                    actions.stream().limit(5).forEach(action -> sb.append("- `")
                        .append(nonBlank(action.getTargetObject(), "unknown_object"))
                        .append(action.getTargetSecondary() != null && !action.getTargetSecondary().isBlank() ? "." + action.getTargetSecondary() : "")
                        .append("` — ")
                        .append(nonBlank(action.getTitle(), "performance action"))
                        .append("\n"));
                } else {
                    sb.append("- No pending action proxy was available, so the next best step is to run plan capture and column-statistics enrichment.\n");
                }
                sb.append("\n**Recommended DBA action**\n");
                sb.append("- Run plan capture/EXPLAIN sampling for the highest-load queries, then refresh column statistics on the joined/filter columns surfaced by the workload analyzers.\n");
                return draft(
                    "Cardinality Accuracy",
                    "Check Brain statistics and plan accuracy data",
                    sb.toString().trim(),
                    "cardinality_accuracy_summary",
                    EvidenceBundle.Source.PERFORMANCE_VAULT,
                    actions == null ? List.of() : actions.stream().limit(5).map(action -> row(
                        "targetObject", action.getTargetObject(),
                        "targetSecondary", action.getTargetSecondary(),
                        "title", action.getTitle(),
                        "roi", action.getRoi(),
                        "impactScore", action.getImpactScore(),
                        "source", action.getSource()
                    )).toList(),
                    row(
                        "trackedExecutions", trackedExecutions,
                        "statsCount", statsCount,
                        "proxyActionCount", actions == null ? 0 : actions.size()
                    ),
                    actions == null ? Set.of() : actions.stream()
                        .map(PerformanceAction::getTargetObject)
                        .filter(Objects::nonNull)
                        .filter(value -> !value.isBlank())
                        .collect(Collectors.toSet()),
                    List.of("Plan execution cardinality history", "Collected column statistics", "Ranked performance action proxy signals"),
                    0.88
                );
            }

            StringBuilder sb = new StringBuilder("Where statistics or cardinality estimates are hurting plan quality:\n\n");
            sb.append("**Coverage**\n");
            sb.append("- Tracked plan executions with estimate-vs-actual rows: ").append(trackedExecutions).append("\n");
            sb.append("- Collected column statistics: ").append(statsCount).append("\n");
            if (averageError != null) {
                sb.append("- Average cardinality error ratio: ").append(formatDouble(averageError)).append("x");
                if (averageError > 2.0d || averageError < 0.5d) {
                    sb.append(" (materially off)");
                }
                sb.append("\n");
            }

            sb.append("\n**Observed pain points**\n");
            sb.append("- Overestimates: ").append(overestimates).append(" execution(s)\n");
            sb.append("- Underestimates: ").append(underestimates).append(" execution(s)\n");
            sb.append("- Acceptable estimates: ").append(accurateEstimates).append(" execution(s)\n");

            if (significantErrors != null && !significantErrors.isEmpty()) {
                sb.append("\n**Queries with the worst estimate drift**\n");
                significantErrors.forEach(exec -> sb.append("- `")
                    .append(safeCap(nonBlank(exec.getNormalizedQuery(), exec.getQueryFingerprint()), 100))
                    .append("` — estimated ")
                    .append(exec.getEstimatedRows())
                    .append(", actual ")
                    .append(exec.getActualRows())
                    .append(", error ratio ")
                    .append(formatDouble(exec.getCardinalityErrorRatio()))
                    .append("x")
                    .append(exec.getActualExecutionMs() != null ? ", runtime " + formatDouble(exec.getActualExecutionMs()) + " ms" : "")
                    .append("\n"));
            }

            if (!highCardinalityColumns.isEmpty()) {
                sb.append("\n**Columns worth checking for stale/incomplete stats**\n");
                highCardinalityColumns.forEach(column -> sb.append("- `")
                    .append(column.getTableName()).append(".").append(column.getColumnName())
                    .append("` — distinct ").append(column.getDistinctCount())
                    .append(", null fraction ").append(formatDouble(column.getNullFraction()))
                    .append("\n"));
            }

            sb.append("\n**Likely next actions**\n");
            sb.append("- Refresh stale statistics for the worst-misestimation tables first.\n");
            sb.append("- Re-check predicates and joins touching the queries above, especially where estimated rows diverge sharply from actual rows.\n");
            sb.append("- If the workload is skewed, add richer statistics/column profiling before trusting plan costs.\n");

            List<Map<String, Object>> rows = significantErrors == null ? List.of() : significantErrors.stream().map(exec -> row(
                "queryFingerprint", exec.getQueryFingerprint(),
                "estimatedRows", exec.getEstimatedRows(),
                "actualRows", exec.getActualRows(),
                "cardinalityErrorRatio", exec.getCardinalityErrorRatio(),
                "actualExecutionMs", exec.getActualExecutionMs()
            )).toList();

            return draft(
                "Cardinality Accuracy",
                "Check Brain statistics and plan accuracy data",
                sb.toString().trim(),
                "cardinality_accuracy_summary",
                EvidenceBundle.Source.PERFORMANCE_VAULT,
                rows,
                row(
                    "trackedExecutions", trackedExecutions,
                    "statsCount", statsCount,
                    "averageCardinalityError", averageError,
                    "overestimates", overestimates,
                    "underestimates", underestimates,
                    "accurateEstimates", accurateEstimates
                ),
                highCardinalityColumns.stream()
                    .map(column -> column.getTableName() + "." + column.getColumnName())
                    .collect(Collectors.toSet()),
                List.of("Plan execution cardinality history", "Collected column statistics"),
                0.93
            );
        } catch (Exception e) {
            return insufficiency(
                "Cardinality Accuracy",
                "Check Brain statistics and plan accuracy data",
                "I couldn't verify cardinality accuracy right now because the Brain statistics or plan history did not load successfully."
            );
        }
    }

    private DraftPerformanceAnswer activeQueryEvidence(String connectionId) {
        try {
            List<ActiveQuery> queries = activeQueryRepository.findLatestSnapshot(connectionId);
            Map<String, Object> stats = activeQueryService.getStatistics(connectionId);
            if (queries == null || queries.isEmpty()) {
                PerformanceSnapshot latest = performanceSnapshotRepository.findFirstByConnectionIdOrderBySnapshotTimeDesc(connectionId);
                List<Map<String, Object>> topQueries = latest == null
                    ? List.of()
                    : parseSnapshotTopQueries(latest.getTopQueries()).stream()
                        .sorted((left, right) -> Double.compare(snapshotQueryTotalTime(right), snapshotQueryTotalTime(left)))
                        .limit(5)
                        .toList();
                List<Map<String, Object>> waits = latest == null
                    ? List.of()
                    : parseSnapshotTopQueries(latest.getWaitEvents()).stream()
                        .sorted((left, right) -> Double.compare(snapshotWaitTime(right), snapshotWaitTime(left)))
                        .limit(5)
                        .toList();
                StringBuilder sb = new StringBuilder();
                sb.append("No live active-query session sample is stored, so I used the latest vault performance snapshot as the pressure source of truth.\n\n");
                if (latest != null && latest.getSnapshotTime() != null) {
                    sb.append("**Snapshot pressure**\n");
                    sb.append("- Captured at: `").append(latest.getSnapshotTime()).append("`\n");
                    sb.append("- Active connections: ").append(latest.getActiveConnections()).append("\n");
                    sb.append("- Total DB time: ").append(formatDouble(latest.getTotalDbTimeMs())).append(" ms\n");
                    sb.append("- Lock wait: ").append(formatDouble(latest.getLockWaitMs())).append(" ms\n");
                    sb.append("- IO wait: ").append(formatDouble(latest.getIoWaitMs())).append(" ms\n");
                    sb.append("- Waiting-on evidence comes from snapshot wait events when session-level wait samples are not stored.\n");
                }
                if (!waits.isEmpty()) {
                    sb.append("\n**Top wait signals**\n");
                    waits.forEach(wait -> sb.append("- ")
                        .append(snapshotWaitName(wait))
                        .append(" — ")
                        .append(formatMillis(snapshotWaitTime(wait)))
                        .append("\n"));
                }
                if (!topQueries.isEmpty()) {
                    sb.append("\n**Queries contributing most to current pressure**\n");
                    int rank = 1;
                    for (Map<String, Object> query : topQueries) {
                        sb.append(rank++).append(". `")
                            .append(safeCap(snapshotQueryText(query), 120))
                            .append("` — total DB time ")
                            .append(formatMillis(snapshotQueryTotalTime(query)))
                            .append(", avg ")
                            .append(formatMillis(snapshotQueryAvgTime(query)))
                            .append("\n");
                    }
                }
                return draft(
                    "Active Query Pressure",
                    "Check active query snapshot",
                    sb.toString().trim(),
                    "active_query_pressure",
                    EvidenceBundle.Source.PERFORMANCE_VAULT,
                    topQueries.stream().map(query -> row(
                        "query", snapshotQueryText(query),
                        "totalTimeMs", snapshotQueryTotalTime(query),
                        "avgTimeMs", snapshotQueryAvgTime(query),
                        "callCount", snapshotQueryLong(query, "callCount", "count", "COUNT_STAR")
                    )).toList(),
                    row(
                        "activeSnapshotCount", 0,
                        "latestSnapshotTime", latest == null ? null : latest.getSnapshotTime(),
                        "waitEventCount", waits.size(),
                        "topQueryCount", topQueries.size()
                    ),
                    topQueries.stream()
                        .map(this::snapshotQueryText)
                        .map(this::firstRelationFromSql)
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()),
                    List.of("Latest captured active-query snapshot", "Latest performance snapshot top_queries", "Latest performance snapshot wait_events"),
                    0.88
                );
            }

            StringBuilder sb = new StringBuilder("Active queries causing pressure right now:\n\n");
            sb.append("**Snapshot summary**\n");
            sb.append("- Total active snapshot rows: ").append(queries.size()).append("\n");
            sb.append("- Blocked queries: ").append(stats.getOrDefault("blocked", 0)).append("\n");
            sb.append("- Long-running queries: ").append(stats.getOrDefault("longRunning", 0)).append("\n");
            sb.append("- Average active duration: ").append(stats.getOrDefault("avgDuration", 0)).append(" sec\n");

            sb.append("\n**Queries to inspect first**\n");
            queries.stream().limit(5).forEach(query -> sb.append("- **")
                .append(query.getPriority())
                .append("** `")
                .append(safeCap(query.getQueryText(), 100))
                .append("`\n")
                .append("   - State: ").append(nonBlank(query.getState(), "unknown"))
                .append(", waiting on ").append(nonBlank(query.getWaitEventType(), "unknown"))
                .append(query.getWaitEvent() != null && !query.getWaitEvent().isBlank() ? " / " + query.getWaitEvent() : "")
                .append(", duration ").append(query.getDurationSeconds() == null ? "n/a" : query.getDurationSeconds() + " sec")
                .append(Boolean.TRUE.equals(query.getIsBlocked()) ? ", blocked=yes" : "")
                .append("\n"));

            return draft(
                "Active Query Pressure",
                "Check active query snapshot",
                sb.toString().trim(),
                "active_query_pressure",
                EvidenceBundle.Source.ACTIVE_QUERY_SNAPSHOT,
                queries.stream().limit(10).map(query -> row(
                    "queryText", query.getQueryText(),
                    "state", query.getState(),
                    "waitEventType", query.getWaitEventType(),
                    "waitEvent", query.getWaitEvent(),
                    "durationSeconds", query.getDurationSeconds(),
                    "priority", query.getPriority(),
                    "blocked", query.getIsBlocked()
                )).toList(),
                stats,
                Set.of(),
                List.of("Latest captured active-query snapshot"),
                0.92
            );
        } catch (Exception e) {
            return insufficiency(
                "Active Query Pressure",
                "Check active query snapshot",
                "I couldn't verify active query pressure right now because the active-query snapshot could not be loaded."
            );
        }
    }

    private DraftPerformanceAnswer hotTableEvidence(String connectionId) {
        try {
            List<TableUsageDTO> usage = performanceInsightsService.getTableUsage(connectionId);
            if (usage == null || usage.isEmpty()) {
                return draft(
                    "Hot Tables",
                    "Check table usage statistics",
                    "I do not have recent table-usage statistics for this connection right now, so I cannot verify which tables are hottest or how they are being used.",
                    "table_usage_heatmap",
                    EvidenceBundle.Source.PERFORMANCE_VAULT,
                    List.of(),
                    metadata("tableCount", 0),
                    Set.of(),
                    List.of("Table usage statistics enriched with slow-query history"),
                    0.88
                );
            }

            StringBuilder sb = new StringBuilder("Hottest tables right now and how they are being used:\n\n");
            usage.stream().limit(5).forEach(table -> sb.append("- **`")
                .append(table.getTableName())
                .append("`** — usage score ")
                .append(table.getUsageScore())
                .append("\n")
                .append("   - Reads: ").append(table.getRowsRead())
                .append(", writes: ").append(table.getRowsWritten())
                .append(", scans: ").append(table.getSeqScans())
                .append(" seq / ").append(table.getIdxScans())
                .append(" index")
                .append(", slow-query hits: ").append(table.getSlowQueryCount())
                .append("\n"));

            return draft(
                "Hot Tables",
                "Check table usage statistics",
                sb.toString().trim(),
                "table_usage_heatmap",
                EvidenceBundle.Source.PERFORMANCE_VAULT,
                usage.stream().limit(10).map(table -> row(
                    "tableName", table.getTableName(),
                    "usageScore", table.getUsageScore(),
                    "rowsRead", table.getRowsRead(),
                    "rowsWritten", table.getRowsWritten(),
                    "seqScans", table.getSeqScans(),
                    "idxScans", table.getIdxScans(),
                    "slowQueryCount", table.getSlowQueryCount()
                )).toList(),
                metadata("tableCount", usage.size()),
                usage.stream().map(TableUsageDTO::getTableName).collect(Collectors.toSet()),
                List.of("Table usage statistics enriched with slow-query history"),
                0.92
            );
        } catch (Exception e) {
            return insufficiency(
                "Hot Tables",
                "Check table usage statistics",
                "I couldn't verify table hot spots right now because the usage statistics could not be assembled."
            );
        }
    }

    private DraftPerformanceAnswer growthRiskEvidence(String connectionId) {
        try {
            List<CapacityForecast> criticalForecasts = capacityForecastRepository.findCriticalForecasts(connectionId);
            if (criticalForecasts == null || criticalForecasts.isEmpty()) {
                criticalForecasts = capacityForecastRepository.findByConnectionIdOrderByForecastDateDesc(connectionId).stream()
                    .limit(5)
                    .toList();
            }
            List<GrowthAnomaly> anomalies = growthAnomalyRepository.findRecentAnomalies(connectionId, LocalDateTime.now().minusDays(30));
            List<SentinelRecommendation> pending = sentinelRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(connectionId, SentinelRecommendation.Status.PENDING);

            if ((criticalForecasts == null || criticalForecasts.isEmpty()) && (anomalies == null || anomalies.isEmpty())) {
                return draft(
                    "Growth Risk",
                    "Check growth forecasts and anomalies",
                    "I do not have forecasted table-growth risk or anomaly data for this connection yet, so I cannot verify what may run out first.",
                    "growth_risk_forecast",
                    EvidenceBundle.Source.CAPACITY_FORECAST,
                    List.of(),
                    row("forecastCount", 0, "anomalyCount", 0),
                    Set.of(),
                    List.of("Capacity forecasts", "Recent growth anomalies"),
                    0.88
                );
            }

            StringBuilder sb = new StringBuilder("Tables on a risky growth path and what may run out first:\n\n");
            if (criticalForecasts != null && !criticalForecasts.isEmpty()) {
                sb.append("**Highest-risk forecasts**\n");
                criticalForecasts.stream().limit(5).forEach(forecast -> sb.append("- **`")
                    .append(nonBlank(forecast.getTableName(), "database"))
                    .append("`** — risk ")
                    .append(forecast.getRiskScore())
                    .append("/10, confidence ")
                    .append(forecast.getConfidenceScore())
                    .append("/10")
                    .append("\n")
                    .append("   - Critical resource: ").append(forecast.getCriticalResourceType())
                    .append(", days remaining: ").append(forecast.getDaysToStorageExhaustion() == null ? "n/a" : forecast.getDaysToStorageExhaustion())
                    .append(", pattern: ").append(forecast.getGrowthPattern() == null ? "UNKNOWN" : forecast.getGrowthPattern().name())
                    .append("\n"));
            }
            if (anomalies != null && !anomalies.isEmpty()) {
                sb.append("\n**Recent growth anomalies**\n");
                anomalies.stream().limit(5).forEach(anomaly -> sb.append("- `")
                    .append(anomaly.getTableName())
                    .append("` — ")
                    .append(anomaly.getAnomalyType())
                    .append(" / ")
                    .append(anomaly.getSeverity())
                    .append("\n")
                    .append("   - Capacity read: this is an early growth signal, not yet a forecasted run-out event. Treat it as a collection/validation task before calling it storage exhaustion.")
                    .append("\n"));
            }
            if ((criticalForecasts == null || criticalForecasts.isEmpty()) && anomalies != null && !anomalies.isEmpty()) {
                sb.append("\n**What may run out first**\n");
                sb.append("- No capacity forecast currently predicts a concrete resource exhaustion point; the available vault evidence is anomaly-only.\n");
                sb.append("- Next DBA action: run or refresh Sentinel capacity forecasting so storage, IOPS, row-count velocity, and days-to-exhaustion can be ranked instead of treating new-table anomalies as capacity risk.\n");
            }
            if (pending != null && !pending.isEmpty()) {
                sb.append("\n**Pending mitigations**\n");
                pending.stream().limit(3).forEach(rec -> sb.append("- **")
                    .append(rec.getTitle())
                    .append("** (")
                    .append(rec.getPriority())
                    .append(")\n"));
            }

            return draft(
                "Growth Risk",
                "Check growth forecasts and anomalies",
                sb.toString().trim(),
                "growth_risk_forecast",
                EvidenceBundle.Source.CAPACITY_FORECAST,
                criticalForecasts == null ? List.of() : criticalForecasts.stream().limit(10).map(forecast -> row(
                    "tableName", forecast.getTableName(),
                    "riskScore", forecast.getRiskScore(),
                    "confidenceScore", forecast.getConfidenceScore(),
                    "criticalResourceType", forecast.getCriticalResourceType(),
                    "daysRemaining", forecast.getDaysToStorageExhaustion(),
                    "growthPattern", forecast.getGrowthPattern()
                )).toList(),
                row(
                    "forecastCount", criticalForecasts == null ? 0 : criticalForecasts.size(),
                    "anomalyCount", anomalies == null ? 0 : anomalies.size(),
                    "pendingRecommendationCount", pending == null ? 0 : pending.size()
                ),
                criticalForecasts == null ? Set.of() : criticalForecasts.stream()
                    .map(CapacityForecast::getTableName)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toSet()),
                List.of("Capacity forecasts", "Recent growth anomalies", "Pending Sentinel recommendations"),
                0.93
            );
        } catch (Exception e) {
            return insufficiency(
                "Growth Risk",
                "Check growth forecasts and anomalies",
                "I couldn't verify growth-risk forecasts right now because the forecasting data did not load successfully."
            );
        }
    }

    private DraftPerformanceAnswer indexEvidence(PromptIntent promptIntent, String normalized, String connectionId) {
        try {
            if (normalized.contains("unused index")) {
                Map<String, Object> report = indexAdvisorService.getIndexHealthReport(connectionId);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> unusedIndexes = (List<Map<String, Object>>) report.getOrDefault("unusedIndexes", List.of());
                if (unusedIndexes != null && !unusedIndexes.isEmpty()) {
                    String message = "Unused indexes detected:\n" + unusedIndexes.stream().limit(10)
                        .map(idx -> "- `" + idx.getOrDefault("tableName", "?") + "." + idx.getOrDefault("indexName", "?") + "`")
                        .collect(Collectors.joining("\n"));
                    return draft(
                        "Index Recommendations",
                        "Check performance advisor data",
                        message,
                        "unused_indexes",
                        EvidenceBundle.Source.LIVE_METADATA,
                        unusedIndexes.stream().limit(10).toList(),
                        Map.of("count", unusedIndexes.size()),
                        unusedIndexes.stream().map(idx -> String.valueOf(idx.get("tableName"))).filter(Objects::nonNull).collect(Collectors.toSet()),
                        List.of("Live index health report"),
                        0.92
                    );
                }
                return insufficiency("Index Recommendations", "Check performance advisor data", "No unused indexes are currently reported for this connection.");
            }

            if (normalized.contains("duplicate index")) {
                Map<String, Object> report = indexAdvisorService.getIndexHealthReport(connectionId);
                @SuppressWarnings("unchecked")
                List<Map<String, Object>> duplicateIndexes = (List<Map<String, Object>>) report.getOrDefault("duplicateIndexes", List.of());
                if (duplicateIndexes != null && !duplicateIndexes.isEmpty()) {
                    String message = "Duplicate or redundant indexes detected:\n" + duplicateIndexes.stream().limit(10)
                        .map(idx -> "- `" + idx.getOrDefault("tableName", "?") + "." + idx.getOrDefault("indexName", "?") + "`")
                        .collect(Collectors.joining("\n"));
                    return draft(
                        "Index Recommendations",
                        "Check performance advisor data",
                        message,
                        "duplicate_indexes",
                        EvidenceBundle.Source.LIVE_METADATA,
                        duplicateIndexes.stream().limit(10).toList(),
                        Map.of("count", duplicateIndexes.size()),
                        duplicateIndexes.stream().map(idx -> String.valueOf(idx.get("tableName"))).filter(Objects::nonNull).collect(Collectors.toSet()),
                        List.of("Live index health report"),
                        0.92
                    );
                }
                return insufficiency("Index Recommendations", "Check performance advisor data", "No duplicate indexes are currently reported for this connection.");
            }

            List<PerformanceAction> indexActions = performanceActionRepository.findByConnectionIdAndCategoryAndStatusOrderByRoiDesc(
                connectionId,
                PerformanceAction.ActionCategory.INDEX,
                PerformanceAction.ActionStatus.PENDING
            );
            if (indexActions != null && !indexActions.isEmpty() && prefersWorkloadRankedIndexActions(normalized)) {
                String message = buildIndexActionMessage(indexActions);
                return draft(
                    "Index Recommendations",
                    "Check performance action index evidence",
                    message,
                    "index_recommendations",
                    EvidenceBundle.Source.PERFORMANCE_ACTION,
                    indexActions.stream().limit(15).map(action -> row(
                        "table", action.getTargetObject(),
                        "columns", action.getTargetSecondary(),
                        "roi", action.getRoi(),
                        "impactScore", action.getImpactScore(),
                        "effortScore", action.getEffortScore(),
                        "queriesAffected", action.getQueriesAffected()
                    )).toList(),
                    Map.of("count", indexActions.size()),
                    indexActions.stream().map(PerformanceAction::getTargetObject).filter(Objects::nonNull).collect(Collectors.toSet()),
                    List.of("Performance actions", "Key-column workload analysis", "Index advisor cached actions"),
                    0.95
                );
            }

            List<IndexRecommendationEntity> recommendations = indexRecommendationRepository
                .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(connectionId, IndexRecommendationEntity.Status.PENDING);
            if (recommendations != null && !recommendations.isEmpty()) {
                String message = buildPendingRecommendationMessage(recommendations, normalized);
                return draft(
                    "Index Recommendations",
                    "Check cached index recommendations",
                    message,
                    "index_recommendations",
                    EvidenceBundle.Source.INDEX_RECOMMENDATION,
                    recommendations.stream().limit(15).map(rec -> row(
                        "table", rec.getTableName(),
                        "columns", rec.getColumnNames(),
                        "priority", rec.getPriority(),
                        "impact", rec.getEstimatedImpact(),
                        "affectedQueries", rec.getAffectedQueries()
                    )).toList(),
                    Map.of("count", recommendations.size()),
                    recommendations.stream().map(IndexRecommendationEntity::getTableName).filter(Objects::nonNull).collect(Collectors.toSet()),
                    List.of("Cached index recommendations"),
                    0.96
                );
            }

            if (indexActions != null && !indexActions.isEmpty()) {
                String message = buildIndexActionMessage(indexActions);
                return draft(
                    "Index Recommendations",
                    "Check performance action index evidence",
                    message,
                    "index_recommendations",
                    EvidenceBundle.Source.PERFORMANCE_ACTION,
                    indexActions.stream().limit(15).map(action -> row(
                        "table", action.getTargetObject(),
                        "columns", action.getTargetSecondary(),
                        "roi", action.getRoi(),
                        "impactScore", action.getImpactScore(),
                        "effortScore", action.getEffortScore(),
                        "queriesAffected", action.getQueriesAffected()
                    )).toList(),
                    Map.of("count", indexActions.size()),
                    indexActions.stream().map(PerformanceAction::getTargetObject).filter(Objects::nonNull).collect(Collectors.toSet()),
                    List.of("Performance actions", "Key-column workload analysis", "Index advisor cached actions"),
                    0.95
                );
            }

            DraftPerformanceAnswer workloadColumnEvidence = columnImpactEvidence(connectionId, normalized);
            if (workloadColumnEvidence != null && workloadColumnEvidence.evidence().sufficient()) {
                String message = "Index recommendations inferred from current workload pressure:\n\n"
                    + workloadColumnEvidence.message();
                EvidenceBundle indexEvidence = EvidenceBundle.sufficient(
                    PromptIntent.Domain.PERFORMANCE,
                    "index_recommendations",
                    EvidenceBundle.Source.PERFORMANCE_VAULT,
                    "index_recommendations",
                    workloadColumnEvidence.evidence().primaryRows(),
                    workloadColumnEvidence.evidence().structuredPayload(),
                    workloadColumnEvidence.evidence().coverage(),
                    workloadColumnEvidence.evidence().confidence(),
                    workloadColumnEvidence.evidence().freshness(),
                    workloadColumnEvidence.evidence().sourceQuery(),
                    workloadColumnEvidence.evidence().supportingObjectNames()
                );
                return new DraftPerformanceAnswer(
                    "Index Recommendations",
                    "Scout workload key-column and anti-pattern evidence",
                    message,
                    indexEvidence,
                    List.of(
                        "Vault key-column usage analysis",
                        "Vault column anti-pattern analysis",
                        "Vault slow-query usage signals",
                        "Vault index recommendation/action evidence"
                    ),
                    workloadColumnEvidence.gapsOrCaveats(),
                    workloadColumnEvidence.followUpPrompt(),
                    workloadColumnEvidence.executedSql()
                );
            }

            PerformanceAnalysis liveAnalysis = databaseAdvisorService.analyzePerformance(connectionId);
            List<IndexRecommendation> liveRecommendations = liveAnalysis != null ? liveAnalysis.getIndexRecommendations() : List.of();
            if (liveRecommendations != null && !liveRecommendations.isEmpty()) {
                String message = buildLiveRecommendationMessage(liveRecommendations);
                return draft(
                    "Index Recommendations",
                    "Run live performance advisor",
                    message,
                    "index_recommendations",
                    EvidenceBundle.Source.LIVE_METADATA,
                    liveRecommendations.stream().limit(10).map(rec -> row(
                        "table", rec.getTableName(),
                        "columns", rec.getColumns(),
                        "priority", rec.getPriority(),
                        "reason", rec.getReasoning()
                    )).toList(),
                    metadata("overallHealth", liveAnalysis.getOverallHealth() != null ? liveAnalysis.getOverallHealth().name() : null),
                    liveRecommendations.stream().map(IndexRecommendation::getTableName).filter(Objects::nonNull).collect(Collectors.toSet()),
                    List.of("Live performance advisor"),
                    0.9
                );
            }

            return insufficiency(
                "Index Recommendations",
                promptIntent.requiresLiveMetadata() ? "Run live performance advisor" : "Check cached index recommendations",
                "No stored index recommendations exist yet for this connection, and live advisor analysis did not find an immediate index candidate for the current workload."
            );
        } catch (Exception e) {
            return insufficiency(
                "Index Recommendations",
                "Run live performance advisor",
                "I could not verify index recommendations for the current workload right now because the performance advisor did not complete successfully."
            );
        }
    }

    private DraftPerformanceAnswer slowQueryEvidence(String normalized, String connectionId) {
        try {
            Optional<SlowQueryHistory> latestOpt = slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc(connectionId);
            if (latestOpt.isEmpty() || (looksLikeCurrentPerformanceQuestion(normalized) && isStaleSlowHistory(latestOpt.get()))) {
                DraftPerformanceAnswer snapshotAnswer = performanceSnapshotTopQueryEvidence(normalized, connectionId, latestOpt.orElse(null));
                if (snapshotAnswer != null && snapshotAnswer.evidence().sufficient()) {
                    return snapshotAnswer;
                }
            }
            if (latestOpt.isPresent()) {
                SlowQueryHistory latest = latestOpt.get();
                Optional<SlowQueryAnalysis> analysisOpt = readSlowQueryAnalysis(latest);
                if (analysisOpt.isPresent()) {
                    SlowQueryAnalysis analysis = analysisOpt.get();
                    if (analysis.getTopSlowQueries() != null && !analysis.getTopSlowQueries().isEmpty()) {
                        if (normalized.contains("health") || normalized.contains("status") || normalized.contains("summary")) {
                            String message = buildSlowQueryHealthMessage(latest);
                            return draft(
                                "Performance Health",
                                "Check cached slow-query metadata",
                                message,
                                "slow_query_health",
                                EvidenceBundle.Source.SLOW_QUERY,
                                List.of(row(
                                    "overallHealth", latest.getOverallHealth(),
                                    "totalSlowQueries", latest.getTotalSlowQueries(),
                                    "criticalCount", latest.getCriticalCount(),
                                    "highCount", latest.getHighCount()
                                )),
                                metadata("createdAt", latest.getCreatedAt()),
                                Set.of(),
                                List.of("Cached slow-query history"),
                                0.9
                            );
                        }

                        int requestedCount = requestedSlowQueryCount(normalized);
                        boolean wantsRanking = requestedCount > 1
                            || normalized.contains("top slow")
                            || normalized.contains("slow queries")
                            || normalized.contains("worst queries")
                            || normalized.contains("top queries");
                        boolean wantsCauses = normalized.contains("cause")
                            || normalized.contains("causing")
                            || normalized.contains("slowness")
                            || normalized.contains("why ");

                        if (wantsRanking || wantsCauses) {
                            List<SlowQuery> ranked = analysis.getTopSlowQueries().stream()
                                .sorted((left, right) -> Double.compare(bestExecutionTime(right), bestExecutionTime(left)))
                                .limit(Math.max(1, requestedCount))
                                .toList();
                            String anchorSql = ranked.isEmpty() ? null : resolveSlowQuerySql(ranked.getFirst());
                            StringBuilder message = new StringBuilder("Top ")
                                .append(ranked.size())
                                .append(" slow queries right now");
                            if (wantsCauses) {
                                message.append(", with the most likely causes of the slowness");
                            }
                            message.append(":\n\n");

                            int ordinal = 1;
                            for (SlowQuery query : ranked) {
                                message.append(ordinal++).append(". **")
                                    .append(nonBlank(safeCap(resolveSlowQuerySql(query), 90), "Slow query"))
                                    .append("**\n");
                                message.append("   - Avg execution time: ").append(formatMillis(bestExecutionTime(query))).append("\n");
                                if (query.getCallCount() != null) {
                                    message.append("   - Call volume: ").append(query.getCallCount()).append(" executions\n");
                                }
                                if (query.getRowsExamined() != null && query.getRowsSent() != null) {
                                    message.append("   - Scan profile: ")
                                        .append(query.getRowsExamined()).append(" rows examined vs ")
                                        .append(query.getRowsSent()).append(" rows returned\n");
                                }
                                if (query.getAffectedTables() != null && !query.getAffectedTables().isEmpty()) {
                                    message.append("   - Tables involved: ")
                                        .append(String.join(", ", query.getAffectedTables())).append("\n");
                                }
                                message.append("   - Likely cause: ").append(slowQueryCauseSummary(query)).append("\n");
                                if (query.getSuggestions() != null && !query.getSuggestions().isEmpty()) {
                                    message.append("   - First action: ").append(safeCap(query.getSuggestions().getFirst(), 160)).append("\n");
                                }
                                message.append("\n");
                            }

                            EvidenceBundle evidence = EvidenceBundle.sufficient(
                                PromptIntent.Domain.PERFORMANCE,
                                "slow_query_ranking",
                                EvidenceBundle.Source.SLOW_QUERY,
                                "slow_query_ranking",
                                ranked.stream().map(query -> row(
                                    "query", resolveSlowQuerySql(query),
                                    "severity", query.getSeverity(),
                                    "executionTimeMs", bestExecutionTime(query),
                                    "callCount", query.getCallCount(),
                                    "rowsExamined", query.getRowsExamined(),
                                    "rowsSent", query.getRowsSent(),
                                    "tables", query.getAffectedTables(),
                                    "causeSummary", slowQueryCauseSummary(query)
                                )).toList(),
                                row(
                                    "createdAt", latest.getCreatedAt(),
                                    "requestedCount", requestedCount,
                                    "returnedCount", ranked.size()
                                ),
                                ranked.size() >= requestedCount ? 0.85 : 0.78,
                                ranked.size() >= requestedCount ? 0.95 : 0.82,
                                "cached_metadata",
                                anchorSql,
                                ranked.stream()
                                    .flatMap(query -> query.getAffectedTables() == null ? java.util.stream.Stream.empty() : query.getAffectedTables().stream())
                                    .filter(Objects::nonNull)
                                    .collect(Collectors.toSet())
                            );
                            return new DraftPerformanceAnswer(
                                "Performance Health",
                                "Check cached slow-query metadata",
                                message.toString().trim(),
                                evidence,
                                List.of("Cached slow-query history with per-query execution and scan characteristics."),
                                List.of(),
                                null,
                                anchorSql
                            );
                        }

                        SlowQuery slowest = analysis.getTopSlowQueries().stream()
                            .max((left, right) -> Double.compare(bestExecutionTime(left), bestExecutionTime(right)))
                            .orElse(null);
                        if (slowest != null) {
                            String fullQueryText = resolveSlowQuerySql(slowest);
                            String message = buildSlowestQueryMessage(slowest, latest);
                            EvidenceBundle evidence = EvidenceBundle.sufficient(
                                PromptIntent.Domain.PERFORMANCE,
                                "slow_query_detail",
                                EvidenceBundle.Source.SLOW_QUERY,
                                "slow_query_detail",
                                List.of(row(
                                    "query", fullQueryText,
                                    "severity", slowest.getSeverity(),
                                    "executionTimeMs", bestExecutionTime(slowest),
                                    "tables", slowest.getAffectedTables()
                                )),
                                metadata("createdAt", latest.getCreatedAt()),
                                0.85,
                                0.9,
                                "cached_metadata",
                                fullQueryText,
                                slowest.getAffectedTables() == null ? Set.of() : Set.copyOf(slowest.getAffectedTables())
                            );
                            return new DraftPerformanceAnswer(
                                "Performance Health",
                                "Check cached slow-query metadata",
                                message,
                                evidence,
                                List.of("Cached slow-query history"),
                                List.of(),
                                null,
                                fullQueryText
                            );
                        }
                    }
                }
            }

            PerformanceAnalysis liveAnalysis = databaseAdvisorService.analyzePerformance(connectionId);
            if (liveAnalysis != null && liveAnalysis.getSlowQueries() != null && !liveAnalysis.getSlowQueries().isEmpty()) {
                String message = "Live performance advisor flagged slow-query hotspots:\n" + liveAnalysis.getSlowQueries().stream().limit(5)
                    .map(query -> "- `" + safeCap(query.getQuery(), 120) + "`")
                    .collect(Collectors.joining("\n"));
                return draft(
                    "Performance Health",
                    "Run live performance advisor",
                    message,
                    "slow_query_detail",
                    EvidenceBundle.Source.LIVE_METADATA,
                    liveAnalysis.getSlowQueries().stream().limit(5).map(query -> row(
                        "query", query.getQuery(),
                        "averageTime", query.getAverageTime(),
                        "executionCount", query.getExecutionCount(),
                        "table", query.getTableName()
                    )).toList(),
                    metadata("overallHealth", liveAnalysis.getOverallHealth() != null ? liveAnalysis.getOverallHealth().name() : null),
                    liveAnalysis.getSlowQueries().stream().map(PerformanceAnalysis.SlowQueryAnalysis::getTableName).filter(Objects::nonNull).collect(Collectors.toSet()),
                    List.of("Live performance advisor"),
                    0.85
                );
            }
        } catch (Exception ignored) {
            // fall through to insufficiency
        }
        return insufficiency(
            "Performance Health",
            "Check cached slow-query metadata",
            "I do not have verified slow-query metadata for this connection yet."
        );
    }

    private DraftPerformanceAnswer performanceSnapshotTopQueryEvidence(
        String normalized,
        String connectionId,
        SlowQueryHistory staleHistory
    ) {
        PerformanceSnapshot latestSnapshot = performanceSnapshotRepository.findFirstByConnectionIdOrderBySnapshotTimeDesc(connectionId);
        if (latestSnapshot == null || latestSnapshot.getTopQueries() == null || latestSnapshot.getTopQueries().isBlank()) {
            return null;
        }

        List<Map<String, Object>> topQueries = parseSnapshotTopQueries(latestSnapshot.getTopQueries());
        if (topQueries.isEmpty()) {
            return null;
        }

        int requestedCount = Math.max(1, requestedSlowQueryCount(normalized));
        List<Map<String, Object>> ranked = topQueries.stream()
            .sorted((left, right) -> Double.compare(snapshotQueryTotalTime(right), snapshotQueryTotalTime(left)))
            .limit(requestedCount)
            .toList();
        if (ranked.isEmpty()) {
            return null;
        }

        StringBuilder message = new StringBuilder();
        message.append("Top ").append(ranked.size()).append(" current query load contributors from the latest performance snapshot:\n\n");
        if (staleHistory != null && isStaleSlowHistory(staleHistory)) {
            message.append("Freshness note: stored slow-query history is older than ")
                .append(SLOW_QUERY_FRESHNESS_WINDOW.toHours())
                .append(" hours, so I used the newer performance snapshot captured at `")
                .append(latestSnapshot.getSnapshotTime())
                .append("`.\n\n");
        }

        int ordinal = 1;
        for (Map<String, Object> query : ranked) {
            String queryText = snapshotQueryText(query);
            message.append(ordinal++).append(". **`").append(safeCap(queryText, 120)).append("`**\n");
            message.append("   - Total DB time: ").append(formatMillis(snapshotQueryTotalTime(query))).append("\n");
            message.append("   - Avg time: ").append(formatMillis(snapshotQueryAvgTime(query))).append("\n");
            Long callCount = snapshotQueryLong(query, "callCount", "count", "COUNT_STAR");
            if (callCount != null) {
                message.append("   - Call volume: ").append(callCount).append(" executions\n");
            }
            Long rowsExamined = snapshotQueryLong(query, "rowsExamined", "ROWS_EXAMINED", "rows_examined");
            if (rowsExamined != null) {
                message.append("   - Rows examined: ").append(rowsExamined).append("\n");
            }
            message.append("   - Evidence source: vault performance snapshot\n\n");
        }

        String anchorSql = snapshotQueryText(ranked.getFirst());
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.PERFORMANCE,
            "slow_query_ranking",
            EvidenceBundle.Source.PERFORMANCE_VAULT,
            "slow_query_ranking",
            ranked.stream().map(query -> row(
                "query", snapshotQueryText(query),
                "totalTimeMs", snapshotQueryTotalTime(query),
                "avgTimeMs", snapshotQueryAvgTime(query),
                "callCount", snapshotQueryLong(query, "callCount", "count", "COUNT_STAR"),
                "rowsExamined", snapshotQueryLong(query, "rowsExamined", "ROWS_EXAMINED", "rows_examined")
            )).toList(),
            snapshotTopQueryPayload(latestSnapshot, staleHistory),
            0.88,
            isFreshPerformanceSnapshot(latestSnapshot) ? 0.95 : 0.86,
            latestSnapshot.getSnapshotTime() == null ? "cached_metadata" : "performance_snapshot:" + latestSnapshot.getSnapshotTime(),
            anchorSql,
            ranked.stream()
                .map(this::snapshotQueryText)
                .filter(Objects::nonNull)
                .map(this::firstRelationFromSql)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet())
        );
        return new DraftPerformanceAnswer(
            "Performance Health",
            "Check latest performance snapshot",
            message.toString().trim(),
            evidence,
            List.of("Latest vault performance snapshot top_queries", "Slow-query history freshness check"),
            staleHistory != null && isStaleSlowHistory(staleHistory)
                ? List.of("Stored slow-query history is stale, so current ranking uses performance snapshots.")
                : List.of(),
            null,
            anchorSql
        );
    }

    private int requestedSlowQueryCount(String normalized) {
        Matcher numericTop = Pattern.compile("\\btop\\s+(\\d+)\\b").matcher(normalized);
        if (numericTop.find()) {
            try {
                return Math.max(1, Integer.parseInt(numericTop.group(1)));
            } catch (NumberFormatException ignored) {
                return 3;
            }
        }
        if (normalized.contains("top three")) {
            return 3;
        }
        if (normalized.contains("top five")) {
            return 5;
        }
        return normalized.contains("queries") ? 3 : 1;
    }

    private boolean looksLikeCurrentPerformanceQuestion(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("right now")
            || normalized.contains("current")
            || normalized.contains("currently")
            || normalized.contains("today")
            || normalized.contains("latest")
            || normalized.contains("now");
    }

    private boolean isStaleSlowHistory(SlowQueryHistory history) {
        if (history == null || history.getCreatedAt() == null) {
            return true;
        }
        return history.getCreatedAt().isBefore(LocalDateTime.now().minus(SLOW_QUERY_FRESHNESS_WINDOW));
    }

    private boolean isFreshPerformanceSnapshot(PerformanceSnapshot snapshot) {
        return snapshot != null
            && snapshot.getSnapshotTime() != null
            && !snapshot.getSnapshotTime().isBefore(LocalDateTime.now().minus(PERFORMANCE_SNAPSHOT_FRESHNESS_WINDOW));
    }

    private List<Map<String, Object>> parseSnapshotTopQueries(String topQueriesJson) {
        if (topQueriesJson == null || topQueriesJson.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(topQueriesJson, new TypeReference<List<Map<String, Object>>>() {});
        } catch (Exception e) {
            return List.of();
        }
    }

    private String snapshotQueryText(Map<String, Object> query) {
        if (query == null) {
            return "Unknown query";
        }
        for (String key : List.of("queryText", "sampleQuery", "normalizedQuery", "DIGEST_TEXT", "digestText", "sqlText")) {
            Object value = query.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "Unknown query";
    }

    private Map<String, Object> snapshotTopQueryPayload(PerformanceSnapshot snapshot, SlowQueryHistory staleHistory) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (snapshot != null && snapshot.getSnapshotTime() != null) {
            payload.put("snapshotTime", snapshot.getSnapshotTime());
            payload.put("snapshotAgeMinutes", Duration.between(snapshot.getSnapshotTime(), LocalDateTime.now()).toMinutes());
        }
        if (staleHistory != null && staleHistory.getCreatedAt() != null) {
            payload.put("slowQueryHistoryCreatedAt", staleHistory.getCreatedAt());
        }
        payload.put("fallbackReason", staleHistory == null ? "slow_query_history_missing" : "slow_query_history_stale");
        return payload;
    }

    private double snapshotQueryTotalTime(Map<String, Object> query) {
        Double value = snapshotQueryDouble(query, "totalTime", "totalTimeMs", "total_ms", "SUM_TIMER_WAIT");
        if (value != null) {
            return value;
        }
        Double avg = snapshotQueryDouble(query, "avgTime", "avgTimeMs", "avg_ms", "AVG_TIMER_WAIT");
        Long count = snapshotQueryLong(query, "callCount", "count", "COUNT_STAR");
        return avg != null && count != null ? avg * count : 0d;
    }

    private double snapshotQueryAvgTime(Map<String, Object> query) {
        Double value = snapshotQueryDouble(query, "avgTime", "avgTimeMs", "avg_ms", "AVG_TIMER_WAIT");
        if (value != null) {
            return value;
        }
        Double total = snapshotQueryDouble(query, "totalTime", "totalTimeMs", "total_ms", "SUM_TIMER_WAIT");
        Long count = snapshotQueryLong(query, "callCount", "count", "COUNT_STAR");
        return total != null && count != null && count > 0 ? total / count : 0d;
    }

    private Double snapshotQueryDouble(Map<String, Object> query, String... keys) {
        if (query == null) {
            return null;
        }
        for (String key : keys) {
            Object value = query.get(key);
            if (value instanceof Number number) {
                return number.doubleValue();
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                try {
                    return Double.parseDouble(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // Try the next key.
                }
            }
        }
        return null;
    }

    private Long snapshotQueryLong(Map<String, Object> query, String... keys) {
        if (query == null) {
            return null;
        }
        for (String key : keys) {
            Object value = query.get(key);
            if (value instanceof Number number) {
                return number.longValue();
            }
            if (value != null && !String.valueOf(value).isBlank()) {
                try {
                    return Long.parseLong(String.valueOf(value));
                } catch (NumberFormatException ignored) {
                    // Try the next key.
                }
            }
        }
        return null;
    }

    private String snapshotWaitName(Map<String, Object> wait) {
        if (wait == null) {
            return "unknown wait";
        }
        for (String key : List.of("eventName", "waitEvent", "waitEventName", "EVENT_NAME", "event")) {
            Object value = wait.get(key);
            if (value != null && !String.valueOf(value).isBlank()) {
                return String.valueOf(value).trim();
            }
        }
        return "unknown wait";
    }

    private double snapshotWaitTime(Map<String, Object> wait) {
        Double value = snapshotQueryDouble(wait, "waitTime", "waitTimeMs", "totalWaitMs", "SUM_TIMER_WAIT");
        return value == null ? 0d : value;
    }

    private String firstRelationFromSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        Matcher matcher = Pattern.compile("(?i)\\b(?:from|join|into|update)\\s+`?([a-zA-Z0-9_.]+)`?").matcher(sql);
        return matcher.find() ? matcher.group(1) : null;
    }

    private String slowQueryCauseSummary(SlowQuery query) {
        List<String> causes = new java.util.ArrayList<>();
        if (Boolean.FALSE.equals(query.getHasIndex())) {
            causes.add("no supporting index is being used");
        }
        if (query.getRowsExamined() != null && query.getRowsSent() != null && query.getRowsExamined() > Math.max(1000L, query.getRowsSent() * 20L)) {
            causes.add("the query is scanning far more rows than it returns");
        }
        if (query.getCallCount() != null && query.getCallCount() > 1000) {
            causes.add("the execution count is high enough that even moderate latency compounds into major total load");
        }
        if (query.getStdDevExecutionTimeMs() != null && query.getStdDevExecutionTimeMs() > Math.max(500d, bestExecutionTime(query) * 0.5d)) {
            causes.add("latency is unstable, which usually points to data skew, contention, or an inconsistent execution plan");
        }
        if (query.getAffectedTables() != null && query.getAffectedTables().size() > 2) {
            causes.add("multiple large tables are involved, which increases join and scan cost");
        }
        if (query.getSuggestions() != null && !query.getSuggestions().isEmpty()) {
            causes.add("cached advisor analysis already flagged a concrete optimization path");
        }
        if (causes.isEmpty()) {
            causes.add("the cached evidence shows high execution latency, but it does not isolate one dominant bottleneck beyond the query cost itself");
        }
        return String.join("; ", causes);
    }

    private String formatMillis(double value) {
        if (value >= 1000d) {
            return String.format(Locale.ROOT, "%.2fs", value / 1000d);
        }
        return String.format(Locale.ROOT, "%.0fms", value);
    }

    private DraftPerformanceAnswer priorQueryFollowUpEvidence(
        String normalized,
        String connectionId,
        ResolvedConversationContext resolvedConversationContext
    ) {
        String priorSql = resolvePriorQuerySql(resolvedConversationContext);
        SlowQuery matchedSlowQuery = resolveSlowQueryFollowUpTarget(connectionId, normalized, resolvedConversationContext).orElse(null);
        String matchedSql = matchedSlowQuery == null ? null : resolveSlowQuerySql(matchedSlowQuery);

        if ((priorSql == null || priorSql.isBlank()) && matchedSql != null && !matchedSql.isBlank()) {
            priorSql = matchedSql;
        } else if (shouldPreferMatchedSlowQuerySql(priorSql, matchedSlowQuery, normalized)) {
            priorSql = matchedSql;
        }

        if (priorSql == null || priorSql.isBlank()) {
            return insufficiency(
                "Slow Query Detail",
                "Reuse prior slow-query context",
                "I understood this as a follow-up asking for the previously identified query text, but that SQL was not captured in the prior context."
            );
        }

        StringBuilder sb = new StringBuilder("Here is the full SQL text for the earlier query from cached conversation context:\n\n");
        sb.append("```sql\n").append(priorSql).append("\n```");
        if (matchedSlowQuery != null) {
            sb.append("\n\n");
            if (asksForScanExplanation(normalized)) {
                sb.append("Why it is scanning so many rows:\n");
                if (matchedSlowQuery.getRowsExamined() != null || matchedSlowQuery.getRowsSent() != null) {
                    sb.append("- Scan profile: ")
                        .append(nonBlank(formatRowCount(matchedSlowQuery.getRowsExamined()), "unknown"))
                        .append(" rows examined vs ")
                        .append(nonBlank(formatRowCount(matchedSlowQuery.getRowsSent()), "unknown"))
                        .append(" rows returned.\n");
                    if (matchedSlowQuery.getRowsExamined() != null
                        && matchedSlowQuery.getRowsSent() != null
                        && matchedSlowQuery.getRowsExamined() > 0) {
                        sb.append("- Efficiency: about ")
                            .append(String.format(Locale.ROOT, "%.2f%%", matchedSlowQuery.getEfficiencyRatio() * 100.0))
                            .append(" of examined rows make it into the result set.\n");
                    }
                }
                if (matchedSlowQuery.getHasIndex() != null) {
                    sb.append("- Index posture: ")
                        .append(Boolean.TRUE.equals(matchedSlowQuery.getHasIndex())
                            ? "an index is present, so the scan is likely being driven by poor selectivity, a wide range, or an unfavorable join/order strategy."
                            : "the cached analysis shows no supporting index, which is a direct reason the engine has to scan far more rows.")
                        .append("\n");
                }
            }
            sb.append("- Likely cause: ").append(slowQueryCauseSummary(matchedSlowQuery)).append("\n");
            if (matchedSlowQuery.getAffectedTables() != null && !matchedSlowQuery.getAffectedTables().isEmpty()) {
                sb.append("- Tables involved: ").append(String.join(", ", matchedSlowQuery.getAffectedTables())).append("\n");
            }
            if (matchedSlowQuery.getSuggestions() != null && !matchedSlowQuery.getSuggestions().isEmpty()) {
                sb.append("- First action: ").append(safeCap(matchedSlowQuery.getSuggestions().getFirst(), 180)).append("\n");
            }
        }
        if (resolvedConversationContext != null
            && resolvedConversationContext.anchorQuestion() != null
            && !resolvedConversationContext.anchorQuestion().isBlank()) {
            sb.append("\n\nSource: prior thread context anchored on `")
                .append(safeCap(resolvedConversationContext.anchorQuestion(), 120))
                .append("`.");
        }

        Set<String> supportingObjects = matchedSlowQuery != null
            ? matchedSlowQuery.getAffectedTables() == null ? Set.of() : matchedSlowQuery.getAffectedTables().stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .collect(Collectors.toSet())
            : resolvedConversationTables(resolvedConversationContext);
        if (supportingObjects.isEmpty()) {
            supportingObjects = resolvedConversationTables(resolvedConversationContext);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        if (resolvedConversationContext != null && resolvedConversationContext.matchedContextId() != null) {
            payload.put("matchedContextId", resolvedConversationContext.matchedContextId());
        }
        if (resolvedConversationContext != null && resolvedConversationContext.anchorQuestion() != null) {
            payload.put("anchorQuestion", resolvedConversationContext.anchorQuestion());
        }
        payload.put("reusedPriorSql", true);
        if (matchedSlowQuery != null) {
            if (matchedSlowQuery.getRowsExamined() != null) {
                payload.put("rowsExamined", matchedSlowQuery.getRowsExamined());
            }
            if (matchedSlowQuery.getRowsSent() != null) {
                payload.put("rowsSent", matchedSlowQuery.getRowsSent());
            }
            if (matchedSlowQuery.getHasIndex() != null) {
                payload.put("hasIndex", matchedSlowQuery.getHasIndex());
            }
            if (matchedSlowQuery.getCallCount() != null) {
                payload.put("callCount", matchedSlowQuery.getCallCount());
            }
            payload.put("matchedSlowQuery", true);
        }

        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.PERFORMANCE,
            "prior_query_context",
            EvidenceBundle.Source.SLOW_QUERY,
            "slow_query_detail",
            List.of(row(
                "query", priorSql,
                "rowsExamined", matchedSlowQuery == null ? null : matchedSlowQuery.getRowsExamined(),
                "rowsSent", matchedSlowQuery == null ? null : matchedSlowQuery.getRowsSent(),
                "hasIndex", matchedSlowQuery == null ? null : matchedSlowQuery.getHasIndex(),
                "tables", matchedSlowQuery == null ? List.copyOf(supportingObjects) : matchedSlowQuery.getAffectedTables(),
                "causeSummary", matchedSlowQuery == null ? null : slowQueryCauseSummary(matchedSlowQuery)
            )),
            payload,
            matchedSlowQuery != null ? 0.94 : 0.82,
            matchedSlowQuery != null ? 0.97 : 0.9,
            "conversation_context",
            priorSql,
            supportingObjects
        );
        List<String> supportingEvidence = new java.util.ArrayList<>();
        supportingEvidence.add("Reused prior query text stored in vault conversation context.");
        if (matchedSlowQuery != null) {
            supportingEvidence.add("Matched the prior query against cached slow-query history to recover scan metrics and likely causes.");
        }
        return new DraftPerformanceAnswer(
            "Slow Query Detail",
            "Reuse prior slow-query context",
            sb.toString(),
            evidence,
            List.copyOf(supportingEvidence),
            List.of(),
            null,
            priorSql
        );
    }

    private Optional<SlowQuery> findMatchingSlowQuery(String connectionId, String priorSql) {
        if (connectionId == null || connectionId.isBlank() || priorSql == null || priorSql.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<SlowQueryHistory> latestOpt = slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc(connectionId);
            if (latestOpt.isEmpty()) {
                return Optional.empty();
            }
            Optional<SlowQueryAnalysis> analysisOpt = readSlowQueryAnalysis(latestOpt.get());
            if (analysisOpt.isEmpty()) {
                return Optional.empty();
            }
            SlowQueryAnalysis analysis = analysisOpt.get();
            if (analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                return Optional.empty();
            }
            String target = canonicalSql(priorSql);
            return analysis.getTopSlowQueries().stream()
                .filter(Objects::nonNull)
                .sorted((left, right) -> Integer.compare(matchStrength(right, target), matchStrength(left, target)))
                .filter(query -> matchStrength(query, target) > 0)
                .findFirst();
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private int matchStrength(SlowQuery query, String target) {
        if (query == null || target == null || target.isBlank()) {
            return 0;
        }
        String raw = canonicalSql(resolveSlowQuerySql(query));
        if (!raw.isBlank()) {
            if (raw.equals(target)) {
                return 4;
            }
            if (raw.contains(target) || target.contains(raw)) {
                return 3;
            }
        }
        String normalized = canonicalSql(query.getNormalizedQuery());
        if (!normalized.isBlank()) {
            if (normalized.equals(target)) {
                return 3;
            }
            if (normalized.contains(target) || target.contains(normalized)) {
                return 2;
            }
        }
        return 0;
    }

    private Optional<SlowQuery> resolveSlowQueryFollowUpTarget(
        String connectionId,
        String normalized,
        ResolvedConversationContext resolvedConversationContext
    ) {
        String priorSql = resolvePriorQuerySql(resolvedConversationContext);
        Optional<SlowQuery> matchedPrior = findMatchingSlowQuery(connectionId, priorSql);
        if (matchedPrior.isPresent()) {
            return matchedPrior;
        }

        Optional<SlowQueryAnalysis> analysisOpt = loadLatestSlowQueryAnalysis(connectionId);
        if (analysisOpt.isEmpty() || analysisOpt.get().getTopSlowQueries() == null || analysisOpt.get().getTopSlowQueries().isEmpty()) {
            return Optional.empty();
        }

        List<SlowQuery> ranked = analysisOpt.get().getTopSlowQueries().stream()
            .filter(Objects::nonNull)
            .sorted((left, right) -> Double.compare(bestExecutionTime(right), bestExecutionTime(left)))
            .toList();
        if (ranked.isEmpty()) {
            return Optional.empty();
        }

        int ordinal = requestedSlowQueryOrdinal(normalized);
        int index = Math.max(0, Math.min(ranked.size() - 1, ordinal - 1));
        return Optional.of(ranked.get(index));
    }

    private Optional<SlowQueryAnalysis> loadLatestSlowQueryAnalysis(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return Optional.empty();
        }
        try {
            Optional<SlowQueryHistory> latestOpt = slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc(connectionId);
            return latestOpt.flatMap(this::readSlowQueryAnalysis);
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private Optional<SlowQueryAnalysis> readSlowQueryAnalysis(SlowQueryHistory history) {
        if (history == null || history.getAnalysisData() == null || history.getAnalysisData().isBlank()) {
            return Optional.empty();
        }
        try {
            return Optional.of(objectMapper.readValue(history.getAnalysisData(), SlowQueryAnalysis.class));
        } catch (Exception ignored) {
            return Optional.empty();
        }
    }

    private boolean shouldPreferMatchedSlowQuerySql(String priorSql, SlowQuery matchedSlowQuery, String normalized) {
        if (matchedSlowQuery == null) {
            return false;
        }
        String matchedSql = resolveSlowQuerySql(matchedSlowQuery);
        if (matchedSql == null || matchedSql.isBlank()) {
            return false;
        }
        if (priorSql == null || priorSql.isBlank()) {
            return true;
        }
        if (requestedSlowQueryOrdinal(normalized) > 0) {
            String normalizedPrior = canonicalSql(priorSql);
            String normalizedActual = canonicalSql(matchedSql);
            String normalizedDigest = canonicalSql(nonBlank(matchedSlowQuery.getNormalizedQuery(), matchedSlowQuery.getQueryText()));
            return !normalizedActual.equals(normalizedPrior)
                && !normalizedDigest.isBlank()
                && normalizedDigest.equals(normalizedPrior);
        }
        return false;
    }

    private String canonicalSql(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return sql
            .replace('`', ' ')
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
    }

    private Set<String> resolvedConversationTables(ResolvedConversationContext resolvedConversationContext) {
        if (resolvedConversationContext == null || resolvedConversationContext.resolvedContext() == null) {
            return Set.of();
        }
        Object tables = resolvedConversationContext.resolvedContext().get("tables");
        if (!(tables instanceof List<?> list) || list.isEmpty()) {
            return Set.of();
        }
        return list.stream()
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toSet());
    }

    private int requestedSlowQueryOrdinal(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return 1;
        }
        if (normalized.contains("#3") || normalized.contains("3rd") || normalized.contains("third")) {
            return 3;
        }
        if (normalized.contains("#2") || normalized.contains("2nd") || normalized.contains("second")) {
            return 2;
        }
        return 1;
    }

    private boolean asksForScanExplanation(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("scan")
            || normalized.contains("rows")
            || normalized.contains("causing")
            || normalized.contains("slowness")
            || normalized.contains("why ");
    }

    private String formatRowCount(Long value) {
        return value == null ? null : String.format(Locale.ROOT, "%,d", value);
    }

    private DraftPerformanceAnswer draft(
        String title,
        String stepTitle,
        String message,
        String answerType,
        EvidenceBundle.Source source,
        List<Map<String, Object>> rows,
        Map<String, Object> payload,
        Set<String> supportingObjectNames,
        List<String> supportingEvidence,
        double confidence
    ) {
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.PERFORMANCE,
            answerType,
            source,
            answerType,
            rows,
            payload,
            0.85,
            confidence,
            source == EvidenceBundle.Source.LIVE_METADATA ? "live_metadata" : "cached_metadata",
            null,
            supportingObjectNames
        );
        return new DraftPerformanceAnswer(title, stepTitle, message, evidence, supportingEvidence, List.of(), null, null);
    }

    private DraftPerformanceAnswer insufficiency(String title, String stepTitle, String message) {
        EvidenceBundle evidence = EvidenceBundle.insufficient(
            PromptIntent.Domain.PERFORMANCE,
            "insufficiency",
            EvidenceBundle.Source.LIVE_METADATA,
            "insufficiency",
            Map.of("reason", message),
            0.7,
            0.8,
            "mixed",
            Set.of(),
            message
        );
        return new DraftPerformanceAnswer(title, stepTitle, message, evidence, List.of("Cached recommendations", "Live performance advisor"), List.of(), null, null);
    }

    private String buildPendingRecommendationMessage(List<IndexRecommendationEntity> recommendations, String normalized) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("Found **%d pending** index recommendations for the current workload:\n\n", recommendations.size()));
        recommendations.stream().limit(10).forEach(rec -> sb.append(String.format(
            "- `%s` on `%s` — %s priority, %s affected queries%s\n",
            rec.getColumnNames(),
            rec.getTableName(),
            rec.getPriority() != null ? rec.getPriority().name().toLowerCase(Locale.ROOT) : "unknown",
            rec.getAffectedQueries() != null ? rec.getAffectedQueries() : 0,
            rec.getReason() != null && !normalized.contains("how many") ? " — " + rec.getReason() : ""
        )));
        IndexRecommendationEntity top = recommendations.getFirst();
        if (top.getCreateStatement() != null && !top.getCreateStatement().isBlank()) {
            sb.append("\nTop recommendation SQL:\n```sql\n").append(top.getCreateStatement()).append("\n```");
        }
        return sb.toString().trim();
    }

    private String buildIndexActionMessage(List<PerformanceAction> actions) {
        StringBuilder sb = new StringBuilder();
        sb.append("Index recommendations for the current workload, ranked from vault performance actions:\n\n");
        int rank = 1;
        for (PerformanceAction action : actions.stream().limit(10).toList()) {
            sb.append(rank++).append(". **`")
                .append(nonBlank(action.getTargetObject(), "unknown_table"));
            if (action.getTargetSecondary() != null && !action.getTargetSecondary().isBlank()) {
                sb.append(".").append(action.getTargetSecondary());
            }
            sb.append("`**\n");
            sb.append("   - Why: ")
                .append(nonBlank(action.getDescription(), nonBlank(action.getTitle(), "Vault action marks this as an index opportunity for the observed workload.")))
                .append("\n");
            if (action.getQueriesAffected() != null) {
                sb.append("   - Workload scope: affects about ").append(action.getQueriesAffected()).append(" observed query pattern(s).\n");
            }
            if (action.getSqlStatement() != null && !action.getSqlStatement().isBlank()) {
                sb.append("   - Candidate SQL: `").append(safeCap(action.getSqlStatement(), 160)).append("`\n");
            }
        }
        sb.append("\nUse these as review candidates, not automatic DDL: validate with EXPLAIN on the top workload queries before applying.");
        return sb.toString().trim();
    }

    private String buildLiveRecommendationMessage(List<IndexRecommendation> recommendations) {
        StringBuilder sb = new StringBuilder("Live advisor index candidates for the current workload:\n");
        recommendations.stream().limit(10).forEach(rec -> sb.append(String.format(
            "- `%s` on `%s` — %s%s\n",
            rec.getColumns() == null ? "" : String.join(", ", rec.getColumns()),
            rec.getTableName(),
            rec.getPriority() != null ? rec.getPriority().name().toLowerCase(Locale.ROOT) : "unknown",
            rec.getReasoning() != null ? " — " + rec.getReasoning() : ""
        )));
        IndexRecommendation top = recommendations.getFirst();
        if (top.getSuggestedSQL() != null && !top.getSuggestedSQL().isBlank()) {
            sb.append("\nTop recommendation SQL:\n```sql\n").append(top.getSuggestedSQL()).append("\n```");
        }
        return sb.toString().trim();
    }

    private String buildSlowQueryHealthMessage(SlowQueryHistory latest) {
        StringBuilder sb = new StringBuilder("### Query Performance Health\n\n");
        sb.append("| Metric | Value |\n");
        sb.append("|--------|-------|\n");
        sb.append(String.format("| Overall Health | %s |\n", latest.getOverallHealth() != null ? latest.getOverallHealth() : "UNKNOWN"));
        sb.append(String.format("| Total Slow Queries | %,d |\n", latest.getTotalSlowQueries() != null ? latest.getTotalSlowQueries() : 0));
        sb.append(String.format("| Critical Severity | %d |\n", latest.getCriticalCount() != null ? latest.getCriticalCount() : 0));
        sb.append(String.format("| High Severity | %d |\n", latest.getHighCount() != null ? latest.getHighCount() : 0));
        if (latest.getTotalDatabaseTimeMs() != null && latest.getTotalDatabaseTimeMs() > 0) {
            sb.append(String.format("| Total DB Time | %.1f sec |\n", latest.getTotalDatabaseTimeMs() / 1000.0));
        }
        return sb.toString().trim();
    }

    private String buildSlowestQueryMessage(SlowQuery slowest, SlowQueryHistory latest) {
        StringBuilder sb = new StringBuilder("### Your Slowest Query (from cached performance history)\n\n");
        double execTime = bestExecutionTime(slowest);
        sb.append(execTime >= 1000
            ? String.format("**Execution Time:** %.2f seconds\n\n", execTime / 1000.0)
            : String.format("**Execution Time:** %.0f ms\n\n", execTime));
        if (slowest.getSeverity() != null) {
            sb.append("**Severity:** ").append(slowest.getSeverity()).append("\n");
        }
        if (slowest.getAffectedTables() != null && !slowest.getAffectedTables().isEmpty()) {
            sb.append("**Tables:** ").append(String.join(", ", slowest.getAffectedTables())).append("\n");
        }
        String queryText = resolveSlowQuerySql(slowest);
        if (queryText != null && !queryText.isBlank()) {
            sb.append("\n**Query:**\n```sql\n").append(safeCap(queryText, 1000)).append("\n```");
        }
        sb.append("\n\n*Data analyzed at ").append(latest.getCreatedAt()).append("*");
        return sb.toString();
    }

    private String resolveSlowQuerySql(SlowQuery slowQuery) {
        if (slowQuery == null) {
            return null;
        }
        String sample = slowQuery.getSampleQuery();
        if (sample != null && !sample.isBlank()) {
            return sample.trim();
        }
        String raw = slowQuery.getQueryText();
        if (raw != null && !raw.isBlank()) {
            return raw.trim();
        }
        String normalized = slowQuery.getNormalizedQuery();
        return normalized == null || normalized.isBlank() ? null : normalized.trim();
    }

    private boolean looksLikePriorQueryDisplayFollowUp(String normalized, ResolvedConversationContext resolvedConversationContext) {
        if (normalized == null
            || normalized.isBlank()
            || resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()) {
            return false;
        }
        if (!asksForPriorQueryText(normalized)) {
            return false;
        }
        return resolvePriorQuerySql(resolvedConversationContext) != null
            || lower(resolvedConversationContext.anchorQuestion()).contains("slow query")
            || lower(resolvedConversationContext.chainSummary()).contains("slow query");
    }

    private boolean asksForPriorQueryText(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        if (normalized.contains("full query")
            || normalized.contains("full sql")
            || normalized.contains("query text")
            || normalized.contains("full text")
            || normalized.contains("sql text")) {
            return true;
        }
        if (normalized.matches(".*\\b(show|give|provide|return|share)\\b.*\\b(query|sql|statement|text)\\b.*")) {
            return true;
        }
        return mentionsSlowQueryOrdinal(normalized)
            && (normalized.contains("query")
                || normalized.contains("sql")
                || normalized.contains("statement")
                || normalized.contains("text"));
    }

    private boolean mentionsSlowQueryOrdinal(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("first query")
            || normalized.contains("1st query")
            || normalized.contains("#1")
            || normalized.contains("second query")
            || normalized.contains("2nd query")
            || normalized.contains("#2")
            || normalized.contains("third query")
            || normalized.contains("3rd query")
            || normalized.contains("#3");
    }

    private String resolvePriorQuerySql(ResolvedConversationContext resolvedConversationContext) {
        if (resolvedConversationContext == null) {
            return null;
        }
        if (resolvedConversationContext.sourceSql() != null && !resolvedConversationContext.sourceSql().isBlank()) {
            return resolvedConversationContext.sourceSql().trim();
        }
        List<AgentExecutionContext.ConversationTurn> history = resolvedConversationContext.conversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            AgentExecutionContext.ConversationTurn turn = history.get(i);
            if (turn == null || !"assistant".equalsIgnoreCase(turn.role()) || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String extracted = extractSqlFromAssistantContent(turn.content());
            if (extracted != null && !extracted.isBlank()) {
                return extracted;
            }
        }
        return null;
    }

    private String extractSqlFromAssistantContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        Matcher codeBlock = SQL_CODE_BLOCK_PATTERN.matcher(content);
        if (codeBlock.find()) {
            String sql = codeBlock.group(1);
            return sql == null || sql.isBlank() ? null : sql.trim();
        }
        Matcher statement = SQL_STATEMENT_PATTERN.matcher(content);
        if (statement.find()) {
            return statement.group().trim();
        }
        return null;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private double bestExecutionTime(SlowQuery query) {
        return contextAssembler.getBestExecutionTime(query);
    }

    private String mapWorkloadToOltpOlap(WorkloadProfile profile) {
        if (profile == null || profile.getWorkloadType() == null) {
            return "Not enough workload evidence yet to place this cleanly on an OLTP vs OLAP spectrum.";
        }
        return switch (profile.getWorkloadType()) {
            case OLTP, WRITE_HEAVY, REAL_TIME -> "Closest to **OLTP**: latency-sensitive transactional work dominates.";
            case OLAP, BATCH -> "Closest to **OLAP**: scan-heavy analytical work dominates.";
            case MIXED, READ_HEAVY -> "Closest to **mixed**: it blends OLTP-style request pressure with heavier read/scan behavior.";
            case UNKNOWN -> "Not enough workload evidence yet to classify this as OLTP, OLAP, or mixed.";
        };
    }

    private String safeCap(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, Math.max(0, maxLength - 3)) + "...";
    }

    private Map<String, Object> metadata(String key, Object value) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(key, value);
        return data;
    }

    private Map<String, Object> metadata(String keyOne, Object valueOne, String keyTwo, Object valueTwo) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put(keyOne, valueOne);
        data.put(keyTwo, valueTwo);
        return data;
    }

    private boolean looksLikePerformanceActionPrompt(String normalized, PromptIntent promptIntent) {
        return normalized.contains("roi")
            || normalized.contains("top performance actions")
            || normalized.matches(".*\\b(top|best|highest)\\b.*\\b(actions?|recommendations?)\\b.*\\b(roi|impact|value)\\b.*")
            || normalized.matches(".*\\b(actions?|recommendations?)\\b.*\\b(take|apply|prioritize)\\b.*\\b(now|first|right now)\\b.*")
            || normalized.matches(".*\\b(actions?|recommendations?)\\b.*\\b(performance|latency|slow query|bottleneck)\\b.*");
    }

    private boolean matchesRequestedTableScope(String tableName, String normalizedQuestion) {
        Optional<String> requestedTable = requestedTableLabel(normalizedQuestion);
        if (requestedTable.isEmpty()) {
            return true;
        }
        String normalizedTable = normalizeIdentifier(tableName);
        String normalizedRequested = normalizeIdentifier(requestedTable.get());
        return normalizedTable.equals(normalizedRequested)
            || normalizedTable.contains(normalizedRequested)
            || normalizedRequested.contains(normalizedTable);
    }

    private Optional<String> requestedTableLabel(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return Optional.empty();
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
            .compile("\\b(?:in|from|for|on)\\s+([a-z0-9_ ]+?)\\s+(?:table|tables)\\b")
            .matcher(normalizedQuestion);
        if (matcher.find()) {
            String label = matcher.group(1).trim().replaceAll("\\s+", "_");
            return label.isBlank() ? Optional.empty() : Optional.of(label);
        }
        return Optional.empty();
    }

    private String normalizeIdentifier(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replace('`', ' ')
            .replaceAll("[^a-z0-9]+", "_")
            .replaceAll("^_+|_+$", "");
    }

    private boolean looksLikeColumnImpactPrompt(String normalized, PromptIntent promptIntent) {
        boolean columnSignal = promptIntent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN)
            || normalized.matches(".*\\b(columns?|fields?)\\b.*");
        boolean queryPerformanceSignal = promptIntent.subjectTypes().contains(PromptIntent.SubjectType.QUERY)
            || normalized.matches(".*\\b(query|queries|performance|latency|slow|slowness|bottleneck|impact|impacting|causing)\\b.*");
        boolean notSchemaCatalog = !normalized.matches(".*\\b(what columns|list columns|show columns|columns are in)\\b.*");
        return columnSignal && queryPerformanceSignal && notSchemaCatalog && !looksLikeCardinalityPrompt(normalized);
    }

    private boolean looksLikeIndexRecommendationPrompt(String normalized, PromptIntent promptIntent) {
        return promptIntent.isIndexFocused()
            || normalized.contains("index")
            || normalized.contains("indexes")
            || normalized.contains("indices")
            || normalized.contains("indexing")
            || normalized.matches(".*\\b(columns?|tables?)\\b.*\\b(need|needs|should|urgent|urgently|required|missing)\\b.*\\bindex.*")
            || normalized.matches(".*\\bindex.*\\b(need|needs|should|recommend|urgent|urgently|required|missing|candidate)\\b.*");
    }

    private boolean prefersWorkloadRankedIndexActions(String normalized) {
        if (normalized == null || normalized.isBlank()) {
            return false;
        }
        return normalized.contains("roi")
            || normalized.contains("current workload")
            || normalized.contains("workload")
            || normalized.contains("urgent")
            || normalized.contains("urgently")
            || normalized.contains("priority")
            || normalized.contains("prioritize")
            || normalized.contains("impact")
            || normalized.contains("most queries")
            || normalized.contains("right now");
    }

    private boolean looksLikePerformanceChangePrompt(String normalized, PromptIntent promptIntent) {
        return normalized.matches(".*\\bwhat changed\\b.*\\b(performance|database)\\b.*")
            || normalized.matches(".*\\b(performance|database)\\b.*\\b(last|past)\\b.*\\b(hours?|days?)\\b.*")
            || normalized.matches(".*\\b(performance|health)\\b.*\\b(summary|status|trend|spike|spikes)\\b.*")
            || normalized.matches(".*\\b(change|changes|changed|delta|trend|trending)\\b.*\\b(performance|latency|cpu|connections?|queries?)\\b.*");
    }

    private boolean looksLikeRegressionPrompt(String normalized) {
        return normalized.contains("regression")
            || normalized.contains("regressions")
            || normalized.contains("regress")
            || normalized.contains("got worse")
            || normalized.contains("query plan");
    }

    private boolean looksLikeTuningPrompt(String normalized, PromptIntent promptIntent) {
        return promptIntent.subjectTypes().contains(PromptIntent.SubjectType.TUNING)
            || normalized.matches(".*\\b(config|configuration|knob|knobs|setting|settings|buffer pool|shared buffers?)\\b.*")
            || normalized.matches(".*\\b(reduc(e|ing)|lower|improv(e|ing))\\b.*\\b(latency|p99|response time)\\b.*");
    }

    private boolean looksLikeWorkloadPrompt(String normalized, PromptIntent promptIntent) {
        return promptIntent.subjectTypes().contains(PromptIntent.SubjectType.WORKLOAD)
            || normalized.matches(".*\\b(oltp|olap|mixed workload|mixed|workload type|workload profile|read-heavy|write-heavy)\\b.*");
    }

    private boolean looksLikeCardinalityPrompt(String normalized) {
        return normalized.matches(".*\\b(cardinality|statistics|selectivity|estimated rows|actual rows|plan quality|plan cost)\\b.*");
    }

    private boolean looksLikeActiveQueryPrompt(String normalized) {
        return normalized.matches(".*\\b(active queries|active query|queries)\\b.*\\b(pressure|waiting|wait|blocked|blocking)\\b.*");
    }

    private boolean looksLikeHotTablePrompt(String normalized) {
        return normalized.matches(".*\\b(hot|hottest|busy|busiest)\\b.*\\b(table|tables)\\b.*")
            || normalized.matches(".*\\b(table|tables)\\b.*\\b(used|usage|pressure)\\b.*");
    }

    private boolean looksLikeGrowthRiskPrompt(String normalized, PromptIntent promptIntent) {
        return promptIntent.subjectTypes().contains(PromptIntent.SubjectType.GROWTH)
            || normalized.matches(".*\\b(growth|capacity|risk|run out|exhaust|forecast|bloat)\\b.*");
    }

    private boolean looksLikeSlowQueryPrompt(String normalized, PromptIntent promptIntent) {
        if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.TUNING)
            || promptIntent.subjectTypes().contains(PromptIntent.SubjectType.WORKLOAD)) {
            return normalized.matches(".*\\b(slow query|slow queries|slowest|query health)\\b.*");
        }
        return normalized.matches(".*\\b(slow query|slow queries|slowest|performance health|query health|bottleneck)\\b.*")
            || normalized.matches(".*\\b(query|queries)\\b.*\\b(latency|slow|bottleneck|wait)\\b.*");
    }

    private int extractMonitoringWindowHours(String normalized) {
        java.util.regex.Matcher matcher = java.util.regex.Pattern.compile("(\\d+)\\s*(hour|hours|day|days)").matcher(normalized);
        if (!matcher.find()) {
            return 24;
        }
        int value = Integer.parseInt(matcher.group(1));
        String unit = matcher.group(2);
        return unit.startsWith("day") ? Math.max(1, value) * 24 : Math.max(1, value);
    }

    private void appendDeltaFinding(List<String> findings, String label, Double startValue, Double endValue, String unit) {
        if (startValue == null || endValue == null) {
            return;
        }
        double delta = endValue - startValue;
        double percent = Math.abs(startValue) < 0.0001d ? 0.0d : (delta / startValue) * 100.0d;
        if (Math.abs(percent) < 5.0d && Math.abs(delta) < 1.0d) {
            return;
        }
        String direction = delta > 0 ? "increased" : "decreased";
        findings.add(label + " " + direction + " from " + formatDouble(startValue) + unit + " to " + formatDouble(endValue) + unit
            + " (" + formatSigned(percent) + "%).");
    }

    private double peakDouble(List<PerformanceSnapshot> snapshots, java.util.function.Function<PerformanceSnapshot, Double> extractor) {
        return snapshots.stream()
            .map(extractor)
            .filter(Objects::nonNull)
            .mapToDouble(Double::doubleValue)
            .max()
            .orElse(0.0d);
    }

    private Double asDouble(Integer value) {
        return value == null ? null : value.doubleValue();
    }

    private String formatDouble(Double value) {
        if (value == null) {
            return "n/a";
        }
        return value == Math.rint(value)
            ? String.format(Locale.ROOT, "%.0f", value)
            : String.format(Locale.ROOT, "%.1f", value);
    }

    private String formatSigned(double value) {
        return String.format(Locale.ROOT, value >= 0 ? "+%.1f" : "%.1f", value);
    }

    private String nonBlank(String primary, String fallback) {
        return primary == null || primary.isBlank() ? fallback : primary;
    }

    private ColumnImpactAccumulator accumulator(
        Map<String, ColumnImpactAccumulator> ranked,
        String tableName,
        String columnName
    ) {
        String normalizedTable = nonBlank(tableName, "unknown_table").trim();
        String normalizedColumn = firstColumnName(columnName);
        String key = (normalizedTable + "." + normalizedColumn).toLowerCase(Locale.ROOT);
        return ranked.computeIfAbsent(key, ignored -> new ColumnImpactAccumulator(normalizedTable, normalizedColumn));
    }

    private String firstColumnName(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return "unknown_column";
        }
        String normalized = columnName
            .replace("[", "")
            .replace("]", "")
            .replace("\"", "")
            .replace("'", "")
            .trim();
        int commaIndex = normalized.indexOf(',');
        if (commaIndex > 0) {
            normalized = normalized.substring(0, commaIndex).trim();
        }
        return normalized.isBlank() ? "unknown_column" : normalized;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private record DraftPerformanceAnswer(
        String title,
        String stepTitle,
        String message,
        EvidenceBundle evidence,
        List<String> supportingEvidence,
        List<String> gapsOrCaveats,
        String followUpPrompt,
        String executedSql
    ) {
    }

    private static final class ColumnImpactAccumulator {
        private final String tableName;
        private final String columnName;
        private int joinCount;
        private int whereCount;
        private int groupByCount;
        private int orderByCount;
        private int totalUsageCount;
        private int slowQueryUsage;
        private int antiPatternCount;
        private int actionCount;
        private int recommendationCount;
        private double score;
        private final List<String> reasons = new java.util.ArrayList<>();
        private final List<String> actions = new java.util.ArrayList<>();

        private ColumnImpactAccumulator(String tableName, String columnName) {
            this.tableName = tableName;
            this.columnName = columnName;
        }

        private void addKeyColumn(KeyColumnAnalysis column) {
            if (column == null) {
                return;
            }
            joinCount += safeInt(column.getJoinCount());
            whereCount += safeInt(column.getWhereCount());
            groupByCount += safeInt(column.getGroupByCount());
            orderByCount += safeInt(column.getOrderByCount());
            totalUsageCount += safeInt(column.getTotalUsageCount());
            slowQueryUsage += safeInt(column.getSlowQueryUsage());
            score += asDouble(column.getEnhancedImportanceScore(), column.getImportanceScore());
            score += safeInt(column.getSlowQueryUsage()) * 0.8d;
            score += safeInt(column.getJoinCount()) * 0.45d;
            score += safeInt(column.getWhereCount()) * 0.35d;
            score += safeInt(column.getOrderByCount()) * 0.2d;
            if (Boolean.TRUE.equals(column.getHasAntiPatterns())) {
                score += Math.max(10, safeInt(column.getAntiPatternCount()) * 8);
                reasons.add("has " + safeInt(column.getAntiPatternCount()) + " detected anti-pattern(s)");
            }
            if (safeInt(column.getSlowQueryUsage()) > 0) {
                reasons.add("appears in " + column.getSlowQueryUsage() + " slow-query usage signal(s)");
            }
            if (safeInt(column.getJoinCount()) > 0 || safeInt(column.getWhereCount()) > 0) {
                reasons.add("used in JOIN/WHERE paths (joins " + safeInt(column.getJoinCount()) + ", filters " + safeInt(column.getWhereCount()) + ")");
            }
        }

        private void addAntiPattern(ColumnAntiPattern pattern) {
            if (pattern == null) {
                return;
            }
            antiPatternCount++;
            score += switch (pattern.getSeverity()) {
                case CRITICAL -> 90;
                case HIGH -> 65;
                case MEDIUM -> 35;
                case LOW -> 15;
            };
            if (pattern.getEstimatedImpactScore() != null) {
                score += pattern.getEstimatedImpactScore().doubleValue();
            }
            reasons.add(nonBlank(pattern.getTitle(), pattern.getPatternType()));
            if (pattern.getRecommendation() != null && !pattern.getRecommendation().isBlank()) {
                actions.add(pattern.getRecommendation());
            } else if (pattern.getSuggestedIndex() != null && !pattern.getSuggestedIndex().isBlank()) {
                actions.add("Consider " + pattern.getSuggestedIndex());
            }
        }

        private void addAction(PerformanceAction action) {
            if (action == null) {
                return;
            }
            actionCount++;
            score += action.getRoi() == null ? 0.0d : Math.min(100.0d, action.getRoi() / 2.0d);
            score += action.getImpactScore() == null ? 0.0d : action.getImpactScore();
            reasons.add(nonBlank(action.getTitle(), String.valueOf(action.getSource())));
            if (action.getSqlStatement() != null && !action.getSqlStatement().isBlank()) {
                actions.add("Review cached implementation SQL: `" + safeCapStatic(action.getSqlStatement(), 140) + "`");
            } else if (action.getDescription() != null && !action.getDescription().isBlank()) {
                actions.add(action.getDescription());
            }
        }

        private void addIndexRecommendation(IndexRecommendationEntity recommendation) {
            if (recommendation == null) {
                return;
            }
            recommendationCount++;
            score += indexPriorityScore(recommendation.getPriority());
            reasons.add("has a pending " + recommendation.getPriority() + " index recommendation");
            if (recommendation.getCreateStatement() != null && !recommendation.getCreateStatement().isBlank()) {
                actions.add("Review pending index SQL: `" + safeCapStatic(recommendation.getCreateStatement(), 140) + "`");
            }
        }

        private void addCompositeRecommendation(CompositeIndexRecommendation recommendation) {
            if (recommendation == null) {
                return;
            }
            recommendationCount++;
            if (recommendation.getEstimatedBenefitScore() != null) {
                score += recommendation.getEstimatedBenefitScore().doubleValue();
            }
            score += compositePriorityScore(recommendation.getPriority());
            reasons.add("part of a pending composite-index recommendation");
            if (recommendation.getSuggestedIndexSql() != null && !recommendation.getSuggestedIndexSql().isBlank()) {
                actions.add("Review composite index SQL: `" + safeCapStatic(recommendation.getSuggestedIndexSql(), 140) + "`");
            }
        }

        private boolean hasColumn() {
            return columnName != null && !columnName.isBlank() && !"unknown_column".equals(columnName);
        }

        private String reason() {
            return reasons.stream().distinct().limit(3).collect(Collectors.joining("; "));
        }

        private String displayName() {
            return tableName + "." + columnName;
        }

        private static int safeInt(Integer value) {
            return value == null ? 0 : value;
        }

        private static double asDouble(BigDecimal primary, BigDecimal fallback) {
            BigDecimal value = primary != null ? primary : fallback;
            return value == null ? 0.0d : value.doubleValue();
        }

        private static String nonBlank(String primary, String fallback) {
            return primary == null || primary.isBlank() ? fallback : primary;
        }

        private static String safeCapStatic(String value, int maxLength) {
            if (value == null || value.length() <= maxLength) {
                return value;
            }
            return value.substring(0, Math.max(0, maxLength - 3)) + "...";
        }

        private static double indexPriorityScore(IndexRecommendationEntity.Priority priority) {
            if (priority == null) {
                return 20;
            }
            return switch (priority) {
                case HIGH -> 55;
                case MEDIUM -> 30;
                case LOW -> 12;
            };
        }

        private static double compositePriorityScore(CompositeIndexRecommendation.Priority priority) {
            if (priority == null) {
                return 20;
            }
            return switch (priority) {
                case CRITICAL -> 70;
                case HIGH -> 45;
                case MEDIUM -> 25;
                case LOW -> 10;
            };
        }

        private String tableName() {
            return tableName;
        }

        private String columnName() {
            return columnName;
        }

        private int joinCount() {
            return joinCount;
        }

        private int whereCount() {
            return whereCount;
        }

        private int orderByCount() {
            return orderByCount;
        }

        private int slowQueryUsage() {
            return slowQueryUsage;
        }

        private int antiPatternCount() {
            return antiPatternCount;
        }

        private int actionCount() {
            return actionCount;
        }

        private int recommendationCount() {
            return recommendationCount;
        }

        private double score() {
            return score;
        }

        private List<String> actions() {
            return actions.stream().filter(value -> value != null && !value.isBlank()).distinct().toList();
        }
    }
}
