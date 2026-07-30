package com.dbaagent.service.scheduler;

import com.dbaagent.model.ConnectionInitHistory;
import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.InitStage;
import com.dbaagent.repository.ConnectionInitHistoryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.VectorSearchService;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import com.github.kagkarlsson.scheduler.task.TaskInstanceId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Schedules Brain initialization via db-scheduler instead of local virtual threads.
 * This makes init survive VM crashes — if a VM dies mid-stage, another VM picks it up
 * after the heartbeat expires (default: 30 minutes).
 *
 * Idempotency: SQL upsert is the single source of truth. The upsert return value
 * determines behavior: >0 means a new run was started, 0 means either genuinely
 * in-progress (skip) or orphaned SCHEMA_SCAN (crash recovery: reschedule with
 * existing runId).
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BrainInitSchedulerService {

    private final SchedulerClient schedulerClient;
    private final ConnectionInitStatusRepository initStatusRepo;
    private final ConnectionInitHistoryRepository initHistoryRepo;
    private final TrainingJobService trainingJobService;
    private final JdbcTemplate jdbcTemplate;
    private final BrainInitPlanningService brainInitPlanningService;
    private final VectorSearchService vectorSearchService;
    private final InferredTableRelationshipRepository inferredRelationshipRepository;

    /**
     * Heartbeat staleness threshold for treating a db-scheduler-picked task
     * as orphaned. kagkarlsson's default dead-execution detector polls every
     * ~5 min and marks tasks whose {@code last_heartbeat} hasn't moved in
     * ~30 min as dead, then reschedules them. We mirror that window so we
     * don't kill a task the scheduler itself still considers alive.
     */
    private static final Duration HEARTBEAT_STALE_THRESHOLD = Duration.ofMinutes(30);

    /**
     * On boot, reconcile non-terminal {@code connection_init_status} rows
     * with db-scheduler's view of the world: only mark a row FAILED when the
     * scheduler ALSO has no live task for it. Three cases:
     *
     * <ol>
     *   <li><b>Scheduler has a live task</b> (last_heartbeat within
     *       {@link #HEARTBEAT_STALE_THRESHOLD}): the previous VM died
     *       mid-stage but the scheduler will reschedule on its next dead-
     *       execution sweep. Leave the row alone — db-scheduler will retry
     *       and the row will be updated naturally.
     *   <li><b>Scheduler has a stale-heartbeat task</b> OR no task at all:
     *       genuinely orphaned. Mark FAILED with a clear "interrupted by
     *       restart" message; the UI renders the Re-init affordance.
     * </ol>
     *
     * <p>Why this exists: each Brain init runs as a db-scheduler task that
     * writes per-stage rows to {@code connection_init_status}. When the JVM
     * is restarted mid-stage (e.g. during a deploy), the row is left at its
     * last-written stage and percent — the UI shows it as "running" forever.
     * The class header promises heartbeats handle this; for a single-VM
     * deployment with no peer to take over, this pass closes the gap by
     * cleaning up after the dead-execution sweep would have.
     *
     * <p>Safe to run repeatedly: only touches rows where the stage is NOT
     * COMPLETED and NOT FAILED, and where the scheduler agrees the work is
     * dead. Best-effort — any failure logs and continues so a broken cleanup
     * pass never blocks app boot.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void markOrphanedInitsAsFailedOnStartup() {
        try {
            LocalDateTime heartbeatCutoff = LocalDateTime.now().minus(HEARTBEAT_STALE_THRESHOLD);
            var stuck = initStatusRepo.findByCurrentStageNotIn(
                java.util.List.of(InitStage.COMPLETED, InitStage.FAILED));
            int marked = 0;
            int spared = 0;
            for (var status : stuck) {
                if (schedulerHasLiveTask(status.getConnectionId(), heartbeatCutoff)) {
                    spared++;
                    log.info("[startup] Sparing connection {} from cleanup — "
                        + "scheduler still has a live task (stage {})",
                        status.getConnectionId(), status.getCurrentStage());
                    continue;
                }
                // Capture stage BEFORE overwriting it; the error message
                // needs the real stage, not the FAILED placeholder.
                InitStage failedAtStage = status.getCurrentStage();
                Integer failedAtPercent = status.getProgressPercent();
                status.setCurrentStage(InitStage.FAILED);
                status.setStageMessage("Init was interrupted by a server restart. "
                    + "Click Re-init to try again.");
                status.setErrorMessage("Interrupted by VM restart at stage "
                    + failedAtStage + " ("
                    + (failedAtPercent != null ? failedAtPercent : 0)
                    + "%)");
                status.setCompletedAt(LocalDateTime.now());
                initStatusRepo.save(status);
                marked++;
                log.warn("[startup] Marked orphaned init as FAILED for connection {} "
                    + "(was at {} {}%, started {})",
                    status.getConnectionId(), failedAtStage,
                    failedAtPercent, status.getStartedAt());
            }
            if (marked > 0 || spared > 0) {
                log.info("[startup] Brain-init cleanup pass: {} marked FAILED, {} spared "
                    + "(scheduler heartbeat within {} min)",
                    marked, spared, HEARTBEAT_STALE_THRESHOLD.toMinutes());
            }
        } catch (Exception e) {
            // Best-effort: a failed startup pass shouldn't block app boot.
            log.error("[startup] Orphaned-init cleanup failed: {}", e.getMessage(), e);
        }
    }

    /**
     * Does the kagkarlsson scheduler think there's a live {@code brain-init-stage}
     * task for this connection? Task instance ids are formatted as
     * {@code {runId}-{STAGE}} — we match by {@code connection_id} via the
     * {@code active_run_id} stored on {@code connection_init_status}.
     *
     * <p>A task is "live" when picked=true AND last_heartbeat &gt; the cutoff
     * (within the heartbeat-stale threshold). If db-scheduler's
     * dead-execution detector hasn't picked it up yet but will soon, we
     * trust it and don't preemptively mark as failed.
     */
    private boolean schedulerHasLiveTask(String connectionId, LocalDateTime heartbeatCutoff) {
        try {
            // Look up the connection's active runId — if there isn't one, no
            // task could be live for this connection.
            var maybeRow = initStatusRepo.findById(connectionId);
            if (maybeRow.isEmpty() || maybeRow.get().getActiveRunId() == null) {
                return false;
            }
            String runIdPrefix = maybeRow.get().getActiveRunId().toString() + "-";
            Integer aliveCount = jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM scheduled_tasks "
                + "WHERE task_name = 'brain-init-stage' "
                + "AND task_instance LIKE ? "
                + "AND picked = TRUE "
                + "AND last_heartbeat > ?",
                Integer.class,
                runIdPrefix + "%",
                java.sql.Timestamp.valueOf(heartbeatCutoff));
            return aliveCount != null && aliveCount > 0;
        } catch (Exception e) {
            // If we can't tell, be conservative and assume it's alive — better
            // to leave a stale row than to kill a running init.
            log.debug("[startup] schedulerHasLiveTask probe failed for {}: {}",
                connectionId, e.getMessage());
            return true;
        }
    }

    /**
     * Schedule a Brain initialization for the given connection.
     *
     * Uses the SQL upsert return value as the single source of truth for idempotency:
     *   - rows > 0: upsert created/overwrote -> schedule the task with new runId
     *   - rows == 0 + SCHEMA_SCAN: orphaned after crash -> reschedule with existing runId
     *   - rows == 0 + other stage: genuinely in progress -> skip
     *
     * The SQL WHERE clause allows overwrite when the row is terminal (COMPLETED/FAILED)
     * or cancelled (cancel_requested = TRUE, for reinit-after-cancel).
     */
    public void scheduleInit(String connectionId) {
        UUID runId = UUID.randomUUID();
        log.info("[scheduleInit] START connectionId={} runId={} schedulerClient={}",
            connectionId, runId, schedulerClient.getClass().getSimpleName());

        // Snapshot pre-existing row state for diagnostics (raw JDBC, bypasses JPA cache)
        var dbRow = jdbcTemplate.queryForList(
            "SELECT current_stage, cancel_requested, active_run_id FROM connection_init_status WHERE connection_id = ?",
            connectionId);
        if (dbRow.isEmpty()) {
            log.info("[scheduleInit] DB CHECK: no row exists for connectionId={}", connectionId);
        } else {
            log.info("[scheduleInit] DB CHECK: connectionId={} stage={} cancel={} runId={}",
                connectionId, dbRow.getFirst().get("current_stage"),
                dbRow.getFirst().get("cancel_requested"), dbRow.getFirst().get("active_run_id"));
        }

        var preExisting = initStatusRepo.findById(connectionId);
        if (preExisting.isPresent()) {
            var s = preExisting.get();
            log.info("[scheduleInit] PRE-EXISTING ROW connectionId={} stage={} cancelRequested={} activeRunId={}",
                connectionId, s.getCurrentStage(), s.getCancelRequested(), s.getActiveRunId());
        } else {
            log.info("[scheduleInit] NO PRE-EXISTING ROW for connectionId={}", connectionId);
        }

        int rows = jdbcTemplate.update("""
            INSERT INTO connection_init_status
                (connection_id, current_stage, progress_percent, stage_message,
                 cancel_requested, active_run_id, started_at,
                 completed_at, error_message, stage_timings, stage_details)
            VALUES (?, 'SCHEMA_SCAN', 0, 'Scheduled...', FALSE, ?::uuid, NOW(),
                    NULL, NULL, '{}'::jsonb, '{}'::jsonb)
            ON CONFLICT (connection_id) DO UPDATE SET
                current_stage = 'SCHEMA_SCAN',
                progress_percent = 0,
                stage_message = 'Scheduled...',
                cancel_requested = FALSE,
                active_run_id = EXCLUDED.active_run_id,
                started_at = NOW(),
                completed_at = NULL,
                error_message = NULL,
                stage_timings = '{}'::jsonb,
                stage_details = '{}'::jsonb
            WHERE connection_init_status.current_stage IN ('COMPLETED', 'FAILED')
               OR connection_init_status.cancel_requested = TRUE
            """, connectionId, runId.toString());

        log.info("[scheduleInit] UPSERT rows={} connectionId={}", rows, connectionId);

        if (rows > 0) {
            scheduleStage(connectionId, runId, InitStage.SCHEMA_SCAN);
            log.info("[scheduleInit] TASK SCHEDULED connectionId={} runId={} instanceId={}",
                connectionId, runId, runId + "-" + InitStage.SCHEMA_SCAN.name());
            return;
        }

        // Upsert returned 0 rows — row exists and is non-terminal, non-cancelled.
        log.warn("[scheduleInit] UPSERT RETURNED 0 — row is non-terminal/non-cancelled. connectionId={}", connectionId);
        var status = initStatusRepo.findById(connectionId);
        if (status.isPresent() && status.get().getCurrentStage() == InitStage.SCHEMA_SCAN) {
            // Possibly orphaned — reschedule with the EXISTING runId (idempotent).
            UUID existingRunId = status.get().getActiveRunId();
            if (existingRunId != null) {
                String instanceId = existingRunId + "-" + InitStage.SCHEMA_SCAN.name();
                schedulerClient.scheduleIfNotExists(
                    BrainInitTaskConfig.BRAIN_INIT_STAGE.instance(instanceId)
                        .data(new BrainInitTaskData(connectionId, InitStage.SCHEMA_SCAN, 0, existingRunId.toString()))
                        .scheduledTo(Instant.now())
                );
                log.info("[scheduleInit] RE-SCHEDULED orphaned SCHEMA_SCAN connectionId={} existingRunId={}",
                    connectionId, existingRunId);
            } else {
                log.warn("[scheduleInit] SCHEMA_SCAN with null activeRunId connectionId={} — marking FAILED", connectionId);
                var stale = status.get();
                stale.setCurrentStage(InitStage.FAILED);
                stale.setErrorMessage("Legacy row with null runId");
                initStatusRepo.save(stale);
            }
        } else {
            log.warn("[scheduleInit] SKIPPED — init in progress connectionId={} stage={}",
                connectionId, status.map(s -> s.getCurrentStage()).orElse(null));
        }
    }

    public BrainInitPlan planAndScheduleInit(String connectionId, boolean forceFullRebuild) {
        if (forceFullRebuild) {
            // Ensure the status row is in a terminal state so the scheduleInit upsert WHERE guard
            // does not silently skip the rebuild when a previous init is stuck mid-stage.
            forceTerminateIfInProgress(connectionId);
            // Wipe stale brain metadata so canReuseExistingEmbedding() cannot reuse partial/broken
            // vectors from the failed build, and so stale inferred relationships don't bleed through.
            clearBrainMetadataForRebuild(connectionId);
        }

        BrainInitPlan plan = brainInitPlanningService.planRefresh(connectionId, forceFullRebuild);
        if (plan.mode() == BrainInitPlanMode.RESUME_FAILED) {
            InitStage resumedStage = resumeFailedInit(connectionId);
            return BrainInitPlan.resumeFailed(resumedStage);
        }
        if (plan.skipped()) {
            return plan;
        }

        InitStage startStage = plan.startedFromStage() != null
            ? plan.startedFromStage()
            : InitStage.SCHEMA_SCAN;
        if (startStage == InitStage.SCHEMA_SCAN) {
            scheduleInit(connectionId);
        } else {
            scheduleInitFromStage(connectionId, startStage);
        }
        return plan;
    }

    /**
     * If an init is currently in a non-terminal stage (i.e. not COMPLETED or FAILED), force it to
     * FAILED so that the scheduleInit upsert WHERE guard allows a fresh start. This is only safe
     * to call on a force rebuild where the caller explicitly wants to discard the in-progress run.
     */
    private void forceTerminateIfInProgress(String connectionId) {
        int updated = jdbcTemplate.update("""
            UPDATE connection_init_status
               SET current_stage = 'FAILED',
                   error_message  = 'Superseded by force rebuild',
                   completed_at   = NOW()
             WHERE connection_id  = ?
               AND current_stage NOT IN ('COMPLETED', 'FAILED')
            """, connectionId);
        if (updated > 0) {
            log.info("[forceTerminate] Terminated in-progress init for connectionId={} to allow force rebuild", connectionId);
        }
    }

    /**
     * Delete stale brain metadata for a connection before a force rebuild so that residuals from
     * a previous broken build cannot affect the new run:
     * <ul>
     *   <li>rag_documents (pgvector) — prevents canReuseExistingEmbedding() from reusing broken vectors</li>
     *   <li>inferred_table_relationship — prevents stale join-path inferences bleeding into the new build</li>
     * </ul>
     * column_profile and schema_documentation are left intact; they are either replaced by upsert
     * during DATA_SAMPLING/AI_DESCRIPTION or are source-of-truth inputs that the new build refines.
     */
    private void clearBrainMetadataForRebuild(String connectionId) {
        try {
            vectorSearchService.deleteConnectionDocuments(connectionId);
            log.info("[clearBrainMetadata] Deleted rag_documents for connectionId={}", connectionId);
        } catch (Exception e) {
            log.warn("[clearBrainMetadata] Failed to delete rag_documents for connectionId={}: {}", connectionId, e.getMessage());
        }
        try {
            inferredRelationshipRepository.deleteByConnectionId(connectionId);
            log.info("[clearBrainMetadata] Deleted inferred_table_relationship for connectionId={}", connectionId);
        } catch (Exception e) {
            log.warn("[clearBrainMetadata] Failed to delete inferred relationships for connectionId={}: {}", connectionId, e.getMessage());
        }
        try {
            // Wipe the AI-generated table/column descriptions so the delta-only
            // AI_DESCRIPTION stage regenerates every one on a force rebuild. User-
            // authored docs and business terms are preserved.
            int n = jdbcTemplate.update(
                "DELETE FROM schema_documentation WHERE connection_id = ? "
                + "AND source = 'AI_GENERATED' AND object_type IN ('TABLE','COLUMN')",
                connectionId);
            log.info("[clearBrainMetadata] Deleted {} AI_GENERATED table/column descriptions for connectionId={}", n, connectionId);
        } catch (Exception e) {
            log.warn("[clearBrainMetadata] Failed to delete AI descriptions for connectionId={}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Resume a previously failed init from the failed stage instead of restarting from schema scan.
     * Earlier successful stage results are preserved; the failed stage and all downstream stages are rerun.
     *
     * @return the stage the resumed run starts from
     */
    public InitStage resumeFailedInit(String connectionId) {
        var status = initStatusRepo.findById(connectionId)
            .orElseThrow(() -> new IllegalStateException("No initialization found for connection"));

        if (status.getCurrentStage() != InitStage.FAILED) {
            throw new IllegalStateException("Initialization is not in a failed state");
        }

        InitStage resumeStage = inferResumeStage(status);
        UUID runId = UUID.randomUUID();

        pruneStageStateFrom(status, resumeStage);
        status.setCurrentStage(resumeStage);
        status.setProgressPercent(stageStartPercent(resumeStage));
        status.setStageMessage("Resuming " + resumeStage.name() + "...");
        status.setCancelRequested(false);
        status.setActiveRunId(runId);
        status.setStartedAt(LocalDateTime.now());
        status.setCompletedAt(null);
        status.setErrorMessage(null);

        initStatusRepo.save(status);
        broadcast(connectionId, status);
        scheduleStage(connectionId, runId, resumeStage);

        log.info("[resumeFailedInit] RESUMED connectionId={} fromStage={} runId={}",
            connectionId, resumeStage, runId);
        return resumeStage;
    }

    void scheduleInitFromStage(String connectionId, InitStage startStage) {
        if (startStage == null || startStage == InitStage.SCHEMA_SCAN) {
            scheduleInit(connectionId);
            return;
        }

        var status = initStatusRepo.findById(connectionId)
            .orElseThrow(() -> new IllegalStateException("No initialization found for connection"));

        if (status.getCurrentStage() != null && !status.getCurrentStage().isTerminal()) {
            throw new IllegalStateException("Initialization is already in progress");
        }

        UUID runId = UUID.randomUUID();
        pruneStageStateFrom(status, startStage);
        status.setCurrentStage(startStage);
        status.setProgressPercent(stageStartPercent(startStage));
        status.setStageMessage("Scheduled " + startStage.name() + "...");
        status.setCancelRequested(false);
        status.setActiveRunId(runId);
        status.setStartedAt(LocalDateTime.now());
        status.setCompletedAt(null);
        status.setErrorMessage(null);

        initStatusRepo.save(status);
        broadcast(connectionId, status);
        scheduleStage(connectionId, runId, startStage);

        log.info("[scheduleInitFromStage] SCHEDULED connectionId={} startStage={} runId={}",
            connectionId, startStage, runId);
    }

    /**
     * Cancel a running init by marking it FAILED (terminal) and cancelling db-scheduler tasks.
     *
     * Marking FAILED (not just setting cancel_requested) ensures that a subsequent
     * scheduleInit() call sees a terminal stage and can start a fresh run (reinit).
     * The cancel_requested flag is also set so that an in-flight executeStage() that
     * already loaded the status row can detect the cancel.
     */
    public void cancelInit(String connectionId) {
        log.info("[cancelInit] START connectionId={}", connectionId);
        var statusOpt = initStatusRepo.findById(connectionId);
        if (statusOpt.isEmpty()) {
            log.info("[cancelInit] NO ROW FOUND for connectionId={}, nothing to cancel", connectionId);
            return;
        }
        var status = statusOpt.get();
        InitStage stage = status.getCurrentStage();
        log.info("[cancelInit] FOUND ROW connectionId={} stage={} cancelRequested={} activeRunId={}",
            connectionId, stage, status.getCancelRequested(), status.getActiveRunId());

        if (stage.isTerminal()) {
            log.info("[cancelInit] ALREADY TERMINAL connectionId={} stage={}, skipping", connectionId, stage);
            return;
        }

        status.setCancelRequested(true);
        status.setCurrentStage(InitStage.FAILED);
        status.setStageMessage("Cancelled by user");
        status.setErrorMessage("Cancelled by user");
        status.setCompletedAt(LocalDateTime.now());
        initStatusRepo.save(status);
        log.info("[cancelInit] SAVED FAILED state for connectionId={} (was {})", connectionId, stage);

        broadcast(connectionId, status);
        saveHistory(status);

        // Cancel pending db-scheduler tasks for this run
        UUID runId = status.getActiveRunId();
        if (runId != null) {
            tryCancel(runId, stage);
            InitStage nextStage = nextStageOf(stage);
            if (nextStage != null) {
                tryCancel(runId, nextStage);
            }
        }
    }

    private void tryCancel(UUID runId, InitStage stage) {
        try {
            schedulerClient.cancel(
                TaskInstanceId.of("brain-init-stage", runId + "-" + stage.name())
            );
            log.debug("Cancelled db-scheduler task for runId={} stage={}", runId, stage);
        } catch (Exception e) {
            log.debug("Could not cancel db-scheduler task for runId={} stage={}: {}",
                runId, stage, e.getMessage());
        }
    }

    private void scheduleStage(String connectionId, UUID runId, InitStage stage) {
        var data = new BrainInitTaskData(connectionId, stage, 0, runId.toString());
        String instanceId = runId + "-" + stage.name();
        schedulerClient.scheduleIfNotExists(
            BrainInitTaskConfig.BRAIN_INIT_STAGE.instance(instanceId)
                .data(data)
                .scheduledTo(Instant.now())
        );
    }

    private InitStage inferResumeStage(ConnectionInitStatus status) {
        if (status.getStageTimings() == null || status.getStageTimings().isEmpty()) {
            return InitStage.SCHEMA_SCAN;
        }

        return status.getStageTimings().entrySet().stream()
            .map(entry -> {
                try {
                    InitStage stage = InitStage.valueOf(entry.getKey());
                    if (stage.isTerminal()) {
                        return null;
                    }
                    String startedAt = entry.getValue() != null ? entry.getValue().startedAt() : null;
                    if (startedAt == null || startedAt.isBlank()) {
                        return null;
                    }
                    return Map.entry(stage, Instant.parse(startedAt));
                } catch (Exception e) {
                    return null;
                }
            })
            .filter(java.util.Objects::nonNull)
            .max(Comparator.comparing(Map.Entry::getValue))
            .map(Map.Entry::getKey)
            .orElse(InitStage.SCHEMA_SCAN);
    }

    private void pruneStageStateFrom(ConnectionInitStatus status, InitStage resumeStage) {
        Map<String, ConnectionInitStatus.StageTimingEntry> timings =
            status.getStageTimings() != null ? new HashMap<>(status.getStageTimings()) : new HashMap<>();
        Map<String, Map<String, Object>> details =
            status.getStageDetails() != null ? new HashMap<>(status.getStageDetails()) : new HashMap<>();

        for (InitStage stage : InitStage.values()) {
            if (stage.isTerminal()) continue;
            if (stage.ordinal() >= resumeStage.ordinal()) {
                timings.remove(stage.name());
                details.remove(stage.name());
            }
        }

        status.setStageTimings(timings);
        status.setStageDetails(details);
    }

    private int stageStartPercent(InitStage stage) {
        return switch (stage) {
            case SCHEMA_SCAN -> 0;
            case DATA_SAMPLING -> 18;
            case KEY_COLUMN_ANALYSIS -> 30;
            case COLUMN_VALUE_COLLECTION -> 40;
            case INFERRED_RELATIONSHIPS -> 50;
            case SCHEMA_CLASSIFICATION -> 60;
            case AI_DESCRIPTION -> 68;
            case RAG_EMBEDDING -> 80;
            case BRAIN_ANALYSIS -> 92;
            case SEMANTIC_MODELING -> 96;
            case COMPLETED, FAILED -> 100;
        };
    }

    private InitStage nextStageOf(InitStage stage) {
        return stage != null ? stage.next() : null;
    }

    private void broadcast(String connectionId, ConnectionInitStatus status) {
        trainingJobService.broadcastInitProgress(connectionId, status);
    }

    private void saveHistory(ConnectionInitStatus status) {
        try {
            long totalMs = 0;
            if (status.getStartedAt() != null && status.getCompletedAt() != null) {
                totalMs = Duration.between(
                    status.getStartedAt().atZone(ZoneId.systemDefault()).toInstant(),
                    status.getCompletedAt().atZone(ZoneId.systemDefault()).toInstant()
                ).toMillis();
            }
            var history = ConnectionInitHistory.builder()
                .connectionId(status.getConnectionId())
                .finalStage(status.getCurrentStage())
                .progressPercent(status.getProgressPercent())
                .stageTimings(status.getStageTimings())
                .stageDetails(status.getStageDetails())
                .startedAt(status.getStartedAt())
                .completedAt(status.getCompletedAt())
                .totalDurationMs(totalMs)
                .errorMessage(status.getErrorMessage())
                .build();
            initHistoryRepo.save(history);
        } catch (Exception e) {
            log.warn("Failed to save init history for {}: {}", status.getConnectionId(), e.getMessage());
        }
    }
}
