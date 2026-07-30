-- Clean up legacy key column analysis data from before database filtering fix
-- This migration removes old system schema data that was incorrectly analyzed
-- for connection 6be6ae30-ba7e-4887-b72e-1a95da01f926 (idb_database)

-- Delete old key column analysis data
DELETE FROM key_column_analysis
WHERE connection_id = '6be6ae30-ba7e-4887-b72e-1a95da01f926';

-- Delete old anti-pattern data
DELETE FROM column_anti_pattern
WHERE connection_id = '6be6ae30-ba7e-4887-b72e-1a95da01f926';

-- Delete old composite index recommendations
DELETE FROM composite_index_recommendation
WHERE connection_id = '6be6ae30-ba7e-4887-b72e-1a95da01f926';

-- Log completion
DO $$
BEGIN
    RAISE NOTICE 'Cleaned up legacy key column analysis data for connection 6be6ae30-ba7e-4887-b72e-1a95da01f926';
END $$;
