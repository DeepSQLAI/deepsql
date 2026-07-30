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
        var doc = mockQueryExample(0.85, "Show hotels", "SELECT * FROM hotels");
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
              "tables": ["bookings", "hotels"],
              "columns": {"bookings": ["status"], "hotels": ["name"]},
              "filterColumns": [{"table": "bookings", "column": "status"}],
              "joinConditions": ["bookings.hotel_id = hotels.id"],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("status", "hotel_id"),
            "hotels", List.of("name", "id"),
            "rooms", List.of("type")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("bookings", "hotels");
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
              "tables": ["bookings", "hotels"],
              "columns": {"bookings": ["hotel_id"], "hotels": ["id"]},
              "filterColumns": [],
              "joinConditions": [
                "bookings.hotel_id = hotels.id",
                "bookings.fake_id = hotels.id",
                "bookings.hotel_id = ghost_table.id"
              ],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("hotel_id"),
            "hotels", List.of("id")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.joinConditions()).containsExactly("bookings.hotel_id = hotels.id");
    }

    @Test
    void step2_deduplicatesTablesColumnsAndFilterColumns() {
        String json = """
            {
              "tables": ["bookings", "bookings", "hotels"],
              "columns": {
                "bookings": ["status", "status", "hotel_id"],
                "hotels": ["name", "name"]
              },
              "filterColumns": [
                {"table": "bookings", "column": "status"},
                {"table": "bookings", "column": "status"}
              ],
              "joinConditions": [
                "bookings.hotel_id = hotels.id",
                "bookings.hotel_id = hotels.id"
              ],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "bookings", List.of("status", "hotel_id"),
            "hotels", List.of("name", "id")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("bookings", "hotels");
        assertThat(result.columns().get("bookings")).containsExactly("status", "hotel_id");
        assertThat(result.columns().get("hotels")).containsExactly("name");
        assertThat(result.filterColumns()).containsExactly(new FilterColumn("bookings", "status"));
        assertThat(result.joinConditions()).containsExactly("bookings.hotel_id = hotels.id");
    }

    @Test
    void step2_caseConflictingTablesPreferPopulatedCanonicalTable() {
        String json = """
            {
              "tables": ["user_bookings"],
              "columns": {"user_bookings": ["hotel_id", "booking_amount", "booking_made_on", "booking_source"]},
              "filterColumns": [{"table": "user_bookings", "column": "booking_source"}],
              "joinConditions": ["user_bookings.hotel_id = HOTEL.id"],
              "confidence": "HIGH"
            }
            """;

        SchemaMetadata schemaMetadata = new SchemaMetadata();
        schemaMetadata.setTables(List.of(
            new TableMetadata("user_bookings", null, "table", 0L, null, List.of(
                new ColumnMetadata("hotel_id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("created_at", "timestamp", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("USER_BOOKINGS", null, "table", 9_419_333L, null, List.of(
                new ColumnMetadata("hotel_id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2),
                new ColumnMetadata("booking_made_on", "timestamp", null, true, false, null, 3),
                new ColumnMetadata("booking_source", "varchar", null, true, false, null, 4)
            ), List.of()),
            new TableMetadata("HOTEL", null, "table", 120L, null, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of())
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("USER_BOOKINGS");
        assertThat(result.columns()).containsKey("USER_BOOKINGS");
        assertThat(result.columns().get("USER_BOOKINGS"))
            .containsExactly("hotel_id", "booking_amount", "booking_made_on", "booking_source");
        assertThat(result.filterColumns()).containsExactly(new FilterColumn("USER_BOOKINGS", "booking_source"));
        assertThat(result.joinConditions()).containsExactly("USER_BOOKINGS.hotel_id = HOTEL.id");
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
            "bookings", List.of("status", "hotel_id")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.filterColumns()).containsExactly(new FilterColumn("bookings", "status"));
    }

    @Test
    void postProcessResolvedContext_addsValidatedJoinForRequestedCompanionEntity() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "BOOKING_TAXES", List.of("booking_id", "tax_name"),
            "USER_BOOKINGS", List.of("id", "booking_amount")
        ));
        when(semanticModelService.getSemanticJoins("conn-1", List.of("BOOKING_TAXES"))).thenReturn(List.of(
            SemanticJoinModel.builder()
                .connectionId("conn-1")
                .sourceTable("BOOKING_TAXES")
                .sourceColumn("booking_id")
                .targetTable("USER_BOOKINGS")
                .targetColumn("id")
                .joinExpression("BOOKING_TAXES.booking_id = USER_BOOKINGS.id")
                .preferred(true)
                .build()
        ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "Show booking taxes along with the booking amounts from USER_BOOKINGS",
            schema,
            new ResolvedContext(
                List.of("BOOKING_TAXES"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).contains("BOOKING_TAXES", "USER_BOOKINGS");
        assertThat(enhanced.joinConditions()).contains("BOOKING_TAXES.booking_id = USER_BOOKINGS.id");
    }

    @Test
    void postProcessResolvedContext_usesSchemaRelationshipWhenSemanticJoinModelIsMissing() {
        SchemaMetadata schemaMetadata = new SchemaMetadata();
        schemaMetadata.setTables(List.of(
            new TableMetadata("USER_BOOKINGS", null, "table", 1000L, null, List.of(
                new ColumnMetadata("id", "varchar", null, false, true, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("GUEST_MAPPING", null, "table", 1000L, null, List.of(
                new ColumnMetadata("booking_id", "varchar", null, false, false, null, 1),
                new ColumnMetadata("user_name", "varchar", null, true, false, null, 2),
                new ColumnMetadata("email", "varchar", null, true, false, null, 3)
            ), List.of())
        ));
        schemaMetadata.setRelationships(List.of(
            new RelationshipMetadata("guest_booking", "GUEST_MAPPING", "booking_id", "USER_BOOKINGS", "id", "many-to-one", "fk_guest_booking")
        ));

        when(semanticModelService.getSemanticJoins(eq("conn-1"), anyList())).thenReturn(List.of());

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("USER_BOOKINGS"),
            Map.of("USER_BOOKINGS", List.of("booking_amount")),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        ResolvedContext result = ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "Show booking amounts with guest emails for each booking",
            schemaMetadata,
            resolvedContext
        );

        assertThat(result.tables()).contains("USER_BOOKINGS", "GUEST_MAPPING");
        assertThat(result.joinConditions()).contains("GUEST_MAPPING.booking_id = USER_BOOKINGS.id");
    }

    @Test
    void postProcessResolvedContext_prefersJoinedEntityDetailColumnsOverFactDuplicates() {
        SchemaMetadata schemaMetadata = new SchemaMetadata();
        schemaMetadata.setTables(List.of(
            new TableMetadata("USER_BOOKINGS", null, "table", 1000L, null, List.of(
                new ColumnMetadata("id", "varchar", null, false, true, null, 1),
                new ColumnMetadata("booking_amount", "decimal", null, true, false, null, 2),
                new ColumnMetadata("user_email", "varchar", null, true, false, null, 3)
            ), List.of()),
            new TableMetadata("GUEST_MAPPING", null, "table", 1000L, null, List.of(
                new ColumnMetadata("booking_id", "varchar", null, false, false, null, 1),
                new ColumnMetadata("user_name", "varchar", null, true, false, null, 2),
                new ColumnMetadata("email", "varchar", null, true, false, null, 3)
            ), List.of())
        ));
        schemaMetadata.setRelationships(List.of(
            new RelationshipMetadata("guest_booking", "GUEST_MAPPING", "booking_id", "USER_BOOKINGS", "id", "many-to-one", "fk_guest_booking")
        ));

        when(semanticModelService.getSemanticJoins(eq("conn-1"), anyList())).thenReturn(List.of());

        ResolvedContext result = ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "Show booking amounts with guest emails for each booking",
            schemaMetadata,
            new ResolvedContext(
                List.of("USER_BOOKINGS", "GUEST_MAPPING"),
                Map.of(
                    "USER_BOOKINGS", List.of("id", "booking_amount", "user_email")
                ),
                List.of(),
                List.of("GUEST_MAPPING.booking_id = USER_BOOKINGS.id"),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(result.columns().get("USER_BOOKINGS"))
            .contains("id", "booking_amount")
            .doesNotContain("user_email");
        assertThat(result.columns().get("GUEST_MAPPING"))
            .contains("user_name", "email");
    }

    @Test
    void postProcessResolvedContext_promotesBaseFactTableOverDerivedSummaryForCounts() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "NR_BOOKING", List.of("booking_count"),
            "USER_BOOKINGS", List.of("id", "booking_amount")
        ));
        when(semanticModelService.findRelevantTables("conn-1", "How many bookings are there?", Set.of()))
            .thenReturn(List.of(
                SemanticTableModel.builder().connectionId("conn-1").tableName("NR_BOOKING").tableRole("AGGREGATE").build(),
                SemanticTableModel.builder().connectionId("conn-1").tableName("USER_BOOKINGS").tableRole("FACT").build()
            ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "How many bookings are there?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).containsExactly("USER_BOOKINGS");
    }

    @Test
    void postProcessResolvedContext_promotesBaseFactTableOverAggregateMeasureSummaryForGroupedTotals() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "NR_BOOKING", List.of("hotel_id", "total_booking_amount"),
            "USER_BOOKINGS", List.of("id", "hotel_id", "booking_amount")
        ));
        when(semanticModelService.findRelevantTables("conn-1", "What is the total booking amount per hotel?", Set.of()))
            .thenReturn(List.of(
                SemanticTableModel.builder().connectionId("conn-1").tableName("NR_BOOKING").tableRole("FACT").build(),
                SemanticTableModel.builder().connectionId("conn-1").tableName("USER_BOOKINGS").tableRole("FACT").build()
            ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "What is the total booking amount per hotel?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).containsExactly("USER_BOOKINGS");
    }

    @Test
    void postProcessResolvedContext_usesSchemaFallbackWhenSemanticRankingMissesBaseFactTable() {
        SchemaMetadata schema = buildMockSchema(Map.of(
            "NR_BOOKING", List.of("hotel_id", "total_booking_amount"),
            "USER_BOOKINGS", List.of("id", "hotel_id", "booking_amount")
        ));
        when(semanticModelService.findRelevantTables("conn-1", "What is the total booking amount per hotel?", Set.of()))
            .thenReturn(List.of(
                SemanticTableModel.builder().connectionId("conn-1").tableName("NR_BOOKING").tableRole("FACT").build()
            ));

        ResolvedContext enhanced = (ResolvedContext) ReflectionTestUtils.invokeMethod(
            pipeline,
            "postProcessResolvedContext",
            "conn-1",
            "What is the total booking amount per hotel?",
            schema,
            new ResolvedContext(
                List.of("NR_BOOKING"),
                Map.of(),
                List.of(),
                List.of(),
                ResolvedContext.Confidence.MEDIUM
            )
        );

        assertThat(enhanced.tables()).containsExactly("USER_BOOKINGS");
    }

    @Test
    void step2_preservesValidMultiHopJoinChain() {
        String json = """
            {
              "tables": ["guest_mapping", "bookings", "hotels"],
              "columns": {
                "guest_mapping": ["booking_id", "user_name"],
                "bookings": ["id", "hotel_id", "booking_amount"],
                "hotels": ["id", "name"]
              },
              "filterColumns": [{"table": "bookings", "column": "booking_amount"}],
              "joinConditions": [
                "guest_mapping.booking_id = bookings.id",
                "bookings.hotel_id = hotels.id"
              ],
              "confidence": "HIGH"
            }
            """;

        var schemaMetadata = buildMockSchema(Map.of(
            "guest_mapping", List.of("booking_id", "user_name"),
            "bookings", List.of("id", "hotel_id", "booking_amount"),
            "hotels", List.of("id", "name")
        ));

        var result = pipeline.parseResolvedContext(json, schemaMetadata);

        assertThat(result.tables()).containsExactly("guest_mapping", "bookings", "hotels");
        assertThat(result.joinConditions()).containsExactly(
            "guest_mapping.booking_id = bookings.id",
            "bookings.hotel_id = hotels.id"
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
            List.of("bookings", "hotels"),
            Map.of("bookings", List.of("status"), "hotels", List.of("name")),
            List.of(new FilterColumn("bookings", "status")),
            List.of("bookings.hotel_id = hotels.id"),
            ResolvedContext.Confidence.HIGH
        );

        String hints = pipeline.buildResolutionHints(resolved);

        assertThat(hints)
            .contains("Tables identified as relevant: bookings, hotels")
            .contains("bookings.hotel_id = hotels.id")
            .contains("bookings.status");
    }

    @Test
    void step2_fastPath_singleTableInQuestion() {
        var schema = buildMockSchema(Map.of(
            "bookings", List.of("status", "hotel_id"),
            "hotels", List.of("name")
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
            "hotels", List.of("name")
        ));
        var ctx = new PipelineContext(
            "conn-1", "Show bookings with hotels", "POSTGRESQL",
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
            "guest_mapping", List.of("booking_id", "user_name"),
            "bookings", List.of("status")
        ));
        var ctx = new PipelineContext(
            "conn-1", "Show guest mapping records for recent guests", "POSTGRESQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);
        assertThat(result).isPresent().hasValue("guest_mapping");
    }

    @Test
    void step2_fastPath_deduplicatesCaseConflictingTableVariants() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata("hotel", null, "table", 0L, null, List.of(
                new ColumnMetadata("id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("created_at", "timestamp", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("HOTEL", null, "table", 1_500L, null, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1),
                new ColumnMetadata("subscription_start_date", "timestamp", null, true, false, null, 2)
            ), List.of()),
            new TableMetadata("HOTEL_SERVICES", null, "table", 50_000L, null, List.of(
                new ColumnMetadata("hotel_id", "bigint", null, false, false, null, 1),
                new ColumnMetadata("last_updated", "timestamp", null, true, false, null, 2)
            ), List.of())
        ));
        var ctx = new PipelineContext(
            "conn-1", "How many hotels are onboarded in the last 3 days?", "MYSQL",
            "", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.detectSingleTableFastPath(ctx);

        assertThat(result).isPresent().hasValue("HOTEL");
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
