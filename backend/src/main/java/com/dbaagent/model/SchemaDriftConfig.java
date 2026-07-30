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

/**
 * Configuration for automatic schema drift detection per database connection.
 */
@Entity
@Table(name = "schema_drift_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDriftConfig {

    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "connection_id", nullable = false, unique = true, length = 36)
    private String connectionId;

    @Column(name = "enabled")
    private Boolean enabled;

    @Column(name = "check_frequency_minutes")
    private Integer checkFrequencyMinutes;

    @Column(name = "baseline_snapshot_id", length = 36)
    private String baselineSnapshotId;

    @Column(name = "alert_on_table_changes")
    private Boolean alertOnTableChanges;

    @Column(name = "alert_on_column_changes")
    private Boolean alertOnColumnChanges;

    @Column(name = "alert_on_index_changes")
    private Boolean alertOnIndexChanges;

    @Column(name = "alert_on_constraint_changes")
    private Boolean alertOnConstraintChanges;

    @Column(name = "ignored_tables", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] ignoredTables;

    @Column(name = "ignored_patterns", columnDefinition = "TEXT[]")
    @JdbcTypeCode(SqlTypes.ARRAY)
    private String[] ignoredPatterns;

    @Column(name = "last_checked_at")
    private LocalDateTime lastCheckedAt;

    @Column(name = "next_check_at")
    private LocalDateTime nextCheckAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        if (updatedAt == null) {
            updatedAt = now;
        }
        if (enabled == null) {
            enabled = true;
        }
        if (checkFrequencyMinutes == null) {
            checkFrequencyMinutes = 60;
        }
        if (alertOnTableChanges == null) {
            alertOnTableChanges = true;
        }
        if (alertOnColumnChanges == null) {
            alertOnColumnChanges = true;
        }
        if (alertOnIndexChanges == null) {
            alertOnIndexChanges = true;
        }
        if (alertOnConstraintChanges == null) {
            alertOnConstraintChanges = true;
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    /**
     * Check if a table should be ignored based on configuration
     */
    public boolean shouldIgnoreTable(String tableName) {
        if (tableName == null) {
            return false;
        }

        // Check exact matches
        if (ignoredTables != null) {
            for (String ignored : ignoredTables) {
                if (tableName.equalsIgnoreCase(ignored)) {
                    return true;
                }
            }
        }

        // Check patterns
        if (ignoredPatterns != null) {
            for (String pattern : ignoredPatterns) {
                if (tableName.matches(pattern)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Calculate next check time based on frequency
     */
    public void scheduleNextCheck() {
        this.lastCheckedAt = LocalDateTime.now();
        this.nextCheckAt = this.lastCheckedAt.plusMinutes(
                checkFrequencyMinutes != null ? checkFrequencyMinutes : 60
        );
    }
}
