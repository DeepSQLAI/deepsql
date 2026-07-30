package com.dbaagent.model;

public enum EffectiveConnectionAccess {
    NONE,
    CHAT_EDITOR,
    FULL_CONTENT,
    OWNER,
    ADMIN;

    public boolean canUseConnection() {
        return this != NONE;
    }

    public boolean canUseChatEditor() {
        return this != NONE;
    }

    public boolean canManageContent() {
        return this == FULL_CONTENT || this == OWNER || this == ADMIN;
    }

    /**
     * Read-only access to content (brain context, business rules, anti-patterns,
     * inferred relationships, slow-query analytics, index recommendations, etc.).
     * Anything that aids query generation is open to any user with connection
     * access. State-modifying actions (apply index, DML, brain reinit, config
     * changes) keep their stricter guards (canManageContent / canManageConfig /
     * @PreAuthorize hasRole ADMIN).
     */
    public boolean canReadContent() {
        return this != NONE;
    }

    public boolean canManageConfig() {
        return this == OWNER || this == ADMIN;
    }
}
