-- Per-user DeepSQL Agent conversations — the identity-keyed index that lets a
-- user resume their agent chats from ANY device.
--
-- The agent webui persists each conversation in its own per-profile state.db,
-- but its session-read endpoints are scoped to a single global "active profile"
-- and cannot enumerate a user's sessions by identity. This table owns that
-- index (which agent session belongs to which DeepSQL user) plus a mirrored
-- transcript, so any device the user logs into can list, render, and continue
-- their threads. Context continuity comes from reusing agent_session_id; the
-- transcript column is the render copy.

CREATE TABLE IF NOT EXISTS agent_conversation (
    id                UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id           BIGINT       NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    connection_id     VARCHAR(255),                       -- nullable: chat with no active connection
    agent_session_id  VARCHAR(255) NOT NULL,              -- the agent session id (context pointer)
    title             VARCHAR(255),
    transcript        JSONB,                              -- mirrored rendered messages for cross-device display
    archived          BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at        TIMESTAMP    NOT NULL DEFAULT now(),
    updated_at        TIMESTAMP    NOT NULL DEFAULT now(),
    last_message_at   TIMESTAMP
);

-- List "my conversations for this connection, newest first".
CREATE INDEX IF NOT EXISTS idx_agent_conv_user_conn
    ON agent_conversation (user_id, connection_id, last_message_at DESC);

CREATE INDEX IF NOT EXISTS idx_agent_conv_session
    ON agent_conversation (agent_session_id);
