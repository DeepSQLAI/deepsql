ALTER TABLE chats
    ADD COLUMN IF NOT EXISTS owner_username VARCHAR(255);

CREATE INDEX IF NOT EXISTS idx_chat_owner_connection
    ON chats (owner_username, connection_id);

CREATE INDEX IF NOT EXISTS idx_chat_owner_project
    ON chats (owner_username, project_id);
