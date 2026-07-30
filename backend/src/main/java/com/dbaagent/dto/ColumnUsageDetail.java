package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Represents a single column usage occurrence in a query
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnUsageDetail {
    private String tableName;
    private String columnName;
    private String usageType;  // JOIN, WHERE, GROUP_BY, ORDER_BY, SELECT
    private String operation;   // =, IN, LIKE, BETWEEN, etc.
    private String context;     // Additional context if needed
}
