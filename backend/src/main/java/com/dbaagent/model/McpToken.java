package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;

import java.time.LocalDateTime;

@Entity
@Table(name = "mcp_tokens", indexes = {
    @Index(name = "idx_mcp_tokens_user_id", columnList = "user_id"),
    @Index(name = "idx_mcp_tokens_public_id", columnList = "public_id", unique = true),
    @Index(name = "idx_mcp_tokens_status", columnList = "status")
})
@Data
public class McpToken {

    public enum Status {
        ACTIVE,
        REVOKED
    }

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Column(nullable = false, length = 120)
    private String name;

    @Column(name = "public_id", nullable = false, unique = true, length = 32)
    private String publicId;

    @Column(name = "token_prefix", nullable = false, length = 64)
    private String tokenPrefix;

    @Lob
    @Column(name = "token_hash", nullable = false)
    private byte[] tokenHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used_at")
    private LocalDateTime lastUsedAt;

    @Column(name = "expires_at")
    private LocalDateTime expiresAt;

    @Column(name = "last_used_ip", length = 128)
    private String lastUsedIp;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = Status.ACTIVE;
        }
    }
}
