-- Performance Action table for unified performance recommendations
-- Aggregates recommendations from multiple sources with ROI-based ranking

CREATE TABLE performance_action (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,

    -- Classification
    category VARCHAR(50) NOT NULL,  -- INDEX, QUERY_REWRITE, CONFIG, SCHEMA, MAINTENANCE
    source VARCHAR(50) NOT NULL,    -- INDEX_ADVISOR, SLOW_QUERY_ANALYSIS, BRAIN_CONFIG_TUNING, etc.
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING',  -- PENDING, IN_PROGRESS, COMPLETED, DISMISSED

    -- Action details
    title VARCHAR(255) NOT NULL,
    description TEXT,
    target_object VARCHAR(255),      -- Table name, query fingerprint, config param
    target_secondary VARCHAR(255),   -- Column name, etc.

    -- Impact/Effort/ROI scores
    impact_score INTEGER NOT NULL,   -- 0-100
    effort_score INTEGER NOT NULL,   -- 0-100
    roi DOUBLE PRECISION NOT NULL,   -- Pre-calculated: (impact/effort)*100

    -- Action content
    sql_statement TEXT,              -- CREATE INDEX, etc.
    optimized_query TEXT,            -- Rewritten query
    config_value VARCHAR(255),       -- New config value

    -- Additional context
    metadata JSONB,                  -- Flexible metadata
    source_reference_id VARCHAR(255), -- Link to source record
    has_cached_analysis BOOLEAN DEFAULT FALSE,
    queries_affected BIGINT,         -- Queries/day affected
    time_savings_ms BIGINT,          -- Estimated ms saved per query

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    resolved_at TIMESTAMP,
    resolved_by VARCHAR(255),
    resolution_notes TEXT,

    -- Constraints
    CONSTRAINT chk_impact_score CHECK (impact_score >= 0 AND impact_score <= 100),
    CONSTRAINT chk_effort_score CHECK (effort_score >= 0 AND effort_score <= 100),
    CONSTRAINT chk_category CHECK (category IN ('INDEX', 'QUERY_REWRITE', 'CONFIG', 'SCHEMA', 'MAINTENANCE')),
    CONSTRAINT chk_status CHECK (status IN ('PENDING', 'IN_PROGRESS', 'COMPLETED', 'DISMISSED'))
);

-- Indexes for efficient queries
CREATE INDEX idx_pa_connection_id ON performance_action(connection_id);
CREATE INDEX idx_pa_connection_status ON performance_action(connection_id, status);
CREATE INDEX idx_pa_connection_category ON performance_action(connection_id, category);
CREATE INDEX idx_pa_roi ON performance_action(roi DESC);
CREATE INDEX idx_pa_created_at ON performance_action(created_at);
CREATE INDEX idx_pa_source_ref ON performance_action(connection_id, source_reference_id);

-- Partial index for pending actions (most common query)
CREATE INDEX idx_pa_pending_roi ON performance_action(connection_id, roi DESC)
    WHERE status = 'PENDING';

-- Comment
COMMENT ON TABLE performance_action IS 'Unified performance recommendations from multiple sources with ROI ranking';
