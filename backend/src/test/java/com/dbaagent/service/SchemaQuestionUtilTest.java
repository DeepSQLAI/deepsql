package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SchemaQuestionUtilTest {

    @Test
    void resolveExactSchemaTable_prefersExactTableMentionOverGenericSuffixMatches() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata("ROOM_RESERVATIONS", null, "table", 5000L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of()),
            new TableMetadata("MASTER_LOGIN_ACCESS_KEYS", null, "table", 20L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of()),
            new TableMetadata("order_table", null, "table", 50L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of()),
            new TableMetadata("nr_reservation", null, "table", 50L, 0L, List.of(
                new ColumnMetadata("id", "bigint", null, false, true, null, 1)
            ), List.of())
        ));

        TableMetadata resolved = SchemaQuestionUtil.resolveExactSchemaTable(
            schema,
            "Show me all key columns in ROOM_RESERVATIONS table"
        );

        assertEquals("ROOM_RESERVATIONS", resolved.getName());
    }

    @Test
    void looksLikeExactTableKeyColumnQuestion_detectsScopedKeyColumnPrompts() {
        assertTrue(SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(
            "Show me all key columns in ROOM_RESERVATIONS table"
        ));
    }

    @Test
    void looksLikeExactTableRowCountQuestion_detectsScopedRowCountPrompts() {
        assertTrue(SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(
            "How many rows in ACCOUNTS table?"
        ));
    }

    @Test
    void looksLikeExactTableIndexQuestion_detectsScopedIndexPrompts() {
        assertTrue(SchemaQuestionUtil.looksLikeExactTableIndexQuestion(
            "What indexes are there on ACCOUNTS table?"
        ));
    }

    @Test
    void resolveExactSchemaTables_keepsRequestedPairWithoutShadowingPluralTable() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            new TableMetadata("ORDERS", null, "table", 1000L, 0L, List.of(), List.of()),
            new TableMetadata("ORDER_DETAIL", null, "table", 3000L, 0L, List.of(), List.of()),
            new TableMetadata("ORDER_TABLE", null, "table", 100L, 0L, List.of(), List.of())
        ));

        List<String> resolved = SchemaQuestionUtil.resolveExactSchemaTables(
            schema,
            "How are ORDERS and ORDER_DETAIL related?"
        );

        assertEquals(List.of("ORDER_DETAIL", "ORDERS"), resolved);
    }

    @Test
    void looksLikeExactTableColumnAndKeyQuestions_excludePairJoinColumnPrompts() {
        String prompt = "what columns are joined commonly between USER BOOKINGS and PRICE BREAKDOWN tables";

        assertTrue(SchemaQuestionUtil.looksLikePairScopedJoinColumnQuestion(prompt));
        assertFalse(SchemaQuestionUtil.looksLikeExactTableColumnQuestion(prompt));
        assertFalse(SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(prompt));
    }

    @Test
    void looksLikeExactTableColumnQuestion_excludesAnalyticImpactPrompts() {
        String prompt = "what are the most impactful columns in user bookings table?";

        assertFalse(SchemaQuestionUtil.looksLikeExactTableColumnQuestion(prompt));
        assertFalse(SchemaQuestionUtil.looksLikeExactTableColumnListQuestion(prompt));
    }
}
