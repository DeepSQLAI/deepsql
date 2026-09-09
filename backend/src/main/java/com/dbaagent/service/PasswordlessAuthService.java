package com.dbaagent.service;

import com.dbaagent.model.*;
import com.dbaagent.repository.*;
import com.dbaagent.security.EncryptionService;
import com.dbaagent.util.SecurityHashUtil;
import jakarta.annotation.PostConstruct;
import lombok.Builder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.RestClient;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.util.LinkedMultiValueMap;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class PasswordlessAuthService {
    private static final SecureRandom RANDOM = new SecureRandom();

    private final UserRepository userRepository;
    private final AuthLoginChallengeRepository authLoginChallengeRepository;
    private final UserMfaEnrollmentRepository userMfaEnrollmentRepository;
    private final AuthSessionService authSessionService;
    private final EmailService emailService;
    private final TotpService totpService;
    private final EncryptionService encryptionService;
    private final PermissionService permissionService;
    private final SecurityEventService securityEventService;
    private final GoogleWorkspaceDomainRepository googleWorkspaceDomainRepository;
    private final PasswordEncoder passwordEncoder;
    private final SecurityEventRepository securityEventRepository;
    private final SystemConfigService systemConfigService;

    @Value("${security.otp.ttl-minutes:10}")
    private long otpTtlMinutes;

    @Value("${security.otp.max-attempts:5}")
    private int maxOtpAttempts;

    @Value("${security.auth.rate-limit-window-minutes:15}")
    private long rateLimitWindowMinutes;

    @Value("${security.auth.max-email-starts:5}")
    private int maxEmailStarts;

    @Value("${security.auth.max-ip-starts:20}")
    private int maxIpStarts;

    @Value("${security.auth.max-password-failures:10}")
    private int maxPasswordFailures;

    @Value("${security.auth.rate-limit-enabled:true}")
    private boolean rateLimitEnabled;

    @Value("${security.auth.skip-2fa:false}")
    private boolean skip2fa;

    @Value("${security.admin-mfa.enabled:false}")
    private boolean adminMfaEnabled;

    @Value("${security.google.enabled:false}")
    private boolean googleEnabled;

    /**
     * Whether email + password sign-in is accepted at all.
     *
     * <p>Defaults to {@code true} so existing installs are unaffected. Set it to
     * false on deployments that front DeepSQL with Google Workspace SSO: enabling
     * SSO does NOT by itself close the password path, so without this flag
     * {@code /auth/login} stays open to every local account even when every human
     * signs in through Google.
     *
     * <p>Turning this off while {@code security.google.enabled} is also off leaves
     * no way to sign in — {@link #warnIfNoAuthMethodEnabled()} shouts about that at
     * startup rather than letting it be discovered at the login screen.
     */
    @Value("${security.password.enabled:true}")
    private boolean passwordLoginEnabled;

    @PostConstruct
    void warnIfNoAuthMethodEnabled() {
        if (!passwordLoginEnabled && !googleEnabled) {
            log.error("security.password.enabled=false AND security.google.enabled=false — "
                + "no sign-in method is available and nobody can log in. Enable one of them.");
        } else if (!passwordLoginEnabled) {
            log.info("Password sign-in is DISABLED (security.password.enabled=false); Google SSO only.");
        }
    }

    @Value("${security.google.client-id:}")
    private String googleClientId;

    @Value("${security.google.client-secret:}")
    private String googleClientSecret;

    @Value("${security.google.redirect-uri:}")
    private String googleRedirectUri;

    @Value("${app.public-issuer:DeepSQL}")
    private String otpIssuer;

    @Transactional
    public AuthFlowResult loginWithPassword(String email, String password, String clientIp, String userAgent, String requestId) {
        String normalizedEmail = normalizeEmail(email);

        // Checked before the rate limiter and before any credential comparison:
        // when the password path is closed there is nothing to rate-limit and no
        // secret to compare, and we must not leak whether the account exists.
        if (!passwordLoginEnabled) {
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.PASSWORD_LOGIN_FAILURE)
                .outcome(SecurityEventOutcome.FAILURE)
                .email(normalizedEmail)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .requestId(requestId)
                .metadata(Map.of("reason", "password_login_disabled"))
                .build());
            return AuthFlowResult.invalid("Password sign-in is disabled. Please sign in with Google.");
        }

        if (rateLimitEnabled) enforcePasswordRateLimit(normalizedEmail, clientIp);

        User user = normalizedEmail == null ? null : userRepository.findByEmailIgnoreCase(normalizedEmail).orElse(null);
        if (user == null || !user.isActiveAccount() || password == null || password.isBlank() || !passwordEncoder.matches(password, user.getPassword())) {
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.PASSWORD_LOGIN_FAILURE)
                .outcome(SecurityEventOutcome.FAILURE)
                .userId(user != null ? user.getId() : null)
                .email(normalizedEmail)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .requestId(requestId)
                .build());
            return AuthFlowResult.invalid("Invalid email or password.");
        }

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.PASSWORD_LOGIN_SUCCESS)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .build());

        AuthLoginChallenge challenge = new AuthLoginChallenge();
        challenge.setId(UUID.randomUUID().toString());
        challenge.setUserId(user.getId());
        challenge.setEmail(user.getEmail());
        challenge.setChallengeTypeEnum(AuthChallengeType.PASSWORD_LOGIN);
        challenge.setChallengeStateEnum(AuthChallengeState.OTP_VERIFIED);
        challenge.setClientIp(clientIp);
        challenge.setUserAgent(userAgent);
        challenge.setVerifiedAt(LocalDateTime.now());
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(otpTtlMinutes));
        authLoginChallengeRepository.save(challenge);

        return completePrimaryAuth(challenge, user, clientIp, userAgent, requestId);
    }

    @Transactional
    public EmailStartResult startEmailLogin(String challengeId, String clientIp, String userAgent, String requestId) {
        AuthLoginChallenge challenge = loadUsableChallenge(challengeId, AuthChallengeType.EMAIL_OTP);
        User user = requireChallengeUser(challenge);
        if (!user.isActiveAccount()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "This account is not active.");
        }
        if (!isWorkspaceEmail2faEnabled()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Email verification is not enabled for this workspace.");
        }
        sendOtpForChallenge(challenge, user, clientIp, userAgent, requestId);
        return new EmailStartResult(challenge.getId(), "A verification code has been sent to your email.");
    }

    @Transactional
    public AuthFlowResult verifyEmailOtp(String challengeId, String otpCode, String clientIp, String userAgent, String requestId) {
        AuthLoginChallenge challenge = loadUsableChallenge(challengeId, AuthChallengeType.EMAIL_OTP);
        User user = challenge.getUserId() != null ? userRepository.findById(challenge.getUserId()).orElse(null) : null;
        if (!isValidOtpChallenge(challenge, user, otpCode)) {
            markFailedOtp(challenge, user, clientIp, userAgent, requestId);
            return AuthFlowResult.invalid("Invalid or expired code.");
        }

        challenge.setVerifiedAt(LocalDateTime.now());
        challenge.setChallengeStateEnum(AuthChallengeState.OTP_VERIFIED);
        authLoginChallengeRepository.save(challenge);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.OTP_VERIFIED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .metadata(Map.of("challengeId", challenge.getId()))
            .build());

        return completePrimaryAuth(challenge, user, clientIp, userAgent, requestId);
    }

    @Transactional
    public MfaEnrollmentResult startMfaEnrollment(String challengeId, String requestId) {
        AuthLoginChallenge challenge = loadUsableChallenge(challengeId, null);
        User user = requireChallengeUser(challenge);
        if (!user.isAdmin()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "MFA enrollment is only required for admins.");
        }
        byte[] secretBytes = totpService.generateSecretBytes();
        challenge.setPendingMfaSecret(encryptionService.encrypt(totpService.toBase32(secretBytes)));
        challenge.setChallengeStateEnum(AuthChallengeState.PENDING_MFA);
        authLoginChallengeRepository.save(challenge);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.MFA_ENROLL_STARTED)
            .outcome(SecurityEventOutcome.INFO)
            .userId(user.getId())
            .email(user.getEmail())
            .requestId(requestId)
            .metadata(Map.of("challengeId", challengeId))
            .build());

        return MfaEnrollmentResult.builder()
            .challengeId(challengeId)
            .secret(totpService.toBase32(secretBytes))
            .otpauthUrl(totpService.otpauthUrl(otpIssuer, user.getEmail(), secretBytes))
            .build();
    }

    @Transactional
    public AuthFlowResult confirmMfaEnrollment(String challengeId, String code, String clientIp, String userAgent, String requestId) {
        AuthLoginChallenge challenge = loadUsableChallenge(challengeId, null);
        User user = requireChallengeUser(challenge);
        byte[] secretBytes = loadPendingMfaSecret(challenge);
        if (!totpService.verifyCode(secretBytes, code)) {
            incrementMfaFailure(challenge, user, clientIp, userAgent, requestId, "Invalid MFA code during enrollment");
            return AuthFlowResult.invalid("Invalid MFA code.");
        }

        UserMfaEnrollment enrollment = userMfaEnrollmentRepository.findByUserId(user.getId())
            .orElseGet(UserMfaEnrollment::new);
        enrollment.setUserId(user.getId());
        enrollment.setSecretCiphertext(encryptionService.encrypt(totpService.toBase32(secretBytes)));
        enrollment.setLastVerifiedAt(LocalDateTime.now());
        userMfaEnrollmentRepository.save(enrollment);

        user.setMfaEnrolledAt(LocalDateTime.now());
        userRepository.save(user);

        challenge.setPendingMfaSecret(null);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.MFA_ENROLL_CONFIRMED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .build());

        return issueSession(challenge, user, clientIp, userAgent, requestId, true);
    }

    @Transactional
    public AuthFlowResult verifyMfa(String challengeId, String code, String clientIp, String userAgent, String requestId) {
        AuthLoginChallenge challenge = loadUsableChallenge(challengeId, null);
        User user = requireChallengeUser(challenge);
        UserMfaEnrollment enrollment = userMfaEnrollmentRepository.findByUserId(user.getId())
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "MFA is not enrolled for this account."));
        String secret = encryptionService.decrypt(enrollment.getSecretCiphertext());
        if (!totpService.verifyCode(com.dbaagent.util.Base32Codec.decode(secret), code)) {
            incrementMfaFailure(challenge, user, clientIp, userAgent, requestId, "Invalid MFA code");
            return AuthFlowResult.invalid("Invalid MFA code.");
        }
        enrollment.setLastVerifiedAt(LocalDateTime.now());
        userMfaEnrollmentRepository.save(enrollment);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.MFA_VERIFIED)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .metadata(Map.of("challengeId", challengeId))
            .build());

        return issueSession(challenge, user, clientIp, userAgent, requestId, true);
    }

    @Transactional
    public AuthFlowResult beginTrustedInviteSession(
        User user,
        InviteType inviteType,
        String clientIp,
        String userAgent,
        String requestId
    ) {
        AuthLoginChallenge challenge = new AuthLoginChallenge();
        challenge.setId(UUID.randomUUID().toString());
        challenge.setUserId(user.getId());
        challenge.setEmail(user.getEmail());
        challenge.setChallengeTypeEnum(inviteType == InviteType.BOOTSTRAP ? AuthChallengeType.BOOTSTRAP : AuthChallengeType.EMAIL_OTP);
        challenge.setChallengeStateEnum(AuthChallengeState.OTP_VERIFIED);
        challenge.setClientIp(clientIp);
        challenge.setUserAgent(userAgent);
        challenge.setVerifiedAt(LocalDateTime.now());
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        authLoginChallengeRepository.save(challenge);

        return completePrimaryAuth(challenge, user, clientIp, userAgent, requestId);
    }

    @Transactional
    public Optional<AuthSessionService.SessionAuthentication> refresh(String rawRefreshToken, String clientIp, String userAgent, String requestId) {
        if (rawRefreshToken == null || rawRefreshToken.isBlank()) {
            return Optional.empty();
        }
        String tokenHash = SecurityHashUtil.sha256Hex(rawRefreshToken);
        return authSessionService.findValidSessionByRefreshToken(tokenHash)
            .flatMap(session -> userRepository.findById(session.getUserId())
                .map(user -> {
                    String roleCode = user.getRoleCode();
                    return authSessionService.refreshSession(
                        rawRefreshToken,
                        user,
                        roleCode,
                        permissionService.getEffectivePermissions(roleCode),
                        clientIp,
                        userAgent
                    );
                }).orElse(Optional.empty())
            );
    }

    @Transactional
    public void logout(String sessionId, Long userId, String requestId) {
        if (sessionId == null || userId == null) {
            return;
        }
        authSessionService.revokeSession(sessionId, "logout");
        userRepository.findById(userId).ifPresent(user ->
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.LOGOUT)
                .outcome(SecurityEventOutcome.SUCCESS)
                .userId(user.getId())
                .email(user.getEmail())
                .requestId(requestId)
                .metadata(Map.of("sessionId", sessionId))
                .build())
        );
    }

    @Transactional
    public void logoutAll(User user, String requestId) {
        authSessionService.revokeAllSessionsForUser(user.getId(), "logout_all");
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.LOGOUT_ALL)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .requestId(requestId)
            .build());
    }

    @Transactional
    public GoogleStartResult startGoogleLogin(String clientIp, String userAgent, String requestId) {
        if (!googleEnabled || googleClientId.isBlank() || googleClientSecret.isBlank() || googleRedirectUri.isBlank()) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Google Workspace login is not enabled");
        }
        String challengeId = UUID.randomUUID().toString();
        String codeVerifier = UUID.randomUUID().toString().replace("-", "") + UUID.randomUUID().toString().replace("-", "");
        String codeChallenge = pkceCodeChallenge(codeVerifier);
        AuthLoginChallenge challenge = new AuthLoginChallenge();
        challenge.setId(challengeId);
        challenge.setEmail("google-login@example.invalid");
        challenge.setChallengeTypeEnum(AuthChallengeType.GOOGLE_OIDC);
        challenge.setChallengeStateEnum(AuthChallengeState.CREATED);
        challenge.setGoogleCodeVerifier(codeVerifier);
        challenge.setClientIp(clientIp);
        challenge.setUserAgent(userAgent);
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(10));
        authLoginChallengeRepository.save(challenge);
        String redirectUrl =
            "https://accounts.google.com/o/oauth2/v2/auth?response_type=code"
                + "&client_id=" + urlEncode(googleClientId)
                + "&redirect_uri=" + urlEncode(googleRedirectUri)
                + "&scope=" + urlEncode("openid email profile")
                + "&state=" + urlEncode(challengeId)
                + "&code_challenge=" + urlEncode(codeChallenge)
                + "&code_challenge_method=S256";
        return new GoogleStartResult(challengeId, redirectUrl);
    }

    @Transactional
    public AuthFlowResult completeGoogleLogin(String state, String code, String clientIp, String userAgent, String requestId) {
        AuthLoginChallenge challenge = loadUsableChallenge(state, AuthChallengeType.GOOGLE_OIDC);
        if (code == null || code.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Missing Google authorization code");
        }
        LinkedMultiValueMap<String, String> tokenRequest = new LinkedMultiValueMap<>();
        tokenRequest.add("code", code);
        tokenRequest.add("client_id", googleClientId);
        tokenRequest.add("client_secret", googleClientSecret);
        tokenRequest.add("redirect_uri", googleRedirectUri);
        tokenRequest.add("grant_type", "authorization_code");
        tokenRequest.add("code_verifier", challenge.getGoogleCodeVerifier());

        Map<String, Object> tokenResponse = RestClient.create()
            .post()
            .uri("https://oauth2.googleapis.com/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(tokenRequest)
            .retrieve()
            .body(Map.class);
        String accessToken = tokenResponse == null ? null : Objects.toString(tokenResponse.get("access_token"), null);
        if (accessToken == null || accessToken.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Google login failed");
        }
        Map<String, Object> userInfo = RestClient.create()
            .get()
            .uri("https://openidconnect.googleapis.com/v1/userinfo")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .body(Map.class);

        String email = userInfo == null ? null : Objects.toString(userInfo.get("email"), null);
        boolean emailVerified = Boolean.parseBoolean(Objects.toString(userInfo.get("email_verified"), "false"));
        String hd = userInfo == null ? null : Objects.toString(userInfo.get("hd"), null);
        if (!emailVerified || email == null || !isAllowedWorkspaceDomain(email, hd)) {
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.GOOGLE_BLOCKED_DOMAIN)
                .outcome(SecurityEventOutcome.BLOCKED)
                .email(email)
                .clientIp(clientIp)
                .userAgent(userAgent)
                .requestId(requestId)
                .metadata(Map.of("hostedDomain", String.valueOf(hd)))
                .build());
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "This Google Workspace domain is not allowed");
        }
        User user = userRepository.findByEmailIgnoreCase(email)
            .filter(User::isActiveAccount)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "This account is not provisioned in DeepSQL"));
        user.setGoogleWorkspaceIssuer(Objects.toString(userInfo.get("iss"), "google"));
        user.setGoogleWorkspaceSubject(Objects.toString(userInfo.get("sub"), null));
        user.setEmailVerifiedAt(user.getEmailVerifiedAt() != null ? user.getEmailVerifiedAt() : LocalDateTime.now());
        userRepository.save(user);
        challenge.setUserId(user.getId());
        challenge.setEmail(user.getEmail());
        challenge.setVerifiedAt(LocalDateTime.now());
        challenge.setChallengeStateEnum(AuthChallengeState.OTP_VERIFIED);
        authLoginChallengeRepository.save(challenge);
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.GOOGLE_LOGIN_SUCCESS)
            .outcome(SecurityEventOutcome.SUCCESS)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .build());
        return completePrimaryAuth(challenge, user, clientIp, userAgent, requestId);
    }

    private AuthFlowResult completePrimaryAuth(
        AuthLoginChallenge challenge,
        User user,
        String clientIp,
        String userAgent,
        String requestId
    ) {
        if (challenge.getChallengeTypeEnum() == AuthChallengeType.EMAIL_OTP) {
            return issueSession(challenge, user, clientIp, userAgent, requestId, false);
        }
        if (!skip2fa && isWorkspaceEmail2faEnabled()) {
            AuthLoginChallenge otpChallenge = new AuthLoginChallenge();
            otpChallenge.setId(UUID.randomUUID().toString());
            otpChallenge.setUserId(user.getId());
            otpChallenge.setEmail(user.getEmail());
            otpChallenge.setChallengeTypeEnum(AuthChallengeType.EMAIL_OTP);
            otpChallenge.setChallengeStateEnum(AuthChallengeState.CREATED);
            otpChallenge.setClientIp(clientIp);
            otpChallenge.setUserAgent(userAgent);
            otpChallenge.setExpiresAt(LocalDateTime.now().plusMinutes(otpTtlMinutes));
            authLoginChallengeRepository.save(otpChallenge);
            sendOtpForChallenge(otpChallenge, user, clientIp, userAgent, requestId);

            challenge.setConsumedAt(LocalDateTime.now());
            challenge.setChallengeStateEnum(AuthChallengeState.CONSUMED);
            authLoginChallengeRepository.save(challenge);

            return AuthFlowResult.emailVerificationRequired(otpChallenge.getId(), user);
        }
        return issueSession(challenge, user, clientIp, userAgent, requestId, false);
    }

    private void sendOtpForChallenge(
        AuthLoginChallenge challenge,
        User user,
        String clientIp,
        String userAgent,
        String requestId
    ) {
        enforceStartRateLimit(user.getEmail(), clientIp);
        String otpCode = generateOtpCode();
        challenge.setOtpHash(SecurityHashUtil.sha256Hex(otpCode));
        challenge.setOtpAttemptCount(0);
        challenge.setChallengeStateEnum(AuthChallengeState.CREATED);
        challenge.setExpiresAt(LocalDateTime.now().plusMinutes(otpTtlMinutes));
        authLoginChallengeRepository.save(challenge);

        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.OTP_REQUESTED)
            .outcome(SecurityEventOutcome.INFO)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .metadata(Map.of("challengeId", challenge.getId()))
            .build());

        try {
            emailService.sendLoginOtp(user.getEmail(), otpCode, Math.toIntExact(otpTtlMinutes));
            challenge.setChallengeStateEnum(AuthChallengeState.OTP_SENT);
            authLoginChallengeRepository.save(challenge);
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.OTP_SENT)
                .outcome(SecurityEventOutcome.SUCCESS)
                .userId(user.getId())
                .email(user.getEmail())
                .clientIp(clientIp)
                .userAgent(userAgent)
                .requestId(requestId)
                .metadata(Map.of("challengeId", challenge.getId()))
                .build());
        } catch (Exception e) {
            securityEventService.log(SecurityEventService.EventRequest.builder()
                .eventType(SecurityEventType.OTP_SENT)
                .outcome(SecurityEventOutcome.FAILURE)
                .userId(user.getId())
                .email(user.getEmail())
                .clientIp(clientIp)
                .userAgent(userAgent)
                .requestId(requestId)
                .reason("Could not send email verification code")
                .metadata(Map.of("challengeId", challenge.getId()))
                .build());
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Could not send verification code");
        }
    }

    private AuthFlowResult issueSession(
        AuthLoginChallenge challenge,
        User user,
        String clientIp,
        String userAgent,
        String requestId,
        boolean mfaVerified
    ) {
        String roleCode = user.getRoleCode();
        Role role = user.getRoleEnum();
        Set<Permission> permissions = permissionService.getEffectivePermissions(roleCode);
        AuthSessionService.SessionAuthentication session = authSessionService.createSession(
            user,
            roleCode,
            permissions,
            clientIp,
            userAgent,
            mfaVerified
        );
        challenge.setConsumedAt(LocalDateTime.now());
        challenge.setChallengeStateEnum(AuthChallengeState.CONSUMED);
        authLoginChallengeRepository.save(challenge);
        user.setLastLoginAt(LocalDateTime.now());
        user.setLastLoginIp(clientIp);
        userRepository.save(user);
        return AuthFlowResult.authenticated(user, role, roleCode, permissions, session);
    }

    private void markFailedOtp(AuthLoginChallenge challenge, User user, String clientIp, String userAgent, String requestId) {
        challenge.setOtpAttemptCount((challenge.getOtpAttemptCount() == null ? 0 : challenge.getOtpAttemptCount()) + 1);
        if (challenge.getOtpAttemptCount() >= maxOtpAttempts) {
            challenge.setChallengeStateEnum(AuthChallengeState.FAILED);
        }
        authLoginChallengeRepository.save(challenge);
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.OTP_FAILED)
            .outcome(SecurityEventOutcome.FAILURE)
            .userId(user != null ? user.getId() : null)
            .email(challenge.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .metadata(Map.of("challengeId", challenge.getId(), "attempts", challenge.getOtpAttemptCount()))
            .build());
    }

    private void incrementMfaFailure(AuthLoginChallenge challenge, User user, String clientIp, String userAgent, String requestId, String reason) {
        challenge.setMfaAttemptCount((challenge.getMfaAttemptCount() == null ? 0 : challenge.getMfaAttemptCount()) + 1);
        authLoginChallengeRepository.save(challenge);
        securityEventService.log(SecurityEventService.EventRequest.builder()
            .eventType(SecurityEventType.MFA_FAILED)
            .outcome(SecurityEventOutcome.FAILURE)
            .userId(user.getId())
            .email(user.getEmail())
            .clientIp(clientIp)
            .userAgent(userAgent)
            .requestId(requestId)
            .reason(reason)
            .metadata(Map.of("challengeId", challenge.getId(), "attempts", challenge.getMfaAttemptCount()))
            .build());
    }

    private AuthLoginChallenge loadUsableChallenge(String challengeId, AuthChallengeType expectedType) {
        AuthLoginChallenge challenge = authLoginChallengeRepository.findById(challengeId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired challenge"));
        if (challenge.isExpired() || challenge.getConsumedAt() != null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid or expired challenge");
        }
        if (expectedType != null && challenge.getChallengeTypeEnum() != expectedType) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Invalid challenge type");
        }
        return challenge;
    }

    private User requireChallengeUser(AuthLoginChallenge challenge) {
        return Optional.ofNullable(challenge.getUserId())
            .flatMap(userRepository::findById)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST, "No eligible user bound to this challenge"));
    }

    private boolean isValidOtpChallenge(AuthLoginChallenge challenge, User user, String otpCode) {
        if (challenge.getOtpAttemptCount() != null && challenge.getOtpAttemptCount() >= maxOtpAttempts) {
            return false;
        }
        if (user == null || !user.isActiveAccount() || !user.isEmailVerified()) {
            return false;
        }
        if (otpCode == null || otpCode.isBlank()) {
            return false;
        }
        return Objects.equals(challenge.getOtpHash(), SecurityHashUtil.sha256Hex(otpCode.trim()));
    }

    private byte[] loadPendingMfaSecret(AuthLoginChallenge challenge) {
        if (challenge.getPendingMfaSecret() == null || challenge.getPendingMfaSecret().length == 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No MFA enrollment is pending for this challenge.");
        }
        String base32 = encryptionService.decrypt(challenge.getPendingMfaSecret());
        return com.dbaagent.util.Base32Codec.decode(base32);
    }

    private void enforceStartRateLimit(String normalizedEmail, String clientIp) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(rateLimitWindowMinutes);
        if (normalizedEmail != null) {
            long emailCount = securityEventRepository.countByEmailIgnoreCaseAndEventTypeAndCreatedAtAfter(
                normalizedEmail,
                SecurityEventType.OTP_REQUESTED.name(),
                windowStart
            );
            if (emailCount >= maxEmailStarts) {
                securityEventService.log(SecurityEventService.EventRequest.builder()
                    .eventType(SecurityEventType.OTP_RATE_LIMITED)
                    .outcome(SecurityEventOutcome.BLOCKED)
                    .email(normalizedEmail)
                    .clientIp(clientIp)
                    .build());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts. Please wait and try again.");
            }
        }
        if (clientIp != null && !clientIp.isBlank()) {
            long ipCount = securityEventRepository.countByClientIpAndEventTypeAndCreatedAtAfter(
                clientIp,
                SecurityEventType.OTP_REQUESTED.name(),
                windowStart
            );
            if (ipCount >= maxIpStarts) {
                securityEventService.log(SecurityEventService.EventRequest.builder()
                    .eventType(SecurityEventType.OTP_RATE_LIMITED)
                    .outcome(SecurityEventOutcome.BLOCKED)
                    .email(normalizedEmail)
                    .clientIp(clientIp)
                    .build());
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many sign-in attempts. Please wait and try again.");
            }
        }
    }

    private void enforcePasswordRateLimit(String normalizedEmail, String clientIp) {
        LocalDateTime windowStart = LocalDateTime.now().minusMinutes(rateLimitWindowMinutes);
        if (normalizedEmail != null) {
            long emailCount = securityEventRepository.countByEmailIgnoreCaseAndEventTypeAndCreatedAtAfter(
                normalizedEmail,
                SecurityEventType.PASSWORD_LOGIN_FAILURE.name(),
                windowStart
            );
            if (emailCount >= maxPasswordFailures) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Please wait and try again.");
            }
        }
        if (clientIp != null && !clientIp.isBlank()) {
            long ipCount = securityEventRepository.countByClientIpAndEventTypeAndCreatedAtAfter(
                clientIp,
                SecurityEventType.PASSWORD_LOGIN_FAILURE.name(),
                windowStart
            );
            if (ipCount >= maxPasswordFailures) {
                throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many login attempts. Please wait and try again.");
            }
        }
    }

    private boolean isWorkspaceEmail2faEnabled() {
        return systemConfigService.getBoolean("security.workspace.email2fa.enabled");
    }

    private boolean isAllowedWorkspaceDomain(String email, String hostedDomain) {
        if (email == null || email.isBlank()) {
            return false;
        }
        String domain = email.substring(email.indexOf('@') + 1).toLowerCase(Locale.ROOT);
        if (hostedDomain == null || hostedDomain.isBlank()) {
            return false;
        }
        return googleWorkspaceDomainRepository.findByDomainIgnoreCase(domain)
            .filter(GoogleWorkspaceDomain::getEnabled)
            .isPresent()
            && hostedDomain.equalsIgnoreCase(domain);
    }

    private String normalizeEmail(String email) {
        if (email == null || email.isBlank()) {
            return null;
        }
        return email.trim().toLowerCase(Locale.ROOT);
    }

    private String generateOtpCode() {
        return String.format("%06d", RANDOM.nextInt(1_000_000));
    }

    private String urlEncode(String value) {
        return java.net.URLEncoder.encode(value, java.nio.charset.StandardCharsets.UTF_8);
    }

    private String pkceCodeChallenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate Google PKCE challenge", e);
        }
    }

    @Builder
    public record EmailStartResult(String challengeId, String message) {
    }

    @Builder
    public record MfaEnrollmentResult(String challengeId, String secret, String otpauthUrl) {
    }

    @Builder
    public record GoogleStartResult(String challengeId, String redirectUrl) {
    }

    @Builder
    public record AuthFlowResult(
        boolean success,
        String message,
        String nextChallengeId,
        boolean mfaRequired,
        boolean mfaSetupRequired,
        User user,
        /**
         * The built-in role, or null when the user holds a custom role. Prefer
         * {@link #roleCode()} — a null here does NOT mean "not authenticated".
         */
        Role role,
        /** The user's role code, built-in or custom. Non-null on a successful auth. */
        String roleCode,
        Set<Permission> permissions,
        AuthSessionService.SessionAuthentication sessionAuthentication
    ) {
        static AuthFlowResult invalid(String message) {
            return AuthFlowResult.builder().success(false).message(message).build();
        }

        static AuthFlowResult mfaRequired(String challengeId, boolean setupRequired) {
            return AuthFlowResult.builder()
                .success(true)
                .nextChallengeId(challengeId)
                .mfaRequired(true)
                .mfaSetupRequired(setupRequired)
                .build();
        }

        static AuthFlowResult emailVerificationRequired(String challengeId, User user) {
            return AuthFlowResult.builder()
                .success(true)
                .nextChallengeId(challengeId)
                .message("Verification code required")
                .user(user)
                .build();
        }

        static AuthFlowResult authenticated(
            User user,
            Role role,
            Set<Permission> permissions,
            AuthSessionService.SessionAuthentication sessionAuthentication
        ) {
            return authenticated(user, role, user != null ? user.getRoleCode() : null,
                permissions, sessionAuthentication);
        }

        static AuthFlowResult authenticated(
            User user,
            Role role,
            String roleCode,
            Set<Permission> permissions,
            AuthSessionService.SessionAuthentication sessionAuthentication
        ) {
            return AuthFlowResult.builder()
                .success(true)
                .user(user)
                .role(role)
                .roleCode(roleCode)
                .permissions(permissions)
                .sessionAuthentication(sessionAuthentication)
                .build();
        }
    }
}
