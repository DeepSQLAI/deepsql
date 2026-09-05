package com.dbaagent.service.migration;

import com.dbaagent.dto.MigrationRiskReport;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.postgres.PostgresMigrationRiskProvider;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.security.AccessControlService;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class MigrationRiskServiceTest {

    @Test
    void unparseableSqlFailsClosedWithoutTouchingTheDatabase() {
        var access = mock(AccessControlService.class);
        var credentials = mock(CredentialService.class);
        var connections = mock(ConnectionService.class);
        var registry = mock(DatabaseProviderRegistry.class);

        var service = new MigrationRiskService(new DdlStatementParser(), registry,
                credentials, connections, access);

        MigrationRiskReport r = service.analyze("conn-1", "this is not sql");

        assertThat(r.verdict()).isEqualTo("UNKNOWN");
        assertThat(r.safeToRun()).isFalse();
        verify(access).assertCanReadConnectionContent("conn-1");
        verifyNoInteractions(connections);   // never opens a session for garbage input
    }

    @Test
    void authorizationIsAssertedBeforeAnyWork() {
        var access = mock(AccessControlService.class);
        doThrow(new RuntimeException("denied")).when(access).assertCanReadConnectionContent(anyString());
        var service = new MigrationRiskService(new DdlStatementParser(),
                mock(DatabaseProviderRegistry.class), mock(CredentialService.class),
                mock(ConnectionService.class), access);

        org.assertj.core.api.Assertions
            .assertThatThrownBy(() -> service.analyze("conn-1", "ALTER TABLE t ADD COLUMN c text"))
            .hasMessageContaining("denied");
    }
}
