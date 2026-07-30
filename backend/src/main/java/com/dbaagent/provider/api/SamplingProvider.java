package com.dbaagent.provider.api;

/**
 * Provider interface for database-specific sampling and identifier quoting.
 * Encapsulates SQL generation that varies by database engine — keeping
 * if/else branching out of service classes.
 */
public interface SamplingProvider {

    String getDatabaseType();

    /**
     * Quote a single identifier (table or column name) for this database type.
     * Always quotes to avoid keyword collisions.
     */
    String quoteIdentifier(String identifier);

    /**
     * Build a fully-qualified, quoted table reference.
     * Skips schema when null or blank.
     */
    String qualifyTable(String schemaName, String tableName);

    /**
     * Build SQL to sample random rows from a table.
     * Uses the database-native random ordering function.
     *
     * @param tableRef pre-qualified and quoted table reference
     * @param limit    max rows to return
     */
    String buildRandomSampleSql(String tableRef, int limit);

    /**
     * Build SQL to sample the most recent rows using a temporal column.
     *
     * @param tableRef              pre-qualified and quoted table reference
     * @param quotedTemporalColumn  quoted column name (already quoted via quoteIdentifier)
     * @param limit                 max rows to return
     */
    String buildRecentSampleSql(String tableRef, String quotedTemporalColumn, int limit);

    /**
     * Build SQL for stratified sampling: recent rows UNION ALL random rows.
     * Produces a combined result set for richer AI context.
     *
     * @param tableRef              pre-qualified and quoted table reference
     * @param quotedTemporalColumn  quoted column name (already quoted via quoteIdentifier)
     * @param recentCount           rows from the recent stratum
     * @param randomCount           rows from the random stratum
     */
    String buildStratifiedSampleSql(String tableRef, String quotedTemporalColumn,
                                     int recentCount, int randomCount);
}
