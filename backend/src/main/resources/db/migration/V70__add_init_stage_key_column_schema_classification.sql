-- Add KEY_COLUMN_ANALYSIS and SCHEMA_CLASSIFICATION to the init stage check constraint
ALTER TABLE connection_init_status
    DROP CONSTRAINT connection_init_status_current_stage_check;

ALTER TABLE connection_init_status
    ADD CONSTRAINT connection_init_status_current_stage_check
    CHECK (current_stage IN (
        'SCHEMA_SCAN', 'DATA_SAMPLING', 'AI_DESCRIPTION', 'RAG_EMBEDDING',
        'BRAIN_ANALYSIS', 'KEY_COLUMN_ANALYSIS', 'SCHEMA_CLASSIFICATION',
        'COMPLETED', 'FAILED'
    ));

-- Also update connection_init_history if it has a similar constraint
DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conname = 'connection_init_history_final_stage_check'
    ) THEN
        ALTER TABLE connection_init_history
            DROP CONSTRAINT connection_init_history_final_stage_check;
        ALTER TABLE connection_init_history
            ADD CONSTRAINT connection_init_history_final_stage_check
            CHECK (final_stage IN (
                'SCHEMA_SCAN', 'DATA_SAMPLING', 'AI_DESCRIPTION', 'RAG_EMBEDDING',
                'BRAIN_ANALYSIS', 'KEY_COLUMN_ANALYSIS', 'SCHEMA_CLASSIFICATION',
                'COMPLETED', 'FAILED'
            ));
    END IF;
END $$;
