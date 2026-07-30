package com.dbaagent.service.scheduler;

import com.dbaagent.service.TableGrowthMonitoringService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class GrowthMonitoringTaskConfig {

    @Bean
    Task<Void> captureMinuteSnapshotsTask(TableGrowthMonitoringService service) {
        return Tasks.recurring("growth-minute-snapshots",
                Schedules.cron("0 * * * * *"))
            .execute((inst, ctx) -> service.captureMinuteSnapshots());
    }

    @Bean
    Task<Void> captureHourlySnapshotsTask(TableGrowthMonitoringService service) {
        return Tasks.recurring("growth-hourly-snapshots",
                Schedules.cron("0 0 * * * *"))
            .execute((inst, ctx) -> service.captureHourlySnapshots());
    }

    @Bean
    Task<Void> captureDailySnapshotsTask(TableGrowthMonitoringService service) {
        return Tasks.recurring("growth-daily-snapshots",
                Schedules.cron("0 0 0 * * *"))
            .execute((inst, ctx) -> service.captureDailySnapshots());
    }

    @Bean
    Task<Void> correlateAnomaliesTask(TableGrowthMonitoringService service) {
        return Tasks.recurring("growth-correlate-anomalies",
                Schedules.cron("0 0 0/6 * * *"))
            .execute((inst, ctx) -> service.correlateAnomaliesWithEvents());
    }
}
