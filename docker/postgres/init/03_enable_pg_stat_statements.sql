DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS pg_stat_statements;
    RAISE NOTICE 'pg_stat_statements extension enabled during fresh vault DB initialization';
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'pg_stat_statements extension could not be enabled during DB initialization: %', SQLERRM;
END $$;
