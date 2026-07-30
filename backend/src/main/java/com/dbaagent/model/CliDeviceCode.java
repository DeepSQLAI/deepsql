package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "cli_device_code")
@Data
public class CliDeviceCode {

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

    @Column(name = "device_code_hash", nullable = false, length = 255)
    private String deviceCodeHash;

    @Column(name = "user_code_hash", nullable = false, length = 255)
    private String userCodeHash;

    @Column(name = "user_code_prefix", nullable = false, length = 8)
    private String userCodePrefix;

    @Column(name = "hostname", length = 255)
    private String hostname;

    @Column(name = "client_label", length = 255)
    private String clientLabel;

    @Column(name = "interval_seconds", nullable = false)
    private int intervalSeconds;

    @Column(name = "status", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    private Status status;

    @Column(name = "approved_by_user_id")
    private Long approvedByUserId;

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

    @Column(name = "last_polled_at")
    private LocalDateTime lastPolledAt;

    @PrePersist
    void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
