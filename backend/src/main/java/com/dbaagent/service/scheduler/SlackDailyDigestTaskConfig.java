package com.dbaagent.service.scheduler;

import com.dbaagent.service.SlackDailyDigestService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class SlackDailyDigestTaskConfig {

    @Bean
    Task<Void> slackDailyDigestTask(
            SlackDailyDigestService service,
            @Value("${slack.daily-digest.cron:0 0 9 * * *}") String cron) {
        return Tasks.recurring("slack-daily-digest", Schedules.cron(cron))
            .execute((inst, ctx) -> service.sendDailyDigest());
    }
}
