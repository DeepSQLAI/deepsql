-- V31: Advanced Table Classification
-- Adds columns for data lifecycle, cache affinity, query complexity, schema evolution risk,
-- denormalization candidates, cost attribution, sharding readiness, data quality,
-- dependency criticality, and growth prediction

-- ==================== Data Lifecycle Classification ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS data_lifecycle VARCHAR(20);
-- HOT, WARM, COLD, FROZEN
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS last_access_at TIMESTAMP;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS access_frequency_per_day DECIMAL(10,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS days_since_last_access INTEGER;

-- ==================== Cache Affinity Classification ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS cache_affinity VARCHAR(30);
-- HIGH_CACHE_VALUE, MEDIUM_CACHE_VALUE, LOW_CACHE_VALUE, NO_CACHE_BENEFIT
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS cache_affinity_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS cache_affinity_breakdown JSONB;
-- { "read_frequency": 0.8, "write_frequency": 0.1, "size_factor": 0.9, "volatility": 0.2 }

-- ==================== Query Complexity Classification ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS query_complexity VARCHAR(30);
-- SIMPLE_LOOKUP, RANGE_SCAN, COMPLEX_JOIN, AGGREGATION_HEAVY, FULL_SCAN, MIXED
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS query_complexity_breakdown JSONB;
-- { "simple_lookup_pct": 60, "range_scan_pct": 20, "complex_join_pct": 15, "aggregation_pct": 5 }
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS avg_query_complexity_score DECIMAL(5,2);

-- ==================== Schema Evolution Risk ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS schema_evolution_risk VARCHAR(20);
-- CRITICAL, HIGH, MEDIUM, LOW, MINIMAL
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS schema_evolution_risk_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS schema_evolution_factors JSONB;
-- { "size_factor": 0.8, "dependency_factor": 0.6, "index_factor": 0.4, "replication_factor": 0.3 }

-- ==================== Denormalization Candidates ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS denormalization_candidate BOOLEAN DEFAULT FALSE;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS denormalization_benefit_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS denormalization_details JSONB;
-- { "frequently_joined_with": ["users", "orders"], "join_frequency": 1500, "recommendation": "..." }

-- ==================== Cost Attribution ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS cost_category VARCHAR(30);
-- STORAGE_HEAVY, IO_HEAVY, CPU_HEAVY, NETWORK_HEAVY, BALANCED, LIGHTWEIGHT
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS storage_cost_mb DECIMAL(15,4);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS io_cost_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS cpu_cost_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS cost_breakdown JSONB;
-- { "storage_pct": 40, "io_pct": 35, "cpu_pct": 20, "network_pct": 5 }

-- ==================== Sharding Readiness ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS sharding_readiness VARCHAR(30);
-- SHARDING_CANDIDATE, SHARDING_COMPLEX, NOT_SHARDABLE, ALREADY_SHARDED
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS sharding_key_candidates JSONB;
-- [{ "column": "tenant_id", "cardinality": 500, "distribution_score": 0.95 }]
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS sharding_complexity_score DECIMAL(5,2);

-- ==================== Data Quality Score ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS data_quality_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS data_quality_breakdown JSONB;
-- { "completeness": 95, "consistency": 88, "accuracy": 92, "uniqueness": 99, "freshness": 85 }
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS null_rate_pct DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS orphan_record_count BIGINT DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS duplicate_rate_pct DECIMAL(5,2);

-- ==================== Dependency Criticality ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS dependency_criticality VARCHAR(30);
-- CRITICAL_PATH, HIGH_DEPENDENCY, MEDIUM_DEPENDENCY, LEAF_NODE, ISOLATED
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS dependency_score DECIMAL(5,2);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS upstream_dependencies INTEGER DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS downstream_dependents INTEGER DEFAULT 0;
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS in_circular_dependency BOOLEAN DEFAULT FALSE;

-- ==================== Growth Prediction ====================
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS growth_trend VARCHAR(20);
-- EXPONENTIAL, LINEAR, STABLE, DECLINING, VOLATILE
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS growth_rate_daily DECIMAL(15,4);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS growth_rate_monthly DECIMAL(15,4);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS predicted_size_30d_mb DECIMAL(15,4);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS predicted_size_90d_mb DECIMAL(15,4);
ALTER TABLE table_classification ADD COLUMN IF NOT EXISTS growth_prediction_confidence DECIMAL(5,2);

-- ==================== Indexes for new columns ====================
CREATE INDEX IF NOT EXISTS idx_table_class_lifecycle ON table_classification(connection_id, data_lifecycle);
CREATE INDEX IF NOT EXISTS idx_table_class_cache ON table_classification(connection_id, cache_affinity);
CREATE INDEX IF NOT EXISTS idx_table_class_complexity ON table_classification(connection_id, query_complexity);
CREATE INDEX IF NOT EXISTS idx_table_class_risk ON table_classification(connection_id, schema_evolution_risk);
CREATE INDEX IF NOT EXISTS idx_table_class_sharding ON table_classification(connection_id, sharding_readiness);
CREATE INDEX IF NOT EXISTS idx_table_class_quality ON table_classification(connection_id, data_quality_score);
CREATE INDEX IF NOT EXISTS idx_table_class_criticality ON table_classification(connection_id, dependency_criticality);
CREATE INDEX IF NOT EXISTS idx_table_class_growth ON table_classification(connection_id, growth_trend);

-- ==================== Schema Classification Aggregates ====================
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS hot_tables_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS cold_tables_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS high_cache_value_tables INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS high_risk_evolution_tables INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS denormalization_candidates_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS sharding_candidates_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS low_quality_tables_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS critical_path_tables_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS high_growth_tables_count INTEGER DEFAULT 0;
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS avg_data_quality_score DECIMAL(5,2);
ALTER TABLE schema_classification ADD COLUMN IF NOT EXISTS total_storage_cost_mb DECIMAL(15,4);
