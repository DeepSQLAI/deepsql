ALTER TABLE users
    ADD COLUMN IF NOT EXISTS email_verified_at TIMESTAMP(6),
    ADD COLUMN IF NOT EXISTS account_status VARCHAR(32) NOT NULL DEFAULT 'ACTIVE',
    ADD COLUMN IF NOT EXISTS last_login_at TIMESTAMP(6),
    ADD COLUMN IF NOT EXISTS last_login_ip VARCHAR(128),
    ADD COLUMN IF NOT EXISTS google_workspace_subject VARCHAR(255),
    ADD COLUMN IF NOT EXISTS google_workspace_issuer VARCHAR(255),
    ADD COLUMN IF NOT EXISTS mfa_enrolled_at TIMESTAMP(6);

UPDATE users
SET email = lower(username) || '@deepsql.local'
WHERE email IS NULL OR btrim(email) = '';

UPDATE users
SET account_status = 'ACTIVE'
WHERE account_status IS NULL OR btrim(account_status) = '';

UPDATE users
SET email_verified_at = COALESCE(email_verified_at, CURRENT_TIMESTAMP)
WHERE account_status = 'ACTIVE' AND email IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uk_users_email_lower
    ON users (lower(email));

CREATE TABLE IF NOT EXISTS user_invite (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(320) NOT NULL,
    username VARCHAR(255),
    role VARCHAR(32) NOT NULL,
    token_hash VARCHAR(128) NOT NULL,
    invited_by VARCHAR(255),
    invite_type VARCHAR(32) NOT NULL DEFAULT 'STANDARD',
    user_id BIGINT,
    expires_at TIMESTAMP(6) NOT NULL,
    accepted_at TIMESTAMP(6),
    revoked_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_invite_token_hash
    ON user_invite (token_hash);

CREATE INDEX IF NOT EXISTS idx_user_invite_email
    ON user_invite (lower(email));

CREATE TABLE IF NOT EXISTS auth_login_challenge (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT,
    email VARCHAR(320) NOT NULL,
    challenge_type VARCHAR(32) NOT NULL,
    challenge_state VARCHAR(32) NOT NULL,
    otp_hash VARCHAR(128),
    otp_attempt_count INTEGER NOT NULL DEFAULT 0,
    resend_count INTEGER NOT NULL DEFAULT 0,
    mfa_attempt_count INTEGER NOT NULL DEFAULT 0,
    google_code_verifier VARCHAR(255),
    pending_mfa_secret BYTEA,
    client_ip VARCHAR(128),
    user_agent VARCHAR(1024),
    expires_at TIMESTAMP(6) NOT NULL,
    verified_at TIMESTAMP(6),
    consumed_at TIMESTAMP(6),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_auth_login_challenge_email
    ON auth_login_challenge (lower(email));

CREATE INDEX IF NOT EXISTS idx_auth_login_challenge_user
    ON auth_login_challenge (user_id);

CREATE TABLE IF NOT EXISTS user_session (
    id VARCHAR(64) PRIMARY KEY,
    user_id BIGINT NOT NULL,
    refresh_token_hash VARCHAR(128) NOT NULL,
    client_ip VARCHAR(128),
    user_agent VARCHAR(1024),
    device_label VARCHAR(255),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_seen_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    access_expires_at TIMESTAMP(6) NOT NULL,
    refresh_expires_at TIMESTAMP(6) NOT NULL,
    revoked_at TIMESTAMP(6),
    revoke_reason VARCHAR(255),
    mfa_verified_at TIMESTAMP(6),
    CONSTRAINT fk_user_session_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_user_session_refresh_token_hash
    ON user_session (refresh_token_hash);

CREATE INDEX IF NOT EXISTS idx_user_session_user
    ON user_session (user_id);

CREATE TABLE IF NOT EXISTS user_mfa_enrollment (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL UNIQUE,
    secret_ciphertext BYTEA NOT NULL,
    enrolled_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    last_verified_at TIMESTAMP(6),
    reset_at TIMESTAMP(6),
    reset_by VARCHAR(255),
    CONSTRAINT fk_user_mfa_user FOREIGN KEY (user_id) REFERENCES users(id)
);

CREATE TABLE IF NOT EXISTS security_event (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(64) NOT NULL,
    outcome VARCHAR(32) NOT NULL,
    user_id BIGINT,
    actor_user_id BIGINT,
    email VARCHAR(320),
    target_resource VARCHAR(255),
    reason VARCHAR(1024),
    client_ip VARCHAR(128),
    user_agent VARCHAR(1024),
    request_id VARCHAR(128),
    event_metadata JSONB,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_security_event_created_at
    ON security_event (created_at DESC);

CREATE INDEX IF NOT EXISTS idx_security_event_user
    ON security_event (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_security_event_email
    ON security_event (lower(email), created_at DESC);

CREATE TABLE IF NOT EXISTS google_workspace_domain (
    id BIGSERIAL PRIMARY KEY,
    domain VARCHAR(255) NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_google_workspace_domain_lower
    ON google_workspace_domain (lower(domain));
