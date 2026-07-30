package com.dbaagent.service;

import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.test.util.ReflectionTestUtils;

import static com.dbaagent.model.TrainingDataEmbedding.TrainingDataType.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests the private resolveTrainingDataType() method in TrainingService.
 * Covers Phase 2 types (RELATIONSHIP, BUSINESS_TERM), normalization edge cases,
 * and the inferTypeFromDocument fallback logic.
 */
@ExtendWith(MockitoExtension.class)
class TrainingServiceTypeResolutionTest {

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

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trainingService, "objectMapper", new ObjectMapper());
    }

    // --- Helpers ---

    private TrainingDataEmbedding.TrainingDataType resolve(TrainingDataSearchDocument doc) {
        return (TrainingDataEmbedding.TrainingDataType) ReflectionTestUtils.invokeMethod(
                trainingService, "resolveTrainingDataType", doc);
    }

    private TrainingDataSearchDocument docWithType(String type) {
        return TrainingDataSearchDocument.builder().id("test-id").type(type).build();
    }

    // --- Test groups ---

    @Nested
    class RelationshipTypeResolution {

        @Test
        void resolvesRelationship() {
            assertThat(resolve(docWithType("RELATIONSHIP"))).isEqualTo(RELATIONSHIP);
        }

        @Test
        void resolvesRelationshipsPlural() {
            assertThat(resolve(docWithType("RELATIONSHIPS"))).isEqualTo(RELATIONSHIP);
        }

        @Test
        void resolvesFkRelationship() {
            assertThat(resolve(docWithType("FK_RELATIONSHIP"))).isEqualTo(RELATIONSHIP);
        }

        @Test
        void resolvesInferredRelationship() {
            assertThat(resolve(docWithType("INFERRED_RELATIONSHIP"))).isEqualTo(RELATIONSHIP);
        }
    }

    @Nested
    class BusinessTermTypeResolution {

        @Test
        void resolvesBusinessTerm() {
            assertThat(resolve(docWithType("BUSINESS_TERM"))).isEqualTo(BUSINESS_TERM);
        }

        @Test
        void resolvesBusinessTermsPlural() {
            assertThat(resolve(docWithType("BUSINESS_TERMS"))).isEqualTo(BUSINESS_TERM);
        }

        @Test
        void resolvesGlossary() {
            assertThat(resolve(docWithType("GLOSSARY"))).isEqualTo(BUSINESS_TERM);
        }

        @Test
        void resolvesTerm() {
            assertThat(resolve(docWithType("TERM"))).isEqualTo(BUSINESS_TERM);
        }
    }

    @Nested
    class NormalizationEdgeCases {

        @Test
        void handlesNullDoc() {
            assertThat(resolve(null)).isEqualTo(DOCUMENTATION);
        }

        @Test
        void handlesNullType() {
            // null type normalizes to "" which falls through to inferTypeFromDocument
            // doc has no sql, no objectName → defaults to DOCUMENTATION
            TrainingDataSearchDocument doc = TrainingDataSearchDocument.builder()
                    .id("test-id")
                    .type(null)
                    .build();
            assertThat(resolve(doc)).isEqualTo(DOCUMENTATION);
        }

        @Test
        void normalizesHyphensAndSpaces() {
            // "business-term" → "BUSINESS_TERM" after trim + toUpperCase + replace('-','_')
            assertThat(resolve(docWithType("business-term"))).isEqualTo(BUSINESS_TERM);
        }

        @Test
        void normalizesCaseInsensitive() {
            assertThat(resolve(docWithType("relationship"))).isEqualTo(RELATIONSHIP);
        }

        @Test
        void normalizesWithWhitespace() {
            assertThat(resolve(docWithType("  RELATIONSHIP  "))).isEqualTo(RELATIONSHIP);
        }
    }

    @Nested
    class InferTypeFromDocument {

        @Test
        void infersQueryExampleFromSqlField() {
            // Unknown type but has sql → QUERY_EXAMPLE
            TrainingDataSearchDocument doc = TrainingDataSearchDocument.builder()
                    .id("test-id")
                    .type("UNKNOWN_TYPE")
                    .sql("SELECT * FROM orders")
                    .build();
            assertThat(resolve(doc)).isEqualTo(QUERY_EXAMPLE);
        }

        @Test
        void infersSchemaDdlFromContent() {
            // Unknown type, has objectName and content containing "Columns:" → SCHEMA_DDL
            TrainingDataSearchDocument doc = TrainingDataSearchDocument.builder()
                    .id("test-id")
                    .type("UNKNOWN_TYPE")
                    .objectName("orders")
                    .content("Table: orders\nColumns:\n  id INT PRIMARY KEY\n  name VARCHAR(255)")
                    .build();
            assertThat(resolve(doc)).isEqualTo(SCHEMA_DDL);
        }

        @Test
        void defaultsToDocumentationForUnknown() {
            // Unknown type, no sql, no objectName → DOCUMENTATION
            TrainingDataSearchDocument doc = TrainingDataSearchDocument.builder()
                    .id("test-id")
                    .type("UNKNOWN_TYPE")
                    .content("Some general documentation content")
                    .build();
            assertThat(resolve(doc)).isEqualTo(DOCUMENTATION);
        }
    }
}
