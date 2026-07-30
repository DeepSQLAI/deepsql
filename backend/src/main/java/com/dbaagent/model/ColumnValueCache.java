package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity for caching column values, especially for low-cardinality columns.
 * These values are embedded into the vector store to help the LLM
 * generate accurate SQL filters without user having to specify values.
 */
@Entity
@Table(name = "column_value_cache", indexes = {
    @Index(name = "idx_colval_connection", columnList = "connectionId"),
    @Index(name = "idx_colval_low_card", columnList = "connectionId,isLowCardinality"),
    @Index(name = "idx_colval_embedded", columnList = "connectionId,embedded"),
    @Index(name = "idx_colval_table", columnList = "connectionId,tableName")
}, uniqueConstraints = {
    @UniqueConstraint(name = "uk_column_value_cache", columnNames = {"connectionId", "tableName", "columnName"})
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnValueCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String columnName;

    /**
     * Number of distinct values in this column.
     */
    private Long distinctCount;

    /**
     * Total rows in the table (for context).
     */
    private Long totalRows;

    /**
     * Sample values for preview (JSON array, first N values).
     */
    @Column(columnDefinition = "TEXT")
    private String sampleValues;

    /**
     * All distinct values if cardinality is below threshold (JSON array).
     * Only populated for low-cardinality columns.
     */
    @Column(columnDefinition = "TEXT")
    private String allValues;

    /**
     * Whether this is a low-cardinality column (< threshold).
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean isLowCardinality = false;

    /**
     * Data type of the column (for context).
     */
    @Column(length = 100)
    private String dataType;

    /**
     * Whether this column's values have been embedded into the vector store.
     */
    @Column(nullable = false)
    @Builder.Default
    private Boolean embedded = false;

    /**
     * ID of the document in the vector store (for updates/deletes).
     */
    private String embeddingId;

    /**
     * When the column values were last analyzed.
     */
    private LocalDateTime analyzedAt;

    /**
     * When the values were embedded into the vector store.
     */
    private LocalDateTime embeddedAt;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
