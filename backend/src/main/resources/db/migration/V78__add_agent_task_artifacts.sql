ALTER TABLE agent_runs
    ADD COLUMN plan_tasks_json TEXT;

ALTER TABLE agent_run_steps
    ADD COLUMN task_id VARCHAR(255),
    ADD COLUMN step_kind VARCHAR(64),
    ADD COLUMN depends_on_json TEXT,
    ADD COLUMN executed_sql_json TEXT,
    ADD COLUMN artifacts_json TEXT;
