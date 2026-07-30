package com.dbaagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "auth_login_challenge")
@Data
public class AuthLoginChallenge {
    @Id
    private String id;

    @Column(name = "user_id")
    private Long userId;

    @Column(nullable = false)
    private String email;

    @Column(name = "challenge_type", nullable = false)
    private String challengeType;

    @Column(name = "challenge_state", nullable = false)
    private String challengeState;

    @Column(name = "otp_hash")
    private String otpHash;

    @Column(name = "otp_attempt_count", nullable = false)
    private Integer otpAttemptCount = 0;

    @Column(name = "resend_count", nullable = false)
    private Integer resendCount = 0;

    @Column(name = "mfa_attempt_count", nullable = false)
    private Integer mfaAttemptCount = 0;

    @Column(name = "google_code_verifier")
    private String googleCodeVerifier;

    @Column(name = "pending_mfa_secret")
    private byte[] pendingMfaSecret;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    public AuthChallengeType getChallengeTypeEnum() {
        return challengeType == null ? null : AuthChallengeType.valueOf(challengeType);
    }

    public void setChallengeTypeEnum(AuthChallengeType challengeType) {
        this.challengeType = challengeType != null ? challengeType.name() : null;
    }

    @Transient
    public AuthChallengeState getChallengeStateEnum() {
        return challengeState == null ? null : AuthChallengeState.valueOf(challengeState);
    }

    public void setChallengeStateEnum(AuthChallengeState challengeState) {
        this.challengeState = challengeState != null ? challengeState.name() : null;
    }

    @Transient
    public boolean isExpired() {
        return expiresAt != null && expiresAt.isBefore(LocalDateTime.now());
    }
}
