package com.dbaagent.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlStatementSplitterTest {

    // ── split() ──────────────────────────────────────────────────────────

    @Test
    void split_singleStatement() {
        List<String> result = SqlStatementSplitter.split("SELECT 1");
        assertThat(result).containsExactly("SELECT 1");
    }

    @Test
    void split_multipleStatements() {
        List<String> result = SqlStatementSplitter.split("SET @x = 1; SELECT @x; SELECT 2;");
        assertThat(result).containsExactly("SET @x = 1", "SELECT @x", "SELECT 2");
    }

    @Test
    void split_semicolonInsideSingleQuotedString() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT * FROM t WHERE comment = 'error; retry' AND id = 1");
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("'error; retry'");
    }

    @Test
    void split_semicolonInsideDoubleQuotedIdentifier() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT \"col;name\" FROM t; SELECT 2");
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("\"col;name\"");
    }

    @Test
    void split_semicolonInsideBacktickIdentifier() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT `col;name` FROM t; SELECT 2");
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("`col;name`");
    }

    @Test
    void split_escapedSingleQuote_backslash() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT * FROM t WHERE name = 'it\\'s here; really'; SELECT 2");
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("'it\\'s here; really'");
    }

    @Test
    void split_escapedSingleQuote_doubleQuote() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT * FROM t WHERE name = 'it''s here; really'; SELECT 2");
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("'it''s here; really'");
    }

    @Test
    void split_singleLineComment() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT 1 -- this has a ; comment\n; SELECT 2");
        assertThat(result).hasSize(2);
    }

    @Test
    void split_blockComment() {
        List<String> result = SqlStatementSplitter.split(
            "SELECT /* ; embedded */ 1; SELECT 2");
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("/* ; embedded */");
    }

    @Test
    void split_emptyAndNull() {
        assertThat(SqlStatementSplitter.split(null)).isEmpty();
        assertThat(SqlStatementSplitter.split("")).isEmpty();
        assertThat(SqlStatementSplitter.split("  ")).isEmpty();
    }

    // ── Dialect-aware # comment handling ─────────────────────────────────

    @Test
    void split_hashCommentInMysqlMode() {
        // In MySQL mode (default), # starts a line comment
        List<String> result = SqlStatementSplitter.split(
            "SELECT 1 # this is a comment\n; SELECT 2", true);
        assertThat(result).hasSize(2);
    }

    @Test
    void split_hashNotCommentInPostgresMode() {
        // In PostgreSQL mode, # is a normal character (used in #>, #>> JSONB operators)
        List<String> result = SqlStatementSplitter.split(
            "SELECT data#>'{key}' FROM t; SELECT 2", false);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("#>'{key}'");
    }

    @Test
    void split_postgresJsonbPathOperator() {
        // #>> extracts JSONB text — must not be treated as a comment
        List<String> result = SqlStatementSplitter.split(
            "SELECT metadata#>>'{address,city}' FROM users WHERE id = 1", false);
        assertThat(result).hasSize(1);
        assertThat(result.get(0)).contains("#>>'{address,city}'");
    }

    // ── splitSetPreamble() ──────────────────────────────────────────────

    @Test
    void splitSetPreamble_noSet() {
        String[] result = SqlStatementSplitter.splitSetPreamble("SELECT * FROM users");
        assertThat(result).containsExactly("SELECT * FROM users");
    }

    @Test
    void splitSetPreamble_setAtVariable() {
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "SET @x = 1; SET @y = 2; SELECT @x + @y");
        assertThat(result).hasSize(3);
        assertThat(result[0]).isEqualTo("SET @x = 1");
        assertThat(result[1]).isEqualTo("SET @y = 2");
        assertThat(result[2]).isEqualTo("SELECT @x + @y");
    }

    @Test
    void splitSetPreamble_setSession() {
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "SET SESSION max_execution_time = 30000; SELECT * FROM t");
        assertThat(result).hasSize(2);
        assertThat(result[0]).startsWith("SET SESSION");
        assertThat(result[1]).startsWith("SELECT");
    }

    @Test
    void splitSetPreamble_setSessionVariable() {
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "SET @@session.time_zone = '+00:00'; SELECT NOW()");
        assertThat(result).hasSize(2);
        assertThat(result[0]).contains("@@session.time_zone");
        assertThat(result[1]).isEqualTo("SELECT NOW()");
    }

    @Test
    void splitSetPreamble_setNames() {
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "SET NAMES utf8mb4; SELECT * FROM t");
        assertThat(result).hasSize(2);
        assertThat(result[0]).isEqualTo("SET NAMES utf8mb4");
    }

    @Test
    void splitSetPreamble_semicolonInStringLiteral() {
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "SET @x = 'a;b'; SELECT @x");
        assertThat(result).hasSize(2);
        assertThat(result[0]).isEqualTo("SET @x = 'a;b'");
        assertThat(result[1]).isEqualTo("SELECT @x");
    }

    @Test
    void splitSetPreamble_mixedSetAndSelect_withSemicolonInBody() {
        String sql = "SET @report_date := DATE('2026-02-17');\n" +
            "SET @utc_start := UNIX_TIMESTAMP(CONVERT_TZ(@report_date, '+05:30', '+00:00'));\n" +
            "SELECT b.customer_id FROM bookings b WHERE b.created > @utc_start";
        String[] result = SqlStatementSplitter.splitSetPreamble(sql);
        assertThat(result).hasSize(3);
        assertThat(result[0]).startsWith("SET @report_date");
        assertThat(result[1]).startsWith("SET @utc_start");
        assertThat(result[2]).startsWith("SELECT");
    }

    // ── isSetStatement() ────────────────────────────────────────────────

    @Test
    void isSetStatement_various() {
        assertThat(SqlStatementSplitter.isSetStatement("SET @x = 1")).isTrue();
        assertThat(SqlStatementSplitter.isSetStatement("SET SESSION max_execution_time = 30000")).isTrue();
        assertThat(SqlStatementSplitter.isSetStatement("SET NAMES utf8mb4")).isTrue();
        assertThat(SqlStatementSplitter.isSetStatement("  set @@session.tz = 'UTC'")).isTrue();
        assertThat(SqlStatementSplitter.isSetStatement("SELECT 1")).isFalse();
        assertThat(SqlStatementSplitter.isSetStatement(null)).isFalse();
    }

    // ── Comments before SET ─────────────────────────────────────────────

    @Test
    void splitSetPreamble_commentBeforeSet() {
        // AI rewrites often prepend comments like "-- optimized version"
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "-- optimized\nSET @x = 1; SELECT @x");
        assertThat(result).hasSize(2);
        assertThat(result[0]).contains("SET @x = 1");
        assertThat(result[1]).isEqualTo("SELECT @x");
    }

    @Test
    void splitSetPreamble_blockCommentBeforeSet() {
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "/* tuned */ SET @x = 1; SELECT @x");
        assertThat(result).hasSize(2);
        assertThat(result[0]).contains("SET @x = 1");
        assertThat(result[1]).isEqualTo("SELECT @x");
    }

    @Test
    void isSetStatement_withLeadingComment() {
        assertThat(SqlStatementSplitter.isSetStatement("-- note\nSET @x = 1")).isTrue();
        assertThat(SqlStatementSplitter.isSetStatement("/* note */ SET @x = 1")).isTrue();
    }

    @Test
    void splitSetPreamble_mysqlHashCommentBeforeSet() {
        // MySQL # comment before SET should still be recognized as SET preamble
        String[] result = SqlStatementSplitter.splitSetPreamble(
            "# note\nSET @x = 1; SELECT @x", true);
        assertThat(result).hasSize(2);
        assertThat(result[0]).contains("SET @x = 1");
        assertThat(result[1]).isEqualTo("SELECT @x");
    }

    // ── PostgreSQL dollar-quoted strings ─────────────────────────────────

    @Test
    void split_dollarQuotedString_postgres() {
        // Dollar-quoted strings can contain semicolons
        List<String> result = SqlStatementSplitter.split(
            "SELECT $$hello; world$$; SELECT 2", false);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("$$hello; world$$");
    }

    @Test
    void split_taggedDollarQuote_postgres() {
        List<String> result = SqlStatementSplitter.split(
            "CREATE FUNCTION f() RETURNS void AS $fn$BEGIN; END;$fn$ LANGUAGE plpgsql; SELECT 1", false);
        assertThat(result).hasSize(2);
        assertThat(result.get(0)).contains("$fn$BEGIN; END;$fn$");
    }

    @Test
    void split_dollarQuote_ignoredInMysqlMode() {
        // In MySQL mode, $ is a normal character — no dollar quoting
        List<String> result = SqlStatementSplitter.split(
            "SELECT $$hello; world$$; SELECT 2", true);
        // MySQL splits on the semicolons inside $$ since it's not recognized
        assertThat(result.size()).isGreaterThan(1);
    }

    // ── stripLeadingComments (package-private) ──────────────────────────

    @Test
    void stripLeadingComments_various() {
        assertThat(SqlStatementSplitter.stripLeadingComments("-- note\nSET @x = 1"))
            .isEqualTo("SET @x = 1");
        assertThat(SqlStatementSplitter.stripLeadingComments("/* block */ SET @x = 1"))
            .isEqualTo("SET @x = 1");
        assertThat(SqlStatementSplitter.stripLeadingComments("-- a\n-- b\nSELECT 1"))
            .isEqualTo("SELECT 1");
        assertThat(SqlStatementSplitter.stripLeadingComments("SELECT 1"))
            .isEqualTo("SELECT 1");
    }
}
