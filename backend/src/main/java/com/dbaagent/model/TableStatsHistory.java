package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "table_stats_history", indexes = {
    @Index(name = "idx_table_stats_history_connection_table", columnList = "connection_id,table_name"),
    @Index(name = "idx_snapshot_time", columnList = "snapshot_timestamp"),
    @Index(name = "idx_connection_time", columnList = "connection_id,snapshot_timestamp")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableStatsHistory {

    @Id
    private String id;

    @Column(name = "connection_id", nullable = false, length = 36)
    private String connectionId;

    @Column(name = "table_name", nullable = false)
    private String tableName;

    @Column(name = "snapshot_timestamp", nullable = false)
    private LocalDateTime snapshotTimestamp;

    // Current metrics
    @Column(name = "size_bytes")
    private Long sizeBytes;

    @Column(name = "row_count")
    private Long rowCount;

    @Column(name = "index_size_bytes")
    private Long indexSizeBytes;

    @Column(name = "data_size_bytes")
    private Long dataSizeBytes;

    // Bloat metrics (allocated vs actual space)
    @Column(name = "allocated_bytes")
    private Long allocatedBytes;

    @Column(name = "bloat_bytes")
    private Long bloatBytes;

    @Column(name = "bloat_percent")
    private Double bloatPercent;

    // Growth calculations
    @Column(name = "size_growth_bytes")
    private Long sizeGrowthBytes;

    @Column(name = "size_growth_percent")
    private Double sizeGrowthPercent;

    @Column(name = "row_count_growth")
    private Long rowCountGrowth;

    @Column(name = "row_count_growth_percent")
    private Double rowCountGrowthPercent;

    // Statistical metrics
    @Column(name = "size_z_score")
    private Double sizeZScore;

    @Column(name = "row_count_z_score")
    private Double rowCountZScore;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (snapshotTimestamp == null) {
            snapshotTimestamp = LocalDateTime.now();
        }
    }

    /**
     * Calculate growth metrics compared to previous snapshot
     * @param previous Previous snapshot for the same table
     */
    public void calculateGrowthMetrics(TableStatsHistory previous) {
        if (previous == null) {
            this.sizeGrowthBytes = 0L;
            this.sizeGrowthPercent = 0.0;
            this.rowCountGrowth = 0L;
            this.rowCountGrowthPercent = 0.0;
            return;
        }

        // Size growth calculations
        if (this.sizeBytes != null && previous.sizeBytes != null) {
            this.sizeGrowthBytes = this.sizeBytes - previous.sizeBytes;

            if (previous.sizeBytes > 0) {
                this.sizeGrowthPercent = ((double) this.sizeGrowthBytes / previous.sizeBytes) * 100.0;
            } else {
                this.sizeGrowthPercent = 0.0;
            }
        }

        // Row count growth calculations
        if (this.rowCount != null && previous.rowCount != null) {
            this.rowCountGrowth = this.rowCount - previous.rowCount;

            if (previous.rowCount > 0) {
                this.rowCountGrowthPercent = ((double) this.rowCountGrowth / previous.rowCount) * 100.0;
            } else {
                this.rowCountGrowthPercent = 0.0;
            }
        }
    }

    /**
     * Format size in bytes to human-readable format
     */
    public String getFormattedSize() {
        if (sizeBytes == null) return "N/A";

        if (sizeBytes < 1024) return sizeBytes + " B";
        if (sizeBytes < 1024 * 1024) return String.format("%.2f KB", sizeBytes / 1024.0);
        if (sizeBytes < 1024 * 1024 * 1024) return String.format("%.2f MB", sizeBytes / (1024.0 * 1024));
        return String.format("%.2f GB", sizeBytes / (1024.0 * 1024 * 1024));
    }

    /**
     * Format row count with thousands separators
     */
    public String getFormattedRowCount() {
        if (rowCount == null) return "N/A";
        return String.format("%,d", rowCount);
    }
}
