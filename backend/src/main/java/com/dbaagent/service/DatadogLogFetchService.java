package com.dbaagent.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.dbaagent.util.CappedOutputStream;
import com.dbaagent.util.LogTempFileUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.io.BufferedWriter;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Service for fetching slow query logs from Datadog.
 * Uses Datadog's Log Search API to retrieve database query logs.
 *
 * Supports:
 * - APM database query spans
 * - Custom log entries with SQL queries
 * - DBM (Database Monitoring) query samples
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class DatadogLogFetchService {

    private static final String DATADOG_US_API = "https://api.datadoghq.com";
    private static final String DATADOG_EU_API = "https://api.datadoghq.eu";
    private static final String DATADOG_US3_API = "https://api.us3.datadoghq.com";
    private static final String DATADOG_US5_API = "https://api.us5.datadoghq.com";
    private static final String DATADOG_GOV_API = "https://api.ddog-gov.com";

    private static final int DEFAULT_LIMIT = 1000;
    private static final int MAX_LIMIT = 5000;

    private final ObjectMapper objectMapper;

    @Value("${slow-query.log.max-bytes:524288000}")
    private long maxLogBytes = 524288000L;

    @Value("${slow-query.log.temp-dir:}")
    private String tempDir;

    @Value("${slow-query.log.min-free-disk-mb:1024}")
    private long minFreeDiskMb = 1024L;

    /**
     * Credentials for Datadog API authentication
     */
    @Data
    public static class DatadogCredentialsInput {
        private String apiKey;          // Datadog API key (required)
        private String applicationKey;  // Datadog Application key (required for some endpoints)
        private String site;            // Datadog site: US, EU, US3, US5, GOV (default: US)
    }

    /**
     * Download slow query logs from Datadog Log Search API
     */
    public InputStream downloadLogs(
            String query,
            DatadogCredentialsInput credentials,
            Instant startTime,
            Instant endTime,
            int maxLogs
    ) {
        validateCredentials(credentials);

        String baseUrl = getBaseUrl(credentials.getSite());
        WebClient client = buildWebClient(baseUrl, credentials);

        if (endTime == null) {
            endTime = Instant.now();
        }
        if (startTime == null) {
            startTime = endTime.minus(Duration.ofHours(24));
        }

        int limit = Math.min(maxLogs > 0 ? maxLogs : DEFAULT_LIMIT, MAX_LIMIT);

        log.info("Fetching Datadog logs: query='{}', from={}, to={}, limit={}",
                query, startTime, endTime, limit);

        java.nio.file.Path responseFile = null;
        java.nio.file.Path logFile = null;
        try {
            // Build the search request body
            Map<String, Object> requestBody = Map.of(
                "filter", Map.of(
                    "query", query != null ? query : "*",
                    "from", startTime.toString(),
                    "to", endTime.toString()
                ),
                "sort", "timestamp",
                "page", Map.of(
                    "limit", limit
                )
            );

            responseFile = LogTempFileUtils.createTempFile(tempDir, minFreeDiskMb, "datadog-response-", ".json");
            try (OutputStream rawOut = java.nio.file.Files.newOutputStream(responseFile);
                 OutputStream cappedOut = maxLogBytes > 0 ? new CappedOutputStream(rawOut, maxLogBytes) : rawOut) {
                AtomicReference<IOException> ioError = new AtomicReference<>();
                Mono<Void> writeMono = client.post()
                        .uri("/api/v2/logs/events/search")
                        .bodyValue(requestBody)
                        .retrieve()
                        .bodyToFlux(DataBuffer.class)
                        .doOnNext(buffer -> {
                            try {
                                int readable = buffer.readableByteCount();
                                byte[] bytes = new byte[readable];
                                buffer.read(bytes);
                                cappedOut.write(bytes);
                            } catch (IOException e) {
                                ioError.set(e);
                                throw new UncheckedIOException(e);
                            } finally {
                                DataBufferUtils.release(buffer);
                            }
                        })
                        .then();
                writeMono.timeout(Duration.ofSeconds(30)).block();

                if (ioError.get() != null) {
                    throw ioError.get();
                }
            }

            if (java.nio.file.Files.size(responseFile) == 0) {
                log.warn("Empty response from Datadog API");
                java.nio.file.Files.deleteIfExists(responseFile);
                return new ByteArrayInputStream(new byte[0]);
            }

            logFile = LogTempFileUtils.createTempFile(tempDir, minFreeDiskMb, "datadog-logs-", ".log");
            try (InputStream responseStream = java.nio.file.Files.newInputStream(responseFile);
                 OutputStream rawOut = java.nio.file.Files.newOutputStream(logFile);
                 OutputStream cappedOut = maxLogBytes > 0 ? new CappedOutputStream(rawOut, maxLogBytes) : rawOut;
                 BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(cappedOut, StandardCharsets.UTF_8))) {
                writeLogsResponse(responseStream, writer);
            }

            java.nio.file.Files.deleteIfExists(responseFile);
            return LogTempFileUtils.openDeleteOnClose(logFile);

        } catch (Exception e) {
            if (responseFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(responseFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
            if (logFile != null) {
                try {
                    java.nio.file.Files.deleteIfExists(logFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
            }
            Throwable cause = e;
            while (cause != null) {
                if (cause instanceof com.dbaagent.exception.LogSizeExceededException) {
                    throw (com.dbaagent.exception.LogSizeExceededException) cause;
                }
                cause = cause.getCause();
            }
            log.error("Failed to fetch logs from Datadog: {}", e.getMessage(), e);
            throw new RuntimeException("Failed to fetch Datadog logs: " + e.getMessage(), e);
        }
    }

    /**
     * Download database query samples from Datadog DBM (Database Monitoring)
     */
    public InputStream downloadDbmQuerySamples(
            String serviceName,
            String databaseHost,
            DatadogCredentialsInput credentials,
            Instant startTime,
            int maxSamples
    ) {
        // Build a query specific to DBM query samples
        StringBuilder query = new StringBuilder();
        query.append("source:dbm");

        if (serviceName != null && !serviceName.isBlank()) {
            query.append(" service:").append(serviceName);
        }
        if (databaseHost != null && !databaseHost.isBlank()) {
            query.append(" host:").append(databaseHost);
        }

        return downloadLogs(query.toString(), credentials, startTime, null, maxSamples);
    }

    /**
     * Download APM database spans (slow queries from application tracing)
     */
    public InputStream downloadApmDatabaseSpans(
            String serviceName,
            String resourceName,
            DatadogCredentialsInput credentials,
            Instant startTime,
            long minDurationMs,
            int maxSpans
    ) {
        // Build APM span query
        StringBuilder query = new StringBuilder();
        query.append("@span.kind:client @span.type:sql");

        if (serviceName != null && !serviceName.isBlank()) {
            query.append(" service:").append(serviceName);
        }
        if (resourceName != null && !resourceName.isBlank()) {
            query.append(" resource_name:\"").append(resourceName).append("\"");
        }
        if (minDurationMs > 0) {
            query.append(" @duration:>").append(minDurationMs * 1000000); // Convert to nanoseconds
        }

        return downloadLogs(query.toString(), credentials, startTime, null, maxSpans);
    }

    private void validateCredentials(DatadogCredentialsInput credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("Datadog credentials are required");
        }
        if (credentials.getApiKey() == null || credentials.getApiKey().isBlank()) {
            throw new IllegalArgumentException("Datadog API key is required");
        }
    }

    private String getBaseUrl(String site) {
        if (site == null || site.isBlank() || site.equalsIgnoreCase("US")) {
            return DATADOG_US_API;
        }
        return switch (site.toUpperCase()) {
            case "EU" -> DATADOG_EU_API;
            case "US3" -> DATADOG_US3_API;
            case "US5" -> DATADOG_US5_API;
            case "GOV" -> DATADOG_GOV_API;
            default -> DATADOG_US_API;
        };
    }

    private WebClient buildWebClient(String baseUrl, DatadogCredentialsInput credentials) {
        return WebClient.builder()
                .baseUrl(baseUrl)
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .defaultHeader("DD-API-KEY", credentials.getApiKey())
                .defaultHeader("DD-APPLICATION-KEY",
                        credentials.getApplicationKey() != null ? credentials.getApplicationKey() : "")
                .build();
    }

    private void writeLogsResponse(InputStream responseStream, BufferedWriter writer) throws IOException {
        JsonParser parser = objectMapper.getFactory().createParser(responseStream);
        if (parser.nextToken() != JsonToken.START_OBJECT) {
            return;
        }
        boolean first = true;

        while (parser.nextToken() != null) {
            if (parser.currentToken() == JsonToken.FIELD_NAME && "data".equals(parser.getCurrentName())) {
                if (parser.nextToken() == JsonToken.START_ARRAY) {
                    while (parser.nextToken() != JsonToken.END_ARRAY) {
                        JsonNode logEntry = objectMapper.readTree(parser);
                        String entry = buildLogEntry(logEntry);
                        if (entry != null && !entry.isBlank()) {
                            if (!first) {
                                writer.newLine();
                                writer.newLine();
                            }
                            writer.write(entry);
                            first = false;
                        }
                    }
                } else {
                    parser.skipChildren();
                }
            } else {
                parser.skipChildren();
            }
        }
    }

    private String buildLogEntry(JsonNode logEntry) {
        if (logEntry == null || logEntry.isMissingNode()) {
            return "";
        }
        JsonNode attributes = logEntry.path("attributes");
        if (attributes.isMissingNode()) {
            return "";
        }

        StringBuilder entry = new StringBuilder();

        // Extract timestamp
        String timestamp = attributes.path("timestamp").asText();
        if (!timestamp.isBlank()) {
            entry.append("# Time: ").append(timestamp).append("\n");
        }

        // Extract service
        String service = attributes.path("service").asText();
        if (!service.isBlank()) {
            entry.append("# Service: ").append(service).append("\n");
        }

        // Extract host
        String host = attributes.path("host").asText();
        if (!host.isBlank()) {
            entry.append("# Host: ").append(host).append("\n");
        }

        // Extract status/severity
        String status = attributes.path("status").asText();
        if (!status.isBlank()) {
            entry.append("# Status: ").append(status).append("\n");
        }

        // Extract the message content
        String message = attributes.path("message").asText();
        if (!message.isBlank()) {
            entry.append(message);
        }

        // Also check for SQL-specific attributes
        JsonNode attributesMap = attributes.path("attributes");
        if (!attributesMap.isMissingNode()) {
            String query = attributesMap.path("db.statement").asText();
            if (query.isBlank()) {
                query = attributesMap.path("sql.query").asText();
            }
            if (!query.isBlank()) {
                entry.append("\n# Query: ").append(query);
            }

            String duration = attributesMap.path("duration").asText();
            if (!duration.isBlank()) {
                entry.append("\n# Duration: ").append(duration);
            }
        }

        return entry.toString();
    }

    private String parseLogsResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode data = root.path("data");

            if (!data.isArray() || data.isEmpty()) {
                return "";
            }

            List<String> logEntries = new ArrayList<>();

            for (JsonNode logEntry : data) {
                String entry = buildLogEntry(logEntry);
                if (entry != null && !entry.isBlank()) {
                    logEntries.add(entry);
                }
            }

            return String.join("\n\n", logEntries);

        } catch (Exception e) {
            log.error("Failed to parse Datadog response", e);
            return response; // Return raw response if parsing fails
        }
    }
}
