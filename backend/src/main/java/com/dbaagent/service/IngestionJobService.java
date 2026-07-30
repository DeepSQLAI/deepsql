package com.dbaagent.service;

import com.dbaagent.dto.SlowLogIngestRequest;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.IngestionJob;
import com.dbaagent.model.IngestionJobEntity;
import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.IngestionJobRepository;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import com.dbaagent.security.EncryptionService;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

/**
 * Service for managing async ingestion jobs with database persistence.
 * Jobs survive server restarts and can be resumed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class IngestionJobService {

    private static final int CLOUDWATCH_MAX_EVENTS = 10000;
    private static final Duration HEARTBEAT_TIMEOUT = Duration.ofMinutes(5);
    private static final Duration JOB_RETENTION = Duration.ofDays(7);

    private final IngestionJobRepository jobRepository;
    private final SlowLogSourceConfigRepository configRepository;
    private final EncryptionService encryptionService;
    private final S3LogFetchService s3LogFetchService;
    private final CloudWatchLogFetchService cloudWatchLogFetchService;
    private final SlowQueryLogParserService slowQueryLogParserService;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final CredentialService credentialService;

    @Value("${server.instance.id:#{T(java.util.UUID).randomUUID().toString().substring(0,8)}}")
    private String serverInstanceId;

    @Value("${slow-query.log.max-bytes:524288000}")
    private long maxLogBytes;

    private String resolvedInstanceId;

    @PostConstruct
    public void init() {
        resolvedInstanceId = resolveInstanceId();
        log.info("IngestionJobService initialized with instance ID: {}", resolvedInstanceId);
        recoverInterruptedJobs();
    }

    /**
     * On startup, mark any RUNNING jobs as INTERRUPTED (they didn't complete before restart)
     */
    @Transactional
    public void recoverInterruptedJobs() {
        List<IngestionJobEntity> activeJobs = jobRepository.findAllActiveJobs();

        if (activeJobs.isEmpty()) {
            log.info("No active jobs to recover on startup");
            return;
        }

        log.info("Found {} active jobs from previous server instance - marking as interrupted", activeJobs.size());

        for (IngestionJobEntity job : activeJobs) {
            job.markInterrupted();
            jobRepository.save(job);
            log.info("Marked job {} as interrupted (was {} at {}% progress)",
                job.getJobId(), job.getStatus(), job.getProgress());
        }
    }

    /**
     * Periodically check for stale jobs (no heartbeat) and mark them as interrupted
     */
    @Transactional
    public void checkStaleJobs() {
        Instant cutoff = Instant.now().minus(HEARTBEAT_TIMEOUT);
        List<IngestionJobEntity> staleJobs = jobRepository.findStaleRunningJobs(cutoff);

        for (IngestionJobEntity job : staleJobs) {
            // Only mark as interrupted if it's not owned by this instance
            // (our own jobs should still be getting heartbeats)
            if (!resolvedInstanceId.equals(job.getServerInstanceId())) {
                job.markInterrupted();
                jobRepository.save(job);
                log.warn("Marked stale job {} as interrupted (no heartbeat since {})",
                    job.getJobId(), job.getLastHeartbeat());
            }
        }
    }

    /**
     * Cleanup old completed jobs periodically
     */
    @Transactional
    public void cleanupOldJobs() {
        Instant cutoff = Instant.now().minus(JOB_RETENTION);
        jobRepository.deleteOldCompletedJobs(cutoff);
        log.info("Cleaned up old completed jobs before {}", cutoff);
    }

    /**
     * Start a new async ingestion job (persisted to database)
     */
    @Transactional
    public IngestionJob startJob(SlowLogIngestRequest request) {
        if (request == null || request.getConnectionId() == null) {
            throw new IllegalArgumentException("connectionId is required");
        }

        // Check for existing active job
        Optional<IngestionJobEntity> existingActive = jobRepository.findActiveJobForConnection(request.getConnectionId());
        if (existingActive.isPresent()) {
            IngestionJobEntity existing = existingActive.get();
            if (existing.getStatus() == IngestionJobEntity.Status.INTERRUPTED) {
                // Resume the interrupted job
                log.info("Found interrupted job {} - resuming", existing.getJobId());
                return resumeJob(existing.getJobId());
            }
            // Return existing active job
            log.info("Returning existing active job {} for connection {}",
                existing.getJobId(), request.getConnectionId());
            return existing.toDto();
        }

        Optional<SlowLogSourceConfig> configOpt = configRepository.findByConnectionId(request.getConnectionId());
        if (configOpt.isEmpty()) {
            throw new IllegalStateException("No slow log source configured for this connection");
        }

        SlowLogSourceConfig config = configOpt.get();
        String provider = config.getProviderType() != null
            ? config.getProviderType().toUpperCase(Locale.ROOT)
            : "UNKNOWN";

        // Create and persist job
        String jobId = UUID.randomUUID().toString();
        IngestionJobEntity job = IngestionJobEntity.builder()
            .jobId(jobId)
            .connectionId(request.getConnectionId())
            .status(IngestionJobEntity.Status.PENDING)
            .progress(0)
            .currentStep("Initializing...")
            .bytesProcessed(0)
            .slowQueriesFound(0)
            .totalStreams(0)
            .streamsProcessed(0)
            .createdAt(Instant.now())
            .lastHeartbeat(Instant.now())
            .timeRange(request.getTimeRange())
            .providerType(provider)
            .logSource(provider.equals("CLOUDWATCH") ? config.getLogGroupName() : config.getBucketName())
            .cancelRequested(false)
            .resumeAttempts(0)
            .serverInstanceId(resolvedInstanceId)
            .build();

        jobRepository.save(job);
        log.info("Created ingestion job {} for connection {}", jobId, request.getConnectionId());

        // Start async execution
        executeJobAsync(jobId, request, config);

        return job.toDto();
    }

    /**
     * Resume an interrupted job
     */
    @Transactional
    public IngestionJob resumeJob(String jobId) {
        IngestionJobEntity job = jobRepository.findById(jobId)
            .orElseThrow(() -> new IllegalArgumentException("Job not found: " + jobId));

        if (!job.isResumable()) {
            throw new IllegalStateException("Job cannot be resumed: " + job.getStatus());
        }

        Optional<SlowLogSourceConfig> configOpt = configRepository.findByConnectionId(job.getConnectionId());
        if (configOpt.isEmpty()) {
            job.fail("Source configuration no longer exists");
            jobRepository.save(job);
            return job.toDto();
        }

        SlowLogSourceConfig config = configOpt.get();

        // Prepare for resume
        job.prepareForResume();
        job.setServerInstanceId(resolvedInstanceId);
        jobRepository.save(job);

        log.info("Resuming job {} (attempt {})", jobId, job.getResumeAttempts());

        // Build request from saved config
        SlowLogIngestRequest request = new SlowLogIngestRequest();
        request.setConnectionId(job.getConnectionId());
        request.setTimeRange(job.getTimeRange());

        // Start async execution
        executeJobAsync(jobId, request, config);

        return job.toDto();
    }

    /**
     * Get job status by ID
     */
    public Optional<IngestionJob> getJob(String jobId) {
        return jobRepository.findById(jobId).map(IngestionJobEntity::toDto);
    }

    /**
     * Get active job for a connection (if any)
     */
    public Optional<IngestionJob> getActiveJobForConnection(String connectionId) {
        return jobRepository.findActiveJobForConnection(connectionId)
            .map(IngestionJobEntity::toDto);
    }

    /**
     * Get recent jobs for a connection
     */
    public List<IngestionJob> getRecentJobsForConnection(String connectionId, int limit) {
        return jobRepository.findRecentJobsForConnection(connectionId, Math.min(limit, 20))
            .stream()
            .map(IngestionJobEntity::toDto)
            .toList();
    }

    /**
     * Cancel a running job
     */
    @Transactional
    public boolean cancelJob(String jobId) {
        Optional<IngestionJobEntity> jobOpt = jobRepository.findById(jobId);
        if (jobOpt.isEmpty()) {
            log.warn("Cancel requested for unknown job: {}", jobId);
            return false;
        }

        IngestionJobEntity job = jobOpt.get();
        if (job.isTerminal()) {
            log.warn("Cannot cancel job {} - already in terminal state: {}", jobId, job.getStatus());
            return false;
        }

        job.requestCancel();
        jobRepository.save(job);
        log.info("Cancellation requested for job {}", jobId);
        return true;
    }

    /**
     * Execute ingestion job asynchronously
     */
    @Async
    public void executeJobAsync(String jobId, SlowLogIngestRequest request, SlowLogSourceConfig config) {
        IngestionJobEntity job = jobRepository.findById(jobId).orElse(null);
        if (job == null) {
            log.error("Job {} not found for execution", jobId);
            return;
        }

        try {
            job.setStatus(IngestionJobEntity.Status.RUNNING);
            job.setStartedAt(Instant.now());
            updateProgress(job, 5, "Connecting to AWS...");

            // Check for cancellation
            if (checkCancelled(job)) return;

            // Resolve credentials
            S3LogFetchService.AwsCredentialsInput creds = new S3LogFetchService.AwsCredentialsInput(
                encryptionService.decryptIfPresent(config.getAccessKeyId(), config.getConnectionId()),
                encryptionService.decryptIfPresent(config.getSecretAccessKey(), config.getConnectionId()),
                encryptionService.decryptIfPresent(config.getSessionToken(), config.getConnectionId())
            );

            updateProgress(job, 10, "Fetching log data...");
            if (checkCancelled(job)) return;

            // Fetch logs (with checkpoint support for resume)
            Instant resumeFrom = job.getResumeAttempts() > 0 ? job.getLastProcessedEventTime() : null;

            try (InputStream logStream = fetchLogStream(job, request, config, creds, resumeFrom);
                 SlowQueryLogParserService.TempLogFile tempLog = slowQueryLogParserService.copyToTempFile(logStream)) {

                if (checkCancelled(job)) return;

                if (tempLog.size() == 0) {
                    String timeRange = request.getTimeRange();
                    if ("SINCE_LAST".equalsIgnoreCase(timeRange)) {
                        failJob(job, "No new log events since last ingestion. This is normal if no new slow queries have occurred.");
                    } else if (timeRange != null && timeRange.toUpperCase().contains("LAST_")) {
                        failJob(job, "No log events found in the selected time range. Try 'All Available' for initial ingestion.");
                    } else {
                        failJob(job, "No log events found. Check that the log group/stream contains data.");
                    }
                    return;
                }

                job.setBytesProcessed(tempLog.size());
                updateProgress(job, 60, "Parsing slow queries...");

                if (checkCancelled(job)) return;

                // Parse logs
                String dbType = resolveDatabaseType(config.getConnectionId());
                SlowQueryAnalysis analysis = slowQueryLogParserService.parseAndAnalyze(
                    tempLog.path(),
                    dbType,
                    config.getConnectionId()
                );

                if (checkCancelled(job)) return;

                if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                    failJob(job, "Log data fetched but no slow queries found in the logs.");
                    return;
                }

                updateProgress(job, 80, "Saving analysis results...");

                if (checkCancelled(job)) return;

                // Save to history
                SlowQueryHistory history = slowQueryHistoryService.saveAnalysis(
                    config.getConnectionId(),
                    analysis,
                    null
                );

                // Update last processed timestamp
                updateLastProcessed(config);

                // Save checkpoint
                job.setLastProcessedEventTime(Instant.now());

                updateProgress(job, 95, "Finalizing...");

                // Complete the job
                int slowQueriesFound = analysis.getTopSlowQueries().size();
                boolean truncated = job.isTruncated() || analysis.isTruncated();
                String message = truncated
                    ? String.format(
                        "Analyzed %d slow-query patterns from %s, but hit the %d-byte size limit; results are partial. Narrow the time window or raise slow-query.log.max-bytes for full coverage.",
                        slowQueriesFound, job.getProviderType(), maxLogBytes)
                    : String.format("Successfully ingested %d slow query patterns from %s",
                        slowQueriesFound, job.getProviderType());
                completeJob(job, message, history.getId(), slowQueriesFound, truncated);

                log.info("Ingestion job {} completed: {}", job.getJobId(), message);
            }

        } catch (Exception e) {
            log.error("Ingestion job {} failed: {}", job.getJobId(), e.getMessage(), e);
            failJob(job, "Ingestion failed: " + e.getMessage());
        }
    }

    /**
     * Update job progress and persist to database.
     * Note: Called from async context, so we persist directly without relying on proxy.
     */
    private void updateProgress(IngestionJobEntity job, int progress, String step) {
        job.updateProgress(progress, step);
        jobRepository.save(job);
    }

    /**
     * Mark job as completed and persist.
     * Note: Called from async context, so we persist directly without relying on proxy.
     */
    private void completeJob(IngestionJobEntity job, String message, String historyId, int slowQueriesFound) {
        job.complete(message, historyId, slowQueriesFound);
        jobRepository.save(job);
    }

    /**
     * Mark job as completed with truncation flag and persist.
     */
    private void completeJob(IngestionJobEntity job, String message, String historyId, int slowQueriesFound, boolean truncated) {
        job.complete(message, historyId, slowQueriesFound, truncated);
        jobRepository.save(job);
    }

    /**
     * Mark job as failed and persist.
     * Note: Called from async context, so we persist directly without relying on proxy.
     */
    private void failJob(IngestionJobEntity job, String errorMessage) {
        job.fail(errorMessage);
        jobRepository.save(job);
    }

    /**
     * Fetch log stream with progress updates
     */
    private InputStream fetchLogStream(IngestionJobEntity job, SlowLogIngestRequest request,
                                       SlowLogSourceConfig config, S3LogFetchService.AwsCredentialsInput creds,
                                       Instant resumeFrom) {
        String provider = config.getProviderType() != null
            ? config.getProviderType().toUpperCase(Locale.ROOT)
            : "";

        if (provider.equals("CLOUDWATCH")) {
            return fetchCloudWatchLogs(job, request, config, creds, resumeFrom);
        }
        return fetchS3Logs(job, config, creds);
    }

    /**
     * Fetch logs from CloudWatch with progress updates
     */
    private InputStream fetchCloudWatchLogs(IngestionJobEntity job, SlowLogIngestRequest request,
                                            SlowLogSourceConfig config, S3LogFetchService.AwsCredentialsInput creds,
                                            Instant resumeFrom) {
        if (config.getLogGroupName() == null || config.getLogGroupName().isBlank()) {
            throw new IllegalArgumentException("CloudWatch log group must be configured");
        }
        if (config.getS3Region() == null || config.getS3Region().isBlank()) {
            throw new IllegalArgumentException("AWS region must be configured");
        }

        updateProgress(job, 15, "Listing CloudWatch log streams...");

        int maxEvents = request.getMaxLogs() != null
            ? Math.min(request.getMaxLogs(), CLOUDWATCH_MAX_EVENTS)
            : CLOUDWATCH_MAX_EVENTS;

        // Use resume checkpoint if available, otherwise resolve from request
        Instant startTime = resumeFrom != null ? resumeFrom : resolveStartTime(request, config);

        updateProgress(job, 25, "Fetching log events...");

        try {
            LogFetchResult fetchResult = cloudWatchLogFetchService.downloadLatestLogsResult(
                config.getLogGroupName(),
                config.getLogStreamPrefix(),
                config.getS3Region(),
                creds,
                startTime,
                maxEvents
            );
            if (fetchResult.truncated()) {
                job.setTruncated(true);
            }
            updateProgress(job, 50, "Processing log data...");
            return fetchResult.stream();
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch CloudWatch logs: " + e.getMessage(), e);
        }
    }

    /**
     * Fetch logs from S3 with progress updates
     */
    private InputStream fetchS3Logs(IngestionJobEntity job, SlowLogSourceConfig config,
                                    S3LogFetchService.AwsCredentialsInput creds) {
        String prefix = config.getObjectPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("S3 object prefix must be configured");
        }
        if (config.getBucketName() == null || config.getBucketName().isBlank()) {
            throw new IllegalArgumentException("S3 bucket must be configured");
        }

        updateProgress(job, 15, "Finding latest S3 object...");

        Optional<software.amazon.awssdk.services.s3.model.S3Object> latest = s3LogFetchService.findLatestObject(
            config.getBucketName(),
            prefix,
            config.getS3Region(),
            creds,
            config.getLastProcessedAt() != null
                ? config.getLastProcessedAt().atZone(ZoneOffset.UTC).toInstant()
                : null
        );

        if (latest.isEmpty()) {
            throw new IllegalStateException("No new S3 objects found for prefix");
        }

        updateProgress(job, 30, "Downloading S3 object...");

        try {
            InputStream input = s3LogFetchService.downloadObject(
                config.getBucketName(),
                latest.get().key(),
                config.getS3Region(),
                creds
            );
            updateProgress(job, 50, "Processing log data...");
            return input;
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch S3 logs: " + e.getMessage(), e);
        }
    }

    /**
     * Check if job was cancelled and update status if so
     */
    private boolean checkCancelled(IngestionJobEntity job) {
        // Refresh from database to get latest cancel status
        IngestionJobEntity refreshed = jobRepository.findById(job.getJobId()).orElse(job);
        if (refreshed.isCancelRequested()) {
            refreshed.cancel();
            jobRepository.save(refreshed);
            log.info("Ingestion job {} was cancelled", job.getJobId());
            return true;
        }
        return false;
    }

    /**
     * Resolve start time based on request parameters
     */
    private Instant resolveStartTime(SlowLogIngestRequest request, SlowLogSourceConfig config) {
        String timeRange = request.getTimeRange();

        if (timeRange != null && !timeRange.isBlank()) {
            Instant now = Instant.now();
            return switch (timeRange.toUpperCase(Locale.ROOT)) {
                case "LAST_24_HOURS" -> now.minus(Duration.ofHours(24));
                case "LAST_7_DAYS" -> now.minus(Duration.ofDays(7));
                case "LAST_30_DAYS" -> now.minus(Duration.ofDays(30));
                case "ALL" -> null;
                case "SINCE_LAST" -> config.getLastProcessedAt() != null
                    ? config.getLastProcessedAt().atZone(ZoneOffset.UTC).toInstant()
                    : null;
                default -> config.getLastProcessedAt() != null
                    ? config.getLastProcessedAt().atZone(ZoneOffset.UTC).toInstant()
                    : null;
            };
        }

        if (request.getStartTime() != null) {
            return request.getStartTime();
        }

        return config.getLastProcessedAt() != null
            ? config.getLastProcessedAt().atZone(ZoneOffset.UTC).toInstant()
            : null;
    }

    /**
     * Update last processed timestamp
     */
    private void updateLastProcessed(SlowLogSourceConfig config) {
        config.setLastProcessedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        configRepository.save(config);
    }

    /**
     * Resolve database type for connection
     */
    private String resolveDatabaseType(String connectionId) {
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            if (connection != null && connection.getDbType() != null && !connection.getDbType().isBlank()) {
                return connection.getDbType().toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve database type, defaulting to MySQL", e);
        }
        return "mysql";
    }

    /**
     * Resolve server instance ID for multi-instance deployments
     */
    private String resolveInstanceId() {
        try {
            // Try to get hostname
            String hostname = InetAddress.getLocalHost().getHostName();
            return hostname + "-" + serverInstanceId;
        } catch (Exception e) {
            return "unknown-" + serverInstanceId;
        }
    }
}
