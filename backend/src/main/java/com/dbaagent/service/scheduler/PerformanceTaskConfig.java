package com.dbaagent.service.scheduler;

import com.dbaagent.service.PerformanceInsightsService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class PerformanceTaskConfig {

    @Bean
    Task<Void> collectPerformanceSnapshotsTask(PerformanceInsightsService service) {
        return Tasks.recurring("perf-collect-snapshots",
                Schedules.cron("0 0/5 * * * *"))
            .execute((inst, ctx) -> service.collectPerformanceSnapshots());
    }

    @Bean
    Task<Void> cleanupOldSnapshotsTask(PerformanceInsightsService service) {
        return Tasks.recurring("perf-cleanup-old-snapshots",
                Schedules.cron("0 0 2 * * *"))
            .execute((inst, ctx) -> service.cleanupOldSnapshots());
    }
}
