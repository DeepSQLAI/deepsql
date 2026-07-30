package com.dbaagent.provider.api;

import com.dbaagent.model.ConnectionRequest;

/**
 * Provider interface for database connection operations.
 * Handles JDBC URL building, driver resolution, and connection properties.
 */
public interface ConnectionProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Build a JDBC URL for the given connection request.
     * @param request The connection request with host, port, database, etc.
     * @param tunnelPort Optional SSH tunnel port (null if no tunnel)
     * @return The complete JDBC URL
     */
    String buildJdbcUrl(ConnectionRequest request, Integer tunnelPort);

    /**
     * Get the default port for this database type.
     * @return The default port number
     */
    int getDefaultPort();

    /**
     * Get the JDBC driver class name for this database.
     * @return The fully qualified driver class name
     */
    String getDriverClassName();

    /**
     * Apply database-specific connection properties to the config.
     * @param tunnelPort The tunnel port (null if not using SSH tunnel)
     * @param useSsl Whether SSL should be used
     * @return A map of connection properties
     */
    java.util.Map<String, Object> getConnectionProperties(Integer tunnelPort, Boolean useSsl);

    /**
     * Apply database-specific connection properties with full SSL configuration.
     * This method supports certificate-based SSL authentication (mTLS).
     *
     * @param tunnelPort The tunnel port (null if not using SSH tunnel)
     * @param sslMode SSL mode: "none", "server-only", or "server-client"
     * @param caCertPem CA certificate in PEM format (null if no SSL)
     * @param clientCertPem Client certificate in PEM format (null if not mTLS)
     * @param clientKeyPem Client private key in PEM format (null if not mTLS)
     * @param keyPassphrase Passphrase for encrypted client key (may be null)
     * @return A map of connection properties
     */
    default java.util.Map<String, Object> getConnectionPropertiesWithSsl(
            Integer tunnelPort,
            String sslMode,
            String caCertPem,
            String clientCertPem,
            String clientKeyPem,
            String keyPassphrase) {
        // Default implementation: delegate to simple method for backward compatibility
        boolean useSsl = sslMode != null && !"none".equals(sslMode);
        return getConnectionProperties(tunnelPort, useSsl);
    }
}
