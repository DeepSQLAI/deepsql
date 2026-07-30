-- Query Optimization Cache table
-- Stores AI-powered query optimization results to avoid redundant API calls

CREATE TABLE IF NOT EXISTS query_optimization_cache (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    query_fingerprint VARCHAR(255) NOT NULL,
    original_query TEXT,
    optimized_query TEXT,
    suggestions JSONB,
    index_recommendations JSONB,
    explanation TEXT,
    estimated_improvement DOUBLE PRECISION,
    explain_analysis JSONB,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    access_count INTEGER DEFAULT 0,
    last_accessed_at TIMESTAMP,

    CONSTRAINT uq_optimization_cache_conn_fingerprint
        UNIQUE (connection_id, query_fingerprint)
);

-- Index for lookups by connection and fingerprint
CREATE INDEX IF NOT EXISTS idx_qoc_connection_fingerprint
    ON query_optimization_cache(connection_id, query_fingerprint);

-- Index for cleanup of old cached entries
CREATE INDEX IF NOT EXISTS idx_qoc_created_at
    ON query_optimization_cache(created_at);

-- Index for finding popular cached optimizations
CREATE INDEX IF NOT EXISTS idx_qoc_access_count
    ON query_optimization_cache(connection_id, access_count DESC);

COMMENT ON TABLE query_optimization_cache IS 'Caches AI-powered query optimization results to avoid redundant API calls';
COMMENT ON COLUMN query_optimization_cache.query_fingerprint IS 'Hash/ID of the normalized query pattern';
COMMENT ON COLUMN query_optimization_cache.suggestions IS 'JSON array of optimization suggestions';
COMMENT ON COLUMN query_optimization_cache.index_recommendations IS 'JSON array of recommended indexes';
COMMENT ON COLUMN query_optimization_cache.explain_analysis IS 'JSON object with EXPLAIN plan analysis';
COMMENT ON COLUMN query_optimization_cache.access_count IS 'Number of times this cached result has been accessed';
