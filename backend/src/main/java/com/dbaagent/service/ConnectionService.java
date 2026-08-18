package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ConnectionTestResult;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.ConnectionProvider;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.PrivilegeCheckProvider;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
@Slf4j
@RequiredArgsConstructor
public class ConnectionService {
    private final Map<String, HikariDataSource> connectionPools = new ConcurrentHashMap<>();
    /**
     * Per-connectionId mutex for SSH tunnel + Hikari pool recreation.
     * Without this, when an SSH tunnel dies under a multi-threaded workload
     * (brain init's SCHEMA_CLASSIFICATION runs ~10 parallel virtual-thread
     * sub-classifications), every thread sees its {@code getConnection()}
     * fail with the same SQLException, every thread observes the tunnel is
     * dead, and every thread independently calls
     * {@code closeConnectionPool() + getDataSource()} — each tearing down
     * what the previous one just rebuilt. The thrash burns CPU, prevents
     * any sub-task from completing, and is the proximate cause of the
     * "stuck at 60% SCHEMA_CLASSIFICATION" loop we've been chasing for
     * aws-rds-master / aws-rds-replica.
     */
    private final Map<String, Object> tunnelRecreationLocks = new ConcurrentHashMap<>();
    private final SshTunnelService sshTunnelService;
    private final CredentialService credentialService;
    private final DatabaseProviderRegistry providerRegistry;
    private final DatabaseHostGuard databaseHostGuard;

    public boolean testConnection(ConnectionRequest request) {
        Connection connection = null;
        String testConnectionId = "test-" + System.currentTimeMillis();
        Integer tunnelPort = null;

        try {
            // If SSH is enabled, first test SSH connection, then establish tunnel
            if (Boolean.TRUE.equals(request.getSshEnabled())) {
                log.info("Testing SSH connection to {}:{}", request.getSshHost(), request.getSshPort());
                if (!sshTunnelService.testSshConnection(request)) {
                    log.error("SSH connection test failed");
                    return false;
                }
                // Establish tunnel for database test
                tunnelPort = sshTunnelService.establishTunnel(testConnectionId, request);
                log.info("SSH tunnel established on local port {}", tunnelPort);
            }

            String jdbcUrl = buildJdbcUrl(request, tunnelPort);
            connection = DriverManager.getConnection(
                jdbcUrl,
                request.getUsername(),
                request.getPassword()
            );

            // Test with a simple query
            connection.createStatement().executeQuery("SELECT 1");
            return true;
        } catch (SQLException e) {
            log.error("Connection test failed: {}", e.getMessage());
            return false;
        } catch (Exception e) {
            log.error("Connection test failed: {}", e.getMessage());
            return false;
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.error("Error closing test connection", e);
                }
            }
            // Clean up test tunnel
            if (tunnelPort != null) {
                sshTunnelService.closeTunnel(testConnectionId);
            }
        }
    }

    /**
     * Test connection with detailed privilege checks.
     * Returns comprehensive results including which privileges are missing.
     */
    public ConnectionTestResult testConnectionWithPrivileges(ConnectionRequest request) {
        Connection connection = null;
        String testConnectionId = "test-" + System.currentTimeMillis();
        Integer tunnelPort = null;
        List<ConnectionTestResult.PrivilegeCheck> privilegeChecks = new ArrayList<>();
        boolean sshSuccess = true;
        boolean connectionSuccess = false;
        String errorMessage = null;

        try {
            // If SSH is enabled, first test SSH connection, then establish tunnel
            if (Boolean.TRUE.equals(request.getSshEnabled())) {
                log.info("Testing SSH connection to {}:{}", request.getSshHost(), request.getSshPort());
                if (!sshTunnelService.testSshConnection(request)) {
                    log.error("SSH connection test failed");
                    return ConnectionTestResult.builder()
                        .success(false)
                        .sshTunnelSuccessful(false)
                        .connectionSuccessful(false)
                        .errorMessage("SSH tunnel connection failed. Check SSH credentials and host.")
                        .privilegeChecks(privilegeChecks)
                        .build();
                }
                sshSuccess = true;
                // Establish tunnel for database test
                tunnelPort = sshTunnelService.establishTunnel(testConnectionId, request);
                log.info("SSH tunnel established on local port {}", tunnelPort);
            }

            String jdbcUrl = buildJdbcUrl(request, tunnelPort);
            connection = DriverManager.getConnection(
                jdbcUrl,
                request.getUsername(),
                request.getPassword()
            );

            // Test basic connectivity
            connection.createStatement().executeQuery("SELECT 1");
            connectionSuccess = true;

            // Now check privileges based on database type
            String dbType = providerRegistry.getCanonicalName(request.getDbType());
            DatabaseDialect dialect = providerRegistry.getDialect(dbType);
            PrivilegeCheckProvider privilegeProvider = dialect.privileges();
            if (privilegeProvider == null) {
                throw new IllegalArgumentException("PrivilegeCheckProvider not available for: " + dbType);
            }
            privilegeProvider.checkPrivileges(connection, request.getDatabase(), privilegeChecks);

            boolean allGranted = privilegeChecks.stream().allMatch(ConnectionTestResult.PrivilegeCheck::isGranted);

            return ConnectionTestResult.builder()
                .success(connectionSuccess && allGranted)
                .sshTunnelSuccessful(sshSuccess)
                .connectionSuccessful(connectionSuccess)
                .privilegeChecks(privilegeChecks)
                .build();

        } catch (SQLException e) {
            log.error("Connection test failed: {}", e.getMessage());
            errorMessage = "Database connection failed: " + e.getMessage();
            return ConnectionTestResult.builder()
                .success(false)
                .sshTunnelSuccessful(sshSuccess)
                .connectionSuccessful(false)
                .errorMessage(errorMessage)
                .privilegeChecks(privilegeChecks)
                .build();
        } catch (Exception e) {
            log.error("Connection test failed: {}", e.getMessage());
            errorMessage = "Connection test failed: " + e.getMessage();
            return ConnectionTestResult.builder()
                .success(false)
                .sshTunnelSuccessful(sshSuccess)
                .connectionSuccessful(false)
                .errorMessage(errorMessage)
                .privilegeChecks(privilegeChecks)
                .build();
        } finally {
            if (connection != null) {
                try {
                    connection.close();
                } catch (SQLException e) {
                    log.error("Error closing test connection", e);
                }
            }
            // Clean up test tunnel
            if (tunnelPort != null) {
                sshTunnelService.closeTunnel(testConnectionId);
            }
        }
    }


    public DataSource getDataSource(String connectionId, ConnectionRequest request) {
        // Check if SSH tunnel is required but no longer active
        if (Boolean.TRUE.equals(request.getSshEnabled())) {
            if (!sshTunnelService.isTunnelActive(connectionId)) {
                // Tunnel is dead, close the pool and let it be recreated
                log.warn("SSH tunnel for connection {} is no longer active, recreating connection pool", connectionId);
                closeConnectionPool(connectionId);
            }
        }
        return connectionPools.computeIfAbsent(connectionId, id -> createConnectionPool(connectionId, request));
    }

    public Connection getConnection(String connectionId, ConnectionRequest request) throws SQLException {
        DataSource dataSource = getDataSource(connectionId, request);
        try {
            return dataSource.getConnection();
        } catch (SQLException e) {
            // If connection fails and SSH is enabled, it might be a tunnel issue.
            // SERIALIZE recreation per connectionId: under a multi-threaded
            // workload (brain init's parallel sub-classifications) every
            // thread hits the same broken pool simultaneously; without the
            // lock they all close + rebuild it in parallel and stomp on each
            // other, producing the rapid-fire "SSH tunnel ... is no longer
            // active, recreating connection pool" log storm we observed.
            if (Boolean.TRUE.equals(request.getSshEnabled())) {
                log.warn("Connection failed for SSH-tunneled connection {}, checking tunnel status", connectionId);
                Object lock = tunnelRecreationLocks.computeIfAbsent(
                    connectionId, k -> new Object());
                synchronized (lock) {
                    // Re-check inside the lock — another thread may have already
                    // rebuilt the pool while we were waiting. If so, just use it.
                    try {
                        return getDataSource(connectionId, request).getConnection();
                    } catch (SQLException retryAfterPotentialPeerRebuild) {
                        if (!sshTunnelService.isTunnelActive(connectionId)) {
                            log.info("SSH tunnel is dead, recreating pool and tunnel for connection {}", connectionId);
                            closeConnectionPool(connectionId);
                            return getDataSource(connectionId, request).getConnection();
                        }
                        throw retryAfterPotentialPeerRebuild;
                    }
                }
            }
            throw e;
        }
    }

    /**
     * Get a JdbcTemplate for the given connection, properly handling SSH tunneling.
     * This is the recommended way for services to execute queries.
     *
     * @param connectionId The connection ID
     * @param request The connection request with credentials
     * @return A JdbcTemplate configured to use the tunneled connection
     */
    public JdbcTemplate getJdbcTemplate(String connectionId, ConnectionRequest request) {
        DataSource dataSource = getDataSource(connectionId, request);
        return new JdbcTemplate(dataSource);
    }

    private HikariDataSource createConnectionPool(String connectionId, ConnectionRequest request) {
        Integer tunnelPort = null;

        // Establish SSH tunnel if enabled
        if (Boolean.TRUE.equals(request.getSshEnabled())) {
            log.info("Establishing SSH tunnel for connection: {}", connectionId);
            tunnelPort = sshTunnelService.establishTunnel(connectionId, request);
            log.info("SSH tunnel established on local port {} for connection: {}", tunnelPort, connectionId);
        }

        if (tunnelPort == null) {
            databaseHostGuard.assertAllowed(request.getHost());
        }

        DatabaseDialect dialect = providerRegistry.getDialect(request.getDbType());
        ConnectionProvider connectionProvider = dialect.connection();

        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(connectionProvider.buildJdbcUrl(request, tunnelPort));
        config.setUsername(request.getUsername());
        config.setPassword(request.getPassword());
        config.setDriverClassName(connectionProvider.getDriverClassName());
        config.setPoolName("HikariPool-" + connectionId);

        // Pool configuration
        config.setMaximumPoolSize(10);
        config.setMinimumIdle(5);
        config.setConnectionTimeout(30000);
        config.setIdleTimeout(600000);
        // Cap the lifetime well under any reasonable server-side idle/wait
        // timeout so HikariCP evicts a connection BEFORE the DB server closes
        // it from under us. AWS RDS MySQL's default wait_timeout is 28800s
        // (8h) but operators commonly lower it; sticking to 15 min stays
        // comfortably under aggressive timeouts.
        config.setMaxLifetime(900_000);  // 15 min (was 30 min)
        // Send a ping on each idle connection every 5 min so the server
        // doesn't decide it's idle and close it. Hikari's JDBC4 isValid()
        // check already validates connections at checkout, but checkout-time
        // validation only catches connections that were already dead — it
        // doesn't PREVENT them from going dead while sitting in the pool.
        // This was the cause of the "Connection is closed" errors we hit in
        // TemporalClassificationService after the brain init's 48-retry loop
        // left stale connections in the pool.
        config.setKeepaliveTime(300_000);  // 5 min
        config.setLeakDetectionThreshold(60000);

        // Connection properties from provider with full SSL config
        Map<String, Object> connectionProperties = connectionProvider.getConnectionPropertiesWithSsl(
            tunnelPort,
            request.getEffectiveSslMode(),
            request.getSslCaCertificate(),
            request.getSslClientCertificate(),
            request.getSslClientKey(),
            request.getSslClientKeyPassphrase()
        );
        for (Map.Entry<String, Object> entry : connectionProperties.entrySet()) {
            config.addDataSourceProperty(entry.getKey(), entry.getValue());
        }

        return new HikariDataSource(config);
    }

    /**
     * Builds JDBC URL for the connection.
     * Delegates to the appropriate database provider.
     */
    private String buildJdbcUrl(ConnectionRequest request, Integer tunnelPort) {
        // Guard here rather than at each caller: this is the single point every
        // JDBC URL is built through (java/ssrf on ConnectionService). A tunnelled
        // connection targets the local forwarded port, so only the direct case
        // carries a user-supplied host worth screening.
        if (tunnelPort == null) {
            databaseHostGuard.assertAllowed(request.getHost());
        }
        DatabaseDialect dialect = providerRegistry.getDialect(request.getDbType());
        return dialect.connection().buildJdbcUrl(request, tunnelPort);
    }

    /**
     * Get default port for database type.
     * Delegates to the appropriate database provider.
     */
    private int getDefaultPort(String dbType) {
        DatabaseDialect dialect = providerRegistry.getDialect(dbType);
        return dialect.connection().getDefaultPort();
    }

    /**
     * Get JDBC driver class name for database type.
     * Delegates to the appropriate database provider.
     */
    private String getDriverClassName(String dbType) {
        DatabaseDialect dialect = providerRegistry.getDialect(dbType);
        return dialect.connection().getDriverClassName();
    }

    /**
     * Get a JdbcTemplate for background jobs that don't have HttpServletRequest.
     * Uses CredentialService to get decrypted connection details.
     *
     * @param connectionId The connection ID
     * @return A JdbcTemplate configured for the connection
     */
    public JdbcTemplate getJdbcTemplateForBackgroundJob(String connectionId) {
        ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
        DataSource dataSource = getDataSource(connectionId, request);
        return new JdbcTemplate(dataSource);
    }

    /**
     * Get the database type for a connection.
     *
     * @param connectionId The connection ID
     * @return The database type (e.g., "postgres", "mysql")
     */
    public String getDbType(String connectionId) {
        ConnectionRequest request = credentialService.getDecryptedConnection(connectionId);
        return request.getDbType();
    }

    /**
     * Check if data sampling is enabled for a connection.
     * Defaults to true if the field is null (pre-migration connections).
     * Fails closed (returns false) when the lookup itself fails.
     */
    public boolean isDataSamplingEnabled(String connectionId) {
        try {
            var conn = credentialService.getConnectionEntity(connectionId);
            return conn.getEnableDataSampling() != null ? conn.getEnableDataSampling() : true;
        } catch (RuntimeException e) {
            log.warn("Failed to check data sampling setting for {}, defaulting to disabled: {}",
                connectionId, e.getMessage());
            return false;
        }
    }

    public void closeConnectionPool(String connectionId) {
        HikariDataSource dataSource = connectionPools.remove(connectionId);
        if (dataSource != null && !dataSource.isClosed()) {
            dataSource.close();
        }
        // Also close SSH tunnel if one exists
        sshTunnelService.closeTunnel(connectionId);
    }

    public void closeAllConnectionPools() {
        connectionPools.forEach((id, dataSource) -> {
            if (!dataSource.isClosed()) {
                dataSource.close();
            }
        });
        connectionPools.clear();
        // Close all SSH tunnels
        sshTunnelService.closeAllTunnels();
    }
}



