package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * DTO representing a single JOIN relationship extracted from a SQL query.
 * Used internally by JoinRelationshipInferenceService during SQL parsing.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class JoinRelationshipDetail {

    /**
     * Left table in the JOIN condition.
     */
    private String leftTable;

    /**
     * Left column in the JOIN condition.
     */
    private String leftColumn;

    /**
     * Right table in the JOIN condition.
     */
    private String rightTable;

    /**
     * Right column in the JOIN condition.
     */
    private String rightColumn;

    /**
     * Type of JOIN (INNER, LEFT, RIGHT, FULL).
     */
    private String joinType;

    /**
     * The source table (FK side) after direction is determined.
     */
    private String sourceTable;

    /**
     * The source column (FK column) after direction is determined.
     */
    private String sourceColumn;

    /**
     * The target table (PK side) after direction is determined.
     */
    private String targetTable;

    /**
     * The target column (PK column) after direction is determined.
     */
    private String targetColumn;

    /**
     * Whether this is a self-join.
     */
    private boolean selfJoin;

    /**
     * The original SQL query this was extracted from.
     */
    private String originalQuery;

    /**
     * Create a key for deduplication.
     */
    public String getKey() {
        return String.format("%s.%s->%s.%s", sourceTable, sourceColumn, targetTable, targetColumn);
    }

    /**
     * Check if this relationship is valid (all required fields present).
     */
    public boolean isValid() {
        return sourceTable != null && !sourceTable.isEmpty()
            && sourceColumn != null && !sourceColumn.isEmpty()
            && targetTable != null && !targetTable.isEmpty()
            && targetColumn != null && !targetColumn.isEmpty();
    }
}
