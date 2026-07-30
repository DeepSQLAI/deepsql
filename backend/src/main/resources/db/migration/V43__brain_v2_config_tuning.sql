-- Brain 2.0: Configuration Tuning Engine (OtterTune-inspired)
-- ML-based knob identification, Gaussian Process tuning, and knowledge transfer

-- Knob Ranking: Ranked list of configuration parameters by impact
CREATE TABLE knob_ranking (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Knob identification
    knob_name VARCHAR(255) NOT NULL,
    db_type VARCHAR(50) NOT NULL,

    -- Ranking from Lasso regression
    rank INTEGER NOT NULL, -- 1 = most impactful
    impact_score DOUBLE PRECISION NOT NULL, -- Lasso coefficient magnitude

    -- Target metric this ranking is for
    target_metric VARCHAR(50) NOT NULL, -- latency, throughput, cache_hit_ratio

    -- Knob metadata
    current_value VARCHAR(255),
    default_value VARCHAR(255),
    min_value VARCHAR(255),
    max_value VARCHAR(255),
    value_type VARCHAR(50), -- integer, float, boolean, enum, memory
    requires_restart BOOLEAN DEFAULT FALSE,

    -- Confidence and validity
    confidence_score DOUBLE PRECISION,
    sample_count INTEGER, -- Number of observations used for ranking

    calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_knob_ranking_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_knob_ranking_conn_metric ON knob_ranking(connection_id, target_metric, rank);
CREATE UNIQUE INDEX idx_knob_ranking_unique ON knob_ranking(connection_id, knob_name, target_metric);

-- Configuration Observation: Records of configuration experiments
CREATE TABLE configuration_observation (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Configuration snapshot
    configuration JSONB NOT NULL, -- All knob values at time of observation

    -- Performance metrics before/after
    metrics_before JSONB,
    metrics_after JSONB NOT NULL,

    -- Key performance indicators
    latency_p50_before DOUBLE PRECISION,
    latency_p50_after DOUBLE PRECISION,
    latency_p99_before DOUBLE PRECISION,
    latency_p99_after DOUBLE PRECISION,
    throughput_before DOUBLE PRECISION,
    throughput_after DOUBLE PRECISION,

    -- Calculated improvement
    improvement_percent DOUBLE PRECISION,

    -- Observation metadata
    observation_type VARCHAR(50) NOT NULL, -- BASELINE, RECOMMENDED, MANUAL, EXPERIMENT
    observation_duration_minutes INTEGER,
    workload_fingerprint VARCHAR(64),

    observed_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_config_obs_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_config_obs_conn_time ON configuration_observation(connection_id, observed_at DESC);
CREATE INDEX idx_config_obs_fingerprint ON configuration_observation(workload_fingerprint);

-- Tuning Experiment: Tracks A/B testing of configuration changes
CREATE TABLE tuning_experiment (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Link to the recommendation being tested
    recommendation_id VARCHAR(255),

    -- Configuration changes being tested
    knob_changes JSONB NOT NULL, -- {knob_name: {old: x, new: y}}

    -- Baseline metrics (captured at experiment start)
    baseline_latency_p50 DOUBLE PRECISION,
    baseline_latency_p99 DOUBLE PRECISION,
    baseline_throughput DOUBLE PRECISION,
    baseline_cache_hit_ratio DOUBLE PRECISION,
    baseline_metrics JSONB,

    -- New metrics (captured after observation period)
    new_latency_p50 DOUBLE PRECISION,
    new_latency_p99 DOUBLE PRECISION,
    new_throughput DOUBLE PRECISION,
    new_cache_hit_ratio DOUBLE PRECISION,
    new_metrics JSONB,

    -- Calculated improvements
    latency_improvement_percent DOUBLE PRECISION,
    throughput_improvement_percent DOUBLE PRECISION,
    overall_improvement_percent DOUBLE PRECISION,

    -- Experiment lifecycle
    status VARCHAR(50) NOT NULL DEFAULT 'PENDING', -- PENDING, RUNNING, COMPLETED, FAILED, ROLLED_BACK
    observation_period_minutes INTEGER DEFAULT 30,

    -- Timestamps
    started_at TIMESTAMP,
    completed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Notes and metadata
    notes TEXT,
    error_message TEXT,

    CONSTRAINT fk_tuning_exp_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_tuning_exp_conn_status ON tuning_experiment(connection_id, status);
CREATE INDEX idx_tuning_exp_created ON tuning_experiment(created_at DESC);

-- Cost Calibration: Learned adjustments to cost model
CREATE TABLE cost_calibration (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Calibration factors
    cost_multiplier DOUBLE PRECISION DEFAULT 1.0,
    seq_scan_cost_factor DOUBLE PRECISION DEFAULT 1.0,
    index_scan_cost_factor DOUBLE PRECISION DEFAULT 1.0,
    join_cost_factor DOUBLE PRECISION DEFAULT 1.0,
    sort_cost_factor DOUBLE PRECISION DEFAULT 1.0,

    -- Learning progress
    training_samples INTEGER DEFAULT 0,
    last_training_error DOUBLE PRECISION,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,

    CONSTRAINT fk_cost_cal_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_cost_cal_conn ON cost_calibration(connection_id);

-- GP Model State: Persisted Gaussian Process model state for incremental updates
CREATE TABLE gp_model_state (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    target_metric VARCHAR(50) NOT NULL, -- latency, throughput

    -- Serialized model state
    model_state BYTEA, -- Serialized GP model
    model_version INTEGER DEFAULT 1,

    -- Model hyperparameters
    kernel_type VARCHAR(50) DEFAULT 'MATERN',
    length_scale DOUBLE PRECISION,
    noise_variance DOUBLE PRECISION,

    -- Training metadata
    training_points INTEGER,
    last_training_time_ms INTEGER,

    -- Best known configuration
    best_config JSONB,
    best_performance DOUBLE PRECISION,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,

    CONSTRAINT fk_gp_model_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_gp_model_conn_metric ON gp_model_state(connection_id, target_metric);
