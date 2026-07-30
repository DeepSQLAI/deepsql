-- V63: Add cancel_requested flag and active_run_id for distributed init mechanism.
-- cancel_requested: Replaces in-memory AtomicBoolean cancel flags that don't survive VM crashes.
-- active_run_id: UUID per reinit attempt. executeStage validates its task's runId matches this
-- value, rejecting stale tasks from cancelled/superseded runs.
ALTER TABLE connection_init_status
    ADD COLUMN IF NOT EXISTS cancel_requested BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE connection_init_status
    ADD COLUMN IF NOT EXISTS active_run_id UUID;

-- Backfill: mark any existing non-terminal rows as FAILED so they don't block
-- scheduleInit() after upgrade. These were orphaned by the old in-memory system
-- and have no queued db-scheduler task to resume them.
UPDATE connection_init_status
SET current_stage = 'FAILED',
    error_message = 'Marked failed during V63 migration (pre-db-scheduler upgrade)',
    completed_at = NOW()
WHERE current_stage NOT IN ('COMPLETED', 'FAILED');
