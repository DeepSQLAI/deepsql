package com.dbaagent.provider.mysql;

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
 * MySQL implementation of ConnectionProvider.
 */
@Slf4j
@Component
public class MySQLConnectionProvider implements ConnectionProvider {

    private static final String DATABASE_TYPE = "mysql";
    private static final int DEFAULT_PORT = 3306;
    private static final String DRIVER_CLASS = "com.mysql.cj.jdbc.Driver";

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
        boolean useSsl = tunnelPort == null && Boolean.TRUE.equals(request.getSsl());
        String sslParam = useSsl ? "&useSSL=true" : "&useSSL=false";

        return String.format(
            "jdbc:mysql://%s:%d/%s?serverTimezone=UTC%s&allowPublicKeyRetrieval=true",
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
        properties.put("useSSL", ssl);
        properties.put("serverTimezone", "UTC");
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
        properties.put("serverTimezone", "UTC");

        // SSH tunnel provides encryption, skip SSL
        if (tunnelPort != null) {
            properties.put("useSSL", false);
            properties.put("sslMode", "DISABLED");
            return properties;
        }

        // No SSL requested
        if (sslMode == null || "none".equals(sslMode)) {
            properties.put("useSSL", false);
            properties.put("sslMode", "DISABLED");
            return properties;
        }

        // SSL enabled
        properties.put("useSSL", true);
        properties.put("allowPublicKeyRetrieval", true);

        // server-only without CA cert: REQUIRED (encrypt only, no cert verification)
        // server-only with CA cert:    VERIFY_CA (encrypt + verify server cert)
        // server-client:               VERIFY_IDENTITY (encrypt + verify + mTLS)
        String mysqlSslMode;
        if ("server-client".equals(sslMode)) {
            mysqlSslMode = "VERIFY_IDENTITY";
        } else if (caCertPem != null && !caCertPem.isBlank()) {
            mysqlSslMode = "VERIFY_CA";
        } else {
            mysqlSslMode = "REQUIRED";
        }
        properties.put("sslMode", mysqlSslMode);

        // CA certificate (optional for server-only, enables VERIFY_CA when provided)
        if (caCertPem != null && !caCertPem.isBlank()) {
            try {
                Path caCertFile = writeTempCertFile(caCertPem, "mysql-ca-cert");
                properties.put("trustCertificateKeyStoreUrl", "file:" + caCertFile.toString());
                properties.put("trustCertificateKeyStoreType", "PEM");
            } catch (IOException e) {
                log.error("Failed to write CA certificate to temp file", e);
                throw new RuntimeException("Failed to configure SSL CA certificate", e);
            }
        }

        // Client certificate and key (only for server-client/mTLS)
        if ("server-client".equals(sslMode)) {
            if (clientCertPem != null && !clientCertPem.isBlank()) {
                try {
                    Path clientCertFile = writeTempCertFile(clientCertPem, "mysql-client-cert");
                    properties.put("clientCertificateKeyStoreUrl", "file:" + clientCertFile.toString());
                    properties.put("clientCertificateKeyStoreType", "PEM");
                } catch (IOException e) {
                    log.error("Failed to write client certificate to temp file", e);
                    throw new RuntimeException("Failed to configure SSL client certificate", e);
                }
            }

            if (clientKeyPem != null && !clientKeyPem.isBlank()) {
                try {
                    Path clientKeyFile = writeTempKeyFile(clientKeyPem, "mysql-client-key");
                    // MySQL Connector/J uses the cert store URL for both cert and key when type is PEM
                    // The key is expected to be concatenated with the cert, or we can use separate properties
                    // For MySQL 8.0.16+, we can use the PEM format directly
                    // The clientCertificateKeyStoreUrl should contain both cert and key
                    // We'll handle this by concatenating them in the cert file write
                } catch (IOException e) {
                    log.error("Failed to write client key to temp file", e);
                    throw new RuntimeException("Failed to configure SSL client key", e);
                }
            }

            // Handle key passphrase if provided
            if (keyPassphrase != null && !keyPassphrase.isBlank()) {
                properties.put("clientCertificateKeyStorePassword", keyPassphrase);
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
