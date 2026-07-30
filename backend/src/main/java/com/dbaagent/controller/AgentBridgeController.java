package com.dbaagent.controller;

import com.dbaagent.service.AgentBridgeService;
import com.dbaagent.service.security.AccessControlService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

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
        String profile = agentBridgeService.ensureProfile(username, token, connectionId);
        return ResponseEntity.ok(Map.of("profile", profile, "username", username));
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
