package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO for table usage statistics.
 * Used by Performance Insights to show table activity heatmap.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableUsageDTO {

    /**
     * Fully qualified table name (schema.table)
     */
    private String tableName;

    /**
     * Usage score from 0-100 based on scan frequency, slow query presence, etc.
     */
    private int usageScore;

    /**
     * Number of sequential scans
     */
    private long seqScans;

    /**
     * Number of index scans
     */
    private long idxScans;

    /**
     * Total rows read
     */
    private long rowsRead;

    /**
     * Total rows written (insert + update + delete)
     */
    private long rowsWritten;

    /**
     * Number of slow queries referencing this table
     */
    private int slowQueryCount;

    /**
     * Timestamp of last activity
     */
    private String lastActivity;
}
