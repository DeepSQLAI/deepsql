package com.dbaagent.service;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import com.dbaagent.model.SecurityEventOutcome;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import com.dbaagent.model.UserSession;
import com.dbaagent.repository.UserSessionRepository;
import com.dbaagent.security.JwtUtil;
import com.dbaagent.util.SecurityHashUtil;
import jakarta.servlet.http.HttpServletResponse;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthSessionService {
    private final UserSessionRepository userSessionRepository;
    private final JwtUtil jwtUtil;
    private final SecurityEventService securityEventService;

    @Value("${security.cookie.name:auth_token}")
    private String accessCookieName;

    @Value("${security.cookie.refresh-name:refresh_token}")
    private String refreshCookieName;

    @Value("${security.cookie.secure:false}")
    private boolean cookieSecure;

    @Value("${security.cookie.same-site:Lax}")
    private String cookieSameSite;

    @Value("${security.session.access-minutes:15}")
    private long accessMinutes;

    @Value("${security.session.refresh-days:7}")
    private long refreshDays;

    @Transactional
    public SessionAuthentication createSession(
        User user,
        Role role,
        Set<Permission> permissions,
        String clientIp,
        String userAgent,
        boolean mfaVerified
    ) {
        UserSession session = new UserSession();
        session.setId(UUID.randomUUID().toString());
        session.setUserId(user.getId());
        String refreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        session.setRefreshTokenHash(SecurityHashUtil.sha256Hex(refreshToken));
        session.setClientIp(clientIp);
        session.setUserAgent(userAgent);
        session.setCreatedAt(LocalDateTime.now());
        session.setLastSeenAt(LocalDateTime.now());
        session.setAccessExpiresAt(LocalDateTime.now().plusMinutes(accessMinutes));
        session.setRefreshExpiresAt(LocalDateTime.now().plusDays(refreshDays));
        session.setMfaVerifiedAt(mfaVerified ? LocalDateTime.now() : null);
        userSessionRepository.save(session);

        String accessToken = jwtUtil.generateAccessToken(
            user.getUsername(),
            session.getId(),
            role,
            permissions,
            Duration.ofMinutes(accessMinutes)
        );

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.SESSION_CREATED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .metadata(Map.of("sessionId", session.getId()))
            .build());

        return SessionAuthentication.builder()
            .session(session)
            .accessToken(accessToken)
            .refreshToken(refreshToken)
            .build();
    }

    @Transactional(readOnly = true)
    public Optional<UserSession> findValidSession(String sessionId) {
        return userSessionRepository.findById(sessionId)
            .filter(session -> !session.isRevoked())
            .filter(session -> !session.isRefreshExpired());
    }

    @Transactional(readOnly = true)
    public Optional<UserSession> findValidSessionByRefreshToken(String refreshTokenHash) {
        return userSessionRepository.findByRefreshTokenHash(refreshTokenHash)
            .filter(session -> !session.isRevoked())
            .filter(session -> !session.isRefreshExpired());
    }

    @Transactional
    public Optional<SessionAuthentication> refreshSession(
        String rawRefreshToken,
        User user,
        Role role,
        Set<Permission> permissions,
        String clientIp,
        String userAgent
    ) {
        String tokenHash = SecurityHashUtil.sha256Hex(rawRefreshToken);
        return userSessionRepository.findByRefreshTokenHash(tokenHash)
            .filter(session -> !session.isRevoked())
            .filter(session -> !session.isRefreshExpired())
            .filter(session -> session.getUserId().equals(user.getId()))
            .map(session -> rotateSession(session, user, role, permissions, clientIp, userAgent));
    }

    @Transactional
    public void revokeSession(String sessionId, String reason) {
        userSessionRepository.findById(sessionId).ifPresent(session -> {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason(reason);
            userSessionRepository.save(session);
        });
    }

    @Transactional
    public void revokeAllSessionsForUser(Long userId, String reason) {
        userSessionRepository.findAllByUserIdAndRevokedAtIsNullOrderByCreatedAtDesc(userId).forEach(session -> {
            session.setRevokedAt(LocalDateTime.now());
            session.setRevokeReason(reason);
            userSessionRepository.save(session);
        });
    }

    public void writeSessionCookies(HttpServletResponse response, SessionAuthentication sessionAuthentication) {
        response.addHeader(HttpHeaders.SET_COOKIE, buildAccessCookie(sessionAuthentication.accessToken()).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, buildRefreshCookie(sessionAuthentication.refreshToken()).toString());
    }

    public void clearSessionCookies(HttpServletResponse response) {
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie(accessCookieName).toString());
        response.addHeader(HttpHeaders.SET_COOKIE, clearCookie(refreshCookieName).toString());
    }

    private SessionAuthentication rotateSession(
        UserSession session,
        User user,
        Role role,
        Set<Permission> permissions,
        String clientIp,
        String userAgent
    ) {
        String nextRefreshToken = UUID.randomUUID() + "." + UUID.randomUUID();
        session.setRefreshTokenHash(SecurityHashUtil.sha256Hex(nextRefreshToken));
        session.setLastSeenAt(LocalDateTime.now());
        session.setAccessExpiresAt(LocalDateTime.now().plusMinutes(accessMinutes));
        session.setRefreshExpiresAt(LocalDateTime.now().plusDays(refreshDays));
        session.setClientIp(clientIp);
        session.setUserAgent(userAgent);
        userSessionRepository.save(session);

        String accessToken = jwtUtil.generateAccessToken(
            user.getUsername(),
            session.getId(),
            role,
            permissions,
            Duration.ofMinutes(accessMinutes)
        );

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.SESSION_REFRESHED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .metadata(Map.of("sessionId", session.getId()))
            .build());

        return SessionAuthentication.builder()
            .session(session)
            .accessToken(accessToken)
            .refreshToken(nextRefreshToken)
            .build();
    }

    private ResponseCookie buildAccessCookie(String token) {
        return ResponseCookie.from(accessCookieName, token)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(Duration.ofMinutes(accessMinutes))
            .build();
    }

    private ResponseCookie buildRefreshCookie(String token) {
        return ResponseCookie.from(refreshCookieName, token)
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(Duration.ofDays(refreshDays))
            .build();
    }

    private ResponseCookie clearCookie(String name) {
        return ResponseCookie.from(name, "")
            .httpOnly(true)
            .secure(cookieSecure)
            .sameSite(cookieSameSite)
            .path("/")
            .maxAge(Duration.ZERO)
            .build();
    }

    @Builder
    public record SessionAuthentication(
        UserSession session,
        String accessToken,
        String refreshToken
    ) {
    }
}
