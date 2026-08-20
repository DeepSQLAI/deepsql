package com.dbaagent.service;

import com.dbaagent.model.User;
import com.dbaagent.security.ImpersonationContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AgentBridgeServiceImpersonationTest {

    @Mock
    private McpTokenService mcpTokenService;

    private AgentBridgeService agentBridgeService;

    @BeforeEach
    void setUp() {
        agentBridgeService = new AgentBridgeService(mcpTokenService);
        ReflectionTestUtils.setField(agentBridgeService, "provisionEnabled", true);
        ReflectionTestUtils.setField(agentBridgeService, "provisionSecret", "secret");
        ReflectionTestUtils.setField(agentBridgeService, "provisionerUrl", "http://127.0.0.1:9/provision");
        ReflectionTestUtils.setField(agentBridgeService, "sessionWindowDays", 7L);
    }

    @AfterEach
    void tearDown() {
        ImpersonationContext.clear();
    }

    @Test
    void ensureProfileDoesNotFallBackToAdminSessionTokenWhileImpersonating() {
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        User editor = new User();
        editor.setId(2L);
        editor.setUsername("marts-editor");
        editor.setRole("DEVELOPER");
        ImpersonationContext.enter(new ImpersonationContext.State(admin, editor));

        when(mcpTokenService.createTokenForUser(eq("marts-editor"), any(), any()))
            .thenThrow(new IllegalStateException("mint failed"));

        AgentBridgeService.ProvisioningException ex = assertThrows(
            AgentBridgeService.ProvisioningException.class,
            () -> agentBridgeService.ensureProfile("marts-editor", "admin-session-jwt", "conn-1")
        );
        assertEquals(
            "Could not mint an MCP token for marts-editor while viewing as that user",
            ex.getMessage()
        );
    }

    @Test
    void ensureProfileRefusesDisabledProvisioningWhileImpersonating() {
        ReflectionTestUtils.setField(agentBridgeService, "provisionEnabled", false);
        User admin = new User();
        admin.setId(1L);
        admin.setUsername("admin");
        admin.setRole("ADMIN");
        User editor = new User();
        editor.setId(2L);
        editor.setUsername("marts-editor");
        editor.setRole("DEVELOPER");
        ImpersonationContext.enter(new ImpersonationContext.State(admin, editor));

        AgentBridgeService.ProvisioningException ex = assertThrows(
            AgentBridgeService.ProvisioningException.class,
            () -> agentBridgeService.ensureProfile("marts-editor", "admin-session-jwt", "conn-1")
        );
        assertEquals(
            "Agent provisioning is disabled; View as cannot bind a user-scoped Agent token",
            ex.getMessage()
        );
    }
}
