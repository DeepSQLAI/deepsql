package com.dbaagent.model;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * An admin-defined role: a name plus an explicit set of permissions.
 *
 * <p>Unlike the built-in {@link Role} values, a custom role has no defaults to inherit —
 * its permission set is exactly what an admin ticked, stored as a comma-separated list of
 * {@link Permission} codes. Unknown codes (a permission removed in a later release) are
 * dropped on read rather than failing, so an old row cannot break login.
 *
 * <p>The {@code code} is the value written to {@code users.role}, so it shares a namespace
 * with the built-in role names; {@code DashboardWorkspaceService} and the role admin API
 * both refuse to create a custom role whose code collides with a built-in one.
 */
@Entity
@Table(name = "custom_roles", uniqueConstraints = {
    @UniqueConstraint(name = "ux_custom_roles_code", columnNames = {"code"})
})
public class CustomRole {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** Stable uppercase identifier written to {@code users.role}. */
    @Column(nullable = false, length = 64)
    private String code;

    @Column(nullable = false, length = 128)
    private String name;

    @Column(length = 500)
    private String description;

    /** Comma-separated {@link Permission} codes. */
    @Column(name = "permission_codes", columnDefinition = "TEXT")
    private String permissionCodes;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt = LocalDateTime.now();

    @Column(name = "created_by", length = 255)
    private String createdBy;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @PreUpdate
    public void preUpdate() {
        this.updatedAt = LocalDateTime.now();
    }

    /** The permissions this role grants, with unknown codes dropped. */
    @Transient
    public Set<Permission> getPermissions() {
        if (permissionCodes == null || permissionCodes.isBlank()) {
            return EnumSet.noneOf(Permission.class);
        }
        Set<Permission> resolved = EnumSet.noneOf(Permission.class);
        for (String raw : permissionCodes.split(",")) {
            Permission permission = Permission.fromCode(raw);
            if (permission != null) {
                resolved.add(permission);
            }
        }
        return resolved;
    }

    public void setPermissions(Set<Permission> permissions) {
        if (permissions == null || permissions.isEmpty()) {
            this.permissionCodes = "";
            return;
        }
        // LinkedHashSet over the enum's natural order keeps the stored string stable, so
        // an unchanged permission set does not produce a spurious row update.
        this.permissionCodes = new LinkedHashSet<>(EnumSet.copyOf(permissions)).stream()
            .map(Enum::name)
            .collect(Collectors.joining(","));
    }

    @Transient
    public Set<String> getPermissionCodeSet() {
        Set<String> codes = getPermissions().stream()
            .map(Enum::name)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        return Collections.unmodifiableSet(codes);
    }

    public Long getId() { return id; }
    public void setId(Long id) { this.id = id; }

    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getPermissionCodes() { return permissionCodes; }
    public void setPermissionCodes(String permissionCodes) { this.permissionCodes = permissionCodes; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }

    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }

    public String getUpdatedBy() { return updatedBy; }
    public void setUpdatedBy(String updatedBy) { this.updatedBy = updatedBy; }
}
