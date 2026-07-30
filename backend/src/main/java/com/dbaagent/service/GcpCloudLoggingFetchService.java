package com.dbaagent.service;

import com.google.api.gax.core.FixedCredentialsProvider;
import com.google.auth.oauth2.GoogleCredentials;
import com.google.auth.oauth2.ServiceAccountCredentials;
import com.google.cloud.logging.LogEntry;
import com.google.cloud.logging.Logging;
import com.google.cloud.logging.Logging.EntryListOption;
import com.google.cloud.logging.LoggingOptions;
import com.google.cloud.logging.Payload;
import com.dbaagent.util.CappedOutputStream;
import com.dbaagent.util.LogTempFileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for fetching slow query logs from Google Cloud Logging (Stackdriver).
 * Supports:
 * - Cloud SQL slow query logs
 * - Custom application logs with SQL queries
 * - Filtering by log name, severity, and time range
 */
@Service
@Slf4j
public class GcpCloudLoggingFetchService {

    private static final int DEFAULT_PAGE_SIZE = 1000;
    private static final int MAX_ENTRIES = 10000;

    @Value("${slow-query.log.max-bytes:524288000}")
    private long maxLogBytes = 524288000L;

    @Value("${slow-query.log.temp-dir:}")
    private String tempDir;

    @Value("${slow-query.log.min-free-disk-mb:1024}")
    private long minFreeDiskMb = 1024L;

    /**
     * Credentials for GCP authentication
     */
    @Data
    public static class GcpCredentialsInput {
        private String projectId;             // GCP project ID (required)
        private String serviceAccountJson;    // Service account JSON key content
        private String serviceAccountPath;    // Path to service account JSON file
        // If neither serviceAccountJson nor serviceAccountPath is provided,
        // will use Application Default Credentials (ADC)
    }

    /**
     * Download slow query logs from GCP Cloud Logging
     *
     * @param projectId GCP project ID
     * @param logFilter Cloud Logging filter string (e.g., 'resource.type="cloudsql_database"')
     * @param credentials Authentication credentials
     * @param startTime Start time for log retrieval
     * @param endTime End time for log retrieval (defaults to now)
     * @param maxEntries Maximum number of log entries to retrieve
     * @return InputStream containing combined log entries
     */
    public InputStream downloadLogs(
            String projectId,
            String logFilter,
            GcpCredentialsInput credentials,
            Instant startTime,
            Instant endTime,
            int maxEntries
    ) {
        if (projectId == null || projectId.isBlank()) {
            throw new IllegalArgumentException("GCP project ID is required");
        }

        Logging logging = buildLoggingClient(projectId, credentials);

        try {
            String filter = buildFilter(logFilter, startTime, endTime);
            log.info("Fetching GCP logs with filter: {}", filter);

            int effectiveMax = Math.min(maxEntries > 0 ? maxEntries : MAX_ENTRIES, MAX_ENTRIES);
            int pageSize = Math.min(DEFAULT_PAGE_SIZE, effectiveMax);

            // Build list options
            List<EntryListOption> options = new ArrayList<>();
            options.add(EntryListOption.filter(filter));
            options.add(EntryListOption.pageSize(pageSize));
            options.add(EntryListOption.sortOrder(Logging.SortingField.TIMESTAMP, Logging.SortingOrder.ASCENDING));

            // Paginate through results
            var entries = logging.listLogEntries(options.toArray(new EntryListOption[0]));

            java.nio.file.Path tempFile;
            try {
                tempFile = LogTempFileUtils.createTempFile(
                    tempDir, minFreeDiskMb, "gcp-logs-", ".log");
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to create temp file for GCP logs", e);
            }
            long entryCount = 0;

            try (OutputStream rawOut = java.nio.file.Files.newOutputStream(tempFile);
                 OutputStream out = maxLogBytes > 0 ? new CappedOutputStream(rawOut, maxLogBytes) : rawOut;
                 java.io.BufferedWriter writer = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8))) {
                for (LogEntry entry : entries.iterateAll()) {
                    if (entryCount >= effectiveMax) {
                        break;
                    }
                    String formatted = formatLogEntry(entry);
                    if (formatted != null && !formatted.isBlank()) {
                        writer.write(formatted);
                        writer.newLine();
                        writer.newLine();
                        entryCount++;
                    }
                }
            } catch (Exception e) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
                if (e instanceof com.dbaagent.exception.LogSizeExceededException) {
                    throw (com.dbaagent.exception.LogSizeExceededException) e;
                }
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }

            if (entryCount == 0) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
                return new ByteArrayInputStream(new byte[0]);
            }

            log.info("Retrieved {} log entries from GCP Cloud Logging", entryCount);
            try {
                return LogTempFileUtils.openDeleteOnClose(tempFile);
            } catch (java.io.IOException e) {
                throw new RuntimeException("Failed to open temp file for GCP logs", e);
            }

        } finally {
            try {
                logging.close();
            } catch (Exception e) {
                log.warn("Error closing Logging client", e);
            }
        }
    }

    /**
     * Download Cloud SQL slow query logs specifically
     * Uses predefined filter for Cloud SQL slow query logs
     */
    public InputStream downloadCloudSqlSlowQueryLogs(
            String projectId,
            String instanceId,
            GcpCredentialsInput credentials,
            Instant startTime,
            int maxEntries
    ) {
        // Cloud SQL slow query log filter
        String filter = String.format(
            "resource.type=\"cloudsql_database\" " +
            "AND resource.labels.database_id=\"%s:%s\" " +
            "AND logName=\"projects/%s/logs/cloudsql.googleapis.com%%2Fmysql-slow.log\"",
            projectId, instanceId, projectId
        );

        return downloadLogs(projectId, filter, credentials, startTime, null, maxEntries);
    }

    /**
     * Download PostgreSQL slow query logs from Cloud SQL
     */
    public InputStream downloadCloudSqlPostgresLogs(
            String projectId,
            String instanceId,
            GcpCredentialsInput credentials,
            Instant startTime,
            int maxEntries
    ) {
        // PostgreSQL logs filter
        String filter = String.format(
            "resource.type=\"cloudsql_database\" " +
            "AND resource.labels.database_id=\"%s:%s\" " +
            "AND logName=\"projects/%s/logs/cloudsql.googleapis.com%%2Fpostgres.log\" " +
            "AND textPayload=~\"duration:\"",
            projectId, instanceId, projectId
        );

        return downloadLogs(projectId, filter, credentials, startTime, null, maxEntries);
    }

    private String buildFilter(String baseFilter, Instant startTime, Instant endTime) {
        StringBuilder filter = new StringBuilder();

        if (baseFilter != null && !baseFilter.isBlank()) {
            filter.append(baseFilter);
        }

        DateTimeFormatter formatter = DateTimeFormatter.ISO_INSTANT;

        if (startTime != null) {
            if (!filter.isEmpty()) filter.append(" AND ");
            filter.append("timestamp >= \"")
                  .append(formatter.format(startTime.atOffset(ZoneOffset.UTC)))
                  .append("\"");
        }

        if (endTime != null) {
            if (!filter.isEmpty()) filter.append(" AND ");
            filter.append("timestamp <= \"")
                  .append(formatter.format(endTime.atOffset(ZoneOffset.UTC)))
                  .append("\"");
        }

        return filter.toString();
    }

    private String formatLogEntry(LogEntry entry) {
        StringBuilder sb = new StringBuilder();

        // Add timestamp
        if (entry.getTimestamp() != null) {
            Instant instant = Instant.ofEpochMilli(entry.getTimestamp());
            sb.append("# Time: ").append(instant.toString()).append("\n");
        }

        // Add severity if present
        if (entry.getSeverity() != null) {
            sb.append("# Severity: ").append(entry.getSeverity()).append("\n");
        }

        // Add resource labels (database info)
        if (entry.getResource() != null && entry.getResource().getLabels() != null) {
            var labels = entry.getResource().getLabels();
            if (labels.containsKey("database_id")) {
                sb.append("# Database: ").append(labels.get("database_id")).append("\n");
            }
        }

        // Add the actual log payload
        Payload<?> payload = entry.getPayload();
        if (payload != null) {
            Object data = payload.getData();
            if (data != null) {
                sb.append(data.toString());
            }
        }

        return sb.toString();
    }

    private Logging buildLoggingClient(String projectId, GcpCredentialsInput credentials) {
        try {
            LoggingOptions.Builder optionsBuilder = LoggingOptions.newBuilder()
                    .setProjectId(projectId);

            GoogleCredentials googleCredentials = null;

            // Priority 1: Service account JSON content
            if (credentials != null && credentials.getServiceAccountJson() != null &&
                !credentials.getServiceAccountJson().isBlank()) {
                log.debug("Using service account JSON for authentication");
                googleCredentials = ServiceAccountCredentials.fromStream(
                        new ByteArrayInputStream(credentials.getServiceAccountJson().getBytes(StandardCharsets.UTF_8)));
            }
            // Priority 2: Service account file path
            else if (credentials != null && credentials.getServiceAccountPath() != null &&
                     !credentials.getServiceAccountPath().isBlank()) {
                log.debug("Using service account file for authentication");
                googleCredentials = ServiceAccountCredentials.fromStream(
                        new java.io.FileInputStream(credentials.getServiceAccountPath()));
            }
            // Priority 3: Application Default Credentials
            else {
                log.debug("Using Application Default Credentials");
                googleCredentials = GoogleCredentials.getApplicationDefault();
            }

            if (googleCredentials != null) {
                optionsBuilder.setCredentials(googleCredentials);
            }

            return optionsBuilder.build().getService();

        } catch (Exception e) {
            log.error("Failed to create GCP Logging client", e);
            throw new RuntimeException("Failed to authenticate with GCP: " + e.getMessage(), e);
        }
    }
}
