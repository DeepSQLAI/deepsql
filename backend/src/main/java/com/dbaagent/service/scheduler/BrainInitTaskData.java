package com.dbaagent.service.scheduler;

import com.dbaagent.model.InitStage;

/**
 * Immutable task data for Brain initialization stages.
 * Serialized to JSON in db-scheduler's task_data column.
 *
 * @param connectionId  the database connection to initialize
 * @param currentStage  which pipeline stage to execute
 * @param attemptCount  how many times this stage has been attempted (for retry tracking)
 * @param runId         unique ID per reinit attempt (prevents instance ID collisions)
 */
public record BrainInitTaskData(
    String connectionId,
    InitStage currentStage,
    int attemptCount,
    String runId
) {
    public BrainInitTaskData withStage(InitStage nextStage) {
        return new BrainInitTaskData(connectionId, nextStage, 0, runId);
    }

    public BrainInitTaskData withIncrementedAttempt() {
        return new BrainInitTaskData(connectionId, currentStage, attemptCount + 1, runId);
    }
}
