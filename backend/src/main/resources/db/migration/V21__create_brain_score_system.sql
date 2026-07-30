-- V21: Create Brain Score System
-- Implements BRAIN-design.md Section 11: "Scoring Model"

-- Create brain_score table for overall database health scores
CREATE TABLE IF NOT EXISTS brain_score (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Overall Brain Score (0-100)
    overall_score DECIMAL(5, 2) NOT NULL,

    -- Sub-scores (0-100 each)
    schema_design_score DECIMAL(5, 2),
    query_quality_score DECIMAL(5, 2),
    index_access_score DECIMAL(5, 2),
    scalability_score DECIMAL(5, 2),

    -- Metadata
    calculated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    tables_analyzed INT,
    columns_analyzed INT,
    queries_analyzed INT,

    -- Explainability JSON
    score_breakdown JSONB,  -- Detailed breakdown of how scores were calculated

    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_brain_score_connection ON brain_score(connection_id);
CREATE INDEX IF NOT EXISTS idx_brain_score_date ON brain_score(connection_id, calculated_at DESC);
CREATE INDEX IF NOT EXISTS idx_brain_score_overall ON brain_score(connection_id, overall_score DESC);

-- Comments
COMMENT ON TABLE brain_score IS 'Stores Brain health scores over time for trend analysis';
COMMENT ON COLUMN brain_score.overall_score IS 'Weighted aggregate of all sub-scores (0-100)';
COMMENT ON COLUMN brain_score.schema_design_score IS 'Score for schema structure soundness (0-100)';
COMMENT ON COLUMN brain_score.query_quality_score IS 'Score for query efficiency and correctness (0-100)';
COMMENT ON COLUMN brain_score.index_access_score IS 'Score for index coverage and workload alignment (0-100)';
COMMENT ON COLUMN brain_score.scalability_score IS 'Score for future growth resilience (0-100)';
COMMENT ON COLUMN brain_score.score_breakdown IS 'JSON explaining score calculation for transparency';
