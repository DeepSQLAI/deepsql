package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "playbook_alerts")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookAlert {

    @Id
    private String id;

    @Column(name = "playbook_run_id", nullable = false)
    private String playbookRunId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Column(nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(nullable = false)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String message;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private Map<String, Object> findings;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON")
    private List<Map<String, Object>> recommendations;

    @Column
    private Boolean acknowledged = false;

    @Column(name = "acknowledged_by")
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "channels_sent", columnDefinition = "JSON")
    private List<String> channelsSent;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    public enum Severity {
        INFO,
        WARNING,
        CRITICAL
    }

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public void acknowledge(String acknowledgedBy) {
        this.acknowledged = true;
        this.acknowledgedBy = acknowledgedBy;
        this.acknowledgedAt = LocalDateTime.now();
    }
}
