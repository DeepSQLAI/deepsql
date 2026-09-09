package com.dbaagent.service.scheduler;

import com.dbaagent.service.SlackDailyDigestService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Scheduled task configuration for digest delivery.
 *
 * <p>Ticks frequently (default: every minute) and delegates to
 * {@link SlackDailyDigestService#processDigestTick()} which:
 * <ul>
 *   <li>Honors each enabled {@code UserDigestPreference.cronExpression} in that
 *       user's timezone when preferences exist</li>
 *   <li>Falls back to the legacy singleton broadcast gated by
 *       {@code slack.daily-digest.cron} when no enabled preferences exist</li>
 * </ul>
 *
 * <p>The global property {@code slack.daily-digest.cron} remains the default
 * schedule for preferences that leave {@code cronExpression} blank, and the
 * legacy broadcast schedule when the system is still in singleton mode.
 */
@Configuration
@Profile("!test")
@Slf4j
public class SlackDailyDigestTaskConfig {

    @Bean
    Task<Void> slackDailyDigestTask(
            SlackDailyDigestService service,
            @Value("${slack.daily-digest.tick-cron:0 * * * * *}") String tickCron) {
        return Tasks.recurring("slack-daily-digest", Schedules.cron(tickCron))
            .execute((inst, ctx) -> {
                log.debug("Digest scheduler tick");
                service.processDigestTick();
            });
    }
}
