package com.dbaagent.service;

import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;

@ExtendWith(MockitoExtension.class)
class TrainingServiceBusinessTermTest {

    @Mock private QueryExampleRepository queryExampleRepository;
    @Mock private SchemaDocumentationRepository schemaDocRepository;
    @Mock private ColumnProfileRepository columnProfileRepository;
    @Mock private InferredTableRelationshipRepository inferredTableRelationshipRepository;
    @Mock private EmbeddingService embeddingService;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private AzureSearchService azureSearchService;
    @Mock private CacheManager cacheManager;
    @Mock private RedisConnectionFactory redisConnectionFactory;
    @Mock private CacheMetricsService cacheMetricsService;
    @Mock private SqlUsageService sqlUsageService;

    @InjectMocks
    private TrainingService trainingService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trainingService, "objectMapper", objectMapper);
    }

    @Nested
    class BusinessTermDocCreation {

        @Test
        void createsBusinessTermDocWhenBusinessTermsPresent() throws Exception {
            // Given: doc with business terms and Azure enabled
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "Purchase orders table",
                    "purchase_orders, transactions, sales");

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(azureSearchService.resolveTableName("orders", null, "TABLE", "unknown"))
                    .thenReturn("orders");
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));

            // When
            trainingService.upsertDocumentationEmbedding(doc);

            // Then: should index 2 docs — DOCUMENTATION + BUSINESS_TERM
            ArgumentCaptor<TrainingDataSearchDocument> captor =
                    ArgumentCaptor.forClass(TrainingDataSearchDocument.class);
            verify(azureSearchService, times(2)).indexDocument(captor.capture());

            List<TrainingDataSearchDocument> indexed = captor.getAllValues();
            TrainingDataSearchDocument docDoc = indexed.stream()
                    .filter(d -> "DOCUMENTATION".equals(d.getType())).findFirst().orElseThrow();
            TrainingDataSearchDocument btDoc = indexed.stream()
                    .filter(d -> "BUSINESS_TERM".equals(d.getType())).findFirst().orElseThrow();

            // Verify BUSINESS_TERM doc fields
            String expectedId = UUID.nameUUIDFromBytes(
                    ("conn-1::BUSINESS_TERM::orders").getBytes(StandardCharsets.UTF_8)).toString();
            assertThat(btDoc.getId()).isEqualTo(expectedId);
            assertThat(btDoc.getConnectionId()).isEqualTo("conn-1");
            assertThat(btDoc.getTableName()).isEqualTo("orders");
            assertThat(btDoc.getObjectName()).isEqualTo("orders");
            assertThat(btDoc.getBusinessTerms()).isEqualTo("purchase_orders, transactions, sales");
            assertThat(btDoc.getContent()).contains("Business Terms for: orders");
            assertThat(btDoc.getContent()).contains("purchase_orders, transactions, sales");
        }

        @Test
        void skipsBusinessTermDocWhenBusinessTermsEmpty() throws Exception {
            // Given: doc WITHOUT business terms
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "Purchase orders table", null);

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));

            // When
            trainingService.upsertDocumentationEmbedding(doc);

            // Then: only 1 doc (DOCUMENTATION), no BUSINESS_TERM
            verify(azureSearchService, times(1)).indexDocument(any());
        }

        @Test
        void skipsBusinessTermDocWhenBusinessTermsBlank() throws Exception {
            // Given: doc with blank business terms
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "desc", "   ");

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));

            // When
            trainingService.upsertDocumentationEmbedding(doc);

            // Then: only 1 doc (DOCUMENTATION)
            verify(azureSearchService, times(1)).indexDocument(any());
        }

        @Test
        void businessTermContentIncludesDescriptionAndKeyColumns() throws Exception {
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "Purchase orders with lifecycle",
                    "purchase_orders, transactions");

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(azureSearchService.resolveTableName("orders", null, "TABLE", "unknown"))
                    .thenReturn("orders");
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));

            trainingService.upsertDocumentationEmbedding(doc);

            ArgumentCaptor<TrainingDataSearchDocument> captor =
                    ArgumentCaptor.forClass(TrainingDataSearchDocument.class);
            verify(azureSearchService, times(2)).indexDocument(captor.capture());

            TrainingDataSearchDocument btDoc = captor.getAllValues().stream()
                    .filter(d -> "BUSINESS_TERM".equals(d.getType())).findFirst().orElseThrow();

            assertThat(btDoc.getContent()).contains("Business Terms for: orders");
            assertThat(btDoc.getContent()).contains("Aliases: purchase_orders, transactions");
            assertThat(btDoc.getContent()).contains("Table Description: Purchase orders with lifecycle");
        }

        @Test
        void businessTermDocUsesOwnEmbedding() throws Exception {
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "desc", "alias1, alias2");

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(azureSearchService.resolveTableName("orders", null, "TABLE", "unknown"))
                    .thenReturn("orders");
            // Return different embeddings for each call
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3))
                    .thenReturn(List.of(0.4, 0.5, 0.6));

            trainingService.upsertDocumentationEmbedding(doc);

            // Embedding service called twice: once for DOCUMENTATION, once for BUSINESS_TERM
            verify(embeddingService, times(2)).createEmbedding(anyString());
        }

        @Test
        void businessTermDocContinuesOnEmbeddingFailure() throws Exception {
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "desc", "alias1, alias2");

            when(azureSearchService.isEnabled()).thenReturn(true);
            lenient().when(azureSearchService.resolveTableName("orders", null, "TABLE", "unknown"))
                    .thenReturn("orders");
            // First call succeeds (DOCUMENTATION), second fails (BUSINESS_TERM)
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3))
                    .thenThrow(new RuntimeException("embedding failed"));

            // Should not throw — BUSINESS_TERM failure is non-fatal
            trainingService.upsertDocumentationEmbedding(doc);

            // DOCUMENTATION doc still indexed
            verify(azureSearchService, times(1)).indexDocument(any());
        }

        @Test
        void inMemoryFallbackAlsoCreatesBusinessTermDoc() throws Exception {
            SchemaDocumentation doc = buildDoc("conn-1", "orders", "desc", "alias1");

            when(azureSearchService.isEnabled()).thenReturn(false);
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));

            trainingService.upsertDocumentationEmbedding(doc);

            // Embedding called twice (DOCUMENTATION + BUSINESS_TERM)
            verify(embeddingService, times(2)).createEmbedding(anyString());
        }
    }

    private SchemaDocumentation buildDoc(String connectionId, String objectName,
                                          String description, String businessTerms) {
        SchemaDocumentation doc = new SchemaDocumentation();
        doc.setId(UUID.randomUUID().toString());
        doc.setConnectionId(connectionId);
        doc.setObjectName(objectName);
        doc.setDescription(description);
        doc.setBusinessTerms(businessTerms);
        return doc;
    }
}
