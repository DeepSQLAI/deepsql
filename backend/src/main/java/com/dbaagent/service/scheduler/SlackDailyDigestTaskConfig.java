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
 * Scheduled task configuration for daily digest delivery.
 *
 * <p>Uses the hybrid mode: per-user personalized delivery when UserDigestPreference
 * rows exist; legacy channel-broadcast when none exist.
 *
 * <h3>PR3 Changes</h3>
 * <ul>
 *   <li>Now calls {@code sendDailyDigestHybrid()} which routes to per-user or legacy
 *       based on whether any preferences are configured</li>
 *   <li>Two users with different personas on the same connection get different digests</li>
 *   <li>Fallback to legacy broadcast if no per-user preferences exist for a connection</li>
 * </ul>
 */
@Configuration
@Profile("!test")
@Slf4j
public class SlackDailyDigestTaskConfig {

    @Bean
    Task<Void> slackDailyDigestTask(
            SlackDailyDigestService service,
            @Value("${slack.daily-digest.cron:0 0 9 * * *}") String cron) {
        return Tasks.recurring("slack-daily-digest", Schedules.cron(cron))
            .execute((inst, ctx) -> {
                log.info("Starting scheduled daily digest delivery");
                service.sendDailyDigestHybrid();
                log.info("Completed scheduled daily digest delivery");
            });
    }
}
