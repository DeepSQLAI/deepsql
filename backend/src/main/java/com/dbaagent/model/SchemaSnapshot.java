package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * Represents a point-in-time snapshot of a database schema.
 * Used for schema comparison and drift detection.
 */
@Entity
@Table(name = "schema_snapshot", indexes = {
    @Index(name = "idx_schema_snapshot_connection", columnList = "connectionId"),
    @Index(name = "idx_schema_snapshot_created", columnList = "capturedAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "snapshot_name")
    private String snapshotName;

    @Column(name = "snapshot_type", length = 30)
    @Enumerated(EnumType.STRING)
    private SnapshotType snapshotType;

    @Column(name = "captured_at", nullable = false)
    private LocalDateTime capturedAt;

    @Column(name = "table_count")
    private Integer tableCount;

    @Column(name = "column_count")
    private Integer columnCount;

    @Column(name = "index_count")
    private Integer indexCount;

    @Column(name = "constraint_count")
    private Integer constraintCount;

    @Column(name = "schema_hash", length = 64)
    private String schemaHash;

    @Column(name = "schema_json", columnDefinition = "TEXT", nullable = false)
    private String schemaJson;

    @Column(name = "notes", columnDefinition = "TEXT")
    private String notes;

    @Column(name = "created_by", length = 100)
    private String createdBy;

    @PrePersist
    protected void onCreate() {
        if (capturedAt == null) {
            capturedAt = LocalDateTime.now();
        }
        if (snapshotType == null) {
            snapshotType = SnapshotType.MANUAL;
        }
    }

    public enum SnapshotType {
        MANUAL,    // User-triggered snapshot
        SCHEDULED, // Automatically captured by scheduler
        BASELINE   // Reference snapshot for drift detection
    }
}
