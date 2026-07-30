package com.dbaagent.provider.api;

import com.dbaagent.model.ActiveQuery;
import com.dbaagent.model.IndexDetail;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

/**
 * Provider interface for performance metrics collection.
 * Handles active queries, unused indexes, and general performance data.
 */
public interface PerformanceMetricsProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Get currently active/running queries.
     * @param connection The database connection
     * @param database The database name
     * @return List of active queries
     * @throws SQLException If a database error occurs
     */
    List<ActiveQuery> getActiveQueries(Connection connection, String database) throws SQLException;

    /**
     * Get unused indexes in the database.
     * @param connection The database connection
     * @param database The database name
     * @return List of unused index details
     * @throws SQLException If a database error occurs
     */
    List<IndexDetail> getUnusedIndexes(Connection connection, String database) throws SQLException;

    /**
     * Get duplicate/redundant indexes in the database.
     * @param connection The database connection
     * @param database The database name
     * @return List of duplicate index details
     * @throws SQLException If a database error occurs
     */
    List<IndexDetail> getDuplicateIndexes(Connection connection, String database) throws SQLException;

    /**
     * Get database performance metrics.
     * @param connection The database connection
     * @param database The database name
     * @return Map of metric names to values
     * @throws SQLException If a database error occurs
     */
    Map<String, Object> getPerformanceMetrics(Connection connection, String database) throws SQLException;

    /**
     * Get cache hit ratio.
     * @param connection The database connection
     * @return Cache hit ratio (0.0 - 1.0)
     * @throws SQLException If a database error occurs
     */
    Double getCacheHitRatio(Connection connection) throws SQLException;
}
