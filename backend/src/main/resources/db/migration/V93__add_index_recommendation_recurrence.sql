-- Recurrence tracking for index recommendations.
--
-- The previous refresh cycle wiped PENDING rows and regenerated from scratch,
-- losing the signal that a (table, columns) pair keeps recurring across many
-- slow-query log fetches. Top-N CLI recommendations need that signal: a
-- candidate that appears in 12 of the last 14 refresh cycles is much higher
-- confidence than one that appeared once.
--
-- After this migration the service accumulates: it increments occurrence_count
-- and refreshes last_seen_at when a (connection_id, table_name, column_names)
-- candidate is observed again, and ages out rows that have not been seen for
-- a configurable staleness window.

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS occurrence_count INTEGER NOT NULL DEFAULT 1;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS first_seen_at TIMESTAMP;

ALTER TABLE index_recommendations
    ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMP;

UPDATE index_recommendations
SET first_seen_at = COALESCE(first_seen_at, created_at),
    last_seen_at  = COALESCE(last_seen_at, updated_at, created_at)
WHERE first_seen_at IS NULL OR last_seen_at IS NULL;

-- Ranking index for the top-N CLI query:
--   WHERE connection_id = ? AND status = 'PENDING'
--   ORDER BY priority, occurrence_count DESC, estimated_impact DESC, last_seen_at DESC
CREATE INDEX IF NOT EXISTS idx_rec_top_ranked
    ON index_recommendations (connection_id, status, priority, occurrence_count DESC, last_seen_at DESC);
