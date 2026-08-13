-- Hand-maintained changelog (no Flyway runtime). BrainInitSchemaCompatibilityInitializer
-- also realigns these CHECKs from InitStage.values() at boot.
ALTER TABLE connection_init_status
    DROP CONSTRAINT IF EXISTS connection_init_status_current_stage_check;

ALTER TABLE connection_init_status
    ADD CONSTRAINT connection_init_status_current_stage_check
    CHECK (current_stage IN (
        'SCHEMA_SCAN', 'DATA_SAMPLING', 'KEY_COLUMN_ANALYSIS',
        'COLUMN_VALUE_COLLECTION', 'INFERRED_RELATIONSHIPS',
        'SCHEMA_CLASSIFICATION', 'AI_DESCRIPTION', 'RAG_EMBEDDING',
        'BRAIN_ANALYSIS', 'SEMANTIC_MODELING', 'NEEDS_ATTENTION',
        'COMPLETED', 'FAILED'
    ));

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
                'SCHEMA_SCAN', 'DATA_SAMPLING', 'KEY_COLUMN_ANALYSIS',
                'COLUMN_VALUE_COLLECTION', 'INFERRED_RELATIONSHIPS',
                'SCHEMA_CLASSIFICATION', 'AI_DESCRIPTION', 'RAG_EMBEDDING',
                'BRAIN_ANALYSIS', 'SEMANTIC_MODELING', 'NEEDS_ATTENTION',
                'COMPLETED', 'FAILED'
            ));
    END IF;
END $$;
