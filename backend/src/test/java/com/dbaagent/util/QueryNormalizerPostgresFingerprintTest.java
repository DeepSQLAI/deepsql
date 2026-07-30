package com.dbaagent.util;

import com.dbaagent.service.QueryFingerprintService;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Reproduction for the customer report: PostgreSQL CloudWatch slow-log samples
 * never attach to the fingerprint shown by `slow-queries latest`, while MySQL works.
 *
 * Root cause hypothesis: QueryNormalizer.normalize() turns PostgreSQL's $N
 * positional placeholders (from pg_stat_statements) into "$?" because the
 * numeric pattern \b\d+\b matches the digit inside "$1". A literal execution
 * captured from the slow log normalizes the same position to "?". So the
 * canonical fingerprint of the pg_stat_statements text != the fingerprint of
 * the literal-bearing log line, and the two never join.
 *
 * MySQL is immune because performance_schema DIGEST_TEXT already uses "?".
 */
class QueryNormalizerPostgresFingerprintTest {

    // The shape the customer described: 7 bind params, an IN-list, equality and booleans.
    private static final String PG_STAT_STATEMENTS_TEXT =
        "SELECT id FROM external_sales_channel_item esci " +
        "JOIN cohort_item ci ON ci.id = esci.cohort_item_id " +
        "WHERE esc.extended_shelf = $1 AND esc.fulfillment_hours_mode = $2 " +
        "AND esc.sales_channel_type_id = $3 AND esci.include_item_type_3 = $4 " +
        "AND ci.id IN ($5) AND esci.include_child_items = $6 AND ci.id IN ($7)";

    private static final String SLOW_LOG_LITERAL_TEXT =
        "SELECT id FROM external_sales_channel_item esci " +
        "JOIN cohort_item ci ON ci.id = esci.cohort_item_id " +
        "WHERE esc.extended_shelf = true AND esc.fulfillment_hours_mode = false " +
        "AND esc.sales_channel_type_id = 1 AND esci.include_item_type_3 = true " +
        "AND ci.id IN (1021, 1045, 2310, 4892, 5001) AND esci.include_child_items = false " +
        "AND ci.id IN (1021, 1045, 2310, 4892, 5001)";

    // MySQL's equivalent of pg_stat_statements: performance_schema DIGEST_TEXT uses "?".
    private static final String MYSQL_DIGEST_TEXT =
        "SELECT id FROM external_sales_channel_item esci " +
        "JOIN cohort_item ci ON ci.id = esci.cohort_item_id " +
        "WHERE esc.extended_shelf = ? AND esc.fulfillment_hours_mode = ? " +
        "AND esc.sales_channel_type_id = ? AND esci.include_item_type_3 = ? " +
        "AND ci.id IN (?) AND esci.include_child_items = ? AND ci.id IN (?)";

    @Test
    void postgres_dollarPlaceholders_normalizeTo_question() {
        // $N positional placeholders must collapse to "?" (not "$?").
        assertEquals("select * from t where id = ?",
            QueryNormalizer.normalize("SELECT * FROM t WHERE id = $1"));
    }

    @Test
    void postgres_booleanLiterals_normalizeTo_question() {
        // Postgres logs render booleans as true/false; they must collapse to "?".
        assertEquals("select * from t where active = ? and deleted = ?",
            QueryNormalizer.normalize("SELECT * FROM t WHERE active = true AND deleted = FALSE"));
    }

    @Test
    void postgres_statSample_and_logLiteral_shouldShareFingerprint() {
        String fpFromStat = QueryFingerprintService.computeCanonicalFingerprint(
            QueryNormalizer.normalize(PG_STAT_STATEMENTS_TEXT));
        String fpFromLog = QueryFingerprintService.computeCanonicalFingerprint(
            QueryNormalizer.normalize(SLOW_LOG_LITERAL_TEXT));

        // DESIRED contract: the literal execution from the slow log must key to the
        // same fingerprint as the pg_stat_statements record so `samples <fp>` resolves.
        assertEquals(fpFromStat, fpFromLog,
            "Postgres pg_stat_statements fingerprint must equal the slow-log literal "
            + "fingerprint, otherwise CloudWatch samples never attach. "
            + "stat-normalized=[" + QueryNormalizer.normalize(PG_STAT_STATEMENTS_TEXT) + "] "
            + "log-normalized=[" + QueryNormalizer.normalize(SLOW_LOG_LITERAL_TEXT) + "]");
    }

    @Test
    void mysql_digest_and_logLiteral_alreadyShareFingerprint() {
        String fpFromDigest = QueryFingerprintService.computeCanonicalFingerprint(
            QueryNormalizer.normalize(MYSQL_DIGEST_TEXT));
        String fpFromLog = QueryFingerprintService.computeCanonicalFingerprint(
            QueryNormalizer.normalize(SLOW_LOG_LITERAL_TEXT));

        // This passes today — explains why MySQL "appears fine".
        assertEquals(fpFromDigest, fpFromLog,
            "MySQL digest (already '?') should match the literal log fingerprint.");
    }

    @Test
    void postgres_and_mysql_placeholderForms_canonicalizeIdentically() {
        String fpStat = QueryFingerprintService.computeCanonicalFingerprint(
            QueryNormalizer.normalize(PG_STAT_STATEMENTS_TEXT));
        String fpDigest = QueryFingerprintService.computeCanonicalFingerprint(
            QueryNormalizer.normalize(MYSQL_DIGEST_TEXT));
        // After the fix, Postgres "$N" and MySQL "?" placeholder forms collapse
        // to the same normalized text and therefore the same fingerprint.
        assertEquals(fpStat, fpDigest,
            "Postgres $N and MySQL ? placeholder forms must canonicalize to one fingerprint.");
    }
}
