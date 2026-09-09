package com.dbaagent.provider.api;

import com.dbaagent.model.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Provider interface for database schema introspection.
 * Handles retrieval of tables, columns, indexes, constraints, and other database objects.
 */
public interface IntrospectionProvider {

    /**
     * Get the database type this provider handles.
     * @return The database type (e.g., "mysql", "postgres")
     */
    String getDatabaseType();

    /**
     * Get all database objects (tables, views, functions, procedures).
     * @param connection The database connection
     * @param database The database/schema name
     * @return List of database objects
     * @throws SQLException If a database error occurs
     */
    List<DatabaseObject> getDatabaseObjects(Connection connection, String database) throws SQLException;

    /**
     * Get columns for a specific table.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return List of column information
     * @throws SQLException If a database error occurs
     */
    List<ColumnInfo> getTableColumns(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get indexes for a specific table.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return List of table indexes
     * @throws SQLException If a database error occurs
     */
    List<TableIndex> getTableIndexes(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get indexes for every table in a schema in one round trip.
     *
     * <p>The per-table {@link #getTableIndexes} above is a round trip each, which turns
     * enrichment of a wide schema into hundreds of serial queries — painful on any link
     * with real latency (an SSH tunnel to a replica, say). This mirrors what
     * {@link #getForeignKeys} already does for constraints: fetch the whole schema once
     * and group in memory.
     *
     * <p>Results are keyed by the caller's own table name, lower-cased — whatever was
     * passed in, qualified or not — so a caller can look up what it asked for.
     *
     * <p>Two distinct outcomes, and callers must treat them differently:
     * <ul>
     *   <li><b>Present, empty list</b> — the table was scanned and genuinely has no
     *       indexes. Nothing further to do.</li>
     *   <li><b>Absent</b> — this provider declined to answer for that name, and the
     *       caller must fall back to {@link #getTableIndexes}. An implementation is free
     *       to decline any name it cannot answer precisely; the Postgres one declines
     *       schema-qualified names rather than risk merging indexes across schemas.</li>
     * </ul>
     *
     * <p>The default implementation just loops {@link #getTableIndexes}, so a provider
     * that does not override this behaves exactly as before.
     *
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableNames Tables the caller cares about (used only by the default fallback)
     * @return Map of lower-cased caller-supplied table name to that table's indexes;
     *         names the provider declined are absent rather than empty
     * @throws SQLException If a database error occurs
     */
    default Map<String, List<TableIndex>> getAllTableIndexes(
        Connection connection, String database, Collection<String> tableNames
    ) throws SQLException {
        Map<String, List<TableIndex>> byTable = new HashMap<>();
        for (String tableName : tableNames) {
            if (tableName == null) {
                continue;
            }
            byTable.put(
                tableName.toLowerCase(Locale.ROOT),
                getTableIndexes(connection, database, tableName)
            );
        }
        return byTable;
    }

    /**
     * Get table statistics (size, row count, etc.).
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return Table statistics
     * @throws SQLException If a database error occurs
     */
    TableStats getTableStats(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Scan the entire schema and return metadata for all tables.
     * @param connection The database connection
     * @param database The database/schema name
     * @return Schema metadata including all tables
     * @throws SQLException If a database error occurs
     */
    SchemaMetadata scanSchema(Connection connection, String database) throws SQLException;

    /**
     * Get foreign key relationships in the database.
     * @param connection The database connection
     * @param database The database/schema name
     * @return List of foreign key relationships
     * @throws SQLException If a database error occurs
     */
    List<RelationshipMetadata> getForeignKeys(Connection connection, String database) throws SQLException;

    /**
     * Get detailed column information for a table.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return List of detailed column information
     * @throws SQLException If a database error occurs
     */
    List<ColumnDetail> getColumnDetails(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get constraint details for a table.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return List of constraint details
     * @throws SQLException If a database error occurs
     */
    List<ConstraintDetail> getConstraintDetails(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get row count for a table (estimated or actual).
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return The row count
     * @throws SQLException If a database error occurs
     */
    Long getTableRowCount(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get detailed index information for a table.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return List of index details
     * @throws SQLException If a database error occurs
     */
    List<IndexDetail> getIndexDetails(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get foreign key details for a table.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return List of foreign key details
     * @throws SQLException If a database error occurs
     */
    List<ForeignKeyDetail> getForeignKeyDetails(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get human-readable table size.
     * @param connection The database connection
     * @param database The database/schema name
     * @param tableName The table name
     * @return The table size as a formatted string (e.g., "10 MB")
     * @throws SQLException If a database error occurs
     */
    String getTableSize(Connection connection, String database, String tableName) throws SQLException;

    /**
     * Get the default schema name for this database type.
     * @return The default schema name (e.g., "public" for PostgreSQL, null for MySQL)
     */
    String getDefaultSchema();

    /**
     * Get all tables with basic metadata.
     * @param connection The database connection
     * @param database The database/schema name
     * @return List of table info maps with tableName, tableSchema, rowCount, tableSize
     * @throws SQLException If a database error occurs
     */
    List<Map<String, Object>> getAllTablesWithMetadata(Connection connection, String database) throws SQLException;

    /**
     * Get table relationships (foreign key mappings).
     * @param connection The database connection
     * @param database The database/schema name
     * @return Map of source table to list of target tables
     * @throws SQLException If a database error occurs
     */
    Map<String, List<String>> getTableRelationships(Connection connection, String database) throws SQLException;
}
