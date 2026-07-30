package com.dbaagent.service;

import com.azure.identity.ClientSecretCredential;
import com.azure.identity.ClientSecretCredentialBuilder;
import com.azure.storage.blob.BlobClient;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobItem;
import com.azure.storage.blob.models.ListBlobsOptions;
import com.dbaagent.util.CappedOutputStream;
import com.dbaagent.util.LogTempFileUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.Optional;

/**
 * Service for fetching slow query logs from Azure Blob Storage.
 * Supports authentication via:
 * - Connection string (recommended for simplicity)
 * - Service Principal (client ID + client secret + tenant ID)
 * - SAS token
 */
@Service
@Slf4j
public class AzureBlobLogFetchService {

    @Value("${slow-query.log.max-bytes:524288000}")
    private long maxLogBytes = 524288000L;

    @Value("${slow-query.log.temp-dir:}")
    private String tempDir;

    @Value("${slow-query.log.min-free-disk-mb:1024}")
    private long minFreeDiskMb = 1024L;

    /**
     * Credentials for Azure Blob Storage authentication
     */
    @Data
    public static class AzureCredentialsInput {
        private String connectionString;      // Full connection string (easiest)
        private String accountName;           // Storage account name
        private String accountKey;            // Storage account key
        private String sasToken;              // SAS token for limited access
        private String clientId;              // Service principal client ID
        private String clientSecret;          // Service principal secret
        private String tenantId;              // Azure AD tenant ID
    }

    /**
     * Download the latest log file from Azure Blob Storage
     */
    public InputStream downloadLatestLog(
            String containerName,
            String blobPrefix,
            AzureCredentialsInput credentials,
            Instant newerThan
    ) {
        BlobServiceClient serviceClient = buildServiceClient(credentials);
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);

        if (!containerClient.exists()) {
            throw new IllegalArgumentException("Container does not exist: " + containerName);
        }

        // Find the latest blob matching the prefix
        ListBlobsOptions options = new ListBlobsOptions().setPrefix(blobPrefix);

        Optional<BlobItem> latestBlob = containerClient.listBlobs(options, null)
                .stream()
                .filter(blob -> {
                    if (newerThan == null) return true;
                    OffsetDateTime lastModified = blob.getProperties().getLastModified();
                    return lastModified != null && lastModified.toInstant().isAfter(newerThan);
                })
                .max(Comparator.comparing(blob ->
                    blob.getProperties().getLastModified() != null
                        ? blob.getProperties().getLastModified()
                        : OffsetDateTime.MIN));

        if (latestBlob.isEmpty()) {
            log.warn("No blobs found in container {} with prefix {}", containerName, blobPrefix);
            return new ByteArrayInputStream(new byte[0]);
        }

        String blobName = latestBlob.get().getName();
        log.info("Downloading blob: {} from container: {}", blobName, containerName);

        return downloadBlob(containerClient, blobName);
    }

    /**
     * Download a specific blob by name
     */
    public InputStream downloadBlob(String containerName, String blobName, AzureCredentialsInput credentials) {
        BlobServiceClient serviceClient = buildServiceClient(credentials);
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);
        return downloadBlob(containerClient, blobName);
    }

    /**
     * Download logs from multiple blobs within a time range
     */
    public InputStream downloadLogsInRange(
            String containerName,
            String blobPrefix,
            AzureCredentialsInput credentials,
            Instant startTime,
            Instant endTime,
            int maxBlobs
    ) {
        BlobServiceClient serviceClient = buildServiceClient(credentials);
        BlobContainerClient containerClient = serviceClient.getBlobContainerClient(containerName);

        if (!containerClient.exists()) {
            throw new IllegalArgumentException("Container does not exist: " + containerName);
        }

        ListBlobsOptions options = new ListBlobsOptions().setPrefix(blobPrefix);

        OffsetDateTime startOdt = startTime != null ? startTime.atOffset(ZoneOffset.UTC) : null;
        OffsetDateTime endOdt = endTime != null ? endTime.atOffset(ZoneOffset.UTC) : OffsetDateTime.now(ZoneOffset.UTC);

        var matchingBlobs = containerClient.listBlobs(options, null)
                .stream()
                .filter(blob -> {
                    OffsetDateTime lastModified = blob.getProperties().getLastModified();
                    if (lastModified == null) return false;
                    boolean afterStart = startOdt == null || lastModified.isAfter(startOdt);
                    boolean beforeEnd = lastModified.isBefore(endOdt) || lastModified.isEqual(endOdt);
                    return afterStart && beforeEnd;
                })
                .sorted(Comparator.comparing(blob -> blob.getProperties().getLastModified()))
                .limit(maxBlobs)
                .toList();

        if (matchingBlobs.isEmpty()) {
            log.warn("No blobs found in range for container {} with prefix {}", containerName, blobPrefix);
            return new ByteArrayInputStream(new byte[0]);
        }

        log.info("Found {} blobs to download from container {}", matchingBlobs.size(), containerName);

        java.nio.file.Path tempFile;
        try {
            tempFile = LogTempFileUtils.createTempFile(tempDir, minFreeDiskMb, "azure-logs-", ".log");
        } catch (IOException e) {
            throw new RuntimeException("Failed to create temp file for Azure logs", e);
        }

        long bytesWritten = 0;
        try (OutputStream rawOut = java.nio.file.Files.newOutputStream(tempFile);
             OutputStream out = maxLogBytes > 0 ? new CappedOutputStream(rawOut, maxLogBytes) : rawOut) {
            for (BlobItem blob : matchingBlobs) {
                try {
                    BlobClient blobClient = containerClient.getBlobClient(blob.getName());
                    try (InputStream blobStream = blobClient.openInputStream()) {
                        bytesWritten += blobStream.transferTo(out);
                        out.write('\n');
                    }
                    log.debug("Downloaded blob: {}", blob.getName());
                } catch (Exception e) {
                    log.warn("Failed to download blob {}: {}", blob.getName(), e.getMessage());
                }
            }
        } catch (Exception e) {
            try {
                java.nio.file.Files.deleteIfExists(tempFile);
            } catch (IOException ignored) {
                // Best-effort cleanup
            }
            if (e instanceof com.dbaagent.exception.LogSizeExceededException) {
                throw (com.dbaagent.exception.LogSizeExceededException) e;
            }
            throw e instanceof RuntimeException ? (RuntimeException) e : new RuntimeException(e);
        }

        log.info("Combined log content size: {} bytes from {} blobs", bytesWritten, matchingBlobs.size());
        try {
            return LogTempFileUtils.openDeleteOnClose(tempFile);
        } catch (IOException e) {
            throw new RuntimeException("Failed to open temp file for Azure logs", e);
        }
    }

    private InputStream downloadBlob(BlobContainerClient containerClient, String blobName) {
        BlobClient blobClient = containerClient.getBlobClient(blobName);

        if (!blobClient.exists()) {
            throw new IllegalArgumentException("Blob does not exist: " + blobName);
        }

        Long size = blobClient.getProperties().getBlobSize();
        log.info("Streaming blob {} ({} bytes)", blobName, size);
        return blobClient.openInputStream();
    }

    private BlobServiceClient buildServiceClient(AzureCredentialsInput credentials) {
        if (credentials == null) {
            throw new IllegalArgumentException("Azure credentials must be provided");
        }

        // Priority 1: Connection string (simplest)
        if (credentials.getConnectionString() != null && !credentials.getConnectionString().isBlank()) {
            log.debug("Using connection string authentication");
            return new BlobServiceClientBuilder()
                    .connectionString(credentials.getConnectionString())
                    .buildClient();
        }

        // Priority 2: Account name + key
        if (credentials.getAccountName() != null && credentials.getAccountKey() != null) {
            log.debug("Using account key authentication");
            String endpoint = String.format("https://%s.blob.core.windows.net", credentials.getAccountName());
            return new BlobServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(new com.azure.storage.common.StorageSharedKeyCredential(
                            credentials.getAccountName(),
                            credentials.getAccountKey()))
                    .buildClient();
        }

        // Priority 3: SAS token
        if (credentials.getAccountName() != null && credentials.getSasToken() != null) {
            log.debug("Using SAS token authentication");
            String endpoint = String.format("https://%s.blob.core.windows.net", credentials.getAccountName());
            String sasToken = credentials.getSasToken();
            if (!sasToken.startsWith("?")) {
                sasToken = "?" + sasToken;
            }
            return new BlobServiceClientBuilder()
                    .endpoint(endpoint + sasToken)
                    .buildClient();
        }

        // Priority 4: Service Principal
        if (credentials.getClientId() != null &&
            credentials.getClientSecret() != null &&
            credentials.getTenantId() != null &&
            credentials.getAccountName() != null) {
            log.debug("Using service principal authentication");
            ClientSecretCredential credential = new ClientSecretCredentialBuilder()
                    .clientId(credentials.getClientId())
                    .clientSecret(credentials.getClientSecret())
                    .tenantId(credentials.getTenantId())
                    .build();

            String endpoint = String.format("https://%s.blob.core.windows.net", credentials.getAccountName());
            return new BlobServiceClientBuilder()
                    .endpoint(endpoint)
                    .credential(credential)
                    .buildClient();
        }

        throw new IllegalArgumentException(
            "Invalid Azure credentials. Provide one of: " +
            "connection string, account name + key, account name + SAS token, " +
            "or service principal credentials (client ID + secret + tenant ID + account name)"
        );
    }
}
