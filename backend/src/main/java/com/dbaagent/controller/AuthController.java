package com.dbaagent.controller;

import com.dbaagent.model.*;
import com.dbaagent.repository.PrivateBetaRequestRepository;
import com.dbaagent.repository.UserMfaEnrollmentRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.repository.UserSessionRepository;
import com.dbaagent.service.AgentBridgeService;
import com.dbaagent.service.AuthSessionService;
import com.dbaagent.service.PasswordlessAuthService;
import com.dbaagent.service.PermissionService;
import com.dbaagent.service.ImpersonationService;
import com.dbaagent.security.ImpersonationContext;
import com.dbaagent.service.SystemConfigService;
import com.dbaagent.service.UserInviteService;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/auth")
@RequiredArgsConstructor
public class AuthController {

    private final UserRepository userRepository;
    private final UserSessionRepository userSessionRepository;
    private final UserMfaEnrollmentRepository userMfaEnrollmentRepository;
    private final PermissionService permissionService;
    private final PasswordlessAuthService passwordlessAuthService;
    private final AuthSessionService authSessionService;
    private final UserInviteService userInviteService;
    private final PrivateBetaRequestRepository privateBetaRequestRepository;
    private final SystemConfigService systemConfigService;
    private final AgentBridgeService agentBridgeService;
    private final ImpersonationService impersonationService;

    @Value("${security.cookie.refresh-name:refresh_token}")
    private String refreshCookieName;

    @Value("${app.public-url:http://localhost:3000}")
    private String publicUrl;

    @PostMapping("/login")
    public ResponseEntity<?> login(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        try {
            var result = passwordlessAuthService.loginWithPassword(
                request.get("email"),
                request.get("password"),
                clientIp(httpRequest),
                userAgent(httpRequest),
                requestId(httpRequest)
            );
            return authResponse(result, httpResponse);
        } catch (ResponseStatusException e) {
            return authError(e);
        }
    }

    @PostMapping("/signup")
    public ResponseEntity<?> disabledSelfSignup() {
        return ResponseEntity.status(403).body(Map.of(
            "message", "Self-service signup is disabled. Ask an administrator to create your account."
        ));
    }

    @PostMapping("/email/start")
    public ResponseEntity<?> startEmailLogin(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        try {
            String challengeId = request.get("challengeId");
            var result = passwordlessAuthService.startEmailLogin(challengeId, clientIp(httpRequest), userAgent(httpRequest), requestId(httpRequest));
            return ResponseEntity.ok(Map.of(
                "challengeId", result.challengeId(),
                "message", result.message()
            ));
        } catch (ResponseStatusException e) {
            return authError(e);
        }
    }

    @PostMapping("/email/verify")
    public ResponseEntity<?> verifyEmailOtp(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        try {
            var result = passwordlessAuthService.verifyEmailOtp(
                request.get("challengeId"),
                request.get("otp"),
                clientIp(httpRequest),
                userAgent(httpRequest),
                requestId(httpRequest)
            );
            return authResponse(result, httpResponse);
        } catch (ResponseStatusException e) {
            return authError(e);
        }
    }

    @PostMapping("/mfa/enroll/start")
    public ResponseEntity<?> startMfaEnrollment(@RequestBody Map<String, String> request, HttpServletRequest httpRequest) {
        return ResponseEntity.status(410).body(Map.of("message", "Authenticator-based MFA is disabled."));
    }

    @PostMapping("/mfa/enroll/confirm")
    public ResponseEntity<?> confirmMfaEnrollment(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        return ResponseEntity.status(410).body(Map.of("message", "Authenticator-based MFA is disabled."));
    }

    @PostMapping("/mfa/verify")
    public ResponseEntity<?> verifyMfa(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        return ResponseEntity.status(410).body(Map.of("message", "Authenticator-based MFA is disabled."));
    }

    @GetMapping("/google/start")
    public ResponseEntity<?> startGoogleLogin(HttpServletRequest httpRequest) {
        var result = passwordlessAuthService.startGoogleLogin(clientIp(httpRequest), userAgent(httpRequest), requestId(httpRequest));
        return ResponseEntity.status(302).header("Location", result.redirectUrl()).build();
    }

    @GetMapping("/google/callback")
    public ResponseEntity<?> completeGoogleLogin(
        @RequestParam String state,
        @RequestParam String code,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        try {
            var result = passwordlessAuthService.completeGoogleLogin(
                state,
                code,
                clientIp(httpRequest),
                userAgent(httpRequest),
                requestId(httpRequest)
            );

            if (!result.success()) {
                return redirectToFrontend("/login?error=" + urlEncode(result.message()));
            }

            if (result.sessionAuthentication() != null && result.user() != null && result.role() != null) {
                authSessionService.writeSessionCookies(httpResponse, result.sessionAuthentication());
                return redirectToFrontend("/dashboard");
            }

            return redirectToFrontend(
                "/login?challengeId=" + urlEncode(result.nextChallengeId())
                    + "&email=" + urlEncode(result.user() != null ? result.user().getEmail() : "")
            );
        } catch (Exception e) {
            return redirectToFrontend("/login?error=" + urlEncode(e.getMessage() != null ? e.getMessage() : "Google login failed"));
        }
    }

    @PostMapping("/refresh")
    public ResponseEntity<?> refreshSession(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        String refreshToken = readCookie(httpRequest, refreshCookieName);
        if (refreshToken == null || refreshToken.isBlank()) {
            return ResponseEntity.status(401).body(Map.of("message", "Session expired"));
        }
        Optional<AuthSessionService.SessionAuthentication> refreshed = passwordlessAuthService.refresh(
            refreshToken,
            clientIp(httpRequest),
            userAgent(httpRequest),
            requestId(httpRequest)
        );
        if (refreshed.isEmpty()) {
            authSessionService.clearSessionCookies(httpResponse);
            return ResponseEntity.status(401).body(Map.of("message", "Session expired"));
        }
        UserSession session = refreshed.get().session();
        User user = userRepository.findById(session.getUserId()).orElse(null);
        if (user == null) {
            authSessionService.clearSessionCookies(httpResponse);
            return ResponseEntity.status(401).body(Map.of("message", "Session expired"));
        }
        authSessionService.writeSessionCookies(httpResponse, refreshed.get());
        User effectiveUser = impersonationService.resolveFromCookie(httpRequest, user)
            .map(ImpersonationContext.State::target)
            .orElse(user);
        if (effectiveUser != user && effectiveUser.getId() != null) {
            authSessionService.reissueAccessToken(
                httpResponse,
                session.getId(),
                user,
                effectiveUser.getId()
            );
        }
        // Keep agent tokens alive for as long as the UI session lives.
        // During View as the SPA still refreshes the *admin* session; also
        // slide the target user's minted MCP token or their Agent tab dies.
        agentBridgeService.extendAgentTokens(user.getUsername());
        if (!effectiveUser.getUsername().equals(user.getUsername())) {
            agentBridgeService.extendAgentTokens(effectiveUser.getUsername());
        }
        Map<String, Object> payload = toAuthPayload(
            effectiveUser,
            effectiveUser.getRoleEnum(),
            permissionService.getEffectivePermissionCodes(effectiveUser.getRoleEnum())
        );
        impersonationService.decorateAuthPayload(httpRequest, user, payload);
        return ResponseEntity.ok(payload);
    }

    @PostMapping("/logout")
    public ResponseEntity<?> logout(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        User user = currentUserEntity();
        String sessionId = sessionId(httpRequest);
        passwordlessAuthService.logout(sessionId, user.getId(), requestId(httpRequest));
        authSessionService.clearSessionCookies(httpResponse);
        // Revoke the agent token only once the user has NO remaining live session.
        // A user logged in elsewhere (another browser/device) keeps the shared
        // agent credential alive; tearing it down on a single logout would break
        // their still-logged-in agent.
        if (!hasRemainingActiveSession(user.getId())) {
            agentBridgeService.revokeAgentTokens(user.getUsername());
        }
        return ResponseEntity.ok(Map.of("message", "Logged out"));
    }

    @PostMapping("/logout-all")
    public ResponseEntity<?> logoutAll(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
        User user = currentUserEntity();
        passwordlessAuthService.logoutAll(user, requestId(httpRequest));
        authSessionService.clearSessionCookies(httpResponse);
        // Every session is gone — the agent must go with it.
        agentBridgeService.revokeAgentTokens(user.getUsername());
        return ResponseEntity.ok(Map.of("message", "All sessions revoked"));
    }

    /** True if the user still has at least one non-revoked, non-expired session. */
    private boolean hasRemainingActiveSession(Long userId) {
        return userSessionRepository.findAllByUserIdOrderByCreatedAtDesc(userId).stream()
            .anyMatch(s -> !s.isRevoked() && !s.isRefreshExpired());
    }

    @GetMapping("/sessions")
    public ResponseEntity<?> listSessions(HttpServletRequest httpRequest) {
        User user = currentUserEntity();
        List<Map<String, Object>> sessions = userSessionRepository.findAllByUserIdOrderByCreatedAtDesc(user.getId()).stream()
            .map(session -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", session.getId());
                row.put("createdAt", session.getCreatedAt());
                row.put("lastSeenAt", session.getLastSeenAt());
                row.put("accessExpiresAt", session.getAccessExpiresAt());
                row.put("refreshExpiresAt", session.getRefreshExpiresAt());
                row.put("clientIp", session.getClientIp());
                row.put("userAgent", session.getUserAgent());
                row.put("revokedAt", session.getRevokedAt());
                row.put("current", session.getId().equals(sessionId(httpRequest)));
                return row;
            })
            .toList();
        return ResponseEntity.ok(sessions);
    }

    @DeleteMapping("/sessions/{sessionId}")
    public ResponseEntity<?> revokeSession(@PathVariable String sessionId) {
        User user = currentUserEntity();
        userSessionRepository.findById(sessionId)
            .filter(session -> session.getUserId().equals(user.getId()))
            .ifPresent(session -> authSessionService.revokeSession(session.getId(), "user_revoke"));
        return ResponseEntity.ok(Map.of("message", "Session revoked"));
    }

    @GetMapping("/invite/preview")
    public ResponseEntity<?> previewInvite(@RequestParam String token) {
        return userInviteService.findUsableInviteByRawToken(token)
            .map(invite -> ResponseEntity.ok(Map.of(
                "email", invite.getEmail(),
                "username", invite.getUsername(),
                "role", invite.getRole(),
                "inviteType", invite.getInviteType(),
                "expiresAt", invite.getExpiresAt()
            )))
            .orElse(ResponseEntity.status(404).body(Map.of("message", "Invite not found or expired")));
    }

    @PostMapping("/invite/accept")
    public ResponseEntity<?> acceptInvite(
        @RequestBody Map<String, String> request,
        HttpServletRequest httpRequest,
        HttpServletResponse httpResponse
    ) {
        String token = request.get("token");
        String username = request.get("username");
        var activated = userInviteService.acceptInvite(token, username, clientIp(httpRequest), userAgent(httpRequest));
        var authResult = passwordlessAuthService.beginTrustedInviteSession(
            activated.user(),
            activated.invite().getInviteTypeEnum(),
            clientIp(httpRequest),
            userAgent(httpRequest),
            requestId(httpRequest)
        );
        return authResponse(authResult, httpResponse);
    }

    @GetMapping("/me")
    public ResponseEntity<?> getCurrentUser(HttpServletRequest httpRequest) {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            return ResponseEntity.status(401).body(Map.of("message", "Not authenticated"));
        }
        User user = currentUserEntity();
        Role role = user.getRoleEnum();
        Set<String> permissions = permissionService.getEffectivePermissionCodes(role);
        Map<String, Object> response = toAuthPayload(user, role, permissions);
        impersonationService.decorateAuthPayload(httpRequest, user, response);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/beta-signup")
    public ResponseEntity<?> submitPrivateBetaRequest(@RequestBody Map<String, String> request) {
        String name = request.get("name");
        String title = request.get("title");
        String companyName = request.get("companyName");
        String email = request.get("email");

        if (name == null || name.isBlank() ||
            title == null || title.isBlank() ||
            companyName == null || companyName.isBlank() ||
            email == null || email.isBlank()) {
            return ResponseEntity.badRequest().body(Map.of(
                "message", "Name, title, company name, and email are required"
            ));
        }

        String normalizedEmail = email.trim().toLowerCase();
        if (!normalizedEmail.matches("^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$")) {
            return ResponseEntity.badRequest().body(Map.of("message", "Please enter a valid email address"));
        }

        PrivateBetaRequest betaRequest = new PrivateBetaRequest();
        betaRequest.setName(name.trim());
        betaRequest.setTitle(title.trim());
        betaRequest.setCompanyName(companyName.trim());
        betaRequest.setEmail(normalizedEmail);
        betaRequest.setCreatedAt(LocalDateTime.now());
        privateBetaRequestRepository.save(betaRequest);

        return ResponseEntity.ok(Map.of(
            "success", true,
            "message", "We have received your private beta request. We will get back to you shortly."
        ));
    }

    private ResponseEntity<?> authResponse(PasswordlessAuthService.AuthFlowResult result, HttpServletResponse httpResponse) {
        if (!result.success()) {
            return ResponseEntity.status(400).body(Map.of("message", result.message()));
        }
        if (result.sessionAuthentication() != null && result.user() != null && result.role() != null) {
            authSessionService.writeSessionCookies(httpResponse, result.sessionAuthentication());
            authSessionService.clearImpersonationCookie(httpResponse);
            Set<String> permissionNames = result.permissions() == null ? Set.of() : result.permissions().stream()
                .map(Enum::name)
                .collect(Collectors.toSet());
            return ResponseEntity.ok(toAuthPayload(result.user(), result.role(), permissionNames));
        }
        return ResponseEntity.ok(Map.of(
            "challengeId", result.nextChallengeId(),
            "message", result.message() != null ? result.message() : "Additional verification required"
        ));
    }

    private ResponseEntity<?> authError(ResponseStatusException e) {
        String message = e.getReason();
        if (message == null || message.isBlank()) {
            message = "Authentication failed.";
        }
        return ResponseEntity.status(e.getStatusCode()).body(Map.of("message", message));
    }

    private Map<String, Object> toAuthPayload(User user, Role role, Set<String> permissions) {
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("username", user.getUsername());
        response.put("email", user.getEmail());
        response.put("role", role.name());
        response.put("permissions", permissions);
        response.put("emailVerified", user.isEmailVerified());
        response.put("accountStatus", user.getAccountStatus());
        response.put("emailTwoFactorEnabled", systemConfigService.getBoolean("security.workspace.email2fa.enabled"));
        return response;
    }

    private User currentUserEntity() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated() || "anonymousUser".equals(auth.getPrincipal())) {
            throw new IllegalStateException("Not authenticated");
        }
        return userRepository.findByUsername(auth.getName())
            .orElseThrow(() -> new IllegalStateException("User not found"));
    }

    private Long currentUserId() {
        return currentUserEntity().getId();
    }

    private String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        return request.getHeader("User-Agent");
    }

    private String requestId(HttpServletRequest request) {
        Object attr = request.getAttribute("requestId");
        return attr != null ? attr.toString() : null;
    }

    private String sessionId(HttpServletRequest request) {
        Object attr = request.getAttribute("auth.sessionId");
        return attr != null ? attr.toString() : null;
    }

    private String readCookie(HttpServletRequest request, String name) {
        if (request.getCookies() == null) {
            return null;
        }
        for (Cookie cookie : request.getCookies()) {
            if (name.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private ResponseEntity<?> redirectToFrontend(String pathWithQuery) {
        String base = publicUrl.replaceAll("/+$", "");
        return ResponseEntity.status(302)
            .header("Location", base + pathWithQuery)
            .build();
    }

    private String urlEncode(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }
}
