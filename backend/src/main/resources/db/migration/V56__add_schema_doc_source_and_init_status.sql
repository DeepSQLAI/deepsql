-- Add source and confidence columns to schema_documentation
ALTER TABLE schema_documentation
    ADD COLUMN source VARCHAR(20) DEFAULT 'USER' NOT NULL,
    ADD COLUMN confidence DOUBLE PRECISION;

-- Create index for filtering by source
CREATE INDEX idx_schema_doc_source ON schema_documentation(connection_id, source);

-- Add data sampling opt-in flag to connections
ALTER TABLE encrypted_credentials
    ADD COLUMN enable_data_sampling BOOLEAN DEFAULT TRUE NOT NULL;

-- Create connection_init_status table
CREATE TABLE connection_init_status (
    connection_id VARCHAR(255) PRIMARY KEY,
    current_stage VARCHAR(30) NOT NULL DEFAULT 'SCHEMA_SCAN',
    progress_percent INTEGER NOT NULL DEFAULT 0,
    stage_message VARCHAR(500),
    started_at TIMESTAMP NOT NULL DEFAULT NOW(),
    completed_at TIMESTAMP,
    error_message TEXT
);
