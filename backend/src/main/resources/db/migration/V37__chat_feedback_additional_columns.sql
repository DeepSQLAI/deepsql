-- ============================================================================
-- V37: Chat Feedback Additional Columns
-- ============================================================================
-- Purpose: Add missing columns for feedback system (table_name, column_name,
--          user_message, sql, feedback_text)
-- ============================================================================

-- Add table_name column for table-specific feedback
ALTER TABLE chat_feedback ADD COLUMN IF NOT EXISTS table_name VARCHAR(255);

-- Add column_name column for column-specific corrections/teachings
ALTER TABLE chat_feedback ADD COLUMN IF NOT EXISTS column_name VARCHAR(255);

-- Add user_message column to store the original user question
ALTER TABLE chat_feedback ADD COLUMN IF NOT EXISTS user_message TEXT;

-- Add sql column to store the SQL that was generated
ALTER TABLE chat_feedback ADD COLUMN IF NOT EXISTS sql TEXT;

-- Add feedback_text column for textual feedback (corrections, teachings)
ALTER TABLE chat_feedback ADD COLUMN IF NOT EXISTS feedback_text TEXT;

-- Add ai_response column (separate from original_response for clarity)
ALTER TABLE chat_feedback ADD COLUMN IF NOT EXISTS ai_response TEXT;

-- Create indexes for table/column lookups
CREATE INDEX IF NOT EXISTS idx_feedback_table ON chat_feedback(connection_id, table_name);
CREATE INDEX IF NOT EXISTS idx_feedback_column ON chat_feedback(connection_id, table_name, column_name);
