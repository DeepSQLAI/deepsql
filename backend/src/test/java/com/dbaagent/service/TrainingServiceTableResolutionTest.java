package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.IntrospectionProvider;
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

import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TrainingServiceTableResolutionTest {

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

    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(trainingService, "objectMapper", objectMapper);
    }

    private void mockPostgresDialect() {
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        IntrospectionProvider introspection = mock(IntrospectionProvider.class);
        when(dialect.introspection()).thenReturn(introspection);
        when(introspection.getDefaultSchema()).thenReturn("public");
        when(databaseProviderRegistry.getDialect("postgresql")).thenReturn(dialect);
    }

    private void mockMysqlDialect() {
        DatabaseDialect dialect = mock(DatabaseDialect.class);
        IntrospectionProvider introspection = mock(IntrospectionProvider.class);
        when(dialect.introspection()).thenReturn(introspection);
        when(introspection.getDefaultSchema()).thenReturn(null);
        when(databaseProviderRegistry.getDialect("mysql")).thenReturn(dialect);
    }

    @Nested
    class ToQualifiedTableName {

        @Test
        void postgresBareNameInDefaultSchema() {
            mockPostgresDialect();
            TableMetadata table = new TableMetadata();
            table.setName("orders");
            table.setSchema("public");

            QualifiedTableName result = trainingService.toQualifiedTableName(table, "postgresql");

            assertThat(result.bare()).isEqualTo("orders");
            assertThat(result.schemaQualified()).isNull();
            assertThat(result.storedForm()).isEqualTo("orders");
        }

        @Test
        void postgresNonDefaultSchema() {
            mockPostgresDialect();
            TableMetadata table = new TableMetadata();
            table.setName("orders");
            table.setSchema("sales");

            QualifiedTableName result = trainingService.toQualifiedTableName(table, "postgresql");

            assertThat(result.bare()).isEqualTo("orders");
            assertThat(result.schemaQualified()).isEqualTo("sales.orders");
            assertThat(result.storedForm()).isEqualTo("sales.orders");
        }

        @Test
        void postgresNullSchema() {
            mockPostgresDialect();
            TableMetadata table = new TableMetadata();
            table.setName("orders");
            table.setSchema(null);

            QualifiedTableName result = trainingService.toQualifiedTableName(table, "postgresql");

            assertThat(result.bare()).isEqualTo("orders");
            assertThat(result.schemaQualified()).isNull();
        }

        @Test
        void mysqlAlwaysBare() {
            mockMysqlDialect();
            TableMetadata table = new TableMetadata();
            table.setName("orders");
            table.setSchema("mydb");

            QualifiedTableName result = trainingService.toQualifiedTableName(table, "mysql");

            assertThat(result.bare()).isEqualTo("orders");
            assertThat(result.schemaQualified()).isNull();
            assertThat(result.storedForm()).isEqualTo("orders");
        }
    }

    @Nested
    class ExtractQualifiedTableNamesFromRag {

        @Test
        void extractsTablesFromRagMetadata() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("orders", "public"),
                    table("customers", "public"));

            List<TrainingDataEmbedding> ragResults = List.of(
                    ragResult("orders,customers"));

            Set<QualifiedTableName> result = trainingService.extractQualifiedTableNamesFromRag(
                    ragResults, schema, "postgresql");

            assertThat(result).containsExactlyInAnyOrder(
                    new QualifiedTableName("orders", null),
                    new QualifiedTableName("customers", null));
        }

        @Test
        void handlesAmbiguousBareNames() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("orders", "sales"),
                    table("orders", "billing"));

            List<TrainingDataEmbedding> ragResults = List.of(ragResult("orders"));

            Set<QualifiedTableName> result = trainingService.extractQualifiedTableNamesFromRag(
                    ragResults, schema, "postgresql");

            // Both schema-qualified forms included
            assertThat(result).containsExactlyInAnyOrder(
                    new QualifiedTableName("orders", "sales.orders"),
                    new QualifiedTableName("orders", "billing.orders"));
        }

        @Test
        void ignoresUnknownTables() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("orders", "public"));

            List<TrainingDataEmbedding> ragResults = List.of(ragResult("orders,nonexistent"));

            Set<QualifiedTableName> result = trainingService.extractQualifiedTableNamesFromRag(
                    ragResults, schema, "postgresql");

            assertThat(result).containsExactly(new QualifiedTableName("orders", null));
        }

        @Test
        void extractsFromObjectNameWhenNoTablesUsed() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("orders", "public"));

            TrainingDataEmbedding docResult = TrainingDataEmbedding.builder()
                    .id("doc-1")
                    .type(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL)
                    .metadata("{\"objectName\":\"orders\"}")
                    .build();

            Set<QualifiedTableName> result = trainingService.extractQualifiedTableNamesFromRag(
                    List.of(docResult), schema, "postgresql");

            assertThat(result).contains(new QualifiedTableName("orders", null));
        }
    }

    @Nested
    class ResolveRelevantTables {

        @Test
        void substringMatchFindsTableByName() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("orders", "public"),
                    table("customers", "public"),
                    table("products", "public"));

            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, "show me all orders from last week", List.of(), "postgresql");

            assertThat(result).contains(new QualifiedTableName("orders", null));
        }

        @Test
        void combinesSubstringAndRagSignals() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("orders", "public"),
                    table("customers", "public"),
                    table("products", "public"));

            List<TrainingDataEmbedding> ragResults = List.of(ragResult("customers"));

            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, "show me all orders", ragResults, "postgresql");

            assertThat(result).containsExactlyInAnyOrder(
                    new QualifiedTableName("orders", null),
                    new QualifiedTableName("customers", null));
        }

        @Test
        void graphExpansionAddsFkNeighbors() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchemaWithFks("postgresql",
                    List.of(table("orders", "public"), table("customers", "public"),
                            table("products", "public"), table("categories", "public")),
                    List.of(fk("orders", "customer_id", "customers", "id")));

            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, "show me all orders", List.of(), "postgresql");

            // orders matched by substring, customers by 1-hop FK expansion
            assertThat(result).contains(
                    new QualifiedTableName("orders", null),
                    new QualifiedTableName("customers", null));
        }

        @Test
        void twoHopExpansionOnlyIfNeighborInRag() {
            mockPostgresDialect();
            // orders → customers → addresses
            SchemaMetadata schema = buildSchemaWithFks("postgresql",
                    List.of(table("orders", "public"), table("customers", "public"),
                            table("addresses", "public")),
                    List.of(fk("orders", "customer_id", "customers", "id"),
                            fk("customers", "address_id", "addresses", "id")));

            // addresses NOT in RAG → 2-hop should not include it
            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, "show me all orders", List.of(), "postgresql");

            assertThat(result).contains(
                    new QualifiedTableName("orders", null),
                    new QualifiedTableName("customers", null));
            // addresses NOT included (2-hop gate: customers not in ragTables)
            assertThat(result).doesNotContain(new QualifiedTableName("addresses", null));
        }

        @Test
        void twoHopExpansionIncludesWhenNeighborInRag() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchemaWithFks("postgresql",
                    List.of(table("orders", "public"), table("customers", "public"),
                            table("addresses", "public")),
                    List.of(fk("orders", "customer_id", "customers", "id"),
                            fk("customers", "address_id", "addresses", "id")));

            // customers IS in RAG → 2-hop should include addresses
            List<TrainingDataEmbedding> ragResults = List.of(ragResult("customers"));

            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, "show me all orders", ragResults, "postgresql");

            assertThat(result).contains(
                    new QualifiedTableName("orders", null),
                    new QualifiedTableName("customers", null),
                    new QualifiedTableName("addresses", null));
        }

        @Test
        void capsAtFifteenTables() {
            mockPostgresDialect();
            List<TableMetadata> tables = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                tables.add(table("table_" + i, "public"));
            }
            SchemaMetadata schema = buildSchema("postgresql",
                    tables.toArray(new TableMetadata[0]));

            // Question mentions all 20 tables
            String question = String.join(" ", tables.stream()
                    .map(TableMetadata::getName).toList());

            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, question, List.of(), "postgresql");

            assertThat(result).hasSize(15);
        }

        @Test
        void caseInsensitiveSubstringMatch() {
            mockPostgresDialect();
            SchemaMetadata schema = buildSchema("postgresql",
                    table("ORDERS", "public"),
                    table("customers", "public"));

            Set<QualifiedTableName> result = trainingService.resolveRelevantTables(
                    schema, "Show me all ORDERS", List.of(), "postgresql");

            // Should match despite case difference
            assertThat(result).isNotEmpty();
        }
    }

    // --- Helpers ---

    private TableMetadata table(String name, String schema) {
        TableMetadata t = new TableMetadata();
        t.setName(name);
        t.setSchema(schema);
        t.setColumns(new ArrayList<>());
        t.setIndexes(new ArrayList<>());
        return t;
    }

    private RelationshipMetadata fk(String fromTable, String fromCol,
                                     String toTable, String toCol) {
        RelationshipMetadata r = new RelationshipMetadata();
        r.setFromTable(fromTable);
        r.setFromColumn(fromCol);
        r.setToTable(toTable);
        r.setToColumn(toCol);
        return r;
    }

    private SchemaMetadata buildSchema(String dbType, TableMetadata... tables) {
        return buildSchemaWithFks(dbType, List.of(tables), List.of());
    }

    private SchemaMetadata buildSchemaWithFks(String dbType, List<TableMetadata> tables,
                                               List<RelationshipMetadata> relationships) {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType(dbType);
        schema.setTables(new ArrayList<>(tables));
        schema.setRelationships(new ArrayList<>(relationships));
        return schema;
    }

    private TrainingDataEmbedding ragResult(String tablesUsed) {
        String metadata;
        try {
            metadata = objectMapper.writeValueAsString(Map.of("tablesUsed", tablesUsed));
        } catch (Exception e) {
            metadata = "{}";
        }
        return TrainingDataEmbedding.builder()
                .id(UUID.randomUUID().toString())
                .type(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE)
                .metadata(metadata)
                .build();
    }
}
