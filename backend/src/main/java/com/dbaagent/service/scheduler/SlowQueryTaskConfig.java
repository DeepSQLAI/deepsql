package com.dbaagent.service.scheduler;

import com.dbaagent.service.SlowQueryDailyAnalysisService;
import com.dbaagent.service.SlowQueryLineageBackfillService;
import com.github.kagkarlsson.scheduler.task.Task;
import com.github.kagkarlsson.scheduler.task.helper.Tasks;
import com.github.kagkarlsson.scheduler.task.schedule.Schedules;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

/**
 * Scheduled tasks for the slow-query subsystem.
 *
 * <p>Before the 2026-05 unification this also registered a {@code
 * slow-log-ingestion-poll} task that fired every 60 seconds against every
 * configured log source. That short-period poll has been removed:
 * {@link SlowQueryDailyAnalysisService} is now the single entry point that
 * drives slow-log ingestion (and everything downstream — fingerprints,
 * regression, attribution, retention). One daily cycle per connection,
 * one set of vault writes, no parallel paths.
 *
 * <p>Manual triggers (UI "Analyze now", the {@code analyze_slow_queries}
 * MCP tool, batch ingestion APIs) still call into {@code SlowLogIngestionService}
 * directly — they just no longer have a recurring scheduler counterpart.
 */
@Configuration
@Profile("!test")
public class SlowQueryTaskConfig {

    @Bean
    Task<Void> slowQueryLineageBackfillTask(
            SlowQueryLineageBackfillService service,
            @Value("${slow-query.lineage.backfill.cron:0 15 2 * * *}") String cron) {
        return Tasks.recurring("slow-query-lineage-backfill",
                Schedules.cron(cron))
            .execute((inst, ctx) -> service.backfillSlowQueryLineage());
    }

    /**
     * Daily slow-query analysis. Once per day per connection: pull anything
     * new from the configured log source, persist it, decompose into the
     * 30-day {@code slow_query_run} time series, attribute samples per
     * customer, and apply retention. Default 01:30 — runs before the
     * lineage backfill (02:15) so the backfill sees today's fresh data.
     */
    @Bean
    Task<Void> slowQueryDailyAnalysisTask(
            SlowQueryDailyAnalysisService service,
            @Value("${slow-query.daily-analysis.cron:0 30 1 * * *}") String cron) {
        return Tasks.recurring("slow-query-daily-analysis",
                Schedules.cron(cron))
            .execute((inst, ctx) -> service.runDailyAnalysis());
    }
}
