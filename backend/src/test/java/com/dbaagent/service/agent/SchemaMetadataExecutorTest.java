package com.dbaagent.service.agent;

import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.service.ChatContextAssembler;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.SchemaChangeTrackingService;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class SchemaMetadataExecutorTest {

    @Mock private ChatContextAssembler contextAssembler;
    @Mock private SchemaClassificationService schemaClassificationService;
    @Mock private TableClassificationRepository tableClassificationRepository;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private InferredTableRelationshipRepository inferredTableRelationshipRepository;
    @Mock private SchemaChangeRepository schemaChangeRepository;
    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;
    @Mock private SchemaChangeTrackingService schemaChangeTrackingService;

    private SchemaMetadataExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new SchemaMetadataExecutor(
            contextAssembler,
            schemaClassificationService,
            tableClassificationRepository,
            keyColumnAnalysisRepository,
            inferredTableRelationshipRepository,
            schemaChangeRepository,
            schemaSnapshotRepository,
            schemaChangeTrackingService,
            new AnswerVerificationService(),
            new MetadataExplanationService(),
            new ObjectMapper()
        );
    }

    @Test
    void execute_latestTablesAdded_fallsBackToSnapshotDiffWhenChangeHistoryIsEmpty() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");

        SchemaSnapshot previous = SchemaSnapshot.builder()
            .id("snap-1")
            .connectionId("conn-1")
            .capturedAt(LocalDateTime.of(2026, 4, 10, 2, 30))
            .schemaJson("{\"tables\":{\"CUSTOMERS\":{},\"PRODUCT_PRICING\":{}}}")
            .build();
        SchemaSnapshot latest = SchemaSnapshot.builder()
            .id("snap-2")
            .connectionId("conn-1")
            .capturedAt(LocalDateTime.of(2026, 4, 11, 2, 30))
            .schemaJson("{\"tables\":{\"CUSTOMERS\":{},\"PRODUCT_PRICING\":{},\"HOTEL_AUDIT\":{}}}")
            .build();

        SchemaChange tableAdded = SchemaChange.builder()
            .connectionId("conn-1")
            .changeType(SchemaChange.ChangeType.TABLE_ADDED)
            .objectType(SchemaChange.ObjectType.TABLE)
            .objectName("HOTEL_AUDIT")
            .severity(SchemaChange.Severity.INFO)
            .detectedAt(LocalDateTime.of(2026, 4, 11, 2, 30))
            .changeDetails(Map.of("tableName", "HOTEL_AUDIT"))
            .build();

        when(schemaChangeRepository.findTop50ByConnectionIdOrderByDetectedAtDesc("conn-1")).thenReturn(List.of());
        when(schemaSnapshotRepository.findTop2ByConnectionIdOrderByCapturedAtDesc("conn-1")).thenReturn(List.of(latest, previous));
        when(schemaChangeTrackingService.detectChanges(previous, latest)).thenReturn(List.of(tableAdded));

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.COMPARE,
            Set.of(PromptIntent.SubjectType.TABLE),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            false
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            ),
            "what are the latest tables added to the schema?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty()
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("HOTEL_AUDIT");
        assertThat(answer.get().answerContract().summary()).contains("latest schema snapshots");
        assertThat(answer.get().evidence().primaryRows()).hasSize(1);
        assertThat(answer.get().evidence().answerType()).isEqualTo("schema_change_summary");
    }

    @Test
    void execute_latestColumnsAdded_returnsVerifiedInsufficiencyWhenNotEnoughSnapshotsExist() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");

        SchemaSnapshot latest = SchemaSnapshot.builder()
            .id("snap-2")
            .connectionId("conn-1")
            .capturedAt(LocalDateTime.of(2026, 4, 11, 2, 30))
            .schemaJson("{\"tables\":{\"CUSTOMERS\":{}}}")
            .build();

        when(schemaChangeRepository.findTop50ByConnectionIdOrderByDetectedAtDesc("conn-1")).thenReturn(List.of());
        when(schemaSnapshotRepository.findTop2ByConnectionIdOrderByCapturedAtDesc("conn-1")).thenReturn(List.of(latest));

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.COMPARE,
            Set.of(PromptIntent.SubjectType.COLUMN),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            false
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            ),
            "what are the latest columns added to the schema?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty()
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().verificationReport().verifiedInsufficiency()).isTrue();
        assertThat(answer.get().answerContract().summary()).contains("at least two schema snapshots");
    }

    @Test
    void execute_schemaChangesWithinLastThreeDays_aggregatesAcrossSnapshotWindow() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");

        LocalDateTime now = LocalDateTime.now();
        SchemaSnapshot olderBaseline = SchemaSnapshot.builder()
            .id("snap-0")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(4))
            .schemaJson("{\"tables\":{\"users\":{}}}")
            .build();
        SchemaSnapshot dayThree = SchemaSnapshot.builder()
            .id("snap-1")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(3).plusHours(1))
            .schemaJson("{\"tables\":{\"users\":{},\"v2_auth_config\":{}}}")
            .build();
        SchemaSnapshot dayTwo = SchemaSnapshot.builder()
            .id("snap-2")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(2))
            .schemaJson("{\"tables\":{\"users\":{},\"v2_auth_config\":{\"columns\":[{\"name\":\"id\"},{\"name\":\"is_enabled\"}]}}}")
            .build();
        SchemaSnapshot dayOne = SchemaSnapshot.builder()
            .id("snap-3")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(1))
            .schemaJson("{\"tables\":{\"users\":{},\"v2_auth_config\":{\"columns\":[{\"name\":\"id\"},{\"name\":\"is_enabled\"}]}}}")
            .build();

        SchemaChange tableAdded = SchemaChange.builder()
            .connectionId("conn-1")
            .changeType(SchemaChange.ChangeType.TABLE_ADDED)
            .objectType(SchemaChange.ObjectType.TABLE)
            .objectName("v2_auth_config")
            .severity(SchemaChange.Severity.INFO)
            .build();
        SchemaChange columnAdded = SchemaChange.builder()
            .connectionId("conn-1")
            .changeType(SchemaChange.ChangeType.COLUMN_ADDED)
            .objectType(SchemaChange.ObjectType.COLUMN)
            .objectName("is_enabled")
            .tableName("v2_auth_config")
            .severity(SchemaChange.Severity.INFO)
            .build();

        when(schemaSnapshotRepository.findByConnectionIdOrderByCapturedAtDesc("conn-1"))
            .thenReturn(List.of(dayOne, dayTwo, dayThree, olderBaseline));
        when(schemaChangeTrackingService.detectChanges(olderBaseline, dayThree)).thenReturn(List.of(tableAdded));
        when(schemaChangeTrackingService.detectChanges(dayThree, dayTwo)).thenReturn(List.of(columnAdded));
        when(schemaChangeTrackingService.detectChanges(dayTwo, dayOne)).thenReturn(List.of());

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.COMPARE,
            Set.of(),
            PromptIntent.RequestedOutput.SUMMARY,
            Map.of(),
            false,
            false,
            true,
            false
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            ),
            "what are the schema changes in the last 3 days?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty()
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("v2_auth_config");
        assertThat(answer.get().answerContract().summary()).contains("is_enabled");
        assertThat(answer.get().answerContract().summary()).contains("last 3 days");
        assertThat(answer.get().evidence().primaryRows()).hasSize(2);
        verify(schemaChangeRepository, never()).findTop50ByConnectionIdOrderByDetectedAtDesc(anyString());
    }

    @Test
    void execute_columnsAddedWithinLastThreeDays_usesSnapshotWindowInsteadOfLatestPairOnly() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");

        LocalDateTime now = LocalDateTime.now();
        SchemaSnapshot olderBaseline = SchemaSnapshot.builder()
            .id("snap-0")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(4))
            .schemaJson("{\"tables\":{\"users\":{}}}")
            .build();
        SchemaSnapshot dayThree = SchemaSnapshot.builder()
            .id("snap-1")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(3).plusHours(1))
            .schemaJson("{\"tables\":{\"users\":{},\"v2_auth_config\":{\"columns\":[{\"name\":\"id\"}]}}}")
            .build();
        SchemaSnapshot dayTwo = SchemaSnapshot.builder()
            .id("snap-2")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(2))
            .schemaJson("{\"tables\":{\"users\":{},\"v2_auth_config\":{\"columns\":[{\"name\":\"id\"},{\"name\":\"is_enabled\"}]}}}")
            .build();
        SchemaSnapshot dayOne = SchemaSnapshot.builder()
            .id("snap-3")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(1))
            .schemaJson("{\"tables\":{\"users\":{},\"v2_auth_config\":{\"columns\":[{\"name\":\"id\"},{\"name\":\"is_enabled\"}]}}}")
            .build();

        SchemaChange columnAdded = SchemaChange.builder()
            .connectionId("conn-1")
            .changeType(SchemaChange.ChangeType.COLUMN_ADDED)
            .objectType(SchemaChange.ObjectType.COLUMN)
            .objectName("is_enabled")
            .tableName("v2_auth_config")
            .severity(SchemaChange.Severity.INFO)
            .build();

        when(schemaSnapshotRepository.findByConnectionIdOrderByCapturedAtDesc("conn-1"))
            .thenReturn(List.of(dayOne, dayTwo, dayThree, olderBaseline));
        when(schemaChangeTrackingService.detectChanges(olderBaseline, dayThree)).thenReturn(List.of());
        when(schemaChangeTrackingService.detectChanges(dayThree, dayTwo)).thenReturn(List.of(columnAdded));
        when(schemaChangeTrackingService.detectChanges(dayTwo, dayOne)).thenReturn(List.of());

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.COMPARE,
            Set.of(PromptIntent.SubjectType.COLUMN),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            false
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            ),
            "what columns are added in the last 3 days?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty()
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("is_enabled");
        assertThat(answer.get().answerContract().summary()).contains("last 3 days");
        assertThat(answer.get().evidence().primaryRows()).hasSize(1);
    }

    @Test
    void execute_columnsAddedWithinLastThreeDays_includesColumnsFromNewlyAddedTables() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");

        LocalDateTime now = LocalDateTime.now();
        SchemaSnapshot olderBaseline = SchemaSnapshot.builder()
            .id("snap-0")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(4))
            .schemaJson("{\"tables\":{\"users\":{}}}")
            .build();
        SchemaSnapshot dayTwo = SchemaSnapshot.builder()
            .id("snap-2")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(2))
            .schemaJson("""
                {"tables":{"users":{},"v2_auth_config":{"columns":[{"name":"id"},{"name":"tenant_id"},{"name":"enabled"}]}}}
                """)
            .build();
        SchemaSnapshot dayOne = SchemaSnapshot.builder()
            .id("snap-3")
            .connectionId("conn-1")
            .capturedAt(now.minusDays(1))
            .schemaJson("""
                {"tables":{"users":{},"v2_auth_config":{"columns":[{"name":"id"},{"name":"tenant_id"},{"name":"enabled"}]}}}
                """)
            .build();

        SchemaChange tableAdded = SchemaChange.builder()
            .id("chg-table")
            .connectionId("conn-1")
            .changeType(SchemaChange.ChangeType.TABLE_ADDED)
            .objectType(SchemaChange.ObjectType.TABLE)
            .objectName("v2_auth_config")
            .severity(SchemaChange.Severity.INFO)
            .build();

        when(schemaSnapshotRepository.findByConnectionIdOrderByCapturedAtDesc("conn-1"))
            .thenReturn(List.of(dayOne, dayTwo, olderBaseline));
        when(schemaChangeTrackingService.detectChanges(olderBaseline, dayTwo)).thenReturn(List.of(tableAdded));
        when(schemaChangeTrackingService.detectChanges(dayTwo, dayOne)).thenReturn(List.of());

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.COMPARE,
            Set.of(PromptIntent.SubjectType.COLUMN),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            false
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            ),
            "what columns are added in the last 3 days?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty()
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("v2_auth_config.id");
        assertThat(answer.get().answerContract().summary()).contains("v2_auth_config.tenant_id");
        assertThat(answer.get().answerContract().summary()).contains("v2_auth_config.enabled");
        assertThat(answer.get().evidence().primaryRows()).hasSize(3);
    }

    @Test
    void execute_tableCountAndLargestTables_answersBothParts() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");
        schema.setTotalTables(3L);
        schema.setTables(List.of(
            new TableMetadata("SMALL_TABLE", null, "table", 10L, 128L, List.of(), List.of()),
            new TableMetadata("LARGE_TABLE", null, "table", 10_000L, 2048L, List.of(), List.of()),
            new TableMetadata("MEDIUM_TABLE", null, "table", 500L, 1024L, List.of(), List.of())
        ));
        when(contextAssembler.formatRowCount(10_000L)).thenReturn("10K");
        when(contextAssembler.formatRowCount(500L)).thenReturn("500");
        when(contextAssembler.formatRowCount(10L)).thenReturn("10");
        when(contextAssembler.formatBytes(2048L)).thenReturn("2 KB");
        when(contextAssembler.formatBytes(1024L)).thenReturn("1 KB");
        when(contextAssembler.formatBytes(128L)).thenReturn("128 B");

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.LOOKUP,
            Set.of(PromptIntent.SubjectType.TABLE),
            PromptIntent.RequestedOutput.RANKING,
            Map.of(),
            false,
            false,
            true,
            false
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.SCHEMA
            ),
            "how many tables we have and what are the largest tables?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty()
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("**3 tables**");
        assertThat(answer.get().answerContract().summary()).contains("Largest tables by row count");
        assertThat(answer.get().answerContract().summary()).contains("LARGE_TABLE");
        assertThat(answer.get().answerContract().summary()).contains("10K");
        assertThat(answer.get().evidence().answerType()).isEqualTo("table_count_and_row_ranking");
        assertThat(answer.get().evidence().primaryRows()).hasSize(4);
    }

    @Test
    void execute_pairScopedRelationshipQuestion_returnsOnlyDirectPairEvidence() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");
        schema.setTables(List.of(
            new TableMetadata("ORDERS", null, "table", 100L, 0L, List.of(), List.of()),
            new TableMetadata("ORDER_DETAIL", null, "table", 400L, 0L, List.of(), List.of())
        ));

        InferredTableRelationship direct = InferredTableRelationship.builder()
            .connectionId("conn-1")
            .sourceTable("ORDERS")
            .sourceColumn("id")
            .targetTable("ORDER_DETAIL")
            .targetColumn("order_id")
            .confidenceScore(java.math.BigDecimal.valueOf(100))
            .joinCount(11)
            .build();
        InferredTableRelationship unrelated = InferredTableRelationship.builder()
            .connectionId("conn-1")
            .sourceTable("CUSTOM_ORDER_INVOICE_NUMBERS")
            .sourceColumn("order_id")
            .targetTable("ORDERS")
            .targetColumn("id")
            .confidenceScore(java.math.BigDecimal.valueOf(100))
            .joinCount(11)
            .build();

        when(inferredTableRelationshipRepository.findHighConfidenceRelationships("conn-1", java.math.BigDecimal.valueOf(25)))
            .thenReturn(List.of(unrelated, direct));

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            true
        );
        MetadataRequestScope requestScope = new MetadataRequestScope(
            MetadataRequestScope.Mode.ANALYTIC_METADATA,
            MetadataRequestScope.FactType.RELATIONSHIPS,
            List.of("ORDERS", "ORDER_DETAIL"),
            List.of(),
            true,
            true,
            "How are ORDERS and ORDER_DETAIL related?"
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS
            ),
            "How are ORDERS and ORDER_DETAIL related?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty(),
            requestScope
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("ORDERS.id").contains("ORDER_DETAIL.order_id");
        assertThat(answer.get().answerContract().summary()).doesNotContain("CUSTOM_ORDER_INVOICE_NUMBERS");
        assertThat(answer.get().evidence().supportingObjectNames()).containsExactlyInAnyOrder("ORDERS", "ORDER_DETAIL");
    }

    @Test
    void execute_explanatoryPairRelationshipQuestion_returnsInterpretationNotJustRawMetadata() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");
        schema.setTables(List.of(
            new TableMetadata("ORDERS", null, "table", 100L, 0L, List.of(), List.of()),
            new TableMetadata("ORDER_DETAIL", null, "table", 400L, 0L, List.of(), List.of())
        ));

        when(inferredTableRelationshipRepository.findHighConfidenceRelationships("conn-1", java.math.BigDecimal.valueOf(25)))
            .thenReturn(List.of(
                InferredTableRelationship.builder()
                    .connectionId("conn-1")
                    .sourceTable("ORDER_DETAIL")
                    .sourceColumn("order_id")
                    .targetTable("ORDERS")
                    .targetColumn("id")
                    .confidenceScore(java.math.BigDecimal.valueOf(100))
                    .joinCount(33)
                    .build()
            ));

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            true
        );
        MetadataRequestScope requestScope = new MetadataRequestScope(
            MetadataRequestScope.Mode.ANALYTIC_METADATA,
            MetadataRequestScope.FactType.RELATIONSHIPS,
            List.of("ORDERS", "ORDER_DETAIL"),
            List.of(),
            true,
            true,
            "How are ORDERS and ORDER_DETAIL related?",
            MetadataRequestScope.AnswerStyle.EXPLANATORY
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS
            ),
            "How are ORDERS and ORDER_DETAIL related?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty(),
            requestScope
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().answerContract().summary()).contains("links to");
        assertThat(answer.get().answerContract().summary()).contains("child table");
        assertThat(answer.get().answerContract().summary()).contains("Use this join condition");
        assertThat(answer.get().answerContract().summary()).contains("ORDER_DETAIL.order_id = ORDERS.id");
    }

    @Test
    void execute_pairScopedRelationshipQuestion_returnsVerifiedInsufficiencyWhenOnlyOneSidedMatchesExist() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("analytics_db");
        schema.setTables(List.of(
            new TableMetadata("ORDERS", null, "table", 100L, 0L, List.of(), List.of()),
            new TableMetadata("ORDER_DETAIL", null, "table", 400L, 0L, List.of(), List.of())
        ));
        schema.setRelationships(List.of(
            new RelationshipMetadata("fk_order_invoice", "CUSTOM_ORDER_INVOICE_NUMBERS", "order_id", "ORDERS", "id", "MANY_TO_ONE", "fk_order_invoice")
        ));

        when(inferredTableRelationshipRepository.findHighConfidenceRelationships("conn-1", java.math.BigDecimal.valueOf(25)))
            .thenReturn(List.of(
                InferredTableRelationship.builder()
                    .connectionId("conn-1")
                    .sourceTable("CUSTOM_ORDER_INVOICE_NUMBERS")
                    .sourceColumn("order_id")
                    .targetTable("ORDERS")
                    .targetColumn("id")
                    .confidenceScore(java.math.BigDecimal.valueOf(100))
                    .joinCount(11)
                    .build()
            ));

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.LIST,
            Map.of(),
            false,
            false,
            true,
            true
        );
        MetadataRequestScope requestScope = new MetadataRequestScope(
            MetadataRequestScope.Mode.ANALYTIC_METADATA,
            MetadataRequestScope.FactType.RELATIONSHIPS,
            List.of("ORDERS", "ORDER_DETAIL"),
            List.of(),
            true,
            true,
            "How are ORDERS and ORDER_DETAIL related?"
        );

        var answer = executor.execute(
            promptIntent,
            new ChatQuestionRoutingService.QuestionRoute(
                ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
                ChatQuestionRoutingService.BrainTopic.RELATIONSHIPS
            ),
            "How are ORDERS and ORDER_DETAIL related?",
            "conn-1",
            schema,
            ResolvedConversationContext.empty(),
            requestScope
        );

        assertThat(answer).isPresent();
        assertThat(answer.get().verificationReport().verifiedInsufficiency()).isTrue();
        assertThat(answer.get().answerContract().summary()).contains("ORDERS").contains("ORDER_DETAIL");
        assertThat(answer.get().answerContract().summary()).doesNotContain("CUSTOM_ORDER_INVOICE_NUMBERS");
    }
}
