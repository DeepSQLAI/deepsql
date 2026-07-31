-- Placeholder migration.
--
-- This version slot previously held a one-off data cleanup that deleted stale
-- key-column analysis rows for a single, specific connection id from before the
-- database-filtering fix landed. That cleanup was deployment-specific and has no
-- meaning for a fresh install, so it is intentionally a no-op here.
--
-- The version is retained rather than removed so the migration chain stays
-- contiguous and existing deployments keep a stable Flyway history.

DO $$
BEGIN
    RAISE NOTICE 'V25 is a no-op placeholder (legacy per-deployment cleanup removed).';
END $$;
