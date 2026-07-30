ALTER TABLE query_optimization_candidate_run
    ADD COLUMN IF NOT EXISTS row_count_result BIGINT;

COMMENT ON COLUMN query_optimization_candidate_run.row_count_result IS 'Actual row count returned by the candidate query during benchmarking.';
