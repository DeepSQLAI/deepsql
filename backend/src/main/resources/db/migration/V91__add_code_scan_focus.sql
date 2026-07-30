-- Intent-driven scanning: persist user-supplied + ambiguity-derived focus on
-- both sources and jobs so the UI can detect "focus changed since last scan"
-- and supersession logic can match runs by hash.

ALTER TABLE code_scan_source
    ADD COLUMN IF NOT EXISTS focus_text       TEXT,
    ADD COLUMN IF NOT EXISTS focus_hash       VARCHAR(64),
    ADD COLUMN IF NOT EXISTS focus_updated_at TIMESTAMP;

ALTER TABLE code_scan_job
    ADD COLUMN IF NOT EXISTS focus_text TEXT,
    ADD COLUMN IF NOT EXISTS focus_hash VARCHAR(64);

CREATE INDEX IF NOT EXISTS idx_code_scan_source_focus_hash
    ON code_scan_source(connection_id, focus_hash);
