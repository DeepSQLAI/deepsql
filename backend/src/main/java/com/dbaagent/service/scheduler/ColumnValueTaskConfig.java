package com.dbaagent.service.scheduler;

import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class ColumnValueTaskConfig {

    @Bean
    Task<Void> columnValuesBackgroundRefreshTask(
            ColumnValueCollectionService service,
            @Value("${spring.ai.column-values.background-sampling.cron:0 0 3 * * *}") String cron) {
        return Tasks.recurring("column-values-background-refresh",
                Schedules.cron(cron))
            .execute((inst, ctx) -> service.backgroundRefreshColumnValues());
    }
}
