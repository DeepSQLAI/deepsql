package com.dbaagent.model;

import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Transient;
import jakarta.persistence.Column;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.Set;

@Entity
@Table(name = "users")
@Data
public class User {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(unique = true, nullable = false)
    private String username;

    @Column(nullable = false)
    private String password;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false)
    private String role = "DEVELOPER"; // "DEVELOPER", "ADMIN"

    @Column(name = "email_verified_at")
    private LocalDateTime emailVerifiedAt;

    @Column(name = "account_status")
    private String accountStatus = UserAccountStatus.ACTIVE.name();

    @Column(name = "last_login_at")
    private LocalDateTime lastLoginAt;

    @Column(name = "last_login_ip")
    private String lastLoginIp;

    @Column(name = "google_workspace_subject")
    private String googleWorkspaceSubject;

    @Column(name = "google_workspace_issuer")
    private String googleWorkspaceIssuer;

    @Column(name = "mfa_enrolled_at")
    private LocalDateTime mfaEnrolledAt;

    @Column(name = "invite_code_id")
    private Long inviteCodeId;

    @Column(name = "invited_at")
    private LocalDateTime invitedAt;

    /**
     * Get the Role enum for this user.
     * Returns DEVELOPER as default if role is null or invalid.
     */
    @Transient
    public Role getRoleEnum() {
        return Role.fromString(this.role);
    }

    /**
     * Set the role from a Role enum.
     */
    public void setRoleEnum(Role role) {
        this.role = role != null ? role.name() : "DEVELOPER";
    }

    /**
     * Get all permissions for this user based on their role.
     */
    @Transient
    public Set<Permission> getPermissions() {
        return getRoleEnum().getPermissions();
    }

    /**
     * Check if this user has a specific permission.
     */
    @Transient
    public boolean hasPermission(Permission permission) {
        return getRoleEnum().hasPermission(permission);
    }

    /**
     * Check if this user has at least the specified role level.
     */
    @Transient
    public boolean hasRole(Role requiredRole) {
        return getRoleEnum().isAtLeast(requiredRole);
    }

    /**
     * Check if this user is an admin.
     */
    @Transient
    public boolean isAdmin() {
        return getRoleEnum() == Role.ADMIN;
    }

    @Transient
    public UserAccountStatus getAccountStatusEnum() {
        return UserAccountStatus.fromString(this.accountStatus);
    }

    public void setAccountStatusEnum(UserAccountStatus status) {
        this.accountStatus = status != null ? status.name() : UserAccountStatus.ACTIVE.name();
    }

    @Transient
    public boolean isEmailVerified() {
        return emailVerifiedAt != null;
    }

    @Transient
    public boolean isActiveAccount() {
        return getAccountStatusEnum() == UserAccountStatus.ACTIVE;
    }
}
