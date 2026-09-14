package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.AllArgsConstructor;
import lombok.Builder;

import java.time.LocalDateTime;

/**
 * Per-user digest preferences for role-aware, personalized digests.
 *
 * <p>Each row represents a single digest subscription for a user. A user can have
 * multiple subscriptions (e.g., different connections, different delivery methods).
 *
 * <p>When no preferences exist for a user, the system falls back to the singleton
 * {@link SlackDigestConfig} behavior for backward compatibility.
 *
 * <h3>Preference Resolution</h3>
 * <ol>
 *   <li>Look for enabled {@code UserDigestPreference} rows matching the user/connection</li>
 *   <li>If found, use those preferences (cron, persona, delivery method)</li>
 *   <li>If not found, fall back to global singleton behavior</li>
 * </ol>
 *
 * <h3>Persona Tags</h3>
 * <p>The {@code personaTag} is optional and allows content prioritization beyond the
 * user's RBAC role. For example, an ADMIN with a DBA persona will see performance
 * metrics emphasized over schema changes in their digest.
 */
@Entity
@Table(
    name = "user_digest_preference",
    uniqueConstraints = {
        @UniqueConstraint(
            name = "uk_user_digest_pref_user_conn_method",
            columnNames = {"username", "connection_id", "delivery_method"}
        )
    },
    indexes = {
        @Index(name = "idx_user_digest_pref_username", columnList = "username"),
        @Index(name = "idx_user_digest_pref_conn", columnList = "connection_id"),
        @Index(name = "idx_user_digest_pref_enabled", columnList = "enabled, username")
    }
)
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class UserDigestPreference {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /**
     * Username of the user this preference belongs to.
     * References users.username (not enforced by FK to allow soft-deleted users).
     */
    @Column(name = "username", nullable = false, length = 255)
    private String username;

    /**
     * Optional connection ID to scope this preference.
     * When null, this preference applies to all connections the user has access to.
     */
    @Column(name = "connection_id", length = 36)
    private String connectionId;

    /**
     * Whether digest delivery is enabled for this preference.
     * Allows users to pause digests without deleting preferences.
     */
    @Column(nullable = false)
    @Builder.Default
    private boolean enabled = true;

    /**
     * Optional persona tag for content prioritization.
     * When null, content is prioritized based on the user's RBAC role alone.
     */
    @Column(name = "persona_tag", length = 32)
    @Enumerated(EnumType.STRING)
    private PersonaTag personaTag;

    /**
     * Optional cron expression for per-user schedule override.
     * When null, uses the global SlackDigestConfig.cronExpression.
     * Format: "second minute hour day-of-month month day-of-week"
     */
    @Column(name = "cron_expression", length = 100)
    private String cronExpression;

    /**
     * Delivery method for this digest preference.
     * Each method requires different prerequisites (e.g., SLACK_DM needs SlackUserLink).
     */
    @Column(name = "delivery_method", nullable = false, length = 32)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DigestDeliveryMethod deliveryMethod = DigestDeliveryMethod.SLACK_DM;

    /**
     * Optional timezone for schedule interpretation.
     * When null or invalid, the digest tick evaluates the cron in UTC.
     * Format: IANA timezone ID (e.g., "America/New_York", "Europe/London").
     */
    @Column(name = "timezone", length = 64)
    private String timezone;

    @Column(name = "created_at", nullable = false, updatable = false)
    @Builder.Default
    private LocalDateTime createdAt = LocalDateTime.now();

    @Column(name = "updated_at", nullable = false)
    @Builder.Default
    private LocalDateTime updatedAt = LocalDateTime.now();

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Returns the effective cron expression, falling back to default if not set.
     */
    @Transient
    public String getEffectiveCronExpression(String globalDefault) {
        return cronExpression != null && !cronExpression.isBlank()
            ? cronExpression
            : globalDefault;
    }

    /**
     * Check if this preference applies to a specific connection.
     */
    @Transient
    public boolean appliesTo(String targetConnectionId) {
        return connectionId == null || connectionId.equals(targetConnectionId);
    }
}
