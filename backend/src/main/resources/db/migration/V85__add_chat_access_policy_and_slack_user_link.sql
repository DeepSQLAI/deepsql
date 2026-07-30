CREATE TABLE connection_chat_access_policy (
    id BIGSERIAL PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    username VARCHAR(255) NOT NULL,
    plain_english_policy TEXT NOT NULL,
    blocked_sensitivity_categories jsonb,
    denied_tables jsonb,
    denied_columns jsonb,
    block_mode BOOLEAN NOT NULL DEFAULT TRUE,
    redact_mode BOOLEAN NOT NULL DEFAULT TRUE,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    updated_by VARCHAR(255),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_connection_chat_access_policy_connection_user
    ON connection_chat_access_policy (connection_id, username);

CREATE INDEX idx_connection_chat_access_policy_username
    ON connection_chat_access_policy (username);

CREATE INDEX idx_connection_chat_access_policy_connection
    ON connection_chat_access_policy (connection_id);

CREATE TABLE slack_user_link (
    id BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    slack_user_id VARCHAR(64) NOT NULL,
    slack_email VARCHAR(255),
    slack_display_name VARCHAR(255),
    deepsql_username VARCHAR(255) NOT NULL,
    link_status VARCHAR(32) NOT NULL,
    linked_at TIMESTAMP,
    last_verified_at TIMESTAMP,
    revoked_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_slack_user_link_team_user
    ON slack_user_link (team_id, slack_user_id);

CREATE INDEX idx_slack_user_link_username
    ON slack_user_link (deepsql_username);

CREATE TABLE slack_link_code (
    id BIGSERIAL PRIMARY KEY,
    deepsql_username VARCHAR(255) NOT NULL,
    code_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    consumed_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_slack_link_code_username
    ON slack_link_code (deepsql_username, created_at DESC);

CREATE TABLE slack_user_connection_binding (
    id BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    slack_user_id VARCHAR(64) NOT NULL,
    default_connection_id VARCHAR(36) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_slack_user_connection_binding_team_user
    ON slack_user_connection_binding (team_id, slack_user_id);

ALTER TABLE slack_thread_session
    ADD COLUMN IF NOT EXISTS slack_user_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS deepsql_username VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_slack_thread_session_slack_user
    ON slack_thread_session (team_id, slack_user_id);
