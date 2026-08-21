package com.dbaagent.service;

import com.dbaagent.model.ColumnInfo;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.DatabaseObject;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class QueryExecutorServiceTest {

    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private SchemaSnapshotService schemaSnapshotService;
    @Mock private CacheManager cacheManager;
    @Mock private CacheMetricsService cacheMetricsService;
    @Mock private QueryLineageService queryLineageService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private QueryExecutionPolicyService queryExecutionPolicyService;
    @Mock private UserDataAccessPolicyService userDataAccessPolicyService;
    @Mock private com.dbaagent.service.telemetry.TelemetryCounters telemetryCounters;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private Connection connection;
    @Mock private Statement statement;

    private QueryExecutorService queryExecutorService;

    @BeforeEach
    void setUp() {
        queryExecutorService = new QueryExecutorService(
            connectionService,
            credentialService,
            schemaSnapshotService,
            cacheManager,
            cacheMetricsService,
            queryLineageService,
            providerRegistry,
            queryExecutionPolicyService,
            userDataAccessPolicyService,
            telemetryCounters,
            keyColumnAnalysisRepository,
            new RunningQueryRegistry()
        );
    }

    @Test
    void getDatabaseObjects_prefersLatestSchemaSnapshotBeforeLiveIntrospection() throws Exception {
        DatabaseObject accounts = new DatabaseObject(
            "ACCOUNTS",
            "table",
            "analytics_db",
            List.of(new ColumnInfo("account_id", "bigint", false, true, null)),
            42L,
            null
        );

        when(cacheManager.getCache("databaseObjects")).thenReturn(null);
        when(schemaSnapshotService.getLatestDatabaseObjects("conn-1"))
            .thenReturn(List.of(accounts));

        List<DatabaseObject> objects = queryExecutorService.getDatabaseObjects("conn-1");

        assertThat(objects).singleElement().extracting(DatabaseObject::getName).isEqualTo("ACCOUNTS");
        verify(schemaSnapshotService).getLatestDatabaseObjects("conn-1");
        // The snapshot is the source of truth for the returned objects. Best-effort enrichment
        // (index/FK/inferred-key flags) may attempt to open a live connection in addition; that
        // is allowed and tolerated to fail silently.
    }

    @Test
    void executeQuery_policyBlockStopsBeforeDbConnection() throws Exception {
        ConnectionRequest request = new ConnectionRequest();
        request.setDbType("mysql");
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
        when(providerRegistry.getCanonicalName("mysql")).thenReturn("mysql");
        when(queryExecutionPolicyService.enforce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("mysql")))
            .thenThrow(QueryExecutionPolicyException.chatReadOnly("DELETE"));

        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> queryExecutorService.executeQuery(
                "conn-1",
                new QueryRequest("DELETE FROM bookings WHERE id = 1", null, null),
                QueryExecutionContext.chat()
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.CHAT_MUTATION_BLOCKED);
        verify(connectionService, never()).getConnection(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void executeQuery_writePrivilegeDenied_mapsToPolicyException() throws Exception {
        ConnectionRequest request = new ConnectionRequest();
        request.setDbType("mysql");
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
        when(providerRegistry.getCanonicalName("mysql")).thenReturn("mysql");
        when(queryExecutionPolicyService.enforce(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.eq("mysql")))
            .thenReturn(new QueryExecutionPolicyService.PolicyDecision(List.of(), true, "UPDATE"));
        when(connectionService.getConnection("conn-1", request)).thenReturn(connection);
        when(connection.createStatement()).thenReturn(statement);
        when(statement.execute("UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9"))
            .thenThrow(new SQLException("UPDATE command denied to user", "42000"));

        QueryExecutionPolicyException exception = assertThrows(
            QueryExecutionPolicyException.class,
            () -> queryExecutorService.executeQuery(
                "conn-1",
                new QueryRequest("UPDATE customers SET property_status = 'ACTIVE' WHERE customer_id = 9", null, null),
                QueryExecutionContext.editor("admin", true, true)
            )
        );

        assertThat(exception.getErrorCode()).isEqualTo(QueryExecutionPolicyException.DB_WRITE_PRIVILEGE_DENIED);
        assertThat(exception.getMessage()).contains("required write privileges");
    }
}
