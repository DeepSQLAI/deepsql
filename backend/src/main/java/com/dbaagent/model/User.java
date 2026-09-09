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
     * The built-in {@link Role} for this user, or null when {@code role} names a
     * custom role.
     *
     * <p>It used to fall back to DEVELOPER for anything unrecognised. That is wrong now
     * that custom roles exist: a user holding the custom role "ANALYST" would report
     * itself as a DEVELOPER and inherit Developer's permissions instead of the ones the
     * admin ticked. Callers that need a permission answer must go through
     * {@code PermissionService}, which resolves both kinds by role code.
     */
    @Transient
    public Role getRoleEnum() {
        return Role.fromString(this.role);
    }

    /** The user's role code, built-in or custom, as stored. */
    @Transient
    public String getRoleCode() {
        return role == null || role.isBlank() ? Role.DEVELOPER.name() : role.trim().toUpperCase();
    }

    /**
     * Set the role from a Role enum.
     */
    public void setRoleEnum(Role role) {
        this.role = role != null ? role.name() : "DEVELOPER";
    }

    /**
     * Default permissions for this user's built-in role.
     *
     * <p>Empty for a custom role — the authoritative answer for any role lives in
     * {@code PermissionService.getEffectivePermissions(roleCode)}, which also applies
     * overrides. This remains only for callers that already had a built-in role in hand.
     */
    @Transient
    public Set<Permission> getPermissions() {
        Role builtIn = getRoleEnum();
        return builtIn == null ? java.util.EnumSet.noneOf(Permission.class) : builtIn.getPermissions();
    }

    /**
     * Whether this user's built-in role holds a permission by default.
     * Custom roles and overrides are not consulted here; use {@code PermissionService}.
     */
    @Transient
    public boolean hasPermission(Permission permission) {
        Role builtIn = getRoleEnum();
        return builtIn != null && builtIn.hasPermission(permission);
    }

    /**
     * Whether this user holds exactly the given built-in role.
     *
     * <p>Formerly {@code isAtLeast}, a rank comparison. The roles no longer form a
     * hierarchy — DATA_ENGINEER and DEVELOPER each have menus the other lacks — so an
     * ordering comparison has no meaning and would silently answer nonsense.
     */
    @Transient
    public boolean hasRole(Role requiredRole) {
        return requiredRole != null && getRoleEnum() == requiredRole;
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
