package com.dbaagent.provider.postgres;

import com.dbaagent.model.*;
import com.dbaagent.provider.api.IntrospectionProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.sql.*;
import java.util.*;

/**
 * PostgreSQL implementation of IntrospectionProvider.
 * Uses pg_catalog and information_schema for schema introspection.
 */
@Slf4j
@Component
public class PostgresIntrospectionProvider implements IntrospectionProvider {

    private static final String DATABASE_TYPE = "postgres";
    private static final String DEFAULT_SCHEMA = "public";

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
        objects.addAll(getTablesAndViews(connection));
        objects.addAll(getFunctions(connection));
        objects.addAll(getProcedures(connection));
        return objects;
    }

    private List<DatabaseObject> getTablesAndViews(Connection connection) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        String query = """
            SELECT t.tablename as name, 'table' as type,
                CASE
                    WHEN s.n_live_tup > 0 THEN s.n_live_tup::bigint
                    WHEN s.n_live_tup = 0 AND c.reltuples = 0 THEN 0::bigint
                    WHEN c.reltuples >= 0 THEN c.reltuples::bigint
                    ELSE NULL
                END as row_count
            FROM pg_tables t
            JOIN pg_namespace n ON n.nspname = t.schemaname
            JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = t.tablename
            LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid
            WHERE t.schemaname = 'public' AND c.relkind IN ('r', 'p')
            UNION ALL
            SELECT v.viewname as name, 'view' as type, 0 as row_count
            FROM pg_views v WHERE v.schemaname = 'public'
            ORDER BY type, name
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                DatabaseObject obj = new DatabaseObject();
                obj.setName(rs.getString("name"));
                obj.setSchema(DEFAULT_SCHEMA);
                obj.setType(rs.getString("type"));
                Long estimatedRowCount = getNullableLong(rs, "row_count");
                obj.setRowCount("table".equals(obj.getType())
                    ? resolveTableRowCount(connection, DEFAULT_SCHEMA, obj.getName(), estimatedRowCount)
                    : estimatedRowCount);
                obj.setColumns(getTableColumns(connection, DEFAULT_SCHEMA, obj.getName()));
                objects.add(obj);
            }
        }
        return objects;
    }

    private List<DatabaseObject> getFunctions(Connection connection) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        String query = """
            SELECT p.proname as name, pg_get_functiondef(p.oid) as definition
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = 'public' AND p.prokind = 'f'
            ORDER BY p.proname
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("name"));
                    obj.setSchema(DEFAULT_SCHEMA);
                    obj.setType("function");
                    obj.setDefinition(rs.getString("definition"));
                    objects.add(obj);
                }
        }
        return objects;
    }

    private List<DatabaseObject> getProcedures(Connection connection) throws SQLException {
        List<DatabaseObject> objects = new ArrayList<>();

        String query = """
            SELECT p.proname as name, pg_get_functiondef(p.oid) as definition
            FROM pg_proc p
            JOIN pg_namespace n ON p.pronamespace = n.oid
            WHERE n.nspname = 'public' AND p.prokind = 'p'
            ORDER BY p.proname
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    DatabaseObject obj = new DatabaseObject();
                    obj.setName(rs.getString("name"));
                    obj.setSchema(DEFAULT_SCHEMA);
                    obj.setType("procedure");
                    obj.setDefinition(rs.getString("definition"));
                    objects.add(obj);
                }
        }
        return objects;
    }

    @Override
    public List<ColumnInfo> getTableColumns(Connection connection, String database, String tableName) throws SQLException {
        List<ColumnInfo> columns = new ArrayList<>();

        String query = """
            SELECT c.column_name, c.data_type, c.is_nullable, c.column_default,
                CASE WHEN pk.column_name IS NOT NULL THEN true ELSE false END as is_primary_key
            FROM information_schema.columns c
            LEFT JOIN (
                SELECT ku.column_name
                FROM information_schema.table_constraints tc
                JOIN information_schema.key_column_usage ku ON tc.constraint_name = ku.constraint_name
                WHERE tc.constraint_type = 'PRIMARY KEY' AND ku.table_name = ?
            ) pk ON c.column_name = pk.column_name
            WHERE c.table_name = ? AND c.table_schema = ?
            ORDER BY c.ordinal_position
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            stmt.setString(2, tableName);
            stmt.setString(3, DEFAULT_SCHEMA);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnInfo col = new ColumnInfo();
                    col.setName(rs.getString("column_name"));
                    col.setDataType(rs.getString("data_type"));
                    col.setNullable("YES".equals(rs.getString("is_nullable")));
                    col.setPrimaryKey(rs.getBoolean("is_primary_key"));
                    col.setDefaultValue(rs.getString("column_default"));
                    columns.add(col);
                }
            }
        }
        return columns;
    }

    @Override
    public List<TableIndex> getTableIndexes(Connection connection, String database, String tableName) throws SQLException {
        List<TableIndex> indexes = new ArrayList<>();
        Map<String, TableIndex> indexMap = new HashMap<>();

        String query = """
            SELECT
                i.relname AS index_name,
                a.attname AS column_name,
                ix.indisunique AS is_unique,
                ix.indisprimary AS is_primary,
                am.amname AS index_type
            FROM pg_class t
            JOIN pg_index ix ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            JOIN pg_am am ON i.relam = am.oid
            WHERE t.relname = ?
            ORDER BY i.relname, a.attnum
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String indexName = rs.getString("index_name");
                    String columnName = rs.getString("column_name");
                    boolean isUnique = rs.getBoolean("is_unique");
                    boolean isPrimary = rs.getBoolean("is_primary");
                    String indexType = rs.getString("index_type");

                    TableIndex index = indexMap.get(indexName);
                    if (index == null) {
                        index = new TableIndex();
                        index.setName(indexName);
                        index.setType(indexType);
                        index.setUnique(isUnique);
                        index.setPrimary(isPrimary);
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

        // One placeholder, resolved once in a CTE, instead of repeating `?::regclass`
        // in every expression. The previous form had NINE placeholders — the two
        // subtractions use two each — while the binding loop ran `i <= 7`, so
        // parameters 8 and 9 were never set and every call threw
        // `No value specified for parameter 8`. That silently broke table-growth
        // snapshots for every table on every Postgres connection
        // (TableGrowthMonitoringService logs it per table and carries on).
        //
        // Counting placeholders by hand is exactly what failed here, so the count is
        // now impossible to get wrong: bind one value and reference it by name.
        String query = """
            WITH t AS (SELECT ?::regclass AS rel)
            SELECT
                pg_size_pretty(pg_total_relation_size(rel)) as total_size,
                pg_total_relation_size(rel) as total_bytes,
                pg_size_pretty(pg_relation_size(rel)) as data_size,
                pg_relation_size(rel) as data_bytes,
                pg_size_pretty(pg_total_relation_size(rel) - pg_relation_size(rel)) as index_size,
                (pg_total_relation_size(rel) - pg_relation_size(rel)) as index_bytes,
                obj_description(rel, 'pg_class') as comment
            FROM t
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);

            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    stats.setEngine("PostgreSQL");
                    stats.setComment(rs.getString("comment"));
                    stats.setDataSize(rs.getLong("data_bytes"));
                    stats.setIndexSize(rs.getLong("index_bytes"));
                    stats.setSizeBytes(rs.getLong("total_bytes"));
                    stats.setIndexSizeBytes(rs.getLong("index_bytes"));
                    stats.setRowCount(resolveTableRowCount(
                        connection,
                        DEFAULT_SCHEMA,
                        tableName,
                        getEstimatedTableRowCount(connection, DEFAULT_SCHEMA, tableName)
                    ));
                }
            }
        }

        return stats;
    }

    @Override
    public SchemaMetadata scanSchema(Connection connection, String database) throws SQLException {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName(database);

        // Get all tables and views
        String tablesQuery = "SELECT t.tablename, 'table' as type, " +
            "pg_total_relation_size(quote_ident(t.schemaname)||'.'||quote_ident(t.tablename)) as size_bytes, " +
            "CASE " +
            "    WHEN s.n_live_tup > 0 THEN s.n_live_tup::bigint " +
            "    WHEN s.n_live_tup = 0 AND c.reltuples = 0 THEN 0::bigint " +
            "    WHEN c.reltuples >= 0 THEN c.reltuples::bigint " +
            "    ELSE NULL " +
            "END as row_count " +
            "FROM pg_tables t " +
            "JOIN pg_namespace n ON n.nspname = t.schemaname " +
            "JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = t.tablename " +
            "LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid " +
            "WHERE t.schemaname = 'public' AND c.relkind IN ('r', 'p') " +
            "UNION ALL " +
            "SELECT v.viewname as tablename, 'view' as type, 0 as size_bytes, 0 as row_count " +
            "FROM pg_views v " +
            "WHERE v.schemaname = 'public' " +
            "ORDER BY tablename";

        Map<String, TableMetadata> tableMap = new HashMap<>();

        try (Statement stmt = connection.createStatement()) {
            applyStatementSettings(stmt);
            try (ResultSet rs = stmt.executeQuery(tablesQuery)) {
                while (rs.next()) {
                    TableMetadata table = new TableMetadata();
                    table.setName(rs.getString("tablename"));
                    table.setSchema(DEFAULT_SCHEMA);
                    table.setType(rs.getString("type"));
                    table.setSizeBytes(rs.getLong("size_bytes"));
                    Long estimatedRowCount = getNullableLong(rs, "row_count");
                    table.setRowCount("table".equals(table.getType())
                        ? resolveTableRowCount(connection, DEFAULT_SCHEMA, table.getName(), estimatedRowCount)
                        : estimatedRowCount);
                    schema.getTables().add(table);
                    tableMap.put(table.getName(), table);
                }
            }
        }

        // Batch load all columns and indexes in single queries (eliminates N+1)
        scanPostgreSQLColumnsBatch(connection, tableMap);
        scanPostgreSQLIndexesBatch(connection, tableMap);

        // Get foreign key relationships
        scanPostgreSQLForeignKeys(connection, schema);

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
    private void scanPostgreSQLColumnsBatch(Connection connection, Map<String, TableMetadata> tableMap) throws SQLException {
        // Get all columns with primary key info in a single query
        String query = "SELECT c.table_name, c.column_name, c.data_type, c.character_maximum_length, " +
            "c.is_nullable, c.column_default, c.ordinal_position, " +
            "CASE WHEN pk.column_name IS NOT NULL THEN true ELSE false END as is_primary_key " +
            "FROM information_schema.columns c " +
            "LEFT JOIN ( " +
            "  SELECT tc.table_name, ku.column_name " +
            "  FROM information_schema.table_constraints tc " +
            "  JOIN information_schema.key_column_usage ku " +
            "  ON tc.constraint_name = ku.constraint_name AND tc.table_name = ku.table_name " +
            "  WHERE tc.table_schema = 'public' AND tc.constraint_type = 'PRIMARY KEY' " +
            ") pk ON c.table_name = pk.table_name AND c.column_name = pk.column_name " +
            "WHERE c.table_schema = 'public' " +
            "ORDER BY c.table_name, c.ordinal_position";

        try (Statement stmt = connection.createStatement()) {
            applyStatementSettings(stmt);
            try (ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String tableName = rs.getString("table_name");
                    TableMetadata table = tableMap.get(tableName);
                    if (table != null) {
                        ColumnMetadata column = new ColumnMetadata();
                        column.setName(rs.getString("column_name"));
                        column.setDataType(rs.getString("data_type"));
                        // Use getLong with null check for character_maximum_length (PostgreSQL returns int4)
                        Object maxLen = rs.getObject("character_maximum_length");
                        column.setMaxLength(maxLen != null ? ((Number) maxLen).longValue() : null);
                        column.setNullable("YES".equals(rs.getString("is_nullable")));
                        column.setPrimaryKey(rs.getBoolean("is_primary_key"));
                        column.setDefaultValue(rs.getString("column_default"));
                        column.setOrdinalPosition(rs.getInt("ordinal_position"));
                        table.getColumns().add(column);
                    }
                }
            }
        }
    }

    /**
     * Batch load all indexes for all tables in a single query (eliminates N+1).
     */
    private void scanPostgreSQLIndexesBatch(Connection connection, Map<String, TableMetadata> tableMap) throws SQLException {
        String query = "SELECT i.tablename, i.indexname, i.indexdef, " +
            "array_agg(a.attname ORDER BY array_position(ix.indkey, a.attnum)) as columns, " +
            "ix.indisunique " +
            "FROM pg_indexes i " +
            "JOIN pg_class c ON c.relname = i.tablename AND c.relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public') " +
            "JOIN pg_index ix ON ix.indexrelid = (SELECT oid FROM pg_class WHERE relname = i.indexname AND relnamespace = (SELECT oid FROM pg_namespace WHERE nspname = 'public')) " +
            "JOIN pg_attribute a ON a.attrelid = c.oid AND a.attnum = ANY(ix.indkey) " +
            "WHERE i.schemaname = 'public' " +
            "GROUP BY i.tablename, i.indexname, i.indexdef, ix.indisunique " +
            "ORDER BY i.tablename, i.indexname";

        try (Statement stmt = connection.createStatement()) {
            applyStatementSettings(stmt);
            try (ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    String tableName = rs.getString("tablename");
                    TableMetadata table = tableMap.get(tableName);
                    if (table != null) {
                        IndexMetadata index = new IndexMetadata();
                        index.setName(rs.getString("indexname"));
                        index.setTableName(tableName);
                        index.setUnique(rs.getBoolean("indisunique"));

                        Array columnArray = rs.getArray("columns");
                        if (columnArray != null) {
                            String[] columns = (String[]) columnArray.getArray();
                            index.setColumns(List.of(columns));
                        }

                        String indexdef = rs.getString("indexdef");
                        if (indexdef != null && indexdef.contains("USING")) {
                            String[] parts = indexdef.split("USING");
                            if (parts.length > 1) {
                                index.setIndexType(parts[1].trim().split("\\s")[0]);
                            }
                        }

                        table.getIndexes().add(index);
                    }
                }
            }
        }
    }

    private void scanPostgreSQLForeignKeys(Connection connection, SchemaMetadata schema) throws SQLException {
        String query = "SELECT tc.constraint_name, tc.table_name, " +
            "kcu.column_name, ccu.table_name AS foreign_table_name, " +
            "ccu.column_name AS foreign_column_name " +
            "FROM information_schema.table_constraints AS tc " +
            "JOIN information_schema.key_column_usage AS kcu " +
            "ON tc.constraint_name = kcu.constraint_name " +
            "JOIN information_schema.constraint_column_usage AS ccu " +
            "ON ccu.constraint_name = tc.constraint_name " +
            "WHERE tc.constraint_type = 'FOREIGN KEY' AND tc.table_schema = 'public' " +
            "ORDER BY tc.table_name, tc.constraint_name";
        
        try (Statement stmt = connection.createStatement()) {
            applyStatementSettings(stmt);
            try (ResultSet rs = stmt.executeQuery(query)) {
                while (rs.next()) {
                    RelationshipMetadata rel = new RelationshipMetadata();
                    rel.setConstraintName(rs.getString("constraint_name"));
                    rel.setFromTable(rs.getString("table_name"));
                    rel.setFromColumn(rs.getString("column_name"));
                    rel.setToTable(rs.getString("foreign_table_name"));
                    rel.setToColumn(rs.getString("foreign_column_name"));
                    rel.setRelationshipType("one-to-many");
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
                tc.constraint_name,
                tc.table_name as source_table,
                kcu.column_name as source_column,
                ccu.table_name as target_table,
                ccu.column_name as target_column
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
            JOIN information_schema.constraint_column_usage ccu
                ON tc.constraint_name = ccu.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
                AND tc.table_schema = 'public'
            ORDER BY tc.table_name, tc.constraint_name
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                RelationshipMetadata rel = new RelationshipMetadata();
                rel.setConstraintName(rs.getString("constraint_name"));
                rel.setFromTable(rs.getString("source_table"));
                rel.setFromColumn(rs.getString("source_column"));
                rel.setToTable(rs.getString("target_table"));
                rel.setToColumn(rs.getString("target_column"));
                relationships.add(rel);
            }
        }

        return relationships;
    }

    @Override
    public List<ColumnDetail> getColumnDetails(Connection connection, String database, String tableName) throws SQLException {
        List<ColumnDetail> columns = new ArrayList<>();

        String query = """
            SELECT
                column_name, ordinal_position, column_default, is_nullable,
                data_type, character_maximum_length, numeric_precision, numeric_scale,
                udt_name
            FROM information_schema.columns
            WHERE table_schema = 'public' AND table_name = ?
            ORDER BY ordinal_position
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    ColumnDetail col = ColumnDetail.builder()
                        .columnName(rs.getString("column_name"))
                        .columnDefault(rs.getString("column_default"))
                        .isNullable("YES".equals(rs.getString("is_nullable")))
                        .dataType(rs.getString("data_type"))
                        .characterMaximumLength(String.valueOf(rs.getLong("character_maximum_length")))
                        .numericPrecision(String.valueOf(rs.getInt("numeric_precision")))
                        .numericScale(String.valueOf(rs.getInt("numeric_scale")))
                        .columnType(rs.getString("udt_name"))
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
                tc.constraint_name,
                tc.constraint_type,
                kcu.column_name,
                ccu.table_name as referenced_table_name,
                ccu.column_name as referenced_column_name
            FROM information_schema.table_constraints tc
            LEFT JOIN information_schema.key_column_usage kcu
                ON tc.constraint_name = kcu.constraint_name
                AND tc.table_schema = kcu.table_schema
            LEFT JOIN information_schema.constraint_column_usage ccu
                ON tc.constraint_name = ccu.constraint_name
                AND tc.constraint_type = 'FOREIGN KEY'
            WHERE tc.table_schema = 'public' AND tc.table_name = ?
            ORDER BY tc.constraint_name
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                while (rs.next()) {
                    String constraintName = rs.getString("constraint_name");
                    String constraintType = rs.getString("constraint_type");
                    String columnName = rs.getString("column_name");

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
        return resolveTableRowCount(
            connection,
            DEFAULT_SCHEMA,
            tableName,
            getEstimatedTableRowCount(connection, DEFAULT_SCHEMA, tableName)
        );
    }

    @Override
    public List<IndexDetail> getIndexDetails(Connection connection, String database, String tableName) throws SQLException {
        List<IndexDetail> indexes = new ArrayList<>();
        Map<String, IndexDetail> indexMap = new LinkedHashMap<>();

        String query = """
            SELECT
                i.relname as index_name,
                am.amname as index_type,
                a.attname as column_name,
                ix.indisunique as is_unique,
                ix.indisprimary as is_primary,
                pg_get_indexdef(ix.indexrelid) as index_definition,
                pg_relation_size(i.oid) as index_size
            FROM pg_index ix
            JOIN pg_class t ON t.oid = ix.indrelid
            JOIN pg_class i ON i.oid = ix.indexrelid
            JOIN pg_am am ON am.oid = i.relam
            JOIN pg_attribute a ON a.attrelid = t.oid AND a.attnum = ANY(ix.indkey)
            WHERE t.relname = ?
            ORDER BY i.relname, a.attnum
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
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
                tc.constraint_name,
                kcu.column_name,
                ccu.table_name AS referenced_table,
                ccu.column_name AS referenced_column,
                rc.delete_rule as on_delete,
                rc.update_rule as on_update
            FROM information_schema.table_constraints tc
            JOIN information_schema.key_column_usage kcu ON tc.constraint_name = kcu.constraint_name
            JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name
            JOIN information_schema.referential_constraints rc ON tc.constraint_name = rc.constraint_name
            WHERE tc.table_name = ? AND tc.constraint_type = 'FOREIGN KEY'
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
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
        String query = "SELECT pg_size_pretty(pg_total_relation_size(?))";

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            stmt.setString(1, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return rs.getString(1);
                }
            }
        }

        return null;
    }

    private Long getEstimatedTableRowCount(Connection connection, String schemaName, String tableName) throws SQLException {
        String query = """
            SELECT
                CASE
                    WHEN s.n_live_tup > 0 THEN s.n_live_tup::bigint
                    WHEN s.n_live_tup = 0 AND c.reltuples = 0 THEN 0::bigint
                    WHEN c.reltuples >= 0 THEN c.reltuples::bigint
                    ELSE NULL
                END AS row_count
            FROM pg_class c
            JOIN pg_namespace n ON n.oid = c.relnamespace
            LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid
            WHERE n.nspname = ? AND c.relname = ? AND c.relkind IN ('r', 'p')
            LIMIT 1
            """;

        try (PreparedStatement stmt = connection.prepareStatement(query)) {
            applyStatementSettings(stmt);
            stmt.setString(1, schemaName);
            stmt.setString(2, tableName);
            try (ResultSet rs = stmt.executeQuery()) {
                if (rs.next()) {
                    return getNullableLong(rs, "row_count");
                }
            }
        }

        return null;
    }

    private Long resolveTableRowCount(
        Connection connection,
        String schemaName,
        String tableName,
        Long estimatedRowCount
    ) {
        if (estimatedRowCount != null) {
            return estimatedRowCount;
        }

        // Some PostgreSQL tables report unknown reltuples until analyzed. Fall back
        // to an exact COUNT(*) only for those missing estimates.
        return getExactTableRowCount(connection, schemaName, tableName);
    }

    private Long getExactTableRowCount(Connection connection, String schemaName, String tableName) {
        String qualifiedTable = quoteIdentifier(schemaName) + "." + quoteIdentifier(tableName);
        String query = "SELECT COUNT(*) AS row_count FROM " + qualifiedTable;

        try (Statement stmt = connection.createStatement()) {
            applyStatementSettings(stmt);
            try (ResultSet rs = stmt.executeQuery(query)) {
                if (rs.next()) {
                    return rs.getLong("row_count");
                }
            }
        } catch (SQLException e) {
            log.debug(
                "Could not fetch exact row count for {}.{}: {}",
                schemaName,
                tableName,
                e.getMessage()
            );
        }

        return null;
    }

    private String quoteIdentifier(String identifier) {
        return "\"" + identifier.replace("\"", "\"\"") + "\"";
    }

    private Long getNullableLong(ResultSet rs, String columnLabel) throws SQLException {
        Object value = rs.getObject(columnLabel);
        if (value == null) {
            return null;
        }
        if (value instanceof Number number) {
            return number.longValue();
        }
        return Long.parseLong(value.toString());
    }

    @Override
    public String getDefaultSchema() {
        return DEFAULT_SCHEMA;
    }

    @Override
    public List<Map<String, Object>> getAllTablesWithMetadata(Connection connection, String database) throws SQLException {
        List<Map<String, Object>> tables = new ArrayList<>();

        String query = """
            SELECT
                t.tablename as table_name,
                t.schemaname as table_schema,
                CASE
                    WHEN s.n_live_tup > 0 THEN s.n_live_tup::bigint
                    WHEN s.n_live_tup = 0 AND c.reltuples = 0 THEN 0::bigint
                    WHEN c.reltuples >= 0 THEN c.reltuples::bigint
                    ELSE NULL
                END as row_count,
                pg_size_pretty(pg_total_relation_size(quote_ident(t.schemaname) || '.' || quote_ident(t.tablename))) as table_size
            FROM pg_tables t
            JOIN pg_namespace n ON n.nspname = t.schemaname
            JOIN pg_class c ON c.relnamespace = n.oid AND c.relname = t.tablename
            LEFT JOIN pg_stat_all_tables s ON s.relid = c.oid
            WHERE t.schemaname = 'public' AND c.relkind IN ('r', 'p')
            ORDER BY t.tablename
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                Map<String, Object> tableInfo = new LinkedHashMap<>();
                String schemaName = rs.getString("table_schema");
                String tableName = rs.getString("table_name");
                Long estimatedRowCount = getNullableLong(rs, "row_count");
                tableInfo.put("tableName", rs.getString("table_name"));
                tableInfo.put("tableSchema", schemaName);
                tableInfo.put("rowCount", resolveTableRowCount(connection, schemaName, tableName, estimatedRowCount));
                tableInfo.put("tableSize", rs.getString("table_size"));
                tables.add(tableInfo);
            }
        }

        return tables;
    }

    @Override
    public Map<String, List<String>> getTableRelationships(Connection connection, String database) throws SQLException {
        Map<String, List<String>> relationships = new HashMap<>();

        String query = """
            SELECT
                tc.table_name as source_table,
                ccu.table_name AS target_table
            FROM information_schema.table_constraints tc
            JOIN information_schema.constraint_column_usage ccu ON tc.constraint_name = ccu.constraint_name
            WHERE tc.constraint_type = 'FOREIGN KEY'
            ORDER BY tc.table_name
            """;

        try (Statement stmt = connection.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {
            while (rs.next()) {
                String sourceTable = rs.getString("source_table");
                String targetTable = rs.getString("target_table");
                relationships.computeIfAbsent(sourceTable, k -> new ArrayList<>()).add(targetTable);
            }
        }

        return relationships;
    }
}
