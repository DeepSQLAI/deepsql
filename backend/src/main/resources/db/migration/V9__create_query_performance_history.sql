-- V9__create_query_performance_history.sql
-- Query Performance History tracking for regression detection

-- Main table to store query performance metrics over time
CREATE TABLE query_performance_history (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    query_hash VARCHAR(64) NOT NULL, -- MD5 hash of normalized query
    query_text TEXT NOT NULL,
    normalized_query TEXT, -- Query with parameters normalized

    -- Performance metrics
    execution_time_ms DOUBLE NOT NULL,
    rows_examined BIGINT,
    rows_sent BIGINT,
    cpu_time_ms DOUBLE,
    io_wait_ms DOUBLE,
    lock_wait_ms DOUBLE,

    -- Execution context
    database_name VARCHAR(255),
    user_name VARCHAR(255),
    client_host VARCHAR(255),

    -- Metadata
    execution_timestamp TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Indexing for fast lookups
    INDEX idx_connection_query (connection_id, query_hash),
    INDEX idx_query_hash (query_hash),
    INDEX idx_timestamp (execution_timestamp),
    INDEX idx_connection_timestamp (connection_id, execution_timestamp)
);

-- Table to store baseline performance metrics for each query
CREATE TABLE query_performance_baseline (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    normalized_query TEXT,

    -- Baseline metrics (calculated from historical data)
    avg_execution_time_ms DOUBLE NOT NULL,
    p50_execution_time_ms DOUBLE,
    p95_execution_time_ms DOUBLE,
    p99_execution_time_ms DOUBLE,
    min_execution_time_ms DOUBLE,
    max_execution_time_ms DOUBLE,
    std_dev_execution_time_ms DOUBLE,

    -- Sample size
    sample_count INT NOT NULL,
    first_seen TIMESTAMP NOT NULL,
    last_seen TIMESTAMP NOT NULL,

    -- Baseline calculation
    baseline_calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    baseline_window_days INT DEFAULT 7,

    -- Unique constraint to prevent duplicates
    UNIQUE KEY uk_connection_query (connection_id, query_hash),
    INDEX idx_connection (connection_id),
    INDEX idx_last_seen (last_seen)
);

-- Table to store detected performance regressions
CREATE TABLE query_performance_regression (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    normalized_query TEXT,

    -- Regression details
    severity VARCHAR(20) NOT NULL, -- MINOR, MODERATE, SEVERE, CRITICAL
    regression_type VARCHAR(50) NOT NULL, -- SUDDEN_SPIKE, GRADUAL_DEGRADATION, ANOMALY

    -- Performance comparison
    baseline_avg_ms DOUBLE NOT NULL,
    current_avg_ms DOUBLE NOT NULL,
    slowdown_factor DOUBLE NOT NULL, -- e.g., 2.5 = 2.5x slower
    slowdown_percent DOUBLE NOT NULL, -- e.g., 150 = 150% slower

    -- Detection metadata
    detected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    detection_window_start TIMESTAMP NOT NULL,
    detection_window_end TIMESTAMP NOT NULL,
    sample_count INT NOT NULL,

    -- Status
    acknowledged BOOLEAN DEFAULT FALSE,
    acknowledged_by VARCHAR(255),
    acknowledged_at TIMESTAMP,
    resolved BOOLEAN DEFAULT FALSE,
    resolved_at TIMESTAMP,
    resolution_notes TEXT,

    INDEX idx_connection (connection_id),
    INDEX idx_query_hash (query_hash),
    INDEX idx_severity (severity),
    INDEX idx_detected_at (detected_at),
    INDEX idx_unresolved (resolved, detected_at)
);

-- Table to track which queries to monitor
CREATE TABLE query_performance_tracking (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    normalized_query TEXT,

    -- Tracking configuration
    is_enabled BOOLEAN DEFAULT TRUE,
    track_all_executions BOOLEAN DEFAULT FALSE, -- If false, sample only
    sample_rate DOUBLE DEFAULT 0.1, -- Sample 10% of executions by default

    -- Alert thresholds
    alert_on_regression BOOLEAN DEFAULT TRUE,
    regression_threshold_percent DOUBLE DEFAULT 50.0, -- Alert if >50% slower
    alert_on_anomaly BOOLEAN DEFAULT TRUE,
    anomaly_z_score_threshold DOUBLE DEFAULT 3.0,

    -- Metadata
    added_by VARCHAR(255),
    added_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_execution TIMESTAMP,
    execution_count BIGINT DEFAULT 0,

    -- Unique constraint
    UNIQUE KEY uk_connection_query (connection_id, query_hash),
    INDEX idx_connection (connection_id),
    INDEX idx_enabled (is_enabled)
);

-- Table to store query performance statistics summary (for dashboard)
CREATE TABLE query_performance_stats (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,

    -- Period
    stat_date DATE NOT NULL,
    stat_hour INT, -- NULL for daily aggregation, 0-23 for hourly

    -- Aggregated metrics
    total_queries_executed BIGINT NOT NULL DEFAULT 0,
    total_execution_time_ms BIGINT NOT NULL DEFAULT 0,
    avg_execution_time_ms DOUBLE,
    p95_execution_time_ms DOUBLE,
    p99_execution_time_ms DOUBLE,

    -- Query counts by performance tier
    fast_queries INT DEFAULT 0,     -- < 100ms
    medium_queries INT DEFAULT 0,   -- 100ms - 1s
    slow_queries INT DEFAULT 0,     -- 1s - 10s
    very_slow_queries INT DEFAULT 0, -- > 10s

    -- Regression tracking
    active_regressions INT DEFAULT 0,
    new_regressions_today INT DEFAULT 0,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    UNIQUE KEY uk_connection_date_hour (connection_id, stat_date, stat_hour),
    INDEX idx_connection_date (connection_id, stat_date)
);
