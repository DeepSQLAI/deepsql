package com.dbaagent.model;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Built-in user roles.
 *
 * <p>These are <em>not</em> a hierarchy. The old model ranked DEVELOPER below ADMIN and
 * compared roles with {@code ordinal()}; the current roles deliberately overlap without
 * nesting — DATA_ENGINEER can open Dashboards but not Digest, DEVELOPER can open Digest
 * but cannot edit connection settings — so there is no ordering to compare. Every
 * authorization question is "does this role hold permission X", answered by
 * {@link Permission#isGrantedByDefaultTo} and, with overrides applied, by
 * {@code PermissionService}.
 *
 * <p>Roles beyond these are defined at runtime as {@link CustomRole} rows. A user's
 * {@code role} column holds either one of these names or a custom role's code, which is
 * why {@link #fromString} returns null for anything unrecognised rather than silently
 * downgrading to DEVELOPER — a custom role name must not be mistaken for a built-in one.
 */
public enum Role {
    /** Full access to every product area and all administrative controls. */
    ADMIN("Admin", "Full access to all product areas and administrative controls"),

    /** All menu items and connection settings, but not user creation. */
    DBA("DBA", "All product areas and connection settings, except user management"),

    /** Agent, Dashboards, and the SQL Editor. */
    DATA_ENGINEER("Data Engineer", "Agent, Dashboards, and the SQL Editor"),

    /** Agent, Digest, Dashboards, Performance, and the SQL Editor. */
    DEVELOPER("Developer", "Agent, Digest, Dashboards, Performance, and the SQL Editor");

    private final String displayName;
    private final String description;

    Role(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /** The permissions this role holds by default, before any override is applied. */
    public Set<Permission> getDefaultPermissions() {
        return Arrays.stream(Permission.values())
            .filter(p -> p.isGrantedByDefaultTo(this))
            .collect(Collectors.toCollection(() -> EnumSet.noneOf(Permission.class)));
    }

    /** Alias for {@link #getDefaultPermissions()}. */
    public Set<Permission> getPermissions() {
        return getDefaultPermissions();
    }

    /** Whether this role holds the permission by default (ignores overrides). */
    public boolean hasPermissionByDefault(Permission permission) {
        return permission != null && permission.isGrantedByDefaultTo(this);
    }

    /** Alias for {@link #hasPermissionByDefault}. */
    public boolean hasPermission(Permission permission) {
        return hasPermissionByDefault(permission);
    }

    public boolean isAdmin() {
        return this == ADMIN;
    }

    /**
     * Resolve a built-in role by name, case-insensitive.
     *
     * <p>Returns {@code null} when the name is not a built-in role — the caller is then
     * expected to look for a {@link CustomRole} with that code. Legacy role names that
     * predate this enum collapse into DEVELOPER, which is what installs upgrading from
     * the two-role model carry in their {@code users.role} column.
     */
    public static Role fromString(String roleName) {
        if (roleName == null || roleName.isBlank()) {
            return null;
        }
        return switch (roleName.trim().toUpperCase()) {
            case "ADMIN" -> ADMIN;
            case "DBA" -> DBA;
            case "DATA_ENGINEER", "DATA-ENGINEER", "DATAENGINEER" -> DATA_ENGINEER;
            case "DEVELOPER", "EDITOR", "VIEWER", "USER" -> DEVELOPER;
            default -> null;
        };
    }

    /** As {@link #fromString}, but falls back to DEVELOPER instead of returning null. */
    public static Role fromStringOrDefault(String roleName) {
        Role role = fromString(roleName);
        return role != null ? role : DEVELOPER;
    }

    public static boolean isBuiltIn(String roleName) {
        return fromString(roleName) != null;
    }
}
