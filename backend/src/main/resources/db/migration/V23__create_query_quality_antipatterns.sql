-- V23: Create Query Quality Anti-Pattern Detection
-- Implements BRAIN-design.md Section 8: "Query Quality Analysis"

-- Create query_anti_pattern table for storing detected query issues
CREATE TABLE IF NOT EXISTS query_anti_pattern (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    query_fingerprint TEXT NOT NULL,  -- Normalized query pattern
    query_text TEXT,  -- Original query (truncated)

    -- Anti-pattern type
    pattern_type VARCHAR(100) NOT NULL,  -- FUNCTION_ON_FILTER, SELECT_STAR, DISTINCT_ABUSE, etc.
    severity VARCHAR(20) NOT NULL,  -- CRITICAL, HIGH, MEDIUM, LOW

    -- Context
    occurrences INT DEFAULT 1,  -- How many times this pattern occurred
    avg_execution_time_ms BIGINT,  -- Average execution time
    total_execution_time_ms BIGINT,  -- Total time spent on this pattern

    -- Impact metrics
    rows_examined BIGINT,
    rows_returned BIGINT,
    examination_ratio DECIMAL(10, 2),  -- rows_examined / rows_returned

    -- Detection details
    title VARCHAR(255) NOT NULL,
    description TEXT NOT NULL,
    recommendation TEXT NOT NULL,
    example_query TEXT,  -- Sample query exhibiting this pattern

    -- Timestamps
    first_detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_detected_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_query_anti_pattern_connection ON query_anti_pattern(connection_id);
CREATE INDEX IF NOT EXISTS idx_query_anti_pattern_type ON query_anti_pattern(connection_id, pattern_type);
CREATE INDEX IF NOT EXISTS idx_query_anti_pattern_severity ON query_anti_pattern(connection_id, severity);
CREATE INDEX IF NOT EXISTS idx_query_anti_pattern_impact ON query_anti_pattern(connection_id, total_execution_time_ms DESC);

-- Comments
COMMENT ON TABLE query_anti_pattern IS 'Stores detected query quality anti-patterns from slow query analysis';
COMMENT ON COLUMN query_anti_pattern.pattern_type IS 'Type: FUNCTION_ON_FILTER, SELECT_STAR, DISTINCT_ABUSE, JOIN_WITHOUT_PREDICATE, HIGH_EXAMINATION_RATIO, etc.';
COMMENT ON COLUMN query_anti_pattern.examination_ratio IS 'Inefficiency ratio: rows examined divided by rows returned';
