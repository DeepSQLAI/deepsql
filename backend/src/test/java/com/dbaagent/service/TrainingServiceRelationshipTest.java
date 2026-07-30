package com.dbaagent.service;

import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaMetadata;
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

import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrainingServiceRelationshipTest {

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
    class EmbedRelationshipDocuments {

        @Test
        void createsRelationshipDocsForTablesWithFks() {
            // Given: schema with 2 tables and FK between them
            String connectionId = "conn-1";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");
            schema.setRelationships(List.of(
                    new RelationshipMetadata("fk_orders_customers", "orders", "customer_id",
                            "customers", "id", "many-to-one", "fk_orders_customers")
            ));

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId))
                    .thenReturn(List.of());
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));
            when(azureSearchService.resolveTableName(anyString(), isNull(), eq("RELATIONSHIP"), eq("postgresql")))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then: 2 RELATIONSHIP docs (one for "orders", one for "customers")
            assertThat(result).isEqualTo(2);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TrainingDataSearchDocument>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(azureSearchService).indexDocuments(captor.capture());

            List<TrainingDataSearchDocument> docs = captor.getValue();
            assertThat(docs).hasSize(2);
            assertThat(docs).allMatch(d -> "RELATIONSHIP".equals(d.getType()));

            List<String> objectNames = docs.stream()
                    .map(TrainingDataSearchDocument::getObjectName).toList();
            assertThat(objectNames).containsExactlyInAnyOrder("orders", "customers");

            // Verify content includes FK info
            TrainingDataSearchDocument ordersDoc = docs.stream()
                    .filter(d -> "orders".equals(d.getObjectName())).findFirst().orElseThrow();
            assertThat(ordersDoc.getContent()).contains("orders.customer_id");
            assertThat(ordersDoc.getContent()).contains("customers.id");
            assertThat(ordersDoc.getContent()).contains("FOREIGN KEY");
        }

        @Test
        void filtersRejectedInferredRelationships() {
            // Given: mix of INFERRED and REJECTED inferred relationships
            String connectionId = "conn-1";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");
            schema.setRelationships(List.of());

            InferredTableRelationship validRel = InferredTableRelationship.builder()
                    .connectionId(connectionId)
                    .sourceTable("orders")
                    .sourceColumn("product_id")
                    .targetTable("products")
                    .targetColumn("id")
                    .confidenceScore(new BigDecimal("80"))
                    .joinCount(5)
                    .status("INFERRED")
                    .build();

            InferredTableRelationship rejectedRel = InferredTableRelationship.builder()
                    .connectionId(connectionId)
                    .sourceTable("orders")
                    .sourceColumn("user_id")
                    .targetTable("users")
                    .targetColumn("id")
                    .confidenceScore(new BigDecimal("90"))
                    .joinCount(10)
                    .status("REJECTED")
                    .build();

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId))
                    .thenReturn(List.of(rejectedRel, validRel));
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));
            when(azureSearchService.resolveTableName(anyString(), isNull(), eq("RELATIONSHIP"), eq("postgresql")))
                    .thenAnswer(inv -> inv.getArgument(0));

            // When
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then: only tables from valid rel appear, rejected rel excluded
            assertThat(result).isEqualTo(2); // orders + products

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TrainingDataSearchDocument>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(azureSearchService).indexDocuments(captor.capture());

            List<TrainingDataSearchDocument> docs = captor.getValue();
            List<String> objectNames = docs.stream()
                    .map(TrainingDataSearchDocument::getObjectName).toList();
            assertThat(objectNames).containsExactlyInAnyOrder("orders", "products");
            // "users" should NOT appear (rejected)
            assertThat(objectNames).doesNotContain("users");

            // Verify content does not mention rejected relationship
            TrainingDataSearchDocument ordersDoc = docs.stream()
                    .filter(d -> "orders".equals(d.getObjectName())).findFirst().orElseThrow();
            assertThat(ordersDoc.getContent()).doesNotContain("users");
        }

        @Test
        void filtersLowConfidenceInferred() {
            // Given: inferred relationship with confidence just under 60
            String connectionId = "conn-1";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");
            schema.setRelationships(List.of());

            InferredTableRelationship lowConfRel = InferredTableRelationship.builder()
                    .connectionId(connectionId)
                    .sourceTable("orders")
                    .sourceColumn("product_id")
                    .targetTable("products")
                    .targetColumn("id")
                    .confidenceScore(new BigDecimal("59"))
                    .joinCount(2)
                    .status("INFERRED")
                    .build();

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId))
                    .thenReturn(List.of(lowConfRel));

            // When
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then: no docs created (low confidence filtered out, no FKs)
            assertThat(result).isEqualTo(0);
            verify(azureSearchService, never()).indexDocuments(any());
        }

        @Test
        void returnsZeroWhenAzureDisabled() {
            // Given: Azure search disabled
            String connectionId = "conn-1";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");

            when(azureSearchService.isEnabled()).thenReturn(false);

            // When
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then
            assertThat(result).isEqualTo(0);
            verify(azureSearchService, never()).indexDocuments(any());
            verify(embeddingService, never()).createEmbedding(anyString());
        }

        @Test
        void returnsZeroWhenNoRelationships() {
            // Given: schema with no FKs, repo returns empty
            String connectionId = "conn-1";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");
            schema.setRelationships(List.of());

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId))
                    .thenReturn(List.of());

            // When
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then
            assertThat(result).isEqualTo(0);
            verify(azureSearchService, never()).indexDocuments(any());
        }

        @Test
        void continuesOnFailure() {
            // Given: embedding service throws RuntimeException
            String connectionId = "conn-1";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");
            schema.setRelationships(List.of(
                    new RelationshipMetadata("fk_1", "orders", "customer_id",
                            "customers", "id", "many-to-one", "fk_1")
            ));

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId))
                    .thenReturn(List.of());
            when(embeddingService.createEmbedding(anyString()))
                    .thenThrow(new RuntimeException("embedding service unavailable"));

            // When: should not throw
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then: returns 0 (non-fatal)
            assertThat(result).isEqualTo(0);
        }

        @Test
        void setsCorrectDocFields() {
            // Given: single FK to verify all doc fields
            String connectionId = "conn-1";
            String tableName = "orders";
            SchemaMetadata schema = new SchemaMetadata();
            schema.setDbType("postgresql");
            schema.setRelationships(List.of(
                    new RelationshipMetadata("fk_1", "orders", "customer_id",
                            "customers", "id", "many-to-one", "fk_1")
            ));

            when(azureSearchService.isEnabled()).thenReturn(true);
            when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(connectionId))
                    .thenReturn(List.of());
            when(embeddingService.createEmbedding(anyString()))
                    .thenReturn(List.of(0.1, 0.2, 0.3));
            when(azureSearchService.resolveTableName(eq("orders"), isNull(), eq("RELATIONSHIP"), eq("postgresql")))
                    .thenReturn("orders");
            when(azureSearchService.resolveTableName(eq("customers"), isNull(), eq("RELATIONSHIP"), eq("postgresql")))
                    .thenReturn("customers");

            // When
            int result = ReflectionTestUtils.invokeMethod(trainingService,
                    "embedRelationshipDocuments", connectionId, schema);

            // Then
            assertThat(result).isEqualTo(2);

            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TrainingDataSearchDocument>> captor =
                    ArgumentCaptor.forClass(List.class);
            verify(azureSearchService).indexDocuments(captor.capture());

            TrainingDataSearchDocument ordersDoc = captor.getValue().stream()
                    .filter(d -> "orders".equals(d.getObjectName())).findFirst().orElseThrow();

            // Verify doc ID
            String expectedId = UUID.nameUUIDFromBytes(
                    (connectionId + "::RELATIONSHIP::" + tableName).getBytes(StandardCharsets.UTF_8)
            ).toString();
            assertThat(ordersDoc.getId()).isEqualTo(expectedId);

            // Verify other fields
            assertThat(ordersDoc.getConnectionId()).isEqualTo(connectionId);
            assertThat(ordersDoc.getType()).isEqualTo("RELATIONSHIP");
            assertThat(ordersDoc.getObjectName()).isEqualTo("orders");
            assertThat(ordersDoc.getTableName()).isEqualTo("orders");
            assertThat(ordersDoc.getDbType()).isEqualTo("postgresql");
            assertThat(ordersDoc.getContentVector()).containsExactly(0.1f, 0.2f, 0.3f);
        }
    }

    @Nested
    class BuildRelationshipContent {

        @Test
        void includesFkRelationshipsWithTag() {
            // Given: 1 FK relationship
            List<RelationshipMetadata> fks = List.of(
                    new RelationshipMetadata("fk_1", "orders", "customer_id",
                            "customers", "id", "many-to-one", "fk_1")
            );

            // When
            String content = ReflectionTestUtils.invokeMethod(trainingService,
                    "buildRelationshipContent", "orders", fks, List.of());

            // Then
            assertThat(content).contains("orders.customer_id \u2192 customers.id (FOREIGN KEY)");
            assertThat(content).startsWith("Table: orders\n");
        }

        @Test
        void includesInferredWithConfidenceAndQueryCount() {
            // Given: inferred relationship with confidence and join count
            InferredTableRelationship inferred = InferredTableRelationship.builder()
                    .sourceTable("orders")
                    .sourceColumn("product_id")
                    .targetTable("products")
                    .targetColumn("id")
                    .confidenceScore(new BigDecimal("85"))
                    .joinCount(12)
                    .status("INFERRED")
                    .build();

            // When
            String content = ReflectionTestUtils.invokeMethod(trainingService,
                    "buildRelationshipContent", "orders", List.of(), List.of(inferred));

            // Then
            assertThat(content).contains("(INFERRED, 85% confidence, 12 queries)");
            assertThat(content).contains("orders.product_id \u2192 products.id");
        }

        @Test
        void deduplicatesFkAndInferred() {
            // Given: same pair in both FK and inferred
            List<RelationshipMetadata> fks = List.of(
                    new RelationshipMetadata("fk_1", "orders", "customer_id",
                            "customers", "id", "many-to-one", "fk_1")
            );
            InferredTableRelationship inferred = InferredTableRelationship.builder()
                    .sourceTable("orders")
                    .sourceColumn("customer_id")
                    .targetTable("customers")
                    .targetColumn("id")
                    .confidenceScore(new BigDecimal("95"))
                    .joinCount(20)
                    .status("INFERRED")
                    .build();

            // When
            String content = ReflectionTestUtils.invokeMethod(trainingService,
                    "buildRelationshipContent", "orders", fks, List.of(inferred));

            // Then: FK wins (added first), inferred duplicate is skipped
            String key = "orders.customer_id \u2192 customers.id";
            int firstIdx = content.indexOf(key);
            int secondIdx = content.indexOf(key, firstIdx + 1);
            assertThat(firstIdx).isGreaterThanOrEqualTo(0);
            assertThat(secondIdx).isEqualTo(-1); // no second occurrence
            assertThat(content).contains("(FOREIGN KEY)");
            // Inferred version should not appear since FK was added first
            assertThat(content).doesNotContain("(INFERRED");
        }

        @Test
        void handlesEmptyLists() {
            // Given: both FK and inferred lists are empty
            // When
            String content = ReflectionTestUtils.invokeMethod(trainingService,
                    "buildRelationshipContent", "orders",
                    List.of(), List.of());

            // Then
            assertThat(content).isEqualTo("Table: orders\nRelationships:\n");
        }
    }
}
