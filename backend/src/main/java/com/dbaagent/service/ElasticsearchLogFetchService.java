package com.dbaagent.service;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.FieldValue;
import co.elastic.clients.elasticsearch._types.SortOrder;
import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.RangeQuery;
import co.elastic.clients.elasticsearch.core.SearchRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import co.elastic.clients.elasticsearch.core.search.Hit;
import co.elastic.clients.json.jackson.JacksonJsonpMapper;
import co.elastic.clients.transport.ElasticsearchTransport;
import co.elastic.clients.transport.rest_client.RestClientTransport;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.dbaagent.util.CappedOutputStream;
import com.dbaagent.util.LogTempFileUtils;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.http.HttpHost;
import org.apache.http.auth.AuthScope;
import org.apache.http.auth.UsernamePasswordCredentials;
import org.apache.http.impl.client.BasicCredentialsProvider;
import org.elasticsearch.client.RestClient;
import org.elasticsearch.client.RestClientBuilder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Service for fetching slow query logs from Elasticsearch/ELK Stack.
 * Supports:
 * - Elasticsearch 7.x and 8.x
 * - Basic authentication and API key authentication
 * - SSL/TLS connections
 * - Custom index patterns and queries
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ElasticsearchLogFetchService {

    private static final int DEFAULT_SIZE = 1000;
    private static final int MAX_SIZE = 10000;

    private final ObjectMapper objectMapper;

    @Value("${slow-query.log.max-bytes:524288000}")
    private long maxLogBytes = 524288000L;

    @Value("${slow-query.log.temp-dir:}")
    private String tempDir;

    @Value("${slow-query.log.min-free-disk-mb:1024}")
    private long minFreeDiskMb = 1024L;

    /**
     * Credentials and configuration for Elasticsearch
     */
    @Data
    public static class ElasticsearchCredentialsInput {
        private String host;              // Elasticsearch host (e.g., localhost)
        private Integer port;             // Port (default: 9200)
        private String scheme;            // http or https (default: https)
        private String username;          // Basic auth username
        private String password;          // Basic auth password
        private String apiKey;            // API key for authentication
        private String apiKeyId;          // API key ID (for API key auth)
        private boolean verifySsl;        // Whether to verify SSL certificates (default: true)
        private String caCertificate;     // Custom CA certificate (PEM format)
    }

    /**
     * Download slow query logs from Elasticsearch
     *
     * @param indexPattern Index pattern to search (e.g., "mysql-slowlog-*", "filebeat-*")
     * @param queryString Lucene query string for filtering
     * @param credentials Elasticsearch credentials
     * @param startTime Start time for log retrieval
     * @param endTime End time for log retrieval
     * @param maxLogs Maximum number of logs to retrieve
     * @param timestampField Name of the timestamp field (default: @timestamp)
     * @return InputStream containing combined log entries
     */
    public InputStream downloadLogs(
            String indexPattern,
            String queryString,
            ElasticsearchCredentialsInput credentials,
            Instant startTime,
            Instant endTime,
            int maxLogs,
            String timestampField
    ) {
        validateCredentials(credentials);

        if (indexPattern == null || indexPattern.isBlank()) {
            throw new IllegalArgumentException("Index pattern is required");
        }

        final String tsField = (timestampField == null || timestampField.isBlank())
            ? "@timestamp"
            : timestampField;

        int size = Math.min(maxLogs > 0 ? maxLogs : DEFAULT_SIZE, MAX_SIZE);

        log.info("Fetching Elasticsearch logs: index={}, query='{}', from={}, to={}, size={}",
                indexPattern, queryString, startTime, endTime, size);

        ElasticsearchClient client = null;
        RestClient restClient = null;

        try {
            restClient = buildRestClient(credentials);
            ElasticsearchTransport transport = new RestClientTransport(restClient, new JacksonJsonpMapper());
            client = new ElasticsearchClient(transport);

            // Build the search query
            Query query = buildQuery(queryString, startTime, endTime, tsField);

            java.nio.file.Path tempFile = LogTempFileUtils.createTempFile(
                tempDir, minFreeDiskMb, "es-logs-", ".log");
            long totalHits = 0;
            int maxTotal = maxLogs > 0 ? maxLogs : Integer.MAX_VALUE;
            List<FieldValue> searchAfter = null;
            boolean hasHits = false;

            try (OutputStream rawOut = java.nio.file.Files.newOutputStream(tempFile);
                 OutputStream out = maxLogBytes > 0 ? new CappedOutputStream(rawOut, maxLogBytes) : rawOut;
                 java.io.BufferedWriter writer = new java.io.BufferedWriter(
                     new java.io.OutputStreamWriter(out, StandardCharsets.UTF_8))) {

                while (totalHits < maxTotal) {
                    SearchRequest.Builder builder = new SearchRequest.Builder()
                        .index(indexPattern)
                        .query(query)
                        .size(size)
                        .sort(sort -> sort.field(f -> f.field(tsField).order(SortOrder.Asc)))
                        .sort(sort -> sort.field(f -> f.field("_id").order(SortOrder.Asc)));

                    if (searchAfter != null && !searchAfter.isEmpty()) {
                        builder.searchAfter(searchAfter);
                    }

                    SearchResponse<JsonNode> response = client.search(builder.build(), JsonNode.class);
                    List<Hit<JsonNode>> hits = response.hits().hits();
                    if (hits.isEmpty()) {
                        break;
                    }

                    for (Hit<JsonNode> hit : hits) {
                        if (totalHits >= maxTotal) {
                            break;
                        }
                        String entry = formatHit(hit, tsField);
                        if (entry != null && !entry.isBlank()) {
                            if (hasHits) {
                                writer.newLine();
                                writer.newLine();
                            }
                            writer.write(entry);
                            hasHits = true;
                        }
                        totalHits++;
                    }

                    if (hits.size() < size) {
                        break;
                    }
                    searchAfter = hits.get(hits.size() - 1).sort();
                    if (searchAfter == null || searchAfter.isEmpty()) {
                        break;
                    }
                }
            } catch (Exception e) {
                try {
                    java.nio.file.Files.deleteIfExists(tempFile);
                } catch (Exception ignored) {
                    // Best-effort cleanup
                }
                throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
            }

            if (!hasHits) {
                java.nio.file.Files.deleteIfExists(tempFile);
                return new ByteArrayInputStream(new byte[0]);
            }

            log.info("Retrieved {} log entries from Elasticsearch", totalHits);
            return LogTempFileUtils.openDeleteOnClose(tempFile);

        } catch (Exception e) {
            log.error("Failed to fetch logs from Elasticsearch: {}", e.getMessage(), e);
            if (e instanceof com.dbaagent.exception.LogSizeExceededException) {
                throw (com.dbaagent.exception.LogSizeExceededException) e;
            }
            throw new RuntimeException("Failed to fetch Elasticsearch logs: " + e.getMessage(), e);
        } finally {
            if (restClient != null) {
                try {
                    restClient.close();
                } catch (Exception e) {
                    log.warn("Error closing Elasticsearch client", e);
                }
            }
        }
    }

    /**
     * Download MySQL slow query logs from Elasticsearch
     * (for Filebeat mysql module or custom MySQL slow log parsing)
     */
    public InputStream downloadMysqlSlowQueryLogs(
            String indexPattern,
            ElasticsearchCredentialsInput credentials,
            Instant startTime,
            int maxLogs
    ) {
        String query = "event.dataset:mysql.slowlog OR mysql.slowlog.query:*";
        if (indexPattern == null || indexPattern.isBlank()) {
            indexPattern = "filebeat-*";
        }
        return downloadLogs(indexPattern, query, credentials, startTime, null, maxLogs, "@timestamp");
    }

    /**
     * Download PostgreSQL slow query logs from Elasticsearch
     */
    public InputStream downloadPostgresSlowQueryLogs(
            String indexPattern,
            ElasticsearchCredentialsInput credentials,
            Instant startTime,
            int maxLogs
    ) {
        String query = "event.dataset:postgresql.log AND message:*duration*";
        if (indexPattern == null || indexPattern.isBlank()) {
            indexPattern = "filebeat-*";
        }
        return downloadLogs(indexPattern, query, credentials, startTime, null, maxLogs, "@timestamp");
    }

    /**
     * Download generic database logs with custom query
     */
    public InputStream downloadDatabaseLogs(
            String indexPattern,
            String databaseType,
            ElasticsearchCredentialsInput credentials,
            Instant startTime,
            long minDurationMs,
            int maxLogs
    ) {
        StringBuilder query = new StringBuilder();

        if (databaseType != null) {
            switch (databaseType.toLowerCase()) {
                case "mysql" -> query.append("(mysql.slowlog.query:* OR event.dataset:mysql.slowlog)");
                case "postgresql", "postgres" -> query.append("(postgresql.log.* OR event.dataset:postgresql.log)");
                case "mongodb" -> query.append("(mongodb.log.* OR event.dataset:mongodb.log)");
                default -> query.append("*");
            }
        }

        if (minDurationMs > 0) {
            if (!query.isEmpty()) query.append(" AND ");
            // Try common duration field names
            query.append("(mysql.slowlog.query_time_sec:>=")
                 .append(minDurationMs / 1000.0)
                 .append(" OR event.duration:>=")
                 .append(minDurationMs * 1000000) // nanoseconds
                 .append(")");
        }

        return downloadLogs(indexPattern, query.toString(), credentials, startTime, null, maxLogs, "@timestamp");
    }

    private void validateCredentials(ElasticsearchCredentialsInput credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("Elasticsearch credentials are required");
        }
        if (credentials.getHost() == null || credentials.getHost().isBlank()) {
            throw new IllegalArgumentException("Elasticsearch host is required");
        }
    }

    private Query buildQuery(String queryString, Instant startTime, Instant endTime, String timestampField) {
        BoolQuery.Builder boolQuery = new BoolQuery.Builder();

        // Add query string if provided
        if (queryString != null && !queryString.isBlank()) {
            boolQuery.must(Query.of(q -> q
                    .queryString(qs -> qs.query(queryString))));
        }

        // Add time range filter using date range query
        if (startTime != null || endTime != null) {
            final Instant finalStartTime = startTime;
            final Instant finalEndTime = endTime;

            boolQuery.filter(Query.of(q -> q
                    .range(r -> {
                        r.date(d -> {
                            d.field(timestampField);
                            if (finalStartTime != null) {
                                d.gte(finalStartTime.toString());
                            }
                            if (finalEndTime != null) {
                                d.lte(finalEndTime.toString());
                            }
                            return d;
                        });
                        return r;
                    })));
        }

        return Query.of(q -> q.bool(boolQuery.build()));
    }

    private String formatHits(List<Hit<JsonNode>> hits, String timestampField) {
        List<String> entries = new ArrayList<>();

        for (Hit<JsonNode> hit : hits) {
            String entry = formatHit(hit, timestampField);
            if (entry != null && !entry.isBlank()) {
                entries.add(entry);
            }
        }

        return String.join("\n\n", entries);
    }

    private String formatHit(Hit<JsonNode> hit, String timestampField) {
        if (hit == null || hit.source() == null) {
            return "";
        }
        JsonNode source = hit.source();

        StringBuilder entry = new StringBuilder();

        // Extract timestamp
        JsonNode timestamp = source.path(timestampField);
        if (!timestamp.isMissingNode()) {
            entry.append("# Time: ").append(timestamp.asText()).append("\n");
        }

        // Extract common fields
        extractField(source, "host.name", "Host", entry);
        extractField(source, "user.name", "User", entry);

        // MySQL slow log specific fields
        extractField(source, "mysql.slowlog.query_time_sec", "Query_time", entry);
        extractField(source, "mysql.slowlog.lock_time_sec", "Lock_time", entry);
        extractField(source, "mysql.slowlog.rows_sent", "Rows_sent", entry);
        extractField(source, "mysql.slowlog.rows_examined", "Rows_examined", entry);

        // PostgreSQL specific fields
        extractField(source, "postgresql.log.duration", "Duration", entry);
        extractField(source, "postgresql.log.database", "Database", entry);

        // Generic event fields
        extractField(source, "event.duration", "Duration_ns", entry);

        // Extract the query/message
        JsonNode query = source.path("mysql.slowlog.query");
        if (query.isMissingNode()) {
            query = source.path("message");
        }
        if (query.isMissingNode()) {
            query = source.path("log.original");
        }
        if (!query.isMissingNode()) {
            entry.append(query.asText());
        }

        return entry.toString();
    }

    private void extractField(JsonNode source, String path, String label, StringBuilder entry) {
        String[] parts = path.split("\\.");
        JsonNode current = source;
        for (String part : parts) {
            current = current.path(part);
            if (current.isMissingNode()) break;
        }
        if (!current.isMissingNode()) {
            entry.append("# ").append(label).append(": ").append(current.asText()).append("\n");
        }
    }

    private RestClient buildRestClient(ElasticsearchCredentialsInput credentials) {
        String scheme = credentials.getScheme() != null ? credentials.getScheme() : "https";
        int port = credentials.getPort() != null ? credentials.getPort() : 9200;

        HttpHost host = new HttpHost(credentials.getHost(), port, scheme);
        RestClientBuilder builder = RestClient.builder(host);

        // Configure authentication
        if (credentials.getApiKey() != null && !credentials.getApiKey().isBlank()) {
            // API Key authentication
            String apiKeyAuth = credentials.getApiKeyId() != null
                    ? credentials.getApiKeyId() + ":" + credentials.getApiKey()
                    : credentials.getApiKey();

            String encodedApiKey = java.util.Base64.getEncoder().encodeToString(
                    apiKeyAuth.getBytes(StandardCharsets.UTF_8));

            builder.setDefaultHeaders(new org.apache.http.Header[]{
                    new org.apache.http.message.BasicHeader("Authorization", "ApiKey " + encodedApiKey)
            });

        } else if (credentials.getUsername() != null && credentials.getPassword() != null) {
            // Basic authentication
            BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
            credentialsProvider.setCredentials(AuthScope.ANY,
                    new UsernamePasswordCredentials(credentials.getUsername(), credentials.getPassword()));

            builder.setHttpClientConfigCallback(httpClientBuilder ->
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider));
        }

        // Configure SSL if needed
        if ("https".equals(scheme)) {
            builder.setHttpClientConfigCallback(httpClientBuilder -> {
                try {
                    if (!credentials.isVerifySsl()) {
                        // Disable SSL verification (not recommended for production)
                        SSLContext sslContext = SSLContext.getInstance("TLS");
                        sslContext.init(null, new javax.net.ssl.TrustManager[]{
                                new javax.net.ssl.X509TrustManager() {
                                    public java.security.cert.X509Certificate[] getAcceptedIssuers() { return null; }
                                    public void checkClientTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                                    public void checkServerTrusted(java.security.cert.X509Certificate[] certs, String authType) {}
                                }
                        }, new java.security.SecureRandom());
                        httpClientBuilder.setSSLContext(sslContext);
                        httpClientBuilder.setSSLHostnameVerifier((hostname, session) -> true);
                    }
                } catch (Exception e) {
                    log.warn("Failed to configure SSL context", e);
                }

                // Add basic auth if configured
                if (credentials.getUsername() != null && credentials.getPassword() != null) {
                    BasicCredentialsProvider credentialsProvider = new BasicCredentialsProvider();
                    credentialsProvider.setCredentials(AuthScope.ANY,
                            new UsernamePasswordCredentials(credentials.getUsername(), credentials.getPassword()));
                    httpClientBuilder.setDefaultCredentialsProvider(credentialsProvider);
                }

                return httpClientBuilder;
            });
        }

        return builder.build();
    }
}
