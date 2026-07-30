package com.dbaagent.service.scheduler;

import com.dbaagent.service.GrowthDataCleanupService;
import com.dbaagent.service.IngestionJobService;
import com.dbaagent.service.QueryPerformanceService;
import com.dbaagent.service.SecurityCleanupService;
import com.dbaagent.service.TrainingJobCleanupService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class CleanupTaskConfig {

    @Bean
    Task<Void> growthDataCleanupTask(GrowthDataCleanupService service) {
        return Tasks.recurring("growth-data-cleanup",
                Schedules.cron("0 0 2 * * *"))
            .execute((inst, ctx) -> service.cleanupOldData());
    }

    @Bean
    Task<Void> trainingHistoryCleanupTask(
            TrainingJobCleanupService service,
            @Value("${training.history.cleanup.cron:0 30 2 * * *}") String cron) {
        return Tasks.recurring("training-history-cleanup",
                Schedules.cron(cron))
            .execute((inst, ctx) -> service.cleanupTrainingHistory());
    }

    @Bean
    Task<Void> queryPerformanceCleanupTask(QueryPerformanceService service) {
        return Tasks.recurring("query-performance-cleanup",
                Schedules.cron("0 0 3 * * *"))
            .execute((inst, ctx) -> service.cleanupOldData());
    }

    @Bean
    Task<Void> ingestionJobCleanupTask(IngestionJobService service) {
        return Tasks.recurring("ingestion-job-cleanup",
                Schedules.cron("0 0 3 * * *"))
            .execute((inst, ctx) -> service.cleanupOldJobs());
    }

    @Bean
    Task<Void> securityLogCleanupTask(SecurityCleanupService service) {
        return Tasks.recurring("security-log-cleanup",
                Schedules.cron("0 0 4 * * *"))
            .execute((inst, ctx) -> service.cleanupOldLogs());
    }
}
