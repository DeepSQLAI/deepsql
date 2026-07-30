CREATE TABLE cli_authorization (
    id BIGSERIAL PRIMARY KEY,
    authorization_id VARCHAR(64) NOT NULL,
    code_challenge_hash VARCHAR(255) NOT NULL,
    redirect_uri VARCHAR(512) NOT NULL,
    state VARCHAR(255),
    hostname VARCHAR(255),
    client_label VARCHAR(255),
    status VARCHAR(32) NOT NULL,
    approved_by_user_id BIGINT,
    code_hash VARCHAR(255),
    issued_token_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP,
    consumed_at TIMESTAMP
);

CREATE UNIQUE INDEX idx_cli_authorization_authorization_id
    ON cli_authorization (authorization_id);

CREATE INDEX idx_cli_authorization_status_expires
    ON cli_authorization (status, expires_at);

CREATE TABLE cli_device_code (
    id BIGSERIAL PRIMARY KEY,
    device_code_hash VARCHAR(255) NOT NULL,
    user_code_hash VARCHAR(255) NOT NULL,
    user_code_prefix VARCHAR(8) NOT NULL,
    hostname VARCHAR(255),
    client_label VARCHAR(255),
    interval_seconds INT NOT NULL DEFAULT 5,
    status VARCHAR(32) NOT NULL,
    approved_by_user_id BIGINT,
    issued_token_id BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    expires_at TIMESTAMP NOT NULL,
    approved_at TIMESTAMP,
    consumed_at TIMESTAMP,
    last_polled_at TIMESTAMP
);

CREATE INDEX idx_cli_device_code_status_expires
    ON cli_device_code (status, expires_at);

CREATE INDEX idx_cli_device_code_user_code_prefix
    ON cli_device_code (user_code_prefix);
