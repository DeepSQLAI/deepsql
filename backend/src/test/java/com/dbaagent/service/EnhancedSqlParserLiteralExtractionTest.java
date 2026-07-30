package com.dbaagent.service;

import com.dbaagent.service.EnhancedSqlParserService.ColumnLiteralPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class EnhancedSqlParserLiteralExtractionTest {

    private EnhancedSqlParserService service;

    @BeforeEach
    void setUp() {
        service = new EnhancedSqlParserService();
    }

    // ==================== SELECT ====================

    @Test
    void extractsSelectWhereEquals() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM bookings WHERE hotel_id = 42");

        assertEquals(1, result.size());
        assertEquals("hotel_id", result.get(0).getColumnName());
        assertEquals("42", result.get(0).getValue());
        assertEquals("=", result.get(0).getOperation());
    }

    @Test
    void extractsSelectWhereStringLiteral() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM users WHERE status = 'active'");

        assertEquals(1, result.size());
        assertEquals("status", result.get(0).getColumnName());
        assertEquals("active", result.get(0).getValue());
    }

    @Test
    void extractsSelectWhereMultipleConditions() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM bookings WHERE hotel_id = 42 AND status = 'confirmed'");

        assertEquals(2, result.size());
    }

    @Test
    void extractsSelectWhereIn() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM bookings WHERE hotel_id IN (1, 2, 3)");

        assertEquals(3, result.size());
        assertTrue(result.stream().allMatch(p -> "IN".equals(p.getOperation())));
    }

    @Test
    void extractsSelectWithAlias() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM bookings b WHERE b.hotel_id = 42");

        assertEquals(1, result.size());
        assertEquals("bookings", result.get(0).getTableName());
        assertEquals("hotel_id", result.get(0).getColumnName());
    }

    // ==================== UPDATE ====================

    @Test
    void extractsUpdateWhereEquals() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "UPDATE bookings SET status = 'cancelled' WHERE hotel_id = 42");

        assertEquals(1, result.size());
        assertEquals("hotel_id", result.get(0).getColumnName());
        assertEquals("42", result.get(0).getValue());
        assertEquals("=", result.get(0).getOperation());
    }

    @Test
    void extractsUpdateWhereMultipleConditions() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "UPDATE users SET active = 0 WHERE email = 'test@example.com' AND org_id = 5");

        assertEquals(2, result.size());
    }

    @Test
    void extractsUpdateWithAlias() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "UPDATE bookings b SET b.status = 'done' WHERE b.hotel_id = 42");

        assertFalse(result.isEmpty());
        assertEquals("hotel_id", result.get(0).getColumnName());
        assertEquals("42", result.get(0).getValue());
    }

    // ==================== DELETE ====================

    @Test
    void extractsDeleteWhereEquals() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "DELETE FROM bookings WHERE hotel_id = 42");

        assertEquals(1, result.size());
        assertEquals("hotel_id", result.get(0).getColumnName());
        assertEquals("42", result.get(0).getValue());
    }

    @Test
    void extractsDeleteWhereStringLiteral() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "DELETE FROM sessions WHERE token = 'abc123'");

        assertEquals(1, result.size());
        assertEquals("token", result.get(0).getColumnName());
        assertEquals("abc123", result.get(0).getValue());
    }

    // ==================== Parameterized (should skip) ====================

    @Test
    void skipsParameterizedWithQuestionMark() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM bookings WHERE hotel_id = ?");

        assertTrue(result.isEmpty());
    }

    @Test
    void skipsParameterizedWithDollarSign() {
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELECT * FROM bookings WHERE hotel_id = $1");

        assertTrue(result.isEmpty());
    }

    // ==================== Edge cases ====================

    @Test
    void returnsEmptyForNull() {
        assertTrue(service.extractWhereClauseLiterals(null).isEmpty());
    }

    @Test
    void returnsEmptyForEmptyString() {
        assertTrue(service.extractWhereClauseLiterals("").isEmpty());
    }

    @Test
    void handlesMalformedSqlViaRegexFallback() {
        // Malformed SQL should fall back to regex
        List<ColumnLiteralPair> result = service.extractWhereClauseLiterals(
            "SELEC * FROM bookings WHERE hotel_id = 42");

        // Regex fallback should still find the literal
        assertEquals(1, result.size());
        assertEquals("hotel_id", result.get(0).getColumnName());
        assertEquals("42", result.get(0).getValue());
    }
}
