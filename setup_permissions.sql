-- Grant required privileges for DBA Agent

-- Basic read access to all tables
GRANT SELECT ON ALL TABLES IN SCHEMA public TO postgres;
GRANT SELECT ON ALL SEQUENCES IN SCHEMA public TO postgres;

-- Access to system views for monitoring
-- Note: postgres is usually a superuser, so this might be redundant or fail if already granted, but harmless to try.
DO $$
BEGIN
  IF NOT EXISTS (SELECT FROM pg_catalog.pg_roles WHERE rolname = 'postgres' AND rolsuper = true) THEN
    GRANT pg_read_all_stats TO postgres;
  END IF;
END
$$;

-- Enable pg_stat_statements extension (if not already enabled)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- For future tables
ALTER DEFAULT PRIVILEGES IN SCHEMA public
  GRANT SELECT ON TABLES TO postgres;
