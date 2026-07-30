package com.dbaagent.service;

import com.dbaagent.model.McpToken;
import com.dbaagent.model.User;
import com.dbaagent.repository.McpTokenRepository;
import com.dbaagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

@Service
@RequiredArgsConstructor
@Slf4j
public class McpTokenService {

    public static final String TOKEN_PREFIX = "dsql_mcp_";

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();

    private final McpTokenRepository mcpTokenRepository;
    private final UserRepository userRepository;

    public record CreatedToken(McpToken token, String plainTextToken) {}
    public record AuthenticatedMcpToken(Long tokenId, String username) {}

    @Transactional
    public CreatedToken createTokenForUser(String username, String name, LocalDateTime expiresAt) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        String normalizedName = name == null ? "" : name.trim();
        if (normalizedName.isBlank()) {
            throw new IllegalArgumentException("Token name is required");
        }
        if (normalizedName.length() > 120) {
            throw new IllegalArgumentException("Token name must be 120 characters or fewer");
        }
        if (expiresAt != null && !expiresAt.isAfter(LocalDateTime.now())) {
            throw new IllegalArgumentException("Token expiry must be in the future");
        }

        String publicId = generateUniquePublicId();
        String secret = randomUrlSafe(32);
        String rawToken = TOKEN_PREFIX + publicId + "." + secret;

        McpToken token = new McpToken();
        token.setUser(user);
        token.setName(normalizedName);
        token.setPublicId(publicId);
        token.setTokenPrefix(TOKEN_PREFIX + publicId);
        token.setTokenHash(hashTokenMaterial(publicId, secret));
        token.setStatus(McpToken.Status.ACTIVE);
        token.setExpiresAt(expiresAt);

        McpToken saved = mcpTokenRepository.save(token);
        log.info("Created MCP token {} for user {}", saved.getId(), username);
        return new CreatedToken(saved, rawToken);
    }

    /**
     * Extend (forward-only) the expiry of every ACTIVE token the user owns with
     * the given name. Used to keep the agent's token alive for as long as the
     * user's UI session stays alive (sliding window, bumped on each session
     * refresh). Never shortens an existing expiry. Returns how many were extended.
     */
    @Transactional
    public int extendActiveTokensByName(String username, String name, LocalDateTime newExpiresAt) {
        if (newExpiresAt == null) {
            return 0;
        }
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return 0;
        }
        int extended = 0;
        for (McpToken token : mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(userOpt.get().getId())) {
            if (token.getStatus() != McpToken.Status.ACTIVE || !name.equals(token.getName())) {
                continue;
            }
            if (token.getExpiresAt() == null || token.getExpiresAt().isAfter(newExpiresAt)) {
                continue; // already longer-lived (or non-expiring) — leave it
            }
            token.setExpiresAt(newExpiresAt);
            mcpTokenRepository.save(token);
            extended++;
        }
        return extended;
    }

    /**
     * Revoke every ACTIVE token the user owns with the given name. Used to tear
     * down the agent's credential when the user fully logs out. Returns how many
     * were revoked.
     */
    @Transactional
    public int revokeActiveTokensByName(String username, String name) {
        Optional<User> userOpt = userRepository.findByUsername(username);
        if (userOpt.isEmpty()) {
            return 0;
        }
        int revoked = 0;
        for (McpToken token : mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(userOpt.get().getId())) {
            if (token.getStatus() == McpToken.Status.ACTIVE && name.equals(token.getName())) {
                token.setStatus(McpToken.Status.REVOKED);
                mcpTokenRepository.save(token);
                revoked++;
            }
        }
        return revoked;
    }

    @Transactional(readOnly = true)
    public List<McpToken> listTokensForUser(String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));
        return mcpTokenRepository.findByUserIdOrderByCreatedAtDesc(user.getId());
    }

    @Transactional
    public void revokeTokenForUser(Long tokenId, String username) {
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        McpToken token = mcpTokenRepository.findByIdAndUserId(tokenId, user.getId())
            .orElseThrow(() -> new IllegalArgumentException("Token not found: " + tokenId));

        token.setStatus(McpToken.Status.REVOKED);
        mcpTokenRepository.save(token);
        log.info("Revoked MCP token {} for user {}", tokenId, username);
    }

    @Transactional
    public Optional<AuthenticatedMcpToken> authenticate(String rawToken, String remoteAddr) {
        ParsedToken parsed = parse(rawToken);
        if (parsed == null) {
            return Optional.empty();
        }

        Optional<McpToken> tokenOpt = mcpTokenRepository.findByPublicIdAndStatus(parsed.publicId(), McpToken.Status.ACTIVE);
        if (tokenOpt.isEmpty()) {
            return Optional.empty();
        }

        McpToken token = tokenOpt.get();
        if (isExpired(token)) {
            token.setStatus(McpToken.Status.REVOKED);
            mcpTokenRepository.save(token);
            return Optional.empty();
        }

        byte[] candidateHash = hashTokenMaterial(parsed.publicId(), parsed.secret());
        if (!MessageDigest.isEqual(token.getTokenHash(), candidateHash)) {
            return Optional.empty();
        }

        token.setLastUsedAt(LocalDateTime.now());
        token.setLastUsedIp(truncate(remoteAddr, 128));
        McpToken saved = mcpTokenRepository.save(token);
        // Resolve primitive auth data inside the transaction so the filter never
        // touches a lazy JPA proxy outside the session boundary.
        String username = saved.getUser().getUsername();
        return Optional.of(new AuthenticatedMcpToken(saved.getId(), username));
    }

    public boolean looksLikeMcpToken(String rawToken) {
        return rawToken != null && rawToken.startsWith(TOKEN_PREFIX);
    }

    private boolean isExpired(McpToken token) {
        return token.getExpiresAt() != null && !token.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private String generateUniquePublicId() {
        String publicId;
        do {
            publicId = randomUrlSafe(12);
        } while (mcpTokenRepository.existsByPublicId(publicId));
        return publicId;
    }

    private static byte[] hashTokenMaterial(String publicId, String secret) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest((publicId + ":" + secret).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        SECURE_RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static ParsedToken parse(String rawToken) {
        if (rawToken == null || !rawToken.startsWith(TOKEN_PREFIX)) {
            return null;
        }

        String remainder = rawToken.substring(TOKEN_PREFIX.length());
        int separator = remainder.indexOf('.');
        if (separator <= 0 || separator == remainder.length() - 1) {
            return null;
        }

        String publicId = remainder.substring(0, separator).trim();
        String secret = remainder.substring(separator + 1).trim();
        if (publicId.isEmpty() || secret.isEmpty()) {
            return null;
        }
        return new ParsedToken(publicId, secret);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength);
    }

    private record ParsedToken(String publicId, String secret) {}
}
