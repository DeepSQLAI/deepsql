-- Query Plan Cache & Comparison

-- Table to store cached query plans
CREATE TABLE IF NOT EXISTS query_plan_cache (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL REFERENCES connection_request(id) ON DELETE CASCADE,
    query_hash VARCHAR(64) NOT NULL, -- SHA-256 hash of normalized query
    query_text TEXT NOT NULL, -- Original query
    normalized_query TEXT, -- Query with literals replaced
    plan_type VARCHAR(20) NOT NULL, -- EXPLAIN, EXPLAIN_ANALYZE
    plan_json JSONB NOT NULL, -- Full EXPLAIN output as JSON
    plan_hash VARCHAR(64) NOT NULL, -- Hash of the plan for quick comparison
    plan_summary TEXT, -- Human-readable summary

    -- Plan metrics
    estimated_cost DOUBLE PRECISION,
    estimated_rows BIGINT,
    actual_time_ms DOUBLE PRECISION, -- Only for EXPLAIN ANALYZE
    actual_rows BIGINT, -- Only for EXPLAIN ANALYZE

    -- Plan characteristics
    uses_seq_scan BOOLEAN DEFAULT FALSE,
    uses_index_scan BOOLEAN DEFAULT FALSE,
    uses_bitmap_scan BOOLEAN DEFAULT FALSE,
    uses_nested_loop BOOLEAN DEFAULT FALSE,
    uses_hash_join BOOLEAN DEFAULT FALSE,
    uses_merge_join BOOLEAN DEFAULT FALSE,
    uses_sort BOOLEAN DEFAULT FALSE,
    uses_materialize BOOLEAN DEFAULT FALSE,

    -- Tables and indexes used
    tables_involved TEXT[], -- List of tables in the plan
    indexes_used TEXT[], -- List of indexes used
    missing_indexes TEXT[], -- Suggested missing indexes

    -- Warnings and recommendations
    warnings TEXT[],
    recommendations TEXT[],

    -- Metadata
    is_baseline BOOLEAN DEFAULT FALSE, -- Reference plan for comparison
    captured_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(100)
);

-- Index for fast lookup by query hash
CREATE INDEX IF NOT EXISTS idx_query_plan_cache_hash
    ON query_plan_cache(connection_id, query_hash, captured_at DESC);

-- Index for finding baselines
CREATE INDEX IF NOT EXISTS idx_query_plan_cache_baseline
    ON query_plan_cache(connection_id, query_hash, is_baseline)
    WHERE is_baseline = TRUE;

-- Index for recent plans
CREATE INDEX IF NOT EXISTS idx_query_plan_cache_recent
    ON query_plan_cache(connection_id, captured_at DESC);

-- Table to store plan comparison results
CREATE TABLE IF NOT EXISTS query_plan_comparison (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL REFERENCES connection_request(id) ON DELETE CASCADE,
    query_hash VARCHAR(64) NOT NULL,
    baseline_plan_id VARCHAR(36) REFERENCES query_plan_cache(id) ON DELETE SET NULL,
    current_plan_id VARCHAR(36) REFERENCES query_plan_cache(id) ON DELETE SET NULL,

    -- Comparison results
    plan_changed BOOLEAN NOT NULL DEFAULT FALSE,
    cost_changed BOOLEAN NOT NULL DEFAULT FALSE,
    cost_change_percent DOUBLE PRECISION, -- Positive = regression, Negative = improvement
    estimated_rows_change_percent DOUBLE PRECISION,

    -- Plan structure changes
    added_operations TEXT[],
    removed_operations TEXT[],
    new_seq_scans TEXT[], -- Tables now using seq scan
    lost_index_scans TEXT[], -- Tables no longer using index

    -- Severity and status
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO', -- INFO, WARNING, CRITICAL
    is_regression BOOLEAN DEFAULT FALSE,
    is_acknowledged BOOLEAN DEFAULT FALSE,
    acknowledged_by VARCHAR(100),
    acknowledged_at TIMESTAMP,

    -- Analysis
    analysis_notes TEXT,
    suggested_actions TEXT[],

    compared_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for finding regressions
CREATE INDEX IF NOT EXISTS idx_query_plan_comparison_regression
    ON query_plan_comparison(connection_id, is_regression, compared_at DESC)
    WHERE is_regression = TRUE;

-- Index for unacknowledged comparisons
CREATE INDEX IF NOT EXISTS idx_query_plan_comparison_unack
    ON query_plan_comparison(connection_id, is_acknowledged)
    WHERE is_acknowledged = FALSE;

-- Comments
COMMENT ON TABLE query_plan_cache IS 'Stores EXPLAIN plans for queries to enable comparison over time';
COMMENT ON TABLE query_plan_comparison IS 'Stores results of plan comparisons to detect regressions';
COMMENT ON COLUMN query_plan_cache.query_hash IS 'SHA-256 hash of normalized query for grouping plans of the same query';
COMMENT ON COLUMN query_plan_cache.plan_hash IS 'SHA-256 hash of plan structure for quick change detection';
COMMENT ON COLUMN query_plan_comparison.cost_change_percent IS 'Positive values indicate regression (higher cost), negative indicate improvement';
