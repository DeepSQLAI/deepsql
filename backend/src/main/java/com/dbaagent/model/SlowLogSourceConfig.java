package com.dbaagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "slow_log_source_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SlowLogSourceConfig {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "enabled", nullable = false)
    private boolean enabled;

    @Column(name = "provider_type", nullable = false, length = 30)
    private String providerType; // S3, CLOUDWATCH, AZURE_BLOB, GCP_LOGGING, DATADOG, ELASTICSEARCH

    // ==================== AWS S3 & CloudWatch Fields ====================
    @Column(name = "bucket_name")
    private String bucketName;

    @Column(name = "object_prefix")
    private String objectPrefix;

    @Column(name = "s3_region")
    private String s3Region;

    @Column(name = "log_group_name")
    private String logGroupName;

    @Column(name = "log_stream_prefix")
    private String logStreamPrefix;

    @Column(name = "access_key_id")
    private byte[] accessKeyId;

    @Column(name = "secret_access_key")
    private byte[] secretAccessKey;

    @Column(name = "session_token")
    private byte[] sessionToken;

    // ==================== Azure Blob Storage Fields ====================
    @Column(name = "azure_connection_string")
    private byte[] azureConnectionString;

    @Column(name = "azure_account_name")
    private String azureAccountName;

    @Column(name = "azure_account_key")
    private byte[] azureAccountKey;

    @Column(name = "azure_sas_token")
    private byte[] azureSasToken;

    @Column(name = "azure_container_name")
    private String azureContainerName;

    @Column(name = "azure_blob_prefix")
    private String azureBlobPrefix;

    @Column(name = "azure_client_id")
    private String azureClientId;

    @Column(name = "azure_client_secret")
    private byte[] azureClientSecret;

    @Column(name = "azure_tenant_id")
    private String azureTenantId;

    // ==================== GCP Cloud Logging Fields ====================
    @Column(name = "gcp_project_id")
    private String gcpProjectId;

    @Column(name = "gcp_log_filter")
    private String gcpLogFilter;

    @Column(name = "gcp_instance_id")
    private String gcpInstanceId;

    @Column(name = "gcp_service_account_json")
    private byte[] gcpServiceAccountJson;

    // ==================== Datadog Fields ====================
    @Column(name = "datadog_api_key")
    private byte[] datadogApiKey;

    @Column(name = "datadog_app_key")
    private byte[] datadogAppKey;

    @Column(name = "datadog_site", length = 10)
    private String datadogSite; // US, EU, US3, US5, GOV

    @Column(name = "datadog_query")
    private String datadogQuery;

    @Column(name = "datadog_service_name")
    private String datadogServiceName;

    // ==================== Elasticsearch/ELK Fields ====================
    @Column(name = "elasticsearch_host")
    private String elasticsearchHost;

    @Column(name = "elasticsearch_port")
    private Integer elasticsearchPort;

    @Column(name = "elasticsearch_scheme", length = 10)
    private String elasticsearchScheme; // http or https

    @Column(name = "elasticsearch_username")
    private String elasticsearchUsername;

    @Column(name = "elasticsearch_password")
    private byte[] elasticsearchPassword;

    @Column(name = "elasticsearch_api_key")
    private byte[] elasticsearchApiKey;

    @Column(name = "elasticsearch_api_key_id")
    private String elasticsearchApiKeyId;

    @Column(name = "elasticsearch_index_pattern")
    private String elasticsearchIndexPattern;

    @Column(name = "elasticsearch_query")
    private String elasticsearchQuery;

    @Column(name = "elasticsearch_verify_ssl")
    private Boolean elasticsearchVerifySsl;

    // ==================== Common Fields ====================
    @Column(name = "refresh_frequency_minutes")
    private Integer refreshFrequencyMinutes;

    @Column(name = "auto_schedule_enabled")
    private Boolean autoScheduleEnabled;

    @Column(name = "last_processed_at")
    private LocalDateTime lastProcessedAt;

    @Column(name = "next_scheduled_run_at")
    private LocalDateTime nextScheduledRunAt;

    @Column(name = "last_auto_ingest_message")
    private String lastAutoIngestMessage;

    /**
     * Consecutive ingestion runs that returned no events. Reset to 0 whenever a
     * run actually ingests data. Used to detect a dry / misconfigured source.
     */
    @Column(name = "consecutive_dry_runs")
    private Integer consecutiveDryRuns;

    /**
     * The target DB's cumulative slow-query count (MySQL {@code Slow_queries}
     * global status) at the last dry-run check. If this keeps climbing while the
     * source stays dry, the slow-log export is misconfigured — the DB is logging
     * slow queries but they aren't reaching the source.
     */
    @Column(name = "last_db_slow_query_count")
    private Long lastDbSlowQueryCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Supported provider types
     */
    public enum ProviderType {
        S3,
        CLOUDWATCH,
        AZURE_BLOB,
        GCP_LOGGING,
        DATADOG,
        ELASTICSEARCH
    }
}
