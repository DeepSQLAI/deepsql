package com.dbaagent.service;

import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.model.TrainingDataEmbedding.TrainingDataType;
import com.dbaagent.provider.DatabaseProviderRegistry;
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

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class TrainingServiceBuildContextTest {

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
    @Mock private DatabaseProviderRegistry databaseProviderRegistry;

    @InjectMocks
    private TrainingService trainingService;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trainingService, "objectMapper", new ObjectMapper());
    }

    private TrainingDataEmbedding buildItem(String id, TrainingDataType type, String content) {
        return TrainingDataEmbedding.builder()
                .id(id)
                .type(type)
                .content(content)
                .build();
    }

    @Nested
    class TwoSourceMerge {

        @Test
        void targetedDataAppearsBeforeFallback() {
            List<TrainingDataEmbedding> targeted = List.of(
                    buildItem("t1", TrainingDataType.DOCUMENTATION, "targeted-doc"));
            List<TrainingDataEmbedding> fallback = List.of(
                    buildItem("f1", TrainingDataType.DOCUMENTATION, "fallback-doc"));

            String result = trainingService.buildTrainingContext(targeted, fallback);

            int targetedIndex = result.indexOf("targeted-doc");
            int fallbackIndex = result.indexOf("fallback-doc");
            assertThat(targetedIndex).isGreaterThan(-1);
            assertThat(fallbackIndex).isGreaterThan(-1);
            assertThat(targetedIndex).isLessThan(fallbackIndex);
        }

        @Test
        void returnsEmptyStringWhenBothEmpty() {
            String result = trainingService.buildTrainingContext(List.of(), List.of());

            assertThat(result).isEqualTo("");
        }

        @Test
        void handlesNullTargetedData() {
            List<TrainingDataEmbedding> fallback = List.of(
                    buildItem("f1", TrainingDataType.QUERY_EXAMPLE, "select 1"));

            String result = trainingService.buildTrainingContext(null, fallback);

            assertThat(result).contains("select 1");
            assertThat(result).contains("Similar Query Examples");
        }

        @Test
        void handlesNullFallbackData() {
            List<TrainingDataEmbedding> targeted = List.of(
                    buildItem("t1", TrainingDataType.SCHEMA_DDL, "CREATE TABLE orders"));

            String result = trainingService.buildTrainingContext(targeted, null);

            assertThat(result).contains("CREATE TABLE orders");
            assertThat(result).contains("Related Schema and Relationships");
        }
    }

    @Nested
    class TypeGrouping {

        @Test
        void relationshipGoesIntoSchemaSection() {
            List<TrainingDataEmbedding> data = List.of(
                    buildItem("r1", TrainingDataType.RELATIONSHIP, "orders->customers FK"));

            String result = trainingService.buildTrainingContext(List.of(), data);

            assertThat(result).contains("Related Schema and Relationships");
            assertThat(result).contains("[RELATIONSHIP] orders->customers FK");
        }

        @Test
        void businessTermGoesIntoBusinessSection() {
            List<TrainingDataEmbedding> data = List.of(
                    buildItem("bt1", TrainingDataType.BUSINESS_TERM, "revenue means total sales"));

            String result = trainingService.buildTrainingContext(List.of(), data);

            assertThat(result).contains("Business Definitions and Semantics");
            assertThat(result).contains("[BUSINESS_TERM] revenue means total sales");
        }

        @Test
        void allTypesGroupedCorrectly() {
            List<TrainingDataEmbedding> data = List.of(
                    buildItem("cv1", TrainingDataType.COLUMN_VALUES, "status: active,inactive"),
                    buildItem("doc1", TrainingDataType.DOCUMENTATION, "orders table docs"),
                    buildItem("bt1", TrainingDataType.BUSINESS_TERM, "revenue alias"),
                    buildItem("qe1", TrainingDataType.QUERY_EXAMPLE, "SELECT * FROM orders"),
                    buildItem("qp1", TrainingDataType.QUERY_PATTERN, "frequent join pattern"),
                    buildItem("ddl1", TrainingDataType.SCHEMA_DDL, "CREATE TABLE customers"),
                    buildItem("rel1", TrainingDataType.RELATIONSHIP, "orders->customers"));

            String result = trainingService.buildTrainingContext(List.of(), data);

            // Column Values section
            assertThat(result).contains("Known Column Values and Allowed Variants");
            assertThat(result).contains("[COLUMN_VALUES] status: active,inactive");

            // Business Definitions section (DOCUMENTATION + BUSINESS_TERM)
            assertThat(result).contains("Business Definitions and Semantics");
            assertThat(result).contains("[DOCUMENTATION] orders table docs");
            assertThat(result).contains("[BUSINESS_TERM] revenue alias");

            // Query Examples section
            assertThat(result).contains("Similar Query Examples");
            assertThat(result).contains("[QUERY_EXAMPLE] SELECT * FROM orders");

            // Pattern Insights section
            assertThat(result).contains("Query and Workload Insights");
            assertThat(result).contains("[QUERY_PATTERN] frequent join pattern");

            // Schema section (SCHEMA_DDL + RELATIONSHIP)
            assertThat(result).contains("Related Schema and Relationships");
            assertThat(result).contains("[SCHEMA_DDL] CREATE TABLE customers");
            assertThat(result).contains("[RELATIONSHIP] orders->customers");
        }
    }

    @Nested
    class SingleSourceDelegate {

        @Test
        void singleArgDelegatesToTwoArg() {
            List<TrainingDataEmbedding> data = List.of(
                    buildItem("d1", TrainingDataType.DOCUMENTATION, "some doc"),
                    buildItem("q1", TrainingDataType.QUERY_EXAMPLE, "SELECT 1"));

            String singleArgResult = trainingService.buildTrainingContext(data);
            String twoArgResult = trainingService.buildTrainingContext(List.of(), data);

            assertThat(singleArgResult).isEqualTo(twoArgResult);
        }
    }

    @Nested
    class TakeByTypeCap {

        @Test
        void capsAt15PerType() {
            List<TrainingDataEmbedding> data = new ArrayList<>();
            IntStream.rangeClosed(1, 20).forEach(i ->
                    data.add(buildItem("qe" + i, TrainingDataType.QUERY_EXAMPLE, "query-" + i)));

            String result = trainingService.buildTrainingContext(List.of(), data);

            // First 15 should be present
            IntStream.rangeClosed(1, 15).forEach(i ->
                    assertThat(result).contains("query-" + i));

            // Items 16-20 should NOT be present
            // Need to check carefully since "query-1" is a substring of "query-16" etc.
            // Use the full line format "[QUERY_EXAMPLE] query-N" to avoid false matches
            IntStream.rangeClosed(16, 20).forEach(i ->
                    assertThat(result).doesNotContain("[QUERY_EXAMPLE] query-" + i));
        }
    }
}
