package com.dbaagent.service.agent;

import com.dbaagent.model.PerformanceAction;
import com.dbaagent.model.PerformanceSnapshot;
import com.dbaagent.model.ActiveQuery;
import com.dbaagent.model.CapacityForecast;
import com.dbaagent.model.ColumnAntiPattern;
import com.dbaagent.model.CompositeIndexRecommendation;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.SentinelRecommendation;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceExecutorTest {

    @Mock private IndexRecommendationRepository indexRecommendationRepository;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private ColumnAntiPatternRepository columnAntiPatternRepository;
    @Mock private PerformanceActionRepository performanceActionRepository;
    @Mock private CompositeIndexRecommendationRepository compositeIndexRecommendationRepository;
    @Mock private SlowQueryHistoryRepository slowQueryHistoryRepository;
    @Mock private PerformanceActionAggregatorService performanceActionAggregatorService;
    @Mock private PerformanceSnapshotRepository performanceSnapshotRepository;
    @Mock private QueryPerformanceRegressionRepository queryPerformanceRegressionRepository;
    @Mock private WorkloadProfileRepository workloadProfileRepository;
    @Mock private KnobRankingRepository knobRankingRepository;
    @Mock private ColumnStatisticsRepository columnStatisticsRepository;
    @Mock private PlanExecutionRepository planExecutionRepository;
    @Mock private ActiveQueryRepository activeQueryRepository;
    @Mock private ActiveQueryService activeQueryService;
    @Mock private PerformanceInsightsService performanceInsightsService;
    @Mock private CapacityForecastRepository capacityForecastRepository;
    @Mock private SentinelRecommendationRepository sentinelRecommendationRepository;
    @Mock private GrowthAnomalyRepository growthAnomalyRepository;
    @Mock private DatabaseAdvisorService databaseAdvisorService;
    @Mock private IndexAdvisorService indexAdvisorService;
    @Mock private ChatContextAssembler contextAssembler;

    private PerformanceExecutor performanceExecutor;

    @BeforeEach
    void setUp() {
        performanceExecutor = new PerformanceExecutor(
            indexRecommendationRepository,
            keyColumnAnalysisRepository,
            columnAntiPatternRepository,
            performanceActionRepository,
            compositeIndexRecommendationRepository,
            slowQueryHistoryRepository,
            performanceActionAggregatorService,
            performanceSnapshotRepository,
            queryPerformanceRegressionRepository,
            workloadProfileRepository,
            knobRankingRepository,
            columnStatisticsRepository,
            planExecutionRepository,
            activeQueryRepository,
            activeQueryService,
            performanceInsightsService,
            capacityForecastRepository,
            sentinelRecommendationRepository,
            growthAnomalyRepository,
            databaseAdvisorService,
            indexAdvisorService,
            contextAssembler,
            new ObjectMapper(),
            new AnswerVerificationService()
        );
    }

    @Test
    void execute_topPerformanceActionsPrompt_returnsRankedNarrative() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.RECOMMEND,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.RANKING,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(performanceActionAggregatorService.getTopActions("conn-1", 5)).thenReturn(List.of(
            PerformanceAction.builder()
                .title("Add composite index for CUSTOMER_ORDERS lookups")
                .targetObject("CUSTOMER_ORDERS")
                .category(PerformanceAction.ActionCategory.INDEX)
                .source(PerformanceAction.ActionSource.INDEX_ADVISOR)
                .impactScore(92)
                .effortScore(20)
                .roi(460.0)
                .description("Frequent booking lookups scan too many rows.")
                .queriesAffected(18L)
                .sqlStatement("CREATE INDEX idx_customer_orders_status_created_at ON CUSTOMER_ORDERS(status, created_at)")
                .build()
        ));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "What are the top performance actions I should take right now, ranked by ROI?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertTrue(result.get().renderedMessage().contains("Top performance actions to take right now"));
        assertTrue(result.get().renderedMessage().contains("ranked by expected benefit"));
        assertFalse(result.get().renderedMessage().contains("ROI **"));
        assertFalse(result.get().renderedMessage().contains("impact 92/100"));
        assertFalse(result.get().renderedMessage().contains("effort 20/100"));
        assertTrue(result.get().renderedMessage().contains("CUSTOMER_ORDERS"));
        assertEquals("performance_action_recommendations", result.get().evidence().answerType());
    }

    @Test
    void execute_performanceChangePrompt_returnsSummaryInsteadOfGenericMetadata() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        LocalDateTime now = LocalDateTime.now();
        when(performanceSnapshotRepository.findByConnectionIdAndSnapshotTimeBetweenOrderBySnapshotTimeAsc(
            org.mockito.ArgumentMatchers.eq("conn-1"),
            org.mockito.ArgumentMatchers.any(),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(
            PerformanceSnapshot.builder()
                .connectionId("conn-1")
                .snapshotTime(now.minusHours(24))
                .totalDbTimeMs(1200.0)
                .activeConnections(12)
                .cpuPercent(38.0)
                .queriesPerSecond(110.0)
                .lockWaitMs(8.0)
                .build(),
            PerformanceSnapshot.builder()
                .connectionId("conn-1")
                .snapshotTime(now)
                .totalDbTimeMs(2100.0)
                .activeConnections(19)
                .cpuPercent(76.0)
                .queriesPerSecond(145.0)
                .lockWaitMs(32.0)
                .build()
        ));
        when(queryPerformanceRegressionRepository.findByConnectionIdAndResolvedFalseOrderByDetectedAtDesc("conn-1"))
            .thenReturn(List.of());
        when(performanceActionAggregatorService.getTopActions("conn-1", 3)).thenReturn(List.of());

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "What changed in database performance over the last 24 hours?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertTrue(result.get().renderedMessage().contains("last 24 hours"));
        assertTrue(result.get().renderedMessage().contains("Key findings"));
        assertEquals("performance_change_summary", result.get().evidence().answerType());
    }

    @Test
    void execute_tuningPrompt_returnsKnobRankingsInsteadOfSlowQueryFallback() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.LOOKUP,
            Set.of(PromptIntent.SubjectType.TUNING, PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(workloadProfileRepository.findByConnectionId("conn-1")).thenReturn(Optional.of(
            WorkloadProfile.builder()
                .connectionId("conn-1")
                .workloadType(WorkloadProfile.WorkloadType.OLTP)
                .workloadSubtype("checkout-heavy")
                .classificationConfidence(91.0)
                .classificationReasoning("High concurrency with short write-heavy requests.")
                .latencyP99Ms(84.0)
                .throughputQps(310.0)
                .build()
        ));
        when(knobRankingRepository.findTopKnobs(
            org.mockito.ArgumentMatchers.eq("conn-1"),
            org.mockito.ArgumentMatchers.eq(KnobRanking.TargetMetric.LATENCY),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(
            KnobRanking.builder()
                .connectionId("conn-1")
                .knobName("innodb_buffer_pool_size")
                .rank(1)
                .impactScore(0.93)
                .confidenceScore(0.88)
                .currentValue("8GB")
                .defaultValue("128MB")
                .minValue("128MB")
                .maxValue("64GB")
                .requiresRestart(true)
                .sampleCount(42)
                .targetMetric(KnobRanking.TargetMetric.LATENCY)
                .build()
        ));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "What config knobs matter most for reducing latency on this database?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("tuning_knob_rankings", result.get().evidence().answerType());
        assertTrue(result.get().renderedMessage().toLowerCase().contains("config knobs"));
        assertTrue(result.get().renderedMessage().contains("innodb_buffer_pool_size"));
        assertFalse(result.get().renderedMessage().contains("Your Slowest Query"));
    }

    @Test
    void execute_cardinalityPrompt_returnsCardinalityFindings() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.LOOKUP,
            Set.of(PromptIntent.SubjectType.QUERY, PromptIntent.SubjectType.COLUMN),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(planExecutionRepository.countWithCardinalityData("conn-1")).thenReturn(12L);
        when(planExecutionRepository.calculateAverageCardinalityError("conn-1")).thenReturn(4.6);
        when(planExecutionRepository.countOverestimates("conn-1")).thenReturn(3L);
        when(planExecutionRepository.countUnderestimates("conn-1")).thenReturn(5L);
        when(planExecutionRepository.countAccurateEstimates("conn-1")).thenReturn(4L);
        when(planExecutionRepository.findWithSignificantCardinalityError(
            org.mockito.ArgumentMatchers.eq("conn-1"),
            org.mockito.ArgumentMatchers.any()
        )).thenReturn(List.of(
            PlanExecution.builder()
                .connectionId("conn-1")
                .queryFingerprint("fp-1")
                .normalizedQuery("select * from bookings where customer_id = ? and created_at >= ?")
                .estimatedRows(120L)
                .actualRows(12400L)
                .cardinalityErrorRatio(103.3)
                .actualExecutionMs(842.0)
                .build()
        ));
        when(columnStatisticsRepository.countByConnectionId("conn-1")).thenReturn(30L);
        when(columnStatisticsRepository.findHighCardinalityColumns("conn-1", 1000L)).thenReturn(List.of());

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "Where are our statistics or cardinality estimates hurting plan quality?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("cardinality_accuracy_summary", result.get().evidence().answerType());
        assertTrue(result.get().renderedMessage().contains("cardinality"));
        assertTrue(result.get().renderedMessage().contains("estimate drift"));
    }

    @Test
    void execute_activeQueryPrompt_returnsWaitingAnalysis() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(activeQueryRepository.findLatestSnapshot("conn-1")).thenReturn(List.of(
            ActiveQuery.builder()
                .queryText("select * from customer_orders where status = 'PENDING'")
                .state("active")
                .waitEventType("Lock")
                .waitEvent("row lock")
                .durationSeconds(92L)
                .priority(ActiveQuery.Priority.CRITICAL)
                .isBlocked(true)
                .build()
        ));
        when(activeQueryService.getStatistics("conn-1")).thenReturn(Map.of(
            "blocked", 1,
            "longRunning", 1,
            "avgDuration", 92.0
        ));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "Which active queries are causing pressure right now, and what are they waiting on?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("active_query_pressure", result.get().evidence().answerType());
        assertTrue(result.get().renderedMessage().contains("waiting on"));
    }

    @Test
    void execute_hotTablePrompt_returnsUsageNarrative() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.TABLE),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(performanceInsightsService.getTableUsage("conn-1")).thenReturn(List.of(
            TableUsageDTO.builder()
                .tableName("analytics_db.CUSTOMER_ORDERS")
                .usageScore(91)
                .rowsRead(120000L)
                .rowsWritten(1200L)
                .seqScans(240L)
                .idxScans(880L)
                .slowQueryCount(4)
                .build()
        ));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "Which tables are hottest right now, and how are they being used?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("table_usage_heatmap", result.get().evidence().answerType());
        assertTrue(result.get().renderedMessage().contains("CUSTOMER_ORDERS"));
    }

    @Test
    void execute_growthRiskPrompt_returnsForecastNarrative() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.GROWTH),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(capacityForecastRepository.findCriticalForecasts("conn-1")).thenReturn(List.of(
            CapacityForecast.builder()
                .connectionId("conn-1")
                .tableName("CM_LOGS_NEW")
                .riskScore(9)
                .confidenceScore(8)
                .storageExhaustionDate(java.time.LocalDateTime.now().plusDays(14))
                .growthPattern(CapacityForecast.GrowthPattern.EXPONENTIAL)
                .build()
        ));
        when(growthAnomalyRepository.findRecentAnomalies(org.mockito.ArgumentMatchers.eq("conn-1"), org.mockito.ArgumentMatchers.any()))
            .thenReturn(List.of(
                GrowthAnomaly.builder()
                    .tableName("CM_LOGS_NEW")
                    .anomalyType(GrowthAnomaly.AnomalyType.ROW_SPIKE)
                    .severity(GrowthAnomaly.Severity.CRITICAL)
                    .build()
            ));
        when(sentinelRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
            "conn-1",
            SentinelRecommendation.Status.PENDING
        )).thenReturn(List.of(
            SentinelRecommendation.builder()
                .title("Partition CM_LOGS_NEW")
                .priority(SentinelRecommendation.Priority.IMMEDIATE)
                .build()
        ));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "Which tables are on a risky growth path, and what might run out first?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("growth_risk_forecast", result.get().evidence().answerType());
        assertTrue(result.get().renderedMessage().contains("risk"));
    }

    @Test
    void execute_slowestQueryPrompt_persistsFullQuerySqlForFollowUps() throws Exception {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        SlowQuery slowQuery = SlowQuery.builder()
            .queryText("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = ? ORDER BY created_at DESC")
            .sampleQuery("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC")
            .normalizedQuery("select * from customer_orders where customer_id = ? order by created_at desc")
            .build();
        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTopSlowQueries(List.of(slowQuery));
        SlowQueryHistory history = SlowQueryHistory.builder()
            .connectionId("conn-1")
            .analysisData(new ObjectMapper().writeValueAsString(analysis))
            .createdAt(LocalDateTime.now())
            .build();
        when(slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc("conn-1"))
            .thenReturn(Optional.of(history));
        when(contextAssembler.getBestExecutionTime(org.mockito.ArgumentMatchers.any())).thenReturn(123.0);

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "What is the #1 slow query?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC", result.get().answerContract().executedSql());
        assertEquals("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC", result.get().evidence().sourceQuery());
    }

    @Test
    void execute_topSlowQueriesRightNow_prefersFreshPerformanceSnapshotWhenSlowHistoryIsStale() throws Exception {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.RANKING,
            Map.of(),
            false,
            true,
            true,
            false
        );
        SlowQuery staleQuery = SlowQuery.builder()
            .queryText("SELECT * FROM OLD_REPORTING_QUERY")
            .avgExecutionTimeMs(99999.0)
            .build();
        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTopSlowQueries(List.of(staleQuery));
        SlowQueryHistory staleHistory = SlowQueryHistory.builder()
            .connectionId("conn-1")
            .analysisData(new ObjectMapper().writeValueAsString(analysis))
            .createdAt(LocalDateTime.now().minusDays(7))
            .build();
        when(slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc("conn-1"))
            .thenReturn(Optional.of(staleHistory));
        when(performanceSnapshotRepository.findFirstByConnectionIdOrderBySnapshotTimeDesc("conn-1"))
            .thenReturn(PerformanceSnapshot.builder()
                .connectionId("conn-1")
                .snapshotTime(LocalDateTime.now().minusMinutes(5))
                .topQueries("""
                    [
                      {"queryText":"INSERT INTO revenue_by_booking_source_aggregation VALUES (...)","totalTime":3528254738.02,"avgTime":5.59,"callCount":630684769},
                      {"queryText":"SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = ?","totalTime":764661036.81,"avgTime":209.18,"callCount":3655480}
                    ]
                    """)
                .build());

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "What are the top 5 slow queries right now?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertTrue(result.get().verificationReport().passed());
        assertEquals(EvidenceBundle.Source.PERFORMANCE_VAULT, result.get().evidence().source());
        assertTrue(result.get().renderedMessage().contains("Freshness note"));
        assertTrue(result.get().renderedMessage().contains("revenue_by_booking_source_aggregation"));
        assertFalse(result.get().renderedMessage().contains("OLD_REPORTING_QUERY"));
    }

    @Test
    void execute_fullQueryFollowUp_firstQueryPrefersVaultSampleQueryOverDigest() throws Exception {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.LOOKUP,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        String digestSql = "SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = ? ORDER BY created_at DESC";
        String sampleSql = "SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC";
        ResolvedConversationContext context = new ResolvedConversationContext(
            "ctx-1",
            "BRAIN_METADATA",
            "COMPLETED",
            "what are the top 3 slow queries?",
            "Previous answer ranked the slow queries.",
            Map.of("tables", List.of("CUSTOMER_ORDERS")),
            List.of(),
            Map.of(),
            digestSql,
            List.of(),
            0.91
        );
        SlowQuery slowQuery = SlowQuery.builder()
            .queryText(digestSql)
            .sampleQuery(sampleSql)
            .normalizedQuery("select * from customer_orders where customer_id = ? order by created_at desc")
            .rowsExamined(75299861L)
            .rowsSent(20885L)
            .build();
        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTopSlowQueries(List.of(slowQuery));
        SlowQueryHistory history = SlowQueryHistory.builder()
            .connectionId("conn-1")
            .analysisData(new ObjectMapper().writeValueAsString(analysis))
            .createdAt(LocalDateTime.now())
            .build();
        when(slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc("conn-1"))
            .thenReturn(Optional.of(history), Optional.of(history));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "can you give me the full text for the first query?",
            "conn-1",
            null,
            context
        );

        assertTrue(result.isPresent());
        assertEquals(sampleSql, result.get().answerContract().executedSql());
        assertTrue(result.get().renderedMessage().contains(sampleSql));
        assertFalse(result.get().renderedMessage().contains(digestSql + "\n```"));
    }

    @Test
    void execute_fullQueryFollowUp_reusesVaultConversationContextInsteadOfLiveMetadata() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.LOOKUP,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        String sql = "SELECT digest_text FROM slow_query_cache WHERE severity = 'CRITICAL'";
        ResolvedConversationContext context = new ResolvedConversationContext(
            "ctx-1",
            "BRAIN_METADATA",
            "COMPLETED",
            "what is the #1 slow query?",
            "Previous answer identified the top slow query.",
            Map.of(),
            List.of(),
            Map.of(),
            null,
            List.of(new AgentExecutionContext.ConversationTurn(
                "assistant",
                "### Your Slowest Query\n\n**Query:**\n```sql\n" + sql + "\n```"
            )),
            0.93
        );

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "show me this full query",
            "conn-1",
            null,
            context
        );

        assertTrue(result.isPresent());
        assertEquals("slow_query_detail", result.get().evidence().answerType());
        assertEquals(sql, result.get().answerContract().executedSql());
        assertTrue(result.get().renderedMessage().contains(sql));
    }

    @Test
    void execute_fullQueryAndScanFollowUp_reusesPriorQueryAndExplainsScanCause() throws Exception {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.MONITOR,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            true,
            true,
            false
        );
        String sql = "SELECT * FROM CM_LOGS_NEW WHERE customer_id = 42 ORDER BY update_time DESC";
        ResolvedConversationContext context = new ResolvedConversationContext(
            "ctx-1",
            "BRAIN_METADATA",
            "COMPLETED",
            "what are the top 3 slow queries? what is causing the slowness?",
            "Previous answer ranked the top slow queries and identified CM_LOGS_NEW as the first one.",
            Map.of("tables", List.of("CM_LOGS_NEW")),
            List.of(),
            Map.of(),
            sql,
            List.of(),
            0.97
        );
        SlowQuery matchedSlowQuery = SlowQuery.builder()
            .queryText(sql)
            .avgExecutionTimeMs(123250.0)
            .rowsExamined(75299861L)
            .rowsSent(20885L)
            .hasIndex(false)
            .affectedTables(List.of("CM_LOGS_NEW"))
            .suggestions(List.of("Add an index that supports customer_id and the update_time ordering."))
            .build();
        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTopSlowQueries(List.of(matchedSlowQuery));
        SlowQueryHistory history = SlowQueryHistory.builder()
            .connectionId("conn-1")
            .analysisData(new ObjectMapper().writeValueAsString(analysis))
            .createdAt(LocalDateTime.now())
            .build();
        when(slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc("conn-1"))
            .thenReturn(Optional.of(history));

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "show me the full query for the first one and explain why it is scanning so many rows",
            "conn-1",
            null,
            context
        );

        assertTrue(result.isPresent());
        assertTrue(result.get().verificationReport().passed());
        assertEquals(sql, result.get().answerContract().executedSql());
        assertTrue(result.get().renderedMessage().contains("Why it is scanning so many rows"));
        assertTrue(result.get().renderedMessage().contains("rows examined vs"));
        assertTrue(result.get().renderedMessage().contains("no supporting index"));
        assertEquals(Set.of("CM_LOGS_NEW"), result.get().evidence().supportingObjectNames());
    }

    @Test
    void execute_topSlowQueriesPrompt_persistsFirstRankedQueryForFollowUps() throws Exception {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.COMPARE,
            Set.of(PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.RANKING,
            Map.of(),
            false,
            true,
            true,
            false
        );
        SlowQuery first = SlowQuery.builder()
            .queryText("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC")
            .normalizedQuery("select * from customer_orders where customer_id = ? order by created_at desc")
            .avgExecutionTimeMs(123250.0)
            .rowsExamined(75299861L)
            .rowsSent(20885L)
            .build();
        SlowQuery second = SlowQuery.builder()
            .queryText("SELECT payment_type, SUM(td_amount) FROM PAYMENT_LEDGER GROUP BY payment_type")
            .normalizedQuery("select payment_type, sum(td_amount) from account_ledger group by payment_type")
            .avgExecutionTimeMs(33110.0)
            .rowsExamined(2L)
            .rowsSent(1L)
            .build();
        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTopSlowQueries(List.of(first, second));
        SlowQueryHistory history = SlowQueryHistory.builder()
            .connectionId("conn-1")
            .analysisData(new ObjectMapper().writeValueAsString(analysis))
            .createdAt(LocalDateTime.now())
            .build();
        when(slowQueryHistoryRepository.findFirstByConnectionIdOrderByCreatedAtDesc("conn-1"))
            .thenReturn(Optional.of(history));
        when(contextAssembler.getBestExecutionTime(org.mockito.ArgumentMatchers.any()))
            .thenAnswer(invocation -> ((SlowQuery) invocation.getArgument(0)).getAvgExecutionTimeMs());

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "what are the top 3 slow queries? what is causing the slowness?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertEquals("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC", result.get().answerContract().executedSql());
        assertEquals("SELECT * FROM CUSTOMER_ORDERS WHERE customer_id = 42 ORDER BY created_at DESC", result.get().evidence().sourceQuery());
        assertTrue(result.get().renderedMessage().contains("Top 2 slow queries right now"));
    }

    @Test
    void execute_columnPerformancePrompt_scoutsVaultColumnSources() {
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.PERFORMANCE,
            PromptIntent.TaskType.TROUBLESHOOT,
            Set.of(PromptIntent.SubjectType.COLUMN, PromptIntent.SubjectType.QUERY),
            PromptIntent.RequestedOutput.RANKING,
            Map.of(),
            false,
            true,
            true,
            false
        );
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("conn-1"))
            .thenReturn(List.of(KeyColumnAnalysis.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMER_ORDERS")
                .columnName("customer_id")
                .importanceScore(BigDecimal.valueOf(95))
                .enhancedImportanceScore(BigDecimal.valueOf(122))
                .joinCount(94)
                .whereCount(494)
                .orderByCount(0)
                .slowQueryUsage(563)
                .totalUsageCount(629)
                .hasAntiPatterns(true)
                .antiPatternCount(1)
                .analyzedAt(LocalDateTime.now())
                .build()));
        when(columnAntiPatternRepository.findByConnectionIdAndStatusOrderBySeverityDescDetectedAtDesc("conn-1", ColumnAntiPattern.Status.ACTIVE))
            .thenReturn(List.of(ColumnAntiPattern.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMER_ORDERS")
                .columnName("customer_id")
                .patternType("UNINDEXED_FILTER")
                .severity(ColumnAntiPattern.Severity.CRITICAL)
                .title("Unindexed filter column")
                .description("customer_id appears in high-pressure predicates.")
                .recommendation("Create or validate a selective index on CUSTOMER_ORDERS.customer_id.")
                .detectedAt(LocalDateTime.now())
                .createdAt(LocalDateTime.now())
                .build()));
        when(performanceActionRepository.findByConnectionIdAndCategoryAndStatusOrderByRoiDesc(
            "conn-1",
            PerformanceAction.ActionCategory.INDEX,
            PerformanceAction.ActionStatus.PENDING
        )).thenReturn(List.of(PerformanceAction.builder()
            .connectionId("conn-1")
            .category(PerformanceAction.ActionCategory.INDEX)
            .source(PerformanceAction.ActionSource.KEY_COLUMN_ANALYSIS)
            .status(PerformanceAction.ActionStatus.PENDING)
            .title("Add index for CUSTOMER_ORDERS.customer_id")
            .targetObject("CUSTOMER_ORDERS")
            .targetSecondary("customer_id")
            .impactScore(90)
            .effortScore(15)
            .roi(600.0)
            .description("High filter and join pressure.")
            .build()));
        when(indexRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
            "conn-1",
            com.dbaagent.model.IndexRecommendationEntity.Status.PENDING
        )).thenReturn(List.of());
        when(compositeIndexRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAsc(
            "conn-1",
            CompositeIndexRecommendation.Status.PENDING
        )).thenReturn(List.of());

        Optional<VerifiedAnswer> result = performanceExecutor.execute(
            promptIntent,
            "Which columns are impacting query performance?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );
        Optional<VerifiedAnswer> articleVariant = performanceExecutor.execute(
            promptIntent,
            "Which columns are impacting the query performance?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );
        Optional<VerifiedAnswer> tableScopedVariant = performanceExecutor.execute(
            promptIntent,
            "what are the most impactful columns in customer orders table?",
            "conn-1",
            null,
            ResolvedConversationContext.empty()
        );

        assertTrue(result.isPresent());
        assertTrue(result.get().verificationReport().passed());
        assertEquals(EvidenceBundle.Source.PERFORMANCE_VAULT, result.get().evidence().source());
        assertTrue(result.get().renderedMessage().contains("CUSTOMER_ORDERS.customer_id"));
        assertTrue(result.get().renderedMessage().contains("key-column"));
        assertTrue(result.get().renderedMessage().contains("anti-pattern"));
        assertTrue(articleVariant.isPresent());
        assertTrue(articleVariant.get().verificationReport().passed());
        assertEquals(result.get().evidence().source(), articleVariant.get().evidence().source());
        assertTrue(articleVariant.get().renderedMessage().contains("CUSTOMER_ORDERS.customer_id"));
        assertTrue(tableScopedVariant.isPresent());
        assertTrue(tableScopedVariant.get().verificationReport().passed());
        assertTrue(tableScopedVariant.get().renderedMessage().contains("CUSTOMER_ORDERS.customer_id"));
        assertFalse(tableScopedVariant.get().renderedMessage().contains("Table `CUSTOMER_ORDERS` has"));
    }
}
