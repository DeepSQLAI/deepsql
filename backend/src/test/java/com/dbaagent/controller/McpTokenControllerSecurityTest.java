package com.dbaagent.controller;

import com.dbaagent.model.McpToken;
import com.dbaagent.service.McpTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "security.auth.enabled=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpTokenControllerSecurityTest {

    private static final String CONTEXT_PATH = "/api";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private McpTokenService mcpTokenService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void listTokensRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/mcp-tokens").contextPath(CONTEXT_PATH))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void createTokenRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/mcp-tokens")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Codex\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void revokeTokenRequiresAuthentication() throws Exception {
        mockMvc.perform(delete("/api/auth/mcp-tokens/42").contextPath(CONTEXT_PATH))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice", roles = "DEVELOPER")
    void nonAdminCannotAccessLegacyUserEndpoints() throws Exception {
        mockMvc.perform(get("/api/users").contextPath(CONTEXT_PATH))
            .andExpect(status().isForbidden());
    }

    @Test
    @WithMockUser(username = "alice", roles = "DEVELOPER")
    void authenticatedUserCanListOwnTokens() throws Exception {
        when(mcpTokenService.listTokensForUser("alice")).thenReturn(List.of());

        mockMvc.perform(get("/api/auth/mcp-tokens").contextPath(CONTEXT_PATH))
            .andExpect(status().isOk());

        verify(mcpTokenService).listTokensForUser("alice");
    }

    @Test
    @WithMockUser(username = "alice", roles = "DEVELOPER")
    void authenticatedUserCanCreateOwnToken() throws Exception {
        McpToken token = new McpToken();
        token.setId(7L);
        token.setName("Claude Code");
        token.setTokenPrefix("dsql_mcp_public");
        token.setStatus(McpToken.Status.ACTIVE);

        when(mcpTokenService.createTokenForUser(eq("alice"), eq("Claude Code"), eq(null)))
            .thenReturn(new McpTokenService.CreatedToken(token, "dsql_mcp_public.secret"));

        mockMvc.perform(post("/api/auth/mcp-tokens")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"name\":\"Claude Code\"}"))
            .andExpect(status().isOk());

        verify(mcpTokenService).createTokenForUser("alice", "Claude Code", null);
    }
}
