package com.dbaagent.model;

/**
 * Persona tags for role-aware digest personalization.
 *
 * <p>These tags allow users to indicate their primary focus area beyond their RBAC role.
 * A user's Role determines what they can access; their PersonaTag influences how content
 * is prioritized and presented in digests.
 *
 * <p>For example, an ADMIN might set a {@code DBA} persona tag to receive digests
 * emphasizing query performance and index recommendations rather than schema changes.
 */
public enum PersonaTag {

    /** Database administrator: prioritize performance, tuning, and operational health. */
    DBA("DBA", "Database Administrator — performance, tuning, and operational health"),

    /** Application engineer: prioritize query patterns, ORM issues, and schema usage. */
    APP_ENG("App Engineer", "Application Engineer — query patterns and schema usage"),

    /** Data engineer: prioritize ETL patterns, data quality, and pipeline health. */
    DATA_ENG("Data Engineer", "Data Engineer — ETL patterns and pipeline health"),

    /** Executive: prioritize high-level summaries, costs, and strategic insights. */
    EXEC("Executive", "Executive — costs, capacity, and strategic insights");

    private final String displayName;
    private final String description;

    PersonaTag(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() {
        return displayName;
    }

    public String getDescription() {
        return description;
    }

    /**
     * Parse a persona tag leniently; returns null when unknown.
     */
    public static PersonaTag fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        return switch (normalized) {
            case "DBA" -> DBA;
            case "APP_ENG", "APPENG", "APP_ENGINEER", "APPLICATION_ENGINEER" -> APP_ENG;
            case "DATA_ENG", "DATAENG", "DATA_ENGINEER" -> DATA_ENG;
            case "EXEC", "EXECUTIVE" -> EXEC;
            default -> {
                try {
                    yield PersonaTag.valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }
}
