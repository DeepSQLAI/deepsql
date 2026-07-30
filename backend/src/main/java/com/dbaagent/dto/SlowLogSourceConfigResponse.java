package com.dbaagent.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class SlowLogSourceConfigResponse {
    private String id;
    private String connectionId;
    private boolean enabled;
    private String providerType;

    // ==================== AWS S3 & CloudWatch Fields ====================
    private String bucketName;
    private String objectPrefix;
    private String s3Region;

    private String logGroupName;
    private String logStreamPrefix;

    // Note: Credentials are NOT returned for security reasons (accessKeyId, secretAccessKey, sessionToken)

    // ==================== Azure Blob Storage Fields ====================
    private String azureAccountName;
    private String azureContainerName;
    private String azureBlobPrefix;
    private String azureClientId;
    private String azureTenantId;
    // Note: Credentials NOT returned (connectionString, accountKey, sasToken, clientSecret)

    // ==================== GCP Cloud Logging Fields ====================
    private String gcpProjectId;
    private String gcpLogFilter;
    private String gcpInstanceId;
    // Note: Service account JSON NOT returned

    // ==================== Datadog Fields ====================
    private String datadogSite;
    private String datadogQuery;
    private String datadogServiceName;
    // Note: Credentials NOT returned (apiKey, appKey)

    // ==================== Elasticsearch/ELK Fields ====================
    private String elasticsearchHost;
    private Integer elasticsearchPort;
    private String elasticsearchScheme;
    private String elasticsearchUsername;
    private String elasticsearchApiKeyId;
    private String elasticsearchIndexPattern;
    private String elasticsearchQuery;
    private Boolean elasticsearchVerifySsl;
    // Note: Credentials NOT returned (password, apiKey)

    // ==================== Common Fields ====================
    private Integer refreshFrequencyMinutes;
    private boolean autoScheduleEnabled;
    private LocalDateTime lastProcessedAt;
    private LocalDateTime nextScheduledRunAt;
    private String lastAutoIngestMessage;
    private LocalDateTime updatedAt;
}
