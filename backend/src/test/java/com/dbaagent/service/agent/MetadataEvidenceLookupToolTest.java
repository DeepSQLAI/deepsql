package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.ChatRetrievalContextService;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.PerformanceActionRepository;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.RetrievalIntent;
import com.dbaagent.service.RetrievedContextResult;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MetadataEvidenceLookupToolTest {

    @Mock private SchemaMetadataExecutor schemaMetadataExecutor;
    @Mock private PerformanceExecutor performanceExecutor;
    @Mock private ChatRetrievalContextService chatRetrievalContextService;
    @Mock private SchemaRelationshipVaultContextService schemaRelationshipVaultContextService;
    @Mock private SchemaRelationshipReasoningService schemaRelationshipReasoningService;
    @Mock private IndexRecommendationRepository indexRecommendationRepository;
    @Mock private PerformanceActionRepository performanceActionRepository;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final MetadataExplanationService metadataExplanationService = new MetadataExplanationService();

    @Test
    void execute_schemaRelationshipFallsBackToVaultDocumentationBeforeLiveMetadata() {
        MetadataEvidenceLookupTool tool = new MetadataEvidenceLookupTool(
            schemaMetadataExecutor,
            performanceExecutor,
            chatRetrievalContextService,
            schemaRelationshipVaultContextService,
            schemaRelationshipReasoningService,
            new AnswerVerificationService(),
            metadataExplanationService,
            indexRecommendationRepository,
            performanceActionRepository,
            keyColumnAnalysisRepository
        );

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.EXPLANATION,
            Map.of(),
            false,
            false,
            true,
            true
        );
        SchemaMetadata schema = new SchemaMetadata();
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "How are ORDERS and ORDER_DETAIL related?",
            "How are ORDERS and ORDER_DETAIL related?",
            null,
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            schema,
            "mysql"
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
        context.putMemory("metadataRequestScope", requestScope);

        when(schemaMetadataExecutor.execute(any(), any(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(Optional.empty());
        when(schemaRelationshipVaultContextService.loadExactContext(anyString(), any()))
            .thenReturn(Optional.empty());
        RetrievedContextResult retrievedContext = new RetrievedContextResult(
            "Relationship doc: ORDER_DETAIL rows belong to ORDERS and link through order_id/order_number depending on workflow.",
            "ORDERS is the order header. ORDER_DETAIL stores the child line items that belong to each order.",
            "",
            List.of(),
            Set.of("ORDERS", "ORDER_DETAIL"),
            RetrievalIntent.GENERAL,
            20,
            2,
            12,
            Map.of(),
            false,
            null
        );
        when(chatRetrievalContextService.buildScopedContext(anyString(), anyString(), any(), any()))
            .thenReturn(retrievedContext);
        when(schemaRelationshipReasoningService.reason(anyString(), any(), any()))
            .thenReturn(Optional.of(new SchemaRelationshipReasoningService.RelationshipReasoningResult(
                "`ORDER_DETAIL` is the child line-item table for `ORDERS`, so each order can have many detail rows attached to it.",
                List.of(
                    "`ORDERS` stores the order header record.",
                    "`ORDER_DETAIL` stores one or more child rows per order."
                ),
                List.of(
                    "Vault-backed schema docs describe `ORDER_DETAIL` as the child line-item table for `ORDERS`.",
                    "The retrieved relationship context references one-to-many order header to detail behavior."
                ),
                Set.of("ORDERS", "ORDER_DETAIL"),
                0.9,
                "mixed",
                "Retrieved documentation covered both tables directly."
            )));

        AgentToolResult result = tool.execute(
            new AgentPlanStep("step-1", "Lookup schema evidence", "metadata_evidence_lookup_tool", Map.of()),
            context
        );

        assertTrue(result.observation().summary().contains("child line-item table"));
        assertFalse(Boolean.TRUE.equals(context.getMemory("metadataNeedsLiveFallback")));
        VerifiedAnswer verifiedAnswer = context.getMemory("metadataVerifiedAnswer");
        assertNotNull(verifiedAnswer);
        assertEquals(EvidenceBundle.Source.COMPANY_KNOWLEDGE, verifiedAnswer.evidence().source());
        assertTrue(verifiedAnswer.verificationReport().accepted());
        verify(chatRetrievalContextService).buildScopedContext("conn-1", "How are ORDERS and ORDER_DETAIL related?", schema, List.of("ORDERS", "ORDER_DETAIL"));
    }

    @Test
    void execute_schemaRelationshipUsesExactVaultSemanticContextBeforeRetrievalFallback() {
        MetadataEvidenceLookupTool tool = new MetadataEvidenceLookupTool(
            schemaMetadataExecutor,
            performanceExecutor,
            chatRetrievalContextService,
            schemaRelationshipVaultContextService,
            schemaRelationshipReasoningService,
            new AnswerVerificationService(),
            metadataExplanationService,
            indexRecommendationRepository,
            performanceActionRepository,
            keyColumnAnalysisRepository
        );

        PromptIntent promptIntent = new PromptIntent(
            PromptIntent.Domain.SCHEMA,
            PromptIntent.TaskType.EXPLAIN,
            Set.of(PromptIntent.SubjectType.TABLE, PromptIntent.SubjectType.RELATIONSHIP),
            PromptIntent.RequestedOutput.EXPLANATION,
            Map.of(),
            false,
            false,
            true,
            true
        );
        AgentExecutionContext context = new AgentExecutionContext(
            "conn-1",
            "How are ORDERS and ORDER_DETAIL related?",
            "How are ORDERS and ORDER_DETAIL related?",
            null,
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            new SchemaMetadata(),
            "mysql"
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
        context.putMemory("metadataRequestScope", requestScope);

        when(schemaMetadataExecutor.execute(any(), any(), anyString(), anyString(), any(), any(), any()))
            .thenReturn(Optional.empty());
        RetrievedContextResult directVaultRetrievedContext = new RetrievedContextResult(
            "Exact semantic joins:\n- ORDER_DETAIL.order_id = ORDERS.id [CLASSIFIED_INFERRED, confidence=100]",
            "Exact schema documentation:\n- TABLE ORDERS: order header\n- TABLE ORDER_DETAIL: child line items",
            "",
            List.of(),
            Set.of("ORDERS", "ORDER_DETAIL"),
            RetrievalIntent.GENERAL,
            0,
            3,
            0,
            Map.of(),
            false,
            null
        );
        SchemaRelationshipVaultContextService.ExactRelationshipVaultContext directVaultContext =
            new SchemaRelationshipVaultContextService.ExactRelationshipVaultContext(
                directVaultRetrievedContext,
                List.of(
                    com.dbaagent.model.SemanticTableModel.builder()
                        .tableName("ORDERS")
                        .businessDescription("Order header")
                        .grainDescription("One row per order")
                        .build(),
                    com.dbaagent.model.SemanticTableModel.builder()
                        .tableName("ORDER_DETAIL")
                        .businessDescription("Child line items")
                        .grainDescription("One row per order detail")
                        .build()
                ),
                List.of(
                    com.dbaagent.model.SemanticJoinModel.builder()
                        .sourceTable("ORDER_DETAIL")
                        .sourceColumn("order_id")
                        .targetTable("ORDERS")
                        .targetColumn("id")
                        .evidenceSource("CLASSIFIED_INFERRED")
                        .build()
                ),
                List.of()
            );
        when(schemaRelationshipVaultContextService.loadExactContext(anyString(), any()))
            .thenReturn(Optional.of(directVaultContext));

        AgentToolResult result = tool.execute(
            new AgentPlanStep("step-1", "Lookup schema evidence", "metadata_evidence_lookup_tool", Map.of()),
            context
        );

        assertTrue(result.observation().summary().contains("ORDER_DETAIL.order_id = ORDERS.id"));
        assertFalse(Boolean.TRUE.equals(context.getMemory("metadataNeedsLiveFallback")));
        verify(schemaRelationshipVaultContextService).loadExactContext("conn-1", requestScope);
    }
}
