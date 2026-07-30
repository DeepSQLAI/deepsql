package com.dbaagent.model;

/**
 * Enumeration of slow query data sources
 */
public enum SlowQuerySource {
    /**
     * MySQL Performance Schema (events_statements_summary_by_digest)
     * - Most detailed metrics
     * - Requires performance_schema=ON
     * - Aggregated statistics
     */
    MYSQL_PERFORMANCE_SCHEMA("MySQL Performance Schema"),

    /**
     * MySQL slow query log table (mysql.slow_log)
     * - Requires log_output='TABLE'
     * - Individual query executions
     * - Historical data
     */
    MYSQL_SLOW_LOG_TABLE("MySQL Slow Log Table"),

    /**
     * MySQL slow query log file
     * - Requires slow_query_log='ON' and log_output='FILE'
     * - Most complete query text
     * - May require file system access
     */
    MYSQL_SLOW_LOG_FILE("MySQL Slow Log File"),

    /**
     * PostgreSQL pg_stat_statements extension
     * - Requires extension installed
     * - Aggregated statistics
     * - Query normalization built-in
     */
    POSTGRESQL_PG_STAT_STATEMENTS("PostgreSQL pg_stat_statements");

    private final String displayName;

    SlowQuerySource(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
