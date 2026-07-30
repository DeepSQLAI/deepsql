CREATE TABLE agent_runs (
    id VARCHAR(36) PRIMARY KEY,
    chat_id VARCHAR(36),
    connection_id VARCHAR(255) NOT NULL,
    question TEXT NOT NULL,
    intent VARCHAR(64) NOT NULL,
    goal TEXT,
    plan_summary TEXT,
    status VARCHAR(32) NOT NULL,
    confidence DOUBLE PRECISION,
    final_message TEXT,
    user_message_id VARCHAR(36),
    assistant_message_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    completed_at TIMESTAMP
);

CREATE INDEX idx_agent_runs_chat_created
    ON agent_runs (chat_id, created_at DESC);

CREATE INDEX idx_agent_runs_connection_created
    ON agent_runs (connection_id, created_at DESC);

CREATE TABLE agent_run_steps (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    step_index INTEGER NOT NULL,
    step_key VARCHAR(255),
    title VARCHAR(255) NOT NULL,
    tool_name VARCHAR(255) NOT NULL,
    status VARCHAR(32) NOT NULL,
    params_json TEXT,
    executed_sql TEXT,
    confidence DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_run_steps_run_step
    ON agent_run_steps (run_id, step_index);

CREATE TABLE agent_observations (
    id VARCHAR(36) PRIMARY KEY,
    run_id VARCHAR(36) NOT NULL REFERENCES agent_runs(id) ON DELETE CASCADE,
    step_id VARCHAR(36) REFERENCES agent_run_steps(id) ON DELETE CASCADE,
    observation_type VARCHAR(255) NOT NULL,
    summary TEXT,
    data_json TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_agent_observations_run_created
    ON agent_observations (run_id, created_at);
