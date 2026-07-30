-- V28: Create Inferred Table Relationship table
-- Stores table relationships inferred from JOIN patterns in slow query logs and query lineage

CREATE TABLE IF NOT EXISTS inferred_table_relationship (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Source side (FK side of relationship)
    source_table VARCHAR(255) NOT NULL,
    source_column VARCHAR(255) NOT NULL,

    -- Target side (PK side of relationship)
    target_table VARCHAR(255) NOT NULL,
    target_column VARCHAR(255) NOT NULL,

    -- Usage statistics
    join_count INT DEFAULT 1,           -- Number of times this JOIN pattern was observed
    distinct_query_count INT DEFAULT 1, -- Number of unique queries using this JOIN

    -- Confidence scoring (0-100)
    confidence_score DECIMAL(5, 2) DEFAULT 0.00,

    -- Relationship metadata
    is_self_join BOOLEAN DEFAULT FALSE,
    cardinality VARCHAR(20),            -- N:1, 1:N, N:M, 1:1

    -- Status for user validation
    status VARCHAR(20) DEFAULT 'INFERRED', -- INFERRED, VALIDATED, REJECTED

    -- Sample query for reference
    sample_query TEXT,

    -- Timestamps
    first_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for efficient querying
CREATE INDEX IF NOT EXISTS idx_inferred_rel_connection ON inferred_table_relationship(connection_id);
CREATE INDEX IF NOT EXISTS idx_inferred_rel_source ON inferred_table_relationship(connection_id, source_table);
CREATE INDEX IF NOT EXISTS idx_inferred_rel_target ON inferred_table_relationship(connection_id, target_table);
CREATE INDEX IF NOT EXISTS idx_inferred_rel_confidence ON inferred_table_relationship(connection_id, confidence_score);
CREATE INDEX IF NOT EXISTS idx_inferred_rel_status ON inferred_table_relationship(connection_id, status);

-- Unique constraint to prevent duplicate relationships
CREATE UNIQUE INDEX IF NOT EXISTS idx_inferred_rel_unique ON inferred_table_relationship(
    connection_id, source_table, source_column, target_table, target_column
);

-- Comments
COMMENT ON TABLE inferred_table_relationship IS 'Stores table relationships inferred from JOIN patterns in SQL queries';
COMMENT ON COLUMN inferred_table_relationship.source_table IS 'The table on the FK side of the relationship';
COMMENT ON COLUMN inferred_table_relationship.source_column IS 'The column on the FK side (usually ends with _id)';
COMMENT ON COLUMN inferred_table_relationship.target_table IS 'The table on the PK side of the relationship';
COMMENT ON COLUMN inferred_table_relationship.target_column IS 'The column on the PK side (usually id)';
COMMENT ON COLUMN inferred_table_relationship.confidence_score IS 'Confidence score (0-100) based on join frequency and query diversity';
COMMENT ON COLUMN inferred_table_relationship.status IS 'User validation status: INFERRED (auto-detected), VALIDATED (user confirmed), REJECTED (user denied)';
