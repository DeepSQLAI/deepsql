package com.dbaagent.model;

public enum ConnectionAccessLevel {
    CHAT_EDITOR,
    FULL_CONTENT;

    public static ConnectionAccessLevel fromString(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Access level is required");
        }
        return switch (value.trim().toUpperCase()) {
            case "CHAT_EDITOR" -> CHAT_EDITOR;
            case "FULL_CONTENT", "FULL_ACCESS" -> FULL_CONTENT;
            default -> throw new IllegalArgumentException("Unsupported access level: " + value);
        };
    }
}
