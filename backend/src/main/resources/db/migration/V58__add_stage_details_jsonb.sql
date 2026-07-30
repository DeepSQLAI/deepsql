-- Add stage_details JSONB column for per-stage metadata (schemas scanned, tables sampled, etc.)
ALTER TABLE connection_init_status ADD COLUMN IF NOT EXISTS stage_details jsonb DEFAULT '{}';
ALTER TABLE connection_init_history ADD COLUMN IF NOT EXISTS stage_details jsonb DEFAULT '{}';
