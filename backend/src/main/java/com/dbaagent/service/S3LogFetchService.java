package com.dbaagent.service;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.AwsSessionCredentials;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.regions.providers.DefaultAwsRegionProviderChain;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Response;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.s3.model.S3Exception;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Comparator;
import java.util.Optional;

@Service
@Slf4j
public class S3LogFetchService {
    public static class AwsCredentialsInput {
        private final String accessKeyId;
        private final String secretAccessKey;
        private final String sessionToken;

        public AwsCredentialsInput(String accessKeyId, String secretAccessKey, String sessionToken) {
            this.accessKeyId = accessKeyId;
            this.secretAccessKey = secretAccessKey;
            this.sessionToken = sessionToken;
        }

        public String getAccessKeyId() {
            return accessKeyId;
        }

        public String getSecretAccessKey() {
            return secretAccessKey;
        }

        public String getSessionToken() {
            return sessionToken;
        }
    }

    public InputStream downloadLog(String s3Url, String regionOverride, AwsCredentialsInput credentialsInput) {
        if (isPresignedUrl(s3Url)) {
            return downloadPresignedLog(s3Url);
        }

        S3Location location = parseLocation(s3Url, regionOverride);
        Region region = resolveRegion(location.getRegion(), regionOverride);
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider(credentialsInput);

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(location.getBucket())
            .key(location.getKey())
            .build();

        S3Client client = null;
        try {
            client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

            var response = client.getObject(request);
            Long contentLength = response.response().contentLength();
            log.info("Streaming slow query log from s3://{}/{}{}",
                location.getBucket(),
                location.getKey(),
                contentLength != null ? " (" + contentLength + " bytes)" : "");

            S3Client clientToClose = client;
            client = null;
            return new java.io.FilterInputStream(response) {
                @Override
                public void close() throws java.io.IOException {
                    try {
                        super.close();
                    } finally {
                        clientToClose.close();
                    }
                }
            };
        } catch (S3Exception e) {
            log.error("Failed to download S3 object: {}", e.awsErrorDetails().errorMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to download S3 object", e);
            throw new RuntimeException("Failed to download S3 object", e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public InputStream downloadLog(String s3Url, String regionOverride) {
        if (isPresignedUrl(s3Url)) {
            return downloadPresignedLog(s3Url);
        }

        S3Location location = parseLocation(s3Url, regionOverride);
        Region region = resolveRegion(location.getRegion(), regionOverride);
        AwsCredentialsProvider credentialsProvider = DefaultCredentialsProvider.create();

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(location.getBucket())
            .key(location.getKey())
            .build();

        S3Client client = null;
        try {
            client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

            var response = client.getObject(request);
            Long contentLength = response.response().contentLength();
            log.info("Streaming slow query log from s3://{}/{}{}",
                location.getBucket(),
                location.getKey(),
                contentLength != null ? " (" + contentLength + " bytes)" : "");

            S3Client clientToClose = client;
            client = null;
            return new java.io.FilterInputStream(response) {
                @Override
                public void close() throws java.io.IOException {
                    try {
                        super.close();
                    } finally {
                        clientToClose.close();
                    }
                }
            };
        } catch (S3Exception e) {
            log.error("Failed to download S3 object: {}", e.awsErrorDetails().errorMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to download S3 object", e);
            throw new RuntimeException("Failed to download S3 object", e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    private AwsCredentialsProvider resolveCredentialsProvider(AwsCredentialsInput credentialsInput) {
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

    private static final int MAX_PRESIGNED_REDIRECTS = 5;

    /**
     * Validates and rebuilds a presigned fetch URL: https only, to a public
     * address. Returns a URI reconstructed from checked components rather than
     * the input, so no unvalidated part of the caller's string survives into
     * the request (java/ssrf). The initial URL and every redirect hop pass here.
     */
    private URI assertFetchableUrl(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(
                "Presigned log URL must use https, got: " + scheme);
        }
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Presigned log URL has no host");
        }
        java.net.InetAddress blocked =
            OutboundHostGuard.findBlockedAddress(OutboundHostGuard.normalize(host));
        if (blocked != null) {
            log.warn("Blocked presigned log fetch to restricted host {} (resolved to {})",
                host, blocked.getHostAddress());
            throw new IllegalArgumentException(
                "Presigned log URL resolves to a restricted address (" + blocked.getHostAddress() + ")");
        }
        try {
            // Rebuild from validated pieces; scheme is pinned to the https literal.
            return new URI("https", uri.getRawUserInfo(), host, uri.getPort(),
                uri.getRawPath(), uri.getRawQuery(), uri.getRawFragment())
                .parseServerAuthority();
        } catch (java.net.URISyntaxException e) {
            throw new IllegalArgumentException("Malformed presigned log URL");
        }
    }

    boolean isPresignedUrl(String s3Url) {
        if (s3Url == null || s3Url.isBlank()) {
            return false;
        }
        String upper = s3Url.toUpperCase();
        return upper.contains("X-AMZ-SIGNATURE=") ||
            upper.contains("X-AMZ-ALGORITHM=") ||
            upper.contains("X-AMZ-CREDENTIAL=") ||
            upper.contains("X-AMZ-SECURITY-TOKEN=");
    }

    private InputStream downloadPresignedLog(String presignedUrl) {
        try {
            // Redirects are followed manually: with setInstanceFollowRedirects(true)
            // the JDK chases a 302 without giving us a chance to screen the target,
            // so a presigned URL on a public host could hand off to 169.254.169.254
            // or an internal address (java/ssrf).
            URI current = assertFetchableUrl(URI.create(presignedUrl));
            HttpURLConnection connection = null;
            int status;
            for (int redirects = 0; ; redirects++) {
                if (redirects > MAX_PRESIGNED_REDIRECTS) {
                    throw new RuntimeException("Too many redirects fetching presigned URL");
                }
                connection = (HttpURLConnection) current.toURL().openConnection();
                connection.setRequestMethod("GET");
                connection.setConnectTimeout(15_000);
                connection.setReadTimeout(60_000);
                connection.setInstanceFollowRedirects(false);

                status = connection.getResponseCode();
                if (status != HttpURLConnection.HTTP_MOVED_PERM
                        && status != HttpURLConnection.HTTP_MOVED_TEMP
                        && status != HttpURLConnection.HTTP_SEE_OTHER
                        && status != 307 && status != 308) {
                    break;
                }
                String location = connection.getHeaderField("Location");
                connection.disconnect();
                if (location == null || location.isBlank()) {
                    throw new RuntimeException("Redirect without a Location header");
                }
                current = assertFetchableUrl(current.resolve(location));
            }

            if (status < 200 || status >= 300) {
                throw new RuntimeException("Failed to download presigned URL, status " + status);
            }

            log.info("Streaming slow query log from presigned URL");
            final HttpURLConnection finalConnection = connection;
            InputStream inputStream = finalConnection.getInputStream();
            return new java.io.FilterInputStream(inputStream) {
                @Override
                public void close() throws java.io.IOException {
                    try {
                        super.close();
                    } finally {
                        finalConnection.disconnect();
                    }
                }
            };
        } catch (Exception e) {
            log.error("Failed to download presigned URL", e);
            throw new RuntimeException("Failed to download presigned URL", e);
        }
    }

    S3Location parseLocation(String s3Url, String regionOverride) {
        if (s3Url == null || s3Url.isBlank()) {
            throw new IllegalArgumentException("S3 URL must be provided");
        }

        if (s3Url.startsWith("s3://")) {
            URI uri = URI.create(s3Url);
            String bucket = uri.getHost();
            String key = decodePath(stripLeadingSlash(uri.getPath()));
            if (bucket == null || bucket.isBlank() || key.isBlank()) {
                throw new IllegalArgumentException("Invalid S3 URL, expected s3://bucket/key");
            }
            return new S3Location(bucket, key, regionOverride);
        }

        URI uri = URI.create(s3Url);
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("Invalid S3 URL, missing host");
        }

        String region = extractRegion(host);
        String path = decodePath(stripLeadingSlash(uri.getPath()));

        if (host.startsWith("s3.")) {
            String[] parts = path.split("/", 2);
            if (parts.length < 2) {
                throw new IllegalArgumentException("Invalid S3 URL, expected /bucket/key path");
            }
            return new S3Location(parts[0], parts[1], region);
        }

        int bucketEnd = host.indexOf(".s3");
        if (bucketEnd > 0) {
            String bucket = host.substring(0, bucketEnd);
            return new S3Location(bucket, path, region);
        }

        throw new IllegalArgumentException("Unsupported S3 URL format");
    }

    private Region resolveRegion(String urlRegion, String override) {
        String resolved = override != null && !override.isBlank()
            ? override
            : (urlRegion != null && !urlRegion.isBlank() ? urlRegion : null);

        if (resolved != null) {
            return Region.of(resolved);
        }

        try {
            return new DefaultAwsRegionProviderChain().getRegion();
        } catch (Exception e) {
            throw new IllegalArgumentException(
                "AWS region not provided and not found in default region provider chain", e);
        }
    }

    private String extractRegion(String host) {
        if (host == null) {
            return null;
        }
        if (host.startsWith("s3.")) {
            String suffix = host.substring(3);
            int end = suffix.indexOf(".amazonaws.com");
            if (end > 0) {
                return suffix.substring(0, end);
            }
            return null;
        }

        int marker = host.indexOf(".s3.");
        if (marker >= 0) {
            String suffix = host.substring(marker + 4);
            int end = suffix.indexOf(".amazonaws.com");
            if (end > 0) {
                return suffix.substring(0, end);
            }
        }

        return null;
    }

    private String stripLeadingSlash(String value) {
        if (value == null) {
            return "";
        }
        return value.startsWith("/") ? value.substring(1) : value;
    }

    private String decodePath(String path) {
        if (path == null) {
            return "";
        }
        return URLDecoder.decode(path, StandardCharsets.UTF_8);
    }

    public InputStream downloadObject(String bucket, String key, String regionOverride, AwsCredentialsInput credentialsInput) {
        if (bucket == null || bucket.isBlank() || key == null || key.isBlank()) {
            throw new IllegalArgumentException("Bucket and key must be provided");
        }
        Region region = resolveRegion(null, regionOverride);
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider(credentialsInput);

        GetObjectRequest request = GetObjectRequest.builder()
            .bucket(bucket)
            .key(key)
            .build();

        S3Client client = null;
        try {
            client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentialsProvider)
                .build();

            var response = client.getObject(request);
            Long contentLength = response.response().contentLength();
            log.info("Streaming slow query log from s3://{}/{}{}",
                bucket,
                key,
                contentLength != null ? " (" + contentLength + " bytes)" : "");

            S3Client clientToClose = client;
            client = null;
            return new java.io.FilterInputStream(response) {
                @Override
                public void close() throws java.io.IOException {
                    try {
                        super.close();
                    } finally {
                        clientToClose.close();
                    }
                }
            };
        } catch (S3Exception e) {
            log.error("Failed to download S3 object: {}", e.awsErrorDetails().errorMessage());
            throw e;
        } catch (Exception e) {
            log.error("Failed to download S3 object", e);
            throw new RuntimeException("Failed to download S3 object", e);
        } finally {
            if (client != null) {
                client.close();
            }
        }
    }

    public Optional<S3Object> findLatestObject(String bucket,
                                               String prefix,
                                               String regionOverride,
                                               AwsCredentialsInput credentialsInput,
                                               Instant newerThan) {
        if (bucket == null || bucket.isBlank()) {
            throw new IllegalArgumentException("Bucket must be provided");
        }
        AwsCredentialsProvider credentialsProvider = resolveCredentialsProvider(credentialsInput);
        Region region = resolveRegion(null, regionOverride);

        ListObjectsV2Request request = ListObjectsV2Request.builder()
            .bucket(bucket)
            .prefix(prefix != null ? prefix : "")
            .build();

        try (S3Client client = S3Client.builder()
            .region(region)
            .credentialsProvider(credentialsProvider)
            .build()) {

            ListObjectsV2Response response = client.listObjectsV2(request);
            if (response.contents() == null || response.contents().isEmpty()) {
                return Optional.empty();
            }

            return response.contents().stream()
                .filter(obj -> newerThan == null || (obj.lastModified() != null && obj.lastModified().isAfter(newerThan)))
                .max(Comparator.comparing(S3Object::lastModified));
        }
    }

    @Data
    @AllArgsConstructor
    static class S3Location {
        private String bucket;
        private String key;
        private String region;
    }
}
