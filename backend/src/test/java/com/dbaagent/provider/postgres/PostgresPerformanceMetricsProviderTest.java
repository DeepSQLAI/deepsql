package com.dbaagent.provider.postgres;

import com.dbaagent.model.ActiveQuery;
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
class PostgresPerformanceMetricsProviderTest {

    private PostgresPerformanceMetricsProvider provider;

    @Mock
    private Connection connection;

    @Mock
    private PreparedStatement preparedStatement;

    @Mock
    private Statement statement;

    @Mock
    private ResultSet resultSet;

    @BeforeEach
    void setUp() {
        provider = new PostgresPerformanceMetricsProvider();
    }

    @Test
    void getDatabaseType_returnsPostgres() {
        assertEquals("postgres", provider.getDatabaseType());
    }

    @Test
    void getActiveQueries_returnsQueries() throws SQLException {
        when(connection.prepareStatement(anyString())).thenReturn(preparedStatement);
        when(preparedStatement.executeQuery()).thenReturn(resultSet);

        when(resultSet.next())
            .thenReturn(true)
            .thenReturn(false);

        when(resultSet.getString("pid")).thenReturn("12345");
        when(resultSet.getString("user")).thenReturn("postgres");
        when(resultSet.getString("database")).thenReturn("testdb");
        when(resultSet.getString("application_name")).thenReturn("psql");
        when(resultSet.getString("client_address")).thenReturn("127.0.0.1");
        when(resultSet.getObject("client_port")).thenReturn(54321);
        when(resultSet.getInt("client_port")).thenReturn(54321);
        when(resultSet.getString("state")).thenReturn("active");
        when(resultSet.getString("query_text")).thenReturn("SELECT * FROM users");
        when(resultSet.getTimestamp("query_start")).thenReturn(null);
        when(resultSet.getTimestamp("state_change")).thenReturn(null);
        when(resultSet.getTimestamp("backend_start")).thenReturn(null);
        when(resultSet.getTimestamp("transaction_start")).thenReturn(null);
        when(resultSet.getLong("duration_seconds")).thenReturn(5L);
        when(resultSet.getLong("transaction_duration_seconds")).thenReturn(10L);
        when(resultSet.getString("wait_event")).thenReturn(null);
        when(resultSet.getString("wait_event_type")).thenReturn(null);

        List<ActiveQuery> queries = provider.getActiveQueries(connection, "testdb");

        assertNotNull(queries);
        assertEquals(1, queries.size());

        ActiveQuery query = queries.get(0);
        assertEquals("12345", query.getPid());
        assertEquals("postgres", query.getUser());
        assertEquals("testdb", query.getDatabase());
        assertEquals("SELECT * FROM users", query.getQueryText());
        assertEquals("active", query.getState());
        assertEquals("psql", query.getApplicationName());
        assertEquals(5L, query.getDurationSeconds());
        assertEquals("SELECT", query.getQueryType());
    }

    @Test
    void getCacheHitRatio_returnsRatio() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        when(resultSet.next()).thenReturn(true);
        when(resultSet.getDouble("cache_hit_ratio")).thenReturn(0.95);

        Double ratio = provider.getCacheHitRatio(connection);

        assertEquals(0.95, ratio, 0.001);
    }

    @Test
    void getCacheHitRatio_returnsDefaultWhenNoData() throws SQLException {
        when(connection.createStatement()).thenReturn(statement);
        when(statement.executeQuery(anyString())).thenReturn(resultSet);

        when(resultSet.next()).thenReturn(false);

        Double ratio = provider.getCacheHitRatio(connection);

        assertEquals(1.0, ratio, 0.001);
    }
}
