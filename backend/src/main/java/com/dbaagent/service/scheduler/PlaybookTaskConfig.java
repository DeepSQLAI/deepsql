package com.dbaagent.service.scheduler;

import com.dbaagent.service.PlaybookSchedulerService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import java.time.Duration;

@Configuration
@Profile("!test")
public class PlaybookTaskConfig {

    @Bean
    Task<Void> playbookSchedulerTask(PlaybookSchedulerService service) {
        return Tasks.recurring("playbook-scheduler",
                Schedules.fixedDelay(Duration.ofMinutes(5)))
            .execute((inst, ctx) -> service.checkScheduledPlaybooks());
    }
}
