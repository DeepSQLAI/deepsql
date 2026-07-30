package com.dbaagent.provider.postgres;

import com.dbaagent.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.*;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostgresIntrospectionProviderTest {

    private PostgresIntrospectionProvider provider;

    @Mock
    private Connection connection;

    @Mock
    private Statement statement;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private ResultSet resultSet;

    @BeforeEach
    void setUp() {
        provider = new PostgresIntrospectionProvider();
    }

    @Test
    void getDatabaseType_returnsPostgres() {
        assertEquals("postgres", provider.getDatabaseType());
    }

    @Test
    void getDatabaseObjects_returnsTables() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        // First call returns one table, second call returns false
        when(resultSet.next())
            .thenReturn(true)   // First table
            .thenReturn(false)  // End of tables
            .thenReturn(false)  // No columns
            .thenReturn(false)  // No functions
            .thenReturn(false); // No procedures

        when(resultSet.getString("name")).thenReturn("users");
        when(resultSet.getString("type")).thenReturn("table");
        when(resultSet.getObject("row_count")).thenReturn(100L);

        List<DatabaseObject> objects = provider.getDatabaseObjects(connection, "test_db");

        assertNotNull(objects);
        assertEquals(1, objects.size());
        assertEquals("users", objects.get(0).getName());
        assertEquals("table", objects.get(0).getType());
    }

    @Test
    void getTableColumns_returnsColumns() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        when(resultSet.next())
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(false);

        when(resultSet.getString("column_name"))
            .thenReturn("id")
            .thenReturn("name");
        when(resultSet.getString("data_type"))
            .thenReturn("integer")
            .thenReturn("character varying");
        when(resultSet.getString("is_nullable"))
            .thenReturn("NO")
            .thenReturn("YES");
        when(resultSet.getBoolean("is_primary_key"))
            .thenReturn(true)
            .thenReturn(false);
        when(resultSet.getString("column_default"))
            .thenReturn("nextval('users_id_seq')")
            .thenReturn(null);

        List<ColumnInfo> columns = provider.getTableColumns(connection, "public", "users");

        assertNotNull(columns);
        assertEquals(2, columns.size());

        assertEquals("id", columns.get(0).getName());
        assertEquals("integer", columns.get(0).getDataType());
        assertFalse(columns.get(0).getNullable());
        assertTrue(columns.get(0).getPrimaryKey());

        assertEquals("name", columns.get(1).getName());
        assertEquals("character varying", columns.get(1).getDataType());
        assertTrue(columns.get(1).getNullable());
        assertFalse(columns.get(1).getPrimaryKey());
    }

    @Test
    void getTableIndexes_returnsIndexes() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        when(resultSet.next())
            .thenReturn(true)
            .thenReturn(true)
            .thenReturn(false);

        when(resultSet.getString("index_name"))
            .thenReturn("users_pkey")
            .thenReturn("users_pkey");
        when(resultSet.getString("column_name"))
            .thenReturn("id")
            .thenReturn("id");
        when(resultSet.getBoolean("is_unique"))
            .thenReturn(true);
        when(resultSet.getBoolean("is_primary"))
            .thenReturn(true);
        when(resultSet.getString("index_type"))
            .thenReturn("btree");

        List<TableIndex> indexes = provider.getTableIndexes(connection, "public", "users");

        assertNotNull(indexes);
        assertEquals(1, indexes.size());
        assertEquals("users_pkey", indexes.get(0).getName());
        assertTrue(indexes.get(0).isUnique());
        assertTrue(indexes.get(0).isPrimary());
        assertEquals("btree", indexes.get(0).getType());
    }

    @Test
    void getTableStats_returnsStats() throws SQLException {
        PreparedStatement rowCountStatement = mock(PreparedStatement.class);
        ResultSet rowCountResultSet = mock(ResultSet.class);

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement, rowCountStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(rowCountStatement.executeQuery()).thenReturn(rowCountResultSet);

        when(resultSet.next()).thenReturn(true);
        when(resultSet.getString("comment")).thenReturn("User accounts table");
        when(resultSet.getLong("data_bytes")).thenReturn(81920L);
        when(resultSet.getLong("index_bytes")).thenReturn(40960L);
        when(resultSet.getLong("total_bytes")).thenReturn(122880L);
        when(rowCountResultSet.next()).thenReturn(true);
        when(rowCountResultSet.getObject("row_count")).thenReturn(1000L);

        TableStats stats = provider.getTableStats(connection, "public", "users");

        assertNotNull(stats);
        assertEquals("users", stats.getTableName());
        assertEquals("PostgreSQL", stats.getEngine());
        assertEquals(1000L, stats.getRowCount());
        assertEquals(81920L, stats.getDataSize());
        assertEquals(40960L, stats.getIndexSize());
        assertEquals(122880L, stats.getSizeBytes());
    }

    @Test
    void scanSchema_returnsSchemaMetadata() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        when(resultSet.next())
            .thenReturn(true)
            .thenReturn(false);

        when(resultSet.getString("tablename")).thenReturn("users");
        when(resultSet.getString("type")).thenReturn("table");
        when(resultSet.getObject("row_count")).thenReturn(100L);
        when(resultSet.getLong("size_bytes")).thenReturn(8192L);

        SchemaMetadata schema = provider.scanSchema(connection, "test_db");

        assertNotNull(schema);
        assertEquals("test_db", schema.getDatabaseName());
        assertNotNull(schema.getTables());
        assertEquals(1, schema.getTables().size());
        assertEquals("users", schema.getTables().get(0).getName());
    }

    @Test
    void getForeignKeys_returnsRelationships() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        when(resultSet.next())
            .thenReturn(true)
            .thenReturn(false);

        when(resultSet.getString("constraint_name")).thenReturn("fk_orders_user");
        when(resultSet.getString("source_table")).thenReturn("orders");
        when(resultSet.getString("source_column")).thenReturn("user_id");
        when(resultSet.getString("target_table")).thenReturn("users");
        when(resultSet.getString("target_column")).thenReturn("id");

        List<RelationshipMetadata> relationships = provider.getForeignKeys(connection, "test_db");

        assertNotNull(relationships);
        assertEquals(1, relationships.size());
        assertEquals("fk_orders_user", relationships.get(0).getConstraintName());
        assertEquals("orders", relationships.get(0).getFromTable());
        assertEquals("user_id", relationships.get(0).getFromColumn());
        assertEquals("users", relationships.get(0).getToTable());
        assertEquals("id", relationships.get(0).getToColumn());
    }

    @Test
    void getTableRowCount_returnsCount() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        when(resultSet.next()).thenReturn(true);
        when(resultSet.getObject("row_count")).thenReturn(500L);

        Long count = provider.getTableRowCount(connection, "public", "users");

        assertEquals(500L, count);
    }

    @Test
    void scanSchema_fallsBackToExactCountWhenEstimateMissing() throws SQLException {
        Statement exactCountStatement = mock(Statement.class);
        Statement columnsStatement = mock(Statement.class);
        Statement indexesStatement = mock(Statement.class);
        Statement foreignKeysStatement = mock(Statement.class);
        ResultSet exactCountResultSet = mock(ResultSet.class);
        ResultSet columnsResultSet = mock(ResultSet.class);
        ResultSet indexesResultSet = mock(ResultSet.class);
        ResultSet foreignKeysResultSet = mock(ResultSet.class);

        when(connection.createStatement()).thenReturn(
            statement,
            exactCountStatement,
            columnsStatement,
            indexesStatement,
            foreignKeysStatement
        );
        when(statement.executeQuery(anyString())).thenReturn(resultSet);
        when(exactCountStatement.executeQuery(anyString())).thenReturn(exactCountResultSet);
        when(columnsStatement.executeQuery(anyString())).thenReturn(columnsResultSet);
        when(indexesStatement.executeQuery(anyString())).thenReturn(indexesResultSet);
        when(foreignKeysStatement.executeQuery(anyString())).thenReturn(foreignKeysResultSet);

        when(resultSet.next()).thenReturn(true).thenReturn(false);
        when(resultSet.getString("tablename")).thenReturn("dim_route");
        when(resultSet.getString("type")).thenReturn("table");
        when(resultSet.getObject("row_count")).thenReturn(null);
        when(resultSet.getLong("size_bytes")).thenReturn(8192L);

        when(exactCountResultSet.next()).thenReturn(true);
        when(exactCountResultSet.getLong("row_count")).thenReturn(40L);

        when(columnsResultSet.next()).thenReturn(false);
        when(indexesResultSet.next()).thenReturn(false);
        when(foreignKeysResultSet.next()).thenReturn(false);

        SchemaMetadata schema = provider.scanSchema(connection, "test_db");

        assertNotNull(schema);
        assertEquals(1, schema.getTables().size());
        assertEquals(40L, schema.getTables().get(0).getRowCount());
    }

    @Test
    void getColumnDetails_returnsDetails() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        when(resultSet.next())
            .thenReturn(true)
            .thenReturn(false);

        when(resultSet.getString("column_name")).thenReturn("email");
        when(resultSet.getString("column_default")).thenReturn(null);
        when(resultSet.getString("is_nullable")).thenReturn("NO");
        when(resultSet.getString("data_type")).thenReturn("character varying");
        when(resultSet.getLong("character_maximum_length")).thenReturn(255L);
        when(resultSet.getInt("numeric_precision")).thenReturn(0);
        when(resultSet.getInt("numeric_scale")).thenReturn(0);
        when(resultSet.getString("udt_name")).thenReturn("varchar");

        List<ColumnDetail> details = provider.getColumnDetails(connection, "public", "users");

        assertNotNull(details);
        assertEquals(1, details.size());
        assertEquals("email", details.get(0).getColumnName());
        assertEquals("character varying", details.get(0).getDataType());
        assertFalse(details.get(0).getIsNullable());
    }
}
