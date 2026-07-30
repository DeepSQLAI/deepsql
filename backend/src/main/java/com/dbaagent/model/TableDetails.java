package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Detailed table information including columns, constraints, and indexes
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TableDetails {
    private String tableName;
    private String tableSchema;
    private Long rowCount;
    private String tableSize;
    private List<ColumnDetail> columns;
    private List<IndexDetail> indexes;
    private List<ConstraintDetail> constraints;
    private List<ForeignKeyDetail> foreignKeys;
}
