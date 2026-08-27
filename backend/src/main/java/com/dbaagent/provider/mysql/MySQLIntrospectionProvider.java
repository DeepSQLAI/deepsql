package com.dbaagent.provider.mysql;

import com.dbaagent.model.*;
import com.dbaagent.provider.api.IntrospectionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * MySQL implementation of IntrospectionProvider.
 * Uses INFORMATION_SCHEMA for schema introspection.
 */
@Slf4j
@Component
public class MySQLIntrospectionProvider implements IntrospectionProvider {

    private static final String DATABASE_TYPE = "mysql";

    @Value("${db.fetch-size:1000}")
    private int fetchSize;

    @Value("${db.query-timeout-seconds:30}")
    private int queryTimeoutSeconds;

    @Override
    public String getDatabaseType() {
        return DATABASE_TYPE;
    }

    @Override
    public List<DatabaseObject> getDatabaseObjects(Connection connection, String database) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();
        objects.addAll(getTablesAndViews(connection, database));
        objects.addAll(getFunctions(connection, database));
        objects.addAll(getProcedures(connection, database));
        return objects;
    }

    private List<DatabaseObject> getTablesAndViews(Connection connection, String database) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        String query = """
            SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_TYPE, TABLE_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("TABLE_NAME"));
                    obj.setSchema(database);
                    String tableType = rs.getString("TABLE_TYPE");
                    obj.setType("BASE TABLE".equals(tableType) ? "table" : "view");
                    obj.setRowCount(rs.getLong("TABLE_ROWS"));
                    obj.setColumns(getTableColumns(connection, database, obj.getName()));
                    objects.add(obj);
                }
            }
        }
        return objects;
    }

    private List<DatabaseObject> getFunctions(Connection connection, String database) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        String query = """
            SELECT ROUTINE_NAME, ROUTINE_DEFINITION
            FROM INFORMATION_SCHEMA.ROUTINES
            WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'FUNCTION'
            ORDER BY ROUTINE_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("ROUTINE_NAME"));
                    obj.setSchema(database);
                    obj.setType("function");
                    obj.setDefinition(rs.getString("ROUTINE_DEFINITION"));
                    objects.add(obj);
                }
            }
        }
        return objects;
    }

    private List<DatabaseObject> getProcedures(Connection connection, String database) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        String query = """
            SELECT ROUTINE_NAME, ROUTINE_DEFINITION
            FROM INFORMATION_SCHEMA.ROUTINES
            WHERE ROUTINE_SCHEMA = ? AND ROUTINE_TYPE = 'PROCEDURE'
            ORDER BY ROUTINE_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("ROUTINE_NAME"));
                    obj.setSchema(database);
                    obj.setType("procedure");
                    obj.setDefinition(rs.getString("ROUTINE_DEFINITION"));
                    objects.add(obj);
                }
            }
        }
        return objects;
    }

    @Override
    public List<ColumnInfo> getTableColumns(Connection connection, String database, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();

        String query = """
            SELECT COLUMN_NAME, DATA_TYPE, IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("COLUMN_NAME"));
                    col.setDataType(rs.getString("DATA_TYPE"));
                    col.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                    col.setPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")));
                    col.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    /**
     * One query for the whole schema instead of one per table.
     *
     * <p>INFORMATION_SCHEMA.STATISTICS is not cheap on MySQL, and paying for it 567 times
     * in a row across a tunnel is what made schema enrichment take minutes. Scoped to a
     * single TABLE_SCHEMA so this stays bounded on servers hosting many databases.
     */
    @Override
    public Map<String, List<TableIndex>> getAllTableIndexes(
        Connection connection, String database, Collection<String> tableNames
    ) throws SQLException {
        String query = """
            SELECT TABLE_NAME, INDEX_NAME, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE, SEQ_IN_INDEX
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX
            """;

        // table -> index name -> index, so multi-column indexes accumulate their columns
        // in SEQ_IN_INDEX order the same way the per-table path builds them.
        Map<String, Map<String, TableIndex>> byTable = new HashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    if (tableName == null) {
                        continue;
                    }
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                    String indexType = rs.getString("INDEX_TYPE");

                    Map<String, TableIndex> indexMap =
                        byTable.computeIfAbsent(tableName.toLowerCase(Locale.ROOT), k -> new LinkedHashMap<>());

                    TableIndex index = indexMap.get(indexName);
                    if (index == null) {
                        index = new TableIndex();
                        index.setName(indexName);
                        index.setType(indexType);
                        index.setUnique(!nonUnique);
                        index.setPrimary("PRIMARY".equals(indexName));
                        index.setColumns(new ArrayList<>());
                        indexMap.put(indexName, index);
                    }
                    index.getColumns().add(columnName);
                }
            }
        }

        // Tables with no indexes at all must still be present, so callers can tell an
        // unindexed table from one this scan never covered.
        Map<String, List<TableIndex>> result = new HashMap<>();
        for (String tableName : tableNames) {
            if (tableName == null) {
                continue;
            }
            // Callers look up by the name they passed in, but STATISTICS returns bare
            // TABLE_NAMEs — so match on the bare name and key the result by the original.
            String key = tableName.toLowerCase(Locale.ROOT);
            int dot = key.lastIndexOf('.');
            String bare = dot > 0 ? key.substring(dot + 1) : key;
            Map<String, TableIndex> indexMap = byTable.get(bare);
            result.put(key, indexMap == null ? new ArrayList<>() : new ArrayList<>(indexMap.values()));
        }
        return result;
    }

    @Override
    public List<TableIndex> getTableIndexes(Connection connection, String database, String tableName) throws SQLException {
        List<TableIndex> indexes = new ArrayList<>();
        Map<String, TableIndex> indexMap = new HashMap<>();

        String schemaName = database;
        String bareName = tableName;
        if (tableName != null) {
            int dot = tableName.lastIndexOf('.');
            if (dot > 0) {
                schemaName = tableName.substring(0, dot);
                bareName = tableName.substring(dot + 1);
            }
        }

        String query = """
            SELECT INDEX_NAME, COLUMN_NAME, NON_UNIQUE, INDEX_TYPE, SEQ_IN_INDEX
            FROM INFORMATION_SCHEMA.STATISTICS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, schemaName);
            stmt.setString(2, bareName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("INDEX_NAME");
                    String columnName = rs.getString("COLUMN_NAME");
                    boolean nonUnique = rs.getBoolean("NON_UNIQUE");
                    String indexType = rs.getString("INDEX_TYPE");

                    TableIndex index = indexMap.get(indexName);
                    if (index == null) {
                        index = new TableIndex();
                        index.setName(indexName);
                        index.setType(indexType);
                        index.setUnique(!nonUnique);
                        index.setPrimary("PRIMARY".equals(indexName));
                        index.setColumns(new ArrayList<>());
                        indexMap.put(indexName, index);
                    }
                    index.getColumns().add(columnName);
                }
            }
        }

        indexes.addAll(indexMap.values());
        return indexes;
    }

    @Override
    public TableStats getTableStats(Connection connection, String database, String tableName) throws SQLException {
        TableStats stats = new TableStats();
        stats.setTableName(tableName);

        String query = """
            SELECT
                ENGINE, TABLE_COLLATION, TABLE_COMMENT, TABLE_ROWS,
                DATA_LENGTH, INDEX_LENGTH, DATA_FREE
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setQueryTimeout(10);
            stmt.setString(1, database);
            stmt.setString(2, tableName);

            long dataFree = 0;
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setEngine(rs.getString("ENGINE"));
                    stats.setCollation(rs.getString("TABLE_COLLATION"));
                    stats.setComment(rs.getString("TABLE_COMMENT"));
                    stats.setRowCount(rs.getLong("TABLE_ROWS"));
                    stats.setDataSize(rs.getLong("DATA_LENGTH"));
                    stats.setIndexSize(rs.getLong("INDEX_LENGTH"));
                    stats.setSizeBytes(rs.getLong("DATA_LENGTH") + rs.getLong("INDEX_LENGTH"));
                    stats.setIndexSizeBytes(rs.getLong("INDEX_LENGTH"));
                    dataFree = rs.getLong("DATA_FREE");
                }
            }
            calculateTableBloat(connection, database, tableName, stats, dataFree);
        }
        return stats;
    }

    private void calculateTableBloat(Connection connection, String database, String tableName, TableStats stats, long dataFree) {
        String bloatQuery = """
            SELECT
                CAST(t.data_length AS DECIMAL(30,0)) + CAST(t.index_length AS DECIMAL(30,0)) AS logical_bytes,
                SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0))) AS allocated_bytes,
                GREATEST(
                  SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0)))
                  - (CAST(t.data_length AS DECIMAL(30,0)) + CAST(t.index_length AS DECIMAL(30,0))),
                  0
                ) AS bloat_bytes,
                ROUND(
                  100 * GREATEST(
                    SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0)))
                    - (CAST(t.data_length AS DECIMAL(30,0)) + CAST(t.index_length AS DECIMAL(30,0))),
                    0
                  )
                  / NULLIF(SUM(CAST(f.total_extents AS DECIMAL(30,0)) * CAST(f.extent_size AS DECIMAL(30,0))), 0),
                  2
                ) AS bloat_pct
            FROM information_schema.tables t
            JOIN information_schema.files f
              ON f.file_type = 'TABLESPACE'
             AND (
                  LOWER(f.tablespace_name) = LOWER(CONCAT(t.table_schema, '/', t.table_name))
                  OR LOWER(f.tablespace_name) LIKE LOWER(CONCAT(t.table_schema, '/', t.table_name, '#P#%'))
                 )
            WHERE t.table_schema = ?
              AND t.table_name = ?
              AND t.table_type = 'BASE TABLE'
            GROUP BY t.table_schema, t.table_name, t.data_length, t.index_length
            """;

        try (PreparedStatement stmt = connection.prepareStatement(bloatQuery)) {
            stmt.setQueryTimeout(5);
            stmt.setString(1, database);
            stmt.setString(2, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setAllocatedBytes(rs.getLong("allocated_bytes"));
                    stats.setBloatBytes(rs.getLong("bloat_bytes"));
                    stats.setBloatPercent(rs.getDouble("bloat_pct"));
                }
            }
        } catch (SQLException e) {
            // Use DATA_FREE as fallback
            if (dataFree > 0 && stats.getSizeBytes() != null && stats.getSizeBytes() > 0) {
                long logicalSize = stats.getSizeBytes();
                long allocatedSize = logicalSize + dataFree;
                stats.setAllocatedBytes(allocatedSize);
                stats.setBloatBytes(dataFree);
                double bloatPct = (dataFree * 100.0) / allocatedSize;
                stats.setBloatPercent(Math.round(bloatPct * 100.0) / 100.0);
                log.debug("Using DATA_FREE fallback for bloat on {}.{}: {} bytes", database, tableName, dataFree);
            } else {
                log.debug("Could not calculate bloat for {}.{}: {}", database, tableName, e.getMessage());
            }
        }
    }

    @Override
    public SchemaMetadata scanSchema(Connection connection, String database) throws SQLException {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName(database);

        // Get all tables and views
        String tablesQuery = "SELECT TABLE_NAME, TABLE_TYPE, TABLE_ROWS, " +
            "ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024) as SIZE_MB " +
            "FROM INFORMATION_SCHEMA.TABLES " +
            "WHERE TABLE_SCHEMA = ? AND TABLE_TYPE IN ('BASE TABLE', 'VIEW') " +
            "ORDER BY TABLE_NAME";

        Map<String, TableMetadata> tableMap = new HashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(tablesQuery)) {
            applyStatementSettings(stmt);
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setName(rs.getString("TABLE_NAME"));
                    table.setSchema(database);
                    table.setType(rs.getString("TABLE_TYPE").equals("BASE TABLE") ? "table" : "view");
                    Long tableRows = rs.getObject("TABLE_ROWS", Long.class);
                    table.setRowCount(tableRows != null ? tableRows : null);
                    long sizeMb = rs.getLong("SIZE_MB");
                    table.setSizeBytes(sizeMb >= 0 ? sizeMb * 1024L * 1024L : 0);
                    schema.getTables().add(table);
                    tableMap.put(table.getName(), table);
                }
            }
        }

        // Batch load all columns and indexes in single queries (eliminates N+1)
        scanMySQLColumnsBatch(connection, tableMap, database);
        scanMySQLIndexesBatch(connection, tableMap, database);

        // Get foreign key relationships
        scanMySQLForeignKeys(connection, schema, database);

        return schema;
    }

    private void applyStatementSettings(Statement stmt) throws SQLException {
        if (fetchSize > 0) {
            stmt.setFetchSize(fetchSize);
        }
        if (queryTimeoutSeconds > 0) {
            stmt.setQueryTimeout(queryTimeoutSeconds);
        }
    }

    /**
     * Batch load all columns for all tables in a single query (eliminates N+1).
     */
    private void scanMySQLColumnsBatch(Connection connection, Map<String, TableMetadata> tableMap, String database) throws SQLException {
        String query = "SELECT TABLE_NAME, COLUMN_NAME, DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, " +
            "IS_NULLABLE, COLUMN_KEY, COLUMN_DEFAULT, ORDINAL_POSITION " +
            "FROM INFORMATION_SCHEMA.COLUMNS " +
            "WHERE TABLE_SCHEMA = ? " +
            "ORDER BY TABLE_NAME, ORDINAL_POSITION";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            applyStatementSettings(stmt);
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    TableMetadata table = tableMap.get(tableName);
                    if (table != null) {
                        ColumnMetadata column = new ColumnMetadata();
                        column.setName(rs.getString("COLUMN_NAME"));
                        column.setDataType(rs.getString("DATA_TYPE"));
                        column.setMaxLength(rs.getObject("CHARACTER_MAXIMUM_LENGTH", Long.class));
                        column.setNullable("YES".equals(rs.getString("IS_NULLABLE")));
                        column.setPrimaryKey("PRI".equals(rs.getString("COLUMN_KEY")));
                        column.setDefaultValue(rs.getString("COLUMN_DEFAULT"));
                        column.setOrdinalPosition(rs.getInt("ORDINAL_POSITION"));
                        table.getColumns().add(column);
                    }
                }
            }
        }
    }

    /**
     * Batch load all indexes for all tables in a single query (eliminates N+1).
     */
    private void scanMySQLIndexesBatch(Connection connection, Map<String, TableMetadata> tableMap, String database) throws SQLException {
        String query = "SELECT TABLE_NAME, INDEX_NAME, NON_UNIQUE, COLUMN_NAME, INDEX_TYPE " +
            "FROM INFORMATION_SCHEMA.STATISTICS " +
            "WHERE TABLE_SCHEMA = ? " +
            "ORDER BY TABLE_NAME, INDEX_NAME, SEQ_IN_INDEX";

        // Map of tableName -> indexName -> IndexMetadata
        Map<String, Map<String, IndexMetadata>> tableIndexMap = new HashMap<>();

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            applyStatementSettings(stmt);
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String indexName = rs.getString("INDEX_NAME");

                    Map<String, IndexMetadata> indexMap = tableIndexMap.computeIfAbsent(tableName, k -> new HashMap<>());
                    IndexMetadata index = indexMap.get(indexName);

                    if (index == null) {
                        index = new IndexMetadata();
                        index.setName(indexName);
                        index.setTableName(tableName);
                        index.setUnique(rs.getInt("NON_UNIQUE") == 0);
                        index.setIndexType(rs.getString("INDEX_TYPE"));
                        index.setColumns(new ArrayList<>());
                        indexMap.put(indexName, index);
                    }
                    index.getColumns().add(rs.getString("COLUMN_NAME"));
                }
            }
        }

        // Assign indexes to tables
        for (Map.Entry<String, Map<String, IndexMetadata>> entry : tableIndexMap.entrySet()) {
            TableMetadata table = tableMap.get(entry.getKey());
            if (table != null) {
                table.setIndexes(new ArrayList<>(entry.getValue().values()));
            }
        }
    }

    private void scanMySQLForeignKeys(Connection connection, SchemaMetadata schema, String database) throws SQLException {
        String query = "SELECT k.CONSTRAINT_NAME, k.TABLE_NAME, k.COLUMN_NAME, " +
            "k.REFERENCED_TABLE_NAME, k.REFERENCED_COLUMN_NAME " +
            "FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE k " +
            "INNER JOIN INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc " +
            "ON k.CONSTRAINT_NAME = tc.CONSTRAINT_NAME " +
            "AND k.TABLE_SCHEMA = tc.TABLE_SCHEMA " +
            "WHERE k.TABLE_SCHEMA = ? AND tc.CONSTRAINT_TYPE = 'FOREIGN KEY' " +
            "ORDER BY k.TABLE_NAME, k.CONSTRAINT_NAME";
        
        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            applyStatementSettings(stmt);
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    RelationshipMetadata rel = new RelationshipMetadata();
                    rel.setConstraintName(rs.getString("CONSTRAINT_NAME"));
                    rel.setFromTable(rs.getString("TABLE_NAME"));
                    rel.setFromColumn(rs.getString("COLUMN_NAME"));
                    rel.setToTable(rs.getString("REFERENCED_TABLE_NAME"));
                    rel.setToColumn(rs.getString("REFERENCED_COLUMN_NAME"));
                    rel.setRelationshipType("one-to-many"); // Default, can be enhanced
                    schema.getRelationships().add(rel);
                }
            }
        }
    }

    @Override
    public List<RelationshipMetadata> getForeignKeys(Connection connection, String database) throws SQLException {
        List<RelationshipMetadata> relationships = new ArrayList<>();

        String query = """
            SELECT
                CONSTRAINT_NAME,
                TABLE_NAME,
                COLUMN_NAME,
                REFERENCED_TABLE_NAME,
                REFERENCED_COLUMN_NAME
            FROM INFORMATION_SCHEMA.KEY_COLUMN_USAGE
            WHERE TABLE_SCHEMA = ?
                AND REFERENCED_TABLE_NAME IS NOT NULL
            ORDER BY TABLE_NAME, CONSTRAINT_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    RelationshipMetadata rel = new RelationshipMetadata();
                    rel.setConstraintName(rs.getString("CONSTRAINT_NAME"));
                    rel.setFromTable(rs.getString("TABLE_NAME"));
                    rel.setFromColumn(rs.getString("COLUMN_NAME"));
                    rel.setToTable(rs.getString("REFERENCED_TABLE_NAME"));
                    rel.setToColumn(rs.getString("REFERENCED_COLUMN_NAME"));
                    relationships.add(rel);
                }
            }
        }
        return relationships;
    }

    @Override
    public List<ColumnDetail> getColumnDetails(Connection connection, String database, String tableName) throws SQLException {
        List<ColumnDetail> columns = new ArrayList<>();

        String query = """
            SELECT
                COLUMN_NAME, ORDINAL_POSITION, COLUMN_DEFAULT, IS_NULLABLE,
                DATA_TYPE, CHARACTER_MAXIMUM_LENGTH, NUMERIC_PRECISION, NUMERIC_SCALE,
                COLUMN_TYPE, COLUMN_KEY, EXTRA, COLUMN_COMMENT
            FROM INFORMATION_SCHEMA.COLUMNS
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            ORDER BY ORDINAL_POSITION
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String columnKey = rs.getString("COLUMN_KEY");
                    ColumnDetail col = ColumnDetail.builder()
                        .columnName(rs.getString("COLUMN_NAME"))
                        .columnDefault(rs.getString("COLUMN_DEFAULT"))
                        .isNullable("YES".equals(rs.getString("IS_NULLABLE")))
                        .dataType(rs.getString("DATA_TYPE"))
                        .characterMaximumLength(String.valueOf(rs.getLong("CHARACTER_MAXIMUM_LENGTH")))
                        .numericPrecision(String.valueOf(rs.getInt("NUMERIC_PRECISION")))
                        .numericScale(String.valueOf(rs.getInt("NUMERIC_SCALE")))
                        .columnType(rs.getString("COLUMN_TYPE"))
                        .isPrimaryKey("PRI".equals(columnKey))
                        .isUnique("UNI".equals(columnKey))
                        .isIndexed("MUL".equals(columnKey) || "PRI".equals(columnKey) || "UNI".equals(columnKey))
                        .comment(rs.getString("COLUMN_COMMENT"))
                        .build();
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    @Override
    public List<ConstraintDetail> getConstraintDetails(Connection connection, String database, String tableName) throws SQLException {
        List<ConstraintDetail> constraints = new ArrayList<>();
        Map<String, ConstraintDetail> constraintMap = new HashMap<>();

        String query = """
            SELECT
                tc.CONSTRAINT_NAME,
                tc.CONSTRAINT_TYPE,
                kcu.COLUMN_NAME,
                kcu.REFERENCED_TABLE_NAME,
                kcu.REFERENCED_COLUMN_NAME
            FROM INFORMATION_SCHEMA.TABLE_CONSTRAINTS tc
            LEFT JOIN INFORMATION_SCHEMA.KEY_COLUMN_USAGE kcu
                ON tc.CONSTRAINT_NAME = kcu.CONSTRAINT_NAME
                AND tc.TABLE_SCHEMA = kcu.TABLE_SCHEMA
                AND tc.TABLE_NAME = kcu.TABLE_NAME
            WHERE tc.TABLE_SCHEMA = ? AND tc.TABLE_NAME = ?
            ORDER BY tc.CONSTRAINT_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String constraintName = rs.getString("CONSTRAINT_NAME");
                    String constraintType = rs.getString("CONSTRAINT_TYPE");
                    String columnName = rs.getString("COLUMN_NAME");

                    ConstraintDetail constraint = constraintMap.get(constraintName);
                    if (constraint == null) {
                        constraint = ConstraintDetail.builder()
                            .constraintName(constraintName)
                            .constraintType(constraintType)
                            .columns(new ArrayList<>())
                            .build();
                        constraintMap.put(constraintName, constraint);
                    }

                    if (columnName != null) {
                        constraint.getColumns().add(columnName);
                    }
                }
            }
        }
        constraints.addAll(constraintMap.values());
        return constraints;
    }

    @Override
    public Long getTableRowCount(Connection connection, String database, String tableName) throws SQLException {
        String query = """
            SELECT TABLE_ROWS
            FROM INFORMATION_SCHEMA.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_NAME = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getLong("TABLE_ROWS");
                }
            }
        }
        return 0L;
    }

    @Override
    public List<IndexDetail> getIndexDetails(Connection connection, String database, String tableName) throws SQLException {
        List<IndexDetail> indexes = new ArrayList<>();
        Map<String, IndexDetail> indexMap = new LinkedHashMap<>();

        String query = """
            SELECT
                INDEX_NAME as index_name,
                INDEX_TYPE as index_type,
                COLUMN_NAME as column_name,
                NON_UNIQUE = 0 as is_unique,
                INDEX_NAME = 'PRIMARY' as is_primary,
                NULL as index_definition,
                NULL as index_size
            FROM information_schema.STATISTICS
            WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ?
            ORDER BY INDEX_NAME, SEQ_IN_INDEX
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            stmt.setString(2, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("index_name");
                    IndexDetail existing = indexMap.get(indexName);

                    if (existing == null) {
                        existing = IndexDetail.builder()
                            .indexName(indexName)
                            .indexType(rs.getString("index_type"))
                            .columns(new ArrayList<>())
                            .isUnique(rs.getBoolean("is_unique"))
                            .isPrimary(rs.getBoolean("is_primary"))
                            .indexDefinition(rs.getString("index_definition"))
                            .indexSize(rs.getObject("index_size") != null ? rs.getLong("index_size") : null)
                            .build();
                        indexMap.put(indexName, existing);
                    }
                    existing.getColumns().add(rs.getString("column_name"));
                }
            }
        }

        indexes.addAll(indexMap.values());
        return indexes;
    }

    @Override
    public List<ForeignKeyDetail> getForeignKeyDetails(Connection connection, String database, String tableName) throws SQLException {
        List<ForeignKeyDetail> foreignKeys = new ArrayList<>();

        String query = """
            SELECT
                kcu.CONSTRAINT_NAME as constraint_name,
                kcu.COLUMN_NAME as column_name,
                kcu.REFERENCED_TABLE_NAME AS referenced_table,
                kcu.REFERENCED_COLUMN_NAME AS referenced_column,
                rc.DELETE_RULE as on_delete,
                rc.UPDATE_RULE as on_update
            FROM information_schema.KEY_COLUMN_USAGE kcu
            JOIN information_schema.REFERENTIAL_CONSTRAINTS rc
                ON kcu.CONSTRAINT_NAME = rc.CONSTRAINT_NAME AND kcu.TABLE_SCHEMA = rc.CONSTRAINT_SCHEMA
            WHERE kcu.TABLE_NAME = ? AND kcu.TABLE_SCHEMA = ? AND kcu.REFERENCED_TABLE_NAME IS NOT NULL
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            stmt.setString(2, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    foreignKeys.add(ForeignKeyDetail.builder()
                        .constraintName(rs.getString("constraint_name"))
                        .columnName(rs.getString("column_name"))
                        .referencedTable(rs.getString("referenced_table"))
                        .referencedColumn(rs.getString("referenced_column"))
                        .onDelete(rs.getString("on_delete"))
                        .onUpdate(rs.getString("on_update"))
                        .build());
                }
            }
        }

        return foreignKeys;
    }

    @Override
    public String getTableSize(Connection connection, String database, String tableName) throws SQLException {
        String query = """
            SELECT CONCAT(
                ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2), ' MB'
            ) as size
            FROM information_schema.TABLES
            WHERE TABLE_NAME = ? AND TABLE_SCHEMA = ?
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            stmt.setString(2, database);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString("size");
                }
            }
        }

        return null;
    }

    @Override
    public String getDefaultSchema() {
        return null; // MySQL uses the database name, not a schema
    }

    @Override
    public List<Map<String, Object>> getAllTablesWithMetadata(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> tables = new ArrayList<>();

        String query = """
            SELECT
                TABLE_NAME as table_name,
                TABLE_SCHEMA as table_schema,
                TABLE_ROWS as row_count,
                CONCAT(ROUND((DATA_LENGTH + INDEX_LENGTH) / 1024 / 1024, 2), ' MB') as table_size
            FROM information_schema.TABLES
            WHERE TABLE_SCHEMA = ? AND TABLE_TYPE = 'BASE TABLE'
            ORDER BY TABLE_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    Map<String, Object> tableInfo = new LinkedHashMap<>();
                    tableInfo.put("tableName", rs.getString("table_name"));
                    tableInfo.put("tableSchema", rs.getString("table_schema"));
                    tableInfo.put("rowCount", rs.getLong("row_count"));
                    tableInfo.put("tableSize", rs.getString("table_size"));
                    tables.add(tableInfo);
                }
            }
        }

        return tables;
    }

    @Override
    public Map<String, List<String>> getTableRelationships(Connection connection, String database) throws SQLException {
        Map<String, List<String>> relationships = new HashMap<>();

        String query = """
            SELECT
                TABLE_NAME as source_table,
                REFERENCED_TABLE_NAME AS target_table
            FROM information_schema.KEY_COLUMN_USAGE
            WHERE REFERENCED_TABLE_NAME IS NOT NULL AND TABLE_SCHEMA = ?
            ORDER BY TABLE_NAME
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, database);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String sourceTable = rs.getString("source_table");
                    String targetTable = rs.getString("target_table");
                    relationships.computeIfAbsent(sourceTable, k -> new ArrayList<>()).add(targetTable);
                }
            }
        }

        return relationships;
    }
}
