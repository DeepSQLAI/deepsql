package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Foreign key relationship information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ForeignKeyDetail {
    private String constraintName;
    private String columnName;
    private String referencedTable;
    private String referencedColumn;
    private String onDelete;  // CASCADE, SET NULL, etc.
    private String onUpdate;
}
