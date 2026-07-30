package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class McpSqlGuardServiceTest {

    private final McpSqlGuardService service = new McpSqlGuardService();

    @Test
    void acceptsSingleReadOnlySelect() {
        var result = service.validateReadOnlySql("SELECT * FROM orders LIMIT 10;", true);

        assertTrue(result.ok());
        assertEquals("SELECT", result.firstKeyword());
        assertEquals("SELECT * FROM orders LIMIT 10", result.normalizedQuery());
    }

    @Test
    void rejectsMultipleStatements() {
        var result = service.validateReadOnlySql("SELECT * FROM orders; DELETE FROM orders", true);

        assertFalse(result.ok());
        assertEquals("Phase 1 MCP only allows a single SQL statement.", result.reason());
    }

    @Test
    void rejectsExplainAnalyze() {
        var result = service.validateReadOnlySql("EXPLAIN ANALYZE SELECT * FROM orders", true);

        assertFalse(result.ok());
        assertEquals(
            "EXPLAIN ANALYZE is blocked in phase 1 MCP because it executes the query.",
            result.reason()
        );
    }

    @Test
    void rejectsExplainWhenUnderlyingQueryRequired() {
        var result = service.validateReadOnlySql("EXPLAIN SELECT * FROM orders", false);

        assertFalse(result.ok());
        assertEquals("Pass the underlying SELECT/WITH query, not EXPLAIN itself.", result.reason());
    }

    @Test
    void ignoresMutatingKeywordsInsideStringsAndComments() {
        var result = service.validateReadOnlySql("""
            -- DELETE FROM users
            SELECT 'DROP TABLE users' AS example
            """, true);

        assertTrue(result.ok());
        assertEquals("SELECT", result.firstKeyword());
    }

    @Test
    void rejectsMutatingKeywordInReadOnlyCte() {
        var result = service.validateReadOnlySql("""
            WITH recent AS (SELECT * FROM orders)
            SELECT * FROM recent UPDATE
            """, true);

        assertFalse(result.ok());
        assertEquals("Blocked potentially mutating SQL keyword: UPDATE.", result.reason());
    }
}
