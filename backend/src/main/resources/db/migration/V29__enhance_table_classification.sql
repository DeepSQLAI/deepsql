-- V29: Enhance Table Classification System
-- Adds access patterns, temporal classification, business domain, health scores, and anti-pattern detection

-- Phase 1: Access Pattern Classification
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS access_pattern VARCHAR(50);
-- Values: READ_HEAVY, WRITE_HEAVY, APPEND_ONLY, UPDATE_INTENSIVE, MIXED_WORKLOAD, RARELY_ACCESSED

ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS read_count BIGINT DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS write_count BIGINT DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS update_count BIGINT DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS delete_count BIGINT DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS read_write_ratio DECIMAL(10, 4);

-- Phase 1: Enhanced Anti-Pattern Detection (per table)
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS anti_patterns JSONB;
-- Stores array of detected anti-patterns: GOD_TABLE, SPARSE_TABLE, POLYMORPHIC_ASSOCIATION,
-- EAV_PATTERN, MISSING_TIMESTAMP, OVER_INDEXED, UNDER_INDEXED, DUPLICATE_INDEXES, UNUSED_COLUMNS

ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS anti_pattern_count INT DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS anti_pattern_severity VARCHAR(20);
-- Values: CRITICAL, HIGH, MEDIUM, LOW, NONE

-- Phase 2: Temporal Classification
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS temporal_type VARCHAR(50);
-- Values: TIME_SERIES, SCD_TYPE_1, SCD_TYPE_2, AUDIT_LOG, EVENT_SOURCING, SNAPSHOT, NONE

ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS has_timestamp_columns BOOLEAN DEFAULT FALSE;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS timestamp_columns JSONB;

-- Phase 2: Table Health Score
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS health_score DECIMAL(5, 2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS health_breakdown JSONB;
-- Stores: index_efficiency, data_quality, schema_design, access_efficiency, maintenance_health

-- Phase 3: Business Domain Classification
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS business_domain VARCHAR(50);
-- Values: CUSTOMER, PRODUCT, TRANSACTION, LOCATION, TEMPORAL, CONFIGURATION, SECURITY, COMMUNICATION, UNKNOWN

ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS domain_confidence DECIMAL(5, 2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS domain_indicators JSONB;

-- Phase 4: Data Sensitivity Classification
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS sensitivity_level VARCHAR(50);
-- Values: PII_HIGH, PII_MEDIUM, FINANCIAL, HEALTH, REGULATED, PUBLIC

ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS sensitive_columns JSONB;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS sensitivity_confidence DECIMAL(5, 2);

-- Phase 4: Partitioning Readiness
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS partition_readiness VARCHAR(50);
-- Values: PARTITION_CANDIDATE_TIME, PARTITION_CANDIDATE_RANGE, PARTITION_CANDIDATE_LIST,
--         ALREADY_PARTITIONED, NOT_PARTITION_CANDIDATE

ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS partition_key_candidates JSONB;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS estimated_partition_benefit DECIMAL(5, 2);

-- Phase 5: Relationship metadata (stored at table level for quick access)
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS relationship_summary JSONB;
-- Stores: strong_relationships, weak_relationships, inferred_relationships, missing_fks

-- Additional indexes for new columns
CREATE INDEX IF NOT EXISTS idx_table_class_access_pattern ON table_classification(connection_id, access_pattern);
CREATE INDEX IF NOT EXISTS idx_table_class_temporal ON table_classification(connection_id, temporal_type);
CREATE INDEX IF NOT EXISTS idx_table_class_domain ON table_classification(connection_id, business_domain);
CREATE INDEX IF NOT EXISTS idx_table_class_sensitivity ON table_classification(connection_id, sensitivity_level);
CREATE INDEX IF NOT EXISTS idx_table_class_health ON table_classification(connection_id, health_score DESC);
CREATE INDEX IF NOT EXISTS idx_table_class_antipattern ON table_classification(connection_id, anti_pattern_severity);

-- Phase 5: Create table_relationship_classification for detailed relationship analysis
CREATE TABLE IF NOT EXISTS table_relationship_classification (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    schema_classification_id UUID REFERENCES schema_classification(id) ON DELETE CASCADE,

    -- Relationship details
    source_table VARCHAR(255) NOT NULL,
    target_table VARCHAR(255) NOT NULL,
    source_column VARCHAR(255),
    target_column VARCHAR(255),

    -- Classification
    relationship_type VARCHAR(50),  -- ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY
    strength VARCHAR(50),           -- STRONG (FK constraint), INFERRED (naming pattern), WEAK (join pattern only)

    -- Metrics
    join_frequency INT DEFAULT 0,
    data_integrity_pct DECIMAL(5, 2),  -- % of FK values that exist in parent
    orphan_count BIGINT DEFAULT 0,     -- Count of orphaned FK values

    -- FK details
    is_cascading BOOLEAN DEFAULT FALSE,
    has_index BOOLEAN DEFAULT FALSE,

    -- Confidence and validation
    confidence_score DECIMAL(5, 2),
    validation_status VARCHAR(20) DEFAULT 'PENDING',  -- PENDING, VALIDATED, REJECTED

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    UNIQUE(schema_classification_id, source_table, target_table, source_column)
);

CREATE INDEX IF NOT EXISTS idx_rel_class_connection ON table_relationship_classification(connection_id);
CREATE INDEX IF NOT EXISTS idx_rel_class_schema ON table_relationship_classification(schema_classification_id);
CREATE INDEX IF NOT EXISTS idx_rel_class_source ON table_relationship_classification(connection_id, source_table);
CREATE INDEX IF NOT EXISTS idx_rel_class_target ON table_relationship_classification(connection_id, target_table);
CREATE INDEX IF NOT EXISTS idx_rel_class_strength ON table_relationship_classification(connection_id, strength);

-- Add aggregate columns to schema_classification for quick access
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS tables_with_anti_patterns INT DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS critical_anti_patterns INT DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS avg_health_score DECIMAL(5, 2);
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS pii_tables_count INT DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS partition_candidates_count INT DEFAULT 0;

-- Comments
COMMENT ON COLUMN table_classification.access_pattern IS 'Table access pattern: READ_HEAVY, WRITE_HEAVY, APPEND_ONLY, UPDATE_INTENSIVE, MIXED_WORKLOAD, RARELY_ACCESSED';
COMMENT ON COLUMN table_classification.temporal_type IS 'Temporal pattern: TIME_SERIES, SCD_TYPE_1, SCD_TYPE_2, AUDIT_LOG, EVENT_SOURCING, SNAPSHOT, NONE';
COMMENT ON COLUMN table_classification.business_domain IS 'Business domain: CUSTOMER, PRODUCT, TRANSACTION, LOCATION, TEMPORAL, CONFIGURATION, SECURITY, COMMUNICATION';
COMMENT ON COLUMN table_classification.sensitivity_level IS 'Data sensitivity: PII_HIGH, PII_MEDIUM, FINANCIAL, HEALTH, REGULATED, PUBLIC';
COMMENT ON COLUMN table_classification.health_score IS 'Overall table health score (0-100) combining index efficiency, data quality, schema design, access efficiency, maintenance health';
COMMENT ON COLUMN table_classification.anti_patterns IS 'JSON array of detected anti-patterns with severity and recommendations';
COMMENT ON TABLE table_relationship_classification IS 'Detailed classification of table relationships including strength, integrity, and validation status';
