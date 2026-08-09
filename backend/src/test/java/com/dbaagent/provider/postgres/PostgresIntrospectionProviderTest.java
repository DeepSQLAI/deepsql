package com.dbaagent.provider.postgres;

import com.dbaagent.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.*;
import java.util.List;

import org.mockito.ArgumentCaptor;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
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

    // resolveSchema() issues `SELECT current_schema()` on its own Statement before
    // any method's real query. These mocks answer that call so the fixtures below
    // keep testing what they were written to test.
    @Mock
    private Statement schemaStatement;

    @Mock
    private ResultSet schemaResultSet;

    @BeforeEach
    void setUp() throws SQLException {
        provider = new PostgresIntrospectionProvider();

        // lenient(): the pure-Java tests (getDatabaseType, getDefaultSchema, …) never
        // touch the Connection, and strict stubbing would fail them over an unused stub.
        //
        // resolveSchema() is the FIRST createStatement() caller in every method that
        // reaches the database, so returning schemaStatement first and the shared
        // statement afterwards routes each to the right place. Answering "public"
        // keeps these fixtures on the historical schema, so they go on asserting the
        // behaviour they were written for rather than the search_path change itself.
        lenient().when(connection.createStatement()).thenReturn(schemaStatement, statement);
        lenient().when(schemaStatement.executeQuery(anyString())).thenReturn(schemaResultSet);
        lenient().when(schemaResultSet.next()).thenReturn(true);
        // resolveSchema() reads current_schema(), search_path, current_user.
        // A schema that differs from the role is an ordinary resolution, so these
        // fixtures land on "public" exactly as they did before search_path support.
        lenient().when(schemaResultSet.getString(1)).thenReturn("public");
        lenient().when(schemaResultSet.getString(2)).thenReturn("\"$user\", public");
        lenient().when(schemaResultSet.getString(3)).thenReturn("app_user");
    }

    @Test
    void getDatabaseType_returnsPostgres() {
        assertEquals("postgres", provider.getDatabaseType());
    }

    @Test
    void getDatabaseObjects_returnsTables() throws SQLException {
        // schemaStatement first: resolveSchema() runs before the objects query.
        when(connection.createStatement()).thenReturn(schemaStatement, statement);
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

    /** Runs getTableColumns and returns the schema it bound (parameter 4). */
    private String schemaUsedByGetTableColumns() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        provider.getTableColumns(connection, "db", "orders");

        ArgumentCaptor<String> bound = ArgumentCaptor.forClass(String.class);
        verify(preparedStatement, atLeastOnce()).setString(eq(4), bound.capture());
        return bound.getValue();
    }

    @Test
    void schemaChosenByTheImplicitDollarUserEntryIsIgnored() throws SQLException {
        // Every Postgres ships search_path = "$user", public. That leading "$user"
        // is inert only while no schema matches the connecting role — create one and
        // current_schema() silently becomes it. Honouring that would move an
        // untouched RDS/Aurora connection off `public` onto an empty user schema and
        // report a healthy, empty brain. An operator who never configured a
        // search_path must keep the historical behaviour exactly.
        when(schemaResultSet.getString(1)).thenReturn("app_user");        // current_schema()
        when(schemaResultSet.getString(2)).thenReturn("\"$user\", public"); // untouched default
        when(schemaResultSet.getString(3)).thenReturn("app_user");        // current_user

        assertEquals("public", schemaUsedByGetTableColumns(),
            "an implicit \"$user\" match must not move introspection off public");
    }

    @Test
    void deliberatelyConfiguredSearchPathIsHonoured() throws SQLException {
        // ALTER ROLE <user> IN DATABASE <db> SET search_path = marts, public;
        when(schemaResultSet.getString(1)).thenReturn("marts");
        when(schemaResultSet.getString(3)).thenReturn("app_user");
        // lenient: the guard short-circuits on schema != current_user, so the
        // search_path is never read here. Stated anyway to describe the scenario.
        lenient().when(schemaResultSet.getString(2)).thenReturn("marts, public");

        assertEquals("marts", schemaUsedByGetTableColumns());
    }

    @Test
    void userSchemaIsHonouredWhenTheSearchPathWasSetDeliberately() throws SQLException {
        // "$user" present but the path is NOT the shipped default — that is a stated
        // intent, so it is honoured rather than second-guessed.
        when(schemaResultSet.getString(1)).thenReturn("app_user");
        when(schemaResultSet.getString(2)).thenReturn("\"$user\", marts");
        when(schemaResultSet.getString(3)).thenReturn("app_user");

        assertEquals("app_user", schemaUsedByGetTableColumns());
    }

    @Test
    void nullCurrentSchemaFallsBackToPublic() throws SQLException {
        // search_path naming only missing schemas makes current_schema() NULL.
        when(schemaResultSet.getString(1)).thenReturn(null);

        assertEquals("public", schemaUsedByGetTableColumns());
    }

    @Test
    void getTableColumns_qualifiesThePrimaryKeySubqueryBySchema() throws SQLException {
        // Postgres auto-names primary keys "<table>_pkey", so joining
        // tc.constraint_name = ku.constraint_name WITHOUT a schema predicate
        // cross-joins any two schemas holding a same-named table. Measured against
        // two `orders` tables (s_a PK `other`, s_b PK `name`), asking for s_b:
        //     name|t  name|t  other|t  other|t
        // — every column a primary key, and each row duplicated. Schema-qualified
        // it returns name|t, other|f.
        //
        // A mocked ResultSet cannot exercise SQL semantics, so this asserts the
        // predicates are present and every placeholder is bound — enough to stop
        // the qualification being dropped again.
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(resultSet.next()).thenReturn(false);

        provider.getTableColumns(connection, "public", "orders");

        verify(connection).prepareStatement(sqlCaptor.capture());
        String sql = sqlCaptor.getValue();

        assertTrue(sql.contains("tc.table_schema = ku.table_schema"),
            "the constraint join must be schema-qualified, or <table>_pkey collides across schemas");
        assertTrue(sql.contains("ku.table_schema = ?"),
            "the PK lookup must be restricted to the target schema");

        int placeholders = (int) sql.chars().filter(c -> c == '?').count();
        verify(preparedStatement, times(placeholders)).setString(anyInt(), anyString());
    }

    @Test
    void getTableStats_bindsEveryPlaceholderInTheStatsQuery() throws SQLException {
        // The stats query carried NINE `?` placeholders (the two size subtractions use
        // two each) while the binding loop ran `i <= 7`, so parameters 8 and 9 were
        // never set and Postgres rejected every call with
        //   No value specified for parameter 8
        // silently killing table-growth snapshots for every table.
        //
        // getTableStats_returnsStats above passed throughout, because a mocked
        // PreparedStatement does not enforce that placeholders are bound. This test
        // compares the two directly, so the count can never drift again.
        PreparedStatement rowCountStatement = mock(PreparedStatement.class);
        ResultSet rowCountResultSet = mock(ResultSet.class);
        ArgumentCaptor<String> sqlCaptor = ArgumentCaptor.forClass(String.class);

        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement, rowCountStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);
        when(rowCountStatement.executeQuery()).thenReturn(rowCountResultSet);
        when(resultSet.next()).thenReturn(true);
        when(rowCountResultSet.next()).thenReturn(true);
        when(rowCountResultSet.getObject("row_count")).thenReturn(1000L);

        provider.getTableStats(connection, "public", "users");

        verify(connection, atLeastOnce()).prepareStatement(sqlCaptor.capture());
        String statsQuery = sqlCaptor.getAllValues().get(0);
        int placeholders = (int) statsQuery.chars().filter(c -> c == '?').count();

        assertTrue(placeholders > 0, "stats query should still be parameterised");
        verify(preparedStatement, times(placeholders)).setString(anyInt(), eq("users"));
    }

    @Test
    void scanSchema_returnsSchemaMetadata() throws SQLException {
        // schemaStatement first: resolveSchema() runs before the tables query.
        when(connection.createStatement()).thenReturn(schemaStatement, statement);
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
            schemaStatement,   // resolveSchema() runs before the tables query
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
