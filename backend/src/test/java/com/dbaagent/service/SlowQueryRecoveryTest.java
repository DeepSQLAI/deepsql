package com.dbaagent.service;

import com.dbaagent.model.QueryLineage;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.QueryLineageRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Recovery of truncated slow-query text from previously-ingested
 * slow-log files stored in vault DB's query_lineage table.
 *
 * Live-stats sources (pg_stat_statements, performance_schema) truncate
 * at a byte boundary configured at the server (default 1024B). When
 * that happens, SlowQuery comes back with `sourceTruncated=true` and
 * EXPLAIN against `queryText` would fail or return a partial plan.
 *
 * If the customer has previously ingested slow log files for that
 * connection, the full SQL is in query_lineage — these tests verify
 * the recovery path that finds it and swaps it in.
 */
@ExtendWith(MockitoExtension.class)
class SlowQueryRecoveryTest {

    @Mock private CredentialService credentialService;
    @Mock private ExplainPlanService explainPlanService;
    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private SlowQueryCollectorService slowQueryCollector;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private QueryLineageRepository queryLineageRepository;

    private SlowQueryService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        service = new SlowQueryService(
            credentialService,
            explainPlanService,
            chatClientBuilder,
            slowQueryCollector,
            providerRegistry,
            queryLineageRepository
        );
    }

    // ─── buildLikePrefixForRecovery (pure function) ──────────────────────────

    private static String buildPrefix(String truncated) throws Exception {
        Method m = SlowQueryService.class.getDeclaredMethod("buildLikePrefixForRecovery", String.class);
        m.setAccessible(true);
        return (String) m.invoke(null, truncated);
    }

    @Test
    void buildLikePrefixForRecovery_takesFirst256CharsAndAppendsWildcard() throws Exception {
        // Input has no LIKE-wildcard chars to escape, so the output is
        // exactly 256 chars from the input + the appended `%`. (An
        // underscore IS a LIKE wildcard so we avoid it here — see the
        // escape-handling test below for that case.)
        String truncated = "SELECT FROM orders WHERE id = 42 AND "
            + "x".repeat(2000);
        String prefix = buildPrefix(truncated);
        assertThat(prefix).hasSize(257);
        assertThat(prefix).endsWith("%");
        assertThat(prefix).startsWith("SELECT FROM orders");
    }

    @Test
    void buildLikePrefixForRecovery_trimsSlackBytesOffTheTail() throws Exception {
        // When the truncated text is shorter than the 256 cap, we trim
        // ~32 chars off the tail to absorb partial-token boundary
        // effects from pg_stat_statements. So a 200-char truncated text
        // produces a 168-char effective prefix (+ the `%` wildcard).
        String truncated = "x".repeat(200);
        String prefix = buildPrefix(truncated);
        assertThat(prefix).hasSize(169);
        assertThat(prefix).endsWith("%");
    }

    @Test
    void buildLikePrefixForRecovery_expansionFromEscapesIsExpected() throws Exception {
        // SQL keywords like `customer_id` contain LIKE wildcards (`_`),
        // which get escaped to `\_` — adding one byte per occurrence.
        // The total prefix can therefore exceed 257 even on a clean
        // 256-char first slice. This is fine for the LIKE; we just
        // assert the SHAPE here.
        String truncated = "SELECT * FROM orders WHERE customer_id = 42 AND "
            + "x".repeat(2000);
        String prefix = buildPrefix(truncated);
        assertThat(prefix).endsWith("%");
        assertThat(prefix).contains("customer\\_id");
        // Underscore appears once in the first 256 chars → +1 escape byte.
        assertThat(prefix).hasSize(258);
    }

    @Test
    void buildLikePrefixForRecovery_escapesLikeWildcardsInTheInput() throws Exception {
        // Real SQL contains `%` and `_` in LIKE patterns and string
        // literals. They MUST be escaped before they hit the repo's
        // LIKE clause, or the recovery query will silently match the
        // wrong rows (or no rows).
        String truncated = "SELECT * FROM users WHERE name LIKE 'a%_b' AND "
            + "stuff " + "y".repeat(200);
        String prefix = buildPrefix(truncated);
        // The `%` and `_` in 'a%_b' must be escaped with a backslash.
        assertThat(prefix).contains("'a\\%\\_b'");
        // The backslashes themselves are doubled so the SQL parser sees
        // them as literal escape chars.
        assertThat(prefix).doesNotContain("'a%_b'");
    }

    @Test
    void buildLikePrefixForRecovery_escapesBackslashesFirstSoTheyArentDoubled() throws Exception {
        // If the input already contains a backslash, we escape it FIRST
        // (`\` → `\\`), THEN escape `%` and `_`. Doing them in the wrong
        // order would re-escape our own escapes.
        String truncated = "SELECT '\\' " + "z".repeat(200);
        String prefix = buildPrefix(truncated);
        // The single backslash becomes a literal `\\` in the LIKE
        // pattern (which means "match one backslash" given ESCAPE '\').
        assertThat(prefix).contains("'\\\\'");
    }

    // ─── recoverTruncatedQueriesFromLineage ──────────────────────────────────

    private SlowQuery truncatedQuery(String text) {
        SlowQuery sq = new SlowQuery();
        sq.setQueryId("q-" + text.hashCode());
        sq.setQueryText(text);
        sq.setNormalizedQuery(text);
        sq.setSourceTruncated(true);
        return sq;
    }

    private QueryLineage lineage(String text, String normalized) {
        QueryLineage row = new QueryLineage();
        row.setConnectionId("conn-1");
        row.setQueryText(text);
        row.setNormalizedQuery(normalized);
        return row;
    }

    @Test
    void recovery_happyPath_replacesTruncatedTextWithFullVersionFromLineage() {
        String truncated = "SELECT * FROM orders WHERE customer_id = 42 AND created_at > '2024-01-01' "
            + "x".repeat(400);  // 480-ish chars, long enough to trigger recovery
        String fullText = truncated + " AND status != 'cancelled' ORDER BY created_at DESC LIMIT 100";

        SlowQuery sq = truncatedQuery(truncated);
        when(queryLineageRepository.findLongestByConnectionIdAndQueryTextPrefix(
                eq("conn-1"), anyString(), anyInt()))
            .thenReturn(lineage(fullText, fullText));

        service.recoverTruncatedQueriesFromLineage("conn-1", List.of(sq));

        assertThat(sq.getQueryText()).isEqualTo(fullText);
        assertThat(sq.getNormalizedQuery()).isEqualTo(fullText);
        assertThat(sq.getSourceTruncated()).isTrue();   // factually still came from truncated source
        assertThat(sq.getQueryTextRecoveredFromLogs()).isTrue();
    }

    @Test
    void recovery_missPath_leavesQueryUnchanged_andFlagStillTrue() {
        SlowQuery sq = truncatedQuery("SELECT * FROM orders " + "x".repeat(200));
        when(queryLineageRepository.findLongestByConnectionIdAndQueryTextPrefix(
                eq("conn-1"), anyString(), anyInt()))
            .thenReturn(null);

        service.recoverTruncatedQueriesFromLineage("conn-1", List.of(sq));

        assertThat(sq.getQueryText()).startsWith("SELECT * FROM orders");
        assertThat(sq.getSourceTruncated()).isTrue();
        assertThat(sq.getQueryTextRecoveredFromLogs()).isNull();
    }

    @Test
    void recovery_skipsNonTruncatedQueries() {
        // Don't waste a repo round-trip on rows that came back full.
        SlowQuery clean = new SlowQuery();
        clean.setQueryText("SELECT 1");
        clean.setSourceTruncated(false);

        service.recoverTruncatedQueriesFromLineage("conn-1", List.of(clean));

        verify(queryLineageRepository, never()).findLongestByConnectionIdAndQueryTextPrefix(
            anyString(), anyString(), anyInt());
    }

    @Test
    void recovery_skipsVeryShortTruncations() {
        // If the truncated text is shorter than RECOVERY_MIN_TRUNCATED_LENGTH
        // (128 chars), a prefix match is too ambiguous to trust. Skip and
        // let the warning fire instead.
        SlowQuery shortish = truncatedQuery("SELECT 1");  // 8 chars

        service.recoverTruncatedQueriesFromLineage("conn-1", List.of(shortish));

        verify(queryLineageRepository, never()).findLongestByConnectionIdAndQueryTextPrefix(
            anyString(), anyString(), anyInt());
    }

    @Test
    void recovery_logsAggregateButDoesntThrowOnRepoFailure() {
        // Repo throwing must not break the whole analysis. We log + move
        // on so the user still gets results, just with the truncation
        // warning intact.
        SlowQuery sq = truncatedQuery("SELECT * FROM orders " + "x".repeat(200));
        when(queryLineageRepository.findLongestByConnectionIdAndQueryTextPrefix(
                anyString(), anyString(), anyInt()))
            .thenThrow(new RuntimeException("DB connection lost"));

        // No throw expected.
        service.recoverTruncatedQueriesFromLineage("conn-1", List.of(sq));

        assertThat(sq.getQueryText()).startsWith("SELECT * FROM orders");
        assertThat(sq.getSourceTruncated()).isTrue();
        assertThat(sq.getQueryTextRecoveredFromLogs()).isNull();
    }

    @Test
    void recovery_passesEscapedPrefixThroughToTheRepo() {
        // Sanity check that the escape work actually reaches the repo
        // call — the repo's LIKE clause uses ESCAPE '\\', so without
        // escaping, a user-controlled `%` in the query would change the
        // semantics of the recovery query.
        String truncated = "SELECT * FROM users WHERE email LIKE '%@example.com' AND "
            + "y".repeat(200);
        SlowQuery sq = truncatedQuery(truncated);
        when(queryLineageRepository.findLongestByConnectionIdAndQueryTextPrefix(
                anyString(), anyString(), anyInt()))
            .thenReturn(null);

        service.recoverTruncatedQueriesFromLineage("conn-1", List.of(sq));

        ArgumentCaptor<String> prefixCaptor = ArgumentCaptor.forClass(String.class);
        verify(queryLineageRepository).findLongestByConnectionIdAndQueryTextPrefix(
            eq("conn-1"), prefixCaptor.capture(), anyInt());
        String prefix = prefixCaptor.getValue();
        // The `%` in the LIKE pattern of the user's SQL must be escaped
        // before reaching the repo so it's matched literally.
        assertThat(prefix).contains("'\\%@example.com'");
        // And the prefix must end with the wildcard we appended.
        assertThat(prefix).endsWith("%");
    }

    @Test
    void recovery_emptyAndNullInputsAreNoOps() {
        service.recoverTruncatedQueriesFromLineage("conn-1", null);
        service.recoverTruncatedQueriesFromLineage("conn-1", new ArrayList<>());
        service.recoverTruncatedQueriesFromLineage(null, List.of(truncatedQuery("SELECT 1 " + "x".repeat(200))));

        verify(queryLineageRepository, never()).findLongestByConnectionIdAndQueryTextPrefix(
            any(), any(), anyInt());
    }
}
