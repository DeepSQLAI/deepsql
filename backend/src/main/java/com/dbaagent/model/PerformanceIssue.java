package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a performance issue detected in an execution plan
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PerformanceIssue {

    public enum Severity {
        CRITICAL,  // Must fix immediately
        HIGH,      // Should fix soon
        MEDIUM,    // Plan to fix
        LOW,       // Optional optimization
        INFO       // Informational only
    }

    public enum IssueType {
        FULL_TABLE_SCAN,
        MISSING_INDEX,
        INEFFICIENT_JOIN,
        LARGE_SORT,
        TEMPORARY_TABLE,
        SUBOPTIMAL_INDEX,
        POOR_SELECTIVITY,
        EXPENSIVE_FUNCTION,
        CARTESIAN_PRODUCT,
        DUPLICATE_INDEX,
        UNUSED_INDEX,
        LOCK_CONTENTION
    }

    private Severity severity;
    private IssueType type;
    private String nodePath;           // Path to problematic node in tree
    private String tableName;
    private String message;            // Human-readable description
    private String technicalDetails;   // Technical explanation
    private String recommendation;     // How to fix
    private String suggestedSQL;       // SQL to implement fix
    private Double estimatedImpact;    // Estimated % improvement

    // Metrics
    private Long affectedRows;
    private Double costImpact;
    private Double timeImpact;

    /**
     * Create a full table scan issue
     */
    public static PerformanceIssue createFullTableScanIssue(
        String tableName,
        Long rows,
        String filter
    ) {
        String message = String.format(
            "Full table scan on '%s' (%,d rows)",
            tableName, rows
        );

        String recommendation = String.format(
            "Create an index on the filtered column(s)"
        );

        Severity severity = rows > 100000 ? Severity.CRITICAL :
                           rows > 10000 ? Severity.HIGH :
                           rows > 1000 ? Severity.MEDIUM : Severity.LOW;

        return PerformanceIssue.builder()
            .severity(severity)
            .type(IssueType.FULL_TABLE_SCAN)
            .tableName(tableName)
            .message(message)
            .technicalDetails("Sequential scan will read every row in the table")
            .recommendation(recommendation)
            .affectedRows(rows)
            .estimatedImpact(rows > 10000 ? 80.0 : 50.0)
            .build();
    }

    /**
     * Create a filesort issue
     */
    public static PerformanceIssue createFilesortIssue(
        String tableName,
        Long rows,
        String orderByColumns
    ) {
        String message = String.format(
            "Sorting %,d rows from '%s' using filesort",
            rows, tableName
        );

        String suggestion = String.format(
            "CREATE INDEX idx_%s_%s ON %s(%s)",
            tableName,
            orderByColumns.replaceAll("[^a-zA-Z0-9]", "_"),
            tableName,
            orderByColumns
        );

        Severity severity = rows > 100000 ? Severity.HIGH :
                           rows > 10000 ? Severity.MEDIUM : Severity.LOW;

        return PerformanceIssue.builder()
            .severity(severity)
            .type(IssueType.LARGE_SORT)
            .tableName(tableName)
            .message(message)
            .technicalDetails("Sorting in memory or disk is expensive for large datasets")
            .recommendation("Add index on ORDER BY columns")
            .suggestedSQL(suggestion)
            .affectedRows(rows)
            .estimatedImpact(40.0)
            .build();
    }

    /**
     * Create a temporary table issue
     */
    public static PerformanceIssue createTemporaryTableIssue(
        String tableName,
        String reason
    ) {
        return PerformanceIssue.builder()
            .severity(Severity.MEDIUM)
            .type(IssueType.TEMPORARY_TABLE)
            .tableName(tableName)
            .message("Using temporary table for " + reason)
            .technicalDetails("Temporary tables add overhead and may spill to disk")
            .recommendation("Optimize query to avoid temporary tables")
            .estimatedImpact(30.0)
            .build();
    }

    /**
     * Create an inefficient join issue
     */
    public static PerformanceIssue createInefficientJoinIssue(
        String leftTable,
        String rightTable,
        String joinType
    ) {
        String message = String.format(
            "Inefficient %s join between '%s' and '%s'",
            joinType, leftTable, rightTable
        );

        return PerformanceIssue.builder()
            .severity(Severity.HIGH)
            .type(IssueType.INEFFICIENT_JOIN)
            .message(message)
            .technicalDetails("Join without proper indexes will be slow")
            .recommendation("Ensure join columns are indexed on both tables")
            .estimatedImpact(70.0)
            .build();
    }

    /**
     * Create a poor selectivity issue
     */
    public static PerformanceIssue createPoorSelectivityIssue(
        String tableName,
        Long totalRows,
        Long filteredRows
    ) {
        double selectivity = (filteredRows * 100.0) / totalRows;

        String message = String.format(
            "Poor filter selectivity on '%s' (%.1f%% of rows filtered)",
            tableName, selectivity
        );

        return PerformanceIssue.builder()
            .severity(Severity.MEDIUM)
            .type(IssueType.POOR_SELECTIVITY)
            .tableName(tableName)
            .message(message)
            .technicalDetails(String.format(
                "Filter removes only %,d out of %,d rows",
                totalRows - filteredRows, totalRows))
            .recommendation("Add more selective WHERE conditions or revise query logic")
            .affectedRows(totalRows)
            .estimatedImpact(20.0)
            .build();
    }
}
