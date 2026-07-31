package com.dbaagent.service;

import com.dbaagent.model.ChatResponse;
import com.dbaagent.model.ChatResultSet;
import com.dbaagent.model.ChatMessage;
import com.dbaagent.model.AgentRun;
import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.IndexMetadata;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaClassification;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.PerformanceAnalysis;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.ActiveQueryRepository;
import com.dbaagent.repository.CapacityForecastRepository;
import com.dbaagent.repository.ColumnAntiPatternRepository;
import com.dbaagent.repository.CompositeIndexRecommendationRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.PerformanceActionRepository;
import com.dbaagent.repository.QueryPerformanceRegressionRepository;
import com.dbaagent.repository.PerformanceSnapshotRepository;
import com.dbaagent.repository.SentinelRecommendationRepository;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.repository.brain.ColumnStatisticsRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.repository.brain.PlanExecutionRepository;
import com.dbaagent.repository.brain.PlanPatternRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.ActiveQueryService;
import com.dbaagent.service.agent.AgentExecutionResult;
import com.dbaagent.service.agent.AgentIntent;
import com.dbaagent.service.agent.AgentOrchestrator;
import com.dbaagent.service.agent.AgentRunService;
import com.dbaagent.service.agent.AgentDecision;
import com.dbaagent.service.agent.AgentTaskKind;
import com.dbaagent.service.agent.AgentTaskResult;
import com.dbaagent.service.agent.AnswerVerificationService;
import com.dbaagent.service.agent.MetadataRequestScopeResolver;
import com.dbaagent.service.agent.MetadataExplanationService;
import com.dbaagent.service.agent.PerformanceExecutor;
import com.dbaagent.service.agent.PromptIntent;
import com.dbaagent.service.agent.PromptIntentAnalyzer;
import com.dbaagent.service.agent.SchemaMetadataExecutor;
import com.dbaagent.service.pipeline.QueryGenerationPipeline;
import com.dbaagent.service.PerformanceInsightsService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ChatServiceRoutingTest {

    @Mock private SchemaScannerService schemaScannerService;
    @Mock private QueryExecutorService queryExecutorService;
    @Mock private TrainingService trainingService;
    @Mock private ChatHistoryService chatHistoryService;
    @Mock private SchemaClassificationService schemaClassificationService;
    @Mock private FeedbackService feedbackService;
    @Mock private BusinessRuleMemoryService businessRuleMemoryService;
    @Mock private CredentialService credentialService;
    @Mock private TableClassificationRepository tableClassificationRepository;
    @Mock private SlowQueryHistoryRepository slowQueryHistoryRepository;
    @Mock private QueryPerformanceRegressionRepository queryPerformanceRegressionRepository;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private ColumnAntiPatternRepository columnAntiPatternRepository;
    @Mock private PerformanceActionRepository performanceActionRepository;
    @Mock private CompositeIndexRecommendationRepository compositeIndexRecommendationRepository;
    @Mock private InferredTableRelationshipRepository inferredTableRelationshipRepository;
    @Mock private GrowthAnomalyRepository growthAnomalyRepository;
    @Mock private IndexRecommendationRepository indexRecommendationRepository;
    @Mock private TableStatsHistoryRepository tableStatsHistoryRepository;
    @Mock private ColumnValueCacheRepository columnValueCacheRepository;
    @Mock private ActiveQueryRepository activeQueryRepository;
    @Mock private ActiveQueryService activeQueryService;
    @Mock private PerformanceInsightsService performanceInsightsService;
    @Mock private CapacityForecastRepository capacityForecastRepository;
    @Mock private SentinelRecommendationRepository sentinelRecommendationRepository;
    @Mock private WorkloadProfileRepository workloadProfileRepository;
    @Mock private KnobRankingRepository knobRankingRepository;
    @Mock private ColumnStatisticsRepository columnStatisticsRepository;
    @Mock private PlanExecutionRepository planExecutionRepository;
    @Mock private PlanPatternRepository planPatternRepository;
    @Mock private QueryOptimizationService queryOptimizationService;
    @Mock private OptimizationCandidateService candidateService;
    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private ChatMemory chatMemory;
    @Mock private QuestionAnswerAdvisor questionAnswerAdvisor;
    @Mock private ChatContextAssembler contextAssembler;
    @Mock private SqlExecutionPipeline sqlExecutionPipeline;
    @Mock private QueryGenerationPipeline queryGenerationPipeline;
    @Mock private AgentOrchestrator agentOrchestrator;
    @Mock private AgentRunService agentRunService;
    @Mock private ChatRetrievalContextService chatRetrievalContextService;
    @Mock private ConversationContextService conversationContextService;
    @Mock private ChatScopeGuardService chatScopeGuardService;
    @Mock private DatabaseAdvisorService databaseAdvisorService;
    @Mock private IndexAdvisorService indexAdvisorService;
    @Mock private PerformanceActionAggregatorService performanceActionAggregatorService;
    @Mock private PerformanceSnapshotRepository performanceSnapshotRepository;
    @Mock private SchemaChangeRepository schemaChangeRepository;
    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;
    @Mock private SchemaChangeTrackingService schemaChangeTrackingService;
    @Mock private AccessControlService accessControlService;
    @Mock private UserRepository userRepository;
    @Mock private UserDataAccessPolicyService userDataAccessPolicyService;

    private ChatService chatService;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(chatHistoryService.addMessage(anyString(), any(), anyString(), any()))
            .thenAnswer(invocation -> {
                ChatMessage message = new ChatMessage();
                message.setId("msg-" + System.nanoTime());
                return message;
            });
        lenient().when(chatHistoryService.addMessage(anyString(), any(), anyString(), any(), any()))
            .thenAnswer(invocation -> {
                ChatMessage message = new ChatMessage();
                message.setId("msg-" + System.nanoTime());
                return message;
            });
        lenient().when(conversationContextService.resolveRelatedContext(anyString(), anyString(), anyString()))
            .thenReturn(ResolvedConversationContext.empty());
        lenient().when(conversationContextService.decideCarryover(anyString(), any(), any()))
            .thenReturn(ConversationCarryoverDecision.empty());
        lenient().when(chatScopeGuardService.evaluate(any(), any(), any()))
            .thenReturn(ChatScopeGuardService.ScopeDecision.allow());
        lenient().when(accessControlService.getCurrentUsername()).thenReturn("analyst");
        lenient().when(accessControlService.isCurrentUserAdmin()).thenReturn(false);
        lenient().when(userDataAccessPolicyService.evaluatePrompt(anyString(), anyString(), anyBoolean(), anyString()))
            .thenReturn(UserDataAccessPolicyService.PromptDecision.allow(ConnectionChatAccessPolicyService.EffectivePolicy.none()));
        lenient().when(userDataAccessPolicyService.decorateQuestionWithPolicy(any(), anyString()))
            .thenAnswer(invocation -> invocation.getArgument(1, String.class));
        lenient().when(contextAssembler.formatRowCount(anyLong()))
            .thenAnswer(invocation -> String.valueOf(invocation.getArgument(0, Long.class)));
        lenient().when(contextAssembler.formatBytes(anyLong()))
            .thenAnswer(invocation -> invocation.getArgument(0, Long.class) + " B");
        lenient().when(contextAssembler.getBestExecutionTime(any()))
            .thenReturn(500d);

        AnswerVerificationService answerVerificationService = new AnswerVerificationService();
        PromptIntentAnalyzer promptIntentAnalyzer = new PromptIntentAnalyzer();
        MetadataRequestScopeResolver metadataRequestScopeResolver = new MetadataRequestScopeResolver();
        SchemaMetadataExecutor schemaMetadataExecutor = new SchemaMetadataExecutor(
            contextAssembler,
            schemaClassificationService,
            tableClassificationRepository,
            keyColumnAnalysisRepository,
            inferredTableRelationshipRepository,
            schemaChangeRepository,
            schemaSnapshotRepository,
            schemaChangeTrackingService,
            answerVerificationService,
            new MetadataExplanationService()
        );
        PerformanceExecutor performanceExecutor = new PerformanceExecutor(
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
            answerVerificationService
        );

        chatService = new ChatService(
            schemaScannerService,
            queryExecutorService,
            trainingService,
            chatHistoryService,
            schemaClassificationService,
            feedbackService,
            businessRuleMemoryService,
            credentialService,
            tableClassificationRepository,
            slowQueryHistoryRepository,
            queryPerformanceRegressionRepository,
            keyColumnAnalysisRepository,
            inferredTableRelationshipRepository,
            growthAnomalyRepository,
            indexRecommendationRepository,
            tableStatsHistoryRepository,
            columnValueCacheRepository,
            workloadProfileRepository,
            knobRankingRepository,
            columnStatisticsRepository,
            planPatternRepository,
            queryOptimizationService,
            candidateService,
            new ObjectMapper(),
            chatClientBuilder,
            null,
            null,
            contextAssembler,
            sqlExecutionPipeline,
            queryGenerationPipeline,
            new ChatQuestionRoutingService(),
            agentOrchestrator,
            agentRunService,
            chatRetrievalContextService,
            conversationContextService,
            chatScopeGuardService,
            promptIntentAnalyzer,
            schemaMetadataExecutor,
            performanceExecutor,
            metadataRequestScopeResolver,
            accessControlService,
            userRepository,
            userDataAccessPolicyService
        );
        ReflectionTestUtils.setField(chatService, "agenticEnabled", true);
    }

    @Test
    void processMessage_largestFactTablesPrompt_usesUnifiedMetadataRun() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        TableMetadata orders = new TableMetadata("orders", null, "table", 2500L, 0L, List.of(
            new ColumnMetadata("order_id", "bigint", null, false, true, null, 1)
        ), List.of());
        TableMetadata bookings = new TableMetadata("bookings", null, "table", 12500L, 0L, List.of(
            new ColumnMetadata("booking_id", "bigint", null, false, true, null, 1)
        ), List.of());
        TableMetadata customers = new TableMetadata("customers", null, "table", 150L, 0L, List.of(
            new ColumnMetadata("customer_id", "bigint", null, false, true, null, 1)
        ), List.of());
        schema.setTables(List.of(orders, bookings, customers));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        lenient().when(schemaClassificationService.getLatestClassification("conn-1")).thenReturn(java.util.Optional.of(
            SchemaClassification.builder()
                .id("sc-1")
                .connectionId("conn-1")
                .globalPattern("STAR")
                .totalTables(3)
                .build()
        ));
        lenient().when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            TableClassification.builder().connectionId("conn-1").tableName("orders").tableRole("FACT").build(),
            TableClassification.builder().connectionId("conn-1").tableName("bookings").tableRole("FACT").build(),
            TableClassification.builder().connectionId("conn-1").tableName("customers").tableRole("DIMENSION").build()
        ));
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "CLASSIFICATION");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-largest-facts",
            AgentIntent.METADATA_ANALYSIS,
            "Largest FACT tables: `bookings`, `orders`.",
            null,
            "Goal: Analyze classification metadata",
            List.of(),
            List.of("metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "metadata_result_synthesis_tool"),
            0.92
        );
        when(agentOrchestrator.previewDecision(eq(true), anyString(), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("what are the largest fact tables?"), eq("what are the largest fact tables?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "what are the largest fact tables?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("`bookings`"));
        assertTrue(response.getMessage().contains("`orders`"));
        assertFalse(response.getMessage().contains("`customers`"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("what are the largest fact tables?"), eq("what are the largest fact tables?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient, keyColumnAnalysisRepository);
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), anyString(), isNull());
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), anyString(), isNull(), any());
    }

    @Test
    void processMessage_indexingPrompt_returnsVerifiedInsufficiencyInsteadOfClassificationFallback() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("ACCOUNTS", null, "table", 1200L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1),
                new ColumnMetadata("account_billing_interval_months", "int", null, true, false, null, 2)
            ), List.of())
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "PERFORMANCE");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-index-insufficiency",
            AgentIntent.METADATA_ANALYSIS,
            "No stored index recommendations exist yet for this connection, so I can't name columns that need immediate indexing from verified evidence.",
            null,
            "Goal: Analyze performance metadata",
            List.of(),
            List.of("llm_orchestration", "metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "metadata_result_synthesis_tool"),
            0.89
        );
        when(agentOrchestrator.previewDecision(eq(true), eq("which columns need immediate indexing?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("which columns need immediate indexing?"), eq("which columns need immediate indexing?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "which columns need immediate indexing?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("No stored index recommendations exist yet"));
        assertFalse(response.getMessage().contains("DIMENSION"));
        assertFalse(response.getMessage().contains("FACT tables"));

        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("which columns need immediate indexing?"), eq("which columns need immediate indexing?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(tableClassificationRepository, queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_indexingPerformancePrompt_returnsVerifiedLiveRecommendations() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("ORDER_CANCELLATIONS", null, "table", 1399701L, 0L, List.of(
                new ColumnMetadata("amount_breakdown", "json", null, true, false, null, 1),
                new ColumnMetadata("cancel_date", "datetime", null, true, false, null, 2),
                new ColumnMetadata("cancel_by", "varchar", 128L, true, false, null, 3)
            ), List.of())
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "PERFORMANCE");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-index-live",
            AgentIntent.METADATA_ANALYSIS,
            "Live advisor index candidates: `ORDER_CANCELLATIONS` should add `amount_breakdown, cancel_date, cancel_by`. CREATE INDEX idx_user_cancellations_amount_breakdown_cancel_date_cancel_by ON ORDER_CANCELLATIONS(amount_breakdown, cancel_date, cancel_by)",
            null,
            "Goal: Analyze performance metadata",
            List.of(),
            List.of("llm_orchestration", "metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "live_metadata_query_tool", "metadata_result_synthesis_tool"),
            0.93
        );
        when(agentOrchestrator.previewDecision(eq(true), eq("which columns should be indexed to get better performance?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("which columns should be indexed to get better performance?"), eq("which columns should be indexed to get better performance?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "which columns should be indexed to get better performance?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("Live advisor index candidates"));
        assertTrue(response.getMessage().contains("`amount_breakdown, cancel_date, cancel_by`"));
        assertTrue(response.getMessage().contains("`ORDER_CANCELLATIONS`"));
        assertTrue(response.getMessage().contains("CREATE INDEX idx_user_cancellations_amount_breakdown_cancel_date_cancel_by"));
        assertFalse(response.getToolsUsed().isEmpty());

        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("which columns should be indexed to get better performance?"), eq("which columns should be indexed to get better performance?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(tableClassificationRepository, queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_databaseAccessFailure_returnsConnectionUnavailableMessage() throws Exception {
        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY");
        SQLException sqlException = new SQLException(
            "Access denied for user 'dba_agent_user'@'172.31.4.56' (using password: YES)",
            "28000"
        );

        when(agentOrchestrator.previewDecision(eq(true), eq("How many active customers we have?"), any()))
            .thenReturn(decision);
        when(agentOrchestrator.execute(
            eq(true),
            eq("conn-1"),
            eq("How many active customers we have?"),
            eq("How many active customers we have?"),
            eq("chat-1"),
            anyList(),
            any(),
            any(),
            isNull(),
            eq(decision),
            any(),
            any()
        )).thenThrow(new RuntimeException(sqlException));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "How many active customers we have?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertFalse(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertEquals(
            "DeepSQL can't access this database connection right now. Please reach out to your admin to verify the connection credentials and connectivity.",
            response.getMessage()
        );

        verify(chatHistoryService).addMessage(eq("chat-1"), any(), eq("How many active customers we have?"), isNull());
        verify(chatHistoryService).addMessage(
            eq("chat-1"),
            any(),
            eq("DeepSQL can't access this database connection right now. Please reach out to your admin to verify the connection credentials and connectivity."),
            isNull(),
            any()
        );
    }

    @Test
    void processMessage_exactTableColumnCountPrompt_usesUnifiedMetadataRunAndAnswersRequestedTable() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata customer = new TableMetadata("CUSTOMERS", null, "table", 1250L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("name", "varchar", 255L, false, false, null, 2),
            new ColumnMetadata("country", "varchar", 100L, true, false, null, 3)
        ), List.of());
        TableMetadata airbnbListingHotel = new TableMetadata("airbnb_listing_hotel", null, "table", 45L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1)
        ), List.of());
        schema.setTables(List.of(customer, airbnbListingHotel));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        stubAgenticMetadataAnswer(
            "How many columns we have in CUSTOMERS table?",
            schema,
            "Connection `analytics` table `CUSTOMERS` has **3 columns**.",
            "run-customer-column-count"
        );

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "How many columns we have in CUSTOMERS table?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("`CUSTOMERS`"));
        assertTrue(response.getMessage().contains("**3 columns**"));
        assertFalse(response.getMessage().contains("airbnb_listing_hotel"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("How many columns we have in CUSTOMERS table?"), eq("How many columns we have in CUSTOMERS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), any(), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_unifiedMetadataResponse_createsInspectableAgentRun() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("CUSTOMERS", null, "table", 1250L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1),
                new ColumnMetadata("name", "varchar", 255L, false, false, null, 2)
            ), List.of())
        ));

        AgentRun run = new AgentRun();
        run.setId("run-unified-metadata");
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        stubAgenticMetadataAnswer(
            "What columns are there in CUSTOMERS table?",
            schema,
            "Table `CUSTOMERS` has **2 columns**.\n\nColumns:\n- `id` — `bigint`; primary key; not null\n- `name` — `varchar`; not null",
            "run-unified-metadata"
        );

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "What columns are there in CUSTOMERS table?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertEquals("run-unified-metadata", response.getAgentRunId());
        assertFalse(response.getToolsUsed().isEmpty());

        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("What columns are there in CUSTOMERS table?"), eq("What columns are there in CUSTOMERS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), any(), any(), any());
    }

    @Test
    void processMessage_exactTableColumnListPrompt_listsColumnsFromRequestedTable() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata customer = new TableMetadata("CUSTOMERS", null, "table", 1250L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("name", "varchar", 255L, false, false, null, 2),
            new ColumnMetadata("country", "varchar", 100L, true, false, null, 3)
        ), List.of());
        schema.setTables(List.of(customer));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        stubAgenticMetadataAnswer(
            "What columns are there in CUSTOMERS table?",
            schema,
            "Table `CUSTOMERS` has **3 columns**.\n\nColumns:\n- `id` — `bigint`; primary key; not null\n- `name` — `varchar`; not null\n- `country` — `varchar`; nullable",
            "run-customer-columns"
        );

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "What columns are there in CUSTOMERS table?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("Columns:"));
        assertTrue(response.getMessage().contains("`id` — `bigint`; primary key; not null"));
        assertTrue(response.getMessage().contains("`name` — `varchar`; not null"));
        assertTrue(response.getMessage().contains("`country` — `varchar`; nullable"));
        assertFalse(response.getMessage().contains("| Column | Type | Attributes |"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("What columns are there in CUSTOMERS table?"), eq("What columns are there in CUSTOMERS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), any(), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_exactTableRowCountPrompt_usesUnifiedMetadataRunAndAnswersRequestedTable() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata accounts = new TableMetadata("ACCOUNTS", null, "table", 9419333L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1)
        ), List.of());
        TableMetadata orderTable = new TableMetadata("order_table", null, "table", 120L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1)
        ), List.of());
        schema.setTables(List.of(accounts, orderTable));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        stubAgenticMetadataAnswer(
            "How many rows in ACCOUNTS table?",
            schema,
            "Table `ACCOUNTS` has an estimated **9419333 rows** from the live database catalogs.",
            "run-accounts-rowcount"
        );

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "How many rows in ACCOUNTS table?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("`ACCOUNTS`"));
        assertTrue(response.getMessage().contains("rows"));
        assertFalse(response.getMessage().contains("order_table"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("How many rows in ACCOUNTS table?"), eq("How many rows in ACCOUNTS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), any(), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_exactTableIndexPrompt_usesUnifiedMetadataRunAndStaysAnchoredOnRequestedTable() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata accounts = new TableMetadata("ACCOUNTS", null, "table", 9419333L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1)
        ), List.of(
            new IndexMetadata("accounts_pkey", "ACCOUNTS", true, List.of("id"), "btree"),
            new IndexMetadata("idx_accounts_group_id", "ACCOUNTS", false, List.of("group_id"), "btree")
        ));
        TableMetadata orderTable = new TableMetadata("order_table", null, "table", 120L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1)
        ), List.of(
            new IndexMetadata("idx_order_hotel", "order_table", false, List.of("customer_id"), "btree")
        ));
        schema.setTables(List.of(accounts, orderTable));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        stubAgenticMetadataAnswer(
            "What indexes are there on ACCOUNTS table?",
            schema,
            "Table `ACCOUNTS` has **2 indexes** in the current schema snapshot.\n\nIndexes:\n- `accounts_pkey` — columns: `id`; unique; btree\n- `idx_accounts_group_id` — columns: `group_id`; btree",
            "run-accounts-indexes"
        );

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "What indexes are there on ACCOUNTS table?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("`ACCOUNTS`"));
        assertTrue(response.getMessage().contains("accounts_pkey"));
        assertTrue(response.getMessage().contains("idx_accounts_group_id"));
        assertFalse(response.getMessage().contains("idx_order_hotel"));
        assertFalse(response.getMessage().contains("order_table"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("What indexes are there on ACCOUNTS table?"), eq("What indexes are there on ACCOUNTS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), any(), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_exactTableRowCountPrompt_missingCachedValueFallsBackToAgenticMetadata() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata accounts = new TableMetadata("ACCOUNTS", null, "table", null, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1)
        ), List.of());
        schema.setTables(List.of(accounts));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);

        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "SCHEMA");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-schema-rowcount",
            AgentIntent.METADATA_ANALYSIS,
            "Table `ACCOUNTS` has an estimated **9419333 rows** from the live database catalogs.",
            null,
            "Goal: Analyze schema metadata for ACCOUNTS",
            List.of(),
            List.of("vault_metadata_lookup_tool", "live_metadata_query_tool"),
            0.9
        );

        when(agentOrchestrator.previewDecision(eq(true), contains("How many rows in ACCOUNTS table"), any()))
            .thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("How many rows in ACCOUNTS table?"), eq("How many rows in ACCOUNTS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "How many rows in ACCOUNTS table?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("`ACCOUNTS`"));
        assertTrue(response.getMessage().contains("rows"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("How many rows in ACCOUNTS table?"), eq("How many rows in ACCOUNTS table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_exactTableKeyColumnPrompt_usesExactSchemaKeysAndSkipsRankedLookup() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata roomReservations = new TableMetadata("ORDER_LINE_ITEMS", null, "table", 5000L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("booking_id", "bigint", null, false, false, null, 2),
            new ColumnMetadata("customer_id", "bigint", null, false, false, null, 3),
            new ColumnMetadata("rate_plan_id", "bigint", null, true, false, null, 4),
            new ColumnMetadata("reservation_count", "int", null, true, false, null, 5)
        ), List.of());
        TableMetadata masterLoginAccessKeys = new TableMetadata("MASTER_LOGIN_ACCESS_KEYS", null, "table", 20L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("access_key", "varchar", 255L, false, false, null, 2)
        ), List.of());
        TableMetadata orderTable = new TableMetadata("order_table", null, "table", 100L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("customer_id", "bigint", null, false, false, null, 2)
        ), List.of());
        TableMetadata nrReservation = new TableMetadata("nr_reservation", null, "table", 100L, 0L, List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("non_rental_type_id", "bigint", null, false, false, null, 2)
        ), List.of());
        schema.setTables(List.of(roomReservations, masterLoginAccessKeys, orderTable, nrReservation));
        schema.setRelationships(List.of(
            new RelationshipMetadata("fk_room_booking", "ORDER_LINE_ITEMS", "booking_id", "BOOKINGS", "id", "many-to-one", "fk_room_booking"),
            new RelationshipMetadata("fk_room_hotel", "ORDER_LINE_ITEMS", "customer_id", "CUSTOMERS", "id", "many-to-one", "fk_room_hotel"),
            new RelationshipMetadata("fk_room_rate_plan", "ORDER_LINE_ITEMS", "rate_plan_id", "RATE_PLAN", "id", "many-to-one", "fk_room_rate_plan")
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        stubAgenticMetadataAnswer(
            "Show me all key columns in ORDER_LINE_ITEMS table",
            schema,
            "The most relevant key columns in `ORDER_LINE_ITEMS` are:\n- `id` — Primary key\n- `booking_id` — References BOOKINGS.id\n- `customer_id` — References CUSTOMERS.id\n- `rate_plan_id` — References RATE_PLAN.id\n- `room_type` — Common grouping/filter column",
            "run-room-reservation-keys"
        );

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "Show me all key columns in ORDER_LINE_ITEMS table",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("`ORDER_LINE_ITEMS`"));
        assertTrue(response.getMessage().contains("`id`"));
        assertTrue(response.getMessage().contains("`booking_id`"));
        assertTrue(response.getMessage().contains("`customer_id`"));
        assertTrue(response.getMessage().contains("`rate_plan_id`"));
        assertTrue(response.getMessage().contains("`room_type`"));
        assertFalse(response.getMessage().contains("MASTER_LOGIN_ACCESS_KEYS"));
        assertFalse(response.getMessage().contains("order_table"));
        assertFalse(response.getMessage().contains("nr_reservation"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("Show me all key columns in ORDER_LINE_ITEMS table"), eq("Show me all key columns in ORDER_LINE_ITEMS table"), eq("chat-1"), anyList(), any(), any(), eq(schema), any(), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_schemaPatternPrompt_includesPatternFactAndDimensionTerms() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("orders", null, "table", 2500L, 0L, List.of(
                new ColumnMetadata("order_id", "bigint", null, false, true, null, 1)
            ), List.of()),
            new TableMetadata("customers", null, "table", 150L, 0L, List.of(
                new ColumnMetadata("customer_id", "bigint", null, false, true, null, 1)
            ), List.of())
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        lenient().when(schemaClassificationService.getLatestClassification("conn-1")).thenReturn(java.util.Optional.of(
            SchemaClassification.builder()
                .id("sc-2")
                .connectionId("conn-1")
                .globalPattern("STAR")
                .totalTables(2)
                .build()
        ));
        lenient().when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            TableClassification.builder().connectionId("conn-1").tableName("orders").tableRole("FACT").build(),
            TableClassification.builder().connectionId("conn-1").tableName("customers").tableRole("DIMENSION").build()
        ));
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "CLASSIFICATION");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-schema-pattern",
            AgentIntent.METADATA_ANALYSIS,
            "Stored schema classification shows a **STAR** PATTERN. FACT tables: orders. DIMENSION tables: customers.",
            null,
            "Goal: Analyze classification metadata",
            List.of(),
            List.of("metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "metadata_result_synthesis_tool"),
            0.92
        );
        when(agentOrchestrator.previewDecision(eq(true), anyString(), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("What is our schema pattern and which fact and dimension tables are identified?"), eq("What is our schema pattern and which fact and dimension tables are identified?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "What is our schema pattern and which fact and dimension tables are identified?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("PATTERN"));
        assertTrue(response.getMessage().contains("FACT"));
        assertTrue(response.getMessage().contains("DIMENSION"));
    }

    @Test
    void processMessage_pairScopedRelationshipPrompt_routesThroughAgenticMetadataWorkflow() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("ORDERS", null, "table", 2500L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of()),
            new TableMetadata("ORDER_DETAIL", null, "table", 6400L, 0L, List.of(
                new ColumnMetadata("order_id", "bigint", null, false, false, null, 1)
            ), List.of())
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "RELATIONSHIPS");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-order-relationship",
            AgentIntent.METADATA_ANALYSIS,
            "Verified direct relationship metadata between `ORDERS` and `ORDER_DETAIL`: `ORDERS.id` -> `ORDER_DETAIL.order_id`.",
            null,
            "Goal: Analyze relationship metadata for ORDERS, ORDER_DETAIL",
            List.of(),
            List.of("metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "live_metadata_query_tool", "metadata_result_synthesis_tool"),
            0.94
        );

        when(agentOrchestrator.previewDecision(eq(true), contains("ORDERS and ORDER_DETAIL"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("How are ORDERS and ORDER_DETAIL related?"), eq("How are ORDERS and ORDER_DETAIL related?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "How are ORDERS and ORDER_DETAIL related?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("ORDERS"));
        assertTrue(response.getMessage().contains("ORDER_DETAIL"));
        assertNull(response.getSql());
        assertEquals(List.of(), response.getExecutedQueries());
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("How are ORDERS and ORDER_DETAIL related?"), eq("How are ORDERS and ORDER_DETAIL related?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_metadataAnalysisWithLiveFallback_keepsSqlOutOfTopLevelResponse() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("CUSTOMER_ORDERS", null, "table", 2500L, 0L, List.of(), List.of()),
            new TableMetadata("PRICE_BREAKDOWN", null, "table", 6400L, 0L, List.of(), List.of())
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "RELATIONSHIPS");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-join-columns",
            AgentIntent.METADATA_ANALYSIS,
            "Verified direct relationship metadata between `CUSTOMER_ORDERS` and `PRICE_BREAKDOWN`: `CUSTOMER_ORDERS.id` -> `PRICE_BREAKDOWN.booking_id`.",
            null,
            "Goal: Analyze relationship metadata for CUSTOMER_ORDERS, PRICE_BREAKDOWN",
            List.of("SELECT * FROM information_schema.KEY_COLUMN_USAGE"),
            List.of("metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "live_metadata_query_tool", "metadata_result_synthesis_tool"),
            0.92
        );

        when(agentOrchestrator.previewDecision(eq(true), contains("USER BOOKINGS and PRICE BREAKDOWN"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("what columns are joined commonly between USER BOOKINGS and PRICE BREAKDOWN tables"), eq("what columns are joined commonly between USER BOOKINGS and PRICE BREAKDOWN tables"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "what columns are joined commonly between USER BOOKINGS and PRICE BREAKDOWN tables",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertNull(response.getSql());
        assertEquals(List.of("SELECT * FROM information_schema.KEY_COLUMN_USAGE"), response.getExecutedQueries());
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), contains("CUSTOMER_ORDERS"), isNull(), any());
    }

    @Test
    void streamProcessMessage_inferredKeysPrompt_usesUnifiedMetadataWorkflow() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        TableMetadata guestMapping = new TableMetadata();
        guestMapping.setName("CONTACT_MAPPING");
        guestMapping.setColumns(List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("booking_id", "varchar", 50L, false, false, null, 2)
        ));
        schema.setTables(List.of(guestMapping));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "KEY_COLUMNS");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-guest-mapping-keys",
            AgentIntent.METADATA_ANALYSIS,
            "Cached schema metadata identifies inferred keys for `CONTACT_MAPPING`: `booking_id`, `room_id`.",
            null,
            "Goal: Analyze key column metadata",
            List.of(),
            List.of("llm_orchestration", "metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "metadata_result_synthesis_tool"),
            0.94
        );
        when(agentOrchestrator.previewDecision(eq(true), eq("what are all the inferred keys in guest mapping table?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("what are all the inferred keys in guest mapping table?"), eq("what are all the inferred keys in guest mapping table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));
        ChatService.StreamResult result = chatService.streamProcessMessage(
            "conn-1",
            "what are all the inferred keys in guest mapping table?",
            "chat-1"
        );

        List<String> tokens = result.tokenStream().collectList().block();
        List<String> metadata = result.metadataStream().collectList().block();
        List<String> structuredResults = result.resultStream().collectList().block();

        assertNotNull(tokens);
        assertEquals(1, tokens.size());
        assertTrue(tokens.get(0).contains("CONTACT_MAPPING"));
        assertTrue(tokens.get(0).contains("booking_id"));
        assertTrue(tokens.get(0).contains("room_id"));

        assertNotNull(metadata);
        assertFalse(metadata.isEmpty());
        assertTrue(metadata.stream().anyMatch(item -> item.contains("\"mode\":\"unified\"")));
        assertNotNull(structuredResults);
        assertEquals(1, structuredResults.size());
        assertTrue(structuredResults.get(0).contains("\"chatId\":\"chat-1\""));
        assertTrue(structuredResults.get(0).contains("\"mode\":\"unified\""));
        assertTrue(structuredResults.get(0).contains("\"success\":true"));

        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("what are all the inferred keys in guest mapping table?"), eq("what are all the inferred keys in guest mapping table?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_complexRevenuePrompt_usesAgenticPath() throws Exception {
        QueryResult primaryResult = new QueryResult(
            List.of("dimension_key", "collected_amount"),
            List.of(List.of("INR", 6070902.0)),
            1,
            null,
            false,
            42L,
            "SELECT ..."
        );
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-1",
            AgentIntent.UNIVERSAL_CHAT,
            "Month-to-date collected revenue by currency:\\n- INR: 6070902",
            primaryResult,
            "Goal: Answer the user's data question with schema-aware reasoning and safe SQL execution\\n- Resolve entities, compile SQL, and validate results (universal_chat_tool)",
            List.of("SELECT revenue_sql"),
            List.of("universal_chat_tool"),
            0.93
        );

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY");
        when(agentOrchestrator.previewDecision(eq(true), contains("subscription revenue"), any()))
            .thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), contains("subscription revenue"), contains("subscription revenue"), eq("chat-1"), anyList(), any(), any(), isNull(), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "What is the subscription revenue collected this month in INR and USD?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertEquals("chat-1", response.getChatId());
        assertEquals(agentResult.planSummary(), response.getPlan());
        assertEquals(agentResult.executedQueries(), response.getExecutedQueries());
        assertEquals(agentResult.toolsUsed(), response.getToolsUsed());
        assertEquals(agentResult.confidence(), response.getConfidence());
        assertEquals("SELECT revenue_sql", response.getSql());
        assertEquals("run-1", response.getAgentRunId());

        verify(agentOrchestrator).previewDecision(eq(true), contains("subscription revenue"), any());
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), contains("subscription revenue"), contains("subscription revenue"), eq("chat-1"), anyList(), any(), any(), isNull(), eq(decision), any(), any());
        verify(schemaScannerService).scanSchema("conn-1");
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), anyString(), isNull());
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), anyString(), any(), any());
    }

    @Test
    void processMessage_genericBiPrompt_routesThroughSchemaAwareAgenticWorkflow() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(new TableMetadata("CUSTOMER_ORDERS", null, "table", null, null, List.of(), List.of())));

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY");
        QueryResult primaryResult = new QueryResult(
            List.of("customer_id", "bookings_count"),
            List.of(List.of("34431", 25)),
            1,
            null,
            false,
            20L,
            "SELECT customer_id, COUNT(*) FROM CUSTOMER_ORDERS"
        );
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-universal",
            AgentIntent.UNIVERSAL_CHAT,
            "Top customer in the last 3 days is `34431` with 25 bookings.",
            primaryResult,
            "Goal: Answer the user's data question with deterministic schema reasoning and safe SQL execution",
            List.of("SELECT customer_id, COUNT(*) FROM CUSTOMER_ORDERS"),
            List.of("universal_chat_tool"),
            0.94
        );

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(agentOrchestrator.previewDecision(eq(true), contains("top 5 customers"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), contains("top 5 customers"), contains("top 5 customers"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "give me top 5 customers by bookings volume in the last 3 days.",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertEquals("run-universal", response.getAgentRunId());
        assertEquals("SELECT customer_id, COUNT(*) FROM CUSTOMER_ORDERS", response.getSql());
        verify(schemaScannerService).scanSchema("conn-1");
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), contains("top 5 customers"), contains("top 5 customers"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void processMessage_multiPartAgenticPrompt_returnsStructuredResultSets() throws Exception {
        QueryResult headlineResult = new QueryResult(
            List.of("total_mrr"),
            List.of(List.of("295488.81")),
            1,
            null,
            false,
            25L,
            "SELECT total_mrr"
        );
        QueryResult detailResult = new QueryResult(
            List.of("customer_name", "country", "subscription_amount"),
            List.of(List.of("Hotel A", "India", "25000")),
            1,
            null,
            false,
            31L,
            "SELECT customer_name, country, subscription_amount"
        );
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-multi",
            AgentIntent.UNIVERSAL_CHAT,
            "MRR from new properties in the last month is 295,488.81 INR. The property details are listed below.",
            headlineResult,
            "Goal: Answer the user's request with multi-step schema reasoning, safe SQL execution, and stitched coverage of every requested part",
            List.of("SELECT total_mrr", "SELECT customer_name, country, subscription_amount"),
            List.of("context_resolution_tool", "universal_chat_tool", "universal_chat_tool", "result_synthesis_tool"),
            0.94,
            List.of(
                new AgentTaskResult(
                    "task-1",
                    "Calculate MRR from new properties",
                    AgentTaskKind.DATA_QUERY,
                    List.of(),
                    "COMPLETED",
                    "MRR from new properties in the last month is 295,488.81 INR.",
                    "Calculated the headline MRR metric",
                    List.of("SELECT total_mrr"),
                    headlineResult,
                    Map.of("rowCount", 1),
                    0.95
                ),
                new AgentTaskResult(
                    "task-2",
                    "List property details",
                    AgentTaskKind.LOOKUP,
                    List.of("task-1"),
                    "COMPLETED",
                    "Hotel A in India has a subscription amount of 25,000.",
                    "Listed the matching property details",
                    List.of("SELECT customer_name, country, subscription_amount"),
                    detailResult,
                    Map.of("rowCount", 1),
                    0.92
                )
            ),
            new PromptIntent(
                PromptIntent.Domain.BI,
                PromptIntent.TaskType.SQL_QUERY,
                java.util.Set.of(PromptIntent.SubjectType.METRIC),
                PromptIntent.RequestedOutput.SQL_RESULT,
                Map.of("routeType", "BI_QUERY"),
                true,
                false,
                false,
                false
            ),
            null,
            null
        );

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "BI_QUERY");
        when(agentOrchestrator.previewDecision(eq(true), contains("MRR contributed by new properties"), any()))
            .thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), contains("MRR contributed by new properties"), contains("MRR contributed by new properties"), eq("chat-1"), anyList(), any(), any(), isNull(), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "what is the MRR contributed by new properties in last month? also, show me details of all those properties. Hotel name, country, subscription amount.",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertEquals("SELECT total_mrr", response.getSql());
        assertNotNull(response.getResultSets());
        assertEquals(2, response.getResultSets().size());
        ChatResultSet first = response.getResultSets().getFirst();
        ChatResultSet second = response.getResultSets().get(1);
        assertEquals("task-1", first.getTaskId());
        assertEquals("task-2", second.getTaskId());
        assertEquals(List.of("task-1"), second.getDependsOn());
        assertEquals(detailResult, second.getData());
    }

    @Test
    void processMessage_followUpPrompt_usesRecentChatHistoryForRouting() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(new TableMetadata("CONTACT_MAPPING", null, "table", null, null, List.of(), List.of())));

        ChatMessage priorUser = new ChatMessage();
        priorUser.setRole(ChatMessage.MessageRole.USER);
        priorUser.setContent("what are all the inferred keys in guest mapping table?");
        ChatMessage priorAssistant = new ChatMessage();
        priorAssistant.setRole(ChatMessage.MessageRole.ASSISTANT);
        priorAssistant.setContent("contact_mapping.booking_id, contact_mapping.room_id, contact_mapping.reservation_id");

        when(chatHistoryService.getChatMessages("chat-1")).thenReturn(List.of(priorUser, priorAssistant));
        ResolvedConversationContext matchedContext = new ResolvedConversationContext(
            "ctx-guest-mapping",
            "BRAIN_METADATA",
            "VERIFIED",
            priorUser.getContent(),
            "Prior thread focused on inferred keys for CONTACT_MAPPING.",
            Map.of("tableName", "CONTACT_MAPPING"),
            List.of(Map.of("displayLabel", "CONTACT_MAPPING", "entityType", "TABLE")),
            Map.of("keyColumns", List.of("booking_id", "room_id", "reservation_id")),
            null,
            List.of(),
            0.95
        );
        ConversationCarryoverDecision carryoverDecision = new ConversationCarryoverDecision(
            ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE,
            "ctx-guest-mapping",
            "BRAIN_METADATA",
            "VERIFIED",
            List.of("CONTACT_MAPPING"),
            List.of(),
            null,
            null,
            List.of(),
            0.9,
            "The user is asking a refinement about the previously discussed inferred keys."
        );
        when(conversationContextService.resolveRelatedContext("conn-1", "chat-1", "which one of those is the strongest join key to bookings?"))
            .thenReturn(matchedContext);
        when(conversationContextService.decideCarryover(eq("which one of those is the strongest join key to bookings?"), any(), eq(matchedContext)))
            .thenReturn(carryoverDecision);
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);

        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "KEY_COLUMNS");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-follow-up",
            AgentIntent.METADATA_ANALYSIS,
            "Based on the prior `CONTACT_MAPPING` key list, `booking_id` is the strongest booking join key.",
            null,
            "Goal: Analyze key columns metadata for CONTACT_MAPPING",
            List.of(),
            List.of("vault_metadata_lookup_tool"),
            0.9
        );

        when(agentOrchestrator.previewDecision(eq(true), eq("which one of those is the strongest join key to bookings?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(
            eq(true),
            eq("conn-1"),
            eq("which one of those is the strongest join key to bookings?"),
            eq("which one of those is the strongest join key to bookings?"),
            eq("chat-1"),
            anyList(),
            eq(matchedContext),
            any(),
            eq(schema),
            eq(decision),
            eq(carryoverDecision),
            any()
        ))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "which one of those is the strongest join key to bookings?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("booking_id"));

        verify(agentOrchestrator).previewDecision(eq(true), eq("which one of those is the strongest join key to bookings?"), any());
        verify(agentOrchestrator).execute(
            eq(true),
            eq("conn-1"),
            eq("which one of those is the strongest join key to bookings?"),
            eq("which one of those is the strongest join key to bookings?"),
            eq("chat-1"),
            anyList(),
            eq(matchedContext),
            any(),
            eq(schema),
            eq(decision),
            eq(carryoverDecision),
            any()
        );
    }

    @Test
    void processMessage_nowOnlyFollowUp_carriesConversationContext() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("CONTACT_MAPPING", null, "table", null, null, List.of(), List.of()),
            new TableMetadata("CUSTOMER_ORDERS", null, "table", null, null, List.of(), List.of())
        ));

        ChatMessage priorUser = new ChatMessage();
        priorUser.setRole(ChatMessage.MessageRole.USER);
        priorUser.setContent("Show guest names and emails from CONTACT_MAPPING with their booking amounts for the last 30 days.");
        ChatMessage priorAssistant = new ChatMessage();
        priorAssistant.setRole(ChatMessage.MessageRole.ASSISTANT);
        priorAssistant.setContent("I used the established CUSTOMER_ORDERS and CONTACT_MAPPING join path.");

        when(chatHistoryService.getChatMessages("chat-1")).thenReturn(List.of(priorUser, priorAssistant));
        ResolvedConversationContext matchedContext = new ResolvedConversationContext(
            "ctx-guest-bookings",
            "BI_QUERY",
            "VERIFIED",
            priorUser.getContent(),
            "Prior thread joined CUSTOMER_ORDERS and CONTACT_MAPPING for guest booking amounts.",
            Map.of("tables", List.of("CUSTOMER_ORDERS", "CONTACT_MAPPING")),
            List.of(Map.of("displayLabel", "CONTACT_MAPPING", "entityType", "TABLE")),
            Map.of("statusFilter", "ALL"),
            "SELECT ... FROM CUSTOMER_ORDERS JOIN CONTACT_MAPPING ...",
            List.of(),
            0.96
        );
        ConversationCarryoverDecision carryoverDecision = new ConversationCarryoverDecision(
            ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE,
            "ctx-guest-bookings",
            "BI_QUERY",
            "VERIFIED",
            List.of("CUSTOMER_ORDERS", "CONTACT_MAPPING"),
            List.of("CUSTOMER_ORDERS", "CONTACT_MAPPING"),
            null,
            "booking amounts",
            List.of("booking_status = 'CANCELLED'"),
            0.93,
            "The user is narrowing the prior guest-booking result set."
        );
        when(conversationContextService.resolveRelatedContext("conn-1", "chat-1", "Now only cancelled ones."))
            .thenReturn(matchedContext);
        when(conversationContextService.decideCarryover(eq("Now only cancelled ones."), any(), eq(matchedContext)))
            .thenReturn(carryoverDecision);
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "GENERAL");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-now-only",
            AgentIntent.UNIVERSAL_CHAT,
            "I narrowed the prior guest-booking query to cancelled bookings only.",
            null,
            "Goal: Answer the user's data question with deterministic schema reasoning and safe SQL execution",
            List.of("SELECT ... FROM CUSTOMER_ORDERS JOIN CONTACT_MAPPING ... WHERE booking_status = 'CANCELLED'"),
            List.of("universal_chat_tool"),
            0.94
        );

        when(agentOrchestrator.previewDecision(eq(true), eq("Now only cancelled ones."), any())).thenReturn(decision);
        when(agentOrchestrator.execute(
            eq(true),
            eq("conn-1"),
            eq("Now only cancelled ones."),
            eq("Now only cancelled ones."),
            eq("chat-1"),
            anyList(),
            eq(matchedContext),
            any(),
            eq(schema),
            eq(decision),
            eq(carryoverDecision),
            any()
        ))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "Now only cancelled ones.",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("cancelled"));

        verify(agentOrchestrator).previewDecision(eq(true), eq("Now only cancelled ones."), any());
        verify(agentOrchestrator).execute(
            eq(true),
            eq("conn-1"),
            eq("Now only cancelled ones."),
            eq("Now only cancelled ones."),
            eq("chat-1"),
            anyList(),
            eq(matchedContext),
            any(),
            eq(schema),
            eq(decision),
            eq(carryoverDecision),
            any()
        );
    }

    @Test
    void processMessage_shortStandalonePrompt_doesNotCarryOldChatContext() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");

        ChatMessage priorUser = new ChatMessage();
        priorUser.setRole(ChatMessage.MessageRole.USER);
        priorUser.setContent("give me recurring guests in last 7 days");
        ChatMessage priorAssistant = new ChatMessage();
        priorAssistant.setRole(ChatMessage.MessageRole.ASSISTANT);
        priorAssistant.setContent("I need one clarification before I run SQL safely: which time column should define this window? Likely candidates are STAFF_USERS.date_joined.");

        when(chatHistoryService.getChatMessages("chat-1")).thenReturn(List.of(priorUser, priorAssistant));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "GENERAL");
        AgentExecutionResult agentResult = new AgentExecutionResult(
            "run-standalone",
            AgentIntent.UNIVERSAL_CHAT,
            "Least-used tables are derived from performance_schema table IO counters.",
            null,
            "Goal: Answer the user's request with vault-first schema reasoning and bounded agent analysis",
            List.of("SELECT ..."),
            List.of("universal_chat_tool"),
            0.88
        );

        when(agentOrchestrator.previewDecision(eq(true), eq("what tables are least used?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("what tables are least used?"), eq("what tables are least used?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(agentResult));

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "what tables are least used?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertTrue(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertTrue(response.getMessage().contains("Least-used tables"));

        verify(agentOrchestrator).previewDecision(eq(true), eq("what tables are least used?"), any());
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("what tables are least used?"), eq("what tables are least used?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
    }

    @Test
    void processMessage_agenticFailureReturnsStandardizedAgenticFailure() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(
            new TableMetadata("orders", null, "table", null, null, List.of(), List.of()),
            new TableMetadata("payments", null, "table", null, null, List.of(), List.of())
        ));

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "GENERAL");

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(agentOrchestrator.previewDecision(eq(true), eq("what tables are least used?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("what tables are least used?"), eq("what tables are least used?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.empty());

        ChatResponse response = chatService.processMessage(
            "conn-1",
            "what tables are least used?",
            null,
            null,
            null,
            "chat-1",
            null
        );

        assertFalse(response.isSuccess());
        assertEquals("unified", response.getMode());
        assertEquals("chat-1", response.getChatId());
        assertEquals(
            "DeepSQL could not complete this run because the agent runtime hit an internal execution failure before it could finish scouting the available sources. Please retry once; if it repeats, ask an admin to inspect the agent run trace.",
            response.getMessage()
        );

        verify(agentOrchestrator).previewDecision(eq(true), eq("what tables are least used?"), any());
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("what tables are least used?"), eq("what tables are least used?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verify(schemaScannerService).scanSchema("conn-1");
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), eq("what tables are least used?"), isNull());
        verify(chatHistoryService).addMessage(eq("chat-1"), any(), contains("agent runtime hit an internal execution failure"), isNull(), any());
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    @Test
    void streamProcessMessage_agenticFailureReturnsStandardizedAgenticFailure() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics");
        schema.setDbType("mysql");
        schema.setTables(List.of(new TableMetadata("orders", null, "table", null, null, List.of(), List.of())));

        AgentDecision decision = new AgentDecision(true, AgentIntent.UNIVERSAL_CHAT, "GENERAL");

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(agentOrchestrator.previewDecision(eq(true), eq("what tables are least used?"), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq("what tables are least used?"), eq("what tables are least used?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.empty());

        ChatService.StreamResult result = chatService.streamProcessMessage(
            "conn-1",
            "what tables are least used?",
            "chat-1"
        );

        List<String> metadata = result.metadataStream().collectList().block();
        List<String> structuredResults = result.resultStream().collectList().block();
        List<String> tokens = result.tokenStream().collectList().block();

        assertNotNull(metadata);
        assertFalse(metadata.isEmpty());
        assertTrue(metadata.stream().anyMatch(item -> item.contains("\"mode\":\"unified\"")));
        assertNotNull(structuredResults);
        assertEquals(1, structuredResults.size());
        assertTrue(structuredResults.get(0).contains("\"mode\":\"unified\""));
        assertTrue(structuredResults.get(0).contains("\"success\":false"));
        assertNotNull(tokens);
        assertEquals(
            List.of("DeepSQL could not complete this run because the agent runtime hit an internal execution failure before it could finish scouting the available sources. Please retry once; if it repeats, ask an admin to inspect the agent run trace."),
            tokens
        );

        verify(agentOrchestrator).previewDecision(eq(true), eq("what tables are least used?"), any());
        verify(agentOrchestrator).execute(eq(true), eq("conn-1"), eq("what tables are least used?"), eq("what tables are least used?"), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any());
        verify(schemaScannerService).scanSchema("conn-1");
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, chatClient);
    }

    private void stubAgenticMetadataAnswer(String question, SchemaMetadata schema, String message, String runId) {
        AgentDecision decision = new AgentDecision(true, AgentIntent.METADATA_ANALYSIS, "SCHEMA");
        AgentExecutionResult result = new AgentExecutionResult(
            runId,
            AgentIntent.METADATA_ANALYSIS,
            message,
            null,
            "Goal: Analyze schema metadata",
            List.of(),
            List.of("llm_orchestration", "metadata_context_resolution_tool", "metadata_evidence_lookup_tool", "metadata_result_synthesis_tool"),
            0.93
        );
        when(agentOrchestrator.previewDecision(eq(true), eq(question), any())).thenReturn(decision);
        when(agentOrchestrator.execute(eq(true), eq("conn-1"), eq(question), eq(question), eq("chat-1"), anyList(), any(), any(), eq(schema), eq(decision), any(), any()))
            .thenReturn(java.util.Optional.of(result));
    }
}
