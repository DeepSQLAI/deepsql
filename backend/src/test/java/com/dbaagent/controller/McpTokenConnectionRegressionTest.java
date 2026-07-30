package com.dbaagent.controller;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.QueryResult;
import com.dbaagent.security.CustomUserDetailsService;
import com.dbaagent.service.ExplainPlanService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.McpTokenService;
import com.dbaagent.service.QueryExecutorService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.core.userdetails.User;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "security.auth.enabled=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class McpTokenConnectionRegressionTest {

    private static final String CONTEXT_PATH = "/api";
    private static final String MCP_TOKEN = "dsql_mcp_public.secret";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private McpTokenService mcpTokenService;

    @MockitoBean
    private CredentialService credentialService;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    @MockitoBean
    private QueryExecutorService queryExecutorService;

    @MockitoBean
    private ExplainPlanService explainPlanService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void validDeveloperMcpTokenCanListOwnedConnections() throws Exception {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-1");
        connection.setConnectionName("alice-db");
        connection.setDbType("postgres");
        connection.setOwnerUsername("alice");
        connection.setCreatedAt(LocalDateTime.now());

        ConnectionRequest decrypted = new ConnectionRequest();
        decrypted.setId("conn-1");
        decrypted.setConnectionName("alice-db");
        decrypted.setDbType("postgres");
        decrypted.setHost("localhost");
        decrypted.setPort(5432);
        decrypted.setDatabase("app");
        decrypted.setUsername("alice");

        stubAuthenticatedToken("alice", "DEVELOPER");
        when(credentialService.getConnectionsForUser("alice", false)).thenReturn(List.of(connection));
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(decrypted);

        mockMvc.perform(get("/api/connections")
                .contextPath(CONTEXT_PATH)
                .header("Authorization", "Bearer " + MCP_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1))
            .andExpect(jsonPath("$[0].id").value("conn-1"))
            .andExpect(jsonPath("$[0].connectionName").value("alice-db"))
            .andExpect(jsonPath("$[0].dbType").value("postgres"));

        verify(credentialService).getConnectionsForUser("alice", false);
    }

    @Test
    void validAdminMcpTokenUsesAdminConnectionScope() throws Exception {
        stubAuthenticatedToken("admin", "ADMIN");
        when(credentialService.getConnectionsForUser("admin", true)).thenReturn(List.of());

        mockMvc.perform(get("/api/connections")
                .contextPath(CONTEXT_PATH)
                .header("Authorization", "Bearer " + MCP_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(0));

        verify(credentialService).getConnectionsForUser("admin", true);
    }

    @Test
    void invalidMcpTokenIsRejectedForConnectionsEndpoint() throws Exception {
        when(mcpTokenService.looksLikeMcpToken(MCP_TOKEN)).thenReturn(true);
        when(mcpTokenService.authenticate(eq(MCP_TOKEN), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/connections")
                .contextPath(CONTEXT_PATH)
                .header("Authorization", "Bearer " + MCP_TOKEN))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void validDeveloperMcpTokenCanExecuteReadOnlyMcpQuery() throws Exception {
        DatabaseConnection ownedConnection = new DatabaseConnection();
        ownedConnection.setId("conn-1");
        ownedConnection.setOwnerUsername("alice");

        QueryResult result = new QueryResult();
        result.setColumns(List.of("id"));
        result.setRows(List.of(List.of(1)));
        result.setRowCount(1);
        result.setQuery("SELECT 1");

        stubAuthenticatedToken("alice", "DEVELOPER");
        when(credentialService.getConnectionEntity("conn-1")).thenReturn(ownedConnection);
        when(queryExecutorService.executeQuery(eq("conn-1"), any())).thenReturn(result);

        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/mcp/query-readonly")
                .contextPath(CONTEXT_PATH)
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content("""
                    {"connectionId":"conn-1","query":"SELECT 1"}
                    """)
                .header("Authorization", "Bearer " + MCP_TOKEN))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.success").value(true))
            .andExpect(jsonPath("$.result.rowCount").value(1))
            .andExpect(jsonPath("$.queryType").value("SELECT"));
    }

    private void stubAuthenticatedToken(String username, String role) {
        when(mcpTokenService.looksLikeMcpToken(MCP_TOKEN)).thenReturn(true);
        when(mcpTokenService.authenticate(eq(MCP_TOKEN), anyString()))
            .thenReturn(Optional.of(new McpTokenService.AuthenticatedMcpToken(7L, username)));
        when(customUserDetailsService.loadUserByUsername(username))
            .thenReturn(User.withUsername(username)
                .password("ignored")
                .roles(role)
                .build());
    }
}
