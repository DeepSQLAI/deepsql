package com.dbaagent.repository;

import com.dbaagent.model.QueryLineage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;

@Repository
public interface QueryLineageRepository extends JpaRepository<QueryLineage, String> {
    @Query("SELECT q FROM QueryLineage q WHERE q.connectionId = :connectionId " +
           "AND q.createdAt >= :since ORDER BY q.createdAt DESC")
    List<QueryLineage> findByConnectionIdSince(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since
    );

    @Query("SELECT q FROM QueryLineage q WHERE q.connectionId = :connectionId " +
           "AND q.createdAt >= :since ORDER BY q.createdAt DESC")
    List<QueryLineage> findByConnectionIdSince(
        @Param("connectionId") String connectionId,
        @Param("since") LocalDateTime since,
        Pageable pageable
    );

    List<QueryLineage> findByConnectionIdOrderByCreatedAtDesc(String connectionId);

    /**
     * Count total query lineage records for a connection.
     */
    long countByConnectionId(String connectionId);

    /**
     * Count query lineage records by source pattern (case-insensitive).
     */
    @Query("SELECT COUNT(q) FROM QueryLineage q WHERE q.connectionId = :connectionId " +
           "AND UPPER(q.source) LIKE :sourcePattern")
    long countByConnectionIdAndSourceLike(
        @Param("connectionId") String connectionId,
        @Param("sourcePattern") String sourcePattern
    );

    /**
     * Count query lineage records for a specific analysis run.
     */
    long countByConnectionIdAndAnalysisId(String connectionId, String analysisId);

    /**
     * Check if a lineage entry already exists for an analysis run.
     */
    boolean existsByConnectionIdAndAnalysisIdAndQueryHash(
        String connectionId,
        String analysisId,
        String queryHash
    );

    @Query("""
        SELECT MAX(q.createdAt)
        FROM QueryLineage q
        WHERE q.connectionId = :connectionId
        """)
    LocalDateTime findLatestCreatedAt(@Param("connectionId") String connectionId);

    /**
     * Find a stored lineage row whose `query_text` starts with the supplied
     * (already-escaped) prefix and is strictly longer than `minLength`.
     * Ordered longest-first; callers typically want the single longest
     * match.
     *
     * This is the "recover the full SQL from previously-ingested logs"
     * path: when pg_stat_statements or performance_schema gives us a
     * truncated query, we look in vault DB for a slow-log-file row with
     * the same connection + same prefix and use that full text instead.
     * pg_stat_statements truncates at a byte boundary, so the stored
     * truncated text is always a prefix of the original — `LIKE prefix%`
     * is the correct match shape.
     *
     * Caller must escape `%`, `_`, and `\` in the prefix before calling
     * (the query uses `ESCAPE '\\'`). Native query rather than JPQL
     * because LENGTH() / LIMIT semantics are more portable at SQL level
     * across Postgres (vault DB) and H2 (test profile).
     */
    @Query(value = """
        SELECT * FROM query_lineage
        WHERE connection_id = :connectionId
          AND LENGTH(query_text) > :minLength
          AND query_text LIKE :escapedPrefix ESCAPE '\\'
        ORDER BY LENGTH(query_text) DESC
        LIMIT 1
        """, nativeQuery = true)
    QueryLineage findLongestByConnectionIdAndQueryTextPrefix(
        @Param("connectionId") String connectionId,
        @Param("escapedPrefix") String escapedPrefix,
        @Param("minLength") int minLength
    );

    /**
     * Same shape as {@link #findLongestByConnectionIdAndQueryTextPrefix} but
     * compares with whitespace collapsed on the {@code query_text} side
     * (multiple spaces / newlines / tabs treated as a single space).
     *
     * Kept for any caller that already passes a whitespace-collapsed prefix;
     * for new code prefer {@link #findLongestByConnectionIdAndNormalizedQueryTextPrefix}
     * which also strips backticks and collapses spacing around punctuation
     * (handles the MySQL Performance Schema digest vs raw slow-log format gap).
     */
    @Query(value = """
        SELECT * FROM query_lineage
        WHERE connection_id = :connectionId
          AND LENGTH(query_text) > :minLength
          AND regexp_replace(query_text, '\\s+', ' ', 'g') LIKE :escapedPrefix ESCAPE '\\'
        ORDER BY LENGTH(query_text) DESC
        LIMIT 1
        """, nativeQuery = true)
    QueryLineage findLongestByConnectionIdAndCollapsedQueryTextPrefix(
        @Param("connectionId") String connectionId,
        @Param("escapedPrefix") String escapedPrefix,
        @Param("minLength") int minLength
    );

    /**
     * Cross-format prefix match: aggressively normalize the {@code query_text}
     * before LIKE-comparing, so a Performance Schema digest can match a raw
     * slow-log-file entry of the same query.
     *
     * <p>MySQL stores the same query in three different shapes depending on
     * the source:
     * <ul>
     *   <li>PERF_SCHEMA DIGEST_TEXT: {@code SELECT `b` . `id` , `b` . `name`} —
     *       backticks around every identifier, spaces around dots and commas.
     *   <li>Slow-query log file: {@code SELECT b.id, b.name} — no backticks,
     *       tight punctuation.
     *   <li>User-typed in chat (SQL_RUNNER): anywhere on that spectrum, plus
     *       multi-line formatting with arbitrary indentation.
     * </ul>
     * A plain prefix match fails after the very first token because the
     * literal bytes diverge. The whitespace-only collapse fixes the multi-
     * line case but not the backtick / digest-punctuation case.
     *
     * <p>This query normalizes the stored {@code query_text} by stripping
     * backticks, collapsing whitespace around {@code . , ; ( )}, and
     * collapsing remaining whitespace runs to a single space, all lowercased.
     * Callers must apply the SAME transformation to their prefix on the
     * Java side (see {@code SlowQueryAnalyticsService.normalizeForMatching}).
     *
     * <p>The normalization is <b>precomputed</b> into the stored generated column
     * {@code normalized_match} ({@code QueryLineageMatchIndexInitializer}) and matched
     * against that, so the regex chain runs once at write time rather than once per
     * candidate row on every read. Inlining the expression here made the predicate
     * unindexable: Postgres had to materialize a rewritten copy of the whole
     * per-connection slice, which measured 36 ms at 1,093 rows and 1,112 ms at 34,976 —
     * and {@code recoverFullText} issues this up to 20 times per "view full query" click,
     * against a table no retention job prunes. On the same scaled table with the ~120-char
     * prefix the caller actually sends, the indexed form plans as an Index Scan at
     * 0.428 ms.
     *
     * <p>Matched against the column <b>directly</b>, with no {@code COALESCE} fallback to
     * the inline expression. That fallback is the obvious way to stay safe on a database
     * without the column, and it silently undoes the whole fix: wrapping the column in
     * {@code COALESCE(...)} makes the predicate non-indexable again. Measured on the same
     * 37,504-row table — {@code COALESCE(normalized_match, …)} plans a Seq Scan at
     * 48.7 ms, the bare column an Index Scan at 0.39 ms. If the column is ever absent,
     * {@code QueryLineageMatchIndexInitializer} logs it at WARN and
     * {@code SlowQueryAnalyticsService.recoverFullText} already treats a failed lookup as
     * "no longer text available" and returns the sample unchanged.
     */
    @Query(value = """
        SELECT * FROM query_lineage
        WHERE connection_id = :connectionId
          AND LENGTH(query_text) > :minLength
          AND normalized_match LIKE :escapedPrefix ESCAPE '\\'
        ORDER BY LENGTH(query_text) DESC
        LIMIT 1
        """, nativeQuery = true)
    QueryLineage findLongestByConnectionIdAndNormalizedQueryTextPrefix(
        @Param("connectionId") String connectionId,
        @Param("escapedPrefix") String escapedPrefix,
        @Param("minLength") int minLength
    );

    /**
     * Drop lineage rows older than the connection's retention window.
     *
     * <p>This table was never pruned: {@code SlowQueryRetentionService} covered
     * {@code slow_query_run}, {@code slow_query_customer_day} and
     * {@code slow_query_sample} but not lineage, so it grew without bound while the
     * 30-day analytics tables stayed small. That is what made sample recovery degrade
     * with age rather than load — the scanned table kept growing even on an idle install.
     *
     * <p>Keyed on {@code created_at}, which is non-null and already indexed
     * ({@code idx_query_lineage_created}).
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM QueryLineage q WHERE q.connectionId = :connectionId "
        + "AND q.createdAt < :cutoff")
    int deleteByConnectionIdAndCreatedAtBefore(
        @Param("connectionId") String connectionId,
        @Param("cutoff") java.time.LocalDateTime cutoff);
}
