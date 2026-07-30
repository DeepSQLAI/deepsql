package com.dbaagent.provider.api;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Provider interface for lock and blocking information.
 * Handles retrieval of lock contention, blocking queries, and deadlock detection.
 */
public interface LockProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Get current lock information.
     * @param connection The database connection
     * @param database The database name
     * @return List of lock information as maps
     * @throws SQLException If a database error occurs
     */
    List<Map<String, Object>> getCurrentLocks(Connection connection, String database) throws SQLException;

    /**
     * Get blocking queries and their blocked victims.
     * @param connection The database connection
     * @param database The database name
     * @return List of blocking relationships as maps
     * @throws SQLException If a database error occurs
     */
    List<Map<String, Object>> getBlockingQueries(Connection connection, String database) throws SQLException;

    /**
     * Get lock wait statistics.
     * @param connection The database connection
     * @param database The database name
     * @return Lock wait statistics as a map
     * @throws SQLException If a database error occurs
     */
    Map<String, Object> getLockWaitStats(Connection connection, String database) throws SQLException;

    /**
     * Check for potential deadlocks.
     * @param connection The database connection
     * @param database The database name
     * @return List of potential deadlock situations
     * @throws SQLException If a database error occurs
     */
    List<Map<String, Object>> detectDeadlocks(Connection connection, String database) throws SQLException;
}
