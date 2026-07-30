package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ColumnInfo {

    /**
     * Backwards-compatible 5-arg constructor matching the original ColumnInfo signature
     * (pre-index/key badges). Newer flags default to {@code null}.
     */
    public ColumnInfo(String name, String dataType, Boolean nullable, Boolean primaryKey, String defaultValue) {
        this.name = name;
        this.dataType = dataType;
        this.nullable = nullable;
        this.primaryKey = primaryKey;
        this.defaultValue = defaultValue;
    }

    private String name;
    private String dataType;
    private Boolean nullable;
    private Boolean primaryKey;
    private String defaultValue;

    /** True when the column participates in at least one single-column index (badge: 'i'). */
    private Boolean hasSingleColumnIndex;

    /** True when the column participates in at least one composite (multi-column) index (badge: 'ci'). */
    private Boolean hasCompositeIndex;

    /** True when the column is a primary key or foreign key declared in the database (badge: 'k'). */
    private Boolean directKey;

    /**
     * True when the column has been inferred as a key column by the brain
     * (KeyColumnAnalysis.keyType = TRUE_KEY / SURROGATE_KEY) without being a direct DB-declared key.
     * Badge: 'ik'.
     */
    private Boolean inferredKey;
}
