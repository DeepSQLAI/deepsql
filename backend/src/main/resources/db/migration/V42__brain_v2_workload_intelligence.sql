-- Brain 2.0: Workload Intelligence Layer (OtterTune-inspired)
-- Enables ML-based workload characterization and knowledge transfer

-- Workload Metrics Snapshot: Stores raw metrics for workload analysis
CREATE TABLE workload_metrics_snapshot (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,
    collected_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    -- Raw metrics as JSON (131+ dimensions for PostgreSQL, 100+ for MySQL)
    raw_metrics JSONB NOT NULL,

    -- Reduced metrics after factor analysis (typically 8-12 dimensions)
    reduced_metrics JSONB,

    -- Workload fingerprint (hash of reduced metrics for similarity matching)
    workload_fingerprint VARCHAR(64),

    -- Database type for context
    db_type VARCHAR(50) NOT NULL,

    -- Metadata
    collection_duration_ms INTEGER,
    metrics_count INTEGER,

    CONSTRAINT fk_workload_metrics_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_workload_metrics_conn_time ON workload_metrics_snapshot(connection_id, collected_at DESC);
CREATE INDEX idx_workload_metrics_fingerprint ON workload_metrics_snapshot(workload_fingerprint);

-- Workload Profile: Characterization of a connection's workload
CREATE TABLE workload_profile (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    connection_id VARCHAR(255) NOT NULL,

    -- Workload classification
    workload_type VARCHAR(50) NOT NULL, -- OLTP, OLAP, MIXED, WRITE_HEAVY, READ_HEAVY, BATCH
    workload_subtype VARCHAR(100),
    classification_confidence DOUBLE PRECISION,

    -- Normalized fingerprint for similarity matching (vector of 8-12 reduced metrics)
    fingerprint_vector JSONB NOT NULL,

    -- Best-performing configuration discovered for this workload
    optimal_config JSONB,

    -- Performance achieved with optimal config
    performance_score DOUBLE PRECISION,
    latency_p50_ms DOUBLE PRECISION,
    latency_p99_ms DOUBLE PRECISION,
    throughput_qps DOUBLE PRECISION,

    -- Factor analysis results
    factor_loadings JSONB, -- Which original metrics contribute to each factor
    selected_metrics JSONB, -- Representative metrics after k-means

    -- Timestamps
    profiled_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_updated_at TIMESTAMP,

    CONSTRAINT fk_workload_profile_connection
        FOREIGN KEY (connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_workload_profile_conn ON workload_profile(connection_id);
CREATE INDEX idx_workload_profile_type ON workload_profile(workload_type);
CREATE UNIQUE INDEX idx_workload_profile_conn_unique ON workload_profile(connection_id);

-- Workload Similarity Cache: Pre-computed similarity scores between workloads
CREATE TABLE workload_similarity (
    id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    source_connection_id VARCHAR(255) NOT NULL,
    target_connection_id VARCHAR(255) NOT NULL,

    -- Similarity metrics
    euclidean_distance DOUBLE PRECISION NOT NULL,
    cosine_similarity DOUBLE PRECISION,
    similarity_score DOUBLE PRECISION NOT NULL, -- 0-100 normalized

    -- Which factors contributed most to similarity
    similarity_breakdown JSONB,

    calculated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_similarity_source
        FOREIGN KEY (source_connection_id) REFERENCES database_connection(id) ON DELETE CASCADE,
    CONSTRAINT fk_similarity_target
        FOREIGN KEY (target_connection_id) REFERENCES database_connection(id) ON DELETE CASCADE
);

CREATE INDEX idx_workload_similarity_source ON workload_similarity(source_connection_id, similarity_score DESC);
CREATE INDEX idx_workload_similarity_target ON workload_similarity(target_connection_id);
CREATE UNIQUE INDEX idx_workload_similarity_pair ON workload_similarity(source_connection_id, target_connection_id);
