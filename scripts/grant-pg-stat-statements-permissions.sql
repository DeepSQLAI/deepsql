-- Grant pg_stat_statements permissions to a database user
-- Run this as a superuser (postgres) on the target database
-- Replace 'your_db_user' with the actual username from your connection

-- Step 1: Ensure extension exists (if not already created)
CREATE EXTENSION IF NOT EXISTS pg_stat_statements;

-- Step 2: Grant permissions (PostgreSQL 13+)
-- This grants the pg_read_all_stats role which includes access to pg_stat_statements
DO $$
BEGIN
  IF EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'pg_read_all_stats') THEN
    -- PostgreSQL 13+ - use role-based permission
    GRANT pg_read_all_stats TO "your_db_user";
    RAISE NOTICE 'Granted pg_read_all_stats role to your_db_user';
  ELSE
    -- PostgreSQL 12 and earlier - direct grant
    GRANT SELECT ON pg_stat_statements TO "your_db_user";
    GRANT EXECUTE ON FUNCTION pg_stat_statements_reset() TO "your_db_user";
    RAISE NOTICE 'Granted SELECT on pg_stat_statements to your_db_user (legacy method)';
  END IF;
END
$$;

-- Verify: Test as the user (replace with your username)
-- psql -U your_db_user -d your_database -c "SELECT 1 FROM pg_stat_statements LIMIT 1;"
