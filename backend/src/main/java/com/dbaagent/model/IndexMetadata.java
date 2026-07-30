package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IndexMetadata {
    private String name;
    private String tableName;
    private Boolean unique;
    private List<String> columns;
    private String indexType;
}



