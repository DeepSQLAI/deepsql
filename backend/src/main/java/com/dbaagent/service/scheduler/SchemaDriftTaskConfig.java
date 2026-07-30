package com.dbaagent.service.scheduler;

import com.dbaagent.service.SchemaChangeTrackingService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class SchemaDriftTaskConfig {

    /**
     * Runs once a day at 03:00 UTC (configurable via
     * {@code schema.drift.check.cron}).
     *
     * <p>Was a 5-minute fixed-delay before — wasteful because
     * {@link SchemaChangeTrackingService#scheduledDriftCheck} filters by
     * each per-connection config's {@code next_check_at}, and the default
     * per-connection frequency is already 1440 min (daily). The 5-min poll
     * was 288 wake-ups per day to find "nothing due 287 of those times".
     *
     * <p>03:00 sits between the other daily brain/slow-query jobs (knob
     * rankings at 02:00, refresh-metadata at 02:30, estimation accuracy
     * at 04:00, calculate-readiness at 06:00).
     */
    @Bean
    Task<Void> scheduledDriftCheckTask(
            SchemaChangeTrackingService service,
            @Value("${schema.drift.check.cron:0 0 3 * * *}") String cron) {
        return Tasks.recurring("schema-drift-check",
                Schedules.cron(cron))
            .execute((inst, ctx) -> service.scheduledDriftCheck());
    }
}
