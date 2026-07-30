-- Brain 2.0: Query Intelligence Engine (optd-inspired)
-- Cardinality estimation, adaptive plan scoring, and plan pattern library

-- Column Statistics: Independent cardinality estimation data
CREATE TABLE column_statistics (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,

    -- Basic statistics
    data_type VARCHAR(100),
    row_count BIGINT,
    distinct_count BIGINT,
    null_count BIGINT,
    null_fraction DOUBLE PRECISION,

    -- TDigest for distribution (serialized, base64 encoded)
    tdigest_data TEXT,

    -- HyperLogLog for precise distinct count (serialized)
    hll_data TEXT,

    -- Most Common Values (MCV)
    mcv_values JSONB, -- Array of common values
    mcv_frequencies JSONB, -- Array of frequencies (0-1)

    -- Range statistics for numeric columns
    min_value TEXT,
    max_value TEXT,
    avg_value DOUBLE PRECISION,
    std_deviation DOUBLE PRECISION,

    -- Histogram bounds for complex distributions
    histogram_bounds JSONB, -- Array of bucket boundaries
    histogram_counts JSONB, -- Array of counts per bucket

    -- String statistics
    avg_length DOUBLE PRECISION,

    -- Correlation with other columns
    correlation_data JSONB, -- {column_name: correlation_coefficient}

    -- Collection metadata
    sample_size INTEGER,
    collected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    collection_method VARCHAR(50) DEFAULT 'SAMPLE', -- SAMPLE, FULL_SCAN

    CONSTRAINT fk_col_stats_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_col_stats_conn_table ON column_statistics(connection_id, table_name);
CREATE UNIQUE INDEX idx_col_stats_unique ON column_statistics(connection_id, table_name, column_name);

-- Plan Execution: Tracks actual execution vs estimated for learning
CREATE TABLE plan_execution (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Query identification
    query_fingerprint VARCHAR(64) NOT NULL, -- Normalized query hash
    query_text TEXT,
    normalized_query TEXT,

    -- Plan identification
    plan_signature VARCHAR(64) NOT NULL, -- Hash of plan structure
    plan_json JSONB,

    -- Estimated values (from EXPLAIN)
    estimated_cost DOUBLE PRECISION,
    estimated_rows BIGINT,
    estimated_startup_cost DOUBLE PRECISION,

    -- Actual values (from EXPLAIN ANALYZE or monitoring)
    actual_execution_ms DOUBLE PRECISION,
    actual_rows BIGINT,
    actual_loops INTEGER,

    -- Buffer statistics
    shared_hit_blocks BIGINT,
    shared_read_blocks BIGINT,
    temp_read_blocks BIGINT,
    temp_written_blocks BIGINT,

    -- Cardinality estimation error
    cardinality_error_ratio DOUBLE PRECISION, -- actual/estimated

    -- Execution context
    executed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    execution_source VARCHAR(50), -- EXPLAIN_ANALYZE, SLOW_LOG, MONITORING

    CONSTRAINT fk_plan_exec_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_plan_exec_conn_fingerprint ON plan_execution(connection_id, query_fingerprint);
CREATE INDEX idx_plan_exec_signature ON plan_execution(plan_signature);
CREATE INDEX idx_plan_exec_time ON plan_execution(executed_at DESC);

-- Plan Pattern: Library of query plan patterns with optimization suggestions
CREATE TABLE plan_pattern (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255),  -- NULL for global patterns

    -- Pattern identification
    query_pattern TEXT NOT NULL, -- Normalized query pattern
    plan_signature VARCHAR(64) NOT NULL,

    -- Pattern characteristics
    pattern_type VARCHAR(50), -- SEQUENTIAL_SCAN, NESTED_LOOP, HASH_JOIN, etc.
    tables_involved JSONB,
    join_types JSONB,

    -- Optimization suggestions (cached)
    suggestions JSONB NOT NULL, -- Array of OptimizationSuggestion
    optimized_query TEXT,

    -- Effectiveness tracking
    effectiveness_score DOUBLE PRECISION, -- 0-100, based on feedback
    usage_count INTEGER DEFAULT 1,
    success_count INTEGER DEFAULT 0,

    -- AI-generated explanation
    explanation TEXT,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,
    last_validated_at TIMESTAMP
);

CREATE INDEX idx_plan_pattern_conn ON plan_pattern(connection_id);
CREATE INDEX idx_plan_pattern_signature ON plan_pattern(plan_signature);
CREATE INDEX idx_plan_pattern_effectiveness ON plan_pattern(effectiveness_score DESC);

-- Cardinality Estimation Comparison: Tracks our estimates vs database estimates
CREATE TABLE cardinality_comparison (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Query context
    query_fingerprint VARCHAR(64) NOT NULL,
    table_name VARCHAR(255),
    predicate_text TEXT,
    predicate_type VARCHAR(50), -- EQUALITY, RANGE, IN, LIKE, BETWEEN

    -- Estimates
    our_estimate BIGINT NOT NULL,
    db_estimate BIGINT NOT NULL,
    actual_rows BIGINT,

    -- Error analysis
    our_error_ratio DOUBLE PRECISION, -- our_estimate / actual
    db_error_ratio DOUBLE PRECISION, -- db_estimate / actual
    better_estimate VARCHAR(10), -- OURS, DB, TIE

    -- Timestamps
    compared_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_card_comp_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_card_comp_conn ON cardinality_comparison(connection_id, compared_at DESC);
CREATE INDEX idx_card_comp_table ON cardinality_comparison(table_name);

-- Transformation Rule Effectiveness: Tracks which query transformations work
CREATE TABLE transformation_rule_effectiveness (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255),  -- NULL for global stats

    -- Rule identification
    rule_name VARCHAR(100) NOT NULL, -- PREDICATE_PUSHDOWN, JOIN_REORDER, etc.
    rule_version INTEGER DEFAULT 1,

    -- Effectiveness metrics
    times_suggested INTEGER DEFAULT 0,
    times_applied INTEGER DEFAULT 0,
    times_improved INTEGER DEFAULT 0,
    avg_improvement_percent DOUBLE PRECISION,

    -- Detailed tracking
    improvement_distribution JSONB, -- Histogram of improvement percentages

    -- Timestamps
    first_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_used_at TIMESTAMP,

    CONSTRAINT unique_rule_conn UNIQUE(connection_id, rule_name)
);

CREATE INDEX idx_trans_rule_name ON transformation_rule_effectiveness(rule_name);
