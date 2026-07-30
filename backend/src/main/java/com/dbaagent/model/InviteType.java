package com.dbaagent.model;

public enum InviteType {
    STANDARD,
    BOOTSTRAP;

    public static InviteType fromString(String value) {
        if (value == null || value.isBlank()) {
            return STANDARD;
        }
        for (InviteType type : values()) {
            if (type.name().equalsIgnoreCase(value.trim())) {
                return type;
            }
        }
        return STANDARD;
    }
}
