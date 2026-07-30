package com.dbaagent.dto;

import lombok.Data;
import java.time.Instant;

@Data
public class SlowLogIngestRequest {
    private String connectionId;
    private boolean includeCloudWatch;
    private boolean includeS3;
    private boolean includeAzureBlob;
    private boolean includeGcpLogging;
    private boolean includeDatadog;
    private boolean includeElasticsearch;
    private Integer maxLogs;

    /**
     * Custom start time for log ingestion.
     * If null, uses lastProcessedAt from config (incremental ingestion).
     * If "ALL", fetches all available logs (no time filter).
     */
    private Instant startTime;

    /**
     * Time range preset: "SINCE_LAST", "LAST_24_HOURS", "LAST_7_DAYS", "LAST_30_DAYS", "ALL"
     * Takes precedence over startTime if set.
     */
    private String timeRange;

    /**
     * Convenience method to check if the request includes the given provider.
     */
    public boolean includesProvider(String provider) {
        if (provider == null) return false;
        return switch (provider.toUpperCase()) {
            case "S3" -> includeS3;
            case "CLOUDWATCH" -> includeCloudWatch;
            case "AZURE_BLOB" -> includeAzureBlob;
            case "GCP_LOGGING" -> includeGcpLogging;
            case "DATADOG" -> includeDatadog;
            case "ELASTICSEARCH" -> includeElasticsearch;
            default -> false;
        };
    }
}
