package com.dbaagent.provider.api;

import com.dbaagent.model.QueryResult;

import java.sql.Connection;
import java.sql.SQLException;

/**
 * Provider interface for query execution operations.
 * Handles database-specific query execution features.
 */
public interface QueryExecutionProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Apply a row limit to a query (database-specific syntax).
     * @param query The original query
     * @param limit The maximum number of rows
     * @return The query with limit applied
     */
    String applyLimit(String query, int limit);

    /**
     * Check if a query is read-only (SELECT, EXPLAIN, etc.).
     * @param query The query to check
     * @return true if the query is read-only
     */
    boolean isReadOnlyQuery(String query);

    /**
     * Get the query to test database connectivity.
     * @return The test query (e.g., "SELECT 1")
     */
    String getConnectionTestQuery();

    /**
     * Normalize a query for comparison/caching purposes.
     * @param query The original query
     * @return The normalized query
     */
    String normalizeQuery(String query);

    /**
     * Generate a stable hash/signature for a query.
     * @param query The query to hash
     * @return The query signature
     */
    String generateQuerySignature(String query);

    /**
     * SQL returning the server-side session id of the current connection, in the
     * form accepted by this dialect's kill statement. Callers use it to cancel a
     * still-running query after the client has gone away.
     *
     * @return a single-column, single-row query, or null when the dialect has no
     *         session identifier we can act on
     */
    default String getSessionPidQuery() {
        return null;
    }
}
