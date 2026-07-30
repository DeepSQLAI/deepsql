package com.dbaagent.service.scheduler;

import com.dbaagent.service.PerformanceRecommendationRefreshScheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class PerformanceRecommendationTaskConfig {

    @Bean
    Task<Void> refreshPerformanceRecommendationsTask(
        PerformanceRecommendationRefreshScheduler service,
        @Value("${performance.recommendations.refresh.cron:0 0 */6 * * *}") String cron
    ) {
        return Tasks.recurring("performance-recommendations-refresh",
                Schedules.cron(cron))
            .execute((inst, ctx) -> service.refreshAllConnections());
    }
}
