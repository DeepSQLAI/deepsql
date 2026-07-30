package com.dbaagent.model;

import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
public class QueryRequest {
    private String query;
    private Integer limit; // Optional limit for safety
    private Integer timeoutSeconds; // Optional per-query timeout override (null = use server default)
    private QueryExecutionOrigin executionOrigin = QueryExecutionOrigin.INTERNAL;
    private Boolean mutationConfirmed = Boolean.FALSE;

    public QueryRequest(String query, Integer limit, Integer timeoutSeconds) {
        this.query = query;
        this.limit = limit;
        this.timeoutSeconds = timeoutSeconds;
        this.executionOrigin = QueryExecutionOrigin.INTERNAL;
        this.mutationConfirmed = Boolean.FALSE;
    }

    public QueryRequest(
        String query,
        Integer limit,
        Integer timeoutSeconds,
        QueryExecutionOrigin executionOrigin,
        Boolean mutationConfirmed
    ) {
        this.query = query;
        this.limit = limit;
        this.timeoutSeconds = timeoutSeconds;
        this.executionOrigin = executionOrigin == null ? QueryExecutionOrigin.INTERNAL : executionOrigin;
        this.mutationConfirmed = Boolean.TRUE.equals(mutationConfirmed);
    }
}
