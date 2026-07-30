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
@Table(name = "playbooks")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class Playbook {

    @Id
    private String id;

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "db_type", length = 20)
    private String dbType; // 'mysql', 'postgres', 'all'

    @Column(length = 50)
    private String category; // 'health', 'performance', 'maintenance', 'troubleshooting'

    @Column(name = "schedule_cron", length = 100)
    private String scheduleCron;

    @Column(name = "is_active")
    private Boolean isActive = true;

    @Column(name = "is_system")
    private Boolean isSystem = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "JSON", nullable = false)
    private List<PlaybookStep> steps;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "alert_config", columnDefinition = "JSON")
    private AlertConfig alertConfig;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PlaybookStep {
        private String tool;
        private String name;
        private Map<String, Object> params;
        private Map<String, Object> thresholds;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AlertConfig {
        private List<String> channels; // ["browser", "slack", "email"]
        private String severityThreshold; // 'info', 'warning', 'critical'
    }
}
