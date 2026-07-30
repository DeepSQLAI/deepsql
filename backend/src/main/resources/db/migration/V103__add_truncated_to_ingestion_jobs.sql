-- Graceful size-cap truncation: ingestion jobs that hit slow-query.log.max-bytes
-- now COMPLETE with partial results instead of failing. This flag records that a
-- job's results were truncated so clients can distinguish full vs partial runs.
ALTER TABLE ingestion_jobs
    ADD COLUMN IF NOT EXISTS truncated BOOLEAN NOT NULL DEFAULT FALSE;
