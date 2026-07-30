DO $$
BEGIN
    CREATE EXTENSION IF NOT EXISTS vector;
    RAISE NOTICE 'pgvector extension enabled during fresh vault DB initialization';
EXCEPTION WHEN OTHERS THEN
    RAISE WARNING 'pgvector extension could not be enabled during DB initialization: %', SQLERRM;
END $$;
