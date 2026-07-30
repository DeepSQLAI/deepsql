-- V22: Create Schema Classification System
-- Implements BRAIN-design.md Section 6: "Schema Classification"

-- Create schema_classification table for overall database schema pattern
CREATE TABLE IF NOT EXISTS schema_classification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Global classification
    global_pattern VARCHAR(50) NOT NULL,  -- STAR, SNOWFLAKE, HYBRID, OLTP, REPORTING, ANTI_PATTERN
    confidence_score DECIMAL(5, 2),  -- 0-100 confidence in classification

    -- Metrics used for classification
    total_tables INT,
    fact_tables INT,
    dimension_tables INT,
    bridge_tables INT,
    orphaned_tables INT,

    -- Pattern characteristics
    avg_fan_out DECIMAL(10, 2),  -- Average number of dimensions per fact
    max_dimension_depth INT,  -- Snowflake depth (1 = star, >1 = snowflake)
    normalization_level VARCHAR(20),  -- 1NF, 2NF, 3NF, DENORMALIZED

    -- Quality indicators
    has_cycles BOOLEAN DEFAULT FALSE,
    unclear_ownership BOOLEAN DEFAULT FALSE,
    missing_foreign_keys INT DEFAULT 0,

    -- Explainability
    classification_reasoning JSONB,  -- Detailed breakdown of classification logic

    -- Timestamps
    classified_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create table_classification for individual table roles
CREATE TABLE IF NOT EXISTS table_classification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    schema_classification_id UUID REFERENCES schema_classification(id) ON DELETE CASCADE,
    connection_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,

    -- Table role
    table_role VARCHAR(50) NOT NULL,  -- FACT, DIMENSION, BRIDGE, LOOKUP, ORPHANED, AGGREGATE
    confidence_score DECIMAL(5, 2),  -- 0-100 confidence

    -- Metrics
    row_count BIGINT,
    column_count INT,
    foreign_key_count INT,
    inbound_join_count INT,  -- How many tables join TO this table
    outbound_join_count INT,  -- How many tables this table joins TO

    -- Characteristics
    is_normalized BOOLEAN,
    has_surrogate_key BOOLEAN,
    granularity_level VARCHAR(50),  -- DETAIL, SUMMARY, AGGREGATE

    -- Graph position
    depth_from_fact INT,  -- For snowflake dimensions (0 = fact, 1 = direct dimension, 2+ = snowflaked)
    cluster_id INT,  -- Which star cluster this belongs to

    -- Reasoning
    classification_reasoning TEXT,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(schema_classification_id, table_name)
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_schema_class_connection ON schema_classification(connection_id);
CREATE INDEX IF NOT EXISTS idx_schema_class_date ON schema_classification(connection_id, classified_at DESC);
CREATE INDEX IF NOT EXISTS idx_table_class_schema ON table_classification(schema_classification_id);
CREATE INDEX IF NOT EXISTS idx_table_class_connection ON table_classification(connection_id);
CREATE INDEX IF NOT EXISTS idx_table_class_role ON table_classification(connection_id, table_role);

-- Comments
COMMENT ON TABLE schema_classification IS 'Stores overall schema pattern classification (Star, Snowflake, OLTP, etc.)';
COMMENT ON TABLE table_classification IS 'Stores individual table role classification (Fact, Dimension, etc.)';
COMMENT ON COLUMN schema_classification.global_pattern IS 'Overall schema pattern: STAR, SNOWFLAKE, HYBRID, OLTP, REPORTING, ANTI_PATTERN';
COMMENT ON COLUMN table_classification.table_role IS 'Table role: FACT, DIMENSION, BRIDGE, LOOKUP, ORPHANED, AGGREGATE';
COMMENT ON COLUMN table_classification.depth_from_fact IS 'Distance from fact table: 0=fact, 1=direct dimension, 2+=snowflaked dimension';
