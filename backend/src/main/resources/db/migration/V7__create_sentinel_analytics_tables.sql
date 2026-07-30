-- =====================================================
-- V7: Sentinel-DBA Analytics Enhancement Tables
-- Purpose: Advanced growth forecasting, event correlation, and capacity planning
-- Author: Sentinel-DBA System
-- Date: 2025-12-26
-- =====================================================

-- Table 1: Resource Limits (Database Capacity Configuration)
CREATE TABLE resource_limits (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,

    -- Storage limits
    max_storage_bytes BIGINT,           -- Total disk capacity
    current_storage_bytes BIGINT,       -- Current usage
    storage_warning_percent DOUBLE DEFAULT 75.0,
    storage_critical_percent DOUBLE DEFAULT 90.0,

    -- IOPS limits
    max_iops INTEGER,
    current_iops INTEGER,
    iops_warning_percent DOUBLE DEFAULT 75.0,
    iops_critical_percent DOUBLE DEFAULT 90.0,

    -- Connection limits
    max_connections INTEGER,
    current_connections INTEGER,
    connection_warning_percent DOUBLE DEFAULT 75.0,
    connection_critical_percent DOUBLE DEFAULT 90.0,

    -- CPU limits
    max_cpu_percent INTEGER DEFAULT 100,
    cpu_warning_percent DOUBLE DEFAULT 75.0,
    cpu_critical_percent DOUBLE DEFAULT 90.0,

    -- Metadata
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    is_enabled BOOLEAN DEFAULT TRUE,

    CONSTRAINT fk_resource_limits_connection FOREIGN KEY (connection_id)
        REFERENCES database_connections(id) ON DELETE CASCADE,
    INDEX idx_resource_limits_connection (connection_id)
);

-- Table 2: Capacity Forecasts (Death Clock Predictions)
CREATE TABLE capacity_forecasts (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255),
    forecast_date TIMESTAMP NOT NULL,

    -- Death Clock predictions
    storage_exhaustion_date TIMESTAMP,
    iops_exhaustion_date TIMESTAMP,
    connection_exhaustion_date TIMESTAMP,
    cpu_exhaustion_date TIMESTAMP,

    -- Current state percentages
    current_storage_percent DOUBLE,
    current_iops_percent DOUBLE,
    current_connection_percent DOUBLE,
    current_cpu_percent DOUBLE,

    -- Growth rates (First Derivative)
    daily_storage_growth_rate DOUBLE,      -- % per day
    daily_iops_growth_rate DOUBLE,
    daily_connection_growth_rate DOUBLE,
    daily_cpu_growth_rate DOUBLE,

    -- Acceleration (Second Derivative)
    storage_acceleration DOUBLE,            -- Change in growth rate per day
    iops_acceleration DOUBLE,
    connection_acceleration DOUBLE,
    cpu_acceleration DOUBLE,

    -- Growth pattern detection
    growth_pattern VARCHAR(50),             -- LINEAR, EXPONENTIAL, STEP_FUNCTION, SEASONAL
    pattern_confidence INTEGER,             -- 1-10 scale

    -- Attribution
    attributed_event_id VARCHAR(36),
    attributed_event_description TEXT,

    -- Risk assessment
    risk_score INTEGER,                     -- 1-10 (10 = critical)
    confidence_score INTEGER,               -- 1-10 (10 = high confidence)
    data_points_used INTEGER,

    -- Metadata
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_forecast_connection (connection_id),
    INDEX idx_forecast_table (connection_id, table_name),
    INDEX idx_forecast_date (forecast_date),
    INDEX idx_forecast_exhaustion (storage_exhaustion_date)
);

-- Table 3: Database Events (Deployment & Schema Change Tracking)
CREATE TABLE database_events (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    event_timestamp TIMESTAMP NOT NULL,
    event_type VARCHAR(50) NOT NULL,        -- DEPLOYMENT, SCHEMA_CHANGE, MAINTENANCE, INDEX_CREATE, INDEX_DROP

    -- Event details
    event_name VARCHAR(255),
    description TEXT,

    -- Affected objects
    affected_tables JSON,                    -- ["table1", "table2"]
    affected_indexes JSON,                   -- ["idx_name1", "idx_name2"]

    -- Event metadata
    deployment_version VARCHAR(100),
    deployment_tag VARCHAR(100),
    initiated_by VARCHAR(255),

    -- Impact tracking
    pre_event_snapshot_id VARCHAR(36),      -- Link to TableStatsHistory
    post_event_snapshot_id VARCHAR(36),
    storage_delta_bytes BIGINT,
    row_count_delta BIGINT,

    -- Additional metadata (JSON for flexibility)
    metadata JSON,

    -- Timestamps
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_event_connection (connection_id),
    INDEX idx_event_timestamp (event_timestamp),
    INDEX idx_event_type (event_type),
    INDEX idx_event_deployment_version (deployment_version)
);

-- Table 4: Workload Drift Metrics
CREATE TABLE workload_drift_metrics (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    measurement_timestamp TIMESTAMP NOT NULL,

    -- Workload metrics
    query_count_per_hour BIGINT,
    avg_query_latency_ms DOUBLE,
    p95_query_latency_ms DOUBLE,
    p99_query_latency_ms DOUBLE,

    -- I/O metrics
    read_iops INTEGER,
    write_iops INTEGER,
    read_throughput_mb DOUBLE,
    write_throughput_mb DOUBLE,

    -- Drift indicators
    size_to_row_ratio DOUBLE,               -- Bytes per row (detects bloat)
    index_to_data_ratio DOUBLE,             -- Index size / Data size
    dead_tuple_percent DOUBLE,              -- For Postgres bloat detection
    fragmentation_percent DOUBLE,           -- For MySQL/SQL Server

    -- Invisible growth
    wal_binlog_size_bytes BIGINT,          -- Write-ahead logs / Binary logs
    temp_table_size_bytes BIGINT,
    system_catalog_size_bytes BIGINT,

    -- Cache efficiency
    cache_hit_ratio DOUBLE,
    buffer_pool_hit_ratio DOUBLE,

    -- Metadata
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    INDEX idx_drift_connection_table (connection_id, table_name),
    INDEX idx_drift_timestamp (measurement_timestamp)
);

-- Table 5: Sentinel Recommendations (Action Items)
CREATE TABLE sentinel_recommendations (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255),

    -- Recommendation details
    recommendation_type VARCHAR(100) NOT NULL,  -- PARTITION, OPTIMIZE, SCALE_IOPS, PURGE_LOGS, etc.
    priority VARCHAR(20) NOT NULL,              -- IMMEDIATE, 24H, 48H, WEEK
    title VARCHAR(500) NOT NULL,
    description TEXT NOT NULL,

    -- Impact assessment
    estimated_storage_savings_bytes BIGINT,
    estimated_performance_improvement_percent DOUBLE,
    estimated_downtime_hours DOUBLE,

    -- Forecast relationship
    forecast_id VARCHAR(36),
    anomaly_id VARCHAR(36),

    -- Execution tracking
    status VARCHAR(50) DEFAULT 'PENDING',   -- PENDING, IN_PROGRESS, COMPLETED, DISMISSED
    implemented_at TIMESTAMP,
    implemented_by VARCHAR(255),
    dismissal_reason TEXT,

    -- Effectiveness tracking (post-implementation)
    actual_storage_savings_bytes BIGINT,
    actual_performance_improvement_percent DOUBLE,

    -- Metadata
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,

    INDEX idx_recommendation_connection (connection_id),
    INDEX idx_recommendation_status (status),
    INDEX idx_recommendation_priority (priority),
    FOREIGN KEY (forecast_id) REFERENCES capacity_forecasts(id) ON DELETE CASCADE,
    FOREIGN KEY (anomaly_id) REFERENCES growth_anomalies(id) ON DELETE CASCADE
);

-- Add indexes for performance
CREATE INDEX idx_forecast_risk ON capacity_forecasts(risk_score DESC, confidence_score DESC);
CREATE INDEX idx_event_correlation ON database_events(connection_id, event_timestamp);

-- Comments for documentation
COMMENT ON TABLE resource_limits IS 'Stores configured capacity limits for each database connection';
COMMENT ON TABLE capacity_forecasts IS 'Death Clock forecasts and growth pattern analysis';
COMMENT ON TABLE database_events IS 'Tracks deployments, schema changes, and maintenance events for correlation';
COMMENT ON TABLE workload_drift_metrics IS 'Captures workload patterns and invisible growth indicators';
COMMENT ON TABLE sentinel_recommendations IS 'AI-generated actionable recommendations based on Sentinel analysis';
