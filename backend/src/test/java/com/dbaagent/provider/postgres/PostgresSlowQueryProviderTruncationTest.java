package com.dbaagent.provider.postgres;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Server-side query-text truncation detection for pg_stat_statements.
 *
 * pg_stat_statements truncates the `query` column at the server-configured
 * `track_activity_query_size` (default 1024 bytes) with no on-disk
 * indicator — the row just has fewer bytes than the real query. EXPLAIN
 * against the truncated text either fails outright (syntax error) or
 * returns a misleading partial plan, so SlowQuery exposes a
 * `sourceTruncated` flag and the chat/CLI surfaces a clear warning when
 * it's set.
 *
 * These tests exercise the detection logic directly via reflection — the
 * full collectSlowQueries path needs a live PG connection, but the
 * looksTruncated/readTrackActivityQuerySize helpers are pure logic.
 */
class PostgresSlowQueryProviderTruncationTest {

    private final PostgresSlowQueryProvider provider = new PostgresSlowQueryProvider();

    private boolean looksTruncated(String queryText, int trackSize) throws Exception {
        Method m = PostgresSlowQueryProvider.class.getDeclaredMethod("looksTruncated", String.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, queryText, trackSize);
    }

    @Test
    void looksTruncated_flagsExactly1024CharQueriesAgainstDefaultLimit() throws Exception {
        // The exact scenario the user reported: a query that came back at
        // 1024 chars because pg_stat_statements is at its default. Must
        // flag this as truncated.
        String q = "a".repeat(1024);
        assertTrue(looksTruncated(q, 1024), "1024-char query must be flagged when track size is 1024");
    }

    @Test
    void looksTruncated_flagsQueriesWithinAFewBytesOfTheLimit() throws Exception {
        // UTF-8 boundary effects can shave 1–2 bytes off the visible
        // length when pg_stat_statements cuts a multi-byte sequence.
        // We accept >= trackSize-3 as "truncated".
        assertTrue(looksTruncated("a".repeat(1022), 1024));
        assertTrue(looksTruncated("a".repeat(1021), 1024));
    }

    @Test
    void looksTruncated_doesNotFlagShortQueries() throws Exception {
        assertFalse(looksTruncated("SELECT 1", 1024));
        assertFalse(looksTruncated("a".repeat(500), 1024));
        assertFalse(looksTruncated("a".repeat(1020), 1024), "1020 chars < trackSize-3 should NOT trigger");
    }

    @Test
    void looksTruncated_respectsACustomTrackSize() throws Exception {
        // Some installs set track_activity_query_size = 4096 in
        // postgresql.conf — detection must scale with the actual limit,
        // not the default.
        assertFalse(looksTruncated("a".repeat(1024), 4096), "1024 chars is fine when track is 4096");
        assertTrue(looksTruncated("a".repeat(4096), 4096));
    }

    @Test
    void looksTruncated_handlesNullAndZeroLimitGracefully() throws Exception {
        assertFalse(looksTruncated(null, 1024));
        assertFalse(looksTruncated("anything", 0));
        assertFalse(looksTruncated("anything", -1));
    }

    @Test
    void provider_returnsCorrectDatabaseType() {
        // Sanity check that we're still registered as the postgres provider
        // — making sure the truncation-detection refactor didn't accidentally
        // change how the dispatch identifies us.
        assertEquals("postgres", provider.getDatabaseType());
    }
}
