-- V18: Add Key Classification (True vs Accidental Keys)
-- Implements BRAIN-design.md Section 7: "True keys vs accidental keys"

-- Create enum type for key classification
DO $$ BEGIN
    CREATE TYPE key_type AS ENUM (
        'TRUE_KEY',        -- Has PK/UK constraint OR perfect cardinality + heavy JOIN usage
        'ACCIDENTAL_KEY',  -- High cardinality but no semantic meaning
        'SURROGATE_KEY',   -- Auto-increment ID or UUID
        'NON_KEY'          -- Regular column
    );
EXCEPTION
    WHEN duplicate_object THEN null;
END $$;

-- Add key_type to key_column_analysis
ALTER TABLE key_column_analysis
ADD COLUMN IF NOT EXISTS key_type VARCHAR(50) DEFAULT 'NON_KEY';

-- Add key confidence score (0.0 to 1.0)
ALTER TABLE key_column_analysis
ADD COLUMN IF NOT EXISTS key_confidence DECIMAL(5, 4);

-- Add index for filtering by key type
CREATE INDEX IF NOT EXISTS idx_key_col_key_type ON key_column_analysis(connection_id, key_type);

-- Comments
COMMENT ON COLUMN key_column_analysis.key_type IS 'Classification: TRUE_KEY, ACCIDENTAL_KEY, SURROGATE_KEY, or NON_KEY';
COMMENT ON COLUMN key_column_analysis.key_confidence IS 'Confidence score (0.0-1.0) for key classification';
