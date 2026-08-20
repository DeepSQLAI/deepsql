package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "connection_chat_access_policy", uniqueConstraints = {
    @UniqueConstraint(name = "uk_connection_chat_access_policy_connection_user", columnNames = {"connection_id", "username"})
})
@Data
public class ConnectionChatAccessPolicy {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(nullable = false, length = 255)
    private String username;

    @Column(name = "plain_english_policy", nullable = false, columnDefinition = "TEXT")
    private String plainEnglishPolicy;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "blocked_sensitivity_categories", columnDefinition = "jsonb")
    private List<String> blockedSensitivityCategories;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "denied_tables", columnDefinition = "jsonb")
    private List<String> deniedTables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "denied_columns", columnDefinition = "jsonb")
    private List<String> deniedColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_schemas", columnDefinition = "jsonb")
    private List<String> allowedSchemas;

    @Column(name = "allow_aggregates", nullable = false)
    private boolean allowAggregates = false;

    @Column(name = "block_mode", nullable = false)
    private boolean blockMode = true;

    @Column(name = "redact_mode", nullable = false)
    private boolean redactMode = true;

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "updated_by", length = 255)
    private String updatedBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        createdAt = now;
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
