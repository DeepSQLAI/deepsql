package com.dbaagent.model;

public enum UserAccountStatus {
    PENDING_INVITE,
    ACTIVE,
    LOCKED,
    DISABLED;

    public static UserAccountStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        for (UserAccountStatus status : values()) {
            if (status.name().equalsIgnoreCase(value.trim())) {
                return status;
            }
        }
        return ACTIVE;
    }
}
