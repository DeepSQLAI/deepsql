package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;

@Entity
@Table(name = "user_invite")
@Data
public class UserInvite {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private String email;

    private String username;

    @Column(nullable = false)
    private String role;

    @Column(name = "token_hash", nullable = false, unique = true)
    private String tokenHash;

    @Column(name = "invited_by")
    private String invitedBy;

    @Column(name = "invite_type", nullable = false)
    private String inviteType = InviteType.STANDARD.name();

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "accepted_at")
    private LocalDateTime acceptedAt;

    @Column(name = "revoked_at")
    private LocalDateTime revokedAt;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Transient
    public InviteType getInviteTypeEnum() {
        return InviteType.fromString(inviteType);
    }

    public void setInviteTypeEnum(InviteType inviteType) {
        this.inviteType = inviteType != null ? inviteType.name() : InviteType.STANDARD.name();
    }

    @Transient
    public boolean isUsable() {
        return acceptedAt == null && revokedAt == null && expiresAt != null && expiresAt.isAfter(LocalDateTime.now());
    }
}
