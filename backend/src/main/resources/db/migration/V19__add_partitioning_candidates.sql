-- V19: Add Partitioning Candidates Detection
-- Implements BRAIN-design.md Section 7: "Partitioning candidates"

-- Add partitioning candidate fields to key_column_analysis
ALTER TABLE key_column_analysis
ADD COLUMN IF NOT EXISTS is_partition_candidate BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS partitioning_type VARCHAR(50),  -- RANGE, LIST, HASH
ADD COLUMN IF NOT EXISTS partitioning_score DECIMAL(5, 2),  -- 0-100 score
ADD COLUMN IF NOT EXISTS partitioning_recommendation TEXT;

-- Create index for filtering partition candidates
CREATE INDEX IF NOT EXISTS idx_key_col_partition_candidate
ON key_column_analysis(connection_id, is_partition_candidate)
WHERE is_partition_candidate = true;

-- Comments
COMMENT ON COLUMN key_column_analysis.is_partition_candidate IS 'True if column is a good candidate for partitioning';
COMMENT ON COLUMN key_column_analysis.partitioning_type IS 'Recommended partitioning strategy: RANGE, LIST, or HASH';
COMMENT ON COLUMN key_column_analysis.partitioning_score IS 'Partitioning suitability score (0-100)';
COMMENT ON COLUMN key_column_analysis.partitioning_recommendation IS 'Detailed partitioning recommendation with justification';
