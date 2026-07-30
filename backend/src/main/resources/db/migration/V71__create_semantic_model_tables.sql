-- Vault-backed semantic model for BI and schema reasoning

CREATE TABLE semantic_table_model (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    table_role VARCHAR(50),
    business_domain VARCHAR(50),
    business_description TEXT,
    grain_description TEXT,
    key_columns JSONB,
    time_columns JSONB,
    dimension_columns JSONB,
    filter_columns JSONB,
    metric_columns JSONB,
    business_terms TEXT,
    confidence_score NUMERIC(5,2),
    source_summary TEXT,
    built_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_semantic_table_model UNIQUE (connection_id, table_name)
);

CREATE INDEX idx_semantic_table_model_connection
    ON semantic_table_model (connection_id);

CREATE INDEX idx_semantic_table_model_role
    ON semantic_table_model (connection_id, table_role);

CREATE INDEX idx_semantic_table_model_domain
    ON semantic_table_model (connection_id, business_domain);

CREATE TABLE semantic_join_model (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    source_table VARCHAR(255) NOT NULL,
    source_column VARCHAR(255) NOT NULL,
    target_table VARCHAR(255) NOT NULL,
    target_column VARCHAR(255) NOT NULL,
    relationship_type VARCHAR(50),
    evidence_source VARCHAR(50) NOT NULL,
    join_expression TEXT,
    preferred BOOLEAN NOT NULL DEFAULT FALSE,
    confidence_score NUMERIC(5,2),
    built_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,
    CONSTRAINT uk_semantic_join_model UNIQUE (
        connection_id, source_table, source_column, target_table, target_column
    )
);

CREATE INDEX idx_semantic_join_model_connection
    ON semantic_join_model (connection_id);

CREATE INDEX idx_semantic_join_model_tables
    ON semantic_join_model (connection_id, source_table, target_table);

CREATE INDEX idx_semantic_join_model_preferred
    ON semantic_join_model (connection_id, preferred, confidence_score DESC);

ALTER TABLE connection_init_status
    DROP CONSTRAINT connection_init_status_current_stage_check;

ALTER TABLE connection_init_status
    ADD CONSTRAINT connection_init_status_current_stage_check
    CHECK (current_stage IN (
        'SCHEMA_SCAN', 'DATA_SAMPLING', 'AI_DESCRIPTION', 'RAG_EMBEDDING',
        'BRAIN_ANALYSIS', 'KEY_COLUMN_ANALYSIS', 'SCHEMA_CLASSIFICATION',
        'SEMANTIC_MODELING', 'COMPLETED', 'FAILED'
    ));

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'connection_init_history_final_stage_check'
    ) THEN
        ALTER TABLE connection_init_history
            DROP CONSTRAINT connection_init_history_final_stage_check;
        ALTER TABLE connection_init_history
            ADD CONSTRAINT connection_init_history_final_stage_check
            CHECK (final_stage IN (
                'SCHEMA_SCAN', 'DATA_SAMPLING', 'AI_DESCRIPTION', 'RAG_EMBEDDING',
                'BRAIN_ANALYSIS', 'KEY_COLUMN_ANALYSIS', 'SCHEMA_CLASSIFICATION',
                'SEMANTIC_MODELING', 'COMPLETED', 'FAILED'
            ));
    END IF;
END $$;
