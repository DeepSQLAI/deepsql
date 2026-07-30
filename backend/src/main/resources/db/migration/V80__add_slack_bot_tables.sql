CREATE TABLE slack_channel_binding (
    id BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    channel_type VARCHAR(32) NOT NULL,
    default_connection_id VARCHAR(36) NOT NULL,
    updated_by VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_slack_channel_binding_team_channel
    ON slack_channel_binding (team_id, channel_id);

CREATE INDEX idx_slack_channel_binding_connection
    ON slack_channel_binding (default_connection_id);

CREATE TABLE slack_thread_session (
    id BIGSERIAL PRIMARY KEY,
    team_id VARCHAR(64) NOT NULL,
    channel_id VARCHAR(64) NOT NULL,
    root_thread_ts VARCHAR(64) NOT NULL,
    connection_id VARCHAR(36) NOT NULL,
    chat_id VARCHAR(36) NOT NULL,
    last_used_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX idx_slack_thread_session_thread
    ON slack_thread_session (team_id, channel_id, root_thread_ts);

CREATE INDEX idx_slack_thread_session_chat
    ON slack_thread_session (chat_id);

CREATE TABLE slack_event_receipt (
    event_id VARCHAR(128) PRIMARY KEY,
    received_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);
