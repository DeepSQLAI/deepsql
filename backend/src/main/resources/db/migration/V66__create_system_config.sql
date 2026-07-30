-- V66: System configuration key-value store
-- Used by the onboarding wizard to persist LLM credentials, org info,
-- and setup completion state. Sensitive values are AES-GCM encrypted.

CREATE TABLE IF NOT EXISTS system_config (
    key         VARCHAR(128) PRIMARY KEY,
    value_data  TEXT,
    is_sensitive BOOLEAN      NOT NULL DEFAULT false,
    description VARCHAR(512),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- Seed initial state so setup flow can detect first-run
INSERT INTO system_config (key, value_data, is_sensitive, description)
VALUES ('setup.complete', 'false', false, 'Whether the initial setup wizard has been completed')
ON CONFLICT (key) DO NOTHING;
