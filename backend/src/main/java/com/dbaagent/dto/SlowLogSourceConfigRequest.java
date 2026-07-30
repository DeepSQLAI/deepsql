package com.dbaagent.dto;

import lombok.Data;

@Data
public class SlowLogSourceConfigRequest {
    private String connectionId;
    private boolean enabled;
    private String providerType; // S3, CLOUDWATCH, AZURE_BLOB, GCP_LOGGING, DATADOG, ELASTICSEARCH

    // ==================== AWS S3 & CloudWatch Fields ====================
    private String bucketName;
    private String objectPrefix;
    private String s3Region;

    private String logGroupName;
    private String logStreamPrefix;

    private String accessKeyId;
    private String secretAccessKey;
    private String sessionToken;

    // ==================== Azure Blob Storage Fields ====================
    private String azureConnectionString;
    private String azureAccountName;
    private String azureAccountKey;
    private String azureSasToken;
    private String azureContainerName;
    private String azureBlobPrefix;
    private String azureClientId;
    private String azureClientSecret;
    private String azureTenantId;

    // ==================== GCP Cloud Logging Fields ====================
    private String gcpProjectId;
    private String gcpLogFilter;
    private String gcpInstanceId;
    private String gcpServiceAccountJson;

    // ==================== Datadog Fields ====================
    private String datadogApiKey;
    private String datadogAppKey;
    private String datadogSite; // US, EU, US3, US5, GOV
    private String datadogQuery;
    private String datadogServiceName;

    // ==================== Elasticsearch/ELK Fields ====================
    private String elasticsearchHost;
    private Integer elasticsearchPort;
    private String elasticsearchScheme; // http or https
    private String elasticsearchUsername;
    private String elasticsearchPassword;
    private String elasticsearchApiKey;
    private String elasticsearchApiKeyId;
    private String elasticsearchIndexPattern;
    private String elasticsearchQuery;
    private Boolean elasticsearchVerifySsl;

    // ==================== Common Fields ====================
    private Integer refreshFrequencyMinutes;
    private Boolean autoScheduleEnabled;
}
