package com.dbaagent.util;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Identifier quoting for the SQL that cannot be parameterised.
 *
 * <p>Table and column names cannot be bind parameters, so they are interpolated into SQL by
 * hand. {@code CardinalityEstimationService.quoteIdentifier} wrapped them in quotes and never
 * doubled an embedded one, which is no protection at all — the same way
 * {@code "'" + value + "'"} is no protection for a string literal.
 *
 * <p>Verified against a real PostgreSQL, not inferred. A {@code tableName} path variable of
 * {@code victim" AS t; DROP TABLE zz_inj.probe; SELECT 1 FROM zz_inj."victim} produced
 *
 * <pre>SELECT COUNT(*) FROM zz_inj."victim" AS t; DROP TABLE zz_inj.probe; SELECT 1 FROM zz_inj."victim"</pre>
 *
 * which ran with no errors: the count returned, the table was dropped (it existed before and
 * did not after), and the trailing select returned its rows. The payload carries no {@code /},
 * so Spring's {@code StrictHttpFirewall} does not stand in its way.
 */
class SqlIdentifierTest {

    // ── the injection that was proven to execute ──────────────────────────────

    @Test
    void doublesAnEmbeddedDoubleQuoteSoTheIdentifierCannotBeClosed() {
        String payload = "victim\" AS t; DROP TABLE zz_inj.probe; SELECT 1 FROM zz_inj.\"victim";

        String quoted = SqlIdentifier.quote(payload, "postgres");

        assertEquals(
            "\"victim\"\" AS t; DROP TABLE zz_inj.probe; SELECT 1 FROM zz_inj.\"\"victim\"",
            quoted);
        // the whole payload is now one identifier: no unescaped quote can terminate it
        assertEquals(1, countUnescapedQuotes(quoted, '"'),
            "an unescaped quote inside the body would end the identifier early: " + quoted);
    }

    @Test
    void doublesAnEmbeddedBacktickForMysql() {
        String payload = "victim` ; DROP TABLE probe; SELECT 1 FROM `victim";

        String quoted = SqlIdentifier.quote(payload, "mysql");

        assertEquals("`victim`` ; DROP TABLE probe; SELECT 1 FROM ``victim`", quoted);
        assertEquals(1, countUnescapedQuotes(quoted, '`'), quoted);
    }

    /**
     * The sinks build {@code SELECT COUNT(*) FROM %s}, so the property that matters is that the
     * whole payload lands inside one identifier rather than becoming a second statement.
     *
     * <p>Asserted on the parse, not on the text. A first version of this test used the regex
     * {@code .*"\s*;.*} and failed on correct output, because {@code "";} is an <em>escaped</em>
     * quote followed by a semicolon <em>inside</em> the identifier — textually close to a
     * terminator and semantically its opposite. That is the same confusion the vulnerable
     * quoter made, so it is worth not repeating in the test.
     *
     * <p>Confirmed against a real PostgreSQL: this exact SQL answers
     * {@code ERROR: relation "t"; DROP TABLE zz_v.probe; --" does not exist} — the server read
     * the payload as one table name — and the probe table it names was still there afterwards,
     * where the unescaped form had dropped it.
     */
    @Test
    void theInjectedStatementCollapsesIntoASingleIdentifier() {
        String payload = "t\"; DROP TABLE probe; --";

        String quoted = SqlIdentifier.quote(payload, "postgres");
        String sql = "SELECT COUNT(*) FROM " + quoted;

        assertEquals("SELECT COUNT(*) FROM \"t\"\"; DROP TABLE probe; --\"", sql);
        assertTrue(sql.endsWith(quoted), "the identifier must be the whole tail of the statement");
        assertEquals(1, countUnescapedQuotes(quoted, '"'),
            "only the closing quote may be unescaped, or the identifier ends early: " + quoted);
    }

    // ── ordinary identifiers must keep working ────────────────────────────────

    @Test
    void leavesAnOrdinaryIdentifierAloneApartFromTheQuotes() {
        assertEquals("\"orders\"", SqlIdentifier.quote("orders", "postgres"));
        assertEquals("\"total_amount\"", SqlIdentifier.quote("total_amount", "postgres"));
        assertEquals("`orders`", SqlIdentifier.quote("orders", "mysql"));
    }

    @Test
    void picksTheQuoteCharacterFromTheDialect() {
        assertEquals("`t`", SqlIdentifier.quote("t", "mysql"));
        assertEquals("`t`", SqlIdentifier.quote("t", "MySQL"));
        assertEquals("\"t\"", SqlIdentifier.quote("t", "postgres"));
        assertEquals("\"t\"", SqlIdentifier.quote("t", "postgresql"));
    }

    /**
     * An unknown or null dialect must not fall through to "no quoting". Postgres double quotes
     * are the ANSI form and the safe default; guessing MySQL backticks for an unknown dialect
     * would be the riskier direction.
     */
    @Test
    void defaultsToAnsiQuotingForAnUnknownDialect() {
        assertEquals("\"t\"", SqlIdentifier.quote("t", null));
        assertEquals("\"t\"", SqlIdentifier.quote("t", "oracle"));
        assertEquals("\"t\"", SqlIdentifier.quote("t", ""));
    }

    // ── rejecting what should never reach SQL at all ──────────────────────────

    /**
     * Escaping alone makes injection impossible but still lets a caller name an identifier the
     * feature never meant to touch. {@code requireSafe} is the second layer: the brain's
     * statistics paths only ever address real tables and columns, so anything that cannot be
     * one is refused before a statement is built.
     */
    @Test
    void refusesAnIdentifierCarryingSqlSyntax() {
        for (String bad : new String[] {
            "victim\" AS t; DROP TABLE probe; --",
            "t; DROP TABLE probe",
            "t--comment",
            "t/*x*/",
            "t'or'1'='1"
        }) {
            assertThrows(IllegalArgumentException.class,
                () -> SqlIdentifier.requireSafe(bad), "should refuse: " + bad);
        }
    }

    @Test
    void refusesBlankAndNull() {
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.requireSafe(null));
        assertThrows(IllegalArgumentException.class, () -> SqlIdentifier.requireSafe("   "));
    }

    /**
     * Real schemas carry all of these. A validator that refused them would break the feature it
     * is protecting, which is the usual reason such a check gets deleted later.
     */
    @Test
    void acceptsTheIdentifiersRealSchemasActuallyUse() {
        for (String ok : new String[] {
            "orders",
            "total_amount",
            "Orders",
            "order_items_2026",
            "public.orders",
            "_private",
            "v_daily_revenue",
            "tableName$"
        }) {
            assertEquals(ok, SqlIdentifier.requireSafe(ok), "should accept: " + ok);
        }
    }

    @Test
    void requireSafeReturnsTheIdentifierSoItComposesWithQuote() {
        assertEquals("\"orders\"",
            SqlIdentifier.quote(SqlIdentifier.requireSafe("orders"), "postgres"));
    }

    /** Counts quote characters that are not part of a doubled pair. */
    private static int countUnescapedQuotes(String quoted, char q) {
        String body = quoted.substring(1, quoted.length() - 1);
        int unescaped = 0;
        for (int i = 0; i < body.length(); i++) {
            if (body.charAt(i) != q) continue;
            if (i + 1 < body.length() && body.charAt(i + 1) == q) { i++; continue; }
            unescaped++;
        }
        return unescaped + 1; // the closing quote
    }
}
