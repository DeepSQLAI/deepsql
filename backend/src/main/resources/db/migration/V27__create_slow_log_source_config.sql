CREATE TABLE IF NOT EXISTS slow_log_source_config (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT FALSE,
    provider_type VARCHAR(30) NOT NULL,
    bucket_name TEXT,
    object_prefix TEXT,
    s3_region TEXT,
    log_group_name TEXT,
    log_stream_prefix TEXT,
    access_key_id BYTEA,
    secret_access_key BYTEA,
    session_token BYTEA,
    refresh_frequency_minutes INTEGER,
    last_processed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_slow_log_source_config_connection
    ON slow_log_source_config (connection_id);
