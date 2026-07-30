-- Allow a single recommendation row to represent either a CREATE or a DROP
-- action, so unused-index removals flow through the same recurrence ranking,
-- top-N endpoint, and CLI tool as missing-index creations.
--
-- Default 'CREATE_INDEX' so every pre-existing row keeps its current meaning.

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS kind VARCHAR(20) NOT NULL DEFAULT 'CREATE_INDEX';

CREATE INDEX IF NOT EXISTS idx_rec_kind
    ON index_recommendations (connection_id, status, kind);
