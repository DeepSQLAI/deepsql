package com.dbaagent.service;

import com.dbaagent.dto.SlowLogIngestRequest;
import com.dbaagent.exception.LogSizeExceededException;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.SlowQueryRun;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import com.dbaagent.repository.SlowQueryRunRepository;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.service.brain.query.AdaptivePlanScoringService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;

@Service
@RequiredArgsConstructor
@Slf4j
public class SlowLogIngestionService {
    private static final int DEFAULT_MAX_LOGS = 10000;

    // Track active EXPLAIN backfills per connection to prevent duplicate backfills
    private static final ConcurrentHashMap<String, AtomicBoolean> activeBackfills = new ConcurrentHashMap<>();

    // Global semaphore to limit total concurrent EXPLAIN backfills across all connections
    // Prevents unbounded parallelism even with many simultaneous ingestions
    private static final Semaphore backfillSemaphore = new Semaphore(3);

    private final SlowLogSourceConfigRepository configRepository;
    private final EncryptionService encryptionService;
    private final S3LogFetchService s3LogFetchService;
    private final CloudWatchLogFetchService cloudWatchLogFetchService;
    private final AzureBlobLogFetchService azureBlobLogFetchService;
    private final GcpCloudLoggingFetchService gcpCloudLoggingFetchService;
    private final DatadogLogFetchService datadogLogFetchService;
    private final ElasticsearchLogFetchService elasticsearchLogFetchService;
    private final SlowQueryLogParserService slowQueryLogParserService;
    private final SlowQueryHistoryService slowQueryHistoryService;
    private final CredentialService credentialService;
    private final QueryPerformanceService queryPerformanceService;
    private final QueryFingerprintService queryFingerprintService;
    private final SlowQueryAlertService slowQueryAlertService;
    private final AdaptivePlanScoringService adaptivePlanScoringService;
    private final SlowQueryCustomerAttributionService customerAttributionService;
    // Wired in as part of the slow-query unification (one path: log → vault).
    // Was split between this service and SlowQueryDailyAnalysisService until
    // the path that fed slow_query_run was a pg_stat_statements read while
    // the path that fed slow_query_sample was a log read — making the two
    // tables structurally diverge for the same query. They now share this
    // service so log ingestion writes the full picture in one pass.
    private final SlowQueryRunRepository slowQueryRunRepository;
    private final SlowQueryRetentionService slowQueryRetentionService;
    private final SlowQueryAnalyticsService slowQueryAnalyticsService;

    public record IngestResult(boolean success, String message, SlowQueryHistory history) {
        public static IngestResult failure(String message) {
            return new IngestResult(false, message, null);
        }
        public static IngestResult success(String message, SlowQueryHistory history) {
            return new IngestResult(true, message, history);
        }
    }

    public IngestResult ingestNowWithDetails(SlowLogIngestRequest request) {
        if (request == null || request.getConnectionId() == null) {
            return IngestResult.failure("Invalid request: connectionId is required");
        }

        Optional<SlowLogSourceConfig> configOpt = configRepository.findByConnectionId(request.getConnectionId());
        if (configOpt.isEmpty()) {
            return IngestResult.failure("No slow log source configured. Please configure a log source (S3, CloudWatch, Azure Blob, GCP Logging, Datadog, or Elasticsearch).");
        }

        SlowLogSourceConfig config = configOpt.get();

        String provider = config.getProviderType() != null
            ? config.getProviderType().toUpperCase(Locale.ROOT)
            : "";

        // Validate that the request includes the configured provider
        if (!request.includesProvider(provider)) {
            return IngestResult.failure(String.format("%s ingestion not requested but config is %s", provider, provider));
        }

        try (InputStream input = fetchLogStream(config, request);
             SlowQueryLogParserService.TempLogFile tempLog = slowQueryLogParserService.copyToTempFile(input)) {
            if (tempLog.size() == 0) {
                String timeRange = request.getTimeRange();
                if ("SINCE_LAST".equalsIgnoreCase(timeRange)) {
                    return IngestResult.failure("No new log events since last ingestion. This is normal if no new slow queries have occurred.");
                } else if (timeRange != null && timeRange.toUpperCase().contains("LAST_")) {
                    return IngestResult.failure("No log events found in the selected time range. CloudWatch filters by event ingestion time, not query execution time. Try 'All Available' for initial ingestion.");
                }
                return IngestResult.failure("No log events found. Check that the log group/stream contains data and credentials have proper permissions.");
            }

            log.info("Fetched {} bytes of log data from {}", tempLog.size(), provider);

            String dbType = resolveDatabaseType(config.getConnectionId());
            SlowQueryAnalysis analysis = slowQueryLogParserService.parseAndAnalyze(
                tempLog.path(),
                dbType,
                config.getConnectionId()
            );

            if (analysis == null || analysis.getTopSlowQueries() == null || analysis.getTopSlowQueries().isEmpty()) {
                return IngestResult.failure("Log data fetched but no slow queries found in the logs. The log format may not match expected MySQL/PostgreSQL slow query format.");
            }

            SlowQueryHistory history = slowQueryHistoryService.saveAnalysis(
                config.getConnectionId(),
                analysis,
                null
            );
            updateLastProcessed(config);

            // Run all post-ingestion processing (fingerprints, alerts, regression,
            // slow_query_run decomposition, attribution, retention).
            postProcessAnalysis(config.getConnectionId(), analysis,
                history != null ? history.getId() : null);

            String message = String.format("Successfully ingested %d slow queries from %s",
                analysis.getTopSlowQueries().size(), provider);
            return IngestResult.success(message, history);
        } catch (LogSizeExceededException e) {
            log.warn("Slow log exceeds max size: {} bytes (max {})", e.getBytesRead(), e.getMaxBytes());
            return IngestResult.failure(String.format(
                "Slow query log exceeds configured max size (%d bytes). Reduce time range or adjust slow-query.log.max-bytes.",
                e.getMaxBytes()));
        } catch (Exception e) {
            log.error("Failed to ingest slow logs for {}: {}", config.getConnectionId(), e.getMessage(), e);
            return IngestResult.failure("Ingestion failed: " + e.getMessage());
        }
    }

    public Optional<SlowQueryHistory> ingestNow(SlowLogIngestRequest request) {
        IngestResult result = ingestNowWithDetails(request);
        return result.success() ? Optional.ofNullable(result.history()) : Optional.empty();
    }

    private InputStream fetchLogStream(SlowLogSourceConfig config, SlowLogIngestRequest request) {
        String provider = config.getProviderType() != null
            ? config.getProviderType().toUpperCase(Locale.ROOT)
            : "";

        Instant resolvedStartTime = resolveStartTime(request, config);
        int maxLogs = request.getMaxLogs() != null ? request.getMaxLogs() : DEFAULT_MAX_LOGS;

        return switch (provider) {
            case "CLOUDWATCH" -> fetchFromCloudWatch(config, resolvedStartTime, maxLogs);
            case "S3" -> fetchFromS3(config);
            case "AZURE_BLOB" -> fetchFromAzureBlob(config, resolvedStartTime);
            case "GCP_LOGGING" -> fetchFromGcpLogging(config, resolvedStartTime, maxLogs);
            case "DATADOG" -> fetchFromDatadog(config, resolvedStartTime, maxLogs);
            case "ELASTICSEARCH" -> fetchFromElasticsearch(config, resolvedStartTime, maxLogs);
            default -> throw new IllegalArgumentException("Unsupported provider type: " + provider);
        };
    }

    private InputStream fetchFromCloudWatch(SlowLogSourceConfig config, Instant startTime, int maxEvents) {
        if (config.getLogGroupName() == null || config.getLogGroupName().isBlank()) {
            throw new IllegalArgumentException("CloudWatch log group must be configured");
        }
        if (config.getS3Region() == null || config.getS3Region().isBlank()) {
            throw new IllegalArgumentException("AWS region must be configured for CloudWatch");
        }

        S3LogFetchService.AwsCredentialsInput creds = buildAwsCredentials(config);

        return cloudWatchLogFetchService.downloadLatestLogs(
            config.getLogGroupName(),
            config.getLogStreamPrefix(),
            config.getS3Region(),
            creds,
            startTime,
            Math.min(maxEvents, DEFAULT_MAX_LOGS)
        );
    }

    private InputStream fetchFromS3(SlowLogSourceConfig config) {
        String prefix = config.getObjectPrefix();
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("S3 object prefix must be configured");
        }
        if (config.getBucketName() == null || config.getBucketName().isBlank()) {
            throw new IllegalArgumentException("S3 bucket must be configured");
        }

        S3LogFetchService.AwsCredentialsInput creds = buildAwsCredentials(config);

        Optional<software.amazon.awssdk.services.s3.model.S3Object> latest = s3LogFetchService.findLatestObject(
            config.getBucketName(),
            prefix,
            config.getS3Region(),
            creds,
            config.getLastProcessedAt() != null
                ? config.getLastProcessedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                : null
        );

        if (latest.isEmpty()) {
            throw new IllegalStateException("No new S3 objects found for prefix");
        }

        return s3LogFetchService.downloadObject(
            config.getBucketName(),
            latest.get().key(),
            config.getS3Region(),
            creds
        );
    }

    private InputStream fetchFromAzureBlob(SlowLogSourceConfig config, Instant startTime) {
        if (config.getAzureContainerName() == null || config.getAzureContainerName().isBlank()) {
            throw new IllegalArgumentException("Azure container name must be configured");
        }

        AzureBlobLogFetchService.AzureCredentialsInput creds = new AzureBlobLogFetchService.AzureCredentialsInput();
        creds.setConnectionString(decryptField(config.getAzureConnectionString(), config.getConnectionId()));
        creds.setAccountName(config.getAzureAccountName());
        creds.setAccountKey(decryptField(config.getAzureAccountKey(), config.getConnectionId()));
        creds.setSasToken(decryptField(config.getAzureSasToken(), config.getConnectionId()));
        creds.setClientId(config.getAzureClientId());
        creds.setClientSecret(decryptField(config.getAzureClientSecret(), config.getConnectionId()));
        creds.setTenantId(config.getAzureTenantId());

        return azureBlobLogFetchService.downloadLatestLog(
            config.getAzureContainerName(),
            config.getAzureBlobPrefix(),
            creds,
            startTime
        );
    }

    private InputStream fetchFromGcpLogging(SlowLogSourceConfig config, Instant startTime, int maxLogs) {
        if (config.getGcpProjectId() == null || config.getGcpProjectId().isBlank()) {
            throw new IllegalArgumentException("GCP project ID must be configured");
        }

        GcpCloudLoggingFetchService.GcpCredentialsInput creds = new GcpCloudLoggingFetchService.GcpCredentialsInput();
        creds.setProjectId(config.getGcpProjectId());

        // Decrypt and set service account JSON if present
        if (config.getGcpServiceAccountJson() != null) {
            String serviceAccountJson = decryptField(config.getGcpServiceAccountJson(), config.getConnectionId());
            creds.setServiceAccountJson(serviceAccountJson);
        }

        // If instance ID is provided, use the Cloud SQL specific method
        if (config.getGcpInstanceId() != null && !config.getGcpInstanceId().isBlank()) {
            String dbType = resolveDatabaseType(config.getConnectionId());
            if ("postgres".equals(dbType)) {
                return gcpCloudLoggingFetchService.downloadCloudSqlPostgresLogs(
                    config.getGcpProjectId(),
                    config.getGcpInstanceId(),
                    creds,
                    startTime,
                    maxLogs
                );
            } else {
                return gcpCloudLoggingFetchService.downloadCloudSqlSlowQueryLogs(
                    config.getGcpProjectId(),
                    config.getGcpInstanceId(),
                    creds,
                    startTime,
                    maxLogs
                );
            }
        }

        // Use custom filter if provided
        return gcpCloudLoggingFetchService.downloadLogs(
            config.getGcpProjectId(),
            config.getGcpLogFilter(),
            creds,
            startTime,
            null,
            maxLogs
        );
    }

    private InputStream fetchFromDatadog(SlowLogSourceConfig config, Instant startTime, int maxLogs) {
        if (config.getDatadogApiKey() == null) {
            throw new IllegalArgumentException("Datadog API key must be configured");
        }

        DatadogLogFetchService.DatadogCredentialsInput creds = new DatadogLogFetchService.DatadogCredentialsInput();
        creds.setApiKey(decryptField(config.getDatadogApiKey(), config.getConnectionId()));
        creds.setApplicationKey(decryptField(config.getDatadogAppKey(), config.getConnectionId()));
        creds.setSite(config.getDatadogSite());

        // Build query - use custom query if provided, otherwise use service name
        String query = config.getDatadogQuery();
        if ((query == null || query.isBlank()) && config.getDatadogServiceName() != null) {
            query = "service:" + config.getDatadogServiceName() + " @db.statement:*";
        }
        if (query == null || query.isBlank()) {
            query = "@db.statement:* OR source:dbm";
        }

        return datadogLogFetchService.downloadLogs(
            query,
            creds,
            startTime,
            null,
            maxLogs
        );
    }

    private InputStream fetchFromElasticsearch(SlowLogSourceConfig config, Instant startTime, int maxLogs) {
        if (config.getElasticsearchHost() == null || config.getElasticsearchHost().isBlank()) {
            throw new IllegalArgumentException("Elasticsearch host must be configured");
        }
        if (config.getElasticsearchIndexPattern() == null || config.getElasticsearchIndexPattern().isBlank()) {
            throw new IllegalArgumentException("Elasticsearch index pattern must be configured");
        }

        ElasticsearchLogFetchService.ElasticsearchCredentialsInput creds = new ElasticsearchLogFetchService.ElasticsearchCredentialsInput();
        creds.setHost(config.getElasticsearchHost());
        creds.setPort(config.getElasticsearchPort());
        creds.setScheme(config.getElasticsearchScheme());
        creds.setUsername(config.getElasticsearchUsername());
        creds.setPassword(decryptField(config.getElasticsearchPassword(), config.getConnectionId()));
        creds.setApiKey(decryptField(config.getElasticsearchApiKey(), config.getConnectionId()));
        creds.setApiKeyId(config.getElasticsearchApiKeyId());
        creds.setVerifySsl(config.getElasticsearchVerifySsl() != null ? config.getElasticsearchVerifySsl() : true);

        return elasticsearchLogFetchService.downloadLogs(
            config.getElasticsearchIndexPattern(),
            config.getElasticsearchQuery(),
            creds,
            startTime,
            null,
            maxLogs,
            "@timestamp"
        );
    }

    private S3LogFetchService.AwsCredentialsInput buildAwsCredentials(SlowLogSourceConfig config) {
        return new S3LogFetchService.AwsCredentialsInput(
            encryptionService.decryptIfPresent(config.getAccessKeyId(), config.getConnectionId()),
            encryptionService.decryptIfPresent(config.getSecretAccessKey(), config.getConnectionId()),
            encryptionService.decryptIfPresent(config.getSessionToken(), config.getConnectionId())
        );
    }

    private String decryptField(byte[] encryptedField, String connectionId) {
        if (encryptedField == null) return null;
        return encryptionService.decryptIfPresent(encryptedField, connectionId);
    }

    /**
     * Resolve the start time for log ingestion based on request parameters.
     * Priority: timeRange preset > explicit startTime > lastProcessedAt from config
     */
    private java.time.Instant resolveStartTime(SlowLogIngestRequest request, SlowLogSourceConfig config) {
        String timeRange = request.getTimeRange();

        if (timeRange != null && !timeRange.isBlank()) {
            java.time.Instant now = java.time.Instant.now();
            return switch (timeRange.toUpperCase(Locale.ROOT)) {
                case "LAST_24_HOURS" -> now.minus(java.time.Duration.ofHours(24));
                case "LAST_7_DAYS" -> now.minus(java.time.Duration.ofDays(7));
                case "LAST_30_DAYS" -> now.minus(java.time.Duration.ofDays(30));
                case "ALL" -> null; // No time filter - fetch all available
                case "SINCE_LAST" -> config.getLastProcessedAt() != null
                    ? config.getLastProcessedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                    : null;
                default -> config.getLastProcessedAt() != null
                    ? config.getLastProcessedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
                    : null;
            };
        }

        // If explicit startTime is provided, use it
        if (request.getStartTime() != null) {
            return request.getStartTime();
        }

        // Default: use lastProcessedAt for incremental ingestion
        return config.getLastProcessedAt() != null
            ? config.getLastProcessedAt().atZone(java.time.ZoneOffset.UTC).toInstant()
            : null;
    }

    private boolean isFrequencySatisfied(SlowLogSourceConfig config) {
        Integer freq = config.getRefreshFrequencyMinutes();
        if (config.getProviderType() != null &&
            config.getProviderType().equalsIgnoreCase("CLOUDWATCH")) {
            if (freq == null || freq < 1440) {
                freq = 1440;
            }
        }
        if (freq == null || freq <= 0) {
            return true;
        }
        LocalDateTime last = config.getLastProcessedAt();
        if (last == null) {
            return true;
        }
        return last.plusMinutes(freq).isBefore(LocalDateTime.now());
    }

    private void updateLastProcessed(SlowLogSourceConfig config) {
        config.setLastProcessedAt(LocalDateTime.now());
        config.setUpdatedAt(LocalDateTime.now());
        configRepository.save(config);
    }

    private String resolveDatabaseType(String connectionId) {
        try {
            ConnectionRequest connection = credentialService.getDecryptedConnection(connectionId);
            if (connection != null && connection.getDbType() != null && !connection.getDbType().isBlank()) {
                return connection.getDbType().toLowerCase(Locale.ROOT);
            }
        } catch (Exception e) {
            log.warn("Failed to resolve database type for slow log ingestion, defaulting to MySQL", e);
        }
        return "mysql";
    }

    /**
     * Record ingested slow queries to the performance history for regression detection.
     * This enables the QueryPerformanceService to track query execution times over time
     * and detect performance regressions.
     */
    private void recordQueriesForRegressionDetection(String connectionId, SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return;
        }

        int recordedCount = 0;
        for (SlowQuery slowQuery : analysis.getTopSlowQueries()) {
            try {
                // Record each slow query execution for performance tracking
                // Use average execution time if available, otherwise total/count
                Double avgExecTime = slowQuery.getAvgExecutionTimeMs();
                if (avgExecTime == null && slowQuery.getTotalExecutionTimeMs() != null && slowQuery.getCallCount() != null && slowQuery.getCallCount() > 0) {
                    avgExecTime = slowQuery.getTotalExecutionTimeMs() / slowQuery.getCallCount();
                }

                if (avgExecTime != null && avgExecTime > 0) {
                    queryPerformanceService.recordQueryExecution(
                        connectionId,
                        slowQuery.getNormalizedQuery() != null ? slowQuery.getNormalizedQuery() : slowQuery.getQueryText(),
                        avgExecTime,
                        slowQuery.getRowsExamined() != null ? slowQuery.getRowsExamined().longValue() : null,
                        null, // rowsSent not available from slow query logs
                        null  // databaseName not always available
                    );
                    recordedCount++;
                }
            } catch (Exception e) {
                log.debug("Failed to record query for regression detection: {}", e.getMessage());
            }
        }

        if (recordedCount > 0) {
            log.info("Recorded {} queries for regression detection from connection {}", recordedCount, connectionId);
        }
    }

    /**
     * Runs all post-ingestion processing on an analysis: tenant-column
     * auto-detection, regression detection, fingerprints, alerts, Query
     * Intelligence, the slow_query_run time-series, per-customer attribution
     * + rollups, and 30-day retention enforcement.
     *
     * <p>This is the single owner of every slow-query vault write. Before the
     * unification it was split with {@code SlowQueryDailyAnalysisService}
     * (which read pg_stat_statements), which silently produced two different
     * fingerprint streams for the same query.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void postProcessAnalysis(String connectionId, SlowQueryAnalysis analysis,
                                    String analysisRunId) {
        // Auto-detect + persist tenant column on first analysis of a connection.
        // Belt-and-braces — also called from the daily entry point, but a
        // direct API ingestion shouldn't depend on the daily wrapper running.
        try {
            slowQueryAnalyticsService.ensureAutoConfig(connectionId);
        } catch (Exception e) {
            log.warn("Tenant-column auto-config failed for {}: {}", connectionId, e.getMessage());
        }

        recordQueriesForRegressionDetection(connectionId, analysis);
        processQueryFingerprints(connectionId, analysis);
        processAlerts(connectionId, analysis);
        recordExecutionsForQueryIntelligence(connectionId, analysis);
        attributeCustomerSamples(connectionId, analysis);

        // slow_query_run: per-fingerprint daily time series + regression factor.
        // Moved here from SlowQueryDailyAnalysisService so log ingestion is the
        // sole writer of this table — fingerprints in slow_query_run now match
        // those in slow_query_sample by construction.
        LocalDate today = LocalDate.now();
        decomposeIntoRuns(connectionId, analysis, today, analysisRunId);

        // Customer-day rollups: today's fresh samples + yesterday's now-complete
        // day. Cheap and idempotent.
        try {
            customerAttributionService.resolvePendingCustomerNames(connectionId);
            customerAttributionService.rollupCustomerDays(connectionId, today);
            customerAttributionService.rollupCustomerDays(connectionId, today.minusDays(1));
        } catch (Exception e) {
            log.warn("Customer-day rollup failed for {}: {}", connectionId, e.getMessage());
        }

        // 30-day retention.
        try {
            slowQueryRetentionService.purge(connectionId);
        } catch (Exception e) {
            log.warn("Slow-query retention purge failed for {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Decompose a {@link SlowQueryAnalysis} into one {@code slow_query_run} row
     * per query per day. Idempotent: upserts on
     * {@code (connectionId, fingerprint, analyzedOn)} so a same-day re-run
     * (e.g. the on-demand "Analyze now" button after the nightly job) just
     * refreshes the row.
     */
    private void decomposeIntoRuns(String connectionId, SlowQueryAnalysis analysis,
                                   LocalDate today, String analysisRunId) {
        if (analysis == null || analysis.getTopSlowQueries() == null) return;
        int persisted = 0;
        for (SlowQuery q : analysis.getTopSlowQueries()) {
            if (q.getQueryText() == null || q.getQueryText().isBlank()) continue;
            try {
                persistRun(connectionId, analysisRunId, q, today);
                persisted++;
            } catch (Exception e) {
                log.warn("Failed to persist slow_query_run for {}: {}", connectionId, e.getMessage());
            }
        }
        log.info("slow_query_run decomposition for {}: {} rows", connectionId, persisted);
    }

    /**
     * Upsert one slow_query_run row, computing the delta and regression factor
     * against the prior run. Logic moved verbatim from the deleted
     * {@code SlowQueryDailyAnalysisService.persistRun}.
     */
    private void persistRun(String connectionId, String analysisRunId, SlowQuery q, LocalDate today) {
        QueryFingerprint fp = queryFingerprintService.updateOrCreateFingerprint(connectionId, q);
        String fingerprint = fp.getFingerprint();
        q.setQueryId(fingerprint);

        Long callsCumulative = q.getCallCount();
        Double totalCumulative = q.getTotalExecutionTimeMs();

        Optional<SlowQueryRun> prevOpt = slowQueryRunRepository
            .findFirstByConnectionIdAndFingerprintAndAnalyzedOnLessThanOrderByAnalyzedOnDesc(
                connectionId, fingerprint, today);

        boolean counterReset = false;
        Long callsDelta = callsCumulative;
        Double totalDelta = totalCumulative;
        Double prevPeriodMean = null;

        if (prevOpt.isPresent()) {
            SlowQueryRun prev = prevOpt.get();
            if (callsCumulative != null && prev.getCallsCumulative() != null) {
                if (callsCumulative >= prev.getCallsCumulative()) {
                    callsDelta = callsCumulative - prev.getCallsCumulative();
                } else {
                    counterReset = true;
                    callsDelta = callsCumulative;
                }
            }
            if (totalCumulative != null && prev.getTotalExecMsCumulative() != null) {
                totalDelta = counterReset
                    ? totalCumulative
                    : Math.max(0.0, totalCumulative - prev.getTotalExecMsCumulative());
            }
            prevPeriodMean = periodMean(prev.getTotalExecMsDelta(), prev.getCallsDelta());
        }

        Double periodMean = periodMean(totalDelta, callsDelta);
        if (periodMean == null) {
            periodMean = q.getAvgExecutionTimeMs();
        }

        Double regressionFactor = (periodMean != null && prevPeriodMean != null && prevPeriodMean > 0)
            ? periodMean / prevPeriodMean
            : null;

        Long rowsExaminedDelta = estimateRowsDelta(q.getAvgRowsExamined(), callsDelta);
        Long rowsSentDelta = estimateRowsDelta(q.getAvgRowsSent(), callsDelta);

        SlowQueryRun run = slowQueryRunRepository
            .findByConnectionIdAndFingerprintAndAnalyzedOn(connectionId, fingerprint, today)
            .orElseGet(SlowQueryRun::new);
        run.setConnectionId(connectionId);
        run.setAnalysisRunId(analysisRunId);
        run.setFingerprint(fingerprint);
        run.setAnalyzedOn(today);
        run.setCapturedAt(LocalDateTime.now());
        run.setCallsCumulative(callsCumulative);
        run.setTotalExecMsCumulative(totalCumulative);
        run.setCallsDelta(callsDelta);
        run.setTotalExecMsDelta(totalDelta);
        run.setMeanExecMs(periodMean);
        run.setMaxExecMs(q.getMaxExecutionTimeMs());
        run.setRowsExaminedDelta(rowsExaminedDelta);
        run.setRowsSentDelta(rowsSentDelta);
        run.setPrevRunId(prevOpt.map(SlowQueryRun::getId).orElse(null));
        run.setRegressionFactor(regressionFactor);
        run.setCounterReset(counterReset);
        slowQueryRunRepository.save(run);
    }

    private static Double periodMean(Double total, Long calls) {
        if (total == null || calls == null || calls <= 0) return null;
        return total / calls;
    }

    private static Long estimateRowsDelta(Long avgPerCall, Long callsDelta) {
        if (avgPerCall == null || callsDelta == null || callsDelta <= 0) return null;
        return avgPerCall * callsDelta;
    }

    /**
     * Write per-customer slow-query samples from the freshly-ingested log.
     * The slow log is the one source that keeps query literals, so this is
     * where tenant attribution happens. Failures are isolated — customer
     * attribution must never break log ingestion.
     */
    private void attributeCustomerSamples(String connectionId, SlowQueryAnalysis analysis) {
        try {
            customerAttributionService.attributeSamples(
                connectionId, analysis, com.dbaagent.model.SlowQuerySample.Source.SLOW_LOG);
        } catch (Exception e) {
            log.warn("Customer attribution failed for connection {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Process query fingerprints for trend analysis.
     * This updates or creates fingerprints for each slow query to enable
     * historical trend tracking and performance comparison.
     */
    private void processQueryFingerprints(String connectionId, SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return;
        }

        try {
            var fingerprints = queryFingerprintService.processAnalysis(connectionId, analysis);
            log.info("Processed {} query fingerprints for connection {}", fingerprints.size(), connectionId);
        } catch (Exception e) {
            log.warn("Failed to process query fingerprints for connection {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Process alerts for critical slow queries.
     * Creates alerts for queries exceeding severity thresholds and
     * routes them to configured notification channels.
     */
    private void processAlerts(String connectionId, SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return;
        }

        try {
            var alerts = slowQueryAlertService.processAnalysisForAlerts(connectionId, analysis);
            if (!alerts.isEmpty()) {
                log.info("Created {} alerts for slow queries in connection {}", alerts.size(), connectionId);
            }
        } catch (Exception e) {
            log.warn("Failed to process alerts for connection {}: {}", connectionId, e.getMessage());
        }
    }

    /**
     * Record slow query executions for Query Intelligence (Brain 2.0).
     * This populates the cardinality accuracy and plan pattern data
     * by recording actual execution metrics from slow queries.
     */
    private void recordExecutionsForQueryIntelligence(String connectionId, SlowQueryAnalysis analysis) {
        if (analysis == null || analysis.getTopSlowQueries() == null) {
            return;
        }

        int recordedCount = 0;
        for (SlowQuery slowQuery : analysis.getTopSlowQueries()) {
            try {
                // Get execution time (prefer avg, fallback to calculated)
                Double execTimeMs = slowQuery.getAvgExecutionTimeMs();
                if (execTimeMs == null && slowQuery.getTotalExecutionTimeMs() != null &&
                    slowQuery.getCallCount() != null && slowQuery.getCallCount() > 0) {
                    execTimeMs = slowQuery.getTotalExecutionTimeMs() / slowQuery.getCallCount();
                }

                // Get actual rows (rowsSent is actual rows returned, rowsExamined is rows scanned)
                // For cardinality accuracy, we want rows returned (rowsSent)
                Long actualRows = slowQuery.getRowsSent();
                if (actualRows == null) {
                    actualRows = slowQuery.getAvgRowsSent();
                }

                // Only record if we have meaningful data
                if (execTimeMs != null && execTimeMs > 0) {
                    String queryText = slowQuery.getNormalizedQuery() != null ?
                        slowQuery.getNormalizedQuery() : slowQuery.getQueryText();

                    if (queryText != null && !queryText.isBlank()) {
                        adaptivePlanScoringService.recordExecution(
                            connectionId,
                            queryText,
                            execTimeMs,
                            actualRows
                        );
                        recordedCount++;
                    }
                }
            } catch (Exception e) {
                log.debug("Failed to record execution for Query Intelligence: {}", e.getMessage());
            }
        }

        if (recordedCount > 0) {
            log.info("Recorded {} executions for Query Intelligence from connection {}", recordedCount, connectionId);

            // Automatically trigger EXPLAIN calculation in background
            // This populates cardinality estimates for accuracy tracking
            // Use concurrency control to prevent unbounded parallelism:
            // 1. Per-connection lock prevents duplicate backfills for same connection
            // 2. Global semaphore limits total concurrent backfills across all connections
            AtomicBoolean isRunning = activeBackfills.computeIfAbsent(connectionId, k -> new AtomicBoolean(false));
            if (isRunning.compareAndSet(false, true)) {
                // Try to acquire global semaphore (non-blocking)
                if (backfillSemaphore.tryAcquire()) {
                    Thread.startVirtualThread(() -> {
                        try {
                            // Small delay to let database settle after ingestion
                            Thread.sleep(1000);

                            // Start EXPLAIN backfill for newly recorded queries (limited batch)
                            var progress = adaptivePlanScoringService.startExplainBackfill(connectionId, 50);
                            if ("RUNNING".equals(progress.getStatus())) {
                                log.info("Started automatic EXPLAIN calculation for {} queries on connection {}",
                                    progress.getTotalQueries(), connectionId);
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.debug("Automatic EXPLAIN calculation failed: {}", e.getMessage());
                        } finally {
                            // Always release locks when done
                            isRunning.set(false);
                            backfillSemaphore.release();
                        }
                    });
                } else {
                    // Couldn't acquire semaphore - too many concurrent backfills
                    isRunning.set(false);
                    log.debug("Skipping EXPLAIN backfill for connection {} - global concurrency limit reached", connectionId);
                }
            } else {
                log.debug("Skipping EXPLAIN backfill for connection {} - already running", connectionId);
            }
        }
    }
}
