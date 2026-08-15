-- Server-owned "is a generation turn in flight" marker per dashboard, so a
-- reload mid-generation can distinguish "still working" from "answer's ready"
-- without depending on the SSE connection that started it staying alive.
-- Idempotent: schema is Hibernate-managed here, so these may already exist.
ALTER TABLE saved_dashboards ADD COLUMN IF NOT EXISTS generation_status VARCHAR(16) NOT NULL DEFAULT 'IDLE';
ALTER TABLE saved_dashboards ADD COLUMN IF NOT EXISTS generation_started_at TIMESTAMP;
