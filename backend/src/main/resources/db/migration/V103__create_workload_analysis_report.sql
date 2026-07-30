-- Holistic Workload Analysis reports.
--
-- One row per on-demand "Analyze workload" run. The composed report (index
-- adds, query rewrites, pre-aggregation recs, index-health cleanup, workload
-- summary) lives in the report_data jsonb column; the scalar columns are the
-- queryable metadata the list/poll/latest endpoints use without deserializing
-- the blob.

CREATE TABLE IF NOT EXISTS workload_analysis_report (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id     VARCHAR(255) NOT NULL,
    status            VARCHAR(20)  NOT NULL,          -- PENDING | RUNNING | COMPLETED | FAILED
    progress          INTEGER      NOT NULL DEFAULT 0,
    current_step      VARCHAR(120),
    candidate_count   INTEGER      NOT NULL DEFAULT 0,
    index_rec_count   INTEGER      NOT NULL DEFAULT 0,
    rewrite_rec_count INTEGER      NOT NULL DEFAULT 0,
    pre_agg_rec_count INTEGER      NOT NULL DEFAULT 0,
    started_at        TIMESTAMP,
    completed_at      TIMESTAMP,
    triggered_by      VARCHAR(120),
    message           TEXT,
    report_data       JSONB
);

CREATE INDEX IF NOT EXISTS idx_workload_report_conn_started
    ON workload_analysis_report (connection_id, started_at DESC);

CREATE INDEX IF NOT EXISTS idx_workload_report_conn_status
    ON workload_analysis_report (connection_id, status);
