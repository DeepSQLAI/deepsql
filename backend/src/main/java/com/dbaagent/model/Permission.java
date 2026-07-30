package com.dbaagent.model;

/**
 * Enumeration of all permissions in the system.
 * Each permission has a description and a default minimum role.
 *
 * The defaultMinRole determines which roles get this permission by default
 * through role hierarchy inheritance:
 *   - DEVELOPER permissions: Available to DEVELOPER, ADMIN
 *   - ADMIN permissions: Available to ADMIN only
 *
 * This can be overridden via RolePermissionOverride for exceptions.
 */
public enum Permission {
    // ==================== ADMIN PRODUCT PERMISSIONS ====================
    VIEW_DASHBOARD("View dashboards and overview information", Role.ADMIN),
    VIEW_SCHEMA("Browse database schema, tables, and columns", Role.ADMIN),
    VIEW_SLOW_QUERIES("View slow query analysis and history", Role.ADMIN),
    VIEW_BRAIN("View Brain overview and database insights", Role.ADMIN),
    VIEW_PERFORMANCE("View performance metrics and insights", Role.ADMIN),
    VIEW_GROWTH("View growth monitoring data", Role.ADMIN),
    VIEW_PLAYBOOKS("View playbook definitions and history", Role.ADMIN),

    // ==================== DEVELOPER PERMISSIONS ====================
    EXECUTE_QUERIES("Execute SQL queries in SQL Runner", Role.DEVELOPER),
    USE_CHAT("Use the AI chat assistant", Role.DEVELOPER),
    EXPORT_DATA("Export query results and reports", Role.DEVELOPER),

    // ==================== ADMIN ACTION PERMISSIONS ====================
    RUN_ANALYSIS("Run analysis tasks (Key Columns, Schema, Anti-patterns)", Role.ADMIN),
    RUN_INGESTION("Run slow query log ingestion", Role.ADMIN),
    EXECUTE_PLAYBOOKS("Execute playbooks and automation", Role.ADMIN),
    USE_INDEX_ADVISOR("Apply Index Advisor recommendations", Role.ADMIN),
    MANAGE_ALERTS("Acknowledge and manage alerts", Role.ADMIN),

    // ==================== ADMIN PERMISSIONS (ADMIN ONLY) ====================
    MANAGE_CONNECTIONS("Create, edit, and delete database connections", Role.ADMIN),
    MANAGE_USERS("View, edit roles, and delete users", Role.ADMIN),
    MANAGE_INVITE_CODES("Generate and manage invite codes", Role.ADMIN),
    MANAGE_SETTINGS("Modify system settings and configurations", Role.ADMIN),
    MANAGE_PERMISSIONS("Manage role-permission overrides", Role.ADMIN);

    private final String description;
    private final Role defaultMinRole;

    Permission(String description, Role defaultMinRole) {
        this.description = description;
        this.defaultMinRole = defaultMinRole;
    }

    public String getDescription() {
        return description;
    }

    /**
     * The minimum role that gets this permission by default.
     * Higher roles in the hierarchy automatically inherit this permission.
     */
    public Role getDefaultMinRole() {
        return defaultMinRole;
    }

    /**
     * Check if a role has this permission by default (via hierarchy).
     * A role has the permission if it's at or above the defaultMinRole.
     */
    public boolean isGrantedByDefaultTo(Role role) {
        return role.isAtLeast(defaultMinRole);
    }
}
