-- V17: Add Data Skew Analysis fields
-- Implements BRAIN-design.md Section 3.2: "Data skew (top-N values)"

-- Add skew fields to column_profile for data distribution tracking
ALTER TABLE column_profile
ADD COLUMN IF NOT EXISTS top_values TEXT,  -- JSON: [{"value": "foo", "count": 1234, "percentage": 12.34}, ...]
ADD COLUMN IF NOT EXISTS skew_coefficient DECIMAL(10, 6);  -- 0.0 to 1.0 (1.0 = highly skewed)

COMMENT ON COLUMN column_profile.top_values IS 'JSON array of top 10 most frequent values with counts and percentages';
COMMENT ON COLUMN column_profile.skew_coefficient IS 'Skew coefficient: 0.0 (uniform) to 1.0 (highly skewed). Calculated as: (top value count / total rows)';

-- Add skew fields to key_column_analysis for workload-aware skew tracking
ALTER TABLE key_column_analysis
ADD COLUMN IF NOT EXISTS skew_coefficient DECIMAL(10, 6),  -- Denormalized from column_profile
ADD COLUMN IF NOT EXISTS is_heavily_skewed BOOLEAN DEFAULT FALSE;  -- Flag for skew > 0.7

COMMENT ON COLUMN key_column_analysis.skew_coefficient IS 'Data distribution skew: 0.0 (uniform) to 1.0 (highly skewed)';
COMMENT ON COLUMN key_column_analysis.is_heavily_skewed IS 'True if skew_coefficient > 0.7 (top value dominates dataset)';

-- Create index for finding heavily skewed columns
CREATE INDEX IF NOT EXISTS idx_key_col_skewed ON key_column_analysis(connection_id, is_heavily_skewed)
WHERE is_heavily_skewed = true;

-- Add comment explaining the skew coefficient calculation
COMMENT ON TABLE column_profile IS 'Stores column statistics including cardinality, NULL ratio, and data skew metrics';
