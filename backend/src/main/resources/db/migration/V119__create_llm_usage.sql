CREATE TABLE llm_usage (
    id BIGSERIAL PRIMARY KEY,
    role VARCHAR(16) NOT NULL,
    provider_id VARCHAR(255) NOT NULL,
    model VARCHAR(255) NOT NULL,
    feature VARCHAR(64) NOT NULL,
    username VARCHAR(255),
    connection_id VARCHAR(255),
    prompt_tokens BIGINT NOT NULL DEFAULT 0,
    completion_tokens BIGINT NOT NULL DEFAULT 0,
    total_tokens BIGINT NOT NULL DEFAULT 0,
    cached_prompt_tokens BIGINT NOT NULL DEFAULT 0,
    estimated_cost_usd NUMERIC(12, 6),
    estimated BOOLEAN NOT NULL DEFAULT false,
    latency_ms BIGINT,
    succeeded BOOLEAN NOT NULL DEFAULT true,
    error_category VARCHAR(64),
    created_at TIMESTAMP NOT NULL DEFAULT now()
);

-- Every rollup filters on a time window first, so created_at leads each index.
CREATE INDEX idx_llm_usage_created_at ON llm_usage(created_at DESC);
CREATE INDEX idx_llm_usage_username ON llm_usage(username, created_at DESC);
CREATE INDEX idx_llm_usage_feature ON llm_usage(feature, created_at DESC);
CREATE INDEX idx_llm_usage_connection ON llm_usage(connection_id, created_at DESC);
