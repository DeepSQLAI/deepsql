-- Add per-stage timing data to connection_init_status
ALTER TABLE connection_init_status ADD COLUMN stage_timings JSONB DEFAULT '{}';

-- Create connection_init_history table for completed/failed runs
CREATE TABLE connection_init_history (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    final_stage VARCHAR(30) NOT NULL,
    progress_percent INTEGER NOT NULL,
    stage_timings JSONB DEFAULT '{}',
    started_at TIMESTAMP NOT NULL,
    completed_at TIMESTAMP NOT NULL,
    total_duration_ms BIGINT,
    error_message TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_init_history_conn ON connection_init_history(connection_id, created_at DESC);
