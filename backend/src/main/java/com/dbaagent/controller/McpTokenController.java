package com.dbaagent.controller;

import com.dbaagent.model.McpToken;
import com.dbaagent.service.McpTokenService;
import com.dbaagent.service.security.AccessControlService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static org.springframework.http.HttpStatus.UNAUTHORIZED;

@RestController
@RequestMapping("/auth/mcp-tokens")
@RequiredArgsConstructor
public class McpTokenController {

    private final McpTokenService mcpTokenService;
    private final AccessControlService accessControlService;

    @GetMapping
    public ResponseEntity<List<Map<String, Object>>> listTokens() {
        String username = currentUsername();
        List<Map<String, Object>> tokens = mcpTokenService.listTokensForUser(username).stream()
            .map(this::toResponse)
            .toList();
        return ResponseEntity.ok(tokens);
    }

    @PostMapping
    public ResponseEntity<Map<String, Object>> createToken(@RequestBody CreateMcpTokenRequest request) {
        String username = currentUsername();
        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser(
            username,
            request.name(),
            request.expiresAt()
        );

        Map<String, Object> response = toResponse(created.token());
        response.put("token", created.plainTextToken());
        response.put("message", "Store this token now. It will not be shown again.");
        return ResponseEntity.ok(response);
    }

    @DeleteMapping("/{tokenId}")
    public ResponseEntity<Map<String, Object>> revokeToken(@PathVariable Long tokenId) {
        String username = currentUsername();
        mcpTokenService.revokeTokenForUser(tokenId, username);
        return ResponseEntity.ok(Map.of(
            "status", "revoked",
            "tokenId", tokenId
        ));
    }

    private String currentUsername() {
        String username = accessControlService.getCurrentUsername();
        if (username == null || username.isBlank()) {
            throw new ResponseStatusException(UNAUTHORIZED, "Not authenticated");
        }
        return username;
    }

    private Map<String, Object> toResponse(McpToken token) {
        Map<String, Object> response = new java.util.LinkedHashMap<>();
        response.put("id", token.getId());
        response.put("name", token.getName());
        response.put("tokenPrefix", token.getTokenPrefix());
        response.put("status", token.getStatus().name());
        response.put("createdAt", token.getCreatedAt());
        response.put("lastUsedAt", token.getLastUsedAt());
        response.put("expiresAt", token.getExpiresAt());
        return response;
    }

    public record CreateMcpTokenRequest(
        String name,
        LocalDateTime expiresAt
    ) {}
}
