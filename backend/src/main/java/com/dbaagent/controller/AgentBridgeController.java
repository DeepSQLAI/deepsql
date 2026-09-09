package com.dbaagent.controller;

import com.dbaagent.service.AgentBridgeService;
import com.dbaagent.service.security.AccessControlService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;

/**
 * Bootstrap endpoint for the native "Agent" chat tab. Resolves the current
 * DeepSQL user to their isolated agent profile (provisioning it on first use)
 * so the frontend can open a chat session against the DeepSQL Agent service.
 */
@RestController
@RequestMapping("/agent")
@RequiredArgsConstructor
public class AgentBridgeController {
    private static final Logger log = LoggerFactory.getLogger(AgentBridgeController.class);

    private final AccessControlService accessControlService;
    private final AgentBridgeService agentBridgeService;

    @Value("${security.cookie.name:auth_token}")
    private String cookieName;

    @PostMapping("/session")
    public ResponseEntity<Map<String, Object>> session(
            @RequestBody(required = false) Map<String, Object> body,
            HttpServletRequest request) {
        String username = accessControlService.requireCurrentUsername();
        String token = extractToken(request);
        String connectionId = body == null ? null : asString(body.get("connectionId"));

        AgentBridgeService.ProfileBootstrap bootstrap;
        try {
            bootstrap = agentBridgeService.ensureProfile(username, token, connectionId);
        } catch (AgentBridgeService.ProvisioningException e) {
            log.warn("Agent session bootstrap failed for {}: {}", username, e.getMessage());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).body(Map.of(
                "error", "agent_provisioning_failed",
                "message", "Could not provision the DeepSQL Agent for this user. "
                    + "Check that the agent provisioner is running and reachable."
            ));
        }

        // Boot health check: probe the exact token just provisioned against this
        // backend's own API before telling the UI it's safe to chat. Without
        // this, an expired/misrouted token surfaces only after several failed
        // tool calls deep into a conversation (W1 "fail loud, early").
        Map<String, Object> response = new HashMap<>();
        response.put("profile", bootstrap.profile());
        response.put("username", username);
        boolean mcpAuthOk = agentBridgeService.probeMcpAuth(bootstrap.token(), username);
        response.put("mcpAuthOk", mcpAuthOk);
        if (!mcpAuthOk) {
            response.put("mcpAuthError", "The DeepSQL Agent could not authenticate against this API with its "
                + "provisioned token. Reconnect or check the Agent runtime.");
        }
        return ResponseEntity.ok(response);
    }

    private String extractToken(HttpServletRequest request) {
        String header = request.getHeader("Authorization");
        if (header != null && header.startsWith("Bearer ")) {
            return header.substring(7);
        }
        Cookie[] cookies = request.getCookies();
        if (cookies != null) {
            for (Cookie c : cookies) {
                if (cookieName.equals(c.getName())) {
                    return c.getValue();
                }
            }
        }
        return "";
    }

    private static String asString(Object o) {
        return o == null ? null : o.toString();
    }
}
