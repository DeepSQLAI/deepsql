package com.dbaagent.service.codescan;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CodeSuggestionAggregatorTest {

    private final CodeSuggestionAggregator aggregator = new CodeSuggestionAggregator();

    private SchemaMetadata schemaWithBookings() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("postgres");
        TableMetadata bookings = new TableMetadata();
        bookings.setName("bookings");
        bookings.setColumns(List.of(
            col("id", "bigint"),
            col("status", "varchar"),
            col("guest_id", "bigint")
        ));
        schema.setTables(new ArrayList<>(List.of(bookings)));
        return schema;
    }

    @Test
    void dropsSuggestionsTargetingUnknownTables() {
        var raw = new CodeKnowledgeExtractor.RawSuggestion();
        raw.kind = "SCHEMA_DOC";
        raw.targetTable = "ghost_table";
        raw.title = "X";
        raw.content = "should be dropped";
        raw.confidence = 0.9;

        var out = aggregator.aggregate("c1", "j1", List.of(raw), schemaWithBookings());
        assertTrue(out.isEmpty());
    }

    @Test
    void mergesDuplicatesAndKeepsHighestConfidenceAsPrimary() {
        var a = new CodeKnowledgeExtractor.RawSuggestion();
        a.kind = "SCHEMA_DOC";
        a.targetTable = "bookings";
        a.targetColumn = "status";
        a.title = "Booking lifecycle status";
        a.content = "Lifecycle of a booking";
        a.confidence = 0.6;
        a.sourcePath = "svc/A.java";

        var b = new CodeKnowledgeExtractor.RawSuggestion();
        b.kind = "SCHEMA_DOC";
        b.targetTable = "BOOKINGS"; // case-insensitive match
        b.targetColumn = "STATUS";
        b.title = "Status of booking";
        b.content = "Reservation state machine";
        b.confidence = 0.9;
        b.sourcePath = "svc/B.java";

        var out = aggregator.aggregate("c1", "j1", List.of(a, b), schemaWithBookings());
        assertEquals(1, out.size());
        CodeKnowledgeSuggestion merged = out.get(0);
        assertEquals(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC, merged.getTargetKind());
        assertEquals("bookings.status", merged.getTargetObject());
        assertEquals("Status of booking", merged.getTitle());
        assertEquals("Reservation state machine", merged.getContent());
        assertEquals(0.9, merged.getConfidence(), 1e-6);
        @SuppressWarnings("unchecked")
        var alts = (List<?>) merged.getPayload().get("alternatives");
        assertNotNull(alts);
        assertEquals(1, alts.size());
        assertEquals(2, merged.getSourceFiles().size(), "both source paths captured");
    }

    @Test
    void dropsColumnSuggestionWhenColumnUnknown() {
        var raw = new CodeKnowledgeExtractor.RawSuggestion();
        raw.kind = "SCHEMA_DOC";
        raw.targetTable = "bookings";
        raw.targetColumn = "totally_made_up";
        raw.title = "X";
        raw.content = "...";

        assertTrue(aggregator.aggregate("c1", "j1", List.of(raw), schemaWithBookings()).isEmpty());
    }

    private static ColumnMetadata col(String name, String dataType) {
        ColumnMetadata c = new ColumnMetadata();
        c.setName(name);
        c.setDataType(dataType);
        return c;
    }
}
