package com.dbaagent.service;

import com.dbaagent.dto.SlowLogSourceConfigRequest;
import com.dbaagent.dto.SlowLogSourceConfigResponse;
import com.dbaagent.model.SlowLogSourceConfig;
import com.dbaagent.repository.SlowLogSourceConfigRepository;
import com.dbaagent.security.EncryptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SlowLogSourceConfigService {
    private final SlowLogSourceConfigRepository repository;
    private final EncryptionService encryptionService;

    public Optional<SlowLogSourceConfig> getByConnectionId(String connectionId) {
        return repository.findByConnectionId(connectionId);
    }

    public SlowLogSourceConfigResponse getResponse(String connectionId) {
        return repository.findByConnectionId(connectionId)
            .map(this::toResponse)
            .orElse(null);
    }

    public SlowLogSourceConfigResponse upsertConfig(SlowLogSourceConfigRequest request) {
        SlowLogSourceConfig config = repository.findByConnectionId(request.getConnectionId())
            .orElseGet(() -> SlowLogSourceConfig.builder()
                .id(UUID.randomUUID().toString())
                .connectionId(request.getConnectionId())
                .createdAt(LocalDateTime.now())
                .build());

        LocalDateTime now = LocalDateTime.now();
        String connectionId = config.getConnectionId();

        config.setEnabled(request.isEnabled());
        config.setProviderType(request.getProviderType());

        // AWS S3 & CloudWatch Fields
        config.setBucketName(request.getBucketName());
        config.setObjectPrefix(request.getObjectPrefix());
        config.setS3Region(request.getS3Region());
        config.setLogGroupName(request.getLogGroupName());
        config.setLogStreamPrefix(request.getLogStreamPrefix());

        // AWS Credentials (only update if provided)
        updateAwsCredentials(config, request, connectionId);

        // Azure Blob Storage Fields
        config.setAzureAccountName(request.getAzureAccountName());
        config.setAzureContainerName(request.getAzureContainerName());
        config.setAzureBlobPrefix(request.getAzureBlobPrefix());
        config.setAzureClientId(request.getAzureClientId());
        config.setAzureTenantId(request.getAzureTenantId());
        updateAzureCredentials(config, request, connectionId);

        // GCP Cloud Logging Fields
        config.setGcpProjectId(request.getGcpProjectId());
        config.setGcpLogFilter(request.getGcpLogFilter());
        config.setGcpInstanceId(request.getGcpInstanceId());
        updateGcpCredentials(config, request, connectionId);

        // Datadog Fields
        config.setDatadogSite(request.getDatadogSite());
        config.setDatadogQuery(request.getDatadogQuery());
        config.setDatadogServiceName(request.getDatadogServiceName());
        updateDatadogCredentials(config, request, connectionId);

        // Elasticsearch Fields
        config.setElasticsearchHost(request.getElasticsearchHost());
        config.setElasticsearchPort(request.getElasticsearchPort());
        config.setElasticsearchScheme(request.getElasticsearchScheme());
        config.setElasticsearchUsername(request.getElasticsearchUsername());
        config.setElasticsearchApiKeyId(request.getElasticsearchApiKeyId());
        config.setElasticsearchIndexPattern(request.getElasticsearchIndexPattern());
        config.setElasticsearchQuery(request.getElasticsearchQuery());
        config.setElasticsearchVerifySsl(request.getElasticsearchVerifySsl());
        updateElasticsearchCredentials(config, request, connectionId);

        // Refresh frequency (enforce minimum for CloudWatch)
        Integer frequency = request.getRefreshFrequencyMinutes();
        if ("CLOUDWATCH".equalsIgnoreCase(request.getProviderType())) {
            if (frequency == null || frequency < 1440) {
                frequency = 1440;
            }
        }
        config.setRefreshFrequencyMinutes(frequency);

        // Handle auto-schedule settings
        Boolean autoScheduleEnabled = request.getAutoScheduleEnabled();
        if (autoScheduleEnabled != null) {
            config.setAutoScheduleEnabled(autoScheduleEnabled);
            if (autoScheduleEnabled && frequency != null) {
                config.setNextScheduledRunAt(now.plusMinutes(frequency));
            } else if (!autoScheduleEnabled) {
                config.setNextScheduledRunAt(null);
            }
        }

        config.setUpdatedAt(now);

        SlowLogSourceConfig saved = repository.save(config);
        return toResponse(saved);
    }

    private void updateAwsCredentials(SlowLogSourceConfig config, SlowLogSourceConfigRequest request, String connectionId) {
        boolean accessProvided = hasText(request.getAccessKeyId());
        boolean secretProvided = hasText(request.getSecretAccessKey());
        boolean sessionProvided = hasText(request.getSessionToken());

        if (accessProvided || secretProvided) {
            config.setAccessKeyId(encryptionService.encryptIfPresent(request.getAccessKeyId(), connectionId));
            config.setSecretAccessKey(encryptionService.encryptIfPresent(request.getSecretAccessKey(), connectionId));
            if (sessionProvided) {
                config.setSessionToken(encryptionService.encryptIfPresent(request.getSessionToken(), connectionId));
            } else {
                config.setSessionToken(null);
            }
        } else if (sessionProvided) {
            config.setSessionToken(encryptionService.encryptIfPresent(request.getSessionToken(), connectionId));
        }
    }

    private void updateAzureCredentials(SlowLogSourceConfig config, SlowLogSourceConfigRequest request, String connectionId) {
        if (hasText(request.getAzureConnectionString())) {
            config.setAzureConnectionString(encryptionService.encryptIfPresent(request.getAzureConnectionString(), connectionId));
        }
        if (hasText(request.getAzureAccountKey())) {
            config.setAzureAccountKey(encryptionService.encryptIfPresent(request.getAzureAccountKey(), connectionId));
        }
        if (hasText(request.getAzureSasToken())) {
            config.setAzureSasToken(encryptionService.encryptIfPresent(request.getAzureSasToken(), connectionId));
        }
        if (hasText(request.getAzureClientSecret())) {
            config.setAzureClientSecret(encryptionService.encryptIfPresent(request.getAzureClientSecret(), connectionId));
        }
    }

    private void updateGcpCredentials(SlowLogSourceConfig config, SlowLogSourceConfigRequest request, String connectionId) {
        if (hasText(request.getGcpServiceAccountJson())) {
            config.setGcpServiceAccountJson(encryptionService.encryptIfPresent(request.getGcpServiceAccountJson(), connectionId));
        }
    }

    private void updateDatadogCredentials(SlowLogSourceConfig config, SlowLogSourceConfigRequest request, String connectionId) {
        if (hasText(request.getDatadogApiKey())) {
            config.setDatadogApiKey(encryptionService.encryptIfPresent(request.getDatadogApiKey(), connectionId));
        }
        if (hasText(request.getDatadogAppKey())) {
            config.setDatadogAppKey(encryptionService.encryptIfPresent(request.getDatadogAppKey(), connectionId));
        }
    }

    private void updateElasticsearchCredentials(SlowLogSourceConfig config, SlowLogSourceConfigRequest request, String connectionId) {
        if (hasText(request.getElasticsearchPassword())) {
            config.setElasticsearchPassword(encryptionService.encryptIfPresent(request.getElasticsearchPassword(), connectionId));
        }
        if (hasText(request.getElasticsearchApiKey())) {
            config.setElasticsearchApiKey(encryptionService.encryptIfPresent(request.getElasticsearchApiKey(), connectionId));
        }
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * Clone the slow-log source config from {@code sourceConnectionId} to
     * {@code targetConnectionId}, properly re-encrypting every secret with
     * the target's AAD.
     *
     * <p>Why this can't be a SQL INSERT-SELECT: every secret column is
     * encrypted by {@link EncryptionService#encryptIfPresent(String, String)}
     * which uses the connection ID as AES-GCM Additional Authenticated Data.
     * Copying the ciphertext as-is into a row with a different
     * {@code connection_id} produces a "Tag mismatch" on the next decrypt —
     * the symptom we hit on the just-cloned aws-rds-replica connection when
     * scheduled ingestion tried to read its CloudWatch credentials.
     *
     * <p>Re-encrypts: AWS (accessKey, secret, session), Azure (connection
     * string, account key, SAS, client secret), GCP (service-account JSON),
     * Datadog (API key, app key), and Elasticsearch (password, API key).
     * Cleartext config fields (log group, region, etc.) and the
     * schedule/refresh metadata are copied as-is.
     *
     * <p>If the target already has a slow-log config, it's overwritten. If
     * the source has none, returns null and writes nothing.
     */
    public SlowLogSourceConfigResponse cloneFrom(String sourceConnectionId, String targetConnectionId) {
        SlowLogSourceConfig source = repository.findByConnectionId(sourceConnectionId).orElse(null);
        if (source == null) {
            return null;
        }

        SlowLogSourceConfig target = repository.findByConnectionId(targetConnectionId)
            .orElseGet(() -> SlowLogSourceConfig.builder()
                .id(UUID.randomUUID().toString())
                .connectionId(targetConnectionId)
                .createdAt(LocalDateTime.now())
                .build());

        target.setEnabled(source.isEnabled());
        target.setProviderType(source.getProviderType());

        // Cleartext config fields (no AAD involved)
        target.setBucketName(source.getBucketName());
        target.setObjectPrefix(source.getObjectPrefix());
        target.setS3Region(source.getS3Region());
        target.setLogGroupName(source.getLogGroupName());
        target.setLogStreamPrefix(source.getLogStreamPrefix());

        target.setAzureAccountName(source.getAzureAccountName());
        target.setAzureContainerName(source.getAzureContainerName());
        target.setAzureBlobPrefix(source.getAzureBlobPrefix());
        target.setAzureClientId(source.getAzureClientId());
        target.setAzureTenantId(source.getAzureTenantId());

        target.setGcpProjectId(source.getGcpProjectId());
        target.setGcpLogFilter(source.getGcpLogFilter());
        target.setGcpInstanceId(source.getGcpInstanceId());

        target.setDatadogSite(source.getDatadogSite());
        target.setDatadogQuery(source.getDatadogQuery());
        target.setDatadogServiceName(source.getDatadogServiceName());

        target.setElasticsearchHost(source.getElasticsearchHost());
        target.setElasticsearchPort(source.getElasticsearchPort());
        target.setElasticsearchScheme(source.getElasticsearchScheme());
        target.setElasticsearchUsername(source.getElasticsearchUsername());
        target.setElasticsearchApiKeyId(source.getElasticsearchApiKeyId());
        target.setElasticsearchIndexPattern(source.getElasticsearchIndexPattern());
        target.setElasticsearchQuery(source.getElasticsearchQuery());
        target.setElasticsearchVerifySsl(source.getElasticsearchVerifySsl());

        // Encrypted credentials — decrypt with source AAD, re-encrypt with target AAD
        target.setAccessKeyId(reencrypt(source.getAccessKeyId(), sourceConnectionId, targetConnectionId));
        target.setSecretAccessKey(reencrypt(source.getSecretAccessKey(), sourceConnectionId, targetConnectionId));
        target.setSessionToken(reencrypt(source.getSessionToken(), sourceConnectionId, targetConnectionId));

        target.setAzureConnectionString(reencrypt(source.getAzureConnectionString(), sourceConnectionId, targetConnectionId));
        target.setAzureAccountKey(reencrypt(source.getAzureAccountKey(), sourceConnectionId, targetConnectionId));
        target.setAzureSasToken(reencrypt(source.getAzureSasToken(), sourceConnectionId, targetConnectionId));
        target.setAzureClientSecret(reencrypt(source.getAzureClientSecret(), sourceConnectionId, targetConnectionId));

        target.setGcpServiceAccountJson(reencrypt(source.getGcpServiceAccountJson(), sourceConnectionId, targetConnectionId));

        target.setDatadogApiKey(reencrypt(source.getDatadogApiKey(), sourceConnectionId, targetConnectionId));
        target.setDatadogAppKey(reencrypt(source.getDatadogAppKey(), sourceConnectionId, targetConnectionId));

        target.setElasticsearchPassword(reencrypt(source.getElasticsearchPassword(), sourceConnectionId, targetConnectionId));
        target.setElasticsearchApiKey(reencrypt(source.getElasticsearchApiKey(), sourceConnectionId, targetConnectionId));

        // Schedule metadata — fresh, so the target picks up on the next poll
        target.setRefreshFrequencyMinutes(source.getRefreshFrequencyMinutes());
        target.setAutoScheduleEnabled(source.getAutoScheduleEnabled());
        Boolean autoOn = source.getAutoScheduleEnabled();
        if (Boolean.TRUE.equals(autoOn) && source.getRefreshFrequencyMinutes() != null) {
            target.setNextScheduledRunAt(LocalDateTime.now());
        }
        target.setUpdatedAt(LocalDateTime.now());

        return toResponse(repository.save(target));
    }

    /**
     * AAD-safe rotation of a single encrypted blob. Returns null when the
     * source ciphertext is null/empty, or when decryption fails (rather
     * than aborting the whole clone — the missing credential will surface
     * as a runtime error on first use, which is more debuggable than a
     * silent half-cloned config).
     */
    private byte[] reencrypt(byte[] ciphertext, String oldAad, String newAad) {
        if (ciphertext == null || ciphertext.length == 0) {
            return null;
        }
        String plain = encryptionService.decryptIfPresent(ciphertext, oldAad);
        if (plain == null || plain.isEmpty()) {
            return null;
        }
        return encryptionService.encryptIfPresent(plain, newAad);
    }

    public void disable(String connectionId) {
        repository.findByConnectionId(connectionId)
            .ifPresent(config -> {
                config.setEnabled(false);
                config.setUpdatedAt(LocalDateTime.now());
                repository.save(config);
            });
    }

    public SlowLogSourceConfigResponse toResponse(SlowLogSourceConfig config) {
        return SlowLogSourceConfigResponse.builder()
            .id(config.getId())
            .connectionId(config.getConnectionId())
            .enabled(config.isEnabled())
            .providerType(config.getProviderType())
            // AWS S3 & CloudWatch
            .bucketName(config.getBucketName())
            .objectPrefix(config.getObjectPrefix())
            .s3Region(config.getS3Region())
            .logGroupName(config.getLogGroupName())
            .logStreamPrefix(config.getLogStreamPrefix())
            // Azure Blob Storage (non-sensitive only)
            .azureAccountName(config.getAzureAccountName())
            .azureContainerName(config.getAzureContainerName())
            .azureBlobPrefix(config.getAzureBlobPrefix())
            .azureClientId(config.getAzureClientId())
            .azureTenantId(config.getAzureTenantId())
            // GCP Cloud Logging
            .gcpProjectId(config.getGcpProjectId())
            .gcpLogFilter(config.getGcpLogFilter())
            .gcpInstanceId(config.getGcpInstanceId())
            // Datadog (non-sensitive only)
            .datadogSite(config.getDatadogSite())
            .datadogQuery(config.getDatadogQuery())
            .datadogServiceName(config.getDatadogServiceName())
            // Elasticsearch (non-sensitive only)
            .elasticsearchHost(config.getElasticsearchHost())
            .elasticsearchPort(config.getElasticsearchPort())
            .elasticsearchScheme(config.getElasticsearchScheme())
            .elasticsearchUsername(config.getElasticsearchUsername())
            .elasticsearchApiKeyId(config.getElasticsearchApiKeyId())
            .elasticsearchIndexPattern(config.getElasticsearchIndexPattern())
            .elasticsearchQuery(config.getElasticsearchQuery())
            .elasticsearchVerifySsl(config.getElasticsearchVerifySsl())
            // Common fields
            .refreshFrequencyMinutes(config.getRefreshFrequencyMinutes())
            .autoScheduleEnabled(Boolean.TRUE.equals(config.getAutoScheduleEnabled()))
            .lastProcessedAt(config.getLastProcessedAt())
            .nextScheduledRunAt(config.getNextScheduledRunAt())
            .lastAutoIngestMessage(config.getLastAutoIngestMessage())
            .updatedAt(config.getUpdatedAt())
            .build();
    }

    public void updateAfterAutoIngestion(SlowLogSourceConfig config, boolean success, String message) {
        LocalDateTime now = LocalDateTime.now();
        config.setLastAutoIngestMessage(message);
        if (success) {
            config.setLastProcessedAt(now);
        }
        // Calculate next scheduled run
        if (Boolean.TRUE.equals(config.getAutoScheduleEnabled()) && config.getRefreshFrequencyMinutes() != null) {
            config.setNextScheduledRunAt(now.plusMinutes(config.getRefreshFrequencyMinutes()));
        }
        config.setUpdatedAt(now);
        repository.save(config);
    }
}
