package com.dbaagent.service;

import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import com.dbaagent.model.UserAccountStatus;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.security.CustomUserDetailsService;
import com.dbaagent.security.ImpersonationContext;
import com.dbaagent.security.JwtUtil;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.springframework.http.HttpStatus.BAD_REQUEST;
import static org.springframework.http.HttpStatus.FORBIDDEN;
import static org.springframework.http.HttpStatus.NOT_FOUND;

/**
 * Admin-only profile switch so an administrator can verify another user's
 * connection ACLs and chat/editor policies.
 *
 * <p>The admin session stays the real session (logout/refresh/control-plane).
 * Identity for policy is the target user: an httpOnly {@code impersonate_user}
 * cookie plus an {@code impUid} claim on the access JWT. The JWT filter overlays
 * that principal onto the SecurityContext for every request except the
 * impersonation control plane, logout, and session refresh.
 *
 * <p>The {@code impUid} claim matters for callers that send the access token as
 * a Bearer credential without cookies (Agent MCP fallback). Cookie-only overlay
 * left those paths running as the administrator, which skipped policy.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ImpersonationService {

    static final String DEFAULT_COOKIE_NAME = "impersonate_user";

    private final UserRepository userRepository;
    private final CustomUserDetailsService userDetailsService;
    private final AuthSessionService authSessionService;
    private final SecurityEventService securityEventService;
    private final JwtUtil jwtUtil;

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${security.cookie.impersonate-name:" + DEFAULT_COOKIE_NAME + "}")
    private String impersonateCookieName;

    @Value("${security.cookie.name:auth_token}")
    private String accessCookieName;

    public ImpersonationContext.State start(
        User actor,
        Long targetUserId,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        requireAdminActor(actor);
        User target = requireAllowedTarget(actor, targetUserId);
        authSessionService.writeImpersonationCookie(response, impersonateCookieName, target.getId());
        rewriteAccessToken(request, response, actor, target.getId());
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.IMPERSONATION_STARTED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(target.getId())
            .actorUserId(actor.getId())
            .email(actor.getEmail())
            .targetResource("user:" + target.getId())
            .clientIp(clientIp(request))
            .userAgent(userAgent(request))
            .metadata(Map.of(
                "impersonatorUsername", actor.getUsername(),
                "targetUsername", target.getUsername()
            ))
            .build());
        log.info("Admin {} started profile switch to {}", actor.getUsername(), target.getUsername());
        return new ImpersonationContext.State(actor, target);
    }

    public User stop(
        User actor,
        HttpServletRequest request,
        HttpServletResponse response
    ) {
        requireAdminActor(actor);
        Optional<User> target = readTargetUser(request);
        authSessionService.clearImpersonationCookie(response, impersonateCookieName);
        rewriteAccessToken(request, response, actor, null);
        ImpersonationContext.clear();
        target.ifPresent(stopped -> securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.IMPERSONATION_STOPPED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(stopped.getId())
            .actorUserId(actor.getId())
            .email(actor.getEmail())
            .targetResource("user:" + stopped.getId())
            .clientIp(clientIp(request))
            .userAgent(userAgent(request))
            .metadata(Map.of(
                "impersonatorUsername", actor.getUsername(),
                "targetUsername", stopped.getUsername()
            ))
            .build()));
        log.info("Admin {} stopped profile switch", actor.getUsername());
        return actor;
    }

    public List<Map<String, Object>> listCandidates(User actor) {
        requireAdminActor(actor);
        return userRepository.findAll().stream()
            .filter(user -> isAllowedTarget(actor, user))
            .map(this::toCandidate)
            .toList();
    }

    public Optional<ImpersonationContext.State> resolveFromCookie(HttpServletRequest request, User sessionUser) {
        if (sessionUser == null || !sessionUser.isAdmin()) {
            return Optional.empty();
        }
        return readTargetUser(request)
            .filter(target -> isAllowedTarget(sessionUser, target))
            .map(target -> new ImpersonationContext.State(sessionUser, target));
    }

    public void decorateAuthPayload(HttpServletRequest request, User sessionUser, Map<String, Object> payload) {
        Optional<ImpersonationContext.State> state = ImpersonationContext.current();
        if (state.isEmpty()) {
            state = resolveFromCookie(request, sessionUser);
        }
        if (state.isEmpty()) {
            payload.put("impersonating", false);
            return;
        }
        payload.put("impersonating", true);
        payload.put("impersonatorUsername", state.get().impersonatorUsername());
        payload.put("impersonatorEmail", state.get().impersonatorEmail());
    }

    /**
     * Overlay the target principal when the admin JWT (or the auth-disabled
     * synthetic admin) is already in the SecurityContext. No-ops on the
     * impersonation control-plane, logout/refresh, MCP tokens, and invalid cookies.
     *
     * <p>The target is taken from the {@code impersonate_user} cookie first, then
     * from the access token's {@code impUid} claim so Bearer callers without
     * cookies still evaluate policy as the viewed-as user.
     */
    public void applyToRequest(HttpServletRequest request) {
        if (!shouldApply(request)) {
            return;
        }
        Authentication current = SecurityContextHolder.getContext().getAuthentication();
        if (current == null || !current.isAuthenticated() || "anonymousUser".equals(current.getPrincipal())) {
            return;
        }
        if (authEnabled && !hasAdminRole(current)) {
            return;
        }
        User impersonator = userRepository.findByUsername(current.getName())
            .orElseGet(() -> syntheticAdmin(current.getName()));
        if (!impersonator.isAdmin() && authEnabled) {
            return;
        }
        Optional<User> target = readTargetUser(request);
        if (target.isEmpty() || !isAllowedTarget(impersonator, target.get())) {
            return;
        }
        UserDetails details;
        try {
            details = userDetailsService.loadUserByUsername(target.get().getUsername());
        } catch (UsernameNotFoundException e) {
            return;
        }
        UsernamePasswordAuthenticationToken swapped = new UsernamePasswordAuthenticationToken(
            details,
            null,
            details.getAuthorities()
        );
        swapped.setDetails(current.getDetails());
        SecurityContextHolder.getContext().setAuthentication(swapped);
        ImpersonationContext.enter(new ImpersonationContext.State(impersonator, target.get()));
        log.debug("Applied profile switch: {} -> {}", impersonator.getUsername(), target.get().getUsername());
    }

    boolean shouldApply(HttpServletRequest request) {
        String path = request.getServletPath() != null ? request.getServletPath() : "";
        String uri = request.getRequestURI() != null ? request.getRequestURI() : "";
        if (isControlPlane(path) || isControlPlane(uri)) {
            return false;
        }
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (token.startsWith(McpTokenService.TOKEN_PREFIX)) {
                return false;
            }
        }
        return true;
    }

    private boolean isControlPlane(String path) {
        if (path == null || path.isBlank()) {
            return false;
        }
        return path.contains("/admin/impersonate")
            || path.endsWith("/auth/logout")
            || path.endsWith("/auth/logout-all")
            || path.endsWith("/auth/refresh");
    }

    private Optional<User> readTargetUser(HttpServletRequest request) {
        Long userId = readTargetUserId(request);
        if (userId == null) {
            return Optional.empty();
        }
        return userRepository.findById(userId);
    }

    private Long readTargetUserId(HttpServletRequest request) {
        Long fromCookie = readTargetUserIdFromCookie(request);
        if (fromCookie != null) {
            return fromCookie;
        }
        return readTargetUserIdFromJwt(request);
    }

    private Long readTargetUserIdFromCookie(HttpServletRequest request) {
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (impersonateCookieName.equals(cookie.getName())) {
                return parseUserId(cookie.getValue());
            }
        }
        return null;
    }

    private Long readTargetUserIdFromJwt(HttpServletRequest request) {
        String jwt = readAccessJwt(request);
        if (jwt == null) {
            return null;
        }
        try {
            return jwtUtil.extractImpersonateUserId(jwt);
        } catch (Exception e) {
            return null;
        }
    }

    private void rewriteAccessToken(
        HttpServletRequest request,
        HttpServletResponse response,
        User sessionOwner,
        Long impersonateUserId
    ) {
        String sessionId = sessionIdFrom(request);
        if (sessionId == null) {
            return;
        }
        authSessionService.reissueAccessToken(response, sessionId, sessionOwner, impersonateUserId);
    }

    private String sessionIdFrom(HttpServletRequest request) {
        Object attr = request.getAttribute("auth.sessionId");
        if (attr instanceof String sid && !sid.isBlank()) {
            return sid;
        }
        String jwt = readAccessJwt(request);
        if (jwt == null) {
            return null;
        }
        try {
            String sessionId = jwtUtil.extractSessionId(jwt);
            return sessionId == null || sessionId.isBlank() ? null : sessionId;
        } catch (Exception e) {
            return null;
        }
    }

    private String readAccessJwt(HttpServletRequest request) {
        String authorization = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (authorization != null && authorization.startsWith("Bearer ")) {
            String token = authorization.substring(7);
            if (token.startsWith(McpTokenService.TOKEN_PREFIX)) {
                return null;
            }
            return token.isBlank() ? null : token;
        }
        Cookie[] cookies = request.getCookies();
        if (cookies == null) {
            return null;
        }
        for (Cookie cookie : cookies) {
            if (accessCookieName.equals(cookie.getName())) {
                return cookie.getValue();
            }
        }
        return null;
    }

    private Long parseUserId(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            long parsed = Long.parseLong(value.trim());
            return parsed > 0 ? parsed : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private void requireAdminActor(User actor) {
        if (actor == null || !actor.isAdmin()) {
            throw new ResponseStatusException(FORBIDDEN, "Only administrators can switch profiles");
        }
    }

    private User requireAllowedTarget(User actor, Long targetUserId) {
        if (targetUserId == null) {
            throw new ResponseStatusException(BAD_REQUEST, "userId is required");
        }
        User target = userRepository.findById(targetUserId)
            .orElseThrow(() -> new ResponseStatusException(NOT_FOUND, "User not found"));
        if (!isAllowedTarget(actor, target)) {
            throw new ResponseStatusException(BAD_REQUEST, denialReason(actor, target));
        }
        return target;
    }

    boolean isAllowedTarget(User actor, User target) {
        if (actor == null || target == null || target.getId() == null) {
            return false;
        }
        if (actor.getId() != null && actor.getId().equals(target.getId())) {
            return false;
        }
        if (target.isAdmin()) {
            return false;
        }
        return target.getAccountStatusEnum() == UserAccountStatus.ACTIVE;
    }

    private String denialReason(User actor, User target) {
        if (actor.getId() != null && actor.getId().equals(target.getId())) {
            return "Cannot switch into your own profile";
        }
        if (target.isAdmin()) {
            return "Cannot switch into another administrator profile";
        }
        if (target.getAccountStatusEnum() != UserAccountStatus.ACTIVE) {
            return "Cannot switch into a locked or disabled account";
        }
        return "Cannot switch into this profile";
    }

    private Map<String, Object> toCandidate(User user) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", user.getId());
        dto.put("username", user.getUsername());
        dto.put("email", user.getEmail());
        dto.put("role", user.getRole());
        dto.put("accountStatus", user.getAccountStatus());
        return dto;
    }

    private boolean hasAdminRole(Authentication authentication) {
        return authentication.getAuthorities().stream()
            .map(GrantedAuthority::getAuthority)
            .anyMatch("ROLE_ADMIN"::equals);
    }

    private User syntheticAdmin(String username) {
        User admin = new User();
        admin.setUsername(username != null && !username.isBlank() ? username : "admin");
        admin.setRole("ADMIN");
        admin.setAccountStatus(UserAccountStatus.ACTIVE.name());
        return admin;
    }

    private String clientIp(HttpServletRequest request) {
        if (request == null) {
            return null;
        }
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        return request.getRemoteAddr();
    }

    private String userAgent(HttpServletRequest request) {
        return request == null ? null : request.getHeader("User-Agent");
    }
}
