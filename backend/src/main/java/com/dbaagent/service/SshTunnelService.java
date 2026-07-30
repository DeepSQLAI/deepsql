package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Service for managing SSH tunnels for database connections.
 * Allows connecting to databases behind firewalls/VPCs via SSH bastion hosts.
 */
@Service
@Slf4j
public class SshTunnelService {

    private final DatabaseProviderRegistry providerRegistry;

    private static final int SSH_CONNECT_TIMEOUT = 30000; // 30 seconds
    private static final String LOCAL_BIND_HOST = "127.0.0.1";

    private final Map<String, SshTunnelInfo> activeTunnels = new ConcurrentHashMap<>();
    /** Fixed-size striped locks to serialize tunnel creation per connectionId without unbounded growth. */
    private static final int LOCK_STRIPES = 64;
    private final Object[] tunnelLocks = new Object[LOCK_STRIPES];
    {
        for (int i = 0; i < LOCK_STRIPES; i++) tunnelLocks[i] = new Object();
    }

    public SshTunnelService(DatabaseProviderRegistry providerRegistry) {
        this.providerRegistry = providerRegistry;
    }

    /**
     * Establishes an SSH tunnel for the given connection request.
     * Returns the local port to connect to.
     *
     * @param connectionId Unique identifier for the connection
     * @param request Connection request containing SSH and database details
     * @return Local port number to use for database connection
     * @throws RuntimeException if tunnel creation fails
     */
    public int establishTunnel(String connectionId, ConnectionRequest request) {
        if (!Boolean.TRUE.equals(request.getSshEnabled())) {
            throw new IllegalArgumentException("SSH is not enabled for this connection");
        }

        // Fast path: reuse existing connected tunnel without locking
        SshTunnelInfo existingTunnel = activeTunnels.get(connectionId);
        if (existingTunnel != null && existingTunnel.session.isConnected()) {
            return existingTunnel.localPort;
        }

        // Synchronize on a striped lock to prevent duplicate tunnel creation
        Object lock = tunnelLocks[Math.floorMod(connectionId.hashCode(), LOCK_STRIPES)];
        synchronized (lock) {
            // Re-check after acquiring lock (another thread may have created it)
            existingTunnel = activeTunnels.get(connectionId);
            if (existingTunnel != null && existingTunnel.session.isConnected()) {
                return existingTunnel.localPort;
            }

            // Close any stale tunnel
            if (existingTunnel != null) {
                closeTunnel(connectionId);
            }

            try {
                Session session = createSession(request);
                session.connect(SSH_CONNECT_TIMEOUT);

                String remoteHost = request.getHost();
                int remotePort = request.getPort() != null ? request.getPort() : getDefaultPort(request.getDbType());
                int localPort = createLocalPortForward(session, remoteHost, remotePort);

                log.info("SSH tunnel established for connection {}: {}:{} -> {}:{}",
                        connectionId, LOCAL_BIND_HOST, localPort, remoteHost, remotePort);

                activeTunnels.put(connectionId, new SshTunnelInfo(session, localPort));
                return localPort;

            } catch (JSchException e) {
                log.error("Failed to establish SSH tunnel for connection {}: {}", connectionId, e.getMessage());
                throw new RuntimeException("Failed to establish SSH tunnel: " + e.getMessage(), e);
            }
        }
    }

    int createLocalPortForward(Session session, String remoteHost, int remotePort) throws JSchException {
        return session.setPortForwardingL(LOCAL_BIND_HOST, 0, remoteHost, remotePort);
    }

    /**
     * Creates and configures an SSH session based on the connection request.
     */
    private Session createSession(ConnectionRequest request) throws JSchException {
        // Create a new JSch instance per session to avoid shared state issues
        JSch jsch = new JSch();

        String sshHost = request.getSshHost();
        int sshPort = request.getSshPort() != null ? request.getSshPort() : 22;
        String sshUsername = request.getSshUsername();

        log.info("Creating SSH session to {}@{}:{}", sshUsername, sshHost, sshPort);

        // Configure authentication before creating session
        if ("PRIVATE_KEY".equalsIgnoreCase(request.getSshAuthType())) {
            configureKeyAuth(jsch, request);
        }

        Session session = jsch.getSession(sshUsername, sshHost, sshPort);

        // Configure password auth after session creation
        if (!"PRIVATE_KEY".equalsIgnoreCase(request.getSshAuthType())) {
            configurePasswordAuth(session, request);
        }

        // Disable strict host key checking (consider making this configurable)
        java.util.Properties config = new java.util.Properties();
        config.put("StrictHostKeyChecking", "no");
        config.put("PreferredAuthentications",
                "PRIVATE_KEY".equalsIgnoreCase(request.getSshAuthType())
                        ? "publickey"
                        : "password,keyboard-interactive");
        session.setConfig(config);

        // Configure keep-alive to prevent tunnel timeout
        // Send keep-alive packet every 30 seconds to keep connection alive
        session.setServerAliveInterval(30000);
        // Retry up to 3 times before considering connection dead
        session.setServerAliveCountMax(3);

        return session;
    }

    /**
     * Configures password-based authentication.
     */
    private void configurePasswordAuth(Session session, ConnectionRequest request) {
        if (request.getSshPassword() == null || request.getSshPassword().isEmpty()) {
            throw new IllegalArgumentException("SSH password is required for password authentication");
        }
        session.setPassword(request.getSshPassword());
    }

    /**
     * Configures private key authentication.
     */
    private void configureKeyAuth(JSch jsch, ConnectionRequest request) throws JSchException {
        String privateKey = request.getSshPrivateKey();
        String passphrase = request.getSshPassphrase();

        if (privateKey == null || privateKey.isEmpty()) {
            throw new IllegalArgumentException("SSH private key is required for key authentication");
        }

        log.debug("Configuring SSH key authentication, key length: {} chars", privateKey.length());

        byte[] privateKeyBytes = privateKey.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] passphraseBytes = passphrase != null && !passphrase.isEmpty()
                ? passphrase.getBytes(java.nio.charset.StandardCharsets.UTF_8)
                : null;

        String keyName = "ssh-key-" + System.currentTimeMillis();
        jsch.addIdentity(
                keyName,
                privateKeyBytes,
                null, // public key (optional, JSch can derive it)
                passphraseBytes
        );
        log.debug("Added SSH identity: {}", keyName);
    }

    /**
     * Gets the default database port based on database type.
     * Delegates to the appropriate database provider.
     */
    private int getDefaultPort(String dbType) {
        try {
            return providerRegistry.getDialect(dbType).connection().getDefaultPort();
        } catch (Exception e) {
            log.warn("Unknown database type '{}', defaulting to port 5432", dbType);
            return 5432;
        }
    }

    /**
     * Closes an SSH tunnel for the given connection ID.
     */
    public void closeTunnel(String connectionId) {
        SshTunnelInfo tunnel = activeTunnels.remove(connectionId);
        // Do NOT remove from tunnelLocks here — another thread may hold the lock
        // in establishTunnel(). Removing would let a third thread create a new lock
        // object and race. Lock objects are tiny (~40 bytes) and bounded by connectionId count.
        if (tunnel != null) {
            try {
                if (tunnel.session.isConnected()) {
                    tunnel.session.disconnect();
                }
                log.info("SSH tunnel closed for connection: {}", connectionId);
            } catch (Exception e) {
                log.warn("Error closing SSH tunnel for connection {}: {}", connectionId, e.getMessage());
            }
        }
    }

    /**
     * Checks if an SSH tunnel is active for the given connection.
     */
    public boolean isTunnelActive(String connectionId) {
        SshTunnelInfo tunnel = activeTunnels.get(connectionId);
        return tunnel != null && tunnel.session.isConnected();
    }

    /**
     * Gets the local port for an active tunnel.
     */
    public Integer getLocalPort(String connectionId) {
        SshTunnelInfo tunnel = activeTunnels.get(connectionId);
        return tunnel != null && tunnel.session.isConnected() ? tunnel.localPort : null;
    }

    /**
     * Tests SSH connection without establishing full tunnel.
     */
    public boolean testSshConnection(ConnectionRequest request) {
        if (!Boolean.TRUE.equals(request.getSshEnabled())) {
            return true; // SSH not required
        }

        Session session = null;
        try {
            log.info("Testing SSH connection to {}@{}:{}",
                    request.getSshUsername(), request.getSshHost(), request.getSshPort());
            session = createSession(request);
            session.connect(SSH_CONNECT_TIMEOUT);
            log.info("SSH connection test successful to {}:{}", request.getSshHost(), request.getSshPort());
            return true;
        } catch (JSchException e) {
            log.error("SSH connection test failed to {}:{} - {}: {}",
                    request.getSshHost(), request.getSshPort(),
                    e.getClass().getSimpleName(), e.getMessage());
            if (e.getCause() != null) {
                log.error("Caused by: {}", e.getCause().getMessage());
            }
            return false;
        } catch (Exception e) {
            log.error("Unexpected error during SSH connection test: {}", e.getMessage(), e);
            return false;
        } finally {
            if (session != null && session.isConnected()) {
                session.disconnect();
            }
        }
    }

    /**
     * Closes all active SSH tunnels.
     */
    @PreDestroy
    public void closeAllTunnels() {
        log.info("Closing all SSH tunnels ({} active)", activeTunnels.size());
        activeTunnels.keySet().forEach(this::closeTunnel);
        activeTunnels.clear();
    }

    /**
     * Gets the count of active tunnels.
     */
    public int getActiveTunnelCount() {
        return (int) activeTunnels.values().stream()
                .filter(t -> t.session.isConnected())
                .count();
    }

    /**
     * Internal class to hold tunnel information.
     */
    private record SshTunnelInfo(Session session, int localPort) {}
}
