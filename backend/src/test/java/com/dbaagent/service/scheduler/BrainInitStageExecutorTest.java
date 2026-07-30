package com.dbaagent.service.scheduler;

import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.InitStage;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.ConnectionInitHistoryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SchemaDescriptionService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.SchemaSnapshotService;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.VectorSearchService;
import com.dbaagent.service.brain.analysis.ColumnProfilingService;
import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import com.dbaagent.service.brain.keycolumn.JoinRelationshipInferenceService;
import com.dbaagent.service.brain.keycolumn.KeyColumnAnalysisService;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BrainInitStageExecutorTest {

    @Mock private TrainingJobService trainingJobService;
    @Mock private SchemaDescriptionService schemaDescriptionService;
    @Mock private ColumnProfilingService columnProfilingService;
    @Mock private ConnectionService connectionService;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private SchemaSnapshotService schemaSnapshotService;
    @Mock private QueryExecutorService queryExecutorService;
    @Mock private KeyColumnAnalysisService keyColumnAnalysisService;
    @Mock private ColumnValueCollectionService columnValueCollectionService;
    @Mock private JoinRelationshipInferenceService joinRelationshipInferenceService;
    @Mock private SchemaClassificationService schemaClassificationService;
    @Mock private SemanticModelService semanticModelService;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private ConnectionInitStatusRepository initStatusRepo;
    @Mock private ConnectionInitHistoryRepository initHistoryRepo;
    @Mock private ColumnProfileRepository columnProfileRepository;
    @Mock private QueryLineageRepository queryLineageRepository;
    @Mock private SlowQueryHistoryRepository slowQueryHistoryRepository;
    @Mock private SchemaDocumentationRepository schemaDocumentationRepository;
    @Mock private CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;

    private BrainInitStageExecutor executor;

    private static final UUID RUN_ID_1 = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID RUN_ID_2 = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID RUN_ID_3 = UUID.fromString("00000000-0000-0000-0000-000000000003");
    private static final UUID STALE_RUN_ID = UUID.fromString("00000000-0000-0000-0000-0000000000ff");

    @BeforeEach
    void setUp() {
        executor = new BrainInitStageExecutor(
            trainingJobService, schemaDescriptionService,
            columnProfilingService, connectionService,
            schemaScannerService, schemaSnapshotService, queryExecutorService,
            keyColumnAnalysisService, columnValueCollectionService, joinRelationshipInferenceService,
            schemaClassificationService, semanticModelService, vectorSearchService,
            initStatusRepo, initHistoryRepo,
            columnProfileRepository, queryLineageRepository, slowQueryHistoryRepository,
            schemaDocumentationRepository, companyKnowledgeEntryRepository
        );
    }

    @Test
    void executeStage_schemaScan_schedulesNextStage() throws Exception {
        var data = new BrainInitTaskData("conn-1", InitStage.SCHEMA_SCAN, 0, RUN_ID_1.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.SCHEMA_SCAN)
            .cancelRequested(false)
            .activeRunId(RUN_ID_1)
            .build();
        var snapshot = SchemaSnapshot.builder()
            .connectionId("conn-1")
            .tableCount(12)
            .columnCount(86)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(new SchemaMetadata(
            null,
            null,
            List.of(
                new TableMetadata("orders", null, "table", null, null, List.of(), List.of()),
                new TableMetadata("customers", null, "table", null, null, List.of(), List.of())
            ),
            List.of(),
            null,
            null,
            null
        ));
        when(schemaSnapshotService.captureSnapshot("conn-1", false)).thenReturn(snapshot);

        InitStage nextStage = executor.executeStage(data);

        verify(queryExecutorService).evictDatabaseObjectsCache("conn-1");
        verify(schemaScannerService).evictSchemaCache("conn-1");
        verify(schemaSnapshotService).captureSnapshot("conn-1", false);
        assertEquals(InitStage.DATA_SAMPLING, nextStage);
        assertEquals(18, status.getProgressPercent());
    }

    @Test
    void executeStage_cancelledBeforeStart_returnsNull() {
        var data = new BrainInitTaskData("conn-1", InitStage.SCHEMA_SCAN, 0, RUN_ID_2.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.SCHEMA_SCAN)
            .cancelRequested(true)
            .activeRunId(RUN_ID_2)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));

        InitStage nextStage = executor.executeStage(data);

        assertNull(nextStage);
        // Verify stage work didn't execute (broadcast is allowed for cancel notification)
        verify(trainingJobService, never()).startSchemaTrainingSync(any(), any());
    }

    @Test
    void executeStage_staleRunId_returnsNull() {
        var data = new BrainInitTaskData("conn-1", InitStage.DATA_SAMPLING, 0, STALE_RUN_ID.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.SCHEMA_SCAN)
            .cancelRequested(false)
            .activeRunId(RUN_ID_1)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));

        InitStage nextStage = executor.executeStage(data);

        assertNull(nextStage);
        verifyNoInteractions(trainingJobService);
    }

    @Test
    void executeStage_noStatusRow_returnsNull() {
        var data = new BrainInitTaskData("conn-1", InitStage.SCHEMA_SCAN, 0, RUN_ID_1.toString());
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.empty());

        InitStage nextStage = executor.executeStage(data);

        assertNull(nextStage);
        verifyNoInteractions(trainingJobService);
    }

    @Test
    void executeStage_brainAnalysis_advancesToKeyColumnAnalysis() {
        var data = new BrainInitTaskData("conn-1", InitStage.BRAIN_ANALYSIS, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.BRAIN_ANALYSIS)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        InitStage nextStage = executor.executeStage(data);

        assertEquals(InitStage.SEMANTIC_MODELING, nextStage);
    }

    @Test
    void executeStage_aiDescription_recordsCompletionMetrics() {
        var data = new BrainInitTaskData("conn-1", InitStage.AI_DESCRIPTION, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.AI_DESCRIPTION)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDescriptionService.generateDescriptions(eq("conn-1"), eq(null), any()))
            .thenReturn(com.dbaagent.service.SchemaDescriptionService.GenerationResult.builder()
                .currentTables(12)
                .matchedExistingAiTableDocs(7)
                .unmatchedExistingAiTableDocs(2)
                .documentationsCreated(12)
                .tablesProcessed(5)
                .tablesSkipped(1)
                .tablesFailed(0)
                .build());

        InitStage nextStage = executor.executeStage(data);

        assertEquals(InitStage.RAG_EMBEDDING, nextStage);
        assertEquals(80, status.getProgressPercent());
        assertEquals("Generated 12 descriptions", status.getStageMessage());
        assertEquals(
            "completed",
            status.getStageDetails().get(InitStage.AI_DESCRIPTION.name()).get("status")
        );
        assertEquals(
            7,
            status.getStageDetails().get(InitStage.AI_DESCRIPTION.name()).get("matchedExistingAiTableDocs")
        );
        assertEquals(
            12,
            status.getStageDetails().get(InitStage.AI_DESCRIPTION.name()).get("currentTables")
        );
    }

    @Test
    void executeStage_aiDescriptionFailureContinuesPipeline() {
        var data = new BrainInitTaskData("conn-1", InitStage.AI_DESCRIPTION, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.AI_DESCRIPTION)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDescriptionService.generateDescriptions(eq("conn-1"), eq(null), any()))
            .thenThrow(new RuntimeException("Azure OpenAI error (404): DeploymentNotFound"));

        InitStage nextStage = executor.executeStage(data);

        assertEquals(InitStage.RAG_EMBEDDING, nextStage);
        assertEquals(80, status.getProgressPercent());
        assertEquals(
            "AI descriptions unavailable, continuing with schema metadata only",
            status.getStageMessage()
        );
        assertEquals(
            "failed",
            status.getStageDetails().get(InitStage.AI_DESCRIPTION.name()).get("status")
        );
        assertTrue(
            String.valueOf(status.getStageDetails().get(InitStage.AI_DESCRIPTION.name()).get("error"))
                .contains("RuntimeException")
        );
        verify(initHistoryRepo, never()).save(any());
    }

    @Test
    void executeStage_columnValueCollection_recordsSummary() {
        var data = new BrainInitTaskData("conn-1", InitStage.COLUMN_VALUE_COLLECTION, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.COLUMN_VALUE_COLLECTION)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(columnValueCollectionService.analyzeColumnValues("conn-1", null))
            .thenReturn(new ColumnValueCollectionService.ColumnValueCollectionSummary(12, 10, 8, 8, 8, 2));

        InitStage nextStage = executor.executeStage(data);

        assertEquals(InitStage.INFERRED_RELATIONSHIPS, nextStage);
        assertEquals(50, status.getProgressPercent());
        assertEquals(
            8,
            status.getStageDetails().get(InitStage.COLUMN_VALUE_COLLECTION.name()).get("cachedColumns")
        );
    }

    @Test
    void executeStage_inferredRelationships_advancesToClassification() {
        var data = new BrainInitTaskData("conn-1", InitStage.INFERRED_RELATIONSHIPS, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.INFERRED_RELATIONSHIPS)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(joinRelationshipInferenceService.inferRelationships("conn-1"))
            .thenReturn(com.dbaagent.dto.RelationshipInferenceResult.builder()
                .totalRelationshipsInferred(7)
                .highConfidenceCount(4)
                .newRelationshipsFound(3)
                .existingRelationshipsUpdated(2)
                .totalQueriesAnalyzed(18)
                .queriesFromLineage(11)
                .queriesFromSlowLogs(7)
                .parseFailures(1)
                .build());

        InitStage nextStage = executor.executeStage(data);

        assertEquals(InitStage.SCHEMA_CLASSIFICATION, nextStage);
        assertEquals(60, status.getProgressPercent());
        assertEquals(
            7,
            status.getStageDetails().get(InitStage.INFERRED_RELATIONSHIPS.name()).get("totalRelationshipsInferred")
        );
    }

    @Test
    void executeStage_ragEmbedding_recordsIndexSummary() {
        var data = new BrainInitTaskData("conn-1", InitStage.RAG_EMBEDDING, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.RAG_EMBEDDING)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doAnswer(invocation -> {
            var listener = invocation.getArgument(1, com.dbaagent.service.TrainingService.TrainingProgressListener.class);
            listener.onStart(5);
            listener.onProgress(5, 5, "orders");
            listener.onRunSummary(com.dbaagent.model.TrainingRunSummary.builder()
                .embeddingCount(5)
                .azureDocumentCount(17)
                .embeddingDimensions(3072)
                .documentCountsByType(java.util.Map.of(
                    "SCHEMA_DDL", 5,
                    "DOCUMENTATION", 8,
                    "BUSINESS_TERM", 4
                ))
                .embeddedDocumentCountsByType(java.util.Map.of(
                    "SCHEMA_DDL", 5,
                    "DOCUMENTATION", 8,
                    "BUSINESS_TERM", 4
                ))
                .build());
            listener.onComplete();
            return null;
        }).when(trainingJobService).startSchemaTrainingSync(eq("conn-1"), any());

        InitStage nextStage = executor.executeStage(data);

        assertEquals(InitStage.BRAIN_ANALYSIS, nextStage);
        assertEquals(92, status.getProgressPercent());
        assertEquals(
            17,
            status.getStageDetails().get(InitStage.RAG_EMBEDDING.name()).get("documentsIndexed")
        );
        assertEquals(
            java.util.Map.of("SCHEMA_DDL", 5, "DOCUMENTATION", 8, "BUSINESS_TERM", 4),
            status.getStageDetails().get(InitStage.RAG_EMBEDDING.name()).get("documentCountsByType")
        );
        assertEquals(
            java.util.Map.of("SCHEMA_DDL", 5, "DOCUMENTATION", 8, "BUSINESS_TERM", 4),
            status.getStageDetails().get(InitStage.RAG_EMBEDDING.name()).get("embeddedDocumentCountsByType")
        );
    }

    @Test
    void executeStage_ragEmbeddingContinuesInDegradedModeWhenVectorsAreMissing() {
        var data = new BrainInitTaskData("conn-1", InitStage.RAG_EMBEDDING, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.RAG_EMBEDDING)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(vectorSearchService.isEnabled()).thenReturn(true);
        when(vectorSearchService.backendName()).thenReturn("Local pgvector");
        doAnswer(invocation -> {
            var listener = invocation.getArgument(1, com.dbaagent.service.TrainingService.TrainingProgressListener.class);
            listener.onStart(5);
            listener.onRunSummary(com.dbaagent.model.TrainingRunSummary.builder()
                .embeddingCount(0)
                .embeddedDocumentCount(0)
                .azureDocumentCount(17)
                .build());
            return null;
        }).when(trainingJobService).startSchemaTrainingSync(eq("conn-1"), any());

        InitStage nextStage = executor.executeStage(data);

        // Brain init should continue (not fail) — degraded keyword-only mode
        assertEquals(InitStage.BRAIN_ANALYSIS, nextStage);
        assertNotEquals(InitStage.FAILED, status.getCurrentStage());
        var ragDetails = status.getStageDetails().get(InitStage.RAG_EMBEDDING.name());
        assertNotNull(ragDetails);
        assertTrue(ragDetails.containsKey("embeddingWarning"),
            "Stage details should contain embeddingWarning when embeddings are missing");

        // This warning is API- and UI-surfaced, and it fires on exactly the condition an
        // operator hits when embeddings are broken. It previously named
        // AZURE_OPENAI_EMBEDDING_DEPLOYMENT, which LlmConfigResolver.resolveEmbedding()
        // does not read — so a self-hoster who had set it correctly was told to check it
        // again while the real knob went unmentioned. Assert it names a live one.
        String warning = String.valueOf(ragDetails.get("embeddingWarning"));
        assertFalse(warning.contains("AZURE_OPENAI_EMBEDDING_DEPLOYMENT"),
            "embeddingWarning must not point at AZURE_OPENAI_EMBEDDING_DEPLOYMENT — nothing "
            + "reads it, so it misdirects the operator on the one path that surfaces it");
        assertTrue(warning.contains("DEEPSQL_EMBEDDING_PROVIDER"),
            "embeddingWarning must name a variable resolveEmbedding() actually reads, "
            + "or the wizard; got: " + warning);
    }

    @Test
    void executeStage_semanticModelingCompletesInit() {
        var data = new BrainInitTaskData("conn-1", InitStage.SEMANTIC_MODELING, 0, RUN_ID_3.toString());
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.SEMANTIC_MODELING)
            .cancelRequested(false)
            .activeRunId(RUN_ID_3)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(semanticModelService.rebuildSemanticModel("conn-1"))
            .thenReturn(new SemanticModelService.SemanticModelBuildSummary(12, 18, 7, 5, 3));

        InitStage nextStage = executor.executeStage(data);

        assertNull(nextStage);
        assertEquals(InitStage.COMPLETED, status.getCurrentStage());
        assertEquals(
            "completed",
            status.getStageDetails().get(InitStage.SEMANTIC_MODELING.name()).get("status")
        );
    }
}
