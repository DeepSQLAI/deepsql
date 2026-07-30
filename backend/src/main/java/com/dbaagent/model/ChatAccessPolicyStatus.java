package com.dbaagent.model;

public enum ChatAccessPolicyStatus {
    ACTIVE,
    DISABLED;

    public static ChatAccessPolicyStatus fromString(String value) {
        if (value == null || value.isBlank()) {
            return ACTIVE;
        }
        return switch (value.trim().toUpperCase()) {
            case "DISABLED", "INACTIVE" -> DISABLED;
            default -> ACTIVE;
        };
    }
}
