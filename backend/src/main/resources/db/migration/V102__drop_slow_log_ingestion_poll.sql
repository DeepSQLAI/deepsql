-- Drop the orphaned slow-log-ingestion-poll task row.
--
-- The 60-second slow-log ingestion poll has been removed in favour of a
-- single daily entry point (SlowQueryDailyAnalysisService.runDailyAnalysis).
-- Without this cleanup the existing scheduled_tasks row would sit forever
-- as "task definition not found in code" — db-scheduler ignores unknown
-- task names but they clutter the brain-jobs panel and the scheduled_tasks
-- diagnostics view.
DELETE FROM scheduled_tasks
WHERE task_name = 'slow-log-ingestion-poll';
