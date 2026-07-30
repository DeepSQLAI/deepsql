package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "slack_link_code")
@Data
public class SlackLinkCode {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "deepsql_username", nullable = false, length = 255)
    private String deepsqlUsername;

    @Column(name = "code_hash", nullable = false, length = 255)
    private String codeHash;

    @Column(name = "encrypted_code", columnDefinition = "BYTEA")
    private byte[] encryptedCode;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "consumed_at")
    private LocalDateTime consumedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    void onCreate() {
        createdAt = LocalDateTime.now();
    }
}
