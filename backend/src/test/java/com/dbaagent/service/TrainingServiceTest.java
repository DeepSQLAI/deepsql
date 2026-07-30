package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.dbaagent.model.TrainingRunSummary;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TrainingServiceTest {

    @Mock private QueryExampleRepository queryExampleRepository;
    @Mock private SchemaDocumentationRepository schemaDocRepository;
    @Mock private CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    @Mock private ColumnProfileRepository columnProfileRepository;
    @Mock private InferredTableRelationshipRepository inferredTableRelationshipRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private PgVectorSearchService vectorSearchService;
    @Mock private CacheManager cacheManager;
    @Mock private RedisConnectionFactory redisConnectionFactory;
    @Mock private CacheMetricsService cacheMetricsService;
    @Mock private SqlUsageService sqlUsageService;
    @Mock private DatabaseProviderRegistry databaseProviderRegistry;
    @Mock private RagDocumentStateService ragDocumentStateService;

    private TrainingService trainingService;

    @BeforeEach
    void setUp() {
        trainingService = new TrainingService(
            queryExampleRepository,
            schemaDocRepository,
            companyKnowledgeEntryRepository,
            columnProfileRepository,
            inferredTableRelationshipRepository,
            embeddingService,
            schemaScannerService,
            vectorSearchService,
            new ObjectMapper(),
            cacheManager,
            redisConnectionFactory,
            cacheMetricsService,
            sqlUsageService,
            databaseProviderRegistry,
            ragDocumentStateService
        );
        lenient().when(companyKnowledgeEntryRepository.findByConnectionId(anyString())).thenReturn(List.of());
    }

    @Test
    void trainWithSchema_indexesDocumentationAndBusinessTermsDuringNormalBrainInit() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("HOTEL",
                column("id", "bigint"),
                column("subscription_start_date", "timestamp")
            )
        ));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .id("doc-1")
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.TABLE)
                .objectName("HOTEL")
                .description("Core hotel entity. subscription_start_date marks when the hotel contract starts.")
                .businessTerms("hotel, onboarded hotel")
                .createdAt(LocalDateTime.now())
                .build()
        ));
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(embeddingService.createEmbedding(anyString())).thenReturn(List.of(0.11d, 0.22d, 0.33d));
        when(vectorSearchService.isEnabled()).thenReturn(true);
        when(vectorSearchService.resolveTableName(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache("ragRetrieval")).thenReturn(null);

        AtomicReference<TrainingRunSummary> summaryRef = new AtomicReference<>();
        trainingService.trainWithSchema("conn-1", new TrainingService.TrainingProgressListener() {
            @Override
            public void onStart(int totalTables) {
            }

            @Override
            public void onProgress(int processedTables, int totalTables, String tableName) {
            }

            @Override
            public void onComplete() {
            }

            @Override
            public void onError(String message) {
            }

            @Override
            public void onRunSummary(TrainingRunSummary summary) {
                summaryRef.set(summary);
            }
        });

        ArgumentCaptor<List<TrainingDataSearchDocument>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorSearchService).indexDocuments(batchCaptor.capture());
        assertThat(batchCaptor.getValue())
            .extracting(TrainingDataSearchDocument::getType)
            .contains("SCHEMA_DDL");

        ArgumentCaptor<TrainingDataSearchDocument> docCaptor = ArgumentCaptor.forClass(TrainingDataSearchDocument.class);
        verify(vectorSearchService, atLeast(2)).indexDocument(docCaptor.capture());
        assertThat(docCaptor.getAllValues())
            .extracting(TrainingDataSearchDocument::getType)
            .contains("DOCUMENTATION", "BUSINESS_TERM");

        TrainingRunSummary summary = summaryRef.get();
        assertThat(summary).isNotNull();
        assertThat(summary.getDocumentCountsByType())
            .containsEntry("SCHEMA_DDL", 1)
            .containsEntry("DOCUMENTATION", 1)
            .containsEntry("BUSINESS_TERM", 1);
        assertThat(summary.getEmbeddedDocumentCountsByType())
            .containsEntry("SCHEMA_DDL", 1)
            .containsEntry("DOCUMENTATION", 1)
            .containsEntry("BUSINESS_TERM", 1);
    }

    @Test
    void trainWithSchema_reusesUnchangedSchemaEmbeddingOnLocalPgvector() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(table("HOTEL", column("id", "bigint"))));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(vectorSearchService.isEnabled()).thenReturn(true);
        when(vectorSearchService.backendName()).thenReturn("Local pgvector");
        when(vectorSearchService.resolveTableName(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache("ragRetrieval")).thenReturn(null);

        String docId = deterministicId("conn-1::SCHEMA_DDL::HOTEL");
        String hash = sha256("Table: HOTEL\nColumns:\n  - id (bigint)\n");
        Map<String, RagDocumentStateService.RagDocumentState> existingStates = new HashMap<>();
        existingStates.put(docId, new RagDocumentStateService.RagDocumentState(hash, true));
        when(ragDocumentStateService.findDocumentStates(any())).thenReturn(existingStates);

        trainingService.trainWithSchema("conn-1");

        verify(embeddingService, never()).createEmbedding(anyString());
        ArgumentCaptor<List<TrainingDataSearchDocument>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorSearchService).indexDocuments(batchCaptor.capture());
        assertThat(batchCaptor.getValue())
            .singleElement()
            .satisfies(doc -> {
                assertThat(doc.getType()).isEqualTo("SCHEMA_DDL");
                assertThat(doc.getContentVector()).isNull();
                assertThat(doc.getMetadata()).contains("_contentHash");
                assertThat(doc.getMetadata()).contains("_lastSeenRunId");
            });
        verify(ragDocumentStateService).deleteStaleDeterministicDocuments(anyString(), any(), anyString());
    }

    @Test
    void trainWithSchema_backfillsMissingEmbeddingForUnchangedSchemaDoc() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(table("HOTEL", column("id", "bigint"))));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(vectorSearchService.isEnabled()).thenReturn(true);
        when(vectorSearchService.backendName()).thenReturn("Local pgvector");
        when(vectorSearchService.resolveTableName(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache("ragRetrieval")).thenReturn(null);

        String docId = deterministicId("conn-1::SCHEMA_DDL::HOTEL");
        String hash = sha256("Table: HOTEL\nColumns:\n  - id (bigint)\n");
        when(ragDocumentStateService.findDocumentStates(any()))
            .thenReturn(Map.of(docId, new RagDocumentStateService.RagDocumentState(hash, false)));
        when(embeddingService.createEmbedding(anyString())).thenReturn(List.of(0.11d, 0.22d, 0.33d));

        trainingService.trainWithSchema("conn-1");

        verify(embeddingService).createEmbedding(anyString());
    }

    @Test
    void trainWithSchema_fallsBackToPerDocumentIndexingWhenBatchIndexFails() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(table("HOTEL", column("id", "bigint"))));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(embeddingService.createEmbedding(anyString())).thenReturn(List.of(0.11d, 0.22d, 0.33d));
        when(vectorSearchService.isEnabled()).thenReturn(true);
        when(vectorSearchService.resolveTableName(any(), any(), any(), any())).thenAnswer(invocation -> invocation.getArgument(0));
        when(cacheManager.getCache("ragRetrieval")).thenReturn(null);
        doThrow(new RuntimeException("batch upload failed")).when(vectorSearchService).indexDocuments(any());

        trainingService.trainWithSchema("conn-1");

        verify(vectorSearchService).indexDocuments(any());
        verify(vectorSearchService).indexDocument(any(TrainingDataSearchDocument.class));
    }

    @Test
    void trainWithSchema_skipsBrokenTableAndContinuesRemainingTables() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("BROKEN", column("id", "bigint")),
            table("HEALTHY", column("id", "bigint"))
        ));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(embeddingService.createEmbedding(anyString())).thenReturn(List.of(0.11d, 0.22d, 0.33d));
        when(vectorSearchService.isEnabled()).thenReturn(true);
        when(vectorSearchService.resolveTableName(any(), any(), any(), any())).thenAnswer(invocation -> {
            Object objectName = invocation.getArgument(0);
            if ("BROKEN".equals(objectName)) {
                throw new IllegalArgumentException("unsupported object");
            }
            return objectName;
        });
        when(cacheManager.getCache("ragRetrieval")).thenReturn(null);

        trainingService.trainWithSchema("conn-1");

        ArgumentCaptor<List<TrainingDataSearchDocument>> batchCaptor = ArgumentCaptor.forClass(List.class);
        verify(vectorSearchService).indexDocuments(batchCaptor.capture());
        assertThat(batchCaptor.getValue())
            .extracting(TrainingDataSearchDocument::getObjectName)
            .containsExactly("HEALTHY");
    }

    private TableMetadata table(String name, ColumnMetadata... columns) {
        return new TableMetadata(name, null, "table", 100L, null, List.of(columns), List.of());
    }

    private ColumnMetadata column(String name, String type) {
        return new ColumnMetadata(name, type, 255L, true, false, null, 1);
    }

    private String deterministicId(String raw) {
        return java.util.UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String sha256(String value) throws Exception {
        byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(digest.length * 2);
        for (byte b : digest) {
            sb.append(String.format(Locale.ROOT, "%02x", b));
        }
        return sb.toString();
    }
}
