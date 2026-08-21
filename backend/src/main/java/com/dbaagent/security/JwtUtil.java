package com.dbaagent.security;

import com.dbaagent.model.Permission;
import com.dbaagent.model.Role;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.SignatureAlgorithm;
import io.jsonwebtoken.security.Keys;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Value;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Component
public class JwtUtil {

    @Value("${security.jwt.secret:}")
    private String jwtSecret;

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${spring.profiles.active:}")
    private String activeProfiles;

    @Value("${security.session.access-minutes:15}")
    private long accessTokenMinutes;

    private SecretKey secretKey;

    @PostConstruct
    public void initialize() {
        byte[] secretBytes = jwtSecret == null || jwtSecret.isBlank()
            ? new byte[0]
            : jwtSecret.getBytes(StandardCharsets.UTF_8);

        boolean prodProfile = Arrays.stream(activeProfiles.split(","))
            .map(String::trim)
            .filter(p -> !p.isEmpty())
            .anyMatch(p -> p.equalsIgnoreCase("prod"));

        // Fail closed whenever auth is on or under prod — never ship an ephemeral signing key.
        if (secretBytes.length < 32) {
            if (authEnabled || prodProfile) {
                throw new IllegalStateException(
                    "SECURITY_JWT_SECRET must be set to at least 32 bytes when auth is enabled "
                        + "or SPRING_PROFILES_ACTIVE includes prod (got "
                        + secretBytes.length + " bytes)"
                );
            }
            secretKey = Keys.secretKeyFor(SignatureAlgorithm.HS256);
            log.warn("JWT secret not configured; generated ephemeral key (tokens will reset on restart).");
            return;
        }

        secretKey = Keys.hmacShaKeyFor(secretBytes);
    }

    public String extractUsername(String token) {
        return extractClaim(token, Claims::getSubject);
    }

    public Date extractExpiration(String token) {
        return extractClaim(token, Claims::getExpiration);
    }

    /**
     * Extract the role from the JWT token.
     */
    public String extractRole(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("role", String.class);
    }

    public String extractSessionId(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("sid", String.class);
    }

    /**
     * Extract permissions from the JWT token.
     */
    @SuppressWarnings("unchecked")
    public List<String> extractPermissions(String token) {
        Claims claims = extractAllClaims(token);
        return claims.get("permissions", List.class);
    }

    public <T> T extractClaim(String token, Function<Claims, T> claimsResolver) {
        final Claims claims = extractAllClaims(token);
        return claimsResolver.apply(claims);
    }

    private Claims extractAllClaims(String token) {
        return Jwts.parser().setSigningKey(secretKey).build().parseClaimsJws(token).getBody();
    }

    private Boolean isTokenExpired(String token) {
        return extractExpiration(token).before(new Date());
    }

    /**
     * Generate token without role/permissions (backward compatible).
     * Uses DEVELOPER role as default.
     */
    public String generateToken(UserDetails userDetails) {
        return generateToken(userDetails, Role.DEVELOPER);
    }

    /**
     * Generate token with role and permissions included.
     */
    public String generateToken(UserDetails userDetails, Role role) {
        return generateAccessToken(userDetails.getUsername(), null, role, role.getPermissions(), Duration.ofMinutes(accessTokenMinutes));
    }

    public String generateAccessToken(
        String username,
        String sessionId,
        Role role,
        Set<Permission> permissions,
        Duration ttl
    ) {
        return generateAccessToken(username, sessionId, role, permissions, ttl, null);
    }

    public String generateAccessToken(
        String username,
        String sessionId,
        Role role,
        Set<Permission> permissions,
        Duration ttl,
        Long impersonateUserId
    ) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role.name());

        List<String> permissionNames = permissions.stream()
                .map(Permission::name)
                .collect(Collectors.toList());
        claims.put("permissions", permissionNames);
        if (sessionId != null && !sessionId.isBlank()) {
            claims.put("sid", sessionId);
        }
        if (impersonateUserId != null && impersonateUserId > 0) {
            claims.put("impUid", impersonateUserId);
        }

        return createToken(claims, username, ttl);
    }

    /**
     * Target user id stamped onto an admin access token during View as.
     * The JWT subject stays the administrator so logout/refresh/control-plane
     * still own the real session; policy evaluation overlays this user.
     */
    public Long extractImpersonateUserId(String token) {
        Claims claims = extractAllClaims(token);
        Object raw = claims.get("impUid");
        if (raw instanceof Number number) {
            long value = number.longValue();
            return value > 0 ? value : null;
        }
        if (raw instanceof String text && !text.isBlank()) {
            try {
                long value = Long.parseLong(text.trim());
                return value > 0 ? value : null;
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    /**
     * Generate token with role string and permissions set.
     */
    public String generateToken(UserDetails userDetails, String roleName, Set<Permission> permissions) {
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", roleName);

        List<String> permissionNames = permissions.stream()
                .map(Permission::name)
                .collect(Collectors.toList());
        claims.put("permissions", permissionNames);

        return createToken(claims, userDetails.getUsername(), Duration.ofMinutes(accessTokenMinutes));
    }

    private String createToken(Map<String, Object> claims, String subject, Duration ttl) {
        long ttlMillis = ttl != null ? ttl.toMillis() : Duration.ofMinutes(accessTokenMinutes).toMillis();
        return Jwts.builder()
                .setClaims(claims)
                .subject(subject)
                .issuedAt(new Date(System.currentTimeMillis()))
                .expiration(new Date(System.currentTimeMillis() + ttlMillis))
                .signWith(secretKey, SignatureAlgorithm.HS256)
                .compact();
    }

    public Boolean validateToken(String token, UserDetails userDetails) {
        final String username = extractUsername(token);
        return (username.equals(userDetails.getUsername()) && !isTokenExpired(token));
    }
}
