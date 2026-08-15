package com.dbaagent.service.scheduler;

import com.dbaagent.model.DashboardAlert;
import com.dbaagent.service.DashboardAlertService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Ticks every minute and evaluates whichever dashboard alerts are actually due
 * (each alert carries its own check_interval_minutes — see
 * DashboardAlertRepository.findDue) rather than registering one scheduled task
 * per alert, which would mean re-registering db-scheduler tasks on every
 * create/edit/delete. One alert's agent call failing is caught and logged inside
 * DashboardAlertService.evaluate — it never aborts the rest of this tick's batch.
 */
@Configuration
@Profile("!test")
@Slf4j
public class DashboardAlertTaskConfig {

    @Bean
    Task<Void> dashboardAlertTickTask(
            DashboardAlertService alertService,
            @Value("${dashboard.alert.tick-cron:0 * * * * *}") String cron) {
        return Tasks.recurring("dashboard-alert-tick", Schedules.cron(cron))
            .execute((inst, ctx) -> {
                for (DashboardAlert alert : alertService.findDue()) {
                    try {
                        alertService.evaluate(alert.getId());
                    } catch (Exception e) {
                        log.warn("Dashboard alert tick failed for alert {}: {}", alert.getId(), e.getMessage());
                    }
                }
            });
    }
}
