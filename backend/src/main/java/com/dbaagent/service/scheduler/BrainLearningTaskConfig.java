package com.dbaagent.service.scheduler;

import com.dbaagent.service.brain.BrainLearningScheduler;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

@Configuration
@Profile("!test")
public class BrainLearningTaskConfig {

    @Bean
    Task<Void> collectWorkloadMetricsTask(
            BrainLearningScheduler scheduler,
            @Value("${brain.v2.learning.metrics-cron:0 0/15 * * * *}") String cron) {
        return Tasks.recurring("brain-collect-workload-metrics",
                Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.collectWorkloadMetrics());
    }

    @Bean
    Task<Void> updateWorkloadCharacterizationTask(
            BrainLearningScheduler scheduler,
            @Value("${brain.v2.learning.characterization-cron:0 0 0/4 * * *}") String cron) {
        return Tasks.recurring("brain-update-workload-characterization",
                Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.updateWorkloadCharacterization());
    }

    @Bean
    Task<Void> updateKnobRankingsTask(
            BrainLearningScheduler scheduler,
            @Value("${brain.v2.learning.knob-ranking-cron:0 0 2 * * *}") String cron) {
        return Tasks.recurring("brain-update-knob-rankings",
                Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.updateKnobRankings());
    }

    @Bean
    Task<Void> refreshColumnStatisticsTask(
            BrainLearningScheduler scheduler,
            @Value("${brain.v2.learning.statistics-refresh-cron:0 0 3 * * SUN}") String cron) {
        return Tasks.recurring("brain-refresh-column-statistics",
                Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.refreshColumnStatistics());
    }

    @Bean
    Task<Void> checkEstimationAccuracyTask(
            BrainLearningScheduler scheduler,
            @Value("${brain.v2.learning.accuracy-check-cron:0 0 4 * * *}") String cron) {
        return Tasks.recurring("brain-check-estimation-accuracy",
                Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.checkEstimationAccuracy());
    }

    @Bean
    Task<Void> calculateReadinessTask(
            BrainLearningScheduler scheduler,
            @Value("${brain.v2.learning.readiness-check-cron:0 0 6 * * *}") String cron) {
        return Tasks.recurring("brain-calculate-readiness",
                Schedules.cron(cron))
            .execute((inst, ctx) -> scheduler.calculateReadiness());
    }
}
