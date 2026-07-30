-- Growth Monitoring System Tables

-- Table: table_stats_history
-- Stores hourly snapshots of table metrics with growth calculations
CREATE TABLE table_stats_history (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    snapshot_timestamp TIMESTAMP NOT NULL,

    -- Current metrics
    size_bytes BIGINT,
    row_count BIGINT,
    index_size_bytes BIGINT,
    data_size_bytes BIGINT,

    -- Growth calculations (compared to previous snapshot)
    size_growth_bytes BIGINT,
    size_growth_percent DOUBLE,
    row_count_growth BIGINT,
    row_count_growth_percent DOUBLE,

    -- Statistical metrics
    size_z_score DOUBLE,
    row_count_z_score DOUBLE,

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_connection_table (connection_id, table_name),
    INDEX idx_snapshot_time (snapshot_timestamp),
    INDEX idx_connection_time (connection_id, snapshot_timestamp)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Table: growth_anomalies
-- Stores detected growth anomalies with severity and type
CREATE TABLE growth_anomalies (
    id VARCHAR(36) PRIMARY KEY,
    snapshot_id VARCHAR(36) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    detection_timestamp TIMESTAMP NOT NULL,

    anomaly_type VARCHAR(50) NOT NULL, -- PERCENTAGE_GROWTH, ABSOLUTE_GROWTH, STATISTICAL_ANOMALY, ROW_SPIKE
    severity VARCHAR(20) NOT NULL, -- INFO, WARNING, CRITICAL

    -- Metrics at detection time
    current_size_bytes BIGINT,
    previous_size_bytes BIGINT,
    size_growth_bytes BIGINT,
    size_growth_percent DOUBLE,

    current_row_count BIGINT,
    previous_row_count BIGINT,
    row_count_growth BIGINT,

    -- Statistical data
    z_score DOUBLE,
    threshold_value DOUBLE,

    description TEXT,
    alert_id VARCHAR(36), -- Links to playbook_alerts table

    -- Acknowledgment tracking
    acknowledged BOOLEAN DEFAULT FALSE,
    acknowledged_by VARCHAR(255),
    acknowledged_at TIMESTAMP,

    FOREIGN KEY (snapshot_id) REFERENCES table_stats_history(id) ON DELETE CASCADE,
    INDEX idx_connection_table (connection_id, table_name),
    INDEX idx_detection_time (detection_timestamp),
    INDEX idx_acknowledged (acknowledged),
    INDEX idx_severity (severity),
    INDEX idx_snapshot (snapshot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Table: growth_alert_configurations
-- Per-table or connection-wide alert threshold configurations
CREATE TABLE growth_alert_configurations (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255), -- NULL = global config for connection

    -- Percentage growth thresholds (per hour)
    percentage_growth_warning DOUBLE DEFAULT 50.0,
    percentage_growth_critical DOUBLE DEFAULT 100.0,

    -- Absolute size growth thresholds (bytes per hour)
    absolute_growth_warning_bytes BIGINT DEFAULT 10737418240, -- 10GB
    absolute_growth_critical_bytes BIGINT DEFAULT 53687091200, -- 50GB

    -- Row count spike thresholds (rows per hour)
    row_spike_warning BIGINT DEFAULT 1000000, -- 1M rows
    row_spike_critical BIGINT DEFAULT 10000000, -- 10M rows
    row_spike_percentage DOUBLE DEFAULT 200.0, -- 200% increase

    -- Statistical detection settings
    z_score_threshold DOUBLE DEFAULT 3.0, -- 3 standard deviations
    historical_window_hours INT DEFAULT 168, -- 7 days

    -- Notification settings
    notification_channels JSON, -- ["in-app", "email", "webhook"]
    email_recipients JSON, -- ["admin@example.com", "dba@example.com"]
    webhook_url VARCHAR(500),

    -- Alert throttling to prevent spam
    min_hours_between_alerts INT DEFAULT 1,
    last_alert_sent TIMESTAMP,

    is_enabled BOOLEAN DEFAULT TRUE,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    UNIQUE KEY unique_connection_table (connection_id, table_name),
    INDEX idx_connection (connection_id),
    INDEX idx_enabled (is_enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Table: growth_monitoring_config
-- System-wide configuration settings
CREATE TABLE growth_monitoring_config (
    config_key VARCHAR(100) PRIMARY KEY,
    config_value VARCHAR(500),
    description TEXT,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

-- Insert default system configuration
INSERT INTO growth_monitoring_config (config_key, config_value, description) VALUES
('retention_days', '90', 'Number of days to retain historical growth data before automatic cleanup'),
('monitoring_enabled', 'true', 'Enable or disable growth monitoring globally'),
('snapshot_frequency', 'hourly', 'Frequency of snapshot capture (hourly, daily, etc.)'),
('min_table_size_bytes', '10485760', 'Minimum table size (10MB) to monitor for growth anomalies');
