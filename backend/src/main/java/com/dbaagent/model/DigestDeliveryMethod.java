package com.dbaagent.model;

/**
 * Delivery method for digest notifications.
 *
 * <p>Determines how a user receives their personalized digest. Multiple methods
 * can be enabled per user by creating multiple {@link UserDigestPreference} rows.
 */
public enum DigestDeliveryMethod {

    /** Direct message via Slack. Requires user to be linked via SlackUserLink. */
    SLACK_DM("Slack DM", "Direct message to your linked Slack account"),

    /** Post to a Slack channel. Requires a SlackChannelBinding for the connection. */
    SLACK_CHANNEL("Slack Channel", "Posted to a configured Slack channel"),

    /** Email delivery. Uses the user's registered email address. */
    EMAIL("Email", "Sent to your registered email address");

    private final String displayName;
    private final String description;

    DigestDeliveryMethod(String displayName, String description) {
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
     * Parse a delivery method leniently; returns null when unknown.
     */
    public static DigestDeliveryMethod fromString(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String normalized = value.trim().toUpperCase().replace("-", "_").replace(" ", "_");
        return switch (normalized) {
            case "SLACK_DM", "SLACKDM", "DM" -> SLACK_DM;
            case "SLACK_CHANNEL", "SLACKCHANNEL", "CHANNEL" -> SLACK_CHANNEL;
            case "EMAIL", "MAIL" -> EMAIL;
            default -> {
                try {
                    yield DigestDeliveryMethod.valueOf(normalized);
                } catch (IllegalArgumentException e) {
                    yield null;
                }
            }
        };
    }
}
