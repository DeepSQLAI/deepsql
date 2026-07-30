-- V30: Create query fingerprints table for tracking normalized query patterns
-- Part of slow query improvements: fingerprint tracking with trends

CREATE TABLE IF NOT EXISTS query_fingerprints (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    fingerprint VARCHAR(64) NOT NULL,
    normalized_query TEXT,
    sample_query TEXT,
    affected_tables JSON,
    query_type VARCHAR(20),

    -- Current metrics
    current_avg_time_ms DOUBLE PRECISION,
    current_max_time_ms DOUBLE PRECISION,
    current_call_count BIGINT,
    current_rows_examined BIGINT,
    current_rows_sent BIGINT,

    -- Baseline metrics
    baseline_avg_time_ms DOUBLE PRECISION,
    baseline_max_time_ms DOUBLE PRECISION,
    baseline_call_count BIGINT,
    baseline_date TIMESTAMP,

    -- Historical data
    performance_history JSON,

    -- Trend analysis
    trend_direction VARCHAR(20),
    trend_percentage DOUBLE PRECISION,
    is_regressing BOOLEAN DEFAULT FALSE,
    regression_detected_at TIMESTAMP,

    -- Metadata
    first_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    observation_count INTEGER DEFAULT 1,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for quick lookups by connection and fingerprint
CREATE INDEX IF NOT EXISTS idx_qf_connection_fingerprint ON query_fingerprints(connection_id, fingerprint);

-- Index for finding recently updated fingerprints
CREATE INDEX IF NOT EXISTS idx_qf_connection_updated ON query_fingerprints(connection_id, last_seen_at);

-- Index for finding regressing queries
CREATE INDEX IF NOT EXISTS idx_qf_regressing ON query_fingerprints(connection_id, is_regressing) WHERE is_regressing = TRUE;

-- Index for trend analysis
CREATE INDEX IF NOT EXISTS idx_qf_trend ON query_fingerprints(connection_id, trend_direction);

-- Unique constraint on connection_id + fingerprint
CREATE UNIQUE INDEX IF NOT EXISTS idx_qf_unique_fingerprint ON query_fingerprints(connection_id, fingerprint);

COMMENT ON TABLE query_fingerprints IS 'Tracks normalized query patterns (fingerprints) over time for trend analysis and regression detection';
COMMENT ON COLUMN query_fingerprints.fingerprint IS 'SHA-256 hash of the normalized query';
COMMENT ON COLUMN query_fingerprints.normalized_query IS 'Query with literals replaced by placeholders';
COMMENT ON COLUMN query_fingerprints.performance_history IS 'JSON array of historical performance data points';
COMMENT ON COLUMN query_fingerprints.trend_direction IS 'IMPROVING, STABLE, DEGRADING, or CRITICAL';
