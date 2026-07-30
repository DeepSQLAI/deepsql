-- Schema Change Tracking & Drift Detection

-- Table to store schema snapshots
CREATE TABLE IF NOT EXISTS schema_snapshot (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL REFERENCES connection_request(id) ON DELETE CASCADE,
    snapshot_name VARCHAR(255),
    snapshot_type VARCHAR(30) NOT NULL, -- MANUAL, SCHEDULED, BASELINE
    table_count INTEGER NOT NULL DEFAULT 0,
    column_count INTEGER NOT NULL DEFAULT 0,
    index_count INTEGER NOT NULL DEFAULT 0,
    constraint_count INTEGER NOT NULL DEFAULT 0,
    schema_hash VARCHAR(64) NOT NULL, -- SHA-256 hash of schema for quick comparison
    schema_json JSONB NOT NULL, -- Full schema definition
    notes TEXT,
    created_by VARCHAR(100),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast lookup by connection and time
CREATE INDEX IF NOT EXISTS idx_schema_snapshot_connection_created
    ON schema_snapshot(connection_id, created_at DESC);

-- Index for finding baselines
CREATE INDEX IF NOT EXISTS idx_schema_snapshot_type
    ON schema_snapshot(connection_id, snapshot_type);

-- Table to store detected schema changes
CREATE TABLE IF NOT EXISTS schema_change (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL REFERENCES connection_request(id) ON DELETE CASCADE,
    from_snapshot_id VARCHAR(36) REFERENCES schema_snapshot(id) ON DELETE SET NULL,
    to_snapshot_id VARCHAR(36) REFERENCES schema_snapshot(id) ON DELETE SET NULL,
    change_type VARCHAR(30) NOT NULL, -- TABLE_ADDED, TABLE_REMOVED, COLUMN_ADDED, COLUMN_REMOVED, COLUMN_MODIFIED, INDEX_ADDED, INDEX_REMOVED, CONSTRAINT_ADDED, CONSTRAINT_REMOVED
    object_type VARCHAR(30) NOT NULL, -- TABLE, COLUMN, INDEX, CONSTRAINT, FOREIGN_KEY
    object_name VARCHAR(255) NOT NULL, -- Full object name (table.column, index name, etc.)
    table_name VARCHAR(255), -- Parent table if applicable
    change_details JSONB NOT NULL, -- Detailed change information
    severity VARCHAR(20) NOT NULL DEFAULT 'INFO', -- INFO, WARNING, CRITICAL
    is_breaking_change BOOLEAN DEFAULT FALSE,
    is_acknowledged BOOLEAN DEFAULT FALSE,
    acknowledged_by VARCHAR(100),
    acknowledged_at TIMESTAMP,
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Index for fast lookup by connection and time
CREATE INDEX IF NOT EXISTS idx_schema_change_connection_detected
    ON schema_change(connection_id, detected_at DESC);

-- Index for finding unacknowledged changes
CREATE INDEX IF NOT EXISTS idx_schema_change_unack
    ON schema_change(connection_id, is_acknowledged)
    WHERE is_acknowledged = FALSE;

-- Index for change type filtering
CREATE INDEX IF NOT EXISTS idx_schema_change_type
    ON schema_change(connection_id, change_type);

-- Table for drift detection configuration per connection
CREATE TABLE IF NOT EXISTS schema_drift_config (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL UNIQUE REFERENCES connection_request(id) ON DELETE CASCADE,
    enabled BOOLEAN DEFAULT TRUE,
    check_frequency_minutes INTEGER DEFAULT 60, -- How often to check for drift
    baseline_snapshot_id VARCHAR(36) REFERENCES schema_snapshot(id) ON DELETE SET NULL,
    alert_on_table_changes BOOLEAN DEFAULT TRUE,
    alert_on_column_changes BOOLEAN DEFAULT TRUE,
    alert_on_index_changes BOOLEAN DEFAULT TRUE,
    alert_on_constraint_changes BOOLEAN DEFAULT TRUE,
    ignored_tables TEXT[], -- Tables to ignore in drift detection
    ignored_patterns TEXT[], -- Regex patterns to ignore
    last_checked_at TIMESTAMP,
    next_check_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Comments
COMMENT ON TABLE schema_snapshot IS 'Stores point-in-time snapshots of database schema for comparison and drift detection';
COMMENT ON TABLE schema_change IS 'Records detected schema changes between snapshots';
COMMENT ON TABLE schema_drift_config IS 'Configuration for automatic schema drift detection per database connection';

COMMENT ON COLUMN schema_snapshot.snapshot_type IS 'MANUAL: User-triggered, SCHEDULED: Auto-captured, BASELINE: Reference for drift detection';
COMMENT ON COLUMN schema_snapshot.schema_hash IS 'SHA-256 hash for quick schema comparison without full JSON diff';
COMMENT ON COLUMN schema_change.change_type IS 'Type of change: TABLE_ADDED, TABLE_REMOVED, COLUMN_ADDED, COLUMN_REMOVED, COLUMN_MODIFIED, INDEX_ADDED, INDEX_REMOVED, CONSTRAINT_ADDED, CONSTRAINT_REMOVED';
COMMENT ON COLUMN schema_change.severity IS 'INFO: Minor change, WARNING: Potential impact, CRITICAL: Breaking change';
