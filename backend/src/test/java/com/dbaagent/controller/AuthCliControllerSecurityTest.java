package com.dbaagent.controller;

import com.dbaagent.service.CliAuthorizationService;
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

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.http.MediaType.APPLICATION_JSON;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = "security.auth.enabled=true")
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AuthCliControllerSecurityTest {

    private static final String CONTEXT_PATH = "/api";

    @Autowired
    private WebApplicationContext context;

    @MockitoBean
    private CliAuthorizationService cliAuthorizationService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
            .apply(springSecurity())
            .build();
    }

    @Test
    void authorizeEndpointIsPublic() throws Exception {
        when(cliAuthorizationService.startBrowserAuthorization(
            eq("http://127.0.0.1:54321/cb"), eq("challenge"), eq("state"), any(), any()
        )).thenReturn(new CliAuthorizationService.StartedAuthorization(
            "auth-123", "http://localhost:3000/cli-authorize?id=auth-123", LocalDateTime.now().plusMinutes(10)
        ));

        mockMvc.perform(post("/api/auth/cli/authorize")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"redirectUri":"http://127.0.0.1:54321/cb","codeChallenge":"challenge","state":"state","hostname":"laptop","clientLabel":"deepsql-cli"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.authorization_id").value("auth-123"));
    }

    @Test
    void exchangeEndpointIsPublic() throws Exception {
        when(cliAuthorizationService.exchange(eq("auth-123"), eq("the-code"), eq("the-verifier")))
            .thenReturn(new CliAuthorizationService.IssuedToken(
                "dsql_mcp_x.y", "alice", 7L, null
            ));

        mockMvc.perform(post("/api/auth/cli/exchange")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"authorizationId":"auth-123","code":"the-code","codeVerifier":"the-verifier"}
                    """))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("dsql_mcp_x.y"))
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void exchangeReturnsBadRequestOnInvalidArgument() throws Exception {
        when(cliAuthorizationService.exchange(any(), any(), any()))
            .thenThrow(new IllegalArgumentException("PKCE verification failed"));

        mockMvc.perform(post("/api/auth/cli/exchange")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"authorizationId":"auth-123","code":"x","codeVerifier":"y"}
                    """))
            .andExpect(status().isBadRequest());
    }

    @Test
    void exchangeReturnsUnauthorizedOnIllegalState() throws Exception {
        when(cliAuthorizationService.exchange(any(), any(), any()))
            .thenThrow(new IllegalStateException("Authorization is not in an exchangeable state"));

        mockMvc.perform(post("/api/auth/cli/exchange")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("""
                    {"authorizationId":"auth-123","code":"x","codeVerifier":"y"}
                    """))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void approveRequiresAuthentication() throws Exception {
        mockMvc.perform(post("/api/auth/cli/approve")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"authorizationId\":\"auth-123\"}"))
            .andExpect(status().isUnauthorized());
    }

    @Test
    void deviceCodePollReturnsPreconditionRequiredWhilePending() throws Exception {
        when(cliAuthorizationService.pollDevice(eq("device-x")))
            .thenReturn(new CliAuthorizationService.DevicePollResult(
                CliAuthorizationService.DevicePollOutcome.AUTHORIZATION_PENDING, null
            ));

        mockMvc.perform(post("/api/auth/cli/device/token")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"deviceCode\":\"device-x\"}"))
            .andExpect(status().isPreconditionRequired())
            .andExpect(jsonPath("$.error").value("authorization_pending"));
    }

    @Test
    void deviceCodePollReturnsTooManyRequestsOnSlowDown() throws Exception {
        when(cliAuthorizationService.pollDevice(eq("device-x")))
            .thenReturn(new CliAuthorizationService.DevicePollResult(
                CliAuthorizationService.DevicePollOutcome.SLOW_DOWN, null
            ));

        mockMvc.perform(post("/api/auth/cli/device/token")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"deviceCode\":\"device-x\"}"))
            .andExpect(status().isTooManyRequests())
            .andExpect(jsonPath("$.error").value("slow_down"));
    }

    @Test
    void deviceCodePollReturnsGoneOnExpired() throws Exception {
        when(cliAuthorizationService.pollDevice(eq("device-x")))
            .thenReturn(new CliAuthorizationService.DevicePollResult(
                CliAuthorizationService.DevicePollOutcome.EXPIRED, null
            ));

        mockMvc.perform(post("/api/auth/cli/device/token")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"deviceCode\":\"device-x\"}"))
            .andExpect(status().isGone())
            .andExpect(jsonPath("$.error").value("expired_token"));
    }

    @Test
    void deviceCodePollReturnsTokenWhenApproved() throws Exception {
        when(cliAuthorizationService.pollDevice(eq("device-x")))
            .thenReturn(new CliAuthorizationService.DevicePollResult(
                CliAuthorizationService.DevicePollOutcome.APPROVED,
                new CliAuthorizationService.IssuedToken("dsql_mcp_x.y", "alice", 9L, null)
            ));

        mockMvc.perform(post("/api/auth/cli/device/token")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"deviceCode\":\"device-x\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.token").value("dsql_mcp_x.y"))
            .andExpect(jsonPath("$.username").value("alice"));
    }

    @Test
    void pendingLookupRequiresAuthentication() throws Exception {
        mockMvc.perform(get("/api/auth/cli/pending/auth-123").contextPath(CONTEXT_PATH))
            .andExpect(status().isUnauthorized());
    }

    @Test
    @WithMockUser(username = "alice", roles = "DEVELOPER")
    void approveSucceedsForAuthenticatedUser() throws Exception {
        when(cliAuthorizationService.approve(eq("auth-123"), eq("alice")))
            .thenReturn(new CliAuthorizationService.ApprovedAuthorization(
                "auth-123", "the-code", "http://127.0.0.1:54321/cb", "the-state"
            ));

        mockMvc.perform(post("/api/auth/cli/approve")
                .contextPath(CONTEXT_PATH)
                .contentType(APPLICATION_JSON)
                .content("{\"authorizationId\":\"auth-123\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.code").value("the-code"));

        verify(cliAuthorizationService).approve("auth-123", "alice");
    }

    @Test
    @WithMockUser(username = "alice", roles = "DEVELOPER")
    void pendingLookupReturnsNotFoundWhenMissing() throws Exception {
        when(cliAuthorizationService.lookupPending(eq("missing"))).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/auth/cli/pending/missing").contextPath(CONTEXT_PATH))
            .andExpect(status().isNotFound());
    }
}
