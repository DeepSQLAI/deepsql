package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Detailed column information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnDetail {
    private String columnName;
    private String dataType;
    private String columnType;  // Full type with size/precision
    private Boolean isNullable;
    private String columnDefault;
    private String characterMaximumLength;
    private String numericPrecision;
    private String numericScale;
    private Boolean isPrimaryKey;
    private Boolean isForeignKey;
    private Boolean isUnique;
    private Boolean isIndexed;
    private String comment;
}
