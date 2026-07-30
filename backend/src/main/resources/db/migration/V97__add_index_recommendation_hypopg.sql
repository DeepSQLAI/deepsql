-- HypoPG validation columns: store the planner-reported cost before and
-- after a hypothetical version of the recommended index existed.
--
-- Approach (the canonical Dexter / pganalyze workflow):
--   1. Run `EXPLAIN (FORMAT JSON) <contributing_query>` → Plan."Total Cost" → before
--   2. `SELECT hypopg_create_index('CREATE INDEX ... ')` (Postgres-only extension)
--   3. EXPLAIN again → Plan."Total Cost" → after
--   4. `SELECT hypopg_reset()` to clean up
--   5. reductionPct = (before − after) / before × 100
--
-- Postgres-only. MySQL impl returns Optional.empty(); columns stay NULL.
-- HypoPG missing on a Postgres connection also yields NULL — graceful no-op.

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS hypopg_before_cost   DOUBLE PRECISION;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS hypopg_after_cost    DOUBLE PRECISION;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS hypopg_reduction_pct DOUBLE PRECISION;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS hypopg_evaluated_at  TIMESTAMP;
