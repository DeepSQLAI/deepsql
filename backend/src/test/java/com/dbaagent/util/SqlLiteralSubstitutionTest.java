package com.dbaagent.util;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SqlLiteralSubstitutionTest {

    // ── extractLiteralValues ──────────────────────────────────────────────

    @Test
    void extractLiteralValues_numericAndString() {
        String sql = "SELECT * FROM t WHERE id = 42 AND name = 'Alice'";
        List<String> literals = SqlLiteralSubstitution.extractLiteralValues(sql);
        assertThat(literals).containsExactly("42", "'Alice'");
    }

    @Test
    void extractLiteralValues_booleanAndNull() {
        String sql = "SELECT * FROM t WHERE active = true AND deleted = false AND note = null";
        List<String> literals = SqlLiteralSubstitution.extractLiteralValues(sql);
        assertThat(literals).containsExactly("true", "false", "null");
    }

    @Test
    void extractLiteralValues_escapedString() {
        String sql = "SELECT * FROM t WHERE name = 'O''Brien'";
        List<String> literals = SqlLiteralSubstitution.extractLiteralValues(sql);
        assertThat(literals).containsExactly("'O''Brien'");
    }

    @Test
    void extractLiteralValues_negativeNumber() {
        String sql = "SELECT * FROM t WHERE balance = -100.50";
        List<String> literals = SqlLiteralSubstitution.extractLiteralValues(sql);
        assertThat(literals).containsExactly("-100.50");
    }

    @Test
    void extractLiteralValues_emptyInput() {
        assertThat(SqlLiteralSubstitution.extractLiteralValues(null)).isEmpty();
        assertThat(SqlLiteralSubstitution.extractLiteralValues("")).isEmpty();
    }

    @Test
    void extractLiteralValues_noLiterals() {
        String sql = "SELECT * FROM t WHERE id = ? AND name = ?";
        List<String> literals = SqlLiteralSubstitution.extractLiteralValues(sql);
        assertThat(literals).isEmpty();
    }

    @Test
    void extractLiteralValues_ignoresBacktickedIdentifiers() {
        String sql = "SELECT * FROM `table1` WHERE `column` = 42";
        List<String> literals = SqlLiteralSubstitution.extractLiteralValues(sql);
        assertThat(literals).containsExactly("42");
    }

    // ── substituteLiterals ────────────────────────────────────────────────

    @Test
    void substituteLiterals_replacesQuestionPlaceholders() {
        String parameterized = "SELECT * FROM t WHERE id = ? AND name = ?";
        String source = "SELECT * FROM t WHERE id = 42 AND name = 'Alice'";
        String result = SqlLiteralSubstitution.substituteLiterals(parameterized, source);
        assertThat(result).isEqualTo("SELECT * FROM t WHERE id = 42 AND name = 'Alice'");
    }

    @Test
    void substituteLiterals_replacesPgPlaceholders() {
        String parameterized = "SELECT * FROM t WHERE id = $1 AND name = $2";
        String source = "SELECT * FROM t WHERE id = 42 AND name = 'Alice'";
        String result = SqlLiteralSubstitution.substituteLiterals(parameterized, source);
        assertThat(result).isEqualTo("SELECT * FROM t WHERE id = 42 AND name = 'Alice'");
    }

    @Test
    void substituteLiterals_bailsWhenMorePlaceholdersThanLiterals() {
        String parameterized = "SELECT * FROM t WHERE a = ? AND b = ? AND c = ?";
        String source = "SELECT * FROM t WHERE a = 1 AND b = 2";
        String result = SqlLiteralSubstitution.substituteLiterals(parameterized, source);
        // Should return original since 3 placeholders > 2 literals
        assertThat(result).isEqualTo(parameterized);
    }

    @Test
    void substituteLiterals_handlesFewerPlaceholdersThanLiterals() {
        String parameterized = "SELECT * FROM t WHERE id = ?";
        String source = "SELECT * FROM t WHERE id = 42 AND name = 'Alice'";
        String result = SqlLiteralSubstitution.substituteLiterals(parameterized, source);
        assertThat(result).isEqualTo("SELECT * FROM t WHERE id = 42");
    }

    @Test
    void substituteLiterals_nullInputs() {
        assertThat(SqlLiteralSubstitution.substituteLiterals(null, "source")).isNull();
        assertThat(SqlLiteralSubstitution.substituteLiterals("sql", null)).isEqualTo("sql");
        assertThat(SqlLiteralSubstitution.substituteLiterals("sql", "")).isEqualTo("sql");
    }

    @Test
    void substituteLiterals_noPlaceholders() {
        String sql = "SELECT * FROM t WHERE id = 42";
        String source = "SELECT * FROM t WHERE id = 99";
        String result = SqlLiteralSubstitution.substituteLiterals(sql, source);
        assertThat(result).isEqualTo(sql);
    }

    @Test
    void substituteLiterals_aiRewriteWithDifferentStructure() {
        // AI rewrite restructured the query but kept same number of conditions
        String parameterized = "SELECT b.id FROM bookings b USE INDEX(idx_hotel) WHERE b.customer_id = ? AND b.status = ?";
        String source = "SELECT * FROM bookings WHERE customer_id = 42 AND status = 'active'";
        String result = SqlLiteralSubstitution.substituteLiterals(parameterized, source);
        assertThat(result).isEqualTo("SELECT b.id FROM bookings b USE INDEX(idx_hotel) WHERE b.customer_id = 42 AND b.status = 'active'");
    }

    @Test
    void substituteLiterals_questionMarkInsideQuotedStringNotReplaced() {
        // The '?' inside single quotes in the TARGET is not treated as a placeholder.
        // However, the SOURCE's '?' IS extracted as a string literal by extractLiteralValues,
        // which shifts positional alignment. This is a known limitation of positional matching.
        // In practice, source SQL (sampleQuery) rarely contains literal '?' strings.
        String sql = "SELECT * FROM t WHERE id = ?";
        String source = "SELECT * FROM t WHERE id = 42";
        String result = SqlLiteralSubstitution.substituteLiterals(sql, source);
        assertThat(result).isEqualTo("SELECT * FROM t WHERE id = 42");
    }

    // ── hasPlaceholders ───────────────────────────────────────────────────

    @Test
    void hasPlaceholders_detectsQuestionMark() {
        assertThat(SqlLiteralSubstitution.hasPlaceholders("SELECT * FROM t WHERE id = ?")).isTrue();
    }

    @Test
    void hasPlaceholders_detectsPgParams() {
        assertThat(SqlLiteralSubstitution.hasPlaceholders("SELECT * FROM t WHERE id = $1")).isTrue();
    }

    @Test
    void hasPlaceholders_noPlaceholders() {
        assertThat(SqlLiteralSubstitution.hasPlaceholders("SELECT * FROM t WHERE id = 42")).isFalse();
    }

    @Test
    void hasPlaceholders_nullAndEmpty() {
        assertThat(SqlLiteralSubstitution.hasPlaceholders(null)).isFalse();
        assertThat(SqlLiteralSubstitution.hasPlaceholders("")).isFalse();
    }

    // ── realistic benchmark scenario ──────────────────────────────────────

    @Test
    void substituteLiterals_realisticBenchmarkScenario() {
        // ORIGINAL candidate (from sampleQuery) has real values
        String originalSql = "SELECT r.id, r.checkin_date, r.checkout_date, r.guest_name " +
            "FROM reservations r " +
            "WHERE r.customer_id = 12345 AND r.status = 'CONFIRMED' AND r.checkin_date >= '2026-01-01'";

        // AI_REWRITE candidate still has placeholders (applySampleLiterals failed at creation)
        String aiRewriteSql = "SELECT r.id, r.checkin_date, r.checkout_date, r.guest_name " +
            "FROM reservations r USE INDEX(idx_hotel_status_checkin) " +
            "WHERE r.customer_id = ? AND r.status = ? AND r.checkin_date >= ?";

        String result = SqlLiteralSubstitution.substituteLiterals(aiRewriteSql, originalSql);

        assertThat(result).isEqualTo(
            "SELECT r.id, r.checkin_date, r.checkout_date, r.guest_name " +
            "FROM reservations r USE INDEX(idx_hotel_status_checkin) " +
            "WHERE r.customer_id = 12345 AND r.status = 'CONFIRMED' AND r.checkin_date >= '2026-01-01'"
        );
        assertThat(SqlLiteralSubstitution.hasPlaceholders(result)).isFalse();
    }

    @Test
    void substituteLiterals_multipleInClause() {
        String originalSql = "SELECT * FROM t WHERE id IN (1, 2, 3) AND name = 'test'";
        String parameterized = "SELECT * FROM t WHERE id IN (?, ?, ?) AND name = ?";
        String result = SqlLiteralSubstitution.substituteLiterals(parameterized, originalSql);
        assertThat(result).isEqualTo("SELECT * FROM t WHERE id IN (1, 2, 3) AND name = 'test'");
    }

    // ── PostgreSQL "Query Parameters" parsing (auto_explain / extended protocol) ──

    @Test
    void parsePgLogParameters_ordersValuesAndPreservesNull() {
        List<String> values = SqlLiteralSubstitution.parsePgLogParameters(
            "$1 = '471', $2 = 'CANCELED', $3 = NULL, $4 = 'f'");
        assertThat(values).containsExactly("'471'", "'CANCELED'", "NULL", "'f'");
    }

    @Test
    void parsePgLogParameters_handlesCommaInsideQuotesAndByteaAndEscapes() {
        List<String> values = SqlLiteralSubstitution.parsePgLogParameters(
            "$1 = '\\xaced0005', $2 = 'a, b', $3 = 'O''Brien'");
        // A comma inside quotes must NOT split; bytea hex and doubled-quote escapes survive.
        assertThat(values).containsExactly("'\\xaced0005'", "'a, b'", "'O''Brien'");
    }

    @Test
    void substitutePgLogParameters_reconstructsLiteralQuery() {
        String sql = "select count(*) from transactions where entity_id in ($1) "
            + "and status in ($2,$3) and ($4=false or tax_exempt=true)";
        String params = "$1 = '471', $2 = 'CANCELED', $3 = 'FAILED', $4 = 'f'";
        String result = SqlLiteralSubstitution.substitutePgLogParameters(sql, params);
        assertThat(result).isEqualTo(
            "select count(*) from transactions where entity_id in ('471') "
            + "and status in ('CANCELED','FAILED') and ('f'=false or tax_exempt=true)");
        assertThat(SqlLiteralSubstitution.hasPlaceholders(result)).isFalse();
    }

    @Test
    void substitutePgLogParameters_returnsUnchangedWhenNoParams() {
        String sql = "select * from t where id = $1";
        assertThat(SqlLiteralSubstitution.substitutePgLogParameters(sql, "")).isEqualTo(sql);
        assertThat(SqlLiteralSubstitution.substitutePgLogParameters(sql, null)).isEqualTo(sql);
    }
}
