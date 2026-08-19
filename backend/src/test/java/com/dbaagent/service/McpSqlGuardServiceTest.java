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
    void rejectsMutatingCteBody() {
        var result = service.validateReadOnlySql("""
            WITH doomed AS (
              DELETE FROM users RETURNING id
            )
            SELECT * FROM doomed
            """, true);

        assertFalse(result.ok());
        assertEquals("Blocked potentially mutating SQL keyword: DELETE.", result.reason());
    }

    @Test
    void rejectsWithClauseFollowedByDelete() {
        var result = service.validateReadOnlySql("""
            WITH doomed AS (
              SELECT id FROM users
            )
            DELETE FROM users WHERE id IN (SELECT id FROM doomed)
            """, true);

        assertFalse(result.ok());
        assertEquals("Blocked potentially mutating SQL keyword: DELETE.", result.reason());
    }

    @Test
    void acceptsCommentAndCallAsTableNames() {
        assertTrue(service.validateReadOnlySql("SELECT * FROM comment", true).ok());
        assertTrue(service.validateReadOnlySql("SELECT * FROM call", true).ok());
        assertTrue(service.validateReadOnlySql(
            "SELECT comment.id FROM public.comment JOIN call ON call.id = comment.call_id",
            true
        ).ok());
    }

    @Test
    void acceptsCommentAsColumnAndFunctionArgument() {
        assertTrue(service.validateReadOnlySql("SELECT comment FROM posts", true).ok());
        assertTrue(service.validateReadOnlySql("SELECT COALESCE(comment, '') FROM posts", true).ok());
        assertTrue(service.validateReadOnlySql("SELECT REPLACE(name, 'a', 'b') FROM users", true).ok());
    }

    @Test
    void stillRejectsTopLevelMutations() {
        assertFalse(service.validateReadOnlySql("DELETE FROM comment", true).ok());
        assertFalse(service.validateReadOnlySql("CALL do_thing()", true).ok());
        assertFalse(service.validateReadOnlySql("COMMENT ON TABLE posts IS 'x'", true).ok());
    }

    @Test
    void rejectsSelectForUpdate() {
        var result = service.validateReadOnlySql("SELECT * FROM orders FOR UPDATE", true);

        assertFalse(result.ok());
        assertEquals("Blocked potentially mutating SQL keyword: UPDATE.", result.reason());
    }

    @Test
    void rejectsExplainOfDeleteButAllowsExplainOfCommentTable() {
        var deletePlan = service.validateReadOnlySql("EXPLAIN DELETE FROM users", true);
        assertFalse(deletePlan.ok());
        assertEquals("Blocked potentially mutating SQL keyword: DELETE.", deletePlan.reason());

        var commentPlan = service.validateReadOnlySql("EXPLAIN SELECT * FROM comment", true);
        assertTrue(commentPlan.ok());
    }
}
