package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseObject {
    private String name;
    private String type; // "table", "view", "function", "procedure"
    private String schema;
    private List<ColumnInfo> columns;
    private Long rowCount;
    private String definition; // For views, functions, procedures
}
