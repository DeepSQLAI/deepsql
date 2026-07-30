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
 * Represents a detected schema change between two snapshots.
 * Tracks modifications to tables, columns, indexes, and constraints.
 */
@Entity
@Table(name = "schema_change")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaChange {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "from_snapshot_id", length = 36)
    private String fromSnapshotId;

    @Column(name = "to_snapshot_id", length = 36)
    private String toSnapshotId;

    @Column(name = "change_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private ChangeType changeType;

    @Column(name = "object_type", nullable = false, length = 30)
    @Enumerated(EnumType.STRING)
    private ObjectType objectType;

    @Column(name = "object_name", nullable = false)
    private String objectName;

    @Column(name = "table_name")
    private String tableName;

    @Column(name = "change_details", nullable = false, columnDefinition = "jsonb")
    @JdbcTypeCode(SqlTypes.JSON)
    private Map<String, Object> changeDetails;

    @Column(name = "severity", nullable = false, length = 20)
    @Enumerated(EnumType.STRING)
    private Severity severity;

    @Column(name = "is_breaking_change")
    private Boolean isBreakingChange;

    @Column(name = "is_acknowledged")
    private Boolean isAcknowledged;

    @Column(name = "acknowledged_by", length = 100)
    private String acknowledgedBy;

    @Column(name = "acknowledged_at")
    private LocalDateTime acknowledgedAt;

    @Column(name = "detected_at", nullable = false)
    private LocalDateTime detectedAt;

    @PrePersist
    protected void onCreate() {
        if (detectedAt == null) {
            detectedAt = LocalDateTime.now();
        }
        if (isAcknowledged == null) {
            isAcknowledged = false;
        }
        if (isBreakingChange == null) {
            isBreakingChange = false;
        }
        if (severity == null) {
            severity = Severity.INFO;
        }
    }

    public enum ChangeType {
        TABLE_ADDED,
        TABLE_REMOVED,
        COLUMN_ADDED,
        COLUMN_REMOVED,
        COLUMN_MODIFIED,
        INDEX_ADDED,
        INDEX_REMOVED,
        INDEX_MODIFIED,
        CONSTRAINT_ADDED,
        CONSTRAINT_REMOVED,
        FOREIGN_KEY_ADDED,
        FOREIGN_KEY_REMOVED
    }

    public enum ObjectType {
        TABLE,
        COLUMN,
        INDEX,
        CONSTRAINT,
        FOREIGN_KEY
    }

    public enum Severity {
        INFO,      // Minor change, informational
        WARNING,   // Potential impact, needs review
        CRITICAL   // Breaking change, immediate attention needed
    }

    /**
     * Get a human-readable description of this change
     */
    public String getDescription() {
        return switch (changeType) {
            case TABLE_ADDED -> "New table '" + objectName + "' was added";
            case TABLE_REMOVED -> "Table '" + objectName + "' was removed";
            case COLUMN_ADDED -> "New column '" + objectName + "' was added to table '" + tableName + "'";
            case COLUMN_REMOVED -> "Column '" + objectName + "' was removed from table '" + tableName + "'";
            case COLUMN_MODIFIED -> "Column '" + objectName + "' in table '" + tableName + "' was modified";
            case INDEX_ADDED -> "New index '" + objectName + "' was created on table '" + tableName + "'";
            case INDEX_REMOVED -> "Index '" + objectName + "' was dropped from table '" + tableName + "'";
            case INDEX_MODIFIED -> "Index '" + objectName + "' on table '" + tableName + "' was modified";
            case CONSTRAINT_ADDED -> "New constraint '" + objectName + "' was added to table '" + tableName + "'";
            case CONSTRAINT_REMOVED -> "Constraint '" + objectName + "' was removed from table '" + tableName + "'";
            case FOREIGN_KEY_ADDED -> "New foreign key '" + objectName + "' was added to table '" + tableName + "'";
            case FOREIGN_KEY_REMOVED -> "Foreign key '" + objectName + "' was removed from table '" + tableName + "'";
        };
    }
}
