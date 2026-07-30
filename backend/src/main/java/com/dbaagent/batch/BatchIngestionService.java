package com.dbaagent.batch;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.job.JobInstance;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

/**
 * Service for managing batch ingestion jobs using Spring Batch 6.0.2.
 * Provides status, start, stop, and restart operations.
 * Reads state from Spring Batch tables (DB-backed, survives restarts).
 *
 * Spring Batch 6.0.2 features used:
 * - JobRepository extends JobExplorer: Single interface for persistence & queries
 * - JobOperator extends JobLauncher: Single interface for launching & operating jobs
 * - restart(executionId) for resuming failed/stopped jobs
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class BatchIngestionService {

    private final Job slowLogIngestionJob;
    // In Spring Batch 6.0: JobOperator extends JobLauncher - unified interface
    private final JobOperator jobOperator;
    // In Spring Batch 6.0: JobRepository extends JobExplorer - unified interface
    private final JobRepository jobRepository;
    private final JdbcTemplate jdbcTemplate;

    /**
     * Start a new ingestion job
     */
    public BatchIngestionStatus startJob(String connectionId, String timeRange, Long maxLogs) {
        try {
            // Check if there's already an active job for this connection
            Optional<BatchIngestionStatus> activeJob = getActiveJobForConnection(connectionId);
            if (activeJob.isPresent()) {
                BatchIngestionStatus active = activeJob.get();
                if (active.isResumable()) {
                    log.info("Found interrupted job {} - user should resume it", active.jobExecutionId());
                    return active;
                }
                if (active.isRunning()) {
                    log.info("Job already running for connection {}: {}", connectionId, active.jobExecutionId());
                    return active;
                }
            }

            // Build job parameters
            JobParameters params = new JobParametersBuilder()
                .addString("connectionId", connectionId)
                .addString("timeRange", timeRange != null ? timeRange : "LAST_24_HOURS")
                .addLong("maxLogs", maxLogs != null ? maxLogs : 10000L)
                .addLong("timestamp", System.currentTimeMillis()) // Makes each run unique
                .toJobParameters();

            // Launch job asynchronously using JobOperator (extends JobLauncher in 6.0)
            JobExecution execution = jobOperator.run(slowLogIngestionJob, params);

            log.info("Started ingestion job {} for connection {}", execution.getId(), connectionId);

            return mapToStatus(execution);

        } catch (Exception e) {
            log.error("Failed to start ingestion job for connection {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Failed to start ingestion job: " + e.getMessage(), e);
        }
    }

    /**
     * Get status of a specific job execution.
     * Loads execution context directly from database to ensure fresh data.
     */
    public Optional<BatchIngestionStatus> getJobStatus(Long executionId) {
        JobExecution execution = jobRepository.getJobExecution(executionId);
        if (execution == null) {
            return Optional.empty();
        }

        // Load execution context directly from database to bypass any caching
        // Spring Batch stores context as Base64-encoded Java serialization
        try {
            String base64Context = jdbcTemplate.queryForObject(
                "SELECT short_context FROM dba_batch_job_execution_context WHERE job_execution_id = ?",
                String.class,
                executionId
            );
            if (base64Context != null && !base64Context.isBlank()) {
                byte[] contextBytes = java.util.Base64.getDecoder().decode(base64Context);
                try (java.io.ByteArrayInputStream bais = new java.io.ByteArrayInputStream(contextBytes);
                     java.io.ObjectInputStream ois = new java.io.ObjectInputStream(bais)) {
                    @SuppressWarnings("unchecked")
                    java.util.Map<String, Object> contextMap = (java.util.Map<String, Object>) ois.readObject();
                    ExecutionContext freshContext = new ExecutionContext(contextMap);
                    execution.setExecutionContext(freshContext);
                    log.info("Loaded fresh context for job {}: currentStep={}, progressPct={}, providerType={}",
                        executionId,
                        freshContext.getString("currentStep", "null"),
                        freshContext.containsKey("progressPct") ? freshContext.get("progressPct") : "null",
                        freshContext.getString("providerType", "null"));
                }
            }
        } catch (Exception e) {
            log.warn("Failed to load execution context directly: {}", e.getMessage());
        }

        return Optional.of(mapToStatus(execution));
    }

    // Stale job threshold - jobs in STARTED status longer than this are considered abandoned
    private static final Duration STALE_JOB_THRESHOLD = Duration.ofMinutes(30);

    /**
     * Get active job for a connection (only STARTED, STARTING, STOPPING, or UNKNOWN).
     * Uses JobRepository which extends JobExplorer in Spring Batch 6.0.
     * Also detects and marks stale jobs as FAILED (STARTED but not updated for too long).
     *
     * NOTE: STOPPED jobs are NOT considered active - they were explicitly cancelled
     * and should allow starting a fresh job.
     */
    public Optional<BatchIngestionStatus> getActiveJobForConnection(String connectionId) {
        // Get all executions for the job using JobRepository (extends JobExplorer)
        List<JobInstance> instances = jobRepository.findJobInstancesByJobName(
            "slowLogIngestionJob", 0, 100
        );

        for (JobInstance instance : instances) {
            List<JobExecution> executions = jobRepository.getJobExecutions(instance);
            for (JobExecution execution : executions) {
                JobParameters params = execution.getJobParameters();
                String execConnectionId = params.getString("connectionId");

                if (connectionId.equals(execConnectionId)) {
                    BatchStatus status = execution.getStatus();

                    // Check for stale jobs (STARTED but not updated for too long)
                    if ((status == BatchStatus.STARTED || status == BatchStatus.UNKNOWN) &&
                        isStaleExecution(execution)) {
                        // Mark stale job as FAILED so user can start fresh
                        // Note: abandon() throws for STARTED, so we update directly
                        markExecutionAsFailed(execution, "Job timed out - no updates for " + STALE_JOB_THRESHOLD);
                        log.warn("Marked stale job execution {} as FAILED for connection {} (no updates for {})",
                            execution.getId(), connectionId, STALE_JOB_THRESHOLD);
                        continue; // Skip this execution, look for others
                    }

                    // Only truly active jobs block new runs:
                    // - STARTED: Currently executing
                    // - STARTING: About to execute
                    // - STOPPING: Being stopped (still running)
                    // - UNKNOWN: Server crashed mid-execution (can be resumed)
                    // NOTE: STOPPED is NOT included - it means user cancelled, allow fresh start
                    if (status == BatchStatus.STARTED ||
                        status == BatchStatus.STARTING ||
                        status == BatchStatus.STOPPING ||
                        status == BatchStatus.UNKNOWN) {
                        return Optional.of(mapToStatus(execution));
                    }
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Mark an execution as FAILED directly via repository update.
     * Used for stale jobs where abandon() would throw an exception.
     */
    private void markExecutionAsFailed(JobExecution execution, String reason) {
        try {
            // Update execution context with failure message for UI display
            ExecutionContext jobContext = execution.getExecutionContext();
            jobContext.putString("message", reason);
            jobContext.putString("currentStep", "Failed (stale)");

            execution.setStatus(BatchStatus.FAILED);
            execution.setExitStatus(new ExitStatus("FAILED", reason));
            execution.setEndTime(LocalDateTime.now());
            execution.setLastUpdated(LocalDateTime.now());
            jobRepository.update(execution);
            // Persist execution context separately (required for context changes to survive reload)
            jobRepository.updateExecutionContext(execution);

            // Also mark any running steps as FAILED
            for (StepExecution stepExecution : execution.getStepExecutions()) {
                if (stepExecution.getStatus() == BatchStatus.STARTED ||
                    stepExecution.getStatus() == BatchStatus.STARTING) {
                    stepExecution.setStatus(BatchStatus.FAILED);
                    stepExecution.setExitStatus(new ExitStatus("FAILED", reason));
                    stepExecution.setEndTime(LocalDateTime.now());
                    jobRepository.update(stepExecution);
                    // Persist step execution context as well
                    jobRepository.updateExecutionContext(stepExecution);
                }
            }
        } catch (Exception e) {
            log.error("Failed to mark execution {} as FAILED: {}", execution.getId(), e.getMessage());
        }
    }

    /**
     * Check if a job execution is stale (hasn't been updated for too long).
     */
    private boolean isStaleExecution(JobExecution execution) {
        LocalDateTime lastUpdated = execution.getLastUpdated();
        if (lastUpdated == null) {
            lastUpdated = execution.getStartTime();
        }
        if (lastUpdated == null) {
            return true; // No timestamps = definitely stale
        }

        // Use system timezone - LocalDateTime from DB is in server's local timezone
        Instant lastUpdateInstant = lastUpdated.atZone(ZoneId.systemDefault()).toInstant();
        Duration elapsed = Duration.between(lastUpdateInstant, Instant.now());
        return elapsed.compareTo(STALE_JOB_THRESHOLD) > 0;
    }

    /**
     * Get recent job history for a connection.
     * Uses JobRepository which extends JobExplorer in Spring Batch 6.0.
     */
    public List<BatchIngestionStatus> getJobHistory(String connectionId, int limit) {
        List<JobInstance> instances = jobRepository.findJobInstancesByJobName(
            "slowLogIngestionJob", 0, limit * 2  // Get more to filter
        );

        return instances.stream()
            .flatMap(instance -> jobRepository.getJobExecutions(instance).stream())
            .filter(execution -> {
                String execConnectionId = execution.getJobParameters().getString("connectionId");
                return connectionId.equals(execConnectionId);
            })
            .sorted((a, b) -> b.getStartTime().compareTo(a.getStartTime()))
            .limit(limit)
            .map(this::mapToStatus)
            .toList();
    }

    /**
     * Stop a running job
     */
    public boolean stopJob(Long executionId) {
        try {
            jobOperator.stop(executionId);
            log.info("Stop requested for job execution {}", executionId);
            return true;
        } catch (Exception e) {
            log.error("Failed to stop job execution {}: {}", executionId, e.getMessage(), e);
            return false;
        }
    }

    /**
     * Restart a failed/stopped job.
     * Uses restart() which launches a new execution with the same parameters,
     * resuming from the last successful step.
     *
     * In Spring Batch 6.0.2:
     * - restart(executionId) launches a new execution, resuming from checkpoint
     * - recover(execution) only marks steps as FAILED, doesn't launch anything
     */
    public BatchIngestionStatus restartJob(Long executionId) {
        try {
            // restart() returns the new execution ID
            Long newExecutionId = jobOperator.restart(executionId);
            log.info("Restarted job {} as new execution {}", executionId, newExecutionId);

            // Fetch and return the new execution status
            JobExecution newExecution = jobRepository.getJobExecution(newExecutionId);
            if (newExecution == null) {
                throw new RuntimeException("Failed to find restarted execution: " + newExecutionId);
            }

            return mapToStatus(newExecution);

        } catch (Exception e) {
            log.error("Failed to restart job execution {}: {}", executionId, e.getMessage(), e);
            throw new RuntimeException("Failed to restart job: " + e.getMessage(), e);
        }
    }

    /**
     * Recover a job (mark running steps as FAILED).
     * This is useful for cleanup but doesn't restart the job.
     * Use restartJob() to actually resume execution.
     */
    public void recoverJob(Long executionId) {
        try {
            JobExecution execution = jobRepository.getJobExecution(executionId);
            if (execution == null) {
                throw new RuntimeException("Job execution not found: " + executionId);
            }

            jobOperator.recover(execution);
            log.info("Recovered job {} (marked failed steps)", executionId);

        } catch (Exception e) {
            log.error("Failed to recover job execution {}: {}", executionId, e.getMessage(), e);
        }
    }

    /**
     * Map Spring Batch JobExecution to our DTO
     */
    private BatchIngestionStatus mapToStatus(JobExecution execution) {
        ExecutionContext jobContext = execution.getExecutionContext();

        String currentStep = jobContext.getString("currentStep", "");
        Integer progressPct = jobContext.containsKey("progressPct")
            ? (Integer) jobContext.get("progressPct")
            : 0;
        Long bytesProcessed = jobContext.containsKey("bytesProcessed")
            ? jobContext.getLong("bytesProcessed")
            : null;
        Long eventsProcessed = jobContext.containsKey("eventsProcessed")
            ? jobContext.getLong("eventsProcessed")
            : null;
        String historyId = jobContext.getString("historyId", null);
        Integer slowQueriesFound = jobContext.containsKey("slowQueriesFound")
            ? (Integer) jobContext.get("slowQueriesFound")
            : null;
        String providerType = jobContext.getString("providerType", null);
        String logSource = jobContext.getString("logSource", null);
        String message = jobContext.getString("message", null);

        // Get connection ID from parameters
        String connectionId = execution.getJobParameters().getString("connectionId");

        // Map batch status to our status
        String status = mapBatchStatus(execution.getStatus());

        // Use exit description as message if job failed — strip stack traces so the UI
        // shows a concise error rather than a raw Java exception dump.
        if (execution.getExitStatus() != null &&
            !"UNKNOWN".equals(execution.getExitStatus().getExitCode()) &&
            execution.getExitStatus().getExitDescription() != null &&
            !execution.getExitStatus().getExitDescription().isBlank()) {
            message = trimExitDescription(execution.getExitStatus().getExitDescription());
        }

        // Only use step name as fallback if no currentStep in job context
        if (currentStep == null || currentStep.isBlank()) {
            for (StepExecution stepExecution : execution.getStepExecutions()) {
                if (stepExecution.getStatus() == BatchStatus.STARTED ||
                    stepExecution.getStatus() == BatchStatus.STARTING) {
                    currentStep = stepExecution.getStepName();
                }
            }
        }

        return new BatchIngestionStatus(
            execution.getId(),
            connectionId,
            status,
            currentStep,
            progressPct,
            bytesProcessed,
            eventsProcessed,
            slowQueriesFound,
            providerType,
            logSource,
            message,
            historyId,
            execution.getStartTime() != null ? execution.getStartTime().atZone(ZoneId.systemDefault()).toInstant() : null,
            execution.getEndTime() != null ? execution.getEndTime().atZone(ZoneId.systemDefault()).toInstant() : null,
            execution.getLastUpdated() != null ? execution.getLastUpdated().atZone(ZoneId.systemDefault()).toInstant() : null
        );
    }

    /**
     * Extracts a concise, user-facing message from a Spring Batch exit description.
     *
     * Spring Batch stores the full exception chain (including stack frames) in the
     * exit description. We want to show only the outermost exception message — the
     * first line that actually describes what went wrong — and strip everything from
     * the first {@code \n\tat } (stack frame) onwards.
     *
     * Examples:
     *   "java.lang.RuntimeException: Cannot reach CloudWatch...\n\tat com.dba..."
     *   → "Cannot reach CloudWatch..."
     *
     *   "IllegalStateException: No slow log source configured\n\tat ..."
     *   → "No slow log source configured"
     */
    static String trimExitDescription(String desc) {
        if (desc == null || desc.isBlank()) {
            return desc;
        }

        // Cut at first stack-frame line (\n\tat ) — everything after that is noise
        int stackStart = desc.indexOf("\n\tat ");
        String firstLine = stackStart >= 0 ? desc.substring(0, stackStart).trim() : desc.trim();

        // Strip leading "java.lang.RuntimeException: " / "java.lang.IllegalStateException: " etc.
        // Walk the "Caused by:" chain to reach the innermost meaningful message.
        String[] lines = firstLine.split("\\r?\\n");
        String meaningful = lines[0];
        for (String line : lines) {
            String trimmed = line.trim();
            if (trimmed.startsWith("Caused by:")) {
                meaningful = trimmed.substring("Caused by:".length()).trim();
            }
        }

        // Remove fully-qualified exception class name prefix (e.g. "java.lang.RuntimeException: ")
        int colon = meaningful.indexOf(": ");
        if (colon > 0 && meaningful.substring(0, colon).matches("[\\w.$]+Exception|[\\w.$]+Error|[\\w.$]+RuntimeException")) {
            meaningful = meaningful.substring(colon + 2).trim();
        } else if (colon > 0) {
            // Strip any dotted class name prefix before the first colon (handles "com.foo.Bar: msg")
            String prefix = meaningful.substring(0, colon);
            if (prefix.contains(".") && !prefix.contains(" ")) {
                meaningful = meaningful.substring(colon + 2).trim();
            }
        }

        return meaningful.isBlank() ? firstLine : meaningful;
    }

    /**
     * Map Spring Batch status to our simplified status.
     *
     * Status semantics:
     * - RUNNING: Job is actively executing
     * - COMPLETED: Job finished successfully
     * - FAILED: Job failed with error (can restart)
     * - CANCELLED: User explicitly cancelled (STOPPED) - can start fresh
     * - INTERRUPTED: Server crash/restart (UNKNOWN) - can restart
     */
    private String mapBatchStatus(BatchStatus batchStatus) {
        return switch (batchStatus) {
            case STARTING, STARTED -> "RUNNING";
            case STOPPING -> "RUNNING"; // Still running while stopping
            case STOPPED -> "CANCELLED"; // User explicitly cancelled
            case COMPLETED -> "COMPLETED";
            case FAILED -> "FAILED";
            case ABANDONED -> "FAILED";
            case UNKNOWN -> "INTERRUPTED"; // Server crash/restart - can restart
        };
    }

    /**
     * DTO for ingestion job status
     */
    public record BatchIngestionStatus(
        Long jobExecutionId,
        String connectionId,
        String status,
        String currentStep,
        Integer progressPct,
        Long bytesProcessed,
        Long eventsProcessed,
        Integer slowQueriesFound,
        String providerType,
        String logSource,
        String message,
        String historyId,
        Instant startTime,
        Instant endTime,
        Instant lastUpdated
    ) {
        public boolean isTerminal() {
            return "COMPLETED".equals(status) || "FAILED".equals(status) || "CANCELLED".equals(status);
        }

        /**
         * Jobs that can be restarted:
         * - INTERRUPTED (server crash/restart)
         * - FAILED (job error)
         */
        public boolean isResumable() {
            return "INTERRUPTED".equals(status) || "FAILED".equals(status);
        }

        /**
         * Check if job was explicitly cancelled by user.
         */
        public boolean isCancelled() {
            return "CANCELLED".equals(status);
        }

        public boolean isRunning() {
            return "RUNNING".equals(status);
        }
    }
}
