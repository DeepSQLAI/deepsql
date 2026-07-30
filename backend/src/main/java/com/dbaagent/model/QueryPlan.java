package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Query execution plan from EXPLAIN
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryPlan {
    private String planText;
    private String planJson;
    private Double estimatedCost;
    private Long estimatedRows;
    private Double actualTime;  // From EXPLAIN ANALYZE
    private Long actualRows;
    private String nodeType;  // Seq Scan, Index Scan, etc.
    private Boolean usesIndex;
    private String indexName;
    private String warnings;
}
