package com.dbaagent.service.security;

import com.dbaagent.model.Permission;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.ChatFeedbackRepository;
import com.dbaagent.repository.ChatRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.mockito.Mockito.mock;

/**
 * Creating a connection requires MANAGE_CONNECTIONS.
 *
 * <p>{@code POST /connections} previously had no authorization whatsoever: it went
 * straight to test-and-save. A Data Engineer could create a connection and then edit and
 * delete it — verified against a running install, where the row persisted with
 * {@code owner_username = analyst}. Only the sidebar button was hidden, which is not a
 * control.
 *
 * <p>The permission is read from the principal's granted authorities, which
 * {@code CustomUserDetailsService} stamps from the fully-resolved effective permission
 * set — so overrides and custom roles are honoured here without a second lookup.
 */
class ConnectionCreateAuthorizationTest {

    private AccessControlService accessControlService;

    @BeforeEach
    void setUp() {
        accessControlService = new AccessControlService(
            mock(ConnectionAccessService.class),
            mock(ChatRepository.class),
            mock(ChatFeedbackRepository.class),
            mock(AnalysisHistoryRepository.class)
        );
        // Every real deployment runs with auth on; the dev-mode bypass is covered below.
        ReflectionTestUtils.setField(accessControlService, "authEnabled", true);
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(String username, String roleCode, String... permissions) {
        var authorities = new java.util.ArrayList<SimpleGrantedAuthority>();
        authorities.add(new SimpleGrantedAuthority("ROLE_" + roleCode));
        for (String p : permissions) {
            authorities.add(new SimpleGrantedAuthority(p));
        }
        SecurityContextHolder.getContext().setAuthentication(
            new UsernamePasswordAuthenticationToken(username, null, authorities));
    }

    @Test
    @DisplayName("A Developer cannot create a connection")
    void developerRefused() {
        // Developer's effective set has no MANAGE_CONNECTIONS (see PermissionResolutionTest).
        authenticateAs("analyst", "DEVELOPER",
            Permission.VIEW_AGENT.name(), Permission.VIEW_DASHBOARDS.name(),
            Permission.VIEW_EDITOR.name(), Permission.EXECUTE_QUERIES.name());

        assertThatThrownBy(() -> accessControlService.assertCanManageConnections())
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
        assertThat(accessControlService.hasPermission(Permission.MANAGE_CONNECTIONS)).isFalse();
    }

    @Test
    @DisplayName("A Data Engineer cannot create a connection")
    void dataEngineerRefused() {
        authenticateAs("analyst", "DATA_ENGINEER",
            Permission.VIEW_AGENT.name(), Permission.VIEW_DASHBOARDS.name(),
            Permission.VIEW_EDITOR.name());

        assertThatThrownBy(() -> accessControlService.assertCanManageConnections())
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    @DisplayName("A DBA can create a connection — the check is permission-based, not admin-only")
    void dbaAllowed() {
        // DBA holds MANAGE_CONNECTIONS by design but is not an admin, so an
        // isCurrentUserAdmin() check here would have broken it.
        authenticateAs("dba-user", "DBA", Permission.MANAGE_CONNECTIONS.name());

        assertDoesNotThrow(() -> accessControlService.assertCanManageConnections());
    }

    @Test
    @DisplayName("An admin can create a connection")
    void adminAllowed() {
        authenticateAs("admin", "ADMIN");

        assertDoesNotThrow(() -> accessControlService.assertCanManageConnections());
        assertThat(accessControlService.hasPermission(Permission.MANAGE_CONNECTIONS)).isTrue();
    }

    @Test
    @DisplayName("A custom role granted MANAGE_CONNECTIONS can create a connection")
    void customRoleWithPermissionAllowed() {
        authenticateAs("analyst", "PLATFORM_ENG", Permission.MANAGE_CONNECTIONS.name());

        assertDoesNotThrow(() -> accessControlService.assertCanManageConnections());
    }

    @Test
    @DisplayName("An unauthenticated caller cannot create a connection")
    void anonymousRefused() {
        SecurityContextHolder.clearContext();

        assertThatThrownBy(() -> accessControlService.assertCanManageConnections())
            .isInstanceOf(ResponseStatusException.class)
            .hasMessageContaining("403");
    }

    @Test
    @DisplayName("With auth disabled the check is a no-op, matching every other guard here")
    void devModeBypass() {
        // Consistency matters: requireCurrentUsername/isCurrentUserAdmin both honour this
        // flag, and a guard that ignored it would switch connection creation off in the
        // documented dev-mode bypass instead of opening it.
        ReflectionTestUtils.setField(accessControlService, "authEnabled", false);
        SecurityContextHolder.clearContext();

        assertDoesNotThrow(() -> accessControlService.assertCanManageConnections());
    }
}
