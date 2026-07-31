package com.dbaagent.service;

import com.dbaagent.model.AuthLoginChallenge;
import com.dbaagent.model.SecurityEventType;
import com.dbaagent.model.User;
import com.dbaagent.repository.AuthLoginChallengeRepository;
import com.dbaagent.repository.GoogleWorkspaceDomainRepository;
import com.dbaagent.repository.SecurityEventRepository;
import com.dbaagent.repository.UserMfaEnrollmentRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.security.EncryptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PasswordlessAuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private AuthLoginChallengeRepository authLoginChallengeRepository;
    @Mock private UserMfaEnrollmentRepository userMfaEnrollmentRepository;
    @Mock private AuthSessionService authSessionService;
    @Mock private EmailService emailService;
    @Mock private TotpService totpService;
    @Mock private EncryptionService encryptionService;
    @Mock private PermissionService permissionService;
    @Mock private SecurityEventService securityEventService;
    @Mock private GoogleWorkspaceDomainRepository googleWorkspaceDomainRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private SecurityEventRepository securityEventRepository;
    @Mock private SystemConfigService systemConfigService;

    @InjectMocks
    private PasswordlessAuthService service;

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "otpTtlMinutes", 10L);
        ReflectionTestUtils.setField(service, "maxOtpAttempts", 5);
        ReflectionTestUtils.setField(service, "rateLimitWindowMinutes", 15L);
        ReflectionTestUtils.setField(service, "maxEmailStarts", 5);
        ReflectionTestUtils.setField(service, "maxIpStarts", 20);
        ReflectionTestUtils.setField(service, "maxPasswordFailures", 10);
        ReflectionTestUtils.setField(service, "adminMfaEnabled", false);

        when(authLoginChallengeRepository.save(any(AuthLoginChallenge.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));
        when(systemConfigService.getBoolean("security.workspace.email2fa.enabled")).thenReturn(true);
        when(securityEventRepository.countByEmailIgnoreCaseAndEventTypeAndCreatedAtAfter(
            anyString(), eq(SecurityEventType.PASSWORD_LOGIN_FAILURE.name()), any(LocalDateTime.class))
        ).thenReturn(0L);
        when(securityEventRepository.countByClientIpAndEventTypeAndCreatedAtAfter(
            anyString(), eq(SecurityEventType.PASSWORD_LOGIN_FAILURE.name()), any(LocalDateTime.class))
        ).thenReturn(0L);
    }

    @Test
    void loginWithPassword_allowsFreshTwoFactorChallengeWhenNoOtpRequestsExist() throws Exception {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase("alex.doe@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret-pass", "encoded-password")).thenReturn(true);
        when(securityEventRepository.countByEmailIgnoreCaseAndEventTypeAndCreatedAtAfter(
            eq("alex.doe@example.com"), eq(SecurityEventType.OTP_REQUESTED.name()), any(LocalDateTime.class))
        ).thenReturn(0L);
        when(securityEventRepository.countByClientIpAndEventTypeAndCreatedAtAfter(
            eq("127.0.0.1"), eq(SecurityEventType.OTP_REQUESTED.name()), any(LocalDateTime.class))
        ).thenReturn(0L);

        PasswordlessAuthService.AuthFlowResult result = service.loginWithPassword(
            "alex.doe@example.com",
            "secret-pass",
            "127.0.0.1",
            "JUnit",
            "req-1"
        );

        assertThat(result.success()).isTrue();
        assertThat(result.nextChallengeId()).isNotBlank();
        assertThat(result.sessionAuthentication()).isNull();
        verify(emailService).sendLoginOtp(eq("alex.doe@example.com"), anyString(), eq(10));

        ArgumentCaptor<AuthLoginChallenge> challengeCaptor = ArgumentCaptor.forClass(AuthLoginChallenge.class);
        verify(authLoginChallengeRepository, atLeastOnce()).save(challengeCaptor.capture());
        assertThat(challengeCaptor.getAllValues())
            .anyMatch(challenge -> "EMAIL_OTP".equals(challenge.getChallengeType()) && challenge.getOtpHash() != null);
    }

    @Test
    void loginWithPassword_blocksOnlyWhenOtpRequestRateLimitIsActuallyExceeded() throws Exception {
        User user = activeUser();
        when(userRepository.findByEmailIgnoreCase("alex.doe@example.com")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("secret-pass", "encoded-password")).thenReturn(true);
        when(securityEventRepository.countByEmailIgnoreCaseAndEventTypeAndCreatedAtAfter(
            eq("alex.doe@example.com"), eq(SecurityEventType.OTP_REQUESTED.name()), any(LocalDateTime.class))
        ).thenReturn(5L);

        ResponseStatusException error = assertThrows(ResponseStatusException.class, () -> service.loginWithPassword(
            "alex.doe@example.com",
            "secret-pass",
            "127.0.0.1",
            "JUnit",
            "req-2"
        ));

        assertThat(error.getStatusCode().value()).isEqualTo(429);
        assertThat(error.getReason()).isEqualTo("Too many sign-in attempts. Please wait and try again.");
        verify(emailService, never()).sendLoginOtp(anyString(), anyString(), anyInt());
    }

    private User activeUser() {
        User user = new User();
        user.setId(1L);
        user.setUsername("admin");
        user.setEmail("alex.doe@example.com");
        user.setPassword("encoded-password");
        user.setRole("ADMIN");
        user.setAccountStatus("ACTIVE");
        user.setEmailVerifiedAt(LocalDateTime.now());
        return user;
    }
}
