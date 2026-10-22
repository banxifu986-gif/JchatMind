BEGIN;

CREATE INDEX idx_builtin_skill_task_running_lease
    ON builtin_skill_execution_task (lease_expires_at, task_id)
    WHERE status = 'RUNNING' AND lease_expires_at IS NOT NULL;

COMMIT;
