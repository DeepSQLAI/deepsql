-- ============================================================================
-- V36: Spring AI Migration - Feedback Learning and Column Value Cache
-- ============================================================================
-- Purpose: Support cross-session memory via feedback persistence and
--          automatic column value embeddings for better LLM context
-- ============================================================================

-- Chat feedback for learning from user corrections and teachings
CREATE TABLE IF NOT EXISTS chat_feedback (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    chat_id VARCHAR(36),
    message_id VARCHAR(36),

    -- Feedback type: CORRECTION, TEACHING, THUMBS_UP, THUMBS_DOWN, COLUMN_VALUES
    type VARCHAR(50) NOT NULL,

    -- Original context
    original_response TEXT,
    user_correction TEXT,

    -- What the system learned (for display/audit)
    learned_content TEXT,

    -- Formatted for embedding into vector store
    embedding_content TEXT,

    -- Has this been added to vector store?
    embedded BOOLEAN DEFAULT FALSE,

    -- Metadata
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by VARCHAR(255),

    -- For tracking which embeddings to update/delete
    embedding_id VARCHAR(255)
);

-- Indexes for chat_feedback
CREATE INDEX IF NOT EXISTS idx_feedback_connection ON chat_feedback(connection_id);
CREATE INDEX IF NOT EXISTS idx_feedback_embedded ON chat_feedback(connection_id, embedded);
CREATE INDEX IF NOT EXISTS idx_feedback_type ON chat_feedback(connection_id, type);
CREATE INDEX IF NOT EXISTS idx_feedback_chat ON chat_feedback(chat_id);
CREATE INDEX IF NOT EXISTS idx_feedback_created ON chat_feedback(created_at DESC);

-- Column value cache for low-cardinality columns
-- Auto-populated during Key Column Analysis
CREATE TABLE IF NOT EXISTS column_value_cache (
    id VARCHAR(36) PRIMARY KEY,
    connection_id VARCHAR(36) NOT NULL,
    table_name VARCHAR(255) NOT NULL,
    column_name VARCHAR(255) NOT NULL,

    -- Cardinality info
    distinct_count BIGINT,
    total_rows BIGINT,

    -- Cached values (JSON arrays)
    sample_values TEXT,  -- First N values for preview
    all_values TEXT,     -- All values if cardinality < threshold

    -- Classification
    is_low_cardinality BOOLEAN DEFAULT FALSE,
    data_type VARCHAR(100),

    -- Embedding status
    embedded BOOLEAN DEFAULT FALSE,
    embedding_id VARCHAR(255),

    -- Timestamps
    analyzed_at TIMESTAMP,
    embedded_at TIMESTAMP,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP,

    -- Unique constraint
    CONSTRAINT uk_column_value_cache UNIQUE(connection_id, table_name, column_name)
);

-- Indexes for column_value_cache
CREATE INDEX IF NOT EXISTS idx_colval_connection ON column_value_cache(connection_id);
CREATE INDEX IF NOT EXISTS idx_colval_low_card ON column_value_cache(connection_id, is_low_cardinality);
CREATE INDEX IF NOT EXISTS idx_colval_embedded ON column_value_cache(connection_id, embedded);
CREATE INDEX IF NOT EXISTS idx_colval_table ON column_value_cache(connection_id, table_name);

-- Add feedback_count to chat_messages for tracking
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS thumbs_up_count INTEGER DEFAULT 0;
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS thumbs_down_count INTEGER DEFAULT 0;
ALTER TABLE chat_messages ADD COLUMN IF NOT EXISTS has_correction BOOLEAN DEFAULT FALSE;
