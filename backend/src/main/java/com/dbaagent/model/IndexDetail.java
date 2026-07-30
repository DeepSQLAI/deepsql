package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Index information
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class IndexDetail {
    private String indexName;
    private String indexType;  // BTREE, HASH, etc.
    private List<String> columns;
    private Boolean isUnique;
    private Boolean isPrimary;
    private String indexDefinition;
    private Long indexSize;
}
