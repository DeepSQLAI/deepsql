package com.dbaagent.service.scheduler;

import com.dbaagent.service.IngestionJobService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration
@Profile("!test")
public class IngestionJobMonitorTaskConfig {

    @Bean
    Task<Void> ingestionJobStaleCheckTask(IngestionJobService service) {
        return Tasks.recurring("ingestion-job-stale-check",
                Schedules.fixedDelay(Duration.ofSeconds(60)))
            .execute((inst, ctx) -> service.checkStaleJobs());
    }
}
