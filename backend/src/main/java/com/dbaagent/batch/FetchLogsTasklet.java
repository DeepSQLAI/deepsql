package com.dbaagent.batch;

import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.service.CloudWatchLogFetchService;
import com.dbaagent.service.S3LogFetchService;
import com.dbaagent.service.SlowQueryLogParserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.StepScope;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.scope.context.ChunkContext;
import org.springframework.batch.core.step.StepContribution;
import org.springframework.batch.core.step.tasklet.Tasklet;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.batch.infrastructure.repeat.RepeatStatus;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Locale;
import java.util.Optional;

/**
 * First step of ingestion job: Fetches logs from CloudWatch or S3.
 *
 * Reads job parameters:
 * - connectionId: Database connection to fetch logs for
 * - timeRange: Time range filter (LAST_24_HOURS, LAST_7_DAYS, ALL, SINCE_LAST)
 * - maxLogs: Maximum number of log events to fetch
 *
 * Stores in ExecutionContext (for next step):
 * - tempFilePath: Path to downloaded temp file
 * - bytesProcessed: Size of downloaded data
 * - providerType: S3 or CLOUDWATCH
 * - logSource: Log group name or bucket name
 */
@Component
@StepScope
@RequiredArgsConstructor
@Slf4j
public class FetchLogsTasklet implements Tasklet {

    private static final int CLOUDWATCH_MAX_EVENTS = 10000;

    private final SlowLogSourceConfigRepository configRepository;
    private final EncryptionService encryptionService;
    private final S3LogFetchService s3LogFetchService;
    private final CloudWatchLogFetchService cloudWatchLogFetchService;
    private final SlowQueryLogParserService slowQueryLogParserService;
    private final BatchProgressService progressService;

    @Value("#{jobParameters['connectionId']}")
    private String connectionId;

    @Value("#{jobParameters['timeRange']}")
    private String timeRange;

    @Value("#{jobParameters['maxLogs']}")
    private Long maxLogs;

    @Override
    public RepeatStatus execute(StepContribution contribution, ChunkContext chunkContext) throws Exception {
        ExecutionContext stepContext = chunkContext.getStepContext().getStepExecution().getExecutionContext();
        ExecutionContext jobContext = chunkContext.getStepContext().getStepExecution().getJobExecution().getExecutionContext();

        // Check for termination request before starting
        if (contribution.getStepExecution().isTerminateOnly()) {
            log.info("Job termination requested before fetch started for connection: {}", connectionId);
            jobContext.putString("message", "Job cancelled before fetch started");
            return RepeatStatus.FINISHED;
        }

        log.info("Starting log fetch for connection: {}, timeRange: {}", connectionId, timeRange);

        JobExecution jobExecution = chunkContext.getStepContext().getStepExecution().getJobExecution();

        // Update progress
        jobContext.putString("currentStep", "Loading configuration...");
        jobContext.putInt("progressPct", 5);
        persistProgress(jobExecution);

        // Load source config
        Optional<SlowLogSourceConfig> configOpt = configRepository.findByConnectionId(connectionId);
        if (configOpt.isEmpty()) {
            throw new IllegalStateException("No slow log source configured for connection: " + connectionId);
        }

        SlowLogSourceConfig config = configOpt.get();
        String provider = config.getProviderType() != null
            ? config.getProviderType().toUpperCase(Locale.ROOT)
            : "UNKNOWN";

        // Store provider info for UI
        jobContext.putString("providerType", provider);
        jobContext.putString("logSource", provider.equals("CLOUDWATCH")
            ? config.getLogGroupName()
            : config.getBucketName());
        persistProgress(jobExecution);

        // Resolve credentials
        jobContext.putString("currentStep", "Connecting to AWS...");
        jobContext.putInt("progressPct", 10);
        persistProgress(jobExecution);

        S3LogFetchService.AwsCredentialsInput creds = new S3LogFetchService.AwsCredentialsInput(
            encryptionService.decryptIfPresent(config.getAccessKeyId(), connectionId),
            encryptionService.decryptIfPresent(config.getSecretAccessKey(), connectionId),
            encryptionService.decryptIfPresent(config.getSessionToken(), connectionId)
        );

        // Check for termination before expensive fetch operation
        if (contribution.getStepExecution().isTerminateOnly()) {
            log.info("Job termination requested before fetch for connection: {}", connectionId);
            jobContext.putString("message", "Job cancelled before fetch");
            return RepeatStatus.FINISHED;
        }

        // Fetch logs
        jobContext.putString("currentStep", "Fetching log data...");
        jobContext.putInt("progressPct", 20);
        persistProgress(jobExecution);
        try (InputStream logStream = fetchLogStream(config, creds, jobExecution);
             SlowQueryLogParserService.TempLogFile tempLog = slowQueryLogParserService.copyToTempFile(logStream)) {

            if (tempLog.size() == 0) {
                String message = resolveEmptyLogsMessage();
                throw new IllegalStateException(message);
            }

            // Store results for next step
            stepContext.putString("tempFilePath", tempLog.path().toString());
            stepContext.putLong("bytesProcessed", tempLog.size());
            stepContext.putString("connectionId", connectionId);

            // Update job context for UI
            jobContext.putLong("bytesProcessed", tempLog.size());
            jobContext.putString("currentStep", "Log data fetched");
            jobContext.putInt("progressPct", 50);

            log.info("Fetched {} bytes of log data from {}", tempLog.size(), provider);

            // Keep temp file open - return path, don't delete yet
            // The temp file will be cleaned up by ParseLogsTasklet
            tempLog.keepOpen();
        }

        return RepeatStatus.FINISHED;
    }

    private InputStream fetchLogStream(SlowLogSourceConfig config,
                                        S3LogFetchService.AwsCredentialsInput creds,
                                        JobExecution jobExecution) {
        String provider = config.getProviderType() != null
            ? config.getProviderType().toUpperCase(Locale.ROOT)
            : "";

        if (provider.equals("CLOUDWATCH")) {
            return fetchCloudWatchLogs(config, creds, jobExecution);
        }
        return fetchS3Logs(config, creds, jobExecution.getExecutionContext());
    }

    private InputStream fetchCloudWatchLogs(SlowLogSourceConfig config,
                                            S3LogFetchService.AwsCredentialsInput creds,
                                            JobExecution jobExecution) {
        ExecutionContext jobContext = jobExecution.getExecutionContext();

        if (config.getLogGroupName() == null || config.getLogGroupName().isBlank()) {
            throw new IllegalArgumentException("CloudWatch log group must be configured");
        }
        if (config.getS3Region() == null || config.getS3Region().isBlank()) {
            throw new IllegalArgumentException("AWS region must be configured");
        }

        jobContext.putString("currentStep", "Listing CloudWatch log streams...");
        jobContext.putInt("progressPct", 25);

        int effectiveMaxLogs = maxLogs != null
            ? Math.min(maxLogs.intValue(), CLOUDWATCH_MAX_EVENTS)
            : CLOUDWATCH_MAX_EVENTS;

        Instant startTime = resolveStartTime(config);

        jobContext.putString("currentStep", "Fetching CloudWatch log events...");
        jobContext.putInt("progressPct", 35);

        // Progress callback to update job context in real-time and persist to DB
        // Uses REQUIRES_NEW transaction via progressService so updates are immediately visible
        CloudWatchLogFetchService.ProgressCallback progressCallback = (eventsProcessed, bytesWritten) -> {
            jobContext.putLong("eventsProcessed", eventsProcessed);
            if (bytesWritten > 0) {
                jobContext.putLong("bytesProcessed", bytesWritten);
            }
            // Update progress message with event count
            jobContext.putString("currentStep", String.format("Fetching events... (%,d processed)", eventsProcessed));

            // Persist to database in a new transaction so status endpoint can read it
            progressService.persistProgress(jobExecution);
        };

        try {
            return cloudWatchLogFetchService.downloadLatestLogs(
                config.getLogGroupName(),
                config.getLogStreamPrefix(),
                config.getS3Region(),
                creds,
                startTime,
                effectiveMaxLogs,
                progressCallback
            );
        } catch (Exception e) {
            throw new RuntimeException(cloudWatchUserMessage(e, config.getS3Region()), e);
        }
    }

    /**
     * Translates raw AWS SDK exceptions into concise, actionable messages for the UI.
     * Walks the cause chain so that wrapped SdkClientExceptions are handled correctly.
     */
    private static String cloudWatchUserMessage(Throwable e, String region) {
        Throwable cause = e;
        while (cause != null) {
            if (cause instanceof java.net.UnknownHostException) {
                return String.format(
                    "Cannot resolve AWS CloudWatch endpoint for region '%s'. " +
                    "Check network connectivity and that the region is correct.", region);
            }
            if (cause instanceof java.net.ConnectException) {
                return String.format(
                    "Connection refused reaching AWS CloudWatch (region '%s'). " +
                    "Check network connectivity.", region);
            }
            if (cause instanceof java.net.SocketTimeoutException ||
                cause instanceof java.net.SocketException) {
                return String.format(
                    "Network timeout fetching CloudWatch logs (region '%s'). " +
                    "Check connectivity and try again.", region);
            }
            if (cause instanceof software.amazon.awssdk.core.exception.SdkClientException) {
                // SdkClientException wraps network errors — use its message unless we already
                // matched the inner cause above
                String msg = cause.getMessage();
                if (msg != null && msg.contains("UnknownHostException")) {
                    return String.format(
                        "Cannot resolve AWS CloudWatch endpoint for region '%s'. " +
                        "Check network connectivity and that the region is correct.", region);
                }
            }
            cause = cause.getCause();
        }
        // Generic fallback keeps the original message concise (no stack noise)
        String raw = e.getMessage();
        return "Failed to fetch CloudWatch logs: " + (raw != null ? raw : e.getClass().getSimpleName());
    }

    private InputStream fetchS3Logs(SlowLogSourceConfig config,
                                    S3LogFetchService.AwsCredentialsInput creds,
                                    ExecutionContext jobContext) {
        String prefix = config.getObjectPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("S3 object prefix must be configured");
        }
        if (config.getBucketName() == null || config.getBucketName().isBlank()) {
            throw new IllegalArgumentException("S3 bucket must be configured");
        }

        jobContext.putString("currentStep", "Finding latest S3 object...");
        jobContext.putInt("progressPct", 25);

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
            throw new IllegalStateException("No new S3 objects found for prefix: " + prefix);
        }

        jobContext.putString("currentStep", "Downloading S3 object...");
        jobContext.putInt("progressPct", 35);

        try {
            return s3LogFetchService.downloadObject(
                config.getBucketName(),
                latest.get().key(),
                config.getS3Region(),
                creds
            );
        } catch (Exception e) {
            throw new RuntimeException("Failed to fetch S3 logs: " + e.getMessage(), e);
        }
    }

    private Instant resolveStartTime(SlowLogSourceConfig config) {
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
        return config.getLastProcessedAt() != null
            ? config.getLastProcessedAt().atZone(ZoneOffset.UTC).toInstant()
            : null;
    }

    private String resolveEmptyLogsMessage() {
        if ("SINCE_LAST".equalsIgnoreCase(timeRange)) {
            return "No new log events since last ingestion. This is normal if no new slow queries have occurred.";
        } else if (timeRange != null && timeRange.toUpperCase().contains("LAST_")) {
            return "No log events found in the selected time range. Try 'All Available' for initial ingestion.";
        }
        return "No log events found. Check that the log group/stream contains data.";
    }

    /**
     * Persist execution context to database so status endpoint can read it.
     * Uses BatchProgressService which commits in a REQUIRES_NEW transaction.
     */
    private void persistProgress(JobExecution jobExecution) {
        ExecutionContext ctx = jobExecution.getExecutionContext();
        log.info("Persisting progress: currentStep={}, progressPct={}, providerType={}",
            ctx.getString("currentStep", ""),
            ctx.containsKey("progressPct") ? ctx.get("progressPct") : "null",
            ctx.getString("providerType", "null"));
        progressService.persistProgress(jobExecution);
    }
}
