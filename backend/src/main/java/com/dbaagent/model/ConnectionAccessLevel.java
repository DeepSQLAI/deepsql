package com.dbaagent.model;

/**
 * The access level stored on a {@link ConnectionAccessGrant}.
 *
 * <p>There is now only one level: {@link #FULL_CONTENT}. Assigning a connection to a user
 * grants full content access to it — the old two-tier split (chat/editor only vs. full)
 * was a distinction users had to reason about for little benefit, and it silently hid the
 * Dashboards section from anyone on the lower tier.
 *
 * <p>{@code CHAT_EDITOR} is retained purely so existing rows written before this change
 * still parse; {@link #fromString} folds it into FULL_CONTENT rather than failing, and
 * nothing writes it any more. Do not reintroduce it as a distinct level without also
 * restoring the UI that explains it.
 */
public enum ConnectionAccessLevel {
    /** @deprecated legacy value; resolves to {@link #FULL_CONTENT}. Retained for old rows. */
    @Deprecated
    CHAT_EDITOR,
    FULL_CONTENT;

    public static ConnectionAccessLevel fromString(String value) {
        if (value == null || value.isBlank()) {
            // Assignment now implies full access, so an omitted level is not an error.
            return FULL_CONTENT;
        }
        return switch (value.trim().toUpperCase()) {
            // Legacy values all collapse to the single remaining level.
            case "CHAT_EDITOR", "FULL_CONTENT", "FULL_ACCESS" -> FULL_CONTENT;
            default -> throw new IllegalArgumentException("Unsupported access level: " + value);
        };
    }
}
