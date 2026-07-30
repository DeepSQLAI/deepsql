package com.dbaagent.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.http.apache.ApacheHttpClient;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.cloudwatchlogs.CloudWatchLogsClient;
import software.amazon.awssdk.services.cloudwatchlogs.model.*;

import java.io.BufferedWriter;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;
import com.dbaagent.util.LogTempFileUtils;
import com.dbaagent.util.TruncatingOutputStream;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudWatchLogFetchService {
    @Value("${slow-query.log.max-bytes:524288000}")
    private long maxLogBytes = 524288000L;

    @Value("${slow-query.log.temp-dir:}")
    private String tempDir;

    @Value("${slow-query.log.min-free-disk-mb:1024}")
    private long minFreeDiskMb = 1024L;

    /**
     * Progress callback for reporting events processed during fetch.
     */
    @FunctionalInterface
    public interface ProgressCallback {
        void onProgress(long eventsProcessed, long bytesWritten);
    }

    /**
     * Downloads logs without progress callback (backwards compatible).
     */
    public InputStream downloadLatestLogs(String logGroupName,
                                          String logStreamPrefix,
                                          String region,
                                          S3LogFetchService.AwsCredentialsInput credentialsInput,
                                          Instant startTime,
                                          int maxEvents) {
        return downloadLatestLogs(logGroupName, logStreamPrefix, region, credentialsInput, startTime, maxEvents, null);
    }

    /**
     * Downloads logs with progress callback for real-time progress reporting.
     *
     * @param maxEvents Note: maxEvents bounds the number of slow-query entries kept after audit/non-slow lines are filtered out, not the number of raw CloudWatch events scanned.
     */
    public InputStream downloadLatestLogs(String logGroupName,
                                          String logStreamPrefix,
                                          String region,
                                          S3LogFetchService.AwsCredentialsInput credentialsInput,
                                          Instant startTime,
                                          int maxEvents,
                                          ProgressCallback progressCallback) {
        return downloadLatestLogsResult(logGroupName, logStreamPrefix, region, credentialsInput, startTime, maxEvents, progressCallback).stream();
    }

    /**
     * Downloads logs without progress callback, returning a {@link LogFetchResult} that
     * also carries a truncation flag.
     */
    public LogFetchResult downloadLatestLogsResult(String logGroupName,
                                                    String logStreamPrefix,
                                                    String region,
                                                    S3LogFetchService.AwsCredentialsInput credentialsInput,
                                                    Instant startTime,
                                                    int maxEvents) {
        return downloadLatestLogsResult(logGroupName, logStreamPrefix, region, credentialsInput, startTime, maxEvents, null);
    }

    /**
     * Downloads logs with optional progress callback, returning a {@link LogFetchResult}
     * that carries the stream and a truncation flag indicating whether any stream hit its
     * per-stream byte budget.
     *
     * @param maxEvents Note: maxEvents bounds the number of slow-query entries kept after audit/non-slow lines are filtered out, not the number of raw CloudWatch events scanned.
     */
    public LogFetchResult downloadLatestLogsResult(String logGroupName,
                                                    String logStreamPrefix,
                                                    String region,
                                                    S3LogFetchService.AwsCredentialsInput credentialsInput,
                                                    Instant startTime,
                                                    int maxEvents,
                                                    ProgressCallback progressCallback) {
        AwsCredentialsProvider provider = resolveCredentialsProvider(credentialsInput);
        Region resolvedRegion = Region.of(region);

        log.info("=== CloudWatch Fetch Started ===");
        log.info("Log Group: {}", logGroupName);
        log.info("Stream Prefix: '{}'", logStreamPrefix);
        log.info("Region: {}", region);
        log.info("Start Time: {} (epoch ms: {})", startTime, startTime != null ? startTime.toEpochMilli() : "null");
        log.info("Max Events: {}", maxEvents);

        try (CloudWatchLogsClient client = CloudWatchLogsClient.builder()
            .region(resolvedRegion)
            .credentialsProvider(provider)
            .httpClientBuilder(ApacheHttpClient.builder()
                .connectionTimeout(Duration.ofSeconds(15))
                .socketTimeout(Duration.ofSeconds(90)))
            .build()) {

            List<String> streams = listStreams(client, logGroupName, logStreamPrefix, 10);
            log.info("Found {} log streams matching prefix '{}'", streams.size(), logStreamPrefix);

            if (streams.isEmpty()) {
                log.warn("No log streams found for group={}, prefix={}", logGroupName, logStreamPrefix);
                return new LogFetchResult(new ByteArrayInputStream(new byte[0]), false);
            }

            // Use temp file to avoid memory issues with large log volumes
            // Note: Events are written in the order returned by CloudWatch per stream
            // to keep memory usage low. If strict ordering is needed, add a bounded merge.
            Path tempFile = LogTempFileUtils.createTempFile(
                tempDir, minFreeDiskMb, "cloudwatch-logs-", ".log");
            long totalEvents = 0;
            long totalBytes = 0;

            long perStreamBudget = maxLogBytes > 0 ? Math.max(1, maxLogBytes / streams.size()) : 0;
            boolean anyTruncated = false;

            try (OutputStream rawOut = Files.newOutputStream(tempFile);
                 BufferedWriter writer = new BufferedWriter(
                     new java.io.OutputStreamWriter(rawOut, StandardCharsets.UTF_8))) {
                for (String stream : streams) {
                    log.debug("Fetching events from stream: {}", stream);
                    TruncatingOutputStream budget =
                        new TruncatingOutputStream(OutputStream.nullOutputStream(), perStreamBudget);
                    long streamCount = writeEventsWithProgress(client, logGroupName, stream, startTime, maxEvents, writer,
                        totalEvents, progressCallback, budget);
                    if (budget.isTruncated()) anyTruncated = true;
                    log.info("Fetched {} events from stream {}{}", streamCount, stream,
                        budget.isTruncated() ? " (truncated at per-stream budget)" : "");
                    totalEvents += streamCount;

                    // Report final progress for this stream
                    if (progressCallback != null) {
                        totalBytes = Files.size(tempFile);
                        progressCallback.onProgress(totalEvents, totalBytes);
                    }
                }
            }

            totalBytes = Files.size(tempFile);
            log.info("Total events fetched: {}, temp file size: {} bytes", totalEvents, totalBytes);

            // Final progress report
            if (progressCallback != null) {
                progressCallback.onProgress(totalEvents, totalBytes);
            }

            // Return input stream that deletes temp file on close
            return new LogFetchResult(LogTempFileUtils.openDeleteOnClose(tempFile), anyTruncated);
        } catch (Exception e) {
            log.error("Error fetching CloudWatch logs: {}", e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException("Failed to fetch CloudWatch logs", e);
        }
    }

    /**
     * Writes the slow-query lines of CloudWatch event messages through the
     * shared filter, dropping audit/other lines. {@code filter} carries entry
     * state across events in a stream. Returns the number of kept slow-query
     * entries written.
     */
    static long writeFilteredEvents(List<FilteredLogEvent> events,
                                    SlowQueryLogFilter filter,
                                    BufferedWriter writer) throws IOException {
        return writeFilteredEvents(events, filter, writer, null);
    }

    /**
     * Budget-aware overload: if {@code budget} is non-null, each line's bytes are
     * accounted against it (writing to a null sink) and iteration stops once the
     * budget is exhausted. The slow-query lines themselves are still written to
     * {@code writer} as normal up to that point.
     */
    static long writeFilteredEvents(List<FilteredLogEvent> events,
                                    SlowQueryLogFilter filter,
                                    BufferedWriter writer,
                                    TruncatingOutputStream budget) throws IOException {
        long kept = 0;
        for (FilteredLogEvent event : events) {
            if (budget != null && budget.isTruncated()) break;
            String msg = event.message();
            if (msg == null || msg.isEmpty()) continue;
            for (String line : msg.split("\n", -1)) {
                if (line.isEmpty()) continue;
                if (budget != null && budget.isTruncated()) break;
                boolean started = filter.acceptLine(line, writer);
                if (started) kept++;
                if (budget != null && filter.isKeeping()) {
                    // only count bytes actually written to the output
                    budget.write((line + "\n").getBytes(StandardCharsets.UTF_8));
                }
            }
        }
        return kept;
    }

    private List<String> listStreams(CloudWatchLogsClient client,
                                     String logGroupName,
                                     String logStreamPrefix,
                                     int limit) {
        DescribeLogStreamsRequest.Builder builder = DescribeLogStreamsRequest.builder()
            .logGroupName(logGroupName)
            .limit(limit);

        // AWS API constraint: when using logStreamNamePrefix, you cannot use orderBy=LAST_EVENT_TIME
        // You must use orderBy=LOG_STREAM_NAME or omit orderBy entirely
        if (logStreamPrefix != null && !logStreamPrefix.isBlank()) {
            builder.logStreamNamePrefix(logStreamPrefix);
            // When using prefix, order by name (AWS requirement)
            builder.orderBy(OrderBy.LOG_STREAM_NAME);
            builder.descending(true);
            log.debug("Listing streams with prefix filter: {}", logStreamPrefix);
        } else {
            // When not using prefix, we can order by last event time
            builder.orderBy(OrderBy.LAST_EVENT_TIME);
            builder.descending(true);
            log.debug("Listing streams ordered by last event time");
        }

        try {
            DescribeLogStreamsResponse response = client.describeLogStreams(builder.build());
            if (response.logStreams() == null) {
                log.warn("No log streams returned from API for group: {}", logGroupName);
                return List.of();
            }

            List<String> streamNames = response.logStreams().stream()
                .map(LogStream::logStreamName)
                .collect(Collectors.toList());

            log.debug("Found streams: {}", streamNames);
            return streamNames;
        } catch (Exception e) {
            log.error("Error listing log streams for group={}, prefix={}: {}",
                logGroupName, logStreamPrefix, e.getMessage(), e);
            throw e;
        }
    }

    private List<FilteredLogEvent> fetchEvents(CloudWatchLogsClient client,
                                               String logGroupName,
                                               String logStreamName,
                                               Instant startTime,
                                               int maxEvents) {
        FilterLogEventsRequest.Builder builder = FilterLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamNames(logStreamName)
            .limit(Math.min(maxEvents, 10000)); // AWS max is 10000

        if (startTime != null) {
            builder.startTime(startTime.toEpochMilli());
            log.info("Filtering events with startTime: {} ({}ms)", startTime, startTime.toEpochMilli());
        } else {
            // No time filter for initial ingestion - get all available events
            log.info("No startTime provided, fetching all available events (no time filter)");
        }

        try {
            FilterLogEventsRequest request = builder.build();
            log.debug("FilterLogEvents request: logGroup={}, stream={}", logGroupName, logStreamName);

            List<FilteredLogEvent> allEvents = new ArrayList<>();
            String nextToken = null;

            // Paginate through results
            do {
                if (nextToken != null) {
                    request = request.toBuilder().nextToken(nextToken).build();
                }

                FilterLogEventsResponse response = client.filterLogEvents(request);
                if (response.events() != null) {
                    allEvents.addAll(response.events());
                }
                nextToken = response.nextToken();

                // Safety limit to avoid infinite loops
                if (allEvents.size() >= maxEvents) {
                    log.debug("Reached maxEvents limit: {}", maxEvents);
                    break;
                }
            } while (nextToken != null);

            log.debug("Fetched {} events from stream {}", allEvents.size(), logStreamName);
            return allEvents;
        } catch (Exception e) {
            log.error("Error fetching events from stream {}: {}", logStreamName, e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    /**
     * Writes events with progress callback for real-time updates.
     * {@code budget} may be {@code null} for unbounded writes; when non-null,
     * iteration stops once the per-stream byte budget is exhausted.
     */
    private long writeEventsWithProgress(CloudWatchLogsClient client,
                                         String logGroupName,
                                         String logStreamName,
                                         Instant startTime,
                                         int maxEvents,
                                         BufferedWriter writer,
                                         long previousTotal,
                                         ProgressCallback progressCallback,
                                         TruncatingOutputStream budget) {
        FilterLogEventsRequest.Builder builder = FilterLogEventsRequest.builder()
            .logGroupName(logGroupName)
            .logStreamNames(logStreamName)
            .limit(Math.min(maxEvents, 10000)); // AWS max is 10000

        if (startTime != null) {
            builder.startTime(startTime.toEpochMilli());
            log.info("Filtering events with startTime: {} ({}ms)", startTime, startTime.toEpochMilli());
        } else {
            log.info("No startTime provided, fetching all available events (no time filter)");
        }

        try {
            FilterLogEventsRequest request = builder.build();
            String nextToken = null;
            long total = 0;
            long lastReportedTotal = 0;
            SlowQueryLogFilter filter = new SlowQueryLogFilter();

            do {
                if (nextToken != null) {
                    request = request.toBuilder().nextToken(nextToken).build();
                }

                FilterLogEventsResponse response = client.filterLogEvents(request);
                if (response.events() != null) {
                    // total counts kept SLOW-QUERY entries (not raw events), so audit
                    // can no longer crowd slow queries out of the maxEvents budget.
                    total += writeFilteredEvents(response.events(), filter, writer, budget);

                    if (progressCallback != null && (total - lastReportedTotal) >= 10) {
                        writer.flush();
                        progressCallback.onProgress(previousTotal + total, 0);
                        lastReportedTotal = total;
                    }
                }
                nextToken = response.nextToken();

                if (total >= maxEvents) {
                    break;
                }
                if (budget != null && budget.isTruncated()) {
                    break;
                }
            } while (nextToken != null);

            return total;
        } catch (Exception e) {
            log.error("Error fetching events from stream {}: {}", logStreamName, e.getMessage(), e);
            if (e instanceof RuntimeException) {
                throw (RuntimeException) e;
            }
            throw new RuntimeException(e);
        }
    }

    private AwsCredentialsProvider resolveCredentialsProvider(S3LogFetchService.AwsCredentialsInput credentialsInput) {
        if (credentialsInput == null) {
            return DefaultCredentialsProvider.create();
        }
        String accessKeyId = credentialsInput.getAccessKeyId();
        String secretAccessKey = credentialsInput.getSecretAccessKey();
        String sessionToken = credentialsInput.getSessionToken();

        if (accessKeyId == null || accessKeyId.isBlank() ||
            secretAccessKey == null || secretAccessKey.isBlank()) {
            return DefaultCredentialsProvider.create();
        }

        if (sessionToken != null && !sessionToken.isBlank()) {
            return StaticCredentialsProvider.create(
                AwsSessionCredentials.create(accessKeyId, secretAccessKey, sessionToken)
            );
        }

        return StaticCredentialsProvider.create(
            AwsBasicCredentials.create(accessKeyId, secretAccessKey)
        );
    }
}
