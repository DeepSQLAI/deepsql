-- Dashboard sharing: an opt-in, revocable public link per dashboard.
-- share_token is a random URL-safe id used by the public view/query endpoints;
-- is_public gates whether the token currently resolves (revoke = set false).
-- Idempotent: schema is Hibernate-managed here, so these may already exist.
ALTER TABLE saved_dashboards ADD COLUMN IF NOT EXISTS share_token VARCHAR(64);
ALTER TABLE saved_dashboards ADD COLUMN IF NOT EXISTS is_public BOOLEAN NOT NULL DEFAULT FALSE;
CREATE UNIQUE INDEX IF NOT EXISTS idx_saved_dashboards_share_token
    ON saved_dashboards (share_token) WHERE share_token IS NOT NULL;
