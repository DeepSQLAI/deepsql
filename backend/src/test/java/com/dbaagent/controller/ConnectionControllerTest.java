package com.dbaagent.controller;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.ConnectionTestResult;
import com.dbaagent.repository.ConnectionInitHistoryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.ConnectionAccessGrantRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.scheduler.BrainInitSchedulerService;
import com.dbaagent.service.scheduler.BrainJobsService;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.security.ConnectionAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionControllerTest {
    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private BrainInitSchedulerService brainInitSchedulerService;
    @Mock private ConnectionInitStatusRepository connectionInitStatusRepository;
    @Mock private ConnectionInitHistoryRepository connectionInitHistoryRepository;
    @Mock private TrainingJobService trainingJobService;
    @Mock private SchemaDocumentationRepository schemaDocumentationRepository;
    @Mock private BrainJobsService brainJobsService;
    @Mock private AccessControlService accessControlService;
    @Mock private ConnectionAccessService connectionAccessService;
    @Mock private ConnectionAccessGrantRepository connectionAccessGrantRepository;

    private ConnectionController controller;

    @BeforeEach
    void setUp() {
        controller = new ConnectionController(
            connectionService,
            credentialService,
            schemaScannerService,
            brainInitSchedulerService,
            connectionInitStatusRepository,
            connectionInitHistoryRepository,
            trainingJobService,
            schemaDocumentationRepository,
            brainJobsService,
            accessControlService,
            connectionAccessService,
            connectionAccessGrantRepository
        );
    }

    @Test
    void savedConnectionTestHydratesPersistedSecretsAndSshConfigFromIdOnlyRequest() {
        ConnectionRequest saved = new ConnectionRequest();
        saved.setId("conn-1");
        saved.setConnectionName("prod");
        saved.setDbType("mysql");
        saved.setHost("db.internal");
        saved.setPort(3306);
        saved.setDatabase("appdb");
        saved.setUsername("app");
        saved.setPassword("db-secret");
        saved.setSshEnabled(true);
        saved.setSshAuthType("PRIVATE_KEY");
        saved.setSshHost("bastion.internal");
        saved.setSshPort(22);
        saved.setSshUsername("ec2-user");
        saved.setSshPrivateKey("pem-secret");

        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(saved);
        when(connectionService.testConnectionWithPrivileges(any(ConnectionRequest.class))).thenReturn(
            ConnectionTestResult.builder()
                .success(true)
                .connectionSuccessful(true)
                .sshTunnelSuccessful(true)
                .build()
        );

        ConnectionRequest incoming = new ConnectionRequest();
        incoming.setId("conn-1");

        ResponseEntity<Map<String, Object>> response = controller.testConnection(incoming);

        ArgumentCaptor<ConnectionRequest> captor = ArgumentCaptor.forClass(ConnectionRequest.class);
        verify(accessControlService).assertCanManageConnectionConfig("conn-1");
        verify(connectionService).testConnectionWithPrivileges(captor.capture());

        ConnectionRequest effective = captor.getValue();
        assertThat(effective.getDbType()).isEqualTo("mysql");
        assertThat(effective.getPassword()).isEqualTo("db-secret");
        assertThat(effective.getSshEnabled()).isTrue();
        assertThat(effective.getSshAuthType()).isEqualTo("PRIVATE_KEY");
        assertThat(effective.getSshPrivateKey()).isEqualTo("pem-secret");
        assertThat(response.getBody()).containsEntry("connectionSuccessful", true);
    }
}
