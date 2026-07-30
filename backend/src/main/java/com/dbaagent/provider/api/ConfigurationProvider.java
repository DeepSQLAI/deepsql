package com.dbaagent.provider.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Provider interface for database configuration analysis.
 * Handles retrieval and analysis of database configuration parameters.
 */
public interface ConfigurationProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Get all configuration parameters.
     * @param connection The database connection
     * @return Map of parameter names to their values
     * @throws SQLException If a database error occurs
     */
    Map<String, String> getConfigurationParameters(Connection connection) throws SQLException;

    /**
     * Get a specific configuration parameter value.
     * @param connection The database connection
     * @param parameterName The parameter name
     * @return The parameter value, or null if not found
     * @throws SQLException If a database error occurs
     */
    String getParameter(Connection connection, String parameterName) throws SQLException;

    /**
     * Get memory-related configuration parameters.
     * @param connection The database connection
     * @return Map of memory parameter names to values
     * @throws SQLException If a database error occurs
     */
    Map<String, String> getMemoryConfiguration(Connection connection) throws SQLException;

    /**
     * Analyze configuration and return recommendations.
     * @param connection The database connection
     * @return List of configuration recommendations
     * @throws SQLException If a database error occurs
     */
    List<Map<String, Object>> analyzeConfiguration(Connection connection) throws SQLException;

    /**
     * Get database version information.
     * @param connection The database connection
     * @return Version string
     * @throws SQLException If a database error occurs
     */
    String getDatabaseVersion(Connection connection) throws SQLException;
}
