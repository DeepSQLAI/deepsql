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
    // Server-side session id this query ran on, so a client that gives up can ask
    // the backend to terminate it instead of leaving it holding a connection.
    private String sessionPid;

    /**
     * Kept so adding {@code sessionPid} did not break every positional caller.
     * Prefer the setter for new code — this class is a response DTO that grows.
     */
    public QueryResult(
        List<String> columns,
        List<List<Object>> rows,
        Integer rowCount,
        Long totalRowCount,
        Boolean isLimited,
        Long executionTimeMs,
        String query
    ) {
        this(columns, rows, rowCount, totalRowCount, isLimited, executionTimeMs, query, null);
    }
}
