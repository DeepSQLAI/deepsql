package com.dbaagent.service.pipeline;

import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.ExplainPlanProvider;
import com.dbaagent.service.ConnectionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SqlValidatorTest {

    @Mock private ConnectionService connectionService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private JdbcTemplate jdbcTemplate;

    private SqlValidator validator;

    @BeforeEach
    void setUp() {
        validator = new SqlValidator(connectionService, providerRegistry);
    }

    @Test
    void validSqlReturnsValidResult_postgresql() throws Exception {
        var mockDialect = mock(DatabaseDialect.class);
        var mockExplain = mock(ExplainPlanProvider.class);
        var mockDataSource = mock(javax.sql.DataSource.class);
        var mockConn = mock(java.sql.Connection.class);

        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(mockDataSource).when(jdbcTemplate).getDataSource();
        doReturn(mockConn).when(mockDataSource).getConnection();
        doReturn(mockDialect).when(providerRegistry).getDialect("POSTGRESQL");
        doReturn(mockExplain).when(mockDialect).explainPlan();
        doReturn(List.of(Map.of("Plan", Map.of())))
            .when(mockExplain).executeExplain(eq(mockConn), anyString(), eq(false));

        var result = validator.validate("conn-1", "SELECT * FROM bookings", "POSTGRESQL");

        assertThat(result.valid()).isTrue();
        assertThat(result.explainPlan()).isNotNull();
    }

    @Test
    void invalidSqlReturnsInvalidResult() throws Exception {
        var mockDialect = mock(DatabaseDialect.class);
        var mockExplain = mock(ExplainPlanProvider.class);
        var mockDataSource = mock(javax.sql.DataSource.class);
        var mockConn = mock(java.sql.Connection.class);

        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(mockDataSource).when(jdbcTemplate).getDataSource();
        doReturn(mockConn).when(mockDataSource).getConnection();
        doReturn(mockDialect).when(providerRegistry).getDialect("POSTGRESQL");
        doReturn(mockExplain).when(mockDialect).explainPlan();
        doThrow(new java.sql.SQLException("column x not found"))
            .when(mockExplain).executeExplain(eq(mockConn), anyString(), eq(false));

        var result = validator.validate("conn-1", "SELECT x FROM bookings", "POSTGRESQL");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("column x not found");
    }

    @Test
    void mysqlUsesProviderChain() throws Exception {
        var mockDialect = mock(DatabaseDialect.class);
        var mockExplain = mock(ExplainPlanProvider.class);
        var mockDataSource = mock(javax.sql.DataSource.class);
        var mockConn = mock(java.sql.Connection.class);

        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(mockDataSource).when(jdbcTemplate).getDataSource();
        doReturn(mockConn).when(mockDataSource).getConnection();
        doReturn(mockDialect).when(providerRegistry).getDialect("MYSQL");
        doReturn(mockExplain).when(mockDialect).explainPlan();
        doReturn(List.of(Map.of("query_block", Map.of())))
            .when(mockExplain).executeExplain(eq(mockConn), anyString(), eq(false));

        var result = validator.validate("conn-1", "SELECT * FROM bookings", "MYSQL");

        assertThat(result.valid()).isTrue();
    }

    @Test
    void nullSqlReturnsInvalid() {
        var result = validator.validate("conn-1", null, "POSTGRESQL");
        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("null");
    }

    @Test
    void blankSqlReturnsInvalid() {
        var result = validator.validate("conn-1", "  ", "POSTGRESQL");
        assertThat(result.valid()).isFalse();
    }

    @Test
    void insertSqlReturnsInvalidWithoutCallingDatabase() {
        var result = validator.validate("conn-1", "INSERT INTO bookings(id) VALUES (1)", "POSTGRESQL");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("read-only");
        verifyNoInteractions(connectionService, providerRegistry, jdbcTemplate);
    }

    @Test
    void explainAnalyzeReturnsInvalidWithoutCallingDatabase() {
        var result = validator.validate("conn-1", "EXPLAIN ANALYZE SELECT * FROM bookings", "POSTGRESQL");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("EXPLAIN ANALYZE");
        verifyNoInteractions(connectionService, providerRegistry, jdbcTemplate);
    }

    @Test
    void multipleStatementsReturnInvalidWithoutCallingDatabase() {
        var result = validator.validate("conn-1", "SELECT * FROM bookings; DELETE FROM bookings", "POSTGRESQL");

        assertThat(result.valid()).isFalse();
        assertThat(result.error()).contains("Multiple SQL statements");
        verifyNoInteractions(connectionService, providerRegistry, jdbcTemplate);
    }

    @Test
    void commentedSelectStillValid() throws Exception {
        var mockDialect = mock(DatabaseDialect.class);
        var mockExplain = mock(ExplainPlanProvider.class);
        var mockDataSource = mock(javax.sql.DataSource.class);
        var mockConn = mock(java.sql.Connection.class);

        doReturn(jdbcTemplate).when(connectionService).getJdbcTemplateForBackgroundJob(anyString());
        doReturn(mockDataSource).when(jdbcTemplate).getDataSource();
        doReturn(mockConn).when(mockDataSource).getConnection();
        doReturn(mockDialect).when(providerRegistry).getDialect("POSTGRESQL");
        doReturn(mockExplain).when(mockDialect).explainPlan();
        doReturn(List.of(Map.of("Plan", Map.of())))
            .when(mockExplain).executeExplain(eq(mockConn), anyString(), eq(false));

        var result = validator.validate("conn-1", "-- comment\nSELECT * FROM bookings", "POSTGRESQL");

        assertThat(result.valid()).isTrue();
    }
}
