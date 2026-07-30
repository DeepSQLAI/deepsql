-- V16: Enhance Key Column Analysis with advanced metrics
-- Adds frequency/recency weighting, selectivity, index stats, and composite recommendations

-- Add new columns to key_column_analysis for advanced metrics
ALTER TABLE key_column_analysis
ADD COLUMN IF NOT EXISTS frequency_score DECIMAL(10, 2),
ADD COLUMN IF NOT EXISTS recency_score DECIMAL(10, 2),
ADD COLUMN IF NOT EXISTS selectivity DECIMAL(10, 6),
ADD COLUMN IF NOT EXISTS distinct_value_count BIGINT,
ADD COLUMN IF NOT EXISTS cardinality_ratio DECIMAL(10, 6),
ADD COLUMN IF NOT EXISTS null_ratio DECIMAL(10, 6),
ADD COLUMN IF NOT EXISTS enhanced_importance_score DECIMAL(10, 2),
ADD COLUMN IF NOT EXISTS index_name VARCHAR(255),
ADD COLUMN IF NOT EXISTS index_usage_count BIGINT,
ADD COLUMN IF NOT EXISTS index_scan_count BIGINT,
ADD COLUMN IF NOT EXISTS has_unused_index BOOLEAN DEFAULT FALSE,
ADD COLUMN IF NOT EXISTS last_used_at TIMESTAMP,
ADD COLUMN IF NOT EXISTS uses_per_day DECIMAL(10, 2),
ADD COLUMN IF NOT EXISTS ml_prediction_score DECIMAL(10, 2);

-- Create composite_index_recommendation table
CREATE TABLE IF NOT EXISTS composite_index_recommendation (
    id BIGSERIAL PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_names TEXT NOT NULL, -- JSON array of column names in order
    recommendation_reason VARCHAR(500),
    co_occurrence_count INT NOT NULL,
    selectivity_product DECIMAL(10, 6),
    estimated_benefit_score DECIMAL(10, 2),
    suggested_index_sql TEXT,
    priority VARCHAR(50), -- CRITICAL, HIGH, MEDIUM, LOW
    status VARCHAR(50) DEFAULT 'PENDING', -- PENDING, ACKNOWLEDGED, IMPLEMENTED, REJECTED
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_composite_index_rec_connection ON composite_index_recommendation(connection_id);
CREATE INDEX IF NOT EXISTS idx_composite_index_rec_table ON composite_index_recommendation(table_name);
CREATE INDEX IF NOT EXISTS idx_composite_index_rec_priority ON composite_index_recommendation(priority, status);

-- Create query_plan_analysis table for unused index detection
CREATE TABLE IF NOT EXISTS query_plan_analysis (
    id BIGSERIAL PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    query_hash VARCHAR(64) NOT NULL,
    query_text TEXT,
    execution_plan TEXT, -- JSON or text format
    uses_index BOOLEAN,
    index_names TEXT, -- JSON array of indexes used
    full_table_scan BOOLEAN,
    estimated_cost DECIMAL(10, 2),
    actual_cost DECIMAL(10, 2),
    analyzed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_query_plan_connection ON query_plan_analysis(connection_id);
CREATE INDEX IF NOT EXISTS idx_query_plan_hash ON query_plan_analysis(query_hash);

-- Create ml_feature_cache table for ML scoring
CREATE TABLE IF NOT EXISTS ml_feature_cache (
    id BIGSERIAL PRIMARY KEY,
    connection_id VARCHAR(255) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,
    feature_vector TEXT, -- JSON with features for ML
    prediction_score DECIMAL(10, 2),
    feature_importance TEXT, -- JSON with feature contributions
    computed_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_ml_feature_connection ON ml_feature_cache(connection_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_ml_feature_unique ON ml_feature_cache(connection_id, table_name, column_name);

-- Add comments for documentation
COMMENT ON COLUMN key_column_analysis.frequency_score IS 'Usage frequency normalized to uses per day';
COMMENT ON COLUMN key_column_analysis.recency_score IS 'Time-decay weighted score prioritizing recent usage';
COMMENT ON COLUMN key_column_analysis.selectivity IS 'Cardinality ratio (distinct_count / total_rows)';
COMMENT ON COLUMN key_column_analysis.enhanced_importance_score IS 'Combined score with selectivity and recency weighting';
COMMENT ON COLUMN key_column_analysis.ml_prediction_score IS 'ML-predicted performance impact score';
COMMENT ON TABLE composite_index_recommendation IS 'Recommendations for multi-column indexes based on co-occurrence patterns';
COMMENT ON TABLE query_plan_analysis IS 'Cached query execution plans for index usage analysis';
COMMENT ON TABLE ml_feature_cache IS 'Cached feature vectors and predictions for ML-based scoring';
