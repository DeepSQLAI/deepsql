-- V59: Widen SPRING_AI_CHAT_MEMORY.conversation_id from VARCHAR(36) to VARCHAR(255)
--
-- Spring AI's default schema uses VARCHAR(36) which only fits UUID-formatted IDs.
-- Longer conversation IDs (e.g. descriptive test IDs, prefixed IDs) cause
-- DataIntegrityViolationException on INSERT.
--
-- This migration also takes ownership of the table creation from Spring AI's
-- initialize-schema=always, allowing us to set initialize-schema=never.

-- Create the table if it doesn't exist yet (fresh deployments)
CREATE TABLE IF NOT EXISTS SPRING_AI_CHAT_MEMORY (
    conversation_id VARCHAR(255) NOT NULL,
    content TEXT NOT NULL,
    type VARCHAR(10) NOT NULL CHECK (type IN ('USER', 'ASSISTANT', 'SYSTEM', 'TOOL')),
    "timestamp" TIMESTAMP NOT NULL
);

CREATE INDEX IF NOT EXISTS SPRING_AI_CHAT_MEMORY_CONVERSATION_ID_TIMESTAMP_IDX
ON SPRING_AI_CHAT_MEMORY(conversation_id, "timestamp");

-- Widen the column for existing deployments where Spring AI already created it
ALTER TABLE SPRING_AI_CHAT_MEMORY ALTER COLUMN conversation_id TYPE VARCHAR(255);
