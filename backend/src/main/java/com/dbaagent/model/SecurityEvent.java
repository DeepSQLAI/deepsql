package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "security_event")
@Data
public class SecurityEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "event_type", nullable = false)
    private String eventType;

    @Column(nullable = false)
    private String outcome;

    @Column(name = "user_id")
    private Long userId;

    @Column(name = "actor_user_id")
    private Long actorUserId;

    private String email;

    @Column(name = "target_resource")
    private String targetResource;

    @Column(length = 1024)
    private String reason;

    @Column(name = "client_ip")
    private String clientIp;

    @Column(name = "user_agent")
    private String userAgent;

    @Column(name = "request_id")
    private String requestId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "event_metadata", columnDefinition = "jsonb")
    private Map<String, Object> eventMetadata;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Transient
    public SecurityEventType getEventTypeEnum() {
        return eventType == null ? null : SecurityEventType.valueOf(eventType);
    }

    public void setEventTypeEnum(SecurityEventType type) {
        this.eventType = type != null ? type.name() : null;
    }

    @Transient
    public SecurityEventOutcome getOutcomeEnum() {
        return outcome == null ? null : SecurityEventOutcome.valueOf(outcome);
    }

    public void setOutcomeEnum(SecurityEventOutcome outcome) {
        this.outcome = outcome != null ? outcome.name() : null;
    }
}
