package com.dbaagent.model;

/**
 * Enumeration of all permissions in the system.
 *
 * <p>Permissions are the unit of authorization. A {@link Role} is nothing more than a
 * named bundle of these, and a {@link CustomRole} is a bundle an admin defined at
 * runtime. There is no longer a role hierarchy: the built-in roles overlap without
 * nesting (Data Engineer sees Dashboards but not Digest; Developer sees Digest but
 * cannot touch connection settings), so "is role A at least role B" is not a question
 * with an answer. Ask whether a role holds a permission instead.
 *
 * <p>{@link #defaultRoles} lists which built-in roles hold the permission out of the box.
 * {@link RolePermissionOverride} can still add or remove one per role.
 */
public enum Permission {
    // ==================== SECTION / MENU PERMISSIONS ====================
    // One per top-level sidebar destination. These drive both the nav in the UI and
    // the server-side section checks, so a hidden menu is not merely cosmetic.
    VIEW_AGENT("Open the Agent chat section", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),
    VIEW_DASHBOARDS("Open the Dashboards section", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),
    VIEW_DIGEST("Open the Digest section", Role.ADMIN, Role.DBA, Role.DEVELOPER),
    VIEW_BRAIN("Open the Brain section and database insights", Role.ADMIN, Role.DBA),
    VIEW_PERFORMANCE("Open the Performance section (slow queries and workload)", Role.ADMIN, Role.DBA, Role.DEVELOPER),
    VIEW_EDITOR("Open the SQL Editor section", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),

    // ==================== READ PERMISSIONS ====================
    VIEW_DASHBOARD("View dashboards and overview information", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),
    VIEW_SCHEMA("Browse database schema, tables, and columns", Role.ADMIN, Role.DBA),
    VIEW_SLOW_QUERIES("View slow query analysis and history", Role.ADMIN, Role.DBA, Role.DEVELOPER),
    VIEW_GROWTH("View growth monitoring data", Role.ADMIN, Role.DBA),
    VIEW_PLAYBOOKS("View playbook definitions and history", Role.ADMIN, Role.DBA),

    // ==================== CORE PRODUCT PERMISSIONS ====================
    EXECUTE_QUERIES("Execute SQL queries in SQL Runner", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),
    USE_CHAT("Use the AI chat assistant", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),
    EXPORT_DATA("Export query results and reports", Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),

    // ==================== ACTION PERMISSIONS ====================
    RUN_ANALYSIS("Run analysis tasks (Key Columns, Schema, Anti-patterns)", Role.ADMIN, Role.DBA),
    RUN_INGESTION("Run slow query log ingestion", Role.ADMIN, Role.DBA),
    EXECUTE_PLAYBOOKS("Execute playbooks and automation", Role.ADMIN, Role.DBA),
    USE_INDEX_ADVISOR("Apply Index Advisor recommendations", Role.ADMIN, Role.DBA),
    MANAGE_ALERTS("Acknowledge and manage alerts", Role.ADMIN, Role.DBA),

    // ==================== WORKSPACE PERMISSIONS ====================
    // Creating a workspace is a normal authoring action for anyone who can build
    // dashboards; granting other people access to one is an administrative act and
    // is checked per-workspace on top of this (see DashboardWorkspaceService).
    MANAGE_DASHBOARD_WORKSPACES("Create dashboard workspaces and manage their members",
        Role.ADMIN, Role.DBA, Role.DATA_ENGINEER, Role.DEVELOPER),

    // ==================== ADMINISTRATIVE PERMISSIONS ====================
    // DBA gets connection settings but explicitly NOT user creation.
    MANAGE_CONNECTIONS("Create, edit, and delete database connections", Role.ADMIN, Role.DBA),
    MANAGE_SETTINGS("Modify system settings and configurations", Role.ADMIN, Role.DBA),
    MANAGE_USERS("View, edit roles, and delete users", Role.ADMIN),
    MANAGE_INVITE_CODES("Generate and manage invite codes", Role.ADMIN),
    MANAGE_PERMISSIONS("Manage roles and role-permission overrides", Role.ADMIN);

    private final String description;
    private final java.util.Set<Role> defaultRoles;

    Permission(String description, Role... defaultRoles) {
        this.description = description;
        this.defaultRoles = defaultRoles.length == 0
            ? java.util.EnumSet.noneOf(Role.class)
            : java.util.EnumSet.copyOf(java.util.Arrays.asList(defaultRoles));
    }

    public String getDescription() {
        return description;
    }

    /** The built-in roles that hold this permission by default. */
    public java.util.Set<Role> getDefaultRoles() {
        return java.util.Collections.unmodifiableSet(defaultRoles);
    }

    /**
     * Check if a role has this permission by default, before overrides.
     *
     * <p>ADMIN always holds every permission — an admin who could be locked out of
     * user management by an override would be an unrecoverable install.
     */
    public boolean isGrantedByDefaultTo(Role role) {
        if (role == null) {
            return false;
        }
        return role == Role.ADMIN || defaultRoles.contains(role);
    }

    /** Parse a permission code leniently; returns null when unknown. */
    public static Permission fromCode(String code) {
        if (code == null || code.isBlank()) {
            return null;
        }
        try {
            return Permission.valueOf(code.trim().toUpperCase());
        } catch (IllegalArgumentException e) {
            return null;
        }
    }
}
