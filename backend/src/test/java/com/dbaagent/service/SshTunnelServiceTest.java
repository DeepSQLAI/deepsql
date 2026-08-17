package com.dbaagent.service;

import com.dbaagent.provider.DatabaseProviderRegistry;
import com.jcraft.jsch.JSchException;
import com.jcraft.jsch.Session;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SshTunnelServiceTest {

    @Mock
    private DatabaseProviderRegistry providerRegistry;

    @Mock
    private Session session;

    private SshTunnelService sshTunnelService;

    @BeforeEach
    void setUp() {
        sshTunnelService = new SshTunnelService(
                providerRegistry, new SshHostGuard(new SshHostGuardProperties()));
    }

    @Test
    void createLocalPortForward_usesLoopbackAndEphemeralPort() throws JSchException {
        when(session.setPortForwardingL("127.0.0.1", 0, "db.internal", 3306)).thenReturn(45123);

        int localPort = sshTunnelService.createLocalPortForward(session, "db.internal", 3306);

        assertEquals(45123, localPort);
        verify(session).setPortForwardingL("127.0.0.1", 0, "db.internal", 3306);
    }
}
