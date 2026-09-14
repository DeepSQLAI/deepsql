package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Log entry for digest deliveries.
 *
 * <p>Tracks each digest sent, including per-recipient details for role-aware digests.
 * When {@code recipientUsername} is null, the entry represents a legacy/global digest
 * sent to a channel rather than a personalized per-user digest.
 *
 * <h3>Delivery Types</h3>
 * <ul>
 *   <li><b>Legacy/Global</b>: channelId set, recipientUsername null — broadcast to channel</li>
 *   <li><b>Per-user DM</b>: recipientUsername set, channelId is the DM channel ID</li>
 *   <li><b>Per-user Email</b>: recipientUsername set, channelId null, deliveryMethod = EMAIL</li>
 * </ul>
 */
@Entity
@Table(
    name = "slack_digest_log",
    indexes = {
        @Index(name = "idx_slack_digest_log_recipient", columnList = "recipient_username, sent_at DESC"),
        @Index(name = "idx_slack_digest_log_conn_recipient", columnList = "connection_id, recipient_username, sent_at DESC")
    }
)
@Data
@NoArgsConstructor
public class SlackDigestLog {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    private String connectionId;
    private String connectionName;
    private String channelId;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String content;

    private String headline;

    @Column(nullable = false)
    private LocalDateTime sentAt = LocalDateTime.now();

    @Column(nullable = false)
    private String status = "SENT";

    @Column(columnDefinition = "TEXT")
    private String errorMessage;

    /**
     * Username of the recipient for per-user digests.
     * Null for legacy channel-broadcast digests.
     */
    @Column(name = "recipient_username", length = 255)
    private String recipientUsername;

    /**
     * RBAC role of the recipient at time of delivery.
     * Captured for audit trail; the user's role may change later.
     */
    @Column(name = "recipient_role", length = 64)
    private String recipientRole;

    /**
     * Persona tag used for content prioritization in this digest.
     * Null if no persona was applied (default role-based prioritization).
     */
    @Column(name = "persona_tag", length = 32)
    @Enumerated(EnumType.STRING)
    private PersonaTag personaTag;

    /**
     * Delivery method used for this digest.
     * Defaults to SLACK_DM for backward compatibility with existing logs.
     */
    @Column(name = "delivery_method", length = 32)
    @Enumerated(EnumType.STRING)
    private DigestDeliveryMethod deliveryMethod;

    /**
     * Reference to the UserDigestPreference that triggered this delivery.
     * Null for legacy/global digests or when preference was deleted.
     */
    @Column(name = "preference_id")
    private Long preferenceId;

    /**
     * Whether this was a personalized digest (role-aware content).
     * When false, the same content was sent to all recipients.
     */
    @Column(name = "personalized", nullable = false)
    private boolean personalized = false;

    /**
     * Check if this is a per-user digest (vs legacy channel broadcast).
     */
    @Transient
    public boolean isPerUserDelivery() {
        return recipientUsername != null && !recipientUsername.isBlank();
    }
}
