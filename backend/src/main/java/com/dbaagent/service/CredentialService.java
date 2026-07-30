package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.service.security.ConnectionAccessService;
import com.dbaagent.service.telemetry.TelemetryClient;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class CredentialService {
    private static final String AAD_PREFIX = "dba-agent:connection:";
    private final CredentialRepository credentialRepository;
    private final EncryptionService encryptionService;
    private final ConnectionAccessService connectionAccessService;
    private final TelemetryClient telemetryClient;

    @Transactional
    public DatabaseConnection saveConnection(ConnectionRequest request, String ownerUsername) {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId(UUID.randomUUID().toString());
        connection.setConnectionName(request.getConnectionName());
        connection.setDbType(request.getDbType());
        connection.setOwnerUsername(ownerUsername);
        connection.setCreatedAt(LocalDateTime.now());
        connection.setLastUsed(LocalDateTime.now());

        // Encrypt all sensitive fields
        connection.setEncryptedHost(encryptionService.encrypt(
            request.getHost(),
            aad(connection.getId(), "host")
        ));
        connection.setEncryptedPort(encryptionService.encrypt(
            request.getPort() != null ? request.getPort().toString() : null,
            aad(connection.getId(), "port")
        ));
        connection.setEncryptedDatabase(encryptionService.encrypt(
            request.getDatabase(),
            aad(connection.getId(), "database")
        ));
        connection.setEncryptedUsername(encryptionService.encrypt(
            request.getUsername(),
            aad(connection.getId(), "username")
        ));
        connection.setEncryptedPassword(encryptionService.encrypt(
            request.getPassword(),
            aad(connection.getId(), "password")
        ));

        // Save SSL configuration - use effective mode (new sslMode takes precedence over legacy ssl)
        String effectiveSslMode = request.getEffectiveSslMode();
        connection.setEncryptedSslMode(encryptionService.encrypt(
            effectiveSslMode,
            aad(connection.getId(), "sslMode")
        ));

        // Sync legacy ssl field from sslMode for backward compatibility
        String sslConfig = !"none".equals(effectiveSslMode) ? "true" : "false";
        connection.setEncryptedSslConfig(encryptionService.encrypt(
            sslConfig,
            aad(connection.getId(), "sslConfig")
        ));

        // Save SSL certificates only if SSL is enabled
        if (!"none".equals(effectiveSslMode)) {
            if (request.getSslCaCertificate() != null && !request.getSslCaCertificate().isBlank()) {
                connection.setEncryptedSslCaCertificate(encryptionService.encrypt(
                    request.getSslCaCertificate(),
                    aad(connection.getId(), "sslCaCertificate")
                ));
            }

            // Client cert and key only for mTLS (server-client mode)
            if ("server-client".equals(effectiveSslMode)) {
                if (request.getSslClientCertificate() != null && !request.getSslClientCertificate().isBlank()) {
                    connection.setEncryptedSslClientCertificate(encryptionService.encrypt(
                        request.getSslClientCertificate(),
                        aad(connection.getId(), "sslClientCertificate")
                    ));
                }
                if (request.getSslClientKey() != null && !request.getSslClientKey().isBlank()) {
                    connection.setEncryptedSslClientKey(encryptionService.encrypt(
                        request.getSslClientKey(),
                        aad(connection.getId(), "sslClientKey")
                    ));
                }
                if (request.getSslClientKeyPassphrase() != null && !request.getSslClientKeyPassphrase().isBlank()) {
                    connection.setEncryptedSslClientKeyPassphrase(encryptionService.encrypt(
                        request.getSslClientKeyPassphrase(),
                        aad(connection.getId(), "sslClientKeyPassphrase")
                    ));
                }
            }
        }

        // Save SSH tunnel configuration
        connection.setSshEnabled(request.getSshEnabled() != null ? request.getSshEnabled() : false);
        connection.setSshAuthType(request.getSshAuthType() != null ? request.getSshAuthType() : "PASSWORD");

        if (Boolean.TRUE.equals(request.getSshEnabled())) {
            connection.setEncryptedSshHost(encryptionService.encrypt(
                request.getSshHost(),
                aad(connection.getId(), "sshHost")
            ));
            connection.setEncryptedSshPort(encryptionService.encrypt(
                request.getSshPort() != null ? request.getSshPort().toString() : "22",
                aad(connection.getId(), "sshPort")
            ));
            connection.setEncryptedSshUsername(encryptionService.encrypt(
                request.getSshUsername(),
                aad(connection.getId(), "sshUsername")
            ));

            if ("PASSWORD".equalsIgnoreCase(request.getSshAuthType())) {
                connection.setEncryptedSshPassword(encryptionService.encrypt(
                    request.getSshPassword(),
                    aad(connection.getId(), "sshPassword")
                ));
            } else if ("PRIVATE_KEY".equalsIgnoreCase(request.getSshAuthType())) {
                connection.setEncryptedSshPrivateKey(encryptionService.encrypt(
                    request.getSshPrivateKey(),
                    aad(connection.getId(), "sshPrivateKey")
                ));
                if (request.getSshPassphrase() != null && !request.getSshPassphrase().isEmpty()) {
                    connection.setEncryptedSshPassphrase(encryptionService.encrypt(
                        request.getSshPassphrase(),
                        aad(connection.getId(), "sshPassphrase")
                    ));
                }
            }
        }

        // Save cloud provider context (plain text, not sensitive)
        connection.setCloudProvider(normalizeBlankToNull(request.getCloudProvider()));
        connection.setManagedService(normalizeBlankToNull(request.getManagedService()));

        // Save instance sizing context (plain text, not sensitive)
        connection.setInstanceClass(normalizeBlankToNull(request.getInstanceClass()));
        connection.setInstanceVcpus(normalizeNonPositiveToNull(request.getInstanceVcpus()));
        connection.setInstanceMemoryGb(normalizeNonPositiveToNull(request.getInstanceMemoryGb()));
        connection.setStorageType(normalizeBlankToNull(request.getStorageType()));
        connection.setStorageMaxIops(normalizeNonPositiveToNull(request.getStorageMaxIops()));

        // Data sampling opt-in (defaults true for AI business context generation)
        connection.setEnableDataSampling(
            request.getEnableDataSampling() != null ? request.getEnableDataSampling() : true);

        DatabaseConnection saved = credentialRepository.save(connection);

        Map<String, Object> props = new HashMap<>();
        props.put("db_dialect",  normalizeDialect(request.getDbType()));
        props.put("ssh_enabled", Boolean.TRUE.equals(request.getSshEnabled()));
        if (request.getCloudProvider() != null && !request.getCloudProvider().isBlank()) {
            props.put("cloud_provider", request.getCloudProvider().trim().toLowerCase());
        }
        emitAfterCommit("connection.created", props);

        return saved;
    }

    @Transactional(readOnly = true)
    public ConnectionRequest getDecryptedConnection(String connectionId) {
        DatabaseConnection connection = credentialRepository.findById(connectionId)
            .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));

        // Decrypt all fields
        ConnectionRequest request = new ConnectionRequest();
        request.setId(connectionId);  // Set the connection ID
        request.setConnectionName(connection.getConnectionName());
        request.setDbType(connection.getDbType());
        request.setHost(encryptionService.decrypt(
            connection.getEncryptedHost(),
            aad(connection.getId(), "host")
        ));
        
        String portStr = encryptionService.decrypt(
            connection.getEncryptedPort(),
            aad(connection.getId(), "port")
        );
        request.setPort(portStr != null ? Integer.parseInt(portStr) : null);
        
        request.setDatabase(encryptionService.decrypt(
            connection.getEncryptedDatabase(),
            aad(connection.getId(), "database")
        ));
        request.setUsername(encryptionService.decrypt(
            connection.getEncryptedUsername(),
            aad(connection.getId(), "username")
        ));
        request.setPassword(encryptionService.decrypt(
            connection.getEncryptedPassword(),
            aad(connection.getId(), "password")
        ));
        
        String sslStr = encryptionService.decrypt(
            connection.getEncryptedSslConfig(),
            aad(connection.getId(), "sslConfig")
        );
        request.setSsl(sslStr != null ? Boolean.parseBoolean(sslStr) : false);

        // Decrypt SSL mode (new granular mode)
        if (connection.getEncryptedSslMode() != null) {
            String sslMode = encryptionService.decrypt(
                connection.getEncryptedSslMode(),
                aad(connection.getId(), "sslMode")
            );
            request.setSslMode(sslMode);
        }

        // Decrypt SSL certificates
        if (connection.getEncryptedSslCaCertificate() != null) {
            request.setSslCaCertificate(encryptionService.decrypt(
                connection.getEncryptedSslCaCertificate(),
                aad(connection.getId(), "sslCaCertificate")
            ));
        }
        if (connection.getEncryptedSslClientCertificate() != null) {
            request.setSslClientCertificate(encryptionService.decrypt(
                connection.getEncryptedSslClientCertificate(),
                aad(connection.getId(), "sslClientCertificate")
            ));
        }
        if (connection.getEncryptedSslClientKey() != null) {
            request.setSslClientKey(encryptionService.decrypt(
                connection.getEncryptedSslClientKey(),
                aad(connection.getId(), "sslClientKey")
            ));
        }
        if (connection.getEncryptedSslClientKeyPassphrase() != null) {
            request.setSslClientKeyPassphrase(encryptionService.decrypt(
                connection.getEncryptedSslClientKeyPassphrase(),
                aad(connection.getId(), "sslClientKeyPassphrase")
            ));
        }

        // Decrypt SSH tunnel configuration
        request.setSshEnabled(connection.getSshEnabled() != null ? connection.getSshEnabled() : false);
        request.setSshAuthType(connection.getSshAuthType() != null ? connection.getSshAuthType() : "PASSWORD");

        if (Boolean.TRUE.equals(connection.getSshEnabled())) {
            request.setSshHost(encryptionService.decrypt(
                connection.getEncryptedSshHost(),
                aad(connection.getId(), "sshHost")
            ));

            String sshPortStr = encryptionService.decrypt(
                connection.getEncryptedSshPort(),
                aad(connection.getId(), "sshPort")
            );
            request.setSshPort(sshPortStr != null ? Integer.parseInt(sshPortStr) : 22);

            request.setSshUsername(encryptionService.decrypt(
                connection.getEncryptedSshUsername(),
                aad(connection.getId(), "sshUsername")
            ));

            if ("PASSWORD".equalsIgnoreCase(connection.getSshAuthType())) {
                request.setSshPassword(encryptionService.decrypt(
                    connection.getEncryptedSshPassword(),
                    aad(connection.getId(), "sshPassword")
                ));
            } else if ("PRIVATE_KEY".equalsIgnoreCase(connection.getSshAuthType())) {
                request.setSshPrivateKey(encryptionService.decrypt(
                    connection.getEncryptedSshPrivateKey(),
                    aad(connection.getId(), "sshPrivateKey")
                ));
                if (connection.getEncryptedSshPassphrase() != null) {
                    request.setSshPassphrase(encryptionService.decrypt(
                        connection.getEncryptedSshPassphrase(),
                        aad(connection.getId(), "sshPassphrase")
                    ));
                }
            }
        }

        // Copy cloud provider context
        request.setCloudProvider(connection.getCloudProvider());
        request.setManagedService(connection.getManagedService());

        // Copy instance sizing context
        request.setInstanceClass(connection.getInstanceClass());
        request.setInstanceVcpus(connection.getInstanceVcpus());
        request.setInstanceMemoryGb(connection.getInstanceMemoryGb());
        request.setStorageType(connection.getStorageType());
        request.setStorageMaxIops(connection.getStorageMaxIops());

        // Data sampling opt-in
        request.setEnableDataSampling(
            connection.getEnableDataSampling() != null ? connection.getEnableDataSampling() : true);

        return request;
    }

    @Transactional(readOnly = true)
    public List<DatabaseConnection> getAllConnections() {
        return credentialRepository.findAllByOrderByCreatedAtDesc();
    }

    @Transactional(readOnly = true)
    public List<DatabaseConnection> getConnectionsForUser(String username, boolean isAdmin) {
        return connectionAccessService.getVisibleConnections(username, isAdmin);
    }

    @Transactional(readOnly = true)
    public DatabaseConnection getConnectionEntity(String connectionId) {
        return credentialRepository.findById(connectionId)
            .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));
    }

    private List<DatabaseConnection> normalizeOwners(List<DatabaseConnection> connections) {
        boolean needsUpdate = connections.stream().anyMatch(conn -> conn.getOwnerUsername() == null);
        if (!needsUpdate) {
            return connections;
        }
        connections.forEach(conn -> {
            if (conn.getOwnerUsername() == null) {
                conn.setOwnerUsername("admin");
            }
        });
        return credentialRepository.saveAll(connections);
    }

    @Transactional
    public void deleteConnection(String connectionId) {
        DatabaseConnection existing = credentialRepository.findById(connectionId).orElse(null);
        credentialRepository.deleteById(connectionId);
        if (existing != null) {
            emitAfterCommit("connection.deleted", Map.of(
                "db_dialect", normalizeDialect(existing.getDbType())
            ));
        }
    }

    /**
     * Schedules a telemetry capture for after the current transaction commits.
     * Prevents phantom events if the surrounding transaction rolls back.
     * If we're not in a transaction (test, non-managed call), emit immediately.
     */
    private void emitAfterCommit(String event, Map<String, Object> props) {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override public void afterCommit() {
                    telemetryClient.capture(event, props);
                }
            });
        } else {
            telemetryClient.capture(event, props);
        }
    }

    private static String normalizeDialect(String raw) {
        if (raw == null) return "unknown";
        String lower = raw.trim().toLowerCase();
        return switch (lower) {
            case "postgres", "postgresql" -> "postgres";
            case "mysql" -> "mysql";
            default -> "unknown";
        };
    }

    @Transactional
    public DatabaseConnection updateConnection(String connectionId, ConnectionRequest request) {
        DatabaseConnection connection = credentialRepository.findById(connectionId)
            .orElseThrow(() -> new RuntimeException("Connection not found: " + connectionId));

        // Update non-encrypted fields
        connection.setConnectionName(request.getConnectionName());
        connection.setDbType(request.getDbType());
        connection.setLastUsed(LocalDateTime.now());

        // Update and re-encrypt sensitive fields
        connection.setEncryptedHost(encryptionService.encrypt(
            request.getHost(),
            aad(connection.getId(), "host")
        ));
        connection.setEncryptedPort(encryptionService.encrypt(
            request.getPort() != null ? request.getPort().toString() : null,
            aad(connection.getId(), "port")
        ));
        connection.setEncryptedDatabase(encryptionService.encrypt(
            request.getDatabase(),
            aad(connection.getId(), "database")
        ));
        connection.setEncryptedUsername(encryptionService.encrypt(
            request.getUsername(),
            aad(connection.getId(), "username")
        ));

        // Only update password if provided (not null or empty)
        if (request.getPassword() != null && !request.getPassword().trim().isEmpty()) {
            connection.setEncryptedPassword(encryptionService.encrypt(
                request.getPassword(),
                aad(connection.getId(), "password")
            ));
        }

        // Update SSL configuration - use effective mode
        String effectiveSslMode = request.getEffectiveSslMode();
        connection.setEncryptedSslMode(encryptionService.encrypt(
            effectiveSslMode,
            aad(connection.getId(), "sslMode")
        ));

        // Sync legacy ssl field from sslMode for backward compatibility
        String sslConfig = !"none".equals(effectiveSslMode) ? "true" : "false";
        connection.setEncryptedSslConfig(encryptionService.encrypt(
            sslConfig,
            aad(connection.getId(), "sslConfig")
        ));

        // Update SSL certificates - only if new value provided (edit mode allows keeping existing)
        if ("none".equals(effectiveSslMode)) {
            // Clear all SSL certificates when SSL is disabled
            connection.setEncryptedSslCaCertificate(null);
            connection.setEncryptedSslClientCertificate(null);
            connection.setEncryptedSslClientKey(null);
            connection.setEncryptedSslClientKeyPassphrase(null);
        } else {
            // Update CA cert if provided
            if (request.getSslCaCertificate() != null && !request.getSslCaCertificate().isBlank()) {
                connection.setEncryptedSslCaCertificate(encryptionService.encrypt(
                    request.getSslCaCertificate(),
                    aad(connection.getId(), "sslCaCertificate")
                ));
            }

            // Update client certs only for mTLS mode
            if ("server-client".equals(effectiveSslMode)) {
                if (request.getSslClientCertificate() != null && !request.getSslClientCertificate().isBlank()) {
                    connection.setEncryptedSslClientCertificate(encryptionService.encrypt(
                        request.getSslClientCertificate(),
                        aad(connection.getId(), "sslClientCertificate")
                    ));
                }
                if (request.getSslClientKey() != null && !request.getSslClientKey().isBlank()) {
                    connection.setEncryptedSslClientKey(encryptionService.encrypt(
                        request.getSslClientKey(),
                        aad(connection.getId(), "sslClientKey")
                    ));
                }
                if (request.getSslClientKeyPassphrase() != null && !request.getSslClientKeyPassphrase().isBlank()) {
                    connection.setEncryptedSslClientKeyPassphrase(encryptionService.encrypt(
                        request.getSslClientKeyPassphrase(),
                        aad(connection.getId(), "sslClientKeyPassphrase")
                    ));
                }
            } else {
                // Clear client certs when switching from mTLS to server-only
                connection.setEncryptedSslClientCertificate(null);
                connection.setEncryptedSslClientKey(null);
                connection.setEncryptedSslClientKeyPassphrase(null);
            }
        }

        // Update SSH tunnel configuration
        connection.setSshEnabled(request.getSshEnabled() != null ? request.getSshEnabled() : false);
        connection.setSshAuthType(request.getSshAuthType() != null ? request.getSshAuthType() : "PASSWORD");

        if (Boolean.TRUE.equals(request.getSshEnabled())) {
            connection.setEncryptedSshHost(encryptionService.encrypt(
                request.getSshHost(),
                aad(connection.getId(), "sshHost")
            ));
            connection.setEncryptedSshPort(encryptionService.encrypt(
                request.getSshPort() != null ? request.getSshPort().toString() : "22",
                aad(connection.getId(), "sshPort")
            ));
            connection.setEncryptedSshUsername(encryptionService.encrypt(
                request.getSshUsername(),
                aad(connection.getId(), "sshUsername")
            ));

            if ("PASSWORD".equalsIgnoreCase(request.getSshAuthType())) {
                // Only update SSH password if provided
                if (request.getSshPassword() != null && !request.getSshPassword().trim().isEmpty()) {
                    connection.setEncryptedSshPassword(encryptionService.encrypt(
                        request.getSshPassword(),
                        aad(connection.getId(), "sshPassword")
                    ));
                }
                // Clear private key fields when using password auth
                connection.setEncryptedSshPrivateKey(null);
                connection.setEncryptedSshPassphrase(null);
            } else if ("PRIVATE_KEY".equalsIgnoreCase(request.getSshAuthType())) {
                // Only update private key if provided
                if (request.getSshPrivateKey() != null && !request.getSshPrivateKey().trim().isEmpty()) {
                    connection.setEncryptedSshPrivateKey(encryptionService.encrypt(
                        request.getSshPrivateKey(),
                        aad(connection.getId(), "sshPrivateKey")
                    ));
                }
                if (request.getSshPassphrase() != null && !request.getSshPassphrase().trim().isEmpty()) {
                    connection.setEncryptedSshPassphrase(encryptionService.encrypt(
                        request.getSshPassphrase(),
                        aad(connection.getId(), "sshPassphrase")
                    ));
                }
                // Clear password field when using key auth
                connection.setEncryptedSshPassword(null);
            }
        } else {
            // Clear all SSH fields when SSH is disabled
            connection.setEncryptedSshHost(null);
            connection.setEncryptedSshPort(null);
            connection.setEncryptedSshUsername(null);
            connection.setEncryptedSshPassword(null);
            connection.setEncryptedSshPrivateKey(null);
            connection.setEncryptedSshPassphrase(null);
        }

        // Update cloud provider context (only if provided; prevents accidental clearing on partial updates)
        if (request.getCloudProvider() != null) {
            connection.setCloudProvider(normalizeBlankToNull(request.getCloudProvider()));
        }
        if (request.getManagedService() != null) {
            connection.setManagedService(normalizeBlankToNull(request.getManagedService()));
        }

        // Update instance sizing context (only if provided; prevents accidental clearing on partial updates)
        if (request.getInstanceClass() != null) {
            connection.setInstanceClass(normalizeBlankToNull(request.getInstanceClass()));
        }
        if (request.getInstanceVcpus() != null) {
            connection.setInstanceVcpus(normalizeNonPositiveToNull(request.getInstanceVcpus()));
        }
        if (request.getInstanceMemoryGb() != null) {
            connection.setInstanceMemoryGb(normalizeNonPositiveToNull(request.getInstanceMemoryGb()));
        }
        if (request.getStorageType() != null) {
            connection.setStorageType(normalizeBlankToNull(request.getStorageType()));
        }
        if (request.getStorageMaxIops() != null) {
            connection.setStorageMaxIops(normalizeNonPositiveToNull(request.getStorageMaxIops()));
        }

        // Data sampling — only update if explicitly provided, preserve existing DB value otherwise
        if (request.getEnableDataSampling() != null) {
            connection.setEnableDataSampling(request.getEnableDataSampling());
        }

        return credentialRepository.save(connection);
    }

    @Transactional(readOnly = true)
    public boolean connectionExists(String connectionId) {
        return credentialRepository.existsById(connectionId);
    }

    private String aad(String connectionId, String field) {
        return AAD_PREFIX + connectionId + ":" + field;
    }

    private String normalizeBlankToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private Integer normalizeNonPositiveToNull(Integer value) {
        if (value == null) {
            return null;
        }
        return value > 0 ? value : null;
    }

    private Double normalizeNonPositiveToNull(Double value) {
        if (value == null) {
            return null;
        }
        return value > 0 ? value : null;
    }
}
