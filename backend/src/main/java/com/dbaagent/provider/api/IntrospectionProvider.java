package com.dbaagent.provider.api;

import com.dbaagent.model.*;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
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
