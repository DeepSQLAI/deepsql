package com.dbaagent.service;

import com.dbaagent.repository.AuthLoginChallengeRepository;
import com.dbaagent.repository.SecurityEventRepository;
import com.dbaagent.repository.UserSessionRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * Enforces a rolling retention window on security/user-log tables.
 *
 * <p>Tables covered:
 * <ul>
 *   <li>{@code security_event}      – audit trail of logins, permission changes, etc.</li>
 *   <li>{@code user_session}        – active and expired user sessions.</li>
 *   <li>{@code auth_login_challenge}– OTP / OAuth login challenges.</li>
 * </ul>
 *
 * <p>Default retention: 30 days, configurable via
 * {@code security.audit.retention-days} in {@code application.properties}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SecurityCleanupService {

    private final SecurityEventRepository securityEventRepository;
    private final UserSessionRepository userSessionRepository;
    private final AuthLoginChallengeRepository authLoginChallengeRepository;

    @Value("${security.audit.retention-days:30}")
    private int retentionDays;

    @Transactional
    public void cleanupOldLogs() {
        LocalDateTime cutoff = LocalDateTime.now().minusDays(retentionDays);

        int events = securityEventRepository.deleteByCreatedAtBefore(cutoff);
        int sessions = userSessionRepository.deleteByRefreshExpiresAtBefore(cutoff);
        int challenges = authLoginChallengeRepository.deleteByExpiresAtBefore(cutoff);

        log.info("Security log retention ({}d): deleted {} audit events, {} expired sessions, {} expired challenges",
                retentionDays, events, sessions, challenges);
    }
}
