-- V24: Create Scalability Growth Simulation
-- Implements BRAIN-design.md Section 10: "Scalability & Growth Reasoning"

-- Create scalability_simulation table for growth predictions
CREATE TABLE IF NOT EXISTS scalability_simulation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Current state
    current_total_rows BIGINT,
    current_total_size_mb BIGINT,
    current_avg_query_time_ms BIGINT,

    -- Growth scenarios
    growth_scenario VARCHAR(20) NOT NULL,  -- 2X, 5X, 10X, 20X

    -- Predicted state
    predicted_total_rows BIGINT,
    predicted_total_size_mb BIGINT,
    predicted_avg_query_time_ms BIGINT,

    -- Risk assessment
    overall_risk_level VARCHAR(20),  -- LOW, MEDIUM, HIGH, CRITICAL
    scalability_score DECIMAL(5, 2),  -- 0-100 (100 = excellent scalability)

    -- Specific risks identified
    risks_identified JSONB,  -- Array of risk objects

    -- Recommendations
    recommendations JSONB,  -- Array of recommendation objects

    -- Simulation details
    simulation_assumptions JSONB,  -- Methodology and assumptions used
    simulated_tables INT,  -- Number of tables analyzed

    -- Timestamps
    simulated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Create table_growth_prediction for individual table forecasts
CREATE TABLE IF NOT EXISTS table_growth_prediction (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    scalability_simulation_id UUID REFERENCES scalability_simulation(id) ON DELETE CASCADE,
    connection_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,

    -- Current metrics
    current_rows BIGINT,
    current_size_mb BIGINT,
    current_index_count INT,

    -- Predicted at growth scenario
    predicted_rows BIGINT,
    predicted_size_mb BIGINT,
    predicted_full_scan_time_ms BIGINT,

    -- Risk factors
    lacks_partitioning BOOLEAN DEFAULT FALSE,
    missing_critical_indexes BOOLEAN DEFAULT FALSE,
    inefficient_queries INT DEFAULT 0,  -- Count of queries with high examination ratio

    -- Recommendations
    recommended_actions TEXT,
    priority VARCHAR(20),  -- LOW, MEDIUM, HIGH, CRITICAL

    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- Indexes
CREATE INDEX IF NOT EXISTS idx_scalability_sim_connection ON scalability_simulation(connection_id);
CREATE INDEX IF NOT EXISTS idx_scalability_sim_date ON scalability_simulation(connection_id, simulated_at DESC);
CREATE INDEX IF NOT EXISTS idx_scalability_sim_risk ON scalability_simulation(connection_id, overall_risk_level);
CREATE INDEX IF NOT EXISTS idx_table_growth_sim ON table_growth_prediction(scalability_simulation_id);
CREATE INDEX IF NOT EXISTS idx_table_growth_connection ON table_growth_prediction(connection_id);

-- Comments
COMMENT ON TABLE scalability_simulation IS 'Stores growth simulation results predicting performance under 2x, 5x, 10x data growth';
COMMENT ON TABLE table_growth_prediction IS 'Per-table growth predictions and scalability risks';
COMMENT ON COLUMN scalability_simulation.scalability_score IS 'How well the system will scale (100 = excellent, 0 = will fail)';
COMMENT ON COLUMN table_growth_prediction.predicted_full_scan_time_ms IS 'Estimated time for full table scan at predicted size';
