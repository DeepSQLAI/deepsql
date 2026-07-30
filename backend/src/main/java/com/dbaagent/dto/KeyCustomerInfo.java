package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * A "key customer" — an entity (literal value for a key column) that
 * drives a disproportionate amount of slow query load.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class KeyCustomerInfo {

    /** Server-generated opaque row identifier (UUID). Stable frontend React key. */
    private String id;

    private String tableName;
    private String columnName;

    /** Masked/truncated literal for API output (see masking policy). */
    private String displayValue;

    /** Unmasked literal for internal/operational use (e.g. skew insights). */
    private String rawValue;

    /** Key type from KeyColumnAnalysis (TRUE_KEY, SURROGATE_KEY, etc.) — nullable. */
    private String keyType;

    /** Importance score from KeyColumnAnalysis — nullable. */
    private Double importanceScore;

    // Metrics
    private int slowQueryCount;
    private long totalDbTimeMs;
    private double avgExecutionTimeMs;
    private double worstQueryTimeMs;

    /** Truncated normalizedQuery of the worst (slowest) matching query. */
    private String worstQueryPreview;

    private int criticalCount;
    private int highCount;

    /** Query IDs guaranteed to be in the capped ≤100 set. */
    private List<String> matchingQueryIds;
}
