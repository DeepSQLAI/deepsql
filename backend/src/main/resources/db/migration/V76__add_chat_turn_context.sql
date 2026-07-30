CREATE TABLE IF NOT EXISTS chat_turn_context (
    id VARCHAR(36) PRIMARY KEY,
    chat_id VARCHAR(36) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    user_message_id VARCHAR(36) NOT NULL,
    assistant_message_id VARCHAR(36) NOT NULL,
    parent_context_id VARCHAR(36),
    state_status VARCHAR(32) NOT NULL,
    route_type VARCHAR(64),
    intent VARCHAR(128),
    anchor_question TEXT,
    current_question TEXT NOT NULL,
    question_summary TEXT,
    answer_summary TEXT,
    chain_summary TEXT,
    resolved_context_json TEXT,
    selected_entities_json TEXT,
    result_summary_json TEXT,
    source_sql TEXT,
    topic_signature TEXT,
    confidence_score DOUBLE PRECISION,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_chat_turn_context_chat_created
    ON chat_turn_context(chat_id, created_at);

CREATE INDEX IF NOT EXISTS idx_chat_turn_context_connection_created
    ON chat_turn_context(connection_id, created_at);

CREATE INDEX IF NOT EXISTS idx_chat_turn_context_parent
    ON chat_turn_context(parent_context_id);

CREATE UNIQUE INDEX IF NOT EXISTS idx_chat_turn_context_assistant_message
    ON chat_turn_context(assistant_message_id);

ALTER TABLE approved_agent_workflows
    ADD COLUMN IF NOT EXISTS source_context_id VARCHAR(36);

ALTER TABLE approved_agent_workflows
    ADD COLUMN IF NOT EXISTS anchor_question TEXT;

ALTER TABLE approved_agent_workflows
    ADD COLUMN IF NOT EXISTS chain_summary TEXT;

ALTER TABLE approved_agent_workflows
    ADD COLUMN IF NOT EXISTS resolved_context_json TEXT;

CREATE INDEX IF NOT EXISTS idx_approved_agent_workflows_source_context
    ON approved_agent_workflows(source_context_id);
