package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.service.security.ConnectionAccessService;
import com.dbaagent.service.telemetry.TelemetryClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class CredentialServiceTelemetryTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private EncryptionService encryptionService;
    @Mock private ConnectionAccessService connectionAccessService;
    @Mock private TelemetryClient telemetryClient;

    private CredentialService service;

    @BeforeEach
    void setup() {
        service = new CredentialService(credentialRepository, encryptionService,
                connectionAccessService, telemetryClient);
        when(encryptionService.encrypt(any(), anyString())).thenReturn(new byte[]{1, 2, 3});
        when(credentialRepository.save(any(DatabaseConnection.class)))
                .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    @SuppressWarnings("unchecked")
    void emitsConnectionCreatedEvent() {
        ConnectionRequest request = new ConnectionRequest();
        request.setConnectionName("test");
        request.setDbType("postgres");
        request.setHost("h");
        request.setPort(5432);
        request.setDatabase("d");
        request.setUsername("u");
        request.setPassword("p");
        request.setSshEnabled(false);

        service.saveConnection(request, "alice");

        ArgumentCaptor<Map<String, Object>> captor = ArgumentCaptor.forClass(Map.class);
        verify(telemetryClient).capture(eq("connection.created"), captor.capture());
        Map<String, Object> props = captor.getValue();
        assertEquals("postgres", props.get("db_dialect"));
        assertEquals(false, props.get("ssh_enabled"));
    }

    @Test
    void emitsConnectionDeletedEvent() {
        String id = "conn-1";
        DatabaseConnection existing = new DatabaseConnection();
        existing.setId(id);
        existing.setDbType("mysql");
        when(credentialRepository.findById(id)).thenReturn(Optional.of(existing));

        service.deleteConnection(id);

        verify(telemetryClient).capture(eq("connection.deleted"), argThat(props ->
                "mysql".equals(((Map<?, ?>) props).get("db_dialect"))));
    }

    @Test
    void includesCloudProviderWhenPresent() {
        ConnectionRequest request = baseRequest();
        request.setCloudProvider("AWS");

        service.saveConnection(request, "alice");

        verify(telemetryClient).capture(eq("connection.created"), argThat(props ->
                "aws".equals(((Map<?, ?>) props).get("cloud_provider"))));
    }

    @Test
    void omitsCloudProviderWhenBlank() {
        ConnectionRequest request = baseRequest();
        request.setCloudProvider("   ");

        service.saveConnection(request, "alice");

        verify(telemetryClient).capture(eq("connection.created"), argThat(props ->
                !((Map<?, ?>) props).containsKey("cloud_provider")));
    }

    @Test
    void deferEmitUntilAfterCommit_whenTransactionActive() {
        // Simulate being inside a Spring-managed transaction. Until the test
        // calls triggerAfterCommit(), no capture() should reach the client.
        TransactionSynchronizationManager.initSynchronization();
        try {
            ConnectionRequest request = baseRequest();
            service.saveConnection(request, "alice");

            verifyNoInteractions(telemetryClient);

            TransactionSynchronizationManager.getSynchronizations()
                    .forEach(s -> s.afterCommit());

            verify(telemetryClient).capture(eq("connection.created"), any());
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    @Test
    void suppressEmitOnRollback() {
        TransactionSynchronizationManager.initSynchronization();
        try {
            ConnectionRequest request = baseRequest();
            service.saveConnection(request, "alice");

            // Rollback: afterCompletion(STATUS_ROLLED_BACK) is called instead of afterCommit
            TransactionSynchronizationManager.getSynchronizations().forEach(s ->
                    s.afterCompletion(org.springframework.transaction.support.TransactionSynchronization.STATUS_ROLLED_BACK));

            verifyNoInteractions(telemetryClient);
        } finally {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    private ConnectionRequest baseRequest() {
        ConnectionRequest request = new ConnectionRequest();
        request.setConnectionName("test");
        request.setDbType("postgres");
        request.setHost("h");
        request.setPort(5432);
        request.setDatabase("d");
        request.setUsername("u");
        request.setPassword("p");
        request.setSshEnabled(false);
        return request;
    }
}
