-- Brain 2.0: Enhanced Brain Score Components
-- New scoring dimensions for config tuning and query intelligence

-- Add new columns to brain_score for Brain 2.0 components
ALTER TABLE brain_score
ADD COLUMN IF NOT EXISTS config_tuning_score DECIMAL(5,2),
ADD COLUMN IF NOT EXISTS query_intelligence_score DECIMAL(5,2),
ADD COLUMN IF NOT EXISTS workload_understanding_score DECIMAL(5,2),
ADD COLUMN IF NOT EXISTS learning_progress_score DECIMAL(5,2);

-- Brain V2 Score History: Detailed tracking of all score components
CREATE TABLE brain_v2_score_detail (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    brain_score_id UUID NOT NULL,
    connection_id VARCHAR(255) NOT NULL,

    -- Config Tuning Score breakdown (OtterTune-inspired)
    config_optimization_level DOUBLE PRECISION, -- How optimized current config is
    experiment_success_rate DOUBLE PRECISION, -- % of successful tuning experiments
    knowledge_transfer_score DOUBLE PRECISION, -- Benefit from similar workloads
    config_stability_score DOUBLE PRECISION, -- How stable recommendations are

    -- Query Intelligence Score breakdown (optd-inspired)
    cardinality_accuracy DOUBLE PRECISION, -- Our estimates vs actual
    plan_pattern_coverage DOUBLE PRECISION, -- % of queries with cached patterns
    adaptive_learning_progress DOUBLE PRECISION, -- Improvement over time
    transformation_effectiveness DOUBLE PRECISION, -- How well rules work

    -- Workload Understanding breakdown
    workload_classification_confidence DOUBLE PRECISION,
    metric_collection_completeness DOUBLE PRECISION,
    fingerprint_stability DOUBLE PRECISION,

    -- Learning Progress breakdown
    training_data_volume INTEGER,
    model_convergence_score DOUBLE PRECISION,
    feedback_incorporation_rate DOUBLE PRECISION,

    -- Timestamps
    calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_v2_score_brain
        FOREIGN KEY (brain_score_id) REFERENCES brain_score(id) ON DELETE CASCADE,
    CONSTRAINT fk_v2_score_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_v2_score_brain ON brain_v2_score_detail(brain_score_id);
CREATE INDEX idx_v2_score_conn ON brain_v2_score_detail(connection_id, calculated_at DESC);

-- Learning Progress Tracker: Tracks overall Brain 2.0 learning progress
CREATE TABLE brain_learning_progress (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Workload Intelligence progress
    workload_snapshots_collected INTEGER DEFAULT 0,
    factor_analysis_completed BOOLEAN DEFAULT FALSE,
    workload_type_identified BOOLEAN DEFAULT FALSE,
    similar_workloads_found INTEGER DEFAULT 0,

    -- Config Tuning progress
    baseline_observation_recorded BOOLEAN DEFAULT FALSE,
    knob_ranking_completed BOOLEAN DEFAULT FALSE,
    top_knobs_identified INTEGER DEFAULT 0,
    gp_model_trained BOOLEAN DEFAULT FALSE,
    experiments_completed INTEGER DEFAULT 0,
    successful_experiments INTEGER DEFAULT 0,

    -- Query Intelligence progress
    column_stats_collected INTEGER DEFAULT 0,
    plan_executions_recorded INTEGER DEFAULT 0,
    patterns_discovered INTEGER DEFAULT 0,
    cardinality_comparisons INTEGER DEFAULT 0,

    -- Overall readiness
    brain_v2_ready BOOLEAN DEFAULT FALSE,
    readiness_percent DOUBLE PRECISION DEFAULT 0,

    -- Timestamps
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,

    CONSTRAINT fk_learning_progress_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE UNIQUE INDEX idx_learning_progress_conn ON brain_learning_progress(connection_id);

-- Brain V2 Alerts: Alerts specific to Brain 2.0 features
CREATE TABLE brain_v2_alert (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Alert details
    alert_type VARCHAR(50) NOT NULL, -- CONFIG_DRIFT, WORKLOAD_CHANGE, CARDINALITY_DEGRADATION, etc.
    severity VARCHAR(20) NOT NULL, -- INFO, WARNING, HIGH, CRITICAL
    title VARCHAR(255) NOT NULL,
    message TEXT NOT NULL,

    -- Context
    context_data JSONB,
    recommended_action TEXT,

    -- Lifecycle
    status VARCHAR(20) DEFAULT 'ACTIVE', -- ACTIVE, ACKNOWLEDGED, RESOLVED
    acknowledged_at TIMESTAMP,
    resolved_at TIMESTAMP,

    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_v2_alert_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_v2_alert_conn_status ON brain_v2_alert(connection_id, status, created_at DESC);
CREATE INDEX idx_v2_alert_type ON brain_v2_alert(alert_type);
