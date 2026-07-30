package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TableMetadata {
    private String name;
    private String schema;
    private String type; // "table" or "view"
    private Long rowCount;
    private Long sizeBytes;
    private List<ColumnMetadata> columns = new ArrayList<>();
    private List<IndexMetadata> indexes = new ArrayList<>();
}



