ALTER TABLE semantic_table_model
    ADD COLUMN IF NOT EXISTS temporal_semantics jsonb;
