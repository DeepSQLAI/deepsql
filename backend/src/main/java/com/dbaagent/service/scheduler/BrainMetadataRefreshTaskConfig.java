package com.dbaagent.service.scheduler;

import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class BrainMetadataRefreshTaskConfig {

    @Bean
    Task<Void> refreshBrainMetadataTask(
        BrainMetadataRefreshScheduler scheduler,
        @Value("${brain.init.refresh.cron:0 30 2 * * *}") String cron
    ) {
        return Tasks.recurring("brain-refresh-metadata-lifecycle", Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.refreshStaleConnections());
    }
}
