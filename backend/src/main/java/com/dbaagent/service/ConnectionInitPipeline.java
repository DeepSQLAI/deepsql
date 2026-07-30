package com.dbaagent.service;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * @deprecated Brain init is now handled by db-scheduler via
 * {@link com.dbaagent.service.scheduler.BrainInitSchedulerService}.
 * This class is retained as a stub until Phase 4 removes all references.
 */
@Service
@Deprecated
@Slf4j
public class ConnectionInitPipeline {

    /**
     * @deprecated Use {@link com.dbaagent.service.scheduler.BrainInitSchedulerService#cancelInit} instead.
     */
    @Deprecated
    public void cancelInit(String connectionId) {
        log.warn("cancelInit called on deprecated ConnectionInitPipeline — use BrainInitSchedulerService");
    }

    /**
     * @deprecated Use {@link com.dbaagent.service.scheduler.BrainInitSchedulerService#scheduleInit} instead.
     */
    @Deprecated
    public void runInit(String connectionId) {
        log.warn("runInit called on deprecated ConnectionInitPipeline — use BrainInitSchedulerService");
    }
}
