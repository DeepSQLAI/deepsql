package com.dbaagent.provider.postgres;

import com.dbaagent.model.SlowQuery;
import org.junit.jupiter.api.Test;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Tests for PostgresSlowQueryProvider's pg_stat_statements default path.
 *
 * This verifies that:
 * - pg_stat_statements is the default data source for Postgres slow queries
 * - Threshold filtering works correctly (queries below threshold are excluded)
 * - The provider reports "postgres" as its database type
 * - Extension availability check works correctly
 */
class PostgresSlowQueryProviderDefaultPathTest {

    private final PostgresSlowQueryProvider provider = new PostgresSlowQueryProvider();

    @Test
    void databaseType_isPostgres() {
        assertEquals("postgres", provider.getDatabaseType(),
            "PostgresSlowQueryProvider should identify as 'postgres'");
    }

    @Test
    void collectSlowQueries_returnsEmptyWhenExtensionNotAvailable() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRs = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(false); // Extension not available

        List<SlowQuery> result = provider.collectSlowQueries(mockConn, "testdb", 100.0, 10);

        assertTrue(result.isEmpty(),
            "Should return empty list when pg_stat_statements extension is not available");
    }

    @Test
    void collectSlowQueries_respectsThresholdParameter() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        PreparedStatement mockPreparedStmt = mock(PreparedStatement.class);
        ResultSet mockExtensionRs = mock(ResultSet.class);
        ResultSet mockTrackSizeRs = mock(ResultSet.class);
        ResultSet mockQueryRs = mock(ResultSet.class);

        // Extension check
        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockExtensionRs);
        when(mockExtensionRs.next()).thenReturn(true);
        when(mockExtensionRs.getBoolean(1)).thenReturn(true); // Extension available

        // Track size check
        when(mockConn.prepareStatement(contains("track_activity_query_size"))).thenReturn(mockPreparedStmt);
        when(mockPreparedStmt.executeQuery()).thenReturn(mockTrackSizeRs);
        when(mockTrackSizeRs.next()).thenReturn(true);
        when(mockTrackSizeRs.getString(1)).thenReturn("1024");

        // Main query - set up to capture the threshold value
        PreparedStatement mockMainStmt = mock(PreparedStatement.class);
        when(mockConn.prepareStatement(contains("pg_stat_statements"))).thenReturn(mockMainStmt);
        when(mockMainStmt.executeQuery()).thenReturn(mockQueryRs);
        when(mockQueryRs.next()).thenReturn(false); // No results for simplicity

        // Call with threshold of 10ms
        double thresholdMs = 10.0;
        provider.collectSlowQueries(mockConn, "testdb", thresholdMs, 10);

        // Verify threshold was set on the prepared statement
        verify(mockMainStmt).setDouble(eq(2), eq(thresholdMs));
    }

    @Test
    void isSlowQueryMonitoringAvailable_returnsTrueWhenExtensionExists() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRs = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(true);

        assertTrue(provider.isSlowQueryMonitoringAvailable(mockConn),
            "Should report monitoring available when pg_stat_statements extension exists");
    }

    @Test
    void isSlowQueryMonitoringAvailable_returnsFalseWhenExtensionMissing() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRs = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(false);

        assertFalse(provider.isSlowQueryMonitoringAvailable(mockConn),
            "Should report monitoring unavailable when pg_stat_statements extension is missing");
    }

    @Test
    void getAvailableSources_returnsPgStatStatementsWhenAvailable() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRs = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(true);

        List<String> sources = provider.getAvailableSources(mockConn);

        assertEquals(1, sources.size());
        assertEquals("pg_stat_statements", sources.get(0),
            "pg_stat_statements should be the only available source for Postgres");
    }

    @Test
    void getAvailableSources_returnsEmptyWhenExtensionMissing() throws Exception {
        Connection mockConn = mock(Connection.class);
        Statement mockStmt = mock(Statement.class);
        ResultSet mockRs = mock(ResultSet.class);

        when(mockConn.createStatement()).thenReturn(mockStmt);
        when(mockStmt.executeQuery(anyString())).thenReturn(mockRs);
        when(mockRs.next()).thenReturn(true);
        when(mockRs.getBoolean(1)).thenReturn(false);

        List<String> sources = provider.getAvailableSources(mockConn);

        assertTrue(sources.isEmpty(),
            "Should return empty list when pg_stat_statements is not available");
    }
}
