package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class QueryResult {
    private List<String> columns;
    private List<List<Object>> rows;
    private Integer rowCount;
    private Long totalRowCount;  // Total rows matching the query (before limit); null if count unavailable
    private Boolean isLimited;   // True when a row limit was applied to this query
    private Long executionTimeMs;
    private String query;
}
