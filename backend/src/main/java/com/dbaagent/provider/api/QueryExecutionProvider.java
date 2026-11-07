package com.dbaagent.provider.api;

import com.dbaagent.model.QueryResult;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

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

    /**
     * Session settings applied once to every pooled connection when it opens, so every
     * path (Editor, agent, dashboards, background jobs) fails fast instead of queueing
     * behind a table lock. Null when the dialect needs none.
     */
    default String connectionGuardSql() {
        return null;
    }

    /**
     * Session settings applied before each user or agent run, and again after any SET
     * preambles the user sent, so a preamble cannot loosen them. A superset of
     * {@link #connectionGuardSql()}.
     *
     * @param timeoutSeconds the client-side timeout for this statement; 0 when unset
     * @param serverVersion  {@code DatabaseMetaData.getDatabaseProductVersion()}, or null
     *                       when unknown — a dialect alias can point at a different
     *                       server (MySQL's covers MariaDB) with different variables
     */
    default String statementGuardSql(int timeoutSeconds, String serverVersion) {
        return connectionGuardSql();
    }

    /**
     * Restores the pooled connection to its {@link #connectionGuardSql()} baseline after a
     * run. Session variables outlive the borrow: without this, a per-statement limit or a
     * user's own SET would carry over to the next borrower, background jobs included.
     */
    default String statementGuardResetSql(String serverVersion) {
        return connectionGuardSql();
    }

    /**
     * Variants of a DDL statement to try in order so it cannot block writes while it
     * runs. The executor moves to the next variant only when
     * {@link #isOnlineDdlUnsupported} says the previous one was refused for its
     * algorithm.
     *
     * @throws IllegalArgumentException when the statement explicitly asks for a
     *         blocking algorithm or lock mode
     */
    default List<String> onlineDdlAttempts(String sql) {
        return List.of(sql);
    }

    /** True when a DDL attempt failed only because the requested online algorithm is unsupported. */
    default boolean isOnlineDdlUnsupported(SQLException e) {
        return false;
    }

    /**
     * A plain-language explanation when the database refused a statement because one of
     * the guards above tripped, or null for any other error.
     */
    default String guardrailMessage(SQLException e) {
        return null;
    }
}
