-- Query Optimization Candidate Benchmark table
-- Stores candidate rewrites (from AI/optd) and optional benchmark results

CREATE TABLE IF NOT EXISTS query_optimization_candidate_run (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    query_fingerprint VARCHAR(255) NOT NULL,
    candidate_id VARCHAR(255) NOT NULL,
    candidate_sql TEXT,
    plan_signature VARCHAR(255),
    plan_text TEXT,
    estimated_cost DOUBLE PRECISION,
    estimated_rows DOUBLE PRECISION,
    benchmark_ms DOUBLE PRECISION,
    median_ms DOUBLE PRECISION,
    run_count INTEGER DEFAULT 0,
    status VARCHAR(20) DEFAULT 'PENDING',
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uq_opt_candidate UNIQUE (connection_id, query_fingerprint, candidate_id)
);

CREATE INDEX IF NOT EXISTS idx_qocr_conn_fingerprint
    ON query_optimization_candidate_run(connection_id, query_fingerprint);

CREATE INDEX IF NOT EXISTS idx_qocr_candidate_status
    ON query_optimization_candidate_run(connection_id, status);

COMMENT ON TABLE query_optimization_candidate_run IS 'Stores candidate query rewrites and benchmark results.';
COMMENT ON COLUMN query_optimization_candidate_run.candidate_id IS 'Plan signature or candidate identifier.';
COMMENT ON COLUMN query_optimization_candidate_run.plan_signature IS 'Optimizer plan signature for the candidate.';
COMMENT ON COLUMN query_optimization_candidate_run.benchmark_ms IS 'Single-run benchmark timing in ms.';
COMMENT ON COLUMN query_optimization_candidate_run.median_ms IS 'Median benchmark timing in ms (multi-run).';
