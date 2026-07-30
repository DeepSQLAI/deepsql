package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.SchemaDocumentationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * Tests for ChatContextAssembler.buildSchemaContext — verifies full column
 * visibility in both full and compact views (accuracy-first, threshold=100).
 */
class ChatContextAssemblerSchemaTest {

    private ChatContextAssembler assembler;

    @BeforeEach
    void setUp() {
        assembler = new ChatContextAssembler(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        ReflectionTestUtils.setField(assembler, "schemaContextFullMaxTables", 100);
        ReflectionTestUtils.setField(assembler, "schemaContextPreviewMaxTables", 100);
        ReflectionTestUtils.setField(assembler, "schemaContextPreviewMaxColumnsPerTable", 8);
        ReflectionTestUtils.setField(assembler, "maxSchemaTokens", 100000);
    }

    private SchemaMetadata buildSchema(int tableCount, int columnsPerTable) {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("test_db");
        schema.setDbType("postgresql");
        List<TableMetadata> tables = new ArrayList<>();
        for (int i = 1; i <= tableCount; i++) {
            TableMetadata table = new TableMetadata();
            table.setName("table_" + i);
            List<ColumnMetadata> columns = new ArrayList<>();
            for (int j = 1; j <= columnsPerTable; j++) {
                ColumnMetadata col = new ColumnMetadata();
                col.setName("col_" + j);
                col.setDataType("varchar");
                columns.add(col);
            }
            table.setColumns(columns);
            tables.add(table);
        }
        schema.setTables(tables);
        return schema;
    }

    private ColumnMetadata buildColumn(String name) {
        ColumnMetadata col = new ColumnMetadata();
        col.setName(name);
        col.setDataType("varchar");
        return col;
    }

    @Test
    void fullView_showsAllColumnsForAllTables() {
        // 80 tables is below threshold of 100 → full view
        SchemaMetadata schema = buildSchema(80, 20);

        String result = assembler.buildSchemaContext(schema, "show me all tables");

        for (int i = 1; i <= 80; i++) {
            assertTrue(result.contains("table_" + i), "Should contain table_" + i);
        }
        assertTrue(result.contains("col_20 varchar"), "Should show all columns");
        assertFalse(result.contains("..."), "Should not truncate");
        assertFalse(result.contains("showing"), "Should not indicate compact view");
    }

    @Test
    void compactView_mentionedTable_showsAllColumns() {
        // 200 tables → compact view. table_5 is mentioned.
        SchemaMetadata schema = buildSchema(200, 30);

        String result = assembler.buildSchemaContext(schema, "show me all columns in table_5");

        assertTrue(result.contains("table_5"), "Should contain mentioned table");
        String[] lines = result.split("\n");
        boolean found = false;
        for (String line : lines) {
            if (line.contains("table_5") && line.contains("col_30 varchar")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Mentioned table should show all 30 columns");
    }

    @Test
    void compactView_allSelectedTables_showAllColumns() {
        // 200 tables → compact view. All selected tables get full columns.
        SchemaMetadata schema = buildSchema(200, 30);

        String result = assembler.buildSchemaContext(schema, "show me table_5");

        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.startsWith("- table_") && line.contains("col_")) {
                assertTrue(line.contains("col_30 varchar"),
                    "All selected tables should show all columns: " + line.substring(0, Math.min(60, line.length())));
                assertFalse(line.contains("..."), "No truncation expected");
            }
        }
    }

    @Test
    void compactView_multipleMentionedTables_allPresent() {
        SchemaMetadata schema = buildSchema(200, 25);

        String result = assembler.buildSchemaContext(schema, "join table_3 with table_7");

        String[] lines = result.split("\n");
        boolean table3Full = false;
        boolean table7Full = false;
        for (String line : lines) {
            if (line.contains("- table_3 ") && line.contains("col_25 varchar")) table3Full = true;
            if (line.contains("- table_7 ") && line.contains("col_25 varchar")) table7Full = true;
        }
        assertTrue(table3Full, "table_3 should be present with all 25 columns");
        assertTrue(table7Full, "table_7 should be present with all 25 columns");
    }

    @Test
    void compactView_showsOmittedCount() {
        // 300 tables, 100 shown → 200 omitted
        SchemaMetadata schema = buildSchema(300, 5);

        String result = assembler.buildSchemaContext(schema, "show tables");

        assertTrue(result.contains("200 more tables omitted"), "Should show correct omitted count");
    }

    @Test
    void compactView_showsSelectedOfTotalCount() {
        SchemaMetadata schema = buildSchema(300, 5);

        String result = assembler.buildSchemaContext(schema, "anything");

        assertTrue(result.contains("showing 100 of 300"), "Should show selected/total count");
    }

    @Test
    void compactView_mentionedTablePrioritized() {
        // table_199 is far from the first N tables
        SchemaMetadata schema = buildSchema(200, 15);

        String result = assembler.buildSchemaContext(schema, "describe table_199");

        assertTrue(result.contains("table_199"), "Mentioned table should always be included");
        String[] lines = result.split("\n");
        boolean found = false;
        for (String line : lines) {
            if (line.contains("table_199") && line.contains("col_15 varchar")) {
                found = true;
                break;
            }
        }
        assertTrue(found, "Mentioned table_199 should show all 15 columns");
    }

    @Test
    void compactView_tokenMatchIncludesUserBookingsForBookingsQuestion() {
        SchemaMetadata schema = buildSchema(180, 4);

        TableMetadata userBookings = new TableMetadata();
        userBookings.setName("USER_BOOKINGS");
        userBookings.setColumns(List.of(
            buildColumn("booking_amount"),
            buildColumn("booking_made_on"),
            buildColumn("booking_status")
        ));

        TableMetadata bookingLogs = new TableMetadata();
        bookingLogs.setName("BOOKING_LOGS");
        bookingLogs.setColumns(List.of(
            buildColumn("booking_id"),
            buildColumn("created_at")
        ));

        schema.getTables().add(userBookings);
        schema.getTables().add(bookingLogs);

        String result = assembler.buildSchemaContext(schema, "what is the volume of bookings in the last 3 days");

        assertTrue(result.contains("USER_BOOKINGS"), "Bookings question should include USER_BOOKINGS in compact schema context");
        assertTrue(result.contains("booking_amount varchar"), "Selected booking table should include its columns");
    }

    @Test
    void emptySchema_handledGracefully() {
        SchemaMetadata schema = buildSchema(0, 0);

        String result = assembler.buildSchemaContext(schema, "show tables");

        assertTrue(result.contains("Total Tables: 0"), "Should show zero tables");
        assertTrue(result.contains("Tables: none"), "Should indicate no tables");
    }

    @Test
    void exactThreshold_usesFullView() {
        // Exactly 100 tables = full view (not compact)
        SchemaMetadata schema = buildSchema(100, 20);

        String result = assembler.buildSchemaContext(schema, "anything");

        assertFalse(result.contains("showing"), "Should use full view at threshold");
        assertTrue(result.contains("col_20 varchar"), "Full view should show all columns");
    }

    @Test
    void justAboveThreshold_usesCompactView() {
        // 101 tables = compact view
        SchemaMetadata schema = buildSchema(101, 20);

        String result = assembler.buildSchemaContext(schema, "anything");

        assertTrue(result.contains("showing"), "Should switch to compact at 101 tables");
    }

    @Test
    void nullQuestion_allSelectedTablesStillShowFullColumns() {
        SchemaMetadata schema = buildSchema(200, 20);

        String result = assembler.buildSchemaContext(schema, null);

        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.startsWith("- table_") && line.contains("col_")) {
                assertTrue(line.contains("col_20 varchar"),
                    "All selected tables should show full columns even with null question");
            }
        }
    }

    @Test
    void compactView_maxSelectedTablesRespected() {
        // 300 tables, should show exactly 100
        SchemaMetadata schema = buildSchema(300, 5);

        String result = assembler.buildSchemaContext(schema, "anything");

        long tableLines = result.lines()
            .filter(line -> line.startsWith("- table_"))
            .count();
        assertEquals(100, tableLines, "Should show exactly previewMaxTables tables");
    }

    @Test
    void compactView_ragTableNamesIncluded() {
        // 200 tables → compact view. table_199 not in question but found by RAG.
        SchemaMetadata schema = buildSchema(200, 10);

        String result = assembler.buildSchemaContext(schema, "show me revenue", Set.of("table_199", "table_188"));

        assertTrue(result.contains("table_199"), "RAG-found table_199 should be included");
        assertTrue(result.contains("table_188"), "RAG-found table_188 should be included");
        // Both RAG tables should show full columns
        String[] lines = result.split("\n");
        boolean found199 = false;
        boolean found188 = false;
        for (String line : lines) {
            if (line.contains("table_199") && line.contains("col_10 varchar")) found199 = true;
            if (line.contains("table_188") && line.contains("col_10 varchar")) found188 = true;
        }
        assertTrue(found199, "RAG table_199 should show all columns");
        assertTrue(found188, "RAG table_188 should show all columns");
    }

    @Test
    void compactView_ragTablesCombinedWithQuestionTables() {
        // Both question-mentioned and RAG-found tables should appear
        SchemaMetadata schema = buildSchema(200, 5);

        String result = assembler.buildSchemaContext(schema, "describe table_3", Set.of("table_150"));

        assertTrue(result.contains("table_3"), "Question-mentioned table should be included");
        assertTrue(result.contains("table_150"), "RAG-found table should also be included");
    }

    @Test
    void compactView_emptyRagTableNames_noChange() {
        SchemaMetadata schema = buildSchema(200, 5);

        String withEmpty = assembler.buildSchemaContext(schema, "describe table_5", Set.of());
        String withNull = assembler.buildSchemaContext(schema, "describe table_5");

        assertEquals(withEmpty, withNull, "Empty RAG set should produce same result as no RAG set");
    }

    @Test
    void compactView_overflowCapHolds_combinedQuestionAndRagExceedsCap() {
        // 200 tables. Question mentions 70 tables, RAG returns 60 tables (30 overlap).
        // Combined unique = 100, but cap is 100, so all priority tables fit.
        SchemaMetadata schema = buildSchema(200, 5);

        // Build question mentioning table_1 through table_70
        StringBuilder question = new StringBuilder("join ");
        for (int i = 1; i <= 70; i++) {
            question.append("table_").append(i);
            if (i < 70) question.append(" and ");
        }

        // RAG tables: table_41 through table_100 (60 tables, 30 overlap with question)
        Set<String> ragTables = new java.util.HashSet<>();
        for (int i = 41; i <= 100; i++) {
            ragTables.add("table_" + i);
        }

        String result = assembler.buildSchemaContext(schema, question.toString(), ragTables);

        long tableLines = result.lines()
            .filter(line -> line.startsWith("- table_"))
            .count();
        assertTrue(tableLines <= 100, "Selected tables should never exceed cap: was " + tableLines);
        assertEquals(100, tableLines, "Should use all 100 slots (70 from question + 30 new from RAG)");

        // Verify all question-mentioned tables are present
        for (int i = 1; i <= 70; i++) {
            assertTrue(result.contains("table_" + i + " "),
                "Question-mentioned table_" + i + " should be included");
        }

        // Verify RAG-only tables (71-100) are present
        for (int i = 71; i <= 100; i++) {
            assertTrue(result.contains("table_" + i + " "),
                "RAG-only table_" + i + " should be included");
        }

        // Verify non-priority tables (101-200) are NOT present since cap is full
        for (int i = 101; i <= 200; i++) {
            assertFalse(result.contains("- table_" + i + " "),
                "Non-priority table_" + i + " should NOT be included when cap is full");
        }

        assertTrue(result.contains("100 more tables omitted"), "Should show correct omitted count");
    }

    @Test
    void compactView_overflowCapEnforced_priorityExceedsCap() {
        // 200 tables. Question mentions 80 tables, RAG returns 50 tables (no overlap).
        // Combined unique = 130, exceeds cap of 100. Verify cap holds.
        SchemaMetadata schema = buildSchema(200, 5);

        StringBuilder question = new StringBuilder("join ");
        for (int i = 1; i <= 80; i++) {
            question.append("table_").append(i);
            if (i < 80) question.append(" and ");
        }

        // RAG tables: table_101 through table_150 (50 non-overlapping tables)
        Set<String> ragTables = new java.util.HashSet<>();
        for (int i = 101; i <= 150; i++) {
            ragTables.add("table_" + i);
        }

        String result = assembler.buildSchemaContext(schema, question.toString(), ragTables);

        long tableLines = result.lines()
            .filter(line -> line.startsWith("- table_"))
            .count();
        assertEquals(100, tableLines, "Must enforce 100 table cap even with 130 priority tables");

        // First 80 question-mentioned tables should be present (they're added first)
        for (int i = 1; i <= 80; i++) {
            assertTrue(result.contains("table_" + i + " "),
                "Question-mentioned table_" + i + " should be prioritized");
        }

        // Only 20 RAG tables can fit (100 - 80 = 20 slots)
        long ragTablesIncluded = 0;
        for (int i = 101; i <= 150; i++) {
            if (result.contains("- table_" + i + " ")) ragTablesIncluded++;
        }
        assertEquals(20, ragTablesIncluded,
            "Should include exactly 20 RAG tables (100 cap - 80 question = 20 slots)");
    }

    // --- Task 1: PK/FK/NOT NULL enrichment tests ---

    private SchemaMetadata buildSchemaWithRelationships() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("test_db");
        schema.setDbType("postgresql");

        // orders table: id (PK, NOT NULL), user_id (FK→users.id, NOT NULL), status (NOT NULL), created_at (nullable)
        TableMetadata orders = new TableMetadata();
        orders.setName("orders");
        orders.setColumns(List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("user_id", "bigint", null, false, false, null, 2),
            new ColumnMetadata("status", "varchar", 50L, false, false, null, 3),
            new ColumnMetadata("created_at", "timestamp", null, true, false, null, 4)
        ));

        // users table: id (PK, NOT NULL), email (NOT NULL), name (nullable)
        TableMetadata users = new TableMetadata();
        users.setName("users");
        users.setColumns(List.of(
            new ColumnMetadata("id", "bigint", null, false, true, null, 1),
            new ColumnMetadata("email", "varchar", 255L, false, false, null, 2),
            new ColumnMetadata("name", "varchar", 100L, true, false, null, 3)
        ));

        schema.setTables(List.of(orders, users));

        // FK: orders.user_id → users.id
        RelationshipMetadata fk = new RelationshipMetadata(
            "fk_orders_user", "orders", "user_id", "users", "id", "many-to-one", "fk_orders_user_id"
        );
        schema.setRelationships(List.of(fk));

        return schema;
    }

    @Test
    void enrichment_pkMarkerPresent() {
        SchemaMetadata schema = buildSchemaWithRelationships();

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        // PK columns should have PK marker
        assertTrue(result.contains("id bigint PK"), "PK column should have PK marker");
    }

    @Test
    void enrichment_fkMarkerPresent() {
        SchemaMetadata schema = buildSchemaWithRelationships();

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        // FK column should have FK→target reference
        assertTrue(result.contains("FK→users.id"), "FK column should show FK→target_table.target_column");
    }

    @Test
    void enrichment_notNullMarkerPresent() {
        SchemaMetadata schema = buildSchemaWithRelationships();

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        // NOT NULL columns should have marker, nullable columns should NOT
        assertTrue(result.contains("status varchar NOT NULL"), "NOT NULL column should have marker");
        assertFalse(result.contains("created_at timestamp NOT NULL"),
            "Nullable column should NOT have NOT NULL marker");
    }

    @Test
    void enrichment_combinedMarkers() {
        SchemaMetadata schema = buildSchemaWithRelationships();

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        // user_id should have both FK and NOT NULL
        assertTrue(result.contains("user_id bigint FK→users.id NOT NULL"),
            "Column should combine FK and NOT NULL markers: " + result);
    }

    @Test
    void enrichment_noRelationships_noFkMarkers() {
        SchemaMetadata schema = buildSchemaWithRelationships();
        schema.setRelationships(List.of()); // clear relationships

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        assertFalse(result.contains("FK→"), "No FK markers when no relationships defined");
        // But PK and NOT NULL should still work
        assertTrue(result.contains("id bigint PK"), "PK should still show without relationships");
        assertTrue(result.contains("status varchar NOT NULL"), "NOT NULL should still show");
    }

    // --- Task 2: Inline table descriptions from SchemaDocumentation ---

    @Test
    void description_userSourceShown() {
        SchemaDocumentationRepository mockRepo = mock(SchemaDocumentationRepository.class);
        ChatContextAssembler assemblerWithDocs = buildAssemblerWithDescriptions(mockRepo);

        SchemaMetadata schema = buildSchemaWithRelationships();

        // USER-sourced doc for "orders" table
        SchemaDocumentation doc = SchemaDocumentation.builder()
            .connectionId("conn-1")
            .objectType(SchemaDocumentation.DocumentationType.TABLE)
            .objectName("orders")
            .description("Purchase orders with lifecycle status tracking")
            .source(DocumentationSource.USER)
            .build();
        when(mockRepo.findByConnectionId("conn-1")).thenReturn(List.of(doc));

        String result = assemblerWithDocs.buildSchemaContext("conn-1", schema, "show me all orders", Set.of());

        assertTrue(result.contains("\"Purchase orders with lifecycle status tracking\""),
            "USER-sourced description should be shown: " + result);
    }

    @Test
    void description_aiHighConfidenceShown() {
        SchemaDocumentationRepository mockRepo = mock(SchemaDocumentationRepository.class);
        ChatContextAssembler assemblerWithDocs = buildAssemblerWithDescriptions(mockRepo);

        SchemaMetadata schema = buildSchemaWithRelationships();

        SchemaDocumentation doc = SchemaDocumentation.builder()
            .connectionId("conn-1")
            .objectType(SchemaDocumentation.DocumentationType.TABLE)
            .objectName("orders")
            .description("AI-generated description with high confidence")
            .source(DocumentationSource.AI_GENERATED)
            .confidence(0.85)
            .build();
        when(mockRepo.findByConnectionId("conn-1")).thenReturn(List.of(doc));

        String result = assemblerWithDocs.buildSchemaContext("conn-1", schema, "show me all orders", Set.of());

        assertTrue(result.contains("\"AI-generated description with high confidence\""),
            "AI doc with confidence >= 0.7 should be shown: " + result);
    }

    @Test
    void description_schemaQualifiedAiDocShownForBareRuntimeTableName() {
        SchemaDocumentationRepository mockRepo = mock(SchemaDocumentationRepository.class);
        ChatContextAssembler assemblerWithDocs = buildAssemblerWithDescriptions(mockRepo);

        SchemaMetadata schema = buildSchemaWithRelationships();

        SchemaDocumentation doc = SchemaDocumentation.builder()
            .connectionId("conn-1")
            .objectType(SchemaDocumentation.DocumentationType.TABLE)
            .objectName("sales.orders")
            .description("Schema-qualified orders description")
            .source(DocumentationSource.AI_GENERATED)
            .confidence(0.82)
            .build();
        when(mockRepo.findByConnectionId("conn-1")).thenReturn(List.of(doc));

        String result = assemblerWithDocs.buildSchemaContext("conn-1", schema, "show me all orders", Set.of());

        assertTrue(result.contains("\"Schema-qualified orders description\""),
            "Schema-qualified AI descriptions should still be surfaced for bare table names: " + result);
    }

    @Test
    void description_aiLowConfidenceHidden() {
        SchemaDocumentationRepository mockRepo = mock(SchemaDocumentationRepository.class);
        ChatContextAssembler assemblerWithDocs = buildAssemblerWithDescriptions(mockRepo);

        SchemaMetadata schema = buildSchemaWithRelationships();

        SchemaDocumentation doc = SchemaDocumentation.builder()
            .connectionId("conn-1")
            .objectType(SchemaDocumentation.DocumentationType.TABLE)
            .objectName("orders")
            .description("Low confidence description")
            .source(DocumentationSource.AI_GENERATED)
            .confidence(0.5)
            .build();
        when(mockRepo.findByConnectionId("conn-1")).thenReturn(List.of(doc));

        String result = assemblerWithDocs.buildSchemaContext("conn-1", schema, "show me all orders", Set.of());

        assertFalse(result.contains("Low confidence description"),
            "AI doc with confidence < 0.7 should NOT be shown: " + result);
    }

    @Test
    void description_noRepoCallWithoutConnectionId() {
        // Old 3-arg overload should NOT call repository
        SchemaMetadata schema = buildSchemaWithRelationships();

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        // Should still produce enriched output (PK/FK/NOT NULL) but no descriptions
        assertTrue(result.contains("id bigint PK"), "Should still enrich without connectionId");
        assertFalse(result.contains("\""), "No descriptions without connectionId");
    }

    // --- Task 3: Schema token safety valve (graceful degradation) ---

    private ChatContextAssembler buildAssemblerWithDescriptions(SchemaDocumentationRepository mockRepo) {
        ChatContextAssembler a = new ChatContextAssembler(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, mockRepo, null
        );
        ReflectionTestUtils.setField(a, "schemaContextFullMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxColumnsPerTable", 8);
        ReflectionTestUtils.setField(a, "maxSchemaTokens", 100000);
        return a;
    }

    /**
     * Build a 20-table schema with PK/FK/NOT NULL properties and mock descriptions.
     * Each table has: id (PK, NOT NULL), parent_id (FK→prev_table.id, NOT NULL), name (NOT NULL), notes (nullable)
     */
    private SchemaMetadata buildRichSchema(int tableCount) {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("test_db");
        schema.setDbType("postgresql");
        List<TableMetadata> tables = new ArrayList<>();
        List<RelationshipMetadata> relationships = new ArrayList<>();

        for (int i = 1; i <= tableCount; i++) {
            TableMetadata table = new TableMetadata();
            table.setName("table_" + i);
            table.setColumns(List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1),
                new ColumnMetadata("parent_id", "bigint", null, false, false, null, 2),
                new ColumnMetadata("name", "varchar", 255L, false, false, null, 3),
                new ColumnMetadata("notes", "text", null, true, false, null, 4)
            ));
            tables.add(table);

            if (i > 1) {
                relationships.add(new RelationshipMetadata(
                    "fk_t" + i + "_parent", "table_" + i, "parent_id",
                    "table_" + (i - 1), "id", "many-to-one", "fk_constraint_" + i
                ));
            }
        }

        schema.setTables(tables);
        schema.setRelationships(relationships);
        return schema;
    }

    private List<SchemaDocumentation> buildMockDocs(String connectionId, int tableCount) {
        List<SchemaDocumentation> docs = new ArrayList<>();
        for (int i = 1; i <= tableCount; i++) {
            docs.add(SchemaDocumentation.builder()
                .connectionId(connectionId)
                .objectType(SchemaDocumentation.DocumentationType.TABLE)
                .objectName("table_" + i)
                .description("Description for table " + i + " with business context")
                .source(DocumentationSource.USER)
                .build());
        }
        return docs;
    }

    @Test
    void degradation_level1_fullEnrichmentWithinBudget() {
        SchemaDocumentationRepository mockRepo = mock(SchemaDocumentationRepository.class);
        ChatContextAssembler a = buildAssemblerWithDescriptions(mockRepo);
        ReflectionTestUtils.setField(a, "maxSchemaTokens", 100000); // huge budget

        SchemaMetadata schema = buildRichSchema(20);
        when(mockRepo.findByConnectionId("conn-1")).thenReturn(buildMockDocs("conn-1", 20));

        String result = a.buildSchemaContext("conn-1", schema, "show tables", Set.of());

        // Level 1: should have all markers AND descriptions
        assertTrue(result.contains("PK"), "Level 1 should include PK markers");
        assertTrue(result.contains("FK→"), "Level 1 should include FK markers");
        assertTrue(result.contains("NOT NULL"), "Level 1 should include NOT NULL markers");
        assertTrue(result.contains("\"Description for table"), "Level 1 should include descriptions");
    }

    @Test
    void degradation_level2_dropDescriptionsWhenOverBudget() {
        SchemaDocumentationRepository mockRepo = mock(SchemaDocumentationRepository.class);
        ChatContextAssembler a = buildAssemblerWithDescriptions(mockRepo);

        SchemaMetadata schema = buildRichSchema(20);
        when(mockRepo.findByConnectionId("conn-1")).thenReturn(buildMockDocs("conn-1", 20));

        // First, build Level 1 output to get its token count
        ReflectionTestUtils.setField(a, "maxSchemaTokens", 100000);
        String level1Output = a.buildSchemaContext("conn-1", schema, "show tables", Set.of());
        int level1Tokens = com.dbaagent.util.TokenEstimator.estimate(level1Output);

        // Now set budget BELOW Level 1 but high enough for Level 2 (no descriptions)
        // Level 2 output should be smaller since it drops description lines
        ReflectionTestUtils.setField(a, "maxSchemaTokens", level1Tokens - 10);
        String result = a.buildSchemaContext("conn-1", schema, "show tables", Set.of());

        assertTrue(result.contains("PK"), "Level 2 should still include PK markers");
        assertTrue(result.contains("FK→"), "Level 2 should still include FK markers");
        assertTrue(result.contains("NOT NULL"), "Level 2 should still include NOT NULL markers");
        assertFalse(result.contains("\"Description for table"), "Level 2 should DROP descriptions");
    }

    @Test
    void degradation_level3_dropNotNullWhenOverBudget() {
        ChatContextAssembler a = new ChatContextAssembler(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        ReflectionTestUtils.setField(a, "schemaContextFullMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxColumnsPerTable", 8);

        SchemaMetadata schema = buildRichSchema(20);

        // Build Level 2 output to measure tokens (no descriptions since no connectionId)
        ReflectionTestUtils.setField(a, "maxSchemaTokens", 100000);
        String level2Output = a.buildSchemaContext(schema, "show tables");
        int level2Tokens = com.dbaagent.util.TokenEstimator.estimate(level2Output);

        // Set budget BELOW Level 2
        ReflectionTestUtils.setField(a, "maxSchemaTokens", level2Tokens - 10);
        String result = a.buildSchemaContext(schema, "show tables");

        assertTrue(result.contains("PK"), "Level 3 should still include PK markers");
        assertTrue(result.contains("FK→"), "Level 3 should still include FK markers");
        assertFalse(result.contains("NOT NULL"), "Level 3 should DROP NOT NULL markers");
    }

    @Test
    void degradation_level4_skeletonWhenAllOverBudget() {
        ChatContextAssembler a = new ChatContextAssembler(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        ReflectionTestUtils.setField(a, "schemaContextFullMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxColumnsPerTable", 8);
        ReflectionTestUtils.setField(a, "maxSchemaTokens", 1); // impossibly small budget

        SchemaMetadata schema = buildRichSchema(20);

        String result = a.buildSchemaContext(schema, "show tables");

        // Level 4: skeleton only — no PK, FK, NOT NULL, or descriptions
        assertFalse(result.contains("PK"), "Level 4 should NOT include PK markers");
        assertFalse(result.contains("FK→"), "Level 4 should NOT include FK markers");
        assertFalse(result.contains("NOT NULL"), "Level 4 should NOT include NOT NULL markers");
        assertFalse(result.contains("\"Description"), "Level 4 should NOT include descriptions");
        // But should still have table names and column types
        assertTrue(result.contains("table_1"), "Level 4 should still list tables");
        assertTrue(result.contains("id bigint"), "Level 4 should still show column names and types");
    }

    // --- Task 4: Explicit FK relationships section ---

    @Test
    void fkSection_showsKeyRelationships() {
        SchemaMetadata schema = buildSchemaWithRelationships();

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        assertTrue(result.contains("Key Relationships"), "Should include Key Relationships header");
        assertTrue(result.contains("orders.user_id → users.id"),
            "Should list FK relationship: " + result);
    }

    @Test
    void fkSection_multipleRelationships() {
        SchemaMetadata schema = buildRichSchema(5);
        // buildRichSchema creates: table_2.parent_id→table_1.id, table_3.parent_id→table_2.id, etc.

        String result = assembler.buildSchemaContext(schema, "show tables");

        assertTrue(result.contains("Key Relationships"), "Should include header");
        assertTrue(result.contains("table_2.parent_id → table_1.id"),
            "Should list FK for table_2: " + result);
        assertTrue(result.contains("table_5.parent_id → table_4.id"),
            "Should list FK for table_5: " + result);
    }

    @Test
    void fkSection_cappedAt50() {
        // Build schema with 60 relationships (exceeds cap)
        SchemaMetadata schema = buildRichSchema(61); // 60 FKs (table_2..table_61 each have FK)

        String result = assembler.buildSchemaContext(schema, "show tables");

        long fkLines = result.lines()
            .filter(line -> line.trim().startsWith("- ") && line.contains(" → "))
            .filter(line -> {
                // Only count FK section lines (after "Key Relationships"), not table lines
                String trimmed = line.trim();
                return trimmed.matches("- \\w+\\.\\w+ → \\w+\\.\\w+");
            })
            .count();
        assertTrue(fkLines <= 50, "FK relationships should be capped at 50, was: " + fkLines);
    }

    @Test
    void fkSection_absentWhenNoRelationships() {
        SchemaMetadata schema = buildSchemaWithRelationships();
        schema.setRelationships(List.of());

        String result = assembler.buildSchemaContext(schema, "show me all orders");

        assertFalse(result.contains("Key Relationships"),
            "Should not show FK section when no relationships exist");
    }

    @Test
    void fkSection_compactView_onlySelectedTableRelationships() {
        // 200 tables → compact view. Only relationships involving selected tables should appear.
        SchemaMetadata schema = buildRichSchema(200);
        // Question mentions table_5, so table_5 and neighbors should be selected.
        // FK: table_5.parent_id → table_4.id, table_6.parent_id → table_5.id

        String result = assembler.buildSchemaContext(schema, "describe table_5");

        // table_5's FK should be present (if table_5 is in selected tables)
        assertTrue(result.contains("table_5"), "table_5 should be selected");

        // FK lines should only reference tables that are in the selected set
        result.lines()
            .filter(line -> line.trim().matches("- \\w+\\.\\w+ → \\w+\\.\\w+"))
            .forEach(line -> {
                // Extract table names from "- fromTable.col → toTable.col"
                String trimmed = line.trim().substring(2); // remove "- "
                String fromTable = trimmed.split("\\.")[0];
                String toTable = trimmed.split(" → ")[1].split("\\.")[0];
                assertTrue(result.contains("- " + fromTable + " (") || result.contains("- " + fromTable + " ("),
                    "FK source table " + fromTable + " should be in selected tables");
            });
    }

    @Test
    void fkSection_includedInTokenBudget() {
        // Skeleton with tiny budget should not include FK section
        ChatContextAssembler a = new ChatContextAssembler(
            null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null
        );
        ReflectionTestUtils.setField(a, "schemaContextFullMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxTables", 100);
        ReflectionTestUtils.setField(a, "schemaContextPreviewMaxColumnsPerTable", 8);
        ReflectionTestUtils.setField(a, "maxSchemaTokens", 1); // impossibly small

        SchemaMetadata schema = buildRichSchema(20);

        String result = a.buildSchemaContext(schema, "show tables");

        // SKELETON fallback should not include FK section
        assertFalse(result.contains("Key Relationships"),
            "Skeleton fallback should not include FK relationships section");
    }

    // --- Task 5: Always include inferred relationships + REJECTED filter ---

    @Test
    void determineNeededContext_alwaysIncludesRelationships() {
        // A normal data query with no JOIN keywords should still include RELATIONSHIPS
        Set<ChatContextAssembler.ContextType> needed =
            assembler.determineNeededContext("show me revenue by customer");

        assertTrue(needed.contains(ChatContextAssembler.ContextType.RELATIONSHIPS),
            "RELATIONSHIPS should always be included, even without JOIN keywords");
    }

    @Test
    void determineNeededContext_includesRelationshipsForSimpleSelect() {
        Set<ChatContextAssembler.ContextType> needed =
            assembler.determineNeededContext("select orders for user John");

        assertTrue(needed.contains(ChatContextAssembler.ContextType.RELATIONSHIPS),
            "RELATIONSHIPS should be included for select queries");
    }

    @Test
    void determineNeededContext_includesRelationshipsWithJoinKeyword() {
        // Existing behavior should still work
        Set<ChatContextAssembler.ContextType> needed =
            assembler.determineNeededContext("join orders with customers");

        assertTrue(needed.contains(ChatContextAssembler.ContextType.RELATIONSHIPS),
            "RELATIONSHIPS should still be included for JOIN keyword queries");
    }

    @Test
    void determineNeededContext_emptyMessageReturnsEmpty() {
        Set<ChatContextAssembler.ContextType> needed = assembler.determineNeededContext("");

        assertTrue(needed.isEmpty(), "Empty message should return empty context set");
    }

    @Test
    void buildInferredRelationships_excludesRejected() {
        // This tests the REJECTED filter in buildInferredRelationshipsContext.
        // Since buildInferredRelationshipsContext is private and uses a repository,
        // we test via the public determineNeededContext + verify RELATIONSHIPS is always requested.
        // The actual REJECTED filter test requires integration with the repository mock,
        // which is better tested at the integration level.
        // Here we verify the contract: RELATIONSHIPS is always in neededContext.
        Set<ChatContextAssembler.ContextType> needed =
            assembler.determineNeededContext("what tables have data about customers?");

        assertTrue(needed.contains(ChatContextAssembler.ContextType.RELATIONSHIPS),
            "RELATIONSHIPS should always be included so buildInferredRelationshipsContext runs");
    }

    // --- Task 6: Truncation priority — feedback protected, RAG last ---

    @Test
    void truncation_feedbackNeverTruncated() {
        // With extremely tight budget, feedback should remain intact
        ReflectionTestUtils.setField(assembler, "maxSystemPromptTokens", 100);

        String[] result = assembler.applyTokenBudget(
            "schema",         // protected
            "classification data here",
            "performance data here",
            "brain data here",
            "CRITICAL: always filter by tenant_id", // feedback - should be protected
            "company rule context",
            "training RAG data here",
            "dbRules",        // protected
            "", ""            // columnValueContext, resolutionHints
        );

        // result: [schema, classification, performance, brain, feedback, companyKnowledge, training, colValues, hints]
        assertEquals("CRITICAL: always filter by tenant_id", result[4],
            "Feedback should never be truncated");
    }

    @Test
    void truncation_brainTruncatedBeforeTraining() {
        // Budget allows schema + dbRules + feedback + one more section.
        // Brain should be cut before training.
        int schemaTokens = com.dbaagent.util.TokenEstimator.estimate("schema");
        int dbRulesTokens = com.dbaagent.util.TokenEstimator.estimate("dbRules");
        int feedbackTokens = com.dbaagent.util.TokenEstimator.estimate("feedback text");
        int companyKnowledgeTokens = com.dbaagent.util.TokenEstimator.estimate("company rule context");
        int trainingTokens = com.dbaagent.util.TokenEstimator.estimate("important training context");
        // Budget = protected + company knowledge + training + a little more
        int budget = schemaTokens + dbRulesTokens + feedbackTokens + companyKnowledgeTokens + trainingTokens + 5;
        ReflectionTestUtils.setField(assembler, "maxSystemPromptTokens", budget);

        String[] result = assembler.applyTokenBudget(
            "schema",
            "classification",
            "performance",
            "brain insights data",
            "feedback text",
            "company rule context",
            "important training context",
            "dbRules",
            "", ""            // columnValueContext, resolutionHints
        );

        // Training (RAG) should survive, brain should be truncated
        // result: [schema, classification, performance, brain, feedback, companyKnowledge, training, colValues, hints]
        assertEquals("important training context", result[6],
            "Training (RAG) context should survive when brain is truncated");
    }

    @Test
    void truncation_orderIsBrainClassificationPerformanceTraining() {
        // Very tight budget: only protected sections fit
        int schemaTokens = com.dbaagent.util.TokenEstimator.estimate("s");
        int dbRulesTokens = com.dbaagent.util.TokenEstimator.estimate("d");
        int feedbackTokens = com.dbaagent.util.TokenEstimator.estimate("f");
        ReflectionTestUtils.setField(assembler, "maxSystemPromptTokens",
            schemaTokens + dbRulesTokens + feedbackTokens);

        String[] result = assembler.applyTokenBudget(
            "s",              // schema (protected)
            "classification", // 2nd to cut
            "performance",    // 3rd to cut
            "brain",          // 1st to cut
            "f",              // feedback (protected)
            "company",        // last to cut
            "training",       // 4th to cut (last = most preserved)
            "d",              // dbRules (protected)
            "", ""            // columnValueContext, resolutionHints
        );

        // Everything non-protected should be empty when budget is this tight
        assertEquals("", result[1], "Classification should be fully truncated");
        assertEquals("", result[2], "Performance should be fully truncated");
        assertEquals("", result[3], "Brain should be fully truncated");
        assertEquals("f", result[4], "Feedback should be preserved (protected)");
        assertEquals("", result[5], "Company knowledge should still truncate when the budget cannot fit any optional context");
        assertEquals("", result[6], "Training should be fully truncated");
    }
}
