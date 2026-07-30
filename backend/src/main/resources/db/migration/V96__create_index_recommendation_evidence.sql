-- Per-recommendation evidence rows.
--
-- The recurrence accumulator tells you "this candidate has resurfaced 7 times
-- across refresh cycles," but it doesn't tell you WHICH queries are paying
-- for the missing index. The /top API and the get_index_recommendations MCP
-- tool need a "here's the time saved" payload — actual query fingerprints,
-- call counts, and execution times — so an AI client or human can audit the
-- recommendation rather than trust it blindly.
--
-- Each refresh cycle upserts the top-K (default 5) most expensive contributing
-- queries per (recommendation, fingerprint). Cascade-delete with the parent
-- so dropping a recommendation cleans its evidence trail automatically.

CREATE TABLE IF NOT EXISTS index_recommendation_evidence (
    id                  VARCHAR(36)       PRIMARY KEY,
    recommendation_id   VARCHAR(36)       NOT NULL REFERENCES index_recommendations(id) ON DELETE CASCADE,
    query_fingerprint   VARCHAR(64)       NOT NULL,
    example_sql         TEXT,
    calls               BIGINT            NOT NULL DEFAULT 0,
    mean_exec_time_ms   DOUBLE PRECISION  NOT NULL DEFAULT 0,
    total_exec_time_ms  DOUBLE PRECISION  NOT NULL DEFAULT 0,
    rows_examined       BIGINT,
    role                VARCHAR(32)       NOT NULL,
    observed_at         TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uk_rec_evidence UNIQUE (recommendation_id, query_fingerprint)
);

CREATE INDEX IF NOT EXISTS idx_rec_evidence_parent
    ON index_recommendation_evidence (recommendation_id, total_exec_time_ms DESC);
