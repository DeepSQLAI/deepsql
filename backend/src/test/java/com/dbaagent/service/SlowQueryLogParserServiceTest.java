package com.dbaagent.service;

import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.provider.DatabaseProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlowQueryLogParserServiceTest {

    private DatabaseProviderRegistry providerRegistry;
    private SlowQueryLogParserService service;

    @BeforeEach
    void setUp() {
        providerRegistry = mock(DatabaseProviderRegistry.class);
        when(providerRegistry.getCanonicalName("mysql")).thenReturn("mysql");
        when(providerRegistry.getCanonicalName("postgres")).thenReturn("postgres");
        when(providerRegistry.getCanonicalName("postgresql")).thenReturn("postgres");
        when(providerRegistry.getCanonicalName("mariadb")).thenReturn("mysql");
        when(providerRegistry.getCanonicalName("aurora-mysql")).thenReturn("mysql");
        when(providerRegistry.getCanonicalName("aurora-postgres")).thenReturn("postgres");
        service = new SlowQueryLogParserService(providerRegistry);
    }

    @Test
    void capsParsedQueries() throws Exception {
        // Use a small cap to avoid memory/CPU spikes during testing
        int testCap = 100;
        int total = testCap + 5; // 105 queries, should be capped at 100

        StringBuilder log = new StringBuilder();
        for (int i = 0; i < total; i++) {
            log.append("# Time: 2024-01-01T00:00:00.000000Z\n");
            log.append("# User@Host: root[root] @ localhost []\n");
            log.append("# Query_time: 1.00  Lock_time: 0.00 Rows_sent: 1  Rows_examined: 1\n");
            log.append("SELECT ").append(i).append(";\n");
        }

        // Set the maxParsedQueries field via reflection for testing
        Field maxParsedField = SlowQueryLogParserService.class.getDeclaredField("maxParsedQueries");
        maxParsedField.setAccessible(true);
        maxParsedField.setInt(service, testCap);

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.toString().getBytes(StandardCharsets.UTF_8)),
            "mysql",
            "conn-1"
        );

        assertNotNull(analysis);
        assertNotNull(analysis.getTotalQueriesAnalyzed());
        assertEquals((long) testCap, analysis.getTotalQueriesAnalyzed());
    }

    @Test
    void parsesValidMySqlSlowQueryLog() throws Exception {
        String log = """
            # Time: 2024-01-01T00:00:00.000000Z
            # User@Host: root[root] @ localhost []
            # Query_time: 2.50  Lock_time: 0.01 Rows_sent: 10  Rows_examined: 1000
            SELECT * FROM users WHERE status = 'active';
            """;

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)),
            "mysql",
            "conn-1"
        );

        assertNotNull(analysis);
        assertEquals("conn-1", analysis.getConnectionId());
        assertNotNull(analysis.getTotalQueriesAnalyzed());
        assertEquals(1L, analysis.getTotalQueriesAnalyzed());
    }

    /**
     * RDS PostgreSQL with auto_explain emits the slow statement as a
     * "duration: N ms  plan:" header followed by a tab-indented "Query Text:"
     * line and an EXPLAIN plan tree, rather than the standard
     * "duration: N ms  statement:" format. The parser must recognise it.
     */
    private static final String AUTO_EXPLAIN_LOG = String.join("\n",
        "2025-12-05 20:26:22 UTC:10.0.0.1(45150):postgres@postgres:[4481]:LOG:  duration: 8868.032 ms  plan:",
        "\tQuery Text: select count(lc1_0.id) from loyalty_customer lc1_0 where lc1_0.loyalty_program_id = $1",
        "\tAggregate  (cost=12345.67..12345.68 rows=1 width=8) (actual time=8868.001..8868.002 rows=1 loops=1)",
        "\t  ->  Seq Scan on loyalty_customer lc1_0  (cost=0.00..12000.00 rows=100000 width=8)",
        "\t        Filter: (loyalty_program_id = 5)",
        "");

    @Test
    void parsesPostgresAutoExplainPlanFormat() throws Exception {
        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(AUTO_EXPLAIN_LOG.getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        assertNotNull(analysis);
        assertEquals(1L, analysis.getTotalQueriesAnalyzed());
        assertEquals(8868.032, analysis.getTopSlowQueries().get(0).getMaxExecutionTimeMs(), 0.001);
    }

    @Test
    void autoExplainRecoveredSqlExcludesPlanTree() throws Exception {
        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(AUTO_EXPLAIN_LOG.getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        assertEquals(1, analysis.getTopSlowQueries().size());
        String sql = analysis.getTopSlowQueries().get(0).getSampleQuery();
        assertNotNull(sql);
        assertTrue(sql.contains("loyalty_customer"), "should recover the SQL text, was: " + sql);
        assertFalse(sql.contains("cost="), "EXPLAIN plan tree must not pollute recovered SQL, was: " + sql);
        assertFalse(sql.toLowerCase().contains("seq scan"),
            "EXPLAIN plan tree must not pollute recovered SQL, was: " + sql);
    }

    /**
     * Real RDS auto_explain output pretty-prints Hibernate SQL across multiple
     * lines and may append a "Query Parameters:" line before the plan tree. The
     * full multi-line query must be recovered, with parameters and plan excluded.
     */
    private static final String AUTO_EXPLAIN_MULTILINE_LOG = String.join("\n",
        "2026-05-04 23:46:56 UTC:54.160.147.98(48870):postgres@postgres:[43018]:LOG:  duration: 4242.508 ms  plan:",
        "\tQuery Text:                SELECT",
        "\t                 COALESCE(SUM(ti.quantity), 0) AS totalQuantity,",
        "\t                 ti.entity_id",
        "\t            FROM transaction_item ti",
        "\t            WHERE ti.entity_id = $1",
        "\t            GROUP BY ti.entity_id",
        "\tQuery Parameters: $1 = '77'",
        "\tAggregate  (cost=1234.56..1234.57 rows=1 width=16) (actual time=4242.0..4242.1 rows=1 loops=1)",
        "\t  ->  Seq Scan on transaction_item ti  (cost=0.00..1000.00 rows=50000 width=12)",
        "2026-05-04 23:47:00 UTC:10.0.0.1(1):postgres@postgres:[1]:LOG:  duration: 5.000 ms  statement: SELECT 1",
        "");

    @Test
    void autoExplainRecoversFullMultilineQueryText() throws Exception {
        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(AUTO_EXPLAIN_MULTILINE_LOG.getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        String sql = analysis.getTopSlowQueries().stream()
            .filter(q -> q.getSampleQuery() != null && q.getSampleQuery().contains("COALESCE"))
            .map(q -> q.getSampleQuery())
            .findFirst()
            .orElse(null);

        assertNotNull(sql, "multi-line auto_explain query should be recovered");
        // Full body across all lines, not just the first "SELECT".
        assertTrue(sql.contains("COALESCE(SUM(ti.quantity)"), "body line missing: " + sql);
        assertTrue(sql.contains("FROM transaction_item ti"), "body line missing: " + sql);
        assertTrue(sql.contains("GROUP BY ti.entity_id"), "body line missing: " + sql);
        // Parameters and plan tree must be excluded.
        assertFalse(sql.contains("Query Parameters"), "parameters leaked into SQL: " + sql);
        assertFalse(sql.contains("$1 = '77'"), "parameter values leaked into SQL: " + sql);
        assertFalse(sql.contains("cost="), "plan tree leaked into SQL: " + sql);
        assertFalse(sql.toLowerCase().contains("seq scan"), "plan tree leaked into SQL: " + sql);
    }

    /**
     * PostgreSQL extended query protocol logs three phases: "parse &lt;name&gt;:",
     * "bind &lt;name&gt;:" and "execute &lt;name&gt;:". asyncpg/JDBC prepared-statement
     * traffic emits parse/bind durations that must be captured like execute/statement.
     */
    @Test
    void parsesExtendedProtocolParseAndBindPhases() throws Exception {
        String log = String.join("\n",
            "2026-05-18 16:01:24 UTC:10.0.35.10(54368):agent@db:[39674]:LOG:  duration: 1213.961 ms  parse <unnamed>: SELECT count(*) FROM pg_stat_database",
            "2026-05-18 16:01:25 UTC:10.0.35.10(54368):agent@db:[39674]:LOG:  duration: 51.500 ms  bind stmt_1: SELECT * FROM users WHERE id = $1",
            "2026-05-18 16:01:26 UTC:10.0.35.10(54368):agent@db:[39674]:LOG:  duration: 12.250 ms  execute stmt_1: SELECT * FROM users WHERE id = $1",
            "");

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        // All three phases captured (execute + bind share SQL so group to 2 distinct texts).
        assertEquals(3L, analysis.getTotalQueriesAnalyzed());

        boolean hasParse = analysis.getTopSlowQueries().stream()
            .anyMatch(q -> q.getSampleQuery() != null
                && q.getSampleQuery().contains("pg_stat_database"));
        assertTrue(hasParse, "parse-phase slow query must be recovered");
    }

    @Test
    void filtersAuditSoLargeMostlyAuditInputIsNotRejected() throws Exception {
        // maxLogBytes small enough that the unfiltered input exceeds it, but the
        // slow-query content alone is well under it.
        Field maxBytes = SlowQueryLogParserService.class.getDeclaredField("maxLogBytes");
        maxBytes.setAccessible(true);
        maxBytes.setLong(service, 4096L);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) { // ~200 audit lines, well over 4096 bytes
            sb.append("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,")
              .append(i).append(",1,READ,SELECT,,,\"SELECT ").append(i).append("\"\n");
        }
        sb.append("2026-05-04 23:47:00 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 1200.0 ms  statement: SELECT * FROM big_table\n");

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        // Without filtering this would throw LogSizeExceededException (input > 4096).
        assertEquals(1L, analysis.getTotalQueriesAnalyzed());
        assertTrue(analysis.getTopSlowQueries().get(0).getSampleQuery().contains("big_table"));
    }

    @Test
    void filtersSmallRawPostgresLogUnderSniffSize() throws Exception {
        String log = String.join("\n",
            "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  AUDIT: SESSION,1,1,READ,SELECT,,,\"x\"",
            "2026-05-04 23:46:57 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 30.0 ms  statement: SELECT * FROM small_table",
            "");
        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)), "postgres", "conn-1");
        assertEquals(1L, analysis.getTotalQueriesAnalyzed());
        assertTrue(analysis.getTopSlowQueries().get(0).getSampleQuery().contains("small_table"));
    }

    @Test
    void truncatesAndCompletesWhenFilteredOutputExceedsCap() throws Exception {
        Field maxBytes = SlowQueryLogParserService.class.getDeclaredField("maxLogBytes");
        maxBytes.setAccessible(true);
        maxBytes.setLong(service, 2048L);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 200; i++) {
            sb.append("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: ")
              .append(100 + i).append(".0 ms  statement: SELECT * FROM tbl WHERE id = ").append(i).append("\n");
        }

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
            "postgres", "conn-1");

        assertTrue(analysis.isTruncated(), "analysis must be flagged truncated");
        assertTrue(analysis.getTotalSlowQueries() > 0, "partial results must still be present");
    }

    @Test
    void notTruncatedWhenUnderCap() throws Exception {
        String log = "2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: 12.0 ms  statement: SELECT 1\n";
        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)), "postgres", "conn-1");
        assertFalse(analysis.isTruncated());
    }

    @Test
    void parseAndAnalyzePathTruncatesOversizedFile() throws Exception {
        Field maxBytes = SlowQueryLogParserService.class.getDeclaredField("maxLogBytes");
        maxBytes.setAccessible(true);
        maxBytes.setLong(service, 1024L);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) {
            sb.append("2026-05-04 23:46:56 UTC:10.0.0.1(1):u@db:[1]:LOG:  duration: ")
              .append(50 + i).append(".0 ms  statement: SELECT ").append(i).append("\n");
        }
        java.nio.file.Path tmp = java.nio.file.Files.createTempFile("parser-path-trunc-", ".log");
        try {
            java.nio.file.Files.writeString(tmp, sb.toString());
            SlowQueryAnalysis analysis = service.parseAndAnalyze(tmp, "postgres", "conn-1");
            assertTrue(analysis.isTruncated(), "direct Path over cap must be flagged truncated");
            assertTrue(analysis.getTotalSlowQueries() > 0, "partial results expected");
        } finally {
            java.nio.file.Files.deleteIfExists(tmp);
        }
    }

    @Test
    void truncatesNonPostgresInputInsteadOfThrowing() throws Exception {
        // A non-Postgres source (MySQL slow log here) does not match the raw-Postgres
        // sniff, so copyToTempFile takes its raw-copy branch. That branch used to wrap
        // the stream in a throwing CappedInputStream, so an oversized S3/MySQL source
        // failed the whole job. It must now truncate to the cap and flag the analysis.
        Field maxBytes = SlowQueryLogParserService.class.getDeclaredField("maxLogBytes");
        maxBytes.setAccessible(true);
        maxBytes.setLong(service, 2048L);

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 100; i++) { // ~100 MySQL slow-log blocks, well over 2048 bytes
            sb.append("# Time: 2024-01-01T00:00:0").append(i % 10).append(".000000Z\n")
              .append("# User@Host: u[u] @ h [10.0.0.1]\n")
              .append("# Query_time: 1.500  Lock_time: 0.000  Rows_sent: 1  Rows_examined: 100\n")
              .append("SELECT * FROM orders WHERE id = ").append(i).append(";\n");
        }

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(sb.toString().getBytes(StandardCharsets.UTF_8)),
            "mysql", "conn-1");

        assertTrue(analysis.isTruncated(),
            "non-Postgres input over cap must be flagged truncated, not throw LogSizeExceededException");
    }

    @Test
    void parsesMariaDbAsMySQL() throws Exception {
        String log = """
            # Time: 2024-01-01T00:00:00.000000Z
            # User@Host: root[root] @ localhost []
            # Query_time: 1.50  Lock_time: 0.00 Rows_sent: 5  Rows_examined: 500
            SELECT * FROM orders;
            """;

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)),
            "mariadb",  // Should be treated as MySQL
            "conn-1"
        );

        assertNotNull(analysis);
        assertEquals(1L, analysis.getTotalQueriesAnalyzed());
    }

    /**
     * The customer's reported case: an RDS Aurora PostgreSQL slow log
     * (standard "duration: N ms  statement:" form) carrying full literals,
     * spanning multiple lines, with an IN-list and a boolean. The parser must
     * (1) preserve the literal values in the sample, and (2) key the row on the
     * SAME canonical fingerprint that the equivalent pg_stat_statements text
     * ($N placeholders) produces — otherwise CloudWatch samples never attach to
     * the fingerprint surfaced by `latest`/`analyze`.
     */
    @Test
    void postgresCloudWatchLiteralLog_preservesLiterals_andKeysOnCanonicalFingerprint() throws Exception {
        String log = String.join("\n",
            "2026-06-15 20:58:44 UTC:10.0.0.5(54321):appuser@transformity:[12345]:LOG:  duration: 54200.123 ms  statement: SELECT esci.id FROM external_sales_channel_item esci",
            "\tJOIN cohort_item ci ON ci.id = esci.cohort_item_id",
            "\tWHERE esc.extended_shelf = true AND esc.sales_channel_type_id = 1 AND ci.id IN (1021, 1045, 2310)",
            "2026-06-15 20:58:45 UTC:10.0.0.5(54321):appuser@transformity:[12345]:LOG:  duration: 12.000 ms  statement: SELECT 1",
            "");

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        assertNotNull(analysis);
        var slow = analysis.getTopSlowQueries().stream()
            .filter(q -> q.getMaxExecutionTimeMs() != null && q.getMaxExecutionTimeMs() > 1000)
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the 54s literal-bearing query"));

        // (1) Literals must survive into the runnable sample.
        String sample = slow.getSampleQuery();
        assertNotNull(sample);
        assertTrue(sample.contains("1021"), "IN-list literals must be preserved, was: " + sample);
        assertTrue(sample.contains("true"), "boolean literal must be preserved, was: " + sample);

        // (2) The row's id must be the canonical fingerprint of the equivalent
        // pg_stat_statements ($N) text, so the sample joins the right query.
        String pgStatText = "SELECT esci.id FROM external_sales_channel_item esci "
            + "JOIN cohort_item ci ON ci.id = esci.cohort_item_id "
            + "WHERE esc.extended_shelf = $1 AND esc.sales_channel_type_id = $2 AND ci.id IN ($3)";
        String expectedFingerprint = QueryFingerprintService.computeCanonicalFingerprint(
            com.dbaagent.util.QueryNormalizer.normalize(pgStatText));
        assertEquals(expectedFingerprint, slow.getQueryId(),
            "CloudWatch literal log must key on the same canonical fingerprint as pg_stat_statements");
    }

    /**
     * The customer's ACTUAL log shape (verified against their RDS Aurora
     * CloudWatch stream): auto_explain "plan:" format where the Query Text uses
     * $N placeholders and the real values live on a separate "Query Parameters:"
     * line. The parser must reconstruct a literal-bearing sample by substituting
     * the parameters, while keeping queryText (with $N) for fingerprinting.
     */
    @Test
    void autoExplainQueryParameters_areSubstitutedIntoSample() throws Exception {
        String log = String.join("\n",
            "2026-06-14 01:20:04 UTC:13.223.179.212(57010):postgres@postgres:[15467]:LOG:  duration: 1438.767 ms  plan:",
            "\tQuery Text: select count(*) from transactions t1_0 where t1_0.entity_id in ($1) and ($2 is null or t1_0.status in ($3,$4)) and ($5=false or t1_0.tax_exempt=true)",
            "\tQuery Parameters: $1 = '471', $2 = 'CANCELED', $3 = 'CANCELED', $4 = 'FAILED', $5 = 'f'",
            "\tAggregate  (cost=10848.99..10849.00 rows=1 width=8) (actual time=1438.7..1438.7 rows=1 loops=1)",
            "\t  ->  Seq Scan on transactions t1_0  (cost=0.00..12000.00 rows=100000 width=8)",
            "2026-06-14 01:20:11 UTC:10.0.0.1(1):postgres@postgres:[1]:LOG:  duration: 5.000 ms  statement: SELECT 1",
            "");

        SlowQueryAnalysis analysis = service.parseAndAnalyze(
            new ByteArrayInputStream(log.getBytes(StandardCharsets.UTF_8)),
            "postgres",
            "conn-1"
        );

        var slow = analysis.getTopSlowQueries().stream()
            .filter(q -> q.getQueryText() != null && q.getQueryText().contains("transactions"))
            .findFirst()
            .orElseThrow(() -> new AssertionError("expected the auto_explain query"));

        // queryText keeps the $N placeholders (used for a stable fingerprint).
        assertTrue(slow.getQueryText().contains("$1"), "queryText should keep placeholders: " + slow.getQueryText());

        // sampleQuery has the real bind values substituted in.
        String sample = slow.getSampleQuery();
        assertNotNull(sample);
        assertTrue(sample.contains("entity_id in ('471')"), "literal not substituted: " + sample);
        assertTrue(sample.contains("'CANCELED'") && sample.contains("'FAILED'"), "IN literals missing: " + sample);
        assertTrue(sample.contains("('f'=false") || sample.contains("'f'=false"), "boolean literal missing: " + sample);
        // No placeholders, no parameter line, no plan tree leaked into the sample.
        assertFalse(sample.contains("$1"), "placeholder leaked into sample: " + sample);
        assertFalse(sample.contains("Query Parameters"), "param line leaked: " + sample);
        assertFalse(sample.contains("cost="), "plan tree leaked: " + sample);
    }
}
