-- Make slow-query sample recovery indexable.
--
-- QueryLineageRepository.findLongestByConnectionIdAndNormalizedQueryTextPrefix used to
-- wrap query_text in three nested regexp_replace/REPLACE calls plus LOWER before
-- comparing, so no index could satisfy the predicate. Postgres materialized a rewritten
-- copy of every row in the connection's slice, and SlowQueryAnalyticsService
-- .recoverFullText issues that up to 20 times per "view full query" click.
--
-- Measured with EXPLAIN (ANALYZE) on a real install:
--     1,093 rows  ->    36 ms per call   (~0.7 s per modal open)
--    34,976 rows  ->  1112 ms per call   (~22 s per modal open)
-- The growth is linear, and query_lineage was not pruned by SlowQueryRetentionService,
-- so this degraded with the install's age rather than its load — which is why it passed
-- every pre-launch test.
--
-- Precomputing the normalization into a STORED generated column pays the regex chain once
-- at write time. On the same 34,976-row table, with the ~120-character prefix the caller
-- actually sends: Index Scan, 0.428 ms.
--
-- NOTE: this repo has no Flyway runtime (see CLAUDE.md). QueryLineageMatchIndexInitializer
-- is what actually applies these statements at startup; this file is the changelog record.

ALTER TABLE query_lineage
    ADD COLUMN IF NOT EXISTS normalized_match text
    GENERATED ALWAYS AS (
        lower(regexp_replace(regexp_replace(
            replace(query_text, '`', ''),
            '\s*([.,();])\s*', '\1', 'g'),
            '\s+', ' ', 'g'))
    ) STORED;

-- text_pattern_ops so LIKE 'prefix%' can use the index under any collation; the default
-- opclass only helps in the C collation.
CREATE INDEX IF NOT EXISTS idx_query_lineage_norm_match
    ON query_lineage (connection_id, normalized_match text_pattern_ops);
