package com.dbaagent.provider.api;

import com.dbaagent.model.SlowQuery;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

/**
 * Provider interface for slow query collection and analysis.
 * Handles retrieval of slow queries from performance schema, slow log, etc.
 */
public interface SlowQueryProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Collect slow queries from the database's performance monitoring systems.
     * @param connection The database connection
     * @param database The database name
     * @param thresholdMs Minimum execution time threshold in milliseconds
     * @param limit Maximum number of slow queries to return
     * @return List of slow queries
     * @throws SQLException If a database error occurs
     */
    List<SlowQuery> collectSlowQueries(Connection connection, String database, double thresholdMs, int limit) throws SQLException;

    /**
     * Check if slow query monitoring is enabled/available.
     * @param connection The database connection
     * @return true if slow query monitoring is available
     * @throws SQLException If a database error occurs
     */
    boolean isSlowQueryMonitoringAvailable(Connection connection) throws SQLException;

    /**
     * Get the sources of slow query data available for this database.
     * @param connection The database connection
     * @return List of available source names (e.g., "Performance Schema", "pg_stat_statements")
     * @throws SQLException If a database error occurs
     */
    List<String> getAvailableSources(Connection connection) throws SQLException;
}
