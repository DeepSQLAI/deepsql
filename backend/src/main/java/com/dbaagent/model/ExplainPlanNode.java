package com.dbaagent.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Represents a node in a query execution plan tree
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ExplainPlanNode {

    // Basic node information
    private String nodeType;           // e.g., "Seq Scan", "Index Scan", "Nested Loop"
    private String tableName;
    private String aliasName;
    private Integer nodeId;

    // Cost estimates
    private Double startupCost;
    private Double totalCost;
    private Integer planRows;          // Estimated rows
    private Integer planWidth;         // Estimated row width in bytes

    // Actual execution (when using EXPLAIN ANALYZE)
    private Double actualStartupTime;
    private Double actualTotalTime;
    private Long actualRows;
    private Integer actualLoops;

    // MySQL-specific fields
    private String selectType;         // SIMPLE, PRIMARY, UNION, etc.
    private String accessType;         // ALL, index, range, ref, const, etc.
    private String possibleKeys;       // Possible indexes to use
    private String key;                // Actually used index
    private String keyLength;
    private String ref;
    private String extra;              // Extra information (Using where, Using filesort, etc.)

    // PostgreSQL-specific fields
    private String filter;             // Filter condition
    private Long rowsRemovedByFilter;
    private String indexCondition;
    private String joinType;           // Inner, Left, Right, etc.
    private String parentRelationship; // Outer, Inner, SubPlan, InitPlan

    // Tree structure
    @Builder.Default
    private List<ExplainPlanNode> children = new ArrayList<>();

    // Additional metadata
    private Map<String, Object> additionalInfo;

    // Performance indicators
    private Boolean isSlowNode;        // True if this node is a bottleneck
    private String slowReason;         // Why this node is slow

    /**
     * Add a child node to this node
     */
    public void addChild(ExplainPlanNode child) {
        if (this.children == null) {
            this.children = new ArrayList<>();
        }
        this.children.add(child);
    }

    /**
     * Calculate total cost including all children
     */
    public Double getTotalCostWithChildren() {
        double total = this.totalCost != null ? this.totalCost : 0.0;
        if (children != null) {
            for (ExplainPlanNode child : children) {
                total += child.getTotalCostWithChildren();
            }
        }
        return total;
    }

    /**
     * Get depth of this node in the tree
     */
    public int getDepth() {
        if (children == null || children.isEmpty()) {
            return 1;
        }
        int maxChildDepth = 0;
        for (ExplainPlanNode child : children) {
            maxChildDepth = Math.max(maxChildDepth, child.getDepth());
        }
        return 1 + maxChildDepth;
    }

    /**
     * Check if this is a full table scan
     */
    public boolean isFullTableScan() {
        if (nodeType != null) {
            // PostgreSQL
            if (nodeType.equalsIgnoreCase("Seq Scan")) {
                return true;
            }
        }
        if (accessType != null) {
            // MySQL
            if (accessType.equalsIgnoreCase("ALL")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Check if using an index
     */
    public boolean isUsingIndex() {
        if (nodeType != null && nodeType.toLowerCase().contains("index")) {
            return true;
        }
        if (key != null && !key.equalsIgnoreCase("NULL")) {
            return true;
        }
        return false;
    }

    /**
     * Check if using filesort (expensive operation)
     */
    public boolean isUsingFilesort() {
        return extra != null && extra.toLowerCase().contains("using filesort");
    }

    /**
     * Check if using temporary table
     */
    public boolean isUsingTemporary() {
        return extra != null && extra.toLowerCase().contains("using temporary");
    }

    /**
     * Get estimated row count
     */
    public Long getEstimatedRows() {
        if (actualRows != null) {
            return actualRows;
        }
        if (planRows != null) {
            return planRows.longValue();
        }
        return 0L;
    }

    /**
     * Get cost value for display
     */
    public Double getCost() {
        if (totalCost != null) {
            return totalCost;
        }
        return 0.0;
    }

    /**
     * Get execution time in milliseconds
     */
    public Double getExecutionTimeMs() {
        if (actualTotalTime != null) {
            return actualTotalTime;
        }
        return null;
    }
}
