package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "cli_authorization")
@Data
public class CliAuthorization {

    public enum Status {
        PENDING,
        APPROVED,
        CONSUMED,
        DENIED,
        EXPIRED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "authorization_id", nullable = false, length = 64, unique = true)
    private String authorizationId;

    @Column(name = "code_challenge_hash", nullable = false, length = 255)
    private String codeChallengeHash;

    @Column(name = "redirect_uri", nullable = false, length = 512)
    private String redirectUri;

    @Column(name = "state", length = 255)
    private String state;

    @Column(name = "hostname", length = 255)
    private String hostname;

    @Column(name = "client_label", length = 255)
    private String clientLabel;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

    @Column(name = "code_hash", length = 255)
    private String codeHash;

    @Column(name = "issued_token_id")
    private Long issuedTokenId;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "approved_at")
    private LocalDateTime approvedAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
