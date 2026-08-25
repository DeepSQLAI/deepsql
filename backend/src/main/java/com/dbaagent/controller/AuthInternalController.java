package com.dbaagent.controller;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.User;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.AuthSessionService;
import com.dbaagent.service.PermissionService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;

/**
 * Issues short-lived JWTs for internal test suite automation.
 * Requires INTERNAL_TEST_TOKEN env var to be configured; disabled if blank.
 */
@RestController
@RequestMapping("/auth/internal")
@RequiredArgsConstructor
public class AuthInternalController {

    private final UserRepository userRepository;
    private final PermissionService permissionService;
    private final AuthSessionService authSessionService;

    @Value("${INTERNAL_TEST_TOKEN:}")
    private String internalTestToken;

    @PostMapping("/token")
    public ResponseEntity<?> issueToken(
        @RequestHeader(value = "X-Internal-Token", required = false) String providedToken,
        HttpServletRequest request
    ) {
        if (internalTestToken == null || internalTestToken.isBlank()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .body(Map.of("message", "Internal token auth not configured"));
        }
        if (providedToken == null || !MessageDigest.isEqual(
                internalTestToken.getBytes(StandardCharsets.UTF_8),
                providedToken.getBytes(StandardCharsets.UTF_8))) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .body(Map.of("message", "Invalid internal token"));
        }

        User admin = userRepository.findByUsername("admin")
            .orElse(null);
        if (admin == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("message", "Admin user not found"));
        }

        String roleCode = admin.getRoleCode();
        Set<Permission> permissions = permissionService.getEffectivePermissions(roleCode);

        AuthSessionService.SessionAuthentication session = authSessionService.createSession(
            admin, roleCode, permissions,
            request.getRemoteAddr(),
            "DeepSQL-TestSuite/1.0",
            true
        );

        return ResponseEntity.ok(Map.of("token", session.accessToken()));
    }
}
