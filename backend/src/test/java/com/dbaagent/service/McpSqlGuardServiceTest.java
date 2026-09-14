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

    // ── dangerous functions ───────────────────────────────────────────────────
    //
    // The guard classifies by statement *verb*, so a SELECT that calls a dangerous
    // function passes every check: the allowlist sees SELECT, and no forbidden verb
    // appears anywhere. Verified against a real PostgreSQL 17 — inside an explicitly
    // READ ONLY transaction, `SELECT dblink_exec(..., 'DELETE FROM t')` reported
    // `DELETE 3` and the table went from 3 rows to 0.
    //
    // connection.setReadOnly(true) cannot stop it either: dblink opens a *new outbound
    // connection* whose transaction is not read-only. The read-only flag constrains the
    // session it is set on, never one the query dials out and creates. So both of the
    // product's layers fail at once, and until the public dashboard path was bound to
    // published query shapes this was reachable anonymously.

    @Test
    void rejectsDblinkExec() {
        var result = service.validateReadOnlySql(
            "SELECT dblink_exec('dbname=app user=postgres host=127.0.0.1','DELETE FROM orders')", true);

        assertFalse(result.ok());
        assertTrue(result.reason().toLowerCase().contains("dblink"),
            "reason should name the function it refused, was: " + result.reason());
    }

    @Test
    void rejectsEveryDblinkEntryPoint() {
        for (String sql : new String[] {
            "SELECT * FROM dblink('dbname=app','SELECT 1') AS t(a int)",
            "SELECT dblink_connect('dbname=app')",
            "SELECT dblink_send_query('conn','DELETE FROM orders')",
            "SELECT dblink_open('conn','cur','SELECT 1')"
        }) {
            assertFalse(service.validateReadOnlySql(sql, true).ok(), "should refuse: " + sql);
        }
    }

    @Test
    void rejectsServerSideFileReads() {
        for (String sql : new String[] {
            "SELECT pg_read_file('/etc/passwd')",
            "SELECT pg_read_binary_file('/etc/passwd')",
            "SELECT pg_ls_dir('/var/lib/postgresql/data')",
            "SELECT pg_stat_file('/etc/passwd')",
            "SELECT lo_import('/etc/passwd')",
            "SELECT lo_export(1,'/tmp/out')"
        }) {
            assertFalse(service.validateReadOnlySql(sql, true).ok(), "should refuse: " + sql);
        }
    }

    @Test
    void rejectsMySqlFileReads() {
        assertFalse(service.validateReadOnlySql("SELECT LOAD_FILE('/etc/passwd')", true).ok());
    }

    @Test
    void rejectsDangerousFunctionRegardlessOfSpacingOrCase() {
        for (String sql : new String[] {
            "SELECT DBLINK_EXEC('x','DELETE FROM t')",
            "SELECT dblink_exec ('x','DELETE FROM t')",
            "SELECT pg_read_file\n('/etc/passwd')",
            "WITH x AS (SELECT pg_read_file('/etc/passwd') AS f) SELECT * FROM x"
        }) {
            assertFalse(service.validateReadOnlySql(sql, true).ok(), "should refuse: " + sql);
        }
    }

    /**
     * The guard must match a function *call*, not a name that merely appears. Column and
     * table names are ordinary identifiers and plenty of schemas contain them — the same
     * mistake CLAUDE.md records for the old \\bCOMMENT\\b rule, which rejected
     * `SELECT * FROM comment`.
     */
    @Test
    void stillAllowsIdentifiersThatMerelyResembleADangerousFunction() {
        for (String sql : new String[] {
            "SELECT * FROM public.dblink_audit",
            "SELECT t.pg_read_file_count FROM public.stats t",
            "SELECT load_file_name FROM public.imports",
            "SELECT * FROM comment"
        }) {
            assertTrue(service.validateReadOnlySql(sql, true).ok(), "should allow: " + sql);
        }
    }

    @Test
    void stillAllowsOrdinaryAnalyticQueries() {
        assertTrue(service.validateReadOnlySql(
            "SELECT count(*), sum(o.total) FROM public.orders o WHERE o.created_at >= '2026-01-01'", true).ok());
    }
}
