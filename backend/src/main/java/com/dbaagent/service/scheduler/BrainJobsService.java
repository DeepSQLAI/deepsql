package com.dbaagent.service.scheduler;

import com.dbaagent.service.SchemaChangeTrackingService;
import com.dbaagent.service.SlowQueryDailyAnalysisService;
import com.dbaagent.service.brain.BrainLearningScheduler;
import com.dbaagent.service.brain.keycolumn.ColumnValueCollectionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrainJobsService {

    private final JdbcTemplate jdbcTemplate;
    private final BrainInitSchedulerService brainInitSchedulerService;
    private final ColumnValueCollectionService columnValueCollectionService;
    private final SchemaChangeTrackingService schemaChangeTrackingService;
    private final BrainLearningScheduler brainLearningScheduler;
    private final SlowQueryDailyAnalysisService slowQueryDailyAnalysisService;

    @Value("${db-scheduler.enabled:true}")
    private boolean dbSchedulerEnabled;

    @Value("${brain.init.refresh.enabled:true}")
    private boolean brainRefreshEnabled;

    @Value("${spring.ai.column-values.background-sampling.enabled:true}")
    private boolean columnValueRefreshEnabled;

    @Value("${brain.v2.learning.enabled:true}")
    private boolean brainLearningEnabled;

    public List<BrainJobStatus> listJobs(String connectionId) {
        Map<String, ScheduledTaskRow> scheduledRows = loadScheduledRows();

        return Arrays.stream(BrainJob.values())
            .sorted(Comparator.comparingInt(BrainJob::displayOrder))
            .map(job -> toStatus(job, connectionId, scheduledRows.get(job.taskName())))
            .toList();
    }

    public ManualRunResult runJob(String connectionId, String jobKey) {
        BrainJob job = BrainJob.fromKey(jobKey)
            .orElseThrow(() -> new IllegalArgumentException("Unknown Brain job `" + jobKey + "`."));

        Runnable task = switch (job) {
            case METADATA_REFRESH ->
                () -> brainInitSchedulerService.planAndScheduleInit(connectionId, false);
            case COLUMN_VALUES_REFRESH ->
                () -> columnValueCollectionService.analyzeColumnValues(connectionId, null);
            case SCHEMA_DRIFT_CHECK ->
                () -> schemaChangeTrackingService.checkDrift(connectionId);
            case WORKLOAD_METRICS ->
                brainLearningScheduler::collectWorkloadMetrics;
            case WORKLOAD_CHARACTERIZATION ->
                brainLearningScheduler::updateWorkloadCharacterization;
            case KNOB_RANKINGS ->
                brainLearningScheduler::updateKnobRankings;
            case COLUMN_STATISTICS ->
                brainLearningScheduler::refreshColumnStatistics;
            case ESTIMATION_ACCURACY ->
                brainLearningScheduler::checkEstimationAccuracy;
            case READINESS ->
                brainLearningScheduler::calculateReadiness;
            case SLOW_QUERY_DAILY_ANALYSIS ->
                slowQueryDailyAnalysisService::runDailyAnalysis;
        };

        Thread.ofVirtual().name("brain-job-" + job.key().toLowerCase(Locale.ROOT)).start(() -> {
            try {
                task.run();
            } catch (Exception e) {
                log.error("Manual Brain job {} failed for connection {}: {}", job.key(), connectionId, e.getMessage(), e);
            }
        });

        return new ManualRunResult(
            job.key(),
            "started",
            switch (job.scope()) {
                case CONNECTION -> job.title() + " started for this connection.";
                case GLOBAL -> job.title() + " started for all connections.";
            }
        );
    }

    private Map<String, ScheduledTaskRow> loadScheduledRows() {
        List<ScheduledTaskRow> rows = jdbcTemplate.query(
            """
            SELECT task_name, execution_time, picked, last_success, last_failure, consecutive_failures, last_heartbeat
            FROM scheduled_tasks
            WHERE task_instance = 'recurring'
            """,
            (rs, rowNum) -> new ScheduledTaskRow(
                rs.getString("task_name"),
                rs.getObject("execution_time", OffsetDateTime.class),
                rs.getBoolean("picked"),
                rs.getObject("last_success", OffsetDateTime.class),
                rs.getObject("last_failure", OffsetDateTime.class),
                (Integer) rs.getObject("consecutive_failures"),
                rs.getObject("last_heartbeat", OffsetDateTime.class)
            )
        );

        return rows.stream().collect(Collectors.toMap(ScheduledTaskRow::taskName, row -> row, (left, right) -> left, LinkedHashMap::new));
    }

    private BrainJobStatus toStatus(BrainJob job, String connectionId, ScheduledTaskRow row) {
        boolean enabledForConnection = switch (job) {
            case METADATA_REFRESH -> dbSchedulerEnabled && brainRefreshEnabled;
            case COLUMN_VALUES_REFRESH -> dbSchedulerEnabled && columnValueRefreshEnabled;
            case SCHEMA_DRIFT_CHECK -> {
                // Ensure a default drift config exists so the job is always active
                if (dbSchedulerEnabled) schemaChangeTrackingService.ensureDefaultDriftConfig(connectionId);
                yield dbSchedulerEnabled;
            }
            case WORKLOAD_METRICS, WORKLOAD_CHARACTERIZATION, KNOB_RANKINGS,
                 COLUMN_STATISTICS, ESTIMATION_ACCURACY, READINESS -> dbSchedulerEnabled && brainLearningEnabled;
            case SLOW_QUERY_DAILY_ANALYSIS -> dbSchedulerEnabled;
        };

        String status = !enabledForConnection
            ? "inactive"
            : row != null && row.picked()
                ? "running"
                : "active";

        String statusReason = null;
        if (!dbSchedulerEnabled) {
            statusReason = "db-scheduler is disabled";
        } else if (job == BrainJob.METADATA_REFRESH && !brainRefreshEnabled) {
            statusReason = "Brain metadata refresh is disabled";
        } else if (job == BrainJob.COLUMN_VALUES_REFRESH && !columnValueRefreshEnabled) {
            statusReason = "Background column-value refresh is disabled";
        } else if ((job == BrainJob.WORKLOAD_METRICS || job == BrainJob.WORKLOAD_CHARACTERIZATION
                 || job == BrainJob.KNOB_RANKINGS || job == BrainJob.COLUMN_STATISTICS
                 || job == BrainJob.ESTIMATION_ACCURACY || job == BrainJob.READINESS)
                && !brainLearningEnabled) {
            statusReason = "Brain learning jobs are disabled";
        } else if (row == null) {
            statusReason = "Recurring job is not registered in db-scheduler";
        }

        // db-scheduler leaves consecutive_failures NULL until the first
        // success/failure is recorded; coerce before unboxing into the int field.
        int consecutiveFailures = row != null && row.consecutiveFailures() != null
            ? row.consecutiveFailures()
            : 0;

        return new BrainJobStatus(
            job.key(),
            job.title(),
            job.description(),
            job.scope().name().toLowerCase(Locale.ROOT),
            "running".equals(status),
            "active".equals(status),
            row != null ? row.executionTime() : null,
            row != null ? row.lastSuccess() : null,
            row != null ? row.lastFailure() : null,
            consecutiveFailures,
            status,
            statusReason
        );
    }

    private enum JobScope {
        CONNECTION,
        GLOBAL
    }

    private enum BrainJob {
        METADATA_REFRESH(
            "metadata_refresh",
            "brain-refresh-metadata-lifecycle",
            "Metadata refresh",
            "Checks whether this connection needs a fresh Brain lifecycle run.",
            JobScope.CONNECTION,
            1
        ),
        COLUMN_VALUES_REFRESH(
            "column_values_refresh",
            "column-values-background-refresh",
            "Column values refresh",
            "Refreshes low-cardinality value dictionaries and embeddings for exact filters.",
            JobScope.CONNECTION,
            2
        ),
        SCHEMA_DRIFT_CHECK(
            "schema_drift_check",
            "schema-drift-check",
            "Schema drift check",
            "Compares the latest schema against the drift baseline when drift tracking is configured.",
            JobScope.CONNECTION,
            3
        ),
        WORKLOAD_METRICS(
            "workload_metrics",
            "brain-collect-workload-metrics",
            "Workload metrics collection",
            "Collects usage and workload signals that feed Brain learning.",
            JobScope.GLOBAL,
            4
        ),
        WORKLOAD_CHARACTERIZATION(
            "workload_characterization",
            "brain-update-workload-characterization",
            "Workload characterization",
            "Updates workload-type classification from the accumulated metrics.",
            JobScope.GLOBAL,
            5
        ),
        KNOB_RANKINGS(
            "knob_rankings",
            "brain-update-knob-rankings",
            "Knob ranking update",
            "Refreshes configuration-tuning priorities from the learned workload profile.",
            JobScope.GLOBAL,
            6
        ),
        COLUMN_STATISTICS(
            "column_statistics",
            "brain-refresh-column-statistics",
            "Column statistics refresh",
            "Refreshes stale column statistics used for estimation and query reasoning.",
            JobScope.GLOBAL,
            7
        ),
        ESTIMATION_ACCURACY(
            "estimation_accuracy",
            "brain-check-estimation-accuracy",
            "Estimation accuracy check",
            "Checks whether estimated row counts are drifting away from reality.",
            JobScope.GLOBAL,
            8
        ),
        READINESS(
            "readiness",
            "brain-calculate-readiness",
            "Readiness calculation",
            "Updates Brain learning readiness across tracked connections.",
            JobScope.GLOBAL,
            9
        ),
        SLOW_QUERY_DAILY_ANALYSIS(
            "slow_query_daily_analysis",
            "slow-query-daily-analysis",
            "Slow query daily analysis",
            "Once-a-day pass: pulls fresh slow-log events from each connection's configured log source (CloudWatch, S3, Azure Blob, GCP, Datadog, Elasticsearch), updates fingerprints, builds the 30-day timeline, attributes samples per customer, and applies retention.",
            JobScope.GLOBAL,
            10
        );

        private final String key;
        private final String taskName;
        private final String title;
        private final String description;
        private final JobScope scope;
        private final int displayOrder;

        BrainJob(String key, String taskName, String title, String description, JobScope scope, int displayOrder) {
            this.key = key;
            this.taskName = taskName;
            this.title = title;
            this.description = description;
            this.scope = scope;
            this.displayOrder = displayOrder;
        }

        public String key() {
            return key;
        }

        public String taskName() {
            return taskName;
        }

        public String title() {
            return title;
        }

        public String description() {
            return description;
        }

        public JobScope scope() {
            return scope;
        }

        public int displayOrder() {
            return displayOrder;
        }

        public static Optional<BrainJob> fromKey(String key) {
            return Arrays.stream(values())
                .filter(job -> job.key.equalsIgnoreCase(key))
                .findFirst();
        }
    }

    private record ScheduledTaskRow(
        String taskName,
        OffsetDateTime executionTime,
        boolean picked,
        OffsetDateTime lastSuccess,
        OffsetDateTime lastFailure,
        Integer consecutiveFailures,
        OffsetDateTime lastHeartbeat
    ) {
    }

    public record BrainJobStatus(
        String key,
        String title,
        String description,
        String scope,
        boolean running,
        boolean active,
        OffsetDateTime nextRunAt,
        OffsetDateTime lastSuccessAt,
        OffsetDateTime lastFailureAt,
        int consecutiveFailures,
        String status,
        String statusReason
    ) {
    }

    public record ManualRunResult(
        String jobKey,
        String status,
        String message
    ) {
    }
}
