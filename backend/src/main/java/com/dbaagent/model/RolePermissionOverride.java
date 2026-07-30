package com.dbaagent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;

/**
 * Entity for storing custom role-permission overrides.
 *
 * This table is SPARSE - it only stores exceptions to the default
 * permission assignments defined in Permission.defaultMinRole.
 *
 * Example use cases:
 * - Grant DEVELOPER access to an admin-only capability for a specific rollout
 * - Revoke EXPORT_DATA from DEVELOPER for a restricted environment
 */
@Entity
@Table(name = "role_permission_overrides", uniqueConstraints = {
    @UniqueConstraint(columnNames = {"role", "permission_code"})
})
public class RolePermissionOverride {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    private Role role;

    @Column(name = "permission_code", nullable = false)
    @Enumerated(EnumType.STRING)
    private Permission permissionCode;

    /**
     * If true, grants the permission to this role (override default denial).
     * If false, revokes the permission from this role (override default grant).
     */
    @Column(nullable = false)
    private boolean granted;

    /**
     * Optional reason for the override (for audit purposes).
     */
    @Column(length = 500)
    private String reason;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "updated_by")
    private String updatedBy;

    public RolePermissionOverride() {
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public RolePermissionOverride(Role role, Permission permissionCode, boolean granted, String updatedBy) {
        this();
        this.role = role;
        this.permissionCode = permissionCode;
        this.granted = granted;
        this.updatedBy = updatedBy;
    }

    public RolePermissionOverride(Role role, Permission permissionCode, boolean granted, String reason, String updatedBy) {
        this(role, permissionCode, granted, updatedBy);
        this.reason = reason;
    }

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    // Getters and Setters
    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Role getRole() {
        return role;
    }

    public void setRole(Role role) {
        this.role = role;
    }

    public Permission getPermissionCode() {
        return permissionCode;
    }

    public void setPermissionCode(Permission permissionCode) {
        this.permissionCode = permissionCode;
    }

    public boolean isGranted() {
        return granted;
    }

    public void setGranted(boolean granted) {
        this.granted = granted;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }
}
