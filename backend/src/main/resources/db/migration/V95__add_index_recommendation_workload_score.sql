-- Workload-weighted scoring for index recommendations.
--
-- Before this migration, recommendation ranking used flat additive constants
-- (+30 table scan, +20 join, +25 if affectedQueries > 3, +15 otherwise) that
-- ignored how often each pattern actually ran. A query firing 1000×/sec
-- scored identically to one firing 4×/day. Top-N output was mis-ranked.
--
-- After this migration each row carries:
--   workload_score_ms  = Σ (calls × mean_exec_time_ms) across slow queries
--                        that touched the candidate columns in the matching
--                        role over the lookback window. The pganalyze /
--                        Microsoft-DTA "total time" metric.
--   write_cost_score   = Approximate per-write overhead based on
--                        pg_stat_user_tables.n_tup_ins + n_tup_upd + n_tup_del.
--                        A new index on a write-heavy table needs to clear
--                        a much higher read-savings bar.
--   evidence_count     = How many contributing queries fed the score
--                        (separate from the per-cycle recurrence counter).
--
-- Ranking changes to ORDER BY priority, (workload_score_ms - write_cost_score)
-- DESC, occurrence_count DESC, last_seen_at DESC — net-benefit-first.

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS workload_score_ms BIGINT NOT NULL DEFAULT 0;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS write_cost_score BIGINT NOT NULL DEFAULT 0;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS evidence_count INTEGER NOT NULL DEFAULT 0;

-- Net-benefit-ordered index for the top-N ranking query.
CREATE INDEX IF NOT EXISTS idx_rec_net_benefit
    ON index_recommendations (connection_id, status, priority, workload_score_ms DESC);
