package com.dbaagent.security;

import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.McpTokenService;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.util.ReflectionTestUtils;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpTokenAuthenticationFilterTest {

    @Mock
    private McpTokenService mcpTokenService;

    @Mock
    private CustomUserDetailsService userDetailsService;

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private McpTokenAuthenticationFilter filter;

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void authenticateUsesResolvedUsernameWithoutTouchingLazyEntity() throws ServletException, IOException {
        ReflectionTestUtils.setField(filter, "authEnabled", true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connections");
        request.addHeader("Authorization", "Bearer dsql_mcp_public.secret");
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(mcpTokenService.looksLikeMcpToken("dsql_mcp_public.secret")).thenReturn(true);
        when(mcpTokenService.authenticate("dsql_mcp_public.secret", "127.0.0.1"))
            .thenReturn(Optional.of(new McpTokenService.AuthenticatedMcpToken(7L, "alice")));
        when(userDetailsService.loadUserByUsername("alice"))
            .thenReturn(new User(
                "alice",
                "ignored",
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"))
            ));

        filter.doFilter(request, response, chain);

        assertNotNull(SecurityContextHolder.getContext().getAuthentication());
        assertEquals("alice", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    /**
     * The cross-user token leak, at the layer that stops it.
     *
     * <p>The agent runtime keeps ONE MCP subprocess and the provisioner rotates
     * its credential on disk, so another user's Agent-tab open could leave
     * analyst's MCP process holding admin's token. Authenticating that token
     * would run analyst's tools as admin — reading connections analyst has no
     * grant for, and writing admin into the audit row. The request's own
     * DEEPSQL_MCP_USER_ID claim ("analyst") contradicts the token owner
     * ("admin"), which is the signal to refuse.
     */
    @Test
    void rejectsTokenWhoseOwnerDiffersFromTheDeclaredMcpUser() throws ServletException, IOException {
        ReflectionTestUtils.setField(filter, "authEnabled", true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connections");
        request.addHeader("Authorization", "Bearer dsql_mcp_public.secret");
        // This MCP process was provisioned for analyst...
        request.addHeader("X-DeepSQL-Client-Agent", "analyst");
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(mcpTokenService.looksLikeMcpToken("dsql_mcp_public.secret")).thenReturn(true);
        // ...but the token file was overwritten with admin's credential.
        when(mcpTokenService.authenticate("dsql_mcp_public.secret", "127.0.0.1"))
            .thenReturn(Optional.of(new McpTokenService.AuthenticatedMcpToken(9L, "admin")));
        when(userRepository.findByUsernameIgnoreCase("analyst"))
            .thenReturn(Optional.of(new com.dbaagent.model.User()));

        filter.doFilter(request, response, chain);

        assertNull(SecurityContextHolder.getContext().getAuthentication(),
            "a mismatched MCP credential must not authenticate anyone");
        assertEquals(401, response.getStatus());
        assertTrue(response.getContentAsString().contains("mcp_identity_mismatch"));
        assertNull(chain.getRequest(), "the request must not reach downstream handlers");
    }

    /** The normal agent case: the claim matches the token owner. */
    @Test
    void allowsTokenWhenDeclaredMcpUserMatchesOwner() throws ServletException, IOException {
        ReflectionTestUtils.setField(filter, "authEnabled", true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connections");
        request.addHeader("Authorization", "Bearer dsql_mcp_public.secret");
        request.addHeader("X-DeepSQL-Client-Agent", "analyst");
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(mcpTokenService.looksLikeMcpToken("dsql_mcp_public.secret")).thenReturn(true);
        when(mcpTokenService.authenticate("dsql_mcp_public.secret", "127.0.0.1"))
            .thenReturn(Optional.of(new McpTokenService.AuthenticatedMcpToken(9L, "analyst")));
        when(userDetailsService.loadUserByUsername("analyst"))
            .thenReturn(new User("analyst", "ignored",
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"))));

        filter.doFilter(request, response, chain);

        assertEquals("analyst", SecurityContextHolder.getContext().getAuthentication().getName());
    }

    /**
     * Editor/CLI installs put a *tool* name in this header ("cursor",
     * "claude-desktop", any --caller-agent value). Those tokens are not
     * agent-provisioned, so the claim must not be compared against a username —
     * otherwise every editor MCP install would 401.
     */
    @Test
    void allowsEditorClientAgentThatIsNotADeepSqlUsername() throws ServletException, IOException {
        ReflectionTestUtils.setField(filter, "authEnabled", true);

        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/connections");
        request.addHeader("Authorization", "Bearer dsql_mcp_public.secret");
        request.addHeader("X-DeepSQL-Client-Agent", "cursor");
        request.setRemoteAddr("127.0.0.1");

        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        when(mcpTokenService.looksLikeMcpToken("dsql_mcp_public.secret")).thenReturn(true);
        when(mcpTokenService.authenticate("dsql_mcp_public.secret", "127.0.0.1"))
            .thenReturn(Optional.of(new McpTokenService.AuthenticatedMcpToken(11L, "bob")));
        when(userRepository.findByUsernameIgnoreCase("cursor")).thenReturn(Optional.empty());
        when(userDetailsService.loadUserByUsername("bob"))
            .thenReturn(new User("bob", "ignored",
                List.of(new SimpleGrantedAuthority("ROLE_DEVELOPER"))));

        filter.doFilter(request, response, chain);

        assertEquals("bob", SecurityContextHolder.getContext().getAuthentication().getName());
    }
}
