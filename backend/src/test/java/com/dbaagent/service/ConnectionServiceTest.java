package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ConnectionTestResult;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.provider.DatabaseProviderRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("ConnectionService Unit Tests")
class ConnectionServiceTest {

    @Mock
    private SshTunnelService sshTunnelService;
    @Mock
    private CredentialService credentialService;
    @Mock
    private DatabaseProviderRegistry providerRegistry;

    private ConnectionService connectionService;

    @BeforeEach
    void setUp() {
        connectionService = new ConnectionService(sshTunnelService, credentialService, providerRegistry,
                new DatabaseHostGuard(new DatabaseHostGuardProperties()));
    }

    // ─── testConnection ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("testConnection")
    class TestConnection {

        @Test
        @DisplayName("returns false when SSH test fails — no tunnel established")
        void returnsFalse_whenSshTestFails() {
            ConnectionRequest request = new ConnectionRequest();
            request.setSshEnabled(true);
            request.setSshHost("bastion.example.com");
            request.setSshPort(22);

            when(sshTunnelService.testSshConnection(request)).thenReturn(false);

            boolean result = connectionService.testConnection(request);

            assertThat(result).isFalse();
            verify(sshTunnelService).testSshConnection(request);
            verify(sshTunnelService, never()).establishTunnel(anyString(), any());
        }

        @Test
        @DisplayName("establishes tunnel when SSH test succeeds before attempting DB connection")
        void establishesTunnel_whenSshSucceeds() {
            ConnectionRequest request = new ConnectionRequest();
            request.setSshEnabled(true);
            request.setSshHost("bastion.example.com");
            request.setSshPort(22);
            request.setDbType("postgresql");
            request.setHost("db.internal");
            request.setPort(5432);

            when(sshTunnelService.testSshConnection(request)).thenReturn(true);
            when(sshTunnelService.establishTunnel(anyString(), eq(request))).thenReturn(54321);
            when(providerRegistry.getDialect("postgresql")).thenThrow(new RuntimeException("no real DB"));

            // The method will reach the DB connection attempt and fail, but we verify tunnel was opened
            boolean result = connectionService.testConnection(request);

            assertThat(result).isFalse(); // fails at JDBC level — expected in unit test
            verify(sshTunnelService).establishTunnel(anyString(), eq(request));
            verify(sshTunnelService).closeTunnel(anyString()); // tunnel cleaned up in finally
        }
    }

    // ─── testConnectionWithPrivileges ────────────────────────────────────────

    @Nested
    @DisplayName("testConnectionWithPrivileges")
    class TestConnectionWithPrivileges {

        @Test
        @DisplayName("returns SSH failure result immediately when SSH test returns false")
        void returnsSshFailure_whenSshFails() {
            ConnectionRequest request = new ConnectionRequest();
            request.setSshEnabled(true);

            when(sshTunnelService.testSshConnection(request)).thenReturn(false);

            ConnectionTestResult result = connectionService.testConnectionWithPrivileges(request);

            assertThat(result.isSuccess()).isFalse();
            assertThat(result.isSshTunnelSuccessful()).isFalse();
            assertThat(result.isConnectionSuccessful()).isFalse();
            assertThat(result.getErrorMessage()).containsIgnoringCase("SSH");
            assertThat(result.getPrivilegeChecks()).isEmpty();
            verify(sshTunnelService, never()).establishTunnel(anyString(), any());
        }

        @Test
        @DisplayName("attempts tunnel establishment when SSH test succeeds")
        void establishesTunnel_whenSshSucceeds() {
            ConnectionRequest request = new ConnectionRequest();
            request.setSshEnabled(true);
            request.setDbType("postgresql");

            when(sshTunnelService.testSshConnection(request)).thenReturn(true);
            when(sshTunnelService.establishTunnel(anyString(), eq(request))).thenReturn(54321);
            when(providerRegistry.getDialect("postgresql")).thenThrow(new RuntimeException("no real DB"));

            ConnectionTestResult result = connectionService.testConnectionWithPrivileges(request);

            assertThat(result.isSuccess()).isFalse();
            verify(sshTunnelService).establishTunnel(anyString(), eq(request));
            verify(sshTunnelService).closeTunnel(anyString());
        }
    }

    // ─── isDataSamplingEnabled ───────────────────────────────────────────────

    @Nested
    @DisplayName("isDataSamplingEnabled")
    class IsDataSamplingEnabled {

        @Test
        @DisplayName("returns true when enableDataSampling is null (legacy default)")
        void returnsTrue_whenFieldIsNull() {
            DatabaseConnection entity = new DatabaseConnection();
            entity.setEnableDataSampling(null);
            when(credentialService.getConnectionEntity("conn-1")).thenReturn(entity);

            assertThat(connectionService.isDataSamplingEnabled("conn-1")).isTrue();
        }

        @Test
        @DisplayName("returns true when explicitly set to true")
        void returnsTrue_whenExplicitlyEnabled() {
            DatabaseConnection entity = new DatabaseConnection();
            entity.setEnableDataSampling(true);
            when(credentialService.getConnectionEntity("conn-1")).thenReturn(entity);

            assertThat(connectionService.isDataSamplingEnabled("conn-1")).isTrue();
        }

        @Test
        @DisplayName("returns false when explicitly disabled")
        void returnsFalse_whenDisabled() {
            DatabaseConnection entity = new DatabaseConnection();
            entity.setEnableDataSampling(false);
            when(credentialService.getConnectionEntity("conn-1")).thenReturn(entity);

            assertThat(connectionService.isDataSamplingEnabled("conn-1")).isFalse();
        }

        @Test
        @DisplayName("returns false (fail-closed) when credential lookup throws")
        void returnsFalse_whenLookupThrows() {
            when(credentialService.getConnectionEntity("conn-bad"))
                .thenThrow(new RuntimeException("connection not found"));

            assertThat(connectionService.isDataSamplingEnabled("conn-bad")).isFalse();
        }
    }

    // ─── getDbType ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("getDbType")
    class GetDbType {

        @Test
        @DisplayName("returns dbType from decrypted connection")
        void returnsDbType_fromCredentialService() {
            ConnectionRequest request = new ConnectionRequest();
            request.setDbType("postgresql");
            when(credentialService.getDecryptedConnection("conn-pg")).thenReturn(request);

            assertThat(connectionService.getDbType("conn-pg")).isEqualTo("postgresql");
        }

        @Test
        @DisplayName("returns mysql type for MySQL connections")
        void returnsMySQL_forMySqlConnections() {
            ConnectionRequest request = new ConnectionRequest();
            request.setDbType("mysql");
            when(credentialService.getDecryptedConnection("conn-mysql")).thenReturn(request);

            assertThat(connectionService.getDbType("conn-mysql")).isEqualTo("mysql");
        }
    }

    // ─── pool lifecycle ───────────────────────────────────────────────────────

    @Nested
    @DisplayName("connection pool lifecycle")
    class PoolLifecycle {

        @Test
        @DisplayName("closeConnectionPool always closes the SSH tunnel for the connection")
        void closePool_closesTunnelEvenWhenPoolNotPresent() {
            connectionService.closeConnectionPool("conn-1");

            verify(sshTunnelService).closeTunnel("conn-1");
        }

        @Test
        @DisplayName("closeAllConnectionPools calls closeAllTunnels on SshTunnelService")
        void closeAll_closesAllSshTunnels() {
            connectionService.closeAllConnectionPools();

            verify(sshTunnelService).closeAllTunnels();
        }
    }
}
