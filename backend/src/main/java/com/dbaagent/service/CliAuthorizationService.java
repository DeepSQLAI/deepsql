package com.dbaagent.service;

import com.dbaagent.model.CliAuthorization;
import com.dbaagent.model.CliDeviceCode;
import com.dbaagent.model.User;
import com.dbaagent.repository.CliAuthorizationRepository;
import com.dbaagent.repository.CliDeviceCodeRepository;
import com.dbaagent.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;

/**
 * Implements first-time CLI authorization for self-hosted DeepSQL installs.
 *
 * <p>Two flows are supported:
 * <ul>
 *     <li><b>Browser callback (PKCE)</b> — `deepsql login` opens the authorize URL,
 *         user approves in the web UI, browser is redirected to a loopback URI on the
 *         CLI host with a one-shot {@code code}, CLI exchanges {@code code +
 *         code_verifier} for an MCP token.</li>
 *     <li><b>Device code</b> — for headless boxes. CLI requests a {@code device_code}
 *         and human-typeable {@code user_code}, displays the activation URL, polls until
 *         the user pastes the {@code user_code} into the web UI and approves.</li>
 * </ul>
 *
 * <p>All flows ultimately mint a long-lived MCP personal access token through
 * {@link McpTokenService} so the existing auth filter chain handles requests
 * unchanged.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CliAuthorizationService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int AUTH_TTL_SECONDS = 600;
    private static final int DEVICE_TTL_SECONDS = 900;
    private static final int DEVICE_INTERVAL_SECONDS = 5;
    private static final int USER_CODE_LENGTH = 8;
    // Human-friendly alphabet — drops O/0/I/1/L to avoid confusion. Same as Slack
    // link codes (see SlackUserLinkService.generateCode) so users only have to learn
    // one alphabet across DeepSQL surfaces.
    private static final String USER_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";

    private final CliAuthorizationRepository cliAuthorizationRepository;
    private final CliDeviceCodeRepository cliDeviceCodeRepository;
    private final McpTokenService mcpTokenService;
    private final UserRepository userRepository;

    @Value("${security.auth.enabled:true}")
    private boolean authEnabled;

    @Value("${app.base-url:http://localhost:3000}")
    private String appBaseUrl;

    /**
     * Log the configured `app.base-url` at startup so operators can see —
     * and CI/health checks can capture — where the `deepsql login` browser
     * flow will redirect users.
     *
     * Critical for self-hosted deploys: this URL is returned to the CLI
     * verbatim as `authorize_url`, the CLI opens it in the user's browser,
     * and the user's authentication state lands on whatever host it points
     * at. If APP_BASE_URL is unset and we silently default to our hosted
     * domain, the customer's CLI session opens our login page — the exact
     * symptom reported on 2026-05-13.
     */
    @jakarta.annotation.PostConstruct
    void logResolvedBaseUrl() {
        log.info("CLI auth flows will hand out authorize_url and verification_uri rooted at {} "
            + "(configure via APP_BASE_URL / `app.base-url` to match your DeepSQL frontend host).",
            appBaseUrl);
        if (appBaseUrl == null || appBaseUrl.isBlank()) {
            log.error("app.base-url is empty — CLI authorize_url will be malformed. "
                + "Set APP_BASE_URL to your DeepSQL frontend host (e.g. https://deepsql.your-company.com).");
        }
    }

    public record StartedAuthorization(
        String authorizationId,
        String authorizeUrl,
        LocalDateTime expiresAt
    ) {}

    public record PendingAuthorization(
        String authorizationId,
        String hostname,
        String clientLabel,
        LocalDateTime expiresAt
    ) {}

    public record IssuedToken(
        String token,
        String username,
        Long tokenId,
        LocalDateTime expiresAt
    ) {}

    public record StartedDeviceCode(
        String deviceCode,
        String userCode,
        String verificationUri,
        int intervalSeconds,
        int expiresInSeconds
    ) {}

    public record PendingDeviceCode(
        Long id,
        String hostname,
        String clientLabel,
        LocalDateTime expiresAt
    ) {}

    public enum DevicePollOutcome {
        AUTHORIZATION_PENDING,
        SLOW_DOWN,
        DENIED,
        EXPIRED,
        APPROVED
    }

    public record DevicePollResult(DevicePollOutcome outcome, IssuedToken issuedToken) {}

    @Transactional
    public StartedAuthorization startBrowserAuthorization(
        String redirectUri,
        String codeChallenge,
        String state,
        String hostname,
        String clientLabel
    ) {
        if (codeChallenge == null || codeChallenge.isBlank()) {
            throw new IllegalArgumentException("code_challenge is required");
        }
        validateLoopbackRedirect(redirectUri);

        CliAuthorization authorization = new CliAuthorization();
        authorization.setAuthorizationId(randomUrlSafe(24));
        authorization.setCodeChallengeHash(codeChallenge.trim());
        authorization.setRedirectUri(redirectUri.trim());
        authorization.setState(state);
        authorization.setHostname(truncate(hostname, 255));
        authorization.setClientLabel(truncate(clientLabel, 255));
        authorization.setStatus(CliAuthorization.Status.PENDING);
        authorization.setExpiresAt(LocalDateTime.now().plusSeconds(AUTH_TTL_SECONDS));
        cliAuthorizationRepository.save(authorization);

        String authorizeUrl = appBaseUrl.replaceAll("/+$", "")
            + "/cli-authorize?id=" + authorization.getAuthorizationId();
        return new StartedAuthorization(
            authorization.getAuthorizationId(),
            authorizeUrl,
            authorization.getExpiresAt()
        );
    }

    @Transactional(readOnly = true)
    public Optional<PendingAuthorization> lookupPending(String authorizationId) {
        return cliAuthorizationRepository.findByAuthorizationId(authorizationId)
            .filter(this::isStillPending)
            .map(auth -> new PendingAuthorization(
                auth.getAuthorizationId(),
                auth.getHostname(),
                auth.getClientLabel(),
                auth.getExpiresAt()
            ));
    }

    /**
     * Marks the authorization approved and returns the one-shot {@code code} that
     * the frontend uses to build the loopback redirect URL. The plaintext code is
     * never returned again.
     */
    @Transactional
    public ApprovedAuthorization approve(String authorizationId, String username) {
        CliAuthorization auth = cliAuthorizationRepository.findByAuthorizationId(authorizationId)
            .orElseThrow(() -> new IllegalArgumentException("Authorization not found"));
        if (!isStillPending(auth)) {
            throw new IllegalStateException("Authorization is not pending");
        }
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        String code = randomUrlSafe(32);
        auth.setStatus(CliAuthorization.Status.APPROVED);
        auth.setApprovedByUserId(user.getId());
        auth.setApprovedAt(LocalDateTime.now());
        auth.setCodeHash(sha256(code));
        cliAuthorizationRepository.save(auth);

        return new ApprovedAuthorization(
            auth.getAuthorizationId(),
            code,
            auth.getRedirectUri(),
            auth.getState()
        );
    }

    public record ApprovedAuthorization(
        String authorizationId,
        String code,
        String redirectUri,
        String state
    ) {}

    @Transactional
    public void deny(String authorizationId) {
        cliAuthorizationRepository.findByAuthorizationId(authorizationId).ifPresent(auth -> {
            if (auth.getStatus() == CliAuthorization.Status.PENDING) {
                auth.setStatus(CliAuthorization.Status.DENIED);
                cliAuthorizationRepository.save(auth);
            }
        });
    }

    /**
     * Exchange the one-shot {@code code} + PKCE {@code code_verifier} for an MCP
     * token. Codes are single-use; replay attempts return 401.
     */
    @Transactional
    public IssuedToken exchange(String authorizationId, String code, String codeVerifier) {
        if (code == null || code.isBlank() || codeVerifier == null || codeVerifier.isBlank()) {
            throw new IllegalArgumentException("code and code_verifier are required");
        }
        CliAuthorization auth = cliAuthorizationRepository.findByAuthorizationId(authorizationId)
            .orElseThrow(() -> new IllegalArgumentException("Authorization not found"));

        if (auth.getStatus() != CliAuthorization.Status.APPROVED) {
            throw new IllegalStateException("Authorization is not in an exchangeable state");
        }
        if (auth.getExpiresAt().isBefore(LocalDateTime.now())) {
            auth.setStatus(CliAuthorization.Status.EXPIRED);
            cliAuthorizationRepository.save(auth);
            throw new IllegalStateException("Authorization expired");
        }
        if (!constantTimeEquals(sha256(code), auth.getCodeHash())) {
            throw new IllegalArgumentException("Invalid authorization code");
        }
        if (!verifyPkce(codeVerifier, auth.getCodeChallengeHash())) {
            throw new IllegalArgumentException("PKCE verification failed");
        }

        User approver = userRepository.findById(auth.getApprovedByUserId())
            .orElseThrow(() -> new IllegalStateException("Approving user no longer exists"));

        String tokenName = buildTokenName(auth.getClientLabel(), auth.getHostname(), "browser");
        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser(
            approver.getUsername(), tokenName, null
        );

        auth.setStatus(CliAuthorization.Status.CONSUMED);
        auth.setConsumedAt(LocalDateTime.now());
        auth.setIssuedTokenId(created.token().getId());
        cliAuthorizationRepository.save(auth);

        log.info("Exchanged CLI authorization {} for token {} (user {})",
            auth.getAuthorizationId(), created.token().getId(), approver.getUsername());
        return new IssuedToken(
            created.plainTextToken(),
            approver.getUsername(),
            created.token().getId(),
            created.token().getExpiresAt()
        );
    }

    @Transactional
    public StartedDeviceCode startDeviceFlow(String hostname, String clientLabel) {
        String deviceCode = randomUrlSafe(32);
        String userCode = generateUserCode();

        CliDeviceCode row = new CliDeviceCode();
        row.setDeviceCodeHash(sha256(deviceCode));
        row.setUserCodeHash(sha256(userCode));
        row.setUserCodePrefix(userCode.substring(0, 4));
        row.setHostname(truncate(hostname, 255));
        row.setClientLabel(truncate(clientLabel, 255));
        row.setIntervalSeconds(DEVICE_INTERVAL_SECONDS);
        row.setStatus(CliDeviceCode.Status.PENDING);
        row.setExpiresAt(LocalDateTime.now().plusSeconds(DEVICE_TTL_SECONDS));
        cliDeviceCodeRepository.save(row);

        String verificationUri = appBaseUrl.replaceAll("/+$", "") + "/cli-authorize/device";
        return new StartedDeviceCode(
            deviceCode,
            formatUserCode(userCode),
            verificationUri,
            DEVICE_INTERVAL_SECONDS,
            DEVICE_TTL_SECONDS
        );
    }

    @Transactional(readOnly = true)
    public Optional<PendingDeviceCode> lookupPendingDevice(String userCode) {
        String normalized = normalizeUserCode(userCode);
        if (normalized.length() < 4) {
            return Optional.empty();
        }
        String prefix = normalized.substring(0, 4);
        String hash = sha256(normalized);
        return cliDeviceCodeRepository.findAllByUserCodePrefix(prefix).stream()
            .filter(this::isStillPending)
            .filter(row -> constantTimeEquals(row.getUserCodeHash(), hash))
            .findFirst()
            .map(row -> new PendingDeviceCode(
                row.getId(),
                row.getHostname(),
                row.getClientLabel(),
                row.getExpiresAt()
            ));
    }

    @Transactional
    public void approveDevice(String userCode, String username) {
        String normalized = normalizeUserCode(userCode);
        if (normalized.length() < 4) {
            throw new IllegalArgumentException("Invalid user code");
        }
        User user = userRepository.findByUsername(username)
            .orElseThrow(() -> new IllegalArgumentException("User not found: " + username));

        String hash = sha256(normalized);
        CliDeviceCode row = cliDeviceCodeRepository.findAllByUserCodePrefix(normalized.substring(0, 4)).stream()
            .filter(this::isStillPending)
            .filter(candidate -> constantTimeEquals(candidate.getUserCodeHash(), hash))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("User code not found or expired"));

        row.setStatus(CliDeviceCode.Status.APPROVED);
        row.setApprovedByUserId(user.getId());
        row.setApprovedAt(LocalDateTime.now());
        cliDeviceCodeRepository.save(row);
    }

    @Transactional
    public void denyDevice(String userCode) {
        String normalized = normalizeUserCode(userCode);
        if (normalized.length() < 4) {
            return;
        }
        String hash = sha256(normalized);
        cliDeviceCodeRepository.findAllByUserCodePrefix(normalized.substring(0, 4)).stream()
            .filter(this::isStillPending)
            .filter(candidate -> constantTimeEquals(candidate.getUserCodeHash(), hash))
            .findFirst()
            .ifPresent(row -> {
                row.setStatus(CliDeviceCode.Status.DENIED);
                cliDeviceCodeRepository.save(row);
            });
    }

    @Transactional
    public DevicePollResult pollDevice(String deviceCode) {
        if (deviceCode == null || deviceCode.isBlank()) {
            throw new IllegalArgumentException("device_code is required");
        }
        String hash = sha256(deviceCode.trim());
        // Device codes are high-entropy; iterate non-consumed rows. Bounded by TTL.
        CliDeviceCode row = cliDeviceCodeRepository.findAll().stream()
            .filter(candidate -> constantTimeEquals(candidate.getDeviceCodeHash(), hash))
            .findFirst()
            .orElseThrow(() -> new IllegalArgumentException("Unknown device_code"));

        LocalDateTime now = LocalDateTime.now();

        // Rate limiting: clients must wait at least intervalSeconds between polls.
        if (row.getLastPolledAt() != null
            && row.getLastPolledAt().plusSeconds(row.getIntervalSeconds()).isAfter(now)) {
            return new DevicePollResult(DevicePollOutcome.SLOW_DOWN, null);
        }
        row.setLastPolledAt(now);

        if (row.getExpiresAt().isBefore(now) && row.getStatus() == CliDeviceCode.Status.PENDING) {
            row.setStatus(CliDeviceCode.Status.EXPIRED);
            cliDeviceCodeRepository.save(row);
            return new DevicePollResult(DevicePollOutcome.EXPIRED, null);
        }
        if (row.getStatus() == CliDeviceCode.Status.DENIED) {
            cliDeviceCodeRepository.save(row);
            return new DevicePollResult(DevicePollOutcome.DENIED, null);
        }
        if (row.getStatus() == CliDeviceCode.Status.EXPIRED) {
            cliDeviceCodeRepository.save(row);
            return new DevicePollResult(DevicePollOutcome.EXPIRED, null);
        }
        if (row.getStatus() == CliDeviceCode.Status.PENDING) {
            cliDeviceCodeRepository.save(row);
            return new DevicePollResult(DevicePollOutcome.AUTHORIZATION_PENDING, null);
        }
        if (row.getStatus() == CliDeviceCode.Status.CONSUMED) {
            cliDeviceCodeRepository.save(row);
            // Replay protection: the device code is single-use.
            throw new IllegalStateException("Device code already consumed");
        }

        // APPROVED — issue token, mark consumed.
        User approver = userRepository.findById(row.getApprovedByUserId())
            .orElseThrow(() -> new IllegalStateException("Approving user no longer exists"));
        String tokenName = buildTokenName(row.getClientLabel(), row.getHostname(), "device");
        McpTokenService.CreatedToken created = mcpTokenService.createTokenForUser(
            approver.getUsername(), tokenName, null
        );

        row.setStatus(CliDeviceCode.Status.CONSUMED);
        row.setConsumedAt(now);
        row.setIssuedTokenId(created.token().getId());
        cliDeviceCodeRepository.save(row);

        IssuedToken issued = new IssuedToken(
            created.plainTextToken(),
            approver.getUsername(),
            created.token().getId(),
            created.token().getExpiresAt()
        );
        return new DevicePollResult(DevicePollOutcome.APPROVED, issued);
    }

    private boolean isStillPending(CliAuthorization auth) {
        return auth.getStatus() == CliAuthorization.Status.PENDING
            && auth.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private boolean isStillPending(CliDeviceCode row) {
        return (row.getStatus() == CliDeviceCode.Status.PENDING
            || row.getStatus() == CliDeviceCode.Status.APPROVED)
            && row.getExpiresAt().isAfter(LocalDateTime.now());
    }

    private void validateLoopbackRedirect(String redirectUri) {
        if (redirectUri == null || redirectUri.isBlank()) {
            throw new IllegalArgumentException("redirect_uri is required");
        }
        URI uri;
        try {
            uri = URI.create(redirectUri.trim());
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("redirect_uri is not a valid URI", e);
        }
        if (!"http".equalsIgnoreCase(uri.getScheme())) {
            throw new IllegalArgumentException("redirect_uri must use http scheme");
        }
        String host = uri.getHost();
        // Reject "localhost" — RFC 8252 §7.3 recommends literal loopback IPs to
        // avoid open-redirect via /etc/hosts shenanigans.
        if (!"127.0.0.1".equals(host) && !"::1".equals(host)) {
            throw new IllegalArgumentException("redirect_uri host must be 127.0.0.1 or ::1");
        }
        if (uri.getPort() < 1 || uri.getPort() > 65535) {
            throw new IllegalArgumentException("redirect_uri must specify a port");
        }
    }

    private boolean verifyPkce(String codeVerifier, String storedChallenge) {
        // We only support S256: challenge = base64url(sha256(verifier)).
        byte[] digest = sha256Bytes(codeVerifier);
        String computed = Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        return constantTimeEquals(computed, storedChallenge);
    }

    private String buildTokenName(String clientLabel, String hostname, String flow) {
        StringBuilder name = new StringBuilder("CLI ");
        if (clientLabel != null && !clientLabel.isBlank()) {
            name.append(clientLabel.trim());
        } else {
            name.append("deepsql");
        }
        if (hostname != null && !hostname.isBlank()) {
            name.append(" @ ").append(hostname.trim());
        }
        name.append(" (").append(flow).append(")");
        return name.length() > 120 ? name.substring(0, 120) : name.toString();
    }

    private String generateUserCode() {
        StringBuilder code = new StringBuilder(USER_CODE_LENGTH);
        for (int i = 0; i < USER_CODE_LENGTH; i++) {
            code.append(USER_CODE_ALPHABET.charAt(SECURE_RANDOM.nextInt(USER_CODE_ALPHABET.length())));
        }
        return code.toString();
    }

    private String formatUserCode(String code) {
        return code.substring(0, 4) + "-" + code.substring(4);
    }

    private String normalizeUserCode(String code) {
        if (code == null) return "";
        return code.replaceAll("[^A-Za-z0-9]", "").toUpperCase(Locale.ROOT);
    }

    private static String randomUrlSafe(int bytes) {
        byte[] buffer = new byte[bytes];
        SECURE_RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    private static String sha256(String input) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(sha256Bytes(input));
    }

    private static byte[] sha256Bytes(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return digest.digest(input.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null) return false;
        byte[] aBytes = a.getBytes(StandardCharsets.UTF_8);
        byte[] bBytes = b.getBytes(StandardCharsets.UTF_8);
        return MessageDigest.isEqual(aBytes, bBytes);
    }

    private static String truncate(String value, int maxLength) {
        if (value == null) return null;
        return value.length() > maxLength ? value.substring(0, maxLength) : value;
    }
}
