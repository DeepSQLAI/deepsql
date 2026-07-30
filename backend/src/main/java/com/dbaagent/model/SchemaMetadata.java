package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class SchemaMetadata {
    private String databaseName;
    private String dbType;
    private List<TableMetadata> tables = new ArrayList<>();
    private List<RelationshipMetadata> relationships = new ArrayList<>();
    private Long totalTables;
    private Long totalViews;
    private Long totalSizeBytes;
}



