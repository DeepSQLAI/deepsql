package com.dbaagent.repository;

import com.dbaagent.model.QueryLineage;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.domain.Pageable;
import org.springframework.data.repository.query.Param;
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
     * <p>The regex chain runs once per candidate row, so this is intended for
     * single-row lookups (LIMIT 1) over the per-connection slice. For
     * d840f866-style connections (~5K lineage rows) it returns in a few ms;
     * larger connections may benefit from a functional index on the
     * normalized expression, but none is needed yet.
     */
    @Query(value = """
        SELECT * FROM query_lineage
        WHERE connection_id = :connectionId
          AND LENGTH(query_text) > :minLength
          AND LOWER(
                regexp_replace(
                  regexp_replace(
                    REPLACE(query_text, '`', ''),
                    '\\s*([.,();])\\s*', '\\1', 'g'),
                  '\\s+', ' ', 'g')
              ) LIKE :escapedPrefix ESCAPE '\\'
        ORDER BY LENGTH(query_text) DESC
        LIMIT 1
        """, nativeQuery = true)
    QueryLineage findLongestByConnectionIdAndNormalizedQueryTextPrefix(
        @Param("connectionId") String connectionId,
        @Param("escapedPrefix") String escapedPrefix,
        @Param("minLength") int minLength
    );
}
