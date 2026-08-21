ALTER TABLE connection_chat_access_policy
    ADD COLUMN IF NOT EXISTS allowed_schemas jsonb,
    ADD COLUMN IF NOT EXISTS allow_aggregates BOOLEAN NOT NULL DEFAULT FALSE;
