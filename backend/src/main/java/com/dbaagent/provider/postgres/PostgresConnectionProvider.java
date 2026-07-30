package com.dbaagent.provider.postgres;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.api.ConnectionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

/**
 * PostgreSQL implementation of ConnectionProvider.
 */
@Slf4j
@Component
public class PostgresConnectionProvider implements ConnectionProvider {

    private static final String DATABASE_TYPE = "postgres";
    private static final int DEFAULT_PORT = 5432;
    private static final String DRIVER_CLASS = "org.postgresql.Driver";

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public String buildJdbcUrl(ConnectionRequest request, Integer tunnelPort) {
        String host;
        int port;

        if (tunnelPort != null) {
            host = "localhost";
            port = tunnelPort;
        } else {
            host = request.getHost();
            port = request.getPort() != null ? request.getPort() : DEFAULT_PORT;
        }

        // When using SSH tunnel, SSL is handled by the tunnel
        // Use getEffectiveSsl() to support both legacy ssl field and new sslMode field
        boolean useSsl = tunnelPort == null && Boolean.TRUE.equals(request.getEffectiveSsl());
        // Use sslmode=require for Azure PostgreSQL compatibility (not just ssl=true)
        String sslParam = useSsl ? "?sslmode=require" : "?sslmode=disable";

        return String.format(
            "jdbc:postgresql://%s:%d/%s%s",
            host, port, request.getDatabase(), sslParam
        );
    }

    @Override
    public int getDefaultPort() {
        return DEFAULT_PORT;
    }

    @Override
    public String getDriverClassName() {
        return DRIVER_CLASS;
    }

    @Override
    public Map<String, Object> getConnectionProperties(Integer tunnelPort, Boolean useSsl) {
        Map<String, Object> properties = new HashMap<>();
        boolean ssl = tunnelPort == null && Boolean.TRUE.equals(useSsl);
        properties.put("ssl", ssl);
        return properties;
    }

    @Override
    public Map<String, Object> getConnectionPropertiesWithSsl(
            Integer tunnelPort,
            String sslMode,
            String caCertPem,
            String clientCertPem,
            String clientKeyPem,
            String keyPassphrase) {

        Map<String, Object> properties = new HashMap<>();

        // SSH tunnel provides encryption, skip SSL
        if (tunnelPort != null) {
            properties.put("ssl", false);
            properties.put("sslmode", "disable");
            return properties;
        }

        // No SSL requested
        if (sslMode == null || "none".equals(sslMode)) {
            properties.put("ssl", false);
            properties.put("sslmode", "disable");
            return properties;
        }

        // SSL enabled (server-only or server-client)
        properties.put("ssl", true);

        // server-only without CA cert: sslmode=require (encrypt only, no cert verification)
        // server-only with CA cert:    sslmode=verify-ca (encrypt + verify server cert)
        // server-client:               sslmode=verify-full (encrypt + verify + mTLS)
        String pgSslMode;
        if ("server-client".equals(sslMode)) {
            pgSslMode = "verify-full";
        } else if (caCertPem != null && !caCertPem.isBlank()) {
            pgSslMode = "verify-ca";
        } else {
            pgSslMode = "require";
        }
        properties.put("sslmode", pgSslMode);

        // CA certificate (optional for server-only, enables verify-ca when provided)
        if (caCertPem != null && !caCertPem.isBlank()) {
            try {
                Path caCertFile = writeTempCertFile(caCertPem, "pg-ca-cert");
                properties.put("sslrootcert", caCertFile.toString());
            } catch (IOException e) {
                log.error("Failed to write CA certificate to temp file", e);
                throw new RuntimeException("Failed to configure SSL CA certificate", e);
            }
        }

        // Client certificate and key (only for server-client/mTLS)
        if ("server-client".equals(sslMode)) {
            if (clientCertPem != null && !clientCertPem.isBlank()) {
                try {
                    Path clientCertFile = writeTempCertFile(clientCertPem, "pg-client-cert");
                    properties.put("sslcert", clientCertFile.toString());
                } catch (IOException e) {
                    log.error("Failed to write client certificate to temp file", e);
                    throw new RuntimeException("Failed to configure SSL client certificate", e);
                }
            }

            if (clientKeyPem != null && !clientKeyPem.isBlank()) {
                try {
                    Path clientKeyFile = writeTempKeyFile(clientKeyPem, "pg-client-key");
                    properties.put("sslkey", clientKeyFile.toString());
                } catch (IOException e) {
                    log.error("Failed to write client key to temp file", e);
                    throw new RuntimeException("Failed to configure SSL client key", e);
                }
            }
        }

        return properties;
    }

    /**
     * Write PEM certificate content to a temporary file.
     * File is marked for deletion on JVM exit.
     */
    private Path writeTempCertFile(String pemContent, String prefix) throws IOException {
        Path tempFile = Files.createTempFile(prefix, ".pem");
        Files.writeString(tempFile, pemContent);
        tempFile.toFile().deleteOnExit();
        log.debug("Created temp cert file: {}", tempFile);
        return tempFile;
    }

    /**
     * Write PEM key content to a temporary file.
     * For PostgreSQL, the key must be in PKCS#8 DER format, but we write PEM
     * and let the JDBC driver handle conversion.
     * File is marked for deletion on JVM exit.
     */
    private Path writeTempKeyFile(String pemContent, String prefix) throws IOException {
        Path tempFile = Files.createTempFile(prefix, ".key");
        Files.writeString(tempFile, pemContent);
        tempFile.toFile().deleteOnExit();
        log.debug("Created temp key file: {}", tempFile);
        return tempFile;
    }
}
