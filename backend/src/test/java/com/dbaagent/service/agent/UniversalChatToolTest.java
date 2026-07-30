package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.BusinessRuleMemoryService;
import com.dbaagent.service.ChatRetrievalContextService;
import com.dbaagent.service.ChatContextAssembler;
import com.dbaagent.service.ConversationCarryoverDecision;
import com.dbaagent.service.FeedbackService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.RetrievalIntent;
import com.dbaagent.service.RetrievedContextResult;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SqlExecutionPipeline;
import com.dbaagent.service.pipeline.ColumnValueContext;
import com.dbaagent.service.pipeline.PipelineResult;
import com.dbaagent.service.pipeline.QueryGenerationPipeline;
import com.dbaagent.service.pipeline.ResolvedContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UniversalChatToolTest {

    @Mock private QueryExecutorService queryExecutorService;
    @Mock private QueryGenerationPipeline queryGenerationPipeline;
    @Mock private SqlExecutionPipeline sqlExecutionPipeline;
    @Mock private ChatContextAssembler contextAssembler;
    @Mock private FeedbackService feedbackService;
    @Mock private BusinessRuleMemoryService businessRuleMemoryService;
    @Mock private SemanticModelService semanticModelService;
    @Mock private ChatRetrievalContextService chatRetrievalContextService;
    @Mock private AnswerVerificationService answerVerificationService;
    @Mock private ChatModel chatModel;
    @Mock private ChatClient chatClient;

    private UniversalChatTool tool;

    @BeforeEach
    void setUp() {
        lenient().when(chatModel.call(any(Prompt.class))).thenThrow(new UnsupportedOperationException("chatModel.call should not be invoked directly in this unit test"));
        lenient().when(semanticModelService.findRelevantTables(anyString(), anyString(), anySet())).thenReturn(List.of());
        lenient().when(contextAssembler.determineNeededContext(anyString())).thenReturn(Set.of());
        lenient().when(contextAssembler.buildSchemaContext(anyString(), any(), anyString(), anySet())).thenReturn("schema");
        lenient().when(contextAssembler.buildPerformanceInsightsContext(anyString(), anySet(), anyString())).thenReturn("");
        lenient().when(contextAssembler.buildBrainContext(anyString())).thenReturn("");
        lenient().when(contextAssembler.buildClassificationContext(anyString())).thenReturn("");
        lenient().when(contextAssembler.buildDatabaseSpecificRules(anyString())).thenReturn("");
        lenient().doAnswer(invocation -> {
            Object[] args = invocation.getArguments();
            return new String[]{
                (String) args[0],
                (String) args[1],
                (String) args[2],
                (String) args[3],
                (String) args[4],
                (String) args[5],
                (String) args[6],
                (String) args[8],
                (String) args[9]
            };
        }).when(contextAssembler).applyTokenBudget(
            anyString(), anyString(), anyString(), anyString(), anyString(),
            anyString(), anyString(), anyString(), anyString(), anyString()
        );
        lenient().when(feedbackService.buildFeedbackContext(anyString())).thenReturn("");
        lenient().when(businessRuleMemoryService.buildGuardrailContext(anyList())).thenReturn("");
        lenient().when(businessRuleMemoryService.resolveApplicableGuardrails(anyString(), anyString(), any())).thenReturn(List.of());
        lenient().when(chatRetrievalContextService.detectRetrievalIntent(anyString())).thenReturn(RetrievalIntent.GENERAL);
        lenient().when(chatRetrievalContextService.buildContext(anyString(), anyString(), any()))
            .thenReturn(RetrievedContextResult.skipped(RetrievalIntent.GENERAL, "test"));
        lenient().when(queryGenerationPipeline.resolveContextOnly(any()))
            .thenReturn(new PipelineResult(null, null, false, ResolvedContext.empty(), ColumnValueContext.empty(), null, List.of(), 0L));
        lenient().when(sqlExecutionPipeline.extractAllSqlFromResponse(anyString())).thenReturn(List.of());
        lenient().when(answerVerificationService.verify(any(), any(), any()))
            .thenReturn(new VerificationReport(
                true,
                false,
                null,
                0.9,
                0.9,
                VerificationReport.SourceStrength.HIGH,
                VerificationReport.RecommendedFallback.NONE,
                List.of("verified in test")
            ));
        tool = new UniversalChatTool(
            queryExecutorService,
            queryGenerationPipeline,
            sqlExecutionPipeline,
            contextAssembler,
            feedbackService,
            businessRuleMemoryService,
            semanticModelService,
            chatRetrievalContextService,
            answerVerificationService,
            chatModel,
            new ByteArrayResource("You are a database reasoning agent.".getBytes()),
            true
        );
        ReflectionTestUtils.setField(tool, "chatClient", chatClient);
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCandidateTables_prefersEventLogTableForUsageDropPrompt() {
        SchemaMetadata schema = usageDropSchema();
        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("USER_BOOKINGS", "HOTEL"),
            Map.of(),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("what are the hotels most likely churn? means they had usage one month ago, but usage steeply dropped recently."), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts per reservation")
                    .timeColumns(List.of("booking_made_on"))
                    .metricColumns(List.of("booking_amount"))
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_LOGS")
                    .tableRole("EVENT_LOG")
                    .businessDescription("Usage activity events for hotels and users")
                    .timeColumns(List.of("event_occurred_at"))
                    .filterColumns(List.of(Map.of("column", "action_type")))
                    .build()
            ));

        List<TableMetadata> candidates = (List<TableMetadata>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveCandidateTables",
            "conn-1",
            "what are the hotels most likely churn? means they had usage one month ago, but usage steeply dropped recently.",
            schema,
            resolvedContext
        );

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().getName()).isEqualTo("USER_LOGS");
    }

    @Test
    void resolveTemporalContext_prefersEventLogTimestampForUsageDropPrompt() {
        SchemaMetadata schema = usageDropSchema();

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("what are the hotels most likely churn? means they had usage one month ago, but usage steeply dropped recently."), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_LOGS")
                    .tableRole("EVENT_LOG")
                    .businessDescription("Usage activity events for hotels and users")
                    .timeColumns(List.of("event_occurred_at", "created_at"))
                    .filterColumns(List.of(Map.of("column", "action_type")))
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts per reservation")
                    .timeColumns(List.of("booking_made_on"))
                    .metricColumns(List.of("booking_amount"))
                    .build()
            ));

        Object temporalResolution = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveTemporalContext",
            "conn-1",
            "what are the hotels most likely churn? means they had usage one month ago, but usage steeply dropped recently.",
            null,
            schema,
            ResolvedContext.empty()
        );

        assertNotNull(temporalResolution);
        Boolean needsClarification = ReflectionTestUtils.invokeMethod(temporalResolution, "needsClarification");
        String directive = ReflectionTestUtils.invokeMethod(temporalResolution, "directive");

        assertEquals(Boolean.FALSE, needsClarification);
        assertThat(directive).contains("USER_LOGS.event_occurred_at");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCandidateTables_prefersBaseFactTableForBookingCountPrompt() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("booking_count")),
            table("USER_BOOKINGS", col("id"), col("booking_amount"))
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("USER_BOOKINGS"),
            Map.of(),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("How many bookings are there?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("AGGREGATE")
                    .businessDescription("Derived booking summary")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Source of truth booking facts")
                    .build()
            ));

        List<TableMetadata> candidates = (List<TableMetadata>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveCandidateTables",
            "conn-1",
            "How many bookings are there?",
            schema,
            resolvedContext
        );

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().getName()).isEqualTo("USER_BOOKINGS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCandidateTables_prefersBaseMeasureFactForGroupedBookingAmountPrompt() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("hotel_id"), col("total_booking_amount")),
            table("USER_BOOKINGS", col("id"), col("hotel_id"), col("booking_amount"), col("booking_made_on")),
            table("HOTEL", col("id"), col("name"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("What is the total booking amount per hotel?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("FACT")
                    .businessDescription("Booking summary per hotel")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Source of truth booking facts")
                    .build()
            ));

        List<TableMetadata> candidates = (List<TableMetadata>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveCandidateTables",
            "conn-1",
            "What is the total booking amount per hotel?",
            schema,
            ResolvedContext.empty()
        );

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().getName()).isEqualTo("USER_BOOKINGS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveSemanticEntity_prefersSourceOfTruthFactOverSummaryWhenBothAreInScope() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("hotel_id"), col("total_booking_amount")),
            table("USER_BOOKINGS", col("id"), col("hotel_id"), col("booking_amount"), col("booking_made_on")),
            table("HOTEL", col("id"), col("name"))
        ));

        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("FACT")
                    .businessDescription("Booking summary per hotel")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Source of truth booking facts")
                    .build()
            ));

        @SuppressWarnings("unchecked")
        java.util.Optional<Object> semanticEntity = (java.util.Optional<Object>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSemanticEntity",
            "conn-1",
            "What is the total booking amount per hotel?",
            schema,
            new ResolvedContext(
                List.of("USER_BOOKINGS", "HOTEL", "NR_BOOKING"),
                Map.of(),
                List.of(),
                List.of("NR_BOOKING.hotel_id = HOTEL.id"),
                ResolvedContext.Confidence.HIGH
            ),
            Set.of(),
            "USER_BOOKINGS"
        );

        assertThat(semanticEntity).isPresent();
        Object chosenEntity = semanticEntity.orElseThrow();
        TableMetadata chosenTable = ReflectionTestUtils.invokeMethod(chosenEntity, "table");
        assertThat(chosenTable.getName()).isEqualTo("USER_BOOKINGS");
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveSemanticEntity_includesPreferredSourceOfTruthTableEvenWhenResolvedFocusOnlyContainsSummaryTable() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("status"), col("booking_made_on")),
            table("USER_BOOKINGS", col("id"), col("booking_status"), col("booking_made_on"))
        ));

        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .businessDescription("Denormalized booking snapshot")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Core booking facts with booking lifecycle attributes")
                    .build()
            ));

        java.util.Optional<Object> semanticEntity = (java.util.Optional<Object>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSemanticEntity",
            "conn-1",
            "How many bookings per booking status in the last 30 days?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of("NR_BOOKING", List.of("status", "booking_made_on")),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            ),
            Set.of(),
            "USER_BOOKINGS"
        );

        assertThat(semanticEntity).isPresent();
        Object chosenEntity = semanticEntity.orElseThrow();
        TableMetadata chosenTable = ReflectionTestUtils.invokeMethod(chosenEntity, "table");
        assertThat(chosenTable.getName()).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void resolveSourceOfTruthDecision_prefersTableWithResolvedRawMeasureEvidence() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("hotel_id"), col("total_booking_amount")),
            table("USER_BOOKINGS", col("id"), col("hotel_id"), col("booking_amount")),
            table("HOTEL", col("id"), col("name"))
        ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "What is the total booking amount per hotel?",
            schema,
            new ResolvedContext(
                List.of("USER_BOOKINGS", "HOTEL", "NR_BOOKING"),
                Map.of(
                    "USER_BOOKINGS", List.of("hotel_id", "booking_amount"),
                    "HOTEL", List.of("id", "name")
                ),
                List.of(),
                List.of("NR_BOOKING.hotel_id = HOTEL.id"),
                ResolvedContext.Confidence.HIGH
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        String directive = ReflectionTestUtils.invokeMethod(decision, "directive");

        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
        assertThat(directive).contains("USER_BOOKINGS");
        assertThat(directive).contains("raw fact table");
    }

    @Test
    void resolveSourceOfTruthDecision_considersSemanticAlternativesWhenResolvedContextHasOneSummaryTable() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("booking_source"), col("booking_count")),
            table("USER_BOOKINGS", col("id"), col("booking_source"), col("booking_amount"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("How many bookings per booking source?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("AGGREGATE")
                    .businessDescription("Booking summary by source")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts per reservation")
                    .build()
            ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "How many bookings per booking source?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of("NR_BOOKING", List.of("booking_source")),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void resolveSourceOfTruthDecision_prefersFactRoleForBookingStatusCountsAfterSchemaTurn() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("status"), col("booking_made_on")),
            table("USER_BOOKINGS", col("id"), col("booking_status"), col("booking_made_on"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("How many bookings per booking status in the last 30 days?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .businessDescription("Denormalized booking snapshot")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts per reservation")
                    .build()
            ));
        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .build()
            ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "How many bookings per booking status in the last 30 days?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of("NR_BOOKING", List.of("status", "booking_made_on")),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void resolveSourceOfTruthDecision_prefersDirectCustomerIdentityColumnsForTopCustomersPrompt() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("hotel_id"), col("contact_email"), col("total_booking_amount"), col("booking_made_on")),
            table("USER_BOOKINGS", col("id"), col("booking_amount"), col("booking_made_on"), col("user_name"), col("user_email")),
            table("HOTEL", col("id"), col("name"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("Show top 10 customers by total booking amount in the last 30 days with their names and emails."), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .businessDescription("Denormalized booking snapshot")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts with user identity columns")
                    .build()
            ));
        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .build()
            ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "Show top 10 customers by total booking amount in the last 30 days with their names and emails.",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING", "HOTEL", "USER_BOOKINGS"),
                Map.of(
                    "NR_BOOKING", List.of("contact_email", "total_booking_amount", "booking_made_on"),
                    "HOTEL", List.of("name"),
                    "USER_BOOKINGS", List.of("booking_amount", "user_name", "user_email", "booking_made_on")
                ),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.HIGH
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void resolveSourceOfTruthDecision_prefersCompositeBusinessFieldOverGenericSnapshotField() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING", col("status"), col("source"), col("booking_made_on")),
            table("USER_BOOKINGS", col("id"), col("booking_status"), col("booking_source"), col("booking_made_on"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("How many bookings per booking source?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .businessDescription("Denormalized booking snapshot")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Core booking facts with booking lifecycle attributes")
                    .build()
            ));
        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .build()
            ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "How many bookings per booking source?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of("NR_BOOKING", List.of("source")),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void resolveSourceOfTruthDecision_doesNotOverweightGenericResolvedAliasWhenFactHasBusinessQualifiedColumns() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING",
                col("status"),
                col("source"),
                col("booking_made_on"),
                col("total_booking_amount")),
            table("USER_BOOKINGS",
                col("id"),
                col("booking_status"),
                col("booking_source"),
                col("booking_amount"),
                col("booking_made_on"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("How many bookings per booking status in the last 30 days?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .businessDescription("Denormalized booking snapshot")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Core booking facts with booking lifecycle attributes")
                    .build()
            ));
        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .build()
            ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "How many bookings per booking status in the last 30 days?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of("NR_BOOKING", List.of("status", "booking_made_on")),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void resolveSourceOfTruthDecision_prefersIdentityRichFactOverSnapshotWithContactAliasAndTotals() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("NR_BOOKING",
                col("hotel_id"),
                col("contact_person"),
                col("contact_email"),
                col("total_booking_amount"),
                col("booking_made_on")),
            table("USER_BOOKINGS",
                col("id"),
                col("hotel_id"),
                col("booking_amount"),
                col("booking_made_on"),
                col("user_name"),
                col("user_email")),
            table("HOTEL", col("id"), col("name"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("Show top 10 customers by total booking amount in the last 30 days with their names and emails."), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .businessDescription("Denormalized booking snapshot")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts with user identity columns")
                    .build()
            ));
        when(semanticModelService.getSemanticTables(eq("conn-1"), anyList()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("NR_BOOKING")
                    .tableRole("DIMENSION")
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .build()
            ));

        Object decision = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSourceOfTruthDecision",
            "conn-1",
            "Show top 10 customers by total booking amount in the last 30 days with their names and emails.",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING", "HOTEL"),
                Map.of(
                    "NR_BOOKING", List.of("contact_email", "total_booking_amount", "booking_made_on"),
                    "HOTEL", List.of("name")
                ),
                List.of(),
                List.of("NR_BOOKING.hotel_id = HOTEL.id"),
                ResolvedContext.Confidence.HIGH
            ),
            Set.of()
        );

        String chosenTable = ReflectionTestUtils.invokeMethod(decision, "tableName");
        assertThat(chosenTable).isEqualTo("USER_BOOKINGS");
    }

    @Test
    void isMeasureLikeColumn_doesNotTreatTemporalNumericFieldsAsBusinessMeasures() {
        ColumnMetadata checkin = new ColumnMetadata("checkin", "bigint", null, true, false, null, 1);
        ColumnMetadata bookingAmount = new ColumnMetadata("booking_amount", "double", null, true, false, null, 1);

        Boolean checkinMeasure = ReflectionTestUtils.invokeMethod(tool, "isMeasureLikeColumn", checkin);
        Boolean bookingAmountMeasure = ReflectionTestUtils.invokeMethod(tool, "isMeasureLikeColumn", bookingAmount);

        assertThat(checkinMeasure).isFalse();
        assertThat(bookingAmountMeasure).isTrue();
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCandidateTables_prefersExplicitHotelServicesTableOverSemanticDrift() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("SERVICE_AMOUNT", col("hotel_id"), col("amount"), col("type")),
            table("HOTEL_SERVICES", col("hotel_id"), col("service_amount"), col("service_description")),
            table("HOTEL", col("id"), col("name"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("Show hotel services with their amounts"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("SERVICE_AMOUNT")
                    .tableRole("FACT")
                    .businessDescription("Service amount ledger")
                    .build()
            ));

        List<TableMetadata> candidates = (List<TableMetadata>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveCandidateTables",
            "conn-1",
            "Show hotel services with their amounts",
            schema,
            ResolvedContext.empty()
        );

        assertThat(candidates).isNotEmpty();
        assertThat(candidates.getFirst().getName()).isEqualTo("HOTEL_SERVICES");
    }

    @Test
    void resolveTemporalContext_prefersBusinessTransactionTimestampOverStaleUpdatedColumn() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("USER_BOOKINGS",
                col("id"),
                col("booking_made_on", "bigint"),
                col("last_updated", "timestamp"),
                col("actual_checkin", "timestamp"),
                col("actual_checkout", "timestamp"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("How many bookings were made per month?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("USER_BOOKINGS")
                    .tableRole("FACT")
                    .businessDescription("Booking facts per reservation")
                    .timeColumns(List.of("last_updated", "booking_made_on", "actual_checkin"))
                    .temporalSemantics(List.of(
                        Map.of("column", "last_updated", "label", "update time", "score", 96),
                        Map.of("column", "booking_made_on", "label", "booking/transaction time", "score", 92)
                    ))
                    .build()
            ));

        Object temporalResolution = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveTemporalContext",
            "conn-1",
            "How many bookings were made per month?",
            null,
            schema,
            new ResolvedContext(List.of("USER_BOOKINGS"), Map.of(), List.of(), List.of(), ResolvedContext.Confidence.HIGH)
        );

        assertNotNull(temporalResolution);
        Boolean needsClarification = ReflectionTestUtils.invokeMethod(temporalResolution, "needsClarification");
        String directive = ReflectionTestUtils.invokeMethod(temporalResolution, "directive");

        assertEquals(Boolean.FALSE, needsClarification);
        assertThat(directive).contains("USER_BOOKINGS.booking_made_on");
        assertThat(directive).doesNotContain("USER_BOOKINGS.last_updated");
    }

    @Test
    void execute_destructivePrompt_refusesWithoutSql() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(table("USER_BOOKINGS", col("id"), col("booking_made_on"))));

        AgentPlanStep step = new AgentPlanStep("universal-chat", "Resolve", "universal_chat_tool",
            Map.of("routeType", "GENERAL", "dataRequest", true));
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "Delete bookings older than 2024",
            "Delete bookings older than 2024",
            "chat-1",
            List.of(),
            ResolvedConversationContext.empty(),
            schema,
            "mysql"
        );

        AgentToolResult toolResult = tool.execute(step, context);

        assertNull(toolResult.executedSql());
        assertNull(toolResult.queryResult());
        assertTrue(toolResult.observation().summary().toLowerCase().contains("read-only"));
        verifyNoInteractions(queryExecutorService, queryGenerationPipeline, sqlExecutionPipeline);
    }

    @Test
    void execute_fullQueryFollowUp_reusesPriorSqlFromVaultContextWithoutLiveFallback() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(table("USER_BOOKINGS", col("id"), col("booking_made_on"))));

        AgentPlanStep step = new AgentPlanStep("universal-chat", "Show full query", "universal_chat_tool",
            Map.of("routeType", "BI_QUERY", "dataRequest", true));
        ResolvedConversationContext resolvedConversationContext = new ResolvedConversationContext(
            "ctx-1",
            "BRAIN_METADATA",
            "COMPLETED",
            "what is the #1 slow query?",
            "Prior answer identified the top slow query.",
            Map.of(),
            List.of(),
            Map.of(),
            null,
            List.of(new AgentExecutionContext.ConversationTurn(
                "assistant",
                "### Your Slowest Query\n\n**Query:**\n```sql\nSELECT * FROM USER_BOOKINGS WHERE hotel_id = 42\n```"
            )),
            0.92
        );
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "show me this full query",
            "show me this full query",
            "chat-1",
            List.of(),
            resolvedConversationContext,
            schema,
            "mysql"
        );
        context.putMemory("conversationCarryoverDecision", new ConversationCarryoverDecision(
            ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE,
            "ctx-1",
            "BRAIN_METADATA",
            "COMPLETED",
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            0.95d,
            "User is asking to see the same query in full."
        ));

        AgentToolResult toolResult = tool.execute(step, context);

        assertThat(toolResult.observation().summary()).contains("Returned the prior full SQL text");
        assertThat((String) context.getMemory("universalMessage")).contains("SELECT * FROM USER_BOOKINGS WHERE hotel_id = 42");
        AnswerContract answerContract = context.getMemory("verifiedAnswerContract");
        assertThat(answerContract.executedSql()).isEqualTo("SELECT * FROM USER_BOOKINGS WHERE hotel_id = 42");
        verifyNoInteractions(queryExecutorService);
    }

    @Test
    void execute_partialCompanyKnowledgeJoinGap_usesHintButStillReasons() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("ACCOUNTS", col("id"), col("account_billing_interval_months")),
            table("HOTEL_PRICING", col("amount"))
        ));

        RetrievedContextResult retrievedContext = new RetrievedContextResult(
            "",
            "Company rule for MRR",
            "coverage=PARTIAL joins=MISSING",
            List.of(
                CompanyKnowledgeEntry.builder()
                    .connectionId("conn-1")
                    .title("MRR calculation")
                    .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
                    .content("Use HOTEL_PRICING.amount / ACCOUNTS.account_billing_interval_months")
                    .linkedTables(List.of("ACCOUNTS"))
                    .linkedColumns(List.of("ACCOUNTS.account_billing_interval_months"))
                    .mentionedTables(List.of("HOTEL_PRICING", "ACCOUNTS"))
                    .mentionedColumns(List.of("ACCOUNTS.account_billing_interval_months", "HOTEL_PRICING.amount"))
                    .unlinkedMentions(List.of("HOTEL_PRICING", "HOTEL_PRICING.amount"))
                    .coverageStatus("PARTIAL")
                    .joinCoverageStatus("MISSING")
                    .build()
            ),
            Set.of("ACCOUNTS", "HOTEL_PRICING"),
            RetrievalIntent.BUSINESS_MEANING,
            20,
            1,
            10,
            Map.of(),
            false,
            null
        );

        when(chatRetrievalContextService.buildContext(eq("conn-1"), anyString(), any())).thenReturn(retrievedContext);
        mockMessageResponse("Which validated join path should define MRR between ACCOUNTS and HOTEL_PRICING?");

        AgentPlanStep step = new AgentPlanStep("universal-chat", "Resolve", "universal_chat_tool",
            Map.of("routeType", "BI_QUERY", "dataRequest", true));
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "What is the total MRR we have?",
            "What is the total MRR we have?",
            "chat-1",
            List.of(),
            ResolvedConversationContext.empty(),
            schema,
            "mysql"
        );

        AgentToolResult toolResult = tool.execute(step, context);

        assertThat(toolResult.observation().summary()).contains("Answered without SQL using bounded schema-aware reasoning");
        assertThat((String) context.getMemory("universalMessage"))
            .contains("Which validated join path should define MRR between ACCOUNTS and HOTEL_PRICING?");
        assertNull(toolResult.executedSql());
        verify(chatClient).prompt();
        verify(sqlExecutionPipeline).extractAllSqlFromResponse(anyString());
        verifyNoInteractions(queryExecutorService);
    }

    @Test
    void execute_conflictingCompanyKnowledge_ignoresInvalidBitsAndKeepsReasoning() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("ACCOUNTS", col("id"), col("account_billing_interval_months"), col("account_status")),
            table("HOTEL_PRICING", col("account_id"), col("amount"))
        ));

        RetrievedContextResult retrievedContext = new RetrievedContextResult(
            "",
            "MRR note: use HOTEL_PRICING.amount / ACCOUNTS.account_billing_interval_months. For active properties use ACCOUNTS.Property_status.",
            "coverage=CONFLICTING joins=VERIFIED invalid=ACCOUNTS.Property_status",
            List.of(
                CompanyKnowledgeEntry.builder()
                    .connectionId("conn-1")
                    .title("MRR calculation")
                    .entryType(CompanyKnowledgeEntry.EntryType.BUSINESS_RULE)
                    .content("Use HOTEL_PRICING.amount / ACCOUNTS.account_billing_interval_months. For active properties use ACCOUNTS.Property_status.")
                    .linkedTables(List.of("ACCOUNTS", "HOTEL_PRICING"))
                    .linkedColumns(List.of("ACCOUNTS.account_billing_interval_months", "HOTEL_PRICING.amount"))
                    .mentionedTables(List.of("ACCOUNTS", "HOTEL_PRICING"))
                    .mentionedColumns(List.of("ACCOUNTS.account_billing_interval_months", "HOTEL_PRICING.amount"))
                    .invalidMentions(List.of("ACCOUNTS.Property_status"))
                    .coverageStatus("CONFLICTING")
                    .joinCoverageStatus("VERIFIED")
                    .build()
            ),
            Set.of("ACCOUNTS", "HOTEL_PRICING"),
            RetrievalIntent.BUSINESS_MEANING,
            20,
            1,
            10,
            Map.of(),
            false,
            null
        );

        when(chatRetrievalContextService.buildContext(eq("conn-1"), anyString(), any())).thenReturn(retrievedContext);
        mockMessageResponse("I need one clarification before I run SQL safely: which column should I use for active properties while I calculate MRR?");

        AgentPlanStep step = new AgentPlanStep("universal-chat", "Resolve", "universal_chat_tool",
            Map.of("routeType", "BI_QUERY", "dataRequest", true));
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "What is the total MRR we have for active properties?",
            "What is the total MRR we have for active properties?",
            "chat-1",
            List.of(),
            ResolvedConversationContext.empty(),
            schema,
            "mysql"
        );

        AgentToolResult toolResult = tool.execute(step, context);

        assertThat(toolResult.observation().summary()).contains("Answered without SQL using bounded schema-aware reasoning");
        assertThat((String) context.getMemory("universalMessage"))
            .contains("which column should I use for active properties while I calculate MRR?");
        assertThat((String) context.getMemory("universalMessage")).doesNotContain("fix your company docs");
        assertNull(toolResult.executedSql());
        verify(chatClient).prompt();
        verify(sqlExecutionPipeline).extractAllSqlFromResponse(anyString());
        verifyNoInteractions(queryExecutorService);
    }

    @Test
    void storeMessage_withPromptIntent_allowsNullVerificationFieldsForCompletedAnswers() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.BI,
            PromptIntent.TaskType.SQL_QUERY,
            Set.of(PromptIntent.SubjectType.METRIC),
            PromptIntent.RequestedOutput.SQL_RESULT,
            Map.of(),
            true,
            false,
            false,
            false
        );
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "how many active hotels we have?",
            "how many active hotels we have?",
            "chat-1",
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            schema,
            "mysql"
        );

        AgentToolResult result = ReflectionTestUtils.invokeMethod(
            tool,
            "storeMessage",
            context,
            "task-1",
            "Calculate active hotels",
            AgentTaskKind.DATA_QUERY,
            List.of(),
            "COMPLETED",
            "There are 42 active hotels.",
            null,
            List.of("SELECT COUNT(*) FROM HOTEL"),
            0.9d,
            "Generated, validated, and executed SQL successfully",
            Map.of("generatedSql", true)
        );

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> verification = ((AgentTaskResult) context.taskResults().getFirst()).derivedValues() != null
            ? (Map<String, Object>) ((AgentTaskResult) context.taskResults().getFirst()).derivedValues().get("verificationReport")
            : null;
        assertNotNull(verification);
        assertTrue(verification.containsKey("failureReason"));
        assertNull(verification.get("failureReason"));
    }

    @Test
    void storeMessage_withPromptIntent_allowsNullVerificationFieldsForClarifications() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.BI,
            PromptIntent.TaskType.SQL_QUERY,
            Set.of(PromptIntent.SubjectType.METRIC),
            PromptIntent.RequestedOutput.SQL_RESULT,
            Map.of(),
            true,
            false,
            false,
            false
        );
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "how many active hotels we have?",
            "how many active hotels we have?",
            "chat-1",
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            schema,
            "mysql"
        );

        AgentToolResult result = ReflectionTestUtils.invokeMethod(
            tool,
            "storeMessage",
            context,
            "task-1",
            "Calculate active hotels",
            AgentTaskKind.DATA_QUERY,
            List.of(),
            "CLARIFICATION",
            "I need a bit more detail to verify the exact count.",
            null,
            List.of(),
            0.71d,
            "Prompt clarification policy stopped early",
            Map.of("clarification", true)
        );

        assertNotNull(result);
        @SuppressWarnings("unchecked")
        Map<String, Object> evidence = ((AgentTaskResult) context.taskResults().getFirst()).derivedValues() != null
            ? (Map<String, Object>) ((AgentTaskResult) context.taskResults().getFirst()).derivedValues().get("evidenceBundle")
            : null;
        assertNotNull(evidence);
        assertTrue(evidence.containsKey("insufficiencyMessage"));
        assertEquals("The universal chat flow did not reach a fully verified answer state.", evidence.get("insufficiencyMessage"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void resolveCandidateTables_prefersPopulatedBookingFactTableOverCaseConflictAndMetadataTables() {
        SchemaMetadata schema = bookingRevenueSchema();
        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("DEMAND_TREND_HOTEL", "HOTEL_REFERRALS", "HOTEL"),
            Map.of(),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        List<TableMetadata> candidates = (List<TableMetadata>) ReflectionTestUtils.invokeMethod(
            tool,
            "resolveCandidateTables",
            "conn-1",
            "what is the google hotel ads revenue for March month?",
            schema,
            resolvedContext
        );

        assertNotNull(candidates);
        assertFalse(candidates.isEmpty());
        assertEquals("USER_BOOKINGS", candidates.getFirst().getName());
        assertTrue(candidates.stream().map(TableMetadata::getName).noneMatch("user_bookings"::equals));
    }

    @Test
    void resolveTemporalContext_prefersBookingMadeOnForGoogleHotelAdsRevenue() {
        SchemaMetadata schema = bookingRevenueSchema();
        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("DEMAND_TREND_HOTEL", "HOTEL_REFERRALS", "HOTEL"),
            Map.of(),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        Object temporalResolution = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveTemporalContext",
            "conn-1",
            "what is the google hotel ads revenue for March month?",
            null,
            schema,
            resolvedContext
        );

        assertNotNull(temporalResolution);
        Boolean needsClarification = ReflectionTestUtils.invokeMethod(temporalResolution, "needsClarification");
        String directive = ReflectionTestUtils.invokeMethod(temporalResolution, "directive");

        assertEquals(Boolean.FALSE, needsClarification);
        assertNotNull(directive);
        assertTrue(directive.contains("USER_BOOKINGS.booking_made_on"));
    }

    @Test
    void resolveTemporalContext_prefersSemanticHotelSubscriptionStartDateWithoutClarification() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("hotel", 0L, col("id"), col("created_at")),
            table("HOTEL", 1_500L, col("id"), col("subscription_start_date"), col("last_updated")),
            table("HOTEL_SERVICES", 50_000L, col("hotel_id"), col("last_updated")),
            table("USER_BOOKINGS", 9_419_333L, col("hotel_id"), col("booking_made_on")),
            table("USER_CANCELLATIONS", 82_000L, col("hotel_id"), col("cancel_date"), col("last_updated"))
        ));
        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("HOTEL_SERVICES", "USER_BOOKINGS"),
            Map.of(),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );
        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("how many hotels are onboarded in the last 3 days?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("HOTEL")
                    .businessDescription("Core hotel entity. subscription_start_date marks when a hotel subscription or contract starts.")
                    .timeColumns(List.of("subscription_start_date", "last_updated"))
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("HOTEL_SERVICES")
                    .businessDescription("Operational service configuration for each hotel")
                    .timeColumns(List.of("last_updated"))
                    .build()
            ));

        Object temporalResolution = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveTemporalContext",
            "conn-1",
            "how many hotels are onboarded in the last 3 days?",
            null,
            schema,
            resolvedContext
        );

        assertNotNull(temporalResolution);
        Boolean needsClarification = ReflectionTestUtils.invokeMethod(temporalResolution, "needsClarification");
        String directive = ReflectionTestUtils.invokeMethod(temporalResolution, "directive");

        assertEquals(Boolean.FALSE, needsClarification);
        assertThat(directive)
            .contains("Treat `HOTEL` as the primary business entity")
            .contains("HOTEL.subscription_start_date");
    }

    @Test
    void resolveEntityFilterContext_prefersEntityScopedStatusColumnWithoutCrossTableClarification() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("HOTEL", 1_500L, col("id"), col("onboarding_status"), col("subscription_start_date")),
            table("HOTEL_PRICING", 5_000L, col("hotel_id"), col("property_status")),
            table("HOTEL_SERVICES", 50_000L, col("hotel_id"), col("service_status"))
        ));

        when(semanticModelService.findRelevantTables(eq("conn-1"), eq("how many active hotels we have?"), anySet()))
            .thenReturn(List.of(
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("HOTEL")
                    .businessDescription("Core hotel entity. onboarding_status tracks whether the property is active or inactive.")
                    .filterColumns(List.of(Map.of("column", "onboarding_status", "businessTerms", "active, inactive")))
                    .build(),
                SemanticTableModel.builder()
                    .connectionId("conn-1")
                    .tableName("HOTEL_PRICING")
                    .businessDescription("Pricing state for each hotel")
                    .filterColumns(List.of(Map.of("column", "property_status")))
                    .build()
            ));

        Object semanticEntityOpt = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveSemanticEntity",
            "conn-1",
            "how many active hotels we have?",
            schema,
            ResolvedContext.empty()
        );
        Object semanticEntity = ((java.util.Optional<?>) semanticEntityOpt).orElse(null);
        Object filterResolution = ReflectionTestUtils.invokeMethod(
            tool,
            "resolveEntityFilterContext",
            "how many active hotels we have?",
            semanticEntity
        );

        assertNotNull(filterResolution);
        Boolean needsClarification = ReflectionTestUtils.invokeMethod(filterResolution, "needsClarification");
        String directive = ReflectionTestUtils.invokeMethod(filterResolution, "directive");

        assertEquals(Boolean.FALSE, needsClarification);
        assertThat(directive)
            .contains("Treat `HOTEL` as the primary business entity")
            .contains("HOTEL.onboarding_status");
    }

    private TableMetadata table(String name, ColumnMetadata... columns) {
        return new TableMetadata(name, null, "table", null, null, List.of(columns), List.of());
    }

    private TableMetadata table(String name, long rowCount, ColumnMetadata... columns) {
        return new TableMetadata(name, null, "table", rowCount, null, List.of(columns), List.of());
    }

    private ColumnMetadata col(String name) {
        return new ColumnMetadata(name, "varchar", 255L, true, false, null, 1);
    }

    private ColumnMetadata col(String name, String dataType) {
        return new ColumnMetadata(name, dataType, 255L, true, false, null, 1);
    }

    private SchemaMetadata usageDropSchema() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("USER_LOGS", 4_500_000L,
                new ColumnMetadata("hotel_id", "varchar", 255L, true, false, null, 1),
                new ColumnMetadata("event_occurred_at", "timestamp", null, true, false, null, 2),
                new ColumnMetadata("action_type", "varchar", 255L, true, false, null, 3),
                new ColumnMetadata("user_id", "varchar", 255L, true, false, null, 4)
            ),
            table("USER_BOOKINGS", 9_419_333L,
                new ColumnMetadata("hotel_id", "varchar", 255L, true, false, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2),
                new ColumnMetadata("booking_made_on", "bigint", null, true, false, null, 3)
            ),
            table("HOTEL", 1_500L,
                new ColumnMetadata("id", "varchar", 255L, true, false, null, 1),
                new ColumnMetadata("subscription_start_date", "timestamp", null, true, false, null, 2)
            )
        ));
        return schema;
    }

    private SchemaMetadata bookingRevenueSchema() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("user_bookings", 0L,
                col("hotel_id"),
                col("created_at")
            ),
            table("USER_BOOKINGS", 9_419_333L,
                col("hotel_id"),
                col("booking_amount"),
                col("booking_source"),
                col("booking_made_on")
            ),
            table("HOTEL_REFERRALS", 50_000L,
                col("hotel_id"),
                col("referral_created_at"),
                col("referral_source")
            ),
            table("DEMAND_TREND_HOTEL", 150_000L,
                col("hotel_id"),
                col("created_at")
            ),
            table("HOTEL", 1_500L,
                col("id"),
                col("subscription_start_date")
            )
        ));
        return schema;
    }

    private void mockMessageResponse(String content) {
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.messages(anyList())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(content);
    }
}
