package com.dbaagent.service.pipeline;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SqlExecutionPipeline;
import com.dbaagent.service.TrainingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryGenerationPipelineTest {

    @Mock private TrainingService trainingService;
    @Mock private ConnectionService connectionService;
    @Mock private SqlExecutionPipeline sqlExecutionPipeline;
    @Mock private ChatClient chatClient;
    @Mock private ColumnValueFetcher columnValueFetcher;
    @Mock private SqlValidator sqlValidator;
    @Mock private SemanticModelService semanticModelService;

    private QueryGenerationPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new QueryGenerationPipeline(
            trainingService, connectionService, sqlExecutionPipeline, chatClient,
            new ClassPathResource("prompts/sql-adaptation-prompt.st"),
            new ClassPathResource("prompts/table-resolution-prompt.st"),
            0.92, columnValueFetcher, sqlValidator, semanticModelService
        );
    }

    @Test
    void step1_noHistoryMatch_returnsEmpty() {
        when(trainingService.cachedRetrieveRelevant(anyString(), anyString(), anyInt()))
            .thenReturn(List.of());

        var result = pipeline.matchQueryHistory("conn-1", "Show bookings by status");

        assertThat(result).isEmpty();
    }

    @Test
    void step1_lowSimilarity_returnsEmpty() {
        var doc = mockQueryExample(0.85, "Show customers", "SELECT * FROM customers");
        when(trainingService.cachedRetrieveRelevant(anyString(), anyString(), anyInt()))
            .thenReturn(List.of(doc));

        var result = pipeline.matchQueryHistory("conn-1", "Show bookings by status");

        assertThat(result).isEmpty();
    }

    @Test
    void step1_extractsSqlFromQueryExampleContent() {
        String content = "Show all bookings by status\nSELECT * FROM bookings ORDER BY status";
        String metadata = "{\"sql\": \"SELECT * FROM bookings ORDER BY status\"}";

        String extractedSql = pipeline.extractSqlFromQueryExample(content, metadata);

        assertThat(extractedSql).isEqualTo("SELECT * FROM bookings ORDER BY status");
    }

    @Test
    void step1_extractsSqlFromMetadataFallback() {
        String content = "just some text without newline-separated SQL";
        String metadata = "{\"sql\": \"SELECT 1\"}";

        String extractedSql = pipeline.extractSqlFromQueryExample(content, metadata);

        assertThat(extractedSql).isEqualTo("SELECT 1");
    }

    @Test
    void step2_parsesResolvedContextFromJson() {
        String json = """
            {
              "tables": ["bookings", "customers"],
              "columns": {"bookings": ["status"], "customers": ["name"]},
              "filterColumns": [{"table": "bookings", "column": "status"}],
              "joinConditions": ["bookings.customer_id = customers.id"],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("status", "customer_id"),
            "customers", List.of("name", "id"),
            "rooms", List.of("type")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("bookings", "customers");
        assertThat(result.filterColumns()).hasSize(1);
        assertThat(result.filterColumns().getFirst().qualifiedName()).isEqualTo("bookings.status");
        assertThat(result.confidence()).isEqualTo(ResolvedContext.Confidence.HIGH);
    }

    @Test
    void step2_removesHallucinatedTables() {
        String json = """
            {
              "tables": ["bookings", "fake_table"],
              "columns": {"bookings": ["status"], "fake_table": ["x"]},
              "filterColumns": [],
              "joinConditions": [],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of("bookings", List.of("status")));
        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("bookings");
        assertThat(result.columns()).doesNotContainKey("fake_table");
    }

    @Test
    void step2_malformedJsonReturnsEmpty() {
        var schemaMetadata = buildMockSchema(Map.of("bookings", List.of("status")));
        var result = pipeline.parseResolvedContext("not json", schemaMetadata);
        assertThat(result.isEmpty()).isTrue();
    }

    @Test
    void step2_removesHallucinatedJoinConditions() {
        String json = """
            {
              "tables": ["bookings", "customers"],
              "columns": {"bookings": ["customer_id"], "customers": ["id"]},
              "filterColumns": [],
              "joinConditions": [
                "bookings.customer_id = customers.id",
                "bookings.fake_id = customers.id",
                "bookings.customer_id = ghost_table.id"
              ],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("customer_id"),
            "customers", List.of("id")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.joinConditions()).containsExactly("bookings.customer_id = customers.id");
    }

    @Test
    void step2_deduplicatesTablesColumnsAndFilterColumns() {
        String json = """
            {
              "tables": ["bookings", "bookings", "customers"],
              "columns": {
                "bookings": ["status", "status", "customer_id"],
                "customers": ["name", "name"]
              },
              "filterColumns": [
                {"table": "bookings", "column": "status"},
                {"table": "bookings", "column": "status"}
              ],
              "joinConditions": [
                "bookings.customer_id = customers.id",
                "bookings.customer_id = customers.id"
              ],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("status", "customer_id"),
            "customers", List.of("name", "id")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("bookings", "customers");
        assertThat(result.columns().get("bookings")).containsExactly("status", "customer_id");
        assertThat(result.columns().get("customers")).containsExactly("name");
        assertThat(result.filterColumns()).containsExactly(new FilterColumn("bookings", "status"));
        assertThat(result.joinConditions()).containsExactly("bookings.customer_id = customers.id");
    }

    @Test
    void step2_caseConflictingTablesPreferPopulatedCanonicalTable() {
        String json = """
            {
              "tables": ["customer_orders"],
              "columns": {"customer_orders": ["customer_id", "booking_amount", "booking_made_on", "booking_source"]},
              "filterColumns": [{"table": "customer_orders", "column": "booking_source"}],
              "joinConditions": ["customer_orders.customer_id = CUSTOMERS.id"],
              "confidence": "HIGH"
            }
            """;

        SchemaMetadata schemaMetadata = new SchemaMetadata();
        schemaMetadata.setTables(List.of(
            new TableMetadata("customer_orders", null, "table", 0L, null, List.of(
                new ColumnMetadata("customer_id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("created_at", "timestamp", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("CUSTOMER_ORDERS", null, "table", 9_419_333L, null, List.of(
                new ColumnMetadata("customer_id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2),
                new ColumnMetadata("booking_made_on", "timestamp", null, true, false, null, 3),
                new ColumnMetadata("booking_source", "varchar", null, true, false, null, 4)
            ), List.of()),
            new TableMetadata("CUSTOMERS", null, "table", 120L, null, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of())
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("CUSTOMER_ORDERS");
        assertThat(result.columns()).containsKey("CUSTOMER_ORDERS");
        assertThat(result.columns().get("CUSTOMER_ORDERS"))
            .containsExactly("customer_id", "booking_amount", "booking_made_on", "booking_source");
        assertThat(result.filterColumns()).containsExactly(new FilterColumn("CUSTOMER_ORDERS", "booking_source"));
        assertThat(result.joinConditions()).containsExactly("CUSTOMER_ORDERS.customer_id = CUSTOMERS.id");
    }

    @Test
    void step2_removesHallucinatedFilterColumns() {
        String json = """
            {
              "tables": ["bookings"],
              "columns": {"bookings": ["status"]},
              "filterColumns": [
                {"table": "bookings", "column": "status"},
                {"table": "bookings", "column": "ghost_column"},
                {"table": "ghost_table", "column": "status"}
              ],
              "joinConditions": [],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("status", "customer_id")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.filterColumns()).containsExactly(new FilterColumn("bookings", "status"));
    }

    @Test
    void postProcessResolvedContext_addsValidatedJoinForRequestedCompanionEntity() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "ORDER_TAXES", List.of("booking_id", "tax_name"),
            "CUSTOMER_ORDERS", List.of("id", "booking_amount")
        ));
        when(semanticModelService.getSemanticJoins("conn-1", List.of("ORDER_TAXES"))).thenReturn(List.of(
            SemanticJoinModel.builder()
                .connectionId("conn-1")
                .sourceTable("ORDER_TAXES")
                .sourceColumn("booking_id")
                .targetTable("CUSTOMER_ORDERS")
                .targetColumn("id")
                .joinExpression("ORDER_TAXES.booking_id = CUSTOMER_ORDERS.id")
                .preferred(true)
                .build()
        ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "Show booking taxes along with the booking amounts from CUSTOMER_ORDERS",
            schema,
            new ResolvedContext(
                List.of("ORDER_TAXES"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).contains("ORDER_TAXES", "CUSTOMER_ORDERS");
        assertThat(enhanced.joinConditions()).contains("ORDER_TAXES.booking_id = CUSTOMER_ORDERS.id");
    }

    @Test
    void postProcessResolvedContext_usesSchemaRelationshipWhenSemanticJoinModelIsMissing() {
        SchemaMetadata schemaMetadata = new SchemaMetadata();
        schemaMetadata.setTables(List.of(
            new TableMetadata("CUSTOMER_ORDERS", null, "table", 1000L, null, List.of(
                new ColumnMetadata("id", "varchar", null, false, true, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("CONTACT_MAPPING", null, "table", 1000L, null, List.of(
                new ColumnMetadata("booking_id", "varchar", null, false, false, null, 1),
                new ColumnMetadata("user_name", "varchar", null, true, false, null, 2),
                new ColumnMetadata("email", "varchar", null, true, false, null, 3)
            ), List.of())
        ));
        schemaMetadata.setRelationships(List.of(
            new RelationshipMetadata("guest_booking", "CONTACT_MAPPING", "booking_id", "CUSTOMER_ORDERS", "id", "many-to-one", "fk_guest_booking")
        ));

        when(semanticModelService.getSemanticJoins(eq("conn-1"), anyList())).thenReturn(List.of());

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("CUSTOMER_ORDERS"),
            Map.of("CUSTOMER_ORDERS", List.of("booking_amount")),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        ResolvedContext result = ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "Show order amounts with contact emails for each order",
            schemaMetadata,
            resolvedContext
        );

        assertThat(result.tables()).contains("CUSTOMER_ORDERS", "CONTACT_MAPPING");
        assertThat(result.joinConditions()).contains("CONTACT_MAPPING.booking_id = CUSTOMER_ORDERS.id");
    }

    @Test
    void postProcessResolvedContext_prefersJoinedEntityDetailColumnsOverFactDuplicates() {
        SchemaMetadata schemaMetadata = new SchemaMetadata();
        schemaMetadata.setTables(List.of(
            new TableMetadata("CUSTOMER_ORDERS", null, "table", 1000L, null, List.of(
                new ColumnMetadata("id", "varchar", null, false, true, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2),
                new ColumnMetadata("user_email", "varchar", null, true, false, null, 3)
            ), List.of()),
            new TableMetadata("CONTACT_MAPPING", null, "table", 1000L, null, List.of(
                new ColumnMetadata("booking_id", "varchar", null, false, false, null, 1),
                new ColumnMetadata("user_name", "varchar", null, true, false, null, 2),
                new ColumnMetadata("email", "varchar", null, true, false, null, 3)
            ), List.of())
        ));
        schemaMetadata.setRelationships(List.of(
            new RelationshipMetadata("guest_booking", "CONTACT_MAPPING", "booking_id", "CUSTOMER_ORDERS", "id", "many-to-one", "fk_guest_booking")
        ));

        when(semanticModelService.getSemanticJoins(eq("conn-1"), anyList())).thenReturn(List.of());

        ResolvedContext result = ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "Show order amounts with contact names and emails for each order",
            schemaMetadata,
            new ResolvedContext(
                List.of("CUSTOMER_ORDERS", "CONTACT_MAPPING"),
                Map.of(
                    "CUSTOMER_ORDERS", List.of("id", "booking_amount", "user_email")
                ),
                List.of(),
                List.of("CONTACT_MAPPING.booking_id = CUSTOMER_ORDERS.id"),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(result.columns().get("CUSTOMER_ORDERS"))
            .contains("id", "booking_amount")
            .doesNotContain("user_email");
        assertThat(result.columns().get("CONTACT_MAPPING"))
            .contains("user_name", "email");
    }

    @Test
    void postProcessResolvedContext_promotesBaseFactTableOverDerivedSummaryForCounts() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "NR_ORDER_SUMMARY", List.of("order_count"),
            "CUSTOMER_ORDERS", List.of("id", "booking_amount")
        ));
        when(semanticModelService.findRelevantTables("conn-1", "How many orders are there?", Set.of()))
            .thenReturn(List.of(
                SemanticTableModel.builder().connectionId("conn-1").tableName("NR_ORDER_SUMMARY").tableRole("AGGREGATE").build(),
                SemanticTableModel.builder().connectionId("conn-1").tableName("CUSTOMER_ORDERS").tableRole("FACT").build()
            ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "How many orders are there?",
            schema,
            new ResolvedContext(
                List.of("NR_ORDER_SUMMARY"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).containsExactly("CUSTOMER_ORDERS");
    }

    @Test
    void postProcessResolvedContext_promotesBaseFactTableOverAggregateMeasureSummaryForGroupedTotals() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "NR_ORDER_SUMMARY", List.of("customer_id", "total_booking_amount"),
            "CUSTOMER_ORDERS", List.of("id", "customer_id", "booking_amount")
        ));
        when(semanticModelService.findRelevantTables("conn-1", "What is the total booking amount per customer?", Set.of()))
            .thenReturn(List.of(
                SemanticTableModel.builder().connectionId("conn-1").tableName("NR_ORDER_SUMMARY").tableRole("FACT").build(),
                SemanticTableModel.builder().connectionId("conn-1").tableName("CUSTOMER_ORDERS").tableRole("FACT").build()
            ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "What is the total booking amount per customer?",
            schema,
            new ResolvedContext(
                List.of("NR_ORDER_SUMMARY"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).containsExactly("CUSTOMER_ORDERS");
    }

    @Test
    void postProcessResolvedContext_usesSchemaFallbackWhenSemanticRankingMissesBaseFactTable() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "NR_ORDER_SUMMARY", List.of("customer_id", "total_booking_amount"),
            "CUSTOMER_ORDERS", List.of("id", "customer_id", "booking_amount")
        ));
        when(semanticModelService.findRelevantTables("conn-1", "What is the total booking amount per customer?", Set.of()))
            .thenReturn(List.of(
                SemanticTableModel.builder().connectionId("conn-1").tableName("NR_ORDER_SUMMARY").tableRole("FACT").build()
            ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "What is the total booking amount per customer?",
            schema,
            new ResolvedContext(
                List.of("NR_ORDER_SUMMARY"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).containsExactly("CUSTOMER_ORDERS");
    }

    @Test
    void step2_preservesValidMultiHopJoinChain() {
        String json = """
            {
              "tables": ["contact_mapping", "bookings", "customers"],
              "columns": {
                "contact_mapping": ["booking_id", "user_name"],
                "bookings": ["id", "customer_id", "booking_amount"],
                "customers": ["id", "name"]
              },
              "filterColumns": [{"table": "bookings", "column": "booking_amount"}],
              "joinConditions": [
                "contact_mapping.booking_id = bookings.id",
                "bookings.customer_id = customers.id"
              ],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "contact_mapping", List.of("booking_id", "user_name"),
            "bookings", List.of("id", "customer_id", "booking_amount"),
            "customers", List.of("id", "name")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("contact_mapping", "bookings", "customers");
        assertThat(result.joinConditions()).containsExactly(
            "contact_mapping.booking_id = bookings.id",
            "bookings.customer_id = customers.id"
        );
    }

    @Test
    void step3_delegatesToColumnValueFetcher() {
        var filterColumns = List.of(new FilterColumn("bookings", "status"));
        var expectedCtx = new ColumnValueContext(
            Map.of("bookings.status", List.of("CONFIRMED", "CANCELLED")),
            "=== COLUMN VALUES ===\nbookings.status: CONFIRMED, CANCELLED",
            50L, List.of()
        );
        when(columnValueFetcher.fetch(eq("conn-1"), eq("POSTGRESQL"), eq(filterColumns)))
            .thenReturn(expectedCtx);

        var result = columnValueFetcher.fetch("conn-1", "POSTGRESQL", filterColumns);

        assertThat(result.valueMap()).containsKey("bookings.status");
        assertThat(result.formattedContext()).contains("CONFIRMED");
        verify(columnValueFetcher).fetch("conn-1", "POSTGRESQL", filterColumns);
    }

    @Test
    void buildResolutionHints_formatsCorrectly() {
        var resolved = new ResolvedContext(
            List.of("bookings", "customers"),
            Map.of("bookings", List.of("status"), "customers", List.of("name")),
            List.of(new FilterColumn("bookings", "status")),
            List.of("bookings.customer_id = customers.id"),
            ResolvedContext.Confidence.HIGH
        );

        String hints = pipeline.buildResolutionHints(resolved);

        assertThat(hints)
            .contains("Tables identified as relevant: bookings, customers")
            .contains("bookings.customer_id = customers.id")
            .contains("bookings.status");
    }

    @Test
    void step2_fastPath_singleTableInQuestion() {
        var schema = buildMockSchema(Map.of(
            "bookings", List.of("status", "customer_id"),
            "customers", List.of("name")
        ));
        var ctx = new PipelineContext(
            "conn-1", "Show all bookings by status", "POSTGRESQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);
        assertThat(result).isPresent().hasValue("bookings");
    }

    @Test
    void step2_fastPath_multipleTablesSkipsFastPath() {
        var schema = buildMockSchema(Map.of(
            "bookings", List.of("status"),
            "customers", List.of("name")
        ));
        var ctx = new PipelineContext(
            "conn-1", "Show bookings with customers", "POSTGRESQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);
        assertThat(result).isEmpty();
    }

    @Test
    void step2_fastPath_respectsWordBoundaries() {
        var schema = buildMockSchema(Map.of(
            "orders", List.of("status"),
            "preorders", List.of("status")
        ));
        var ctx = new PipelineContext(
            "conn-1", "Show preorder status trends", "POSTGRESQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);
        assertThat(result).isPresent().hasValue("preorders");
    }

    @Test
    void step2_fastPath_matchesUnderscoreTableNamesFromNaturalLanguage() {
        var schema = buildMockSchema(Map.of(
            "contact_mapping", List.of("booking_id", "user_name"),
            "bookings", List.of("status")
        ));
        var ctx = new PipelineContext(
            "conn-1", "Show guest mapping records for recent guests", "POSTGRESQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);
        assertThat(result).isPresent().hasValue("contact_mapping");
    }

    @Test
    void step2_fastPath_deduplicatesCaseConflictingTableVariants() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata("customers", null, "table", 0L, null, List.of(
                new ColumnMetadata("id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("created_at", "timestamp", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("CUSTOMERS", null, "table", 1_500L, null, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1),
                new ColumnMetadata("subscription_start_date", "timestamp", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("PRODUCT_SERVICES", null, "table", 50_000L, null, List.of(
                new ColumnMetadata("customer_id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("last_updated", "timestamp", null, true, false, null, 2)
            ), List.of())
        ));
        var ctx = new PipelineContext(
            "conn-1", "How many customers are onboarded in the last 3 days?", "MYSQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);

        assertThat(result).isPresent().hasValue("CUSTOMERS");
    }

    /** Helper: build a mock SchemaMetadata with given table→columns mapping */
    private SchemaMetadata buildMockSchema(Map<String, List<String>> tableColumns) {
        var schema = new SchemaMetadata();
        var tables = tableColumns.entrySet().stream().map(e -> {
            var table = new TableMetadata();
            table.setName(e.getKey());
            table.setColumns(e.getValue().stream().map(colName -> {
                var col = new ColumnMetadata();
                col.setName(colName);
                return col;
            }).toList());
            return table;
        }).toList();
        schema.setTables(tables);
        return schema;
    }

    private TrainingDataEmbedding mockQueryExample(double score, String question, String sql) {
        var doc = mock(TrainingDataEmbedding.class);
        lenient().when(doc.getScore()).thenReturn(score);
        lenient().when(doc.getType()).thenReturn(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE);
        lenient().when(doc.getContent()).thenReturn(sql);
        lenient().when(doc.getMetadata()).thenReturn("{\"question\": \"" + question + "\"}");
        return doc;
    }
}
