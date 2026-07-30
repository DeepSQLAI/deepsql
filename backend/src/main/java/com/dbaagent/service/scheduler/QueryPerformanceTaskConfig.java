package com.dbaagent.service.scheduler;

import com.dbaagent.service.QueryPerformanceService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class QueryPerformanceTaskConfig {

    @Bean
    Task<Void> queryPerformanceAnalysisTask(QueryPerformanceService service) {
        return Tasks.recurring("query-performance-analysis",
                Schedules.cron("0 0 * * * *"))
            .execute((inst, ctx) -> service.analyzePerformance());
    }
}
