CREATE TABLE IF NOT EXISTS approved_agent_workflows (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    intent VARCHAR(64) NOT NULL,
    example_question TEXT NOT NULL,
    normalized_question TEXT NOT NULL,
    question_signature VARCHAR(128) NOT NULL,
    goal TEXT,
    plan_summary TEXT,
    tools_json TEXT,
    step_params_json TEXT,
    helpful_count INTEGER NOT NULL DEFAULT 1,
    average_confidence DOUBLE PRECISION,
    latest_agent_run_id VARCHAR(36),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_approved_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_approved_agent_workflows_signature
    ON approved_agent_workflows(connection_id, intent, question_signature);

CREATE INDEX IF NOT EXISTS idx_approved_agent_workflows_connection_intent
    ON approved_agent_workflows(connection_id, intent);
