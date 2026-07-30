package com.dbaagent.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Enumeration of user roles in the system.
 * Roles are hierarchical: each role inherits permissions from lower roles.
 *
 * Hierarchy (lowest to highest):
 *   DEVELOPER (0) → ADMIN (1)
 *
 * Permission assignment works via Permission.defaultMinRole:
 *   - Permission with defaultMinRole=DEVELOPER → granted to DEVELOPER, ADMIN
 *   - Permission with defaultMinRole=ADMIN → granted to ADMIN only
 *
 * This can be overridden via RolePermissionOverride for exceptions.
 */
public enum Role {
    /**
     * DEVELOPER: Default product user.
     * Can use Chat and the SQL Editor.
     */
    DEVELOPER("Access to Chat and the SQL Editor"),

    /**
     * ADMIN: Full access.
     * Inherits all DEVELOPER permissions plus access to all product areas and admin controls.
     */
    ADMIN("Access to all product areas and administrative controls");

    private final String description;

    Role(String description) {
        this.description = description;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Get the default permissions for this role based on hierarchy.
     * A role gets all permissions where permission.defaultMinRole <= this role.
     */
    public Set<Permission> getDefaultPermissions() {
        return Arrays.stream(Permission.values())
            .filter(p -> p.isGrantedByDefaultTo(this))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }

    /**
     * Get all permissions for this role.
     * Alias for getDefaultPermissions() for simpler API usage.
     */
    public Set<Permission> getPermissions() {
        return getDefaultPermissions();
    }

    /**
     * Check if this role has a permission by default (without considering overrides).
     */
    public boolean hasPermissionByDefault(Permission permission) {
        return permission.isGrantedByDefaultTo(this);
    }

    /**
     * Check if this role has a specific permission.
     * Alias for hasPermissionByDefault() for simpler API usage.
     */
    public boolean hasPermission(Permission permission) {
        return hasPermissionByDefault(permission);
    }

    /**
     * Check if this role is at or above another role in the hierarchy.
     * Used for permission inheritance.
     */
    public boolean isAtLeast(Role other) {
        return this.ordinal() >= other.ordinal();
    }

    /**
     * Check if this role is strictly above another role in the hierarchy.
     */
    public boolean isAbove(Role other) {
        return this.ordinal() > other.ordinal();
    }

    /**
     * Get a role by name, case-insensitive.
     * Legacy roles collapse into DEVELOPER for backward compatibility.
     */
    public static Role fromString(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return DEVELOPER;
        }
        return switch (roleName.trim().toUpperCase()) {
            case "ADMIN" -> ADMIN;
            case "DEVELOPER", "EDITOR", "VIEWER", "USER" -> DEVELOPER;
            default -> DEVELOPER;
        };
    }

    /**
     * Get all roles at or above the given minimum role.
     */
    public static Set<Role> getRolesAtOrAbove(Role minRole) {
        return Arrays.stream(values())
            .filter(r -> r.isAtLeast(minRole))
            .collect(Collectors.toSet());
    }
}
