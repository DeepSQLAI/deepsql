-- V120: Per-user digest preferences with role-aware personalization
--
-- Migrates from singleton SlackDigestConfig to per-user preferences.
-- The singleton row (id=1) remains for backward compatibility and serves as
-- the global default when a user has no specific preferences configured.
--
-- This migration:
-- 1. Creates user_digest_preference table for per-user preferences
-- 2. Extends slack_digest_log with per-recipient tracking fields
-- 3. Preserves existing singleton behavior as fallback

-- ============================================================================
-- 1. Create per-user digest preference table
-- ============================================================================
CREATE TABLE IF NOT EXISTS user_digest_preference (
    id                  BIGSERIAL PRIMARY KEY,
    username            VARCHAR(255) NOT NULL,
    connection_id       VARCHAR(36),
    enabled             BOOLEAN NOT NULL DEFAULT TRUE,
    persona_tag         VARCHAR(32),
    cron_expression     VARCHAR(100),
    delivery_method     VARCHAR(32) NOT NULL DEFAULT 'SLACK_DM',
    timezone            VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP NOT NULL DEFAULT NOW(),

    CONSTRAINT uk_user_digest_pref_user_conn_method
        UNIQUE (username, connection_id, delivery_method)
);

COMMENT ON TABLE user_digest_preference IS
    'Per-user digest subscription preferences. Each row is one delivery subscription.';
COMMENT ON COLUMN user_digest_preference.username IS
    'Username of the subscriber (references users.username)';
COMMENT ON COLUMN user_digest_preference.connection_id IS
    'Scoped to this connection, or NULL for all connections user can access';
COMMENT ON COLUMN user_digest_preference.persona_tag IS
    'Content prioritization persona: DBA, APP_ENG, DATA_ENG, EXEC';
COMMENT ON COLUMN user_digest_preference.cron_expression IS
    'Override schedule (uses global default when NULL)';
COMMENT ON COLUMN user_digest_preference.delivery_method IS
    'SLACK_DM, SLACK_CHANNEL, or EMAIL';
COMMENT ON COLUMN user_digest_preference.timezone IS
    'IANA timezone for schedule (e.g. America/New_York)';

CREATE INDEX IF NOT EXISTS idx_user_digest_pref_username
    ON user_digest_preference(username);
CREATE INDEX IF NOT EXISTS idx_user_digest_pref_conn
    ON user_digest_preference(connection_id);
CREATE INDEX IF NOT EXISTS idx_user_digest_pref_enabled
    ON user_digest_preference(enabled, username);


-- ============================================================================
-- 2. Extend slack_digest_log for per-recipient tracking
-- ============================================================================

-- Add per-recipient fields (nullable for backward compatibility)
ALTER TABLE slack_digest_log
    ADD COLUMN IF NOT EXISTS recipient_username VARCHAR(255),
    ADD COLUMN IF NOT EXISTS recipient_role VARCHAR(64),
    ADD COLUMN IF NOT EXISTS persona_tag VARCHAR(32),
    ADD COLUMN IF NOT EXISTS delivery_method VARCHAR(32),
    ADD COLUMN IF NOT EXISTS preference_id BIGINT,
    ADD COLUMN IF NOT EXISTS personalized BOOLEAN NOT NULL DEFAULT FALSE;

COMMENT ON COLUMN slack_digest_log.recipient_username IS
    'Username of recipient (NULL for legacy channel-broadcast)';
COMMENT ON COLUMN slack_digest_log.recipient_role IS
    'RBAC role at delivery time (audit trail)';
COMMENT ON COLUMN slack_digest_log.persona_tag IS
    'Persona tag used for content prioritization';
COMMENT ON COLUMN slack_digest_log.delivery_method IS
    'SLACK_DM, SLACK_CHANNEL, or EMAIL';
COMMENT ON COLUMN slack_digest_log.preference_id IS
    'FK to user_digest_preference that triggered delivery';
COMMENT ON COLUMN slack_digest_log.personalized IS
    'TRUE if content was role-personalized';

CREATE INDEX IF NOT EXISTS idx_slack_digest_log_recipient
    ON slack_digest_log(recipient_username, sent_at DESC);
CREATE INDEX IF NOT EXISTS idx_slack_digest_log_conn_recipient
    ON slack_digest_log(connection_id, recipient_username, sent_at DESC);


-- ============================================================================
-- 3. Add global_default flag to slack_digest_config for clarity
-- ============================================================================

-- The singleton row now explicitly marks itself as the global default
ALTER TABLE slack_digest_config
    ADD COLUMN IF NOT EXISTS is_global_default BOOLEAN NOT NULL DEFAULT TRUE;

COMMENT ON COLUMN slack_digest_config.is_global_default IS
    'TRUE for the singleton row that serves as fallback';

-- Ensure the singleton row exists and is marked as global default
INSERT INTO slack_digest_config (id, cron_expression, updated_at, is_global_default)
VALUES (1, '0 0 9 * * *', NOW(), TRUE)
ON CONFLICT (id) DO UPDATE SET is_global_default = TRUE;
