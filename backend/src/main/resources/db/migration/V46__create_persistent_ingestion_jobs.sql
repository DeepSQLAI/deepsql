-- Persistent ingestion jobs table for server restart resilience
-- Jobs are persisted to database and can be resumed after server restarts

CREATE TABLE IF NOT EXISTS ingestion_jobs (
    job_id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    status VARCHAR(20) NOT NULL,

    -- Progress tracking
    progress INT NOT NULL DEFAULT 0,
    current_step VARCHAR(500),
    bytes_processed BIGINT NOT NULL DEFAULT 0,
    slow_queries_found INT NOT NULL DEFAULT 0,
    total_streams INT NOT NULL DEFAULT 0,
    streams_processed INT NOT NULL DEFAULT 0,

    -- Timing
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    last_heartbeat TIMESTAMP,

    -- Result
    message VARCHAR(2000),
    history_id VARCHAR(36),

    -- Cancellation
    cancel_requested BOOLEAN NOT NULL DEFAULT FALSE,

    -- Configuration (for resume)
    time_range VARCHAR(50),
    provider_type VARCHAR(50),
    log_source VARCHAR(500),

    -- Checkpoint for resume
    last_processed_event_time TIMESTAMP,
    last_processed_stream_name VARCHAR(500),
    resume_attempts INT NOT NULL DEFAULT 0,

    -- Server instance tracking
    server_instance_id VARCHAR(100),

    CONSTRAINT fk_ingestion_job_connection
        FOREIGN KEY (connection_id) REFERENCES database_connections(id) ON DELETE CASCADE
);

-- Indexes for efficient queries
CREATE INDEX IF NOT EXISTS idx_ingestion_job_connection ON ingestion_jobs(connection_id);
CREATE INDEX IF NOT EXISTS idx_ingestion_job_status ON ingestion_jobs(status);
CREATE INDEX IF NOT EXISTS idx_ingestion_job_created ON ingestion_jobs(created_at);
CREATE INDEX IF NOT EXISTS idx_ingestion_job_heartbeat ON ingestion_jobs(last_heartbeat);

-- Comment on table
COMMENT ON TABLE ingestion_jobs IS 'Persistent storage for slow query log ingestion jobs - survives server restarts';
COMMENT ON COLUMN ingestion_jobs.status IS 'PENDING, RUNNING, COMPLETED, FAILED, CANCELLED, INTERRUPTED';
COMMENT ON COLUMN ingestion_jobs.last_heartbeat IS 'Last activity timestamp - used to detect stale jobs';
COMMENT ON COLUMN ingestion_jobs.last_processed_event_time IS 'Checkpoint: timestamp of last processed log event (for resume)';
COMMENT ON COLUMN ingestion_jobs.resume_attempts IS 'Number of times this job has been resumed (max 3)';
