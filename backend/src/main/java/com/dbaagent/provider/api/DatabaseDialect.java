package com.dbaagent.provider.api;

import java.util.Set;

/**
 * Composite interface representing a complete database dialect.
 * Groups all provider interfaces for a specific database type.
 *
 * Each database implementation (MySQL, PostgreSQL, etc.) should provide
 * a single class implementing this interface that returns the appropriate
 * provider implementations.
 */
public interface DatabaseDialect {

    /**
     * Get the canonical name for this database type.
     * This is the primary identifier used in configuration.
     * @return The canonical name (e.g., "mysql", "postgres")
     */
    String getCanonicalName();

    /**
     * Get all aliases that should resolve to this dialect.
     * Used for flexible configuration (e.g., "postgresql" -> "postgres").
     * @return Set of alias names
     */
    Set<String> getAliases();

    /**
     * Get the display name for this database type.
     * Used in UI and logging.
     * @return The display name (e.g., "MySQL", "PostgreSQL")
     */
    String getDisplayName();

    /**
     * Get the connection provider for this database.
     * @return The connection provider
     */
    ConnectionProvider connection();

    /**
     * Get the introspection provider for this database.
     * @return The introspection provider
     */
    IntrospectionProvider introspection();

    /**
     * Get the slow query provider for this database.
     * @return The slow query provider
     */
    SlowQueryProvider slowQueries();

    /**
     * Get the performance metrics provider for this database.
     * @return The performance metrics provider
     */
    PerformanceMetricsProvider metrics();

    /**
     * Get the lock provider for this database.
     * @return The lock provider
     */
    LockProvider locks();

    /**
     * Get the explain plan provider for this database.
     * @return The explain plan provider
     */
    ExplainPlanProvider explainPlan();

    /**
     * Get the configuration provider for this database.
     * @return The configuration provider
     */
    ConfigurationProvider configuration();

    /**
     * Get the query execution provider for this database.
     * @return The query execution provider
     */
    QueryExecutionProvider queryExecution();

    /**
     * Get the privilege check provider for this database.
     * @return The privilege check provider
     */
    PrivilegeCheckProvider privileges();

    /**
     * Get the sampling provider for this database.
     * Handles identifier quoting and sampling SQL generation.
     * @return The sampling provider
     */
    SamplingProvider sampling();

    /**
     * Get the migration risk provider for this database.
     * @return The migration risk provider
     */
    MigrationRiskProvider migrationRisk();
}
