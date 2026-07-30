package com.dbaagent.security;

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
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class McpTokenAuthenticationFilterTest {

    @Mock
    private McpTokenService mcpTokenService;

    @Mock
    private CustomUserDetailsService userDetailsService;

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
}
