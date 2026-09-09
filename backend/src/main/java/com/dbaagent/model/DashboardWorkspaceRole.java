package com.dbaagent.model;

/**
 * A member's role within one dashboard workspace.
 *
 * <p>Deliberately two values, not a copy of the product's role model: this answers only
 * "can this member change the workspace itself", on top of whatever the member's product
 * role and connection grant already allow.
 */
public enum DashboardWorkspaceRole {
    /** Can open the workspace and the dashboards in it. */
    VIEWER,

    /** VIEWER, plus renaming the workspace, adding/removing members, and moving dashboards in or out. */
    MANAGER;

    public boolean canManage() {
        return this == MANAGER;
    }

    public static DashboardWorkspaceRole fromString(String value) {
        if (value == null || value.isBlank()) {
            return VIEWER;
        }
        return switch (value.trim().toUpperCase()) {
            case "MANAGER", "ADMIN", "OWNER", "EDITOR" -> MANAGER;
            default -> VIEWER;
        };
    }
}
