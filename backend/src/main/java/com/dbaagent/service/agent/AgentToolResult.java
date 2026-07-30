package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.springframework.lang.Nullable;

import java.util.List;

public record AgentToolResult(
    AgentObservation observation,
    @Nullable QueryResult queryResult,
    @Nullable String executedSql,
    List<String> executedQueries,
    List<QueryResult> queryResults,
    List<AgentToolArtifact> artifacts,
    double confidence
) {
    public AgentToolResult(AgentObservation observation, @Nullable QueryResult queryResult, @Nullable String executedSql, double confidence) {
        this(
            observation,
            queryResult,
            executedSql,
            executedSql == null || executedSql.isBlank() ? List.of() : List.of(executedSql),
            queryResult == null ? List.of() : List.of(queryResult),
            List.of(),
            confidence
        );
    }

    public AgentToolResult {
        executedQueries = executedQueries == null ? List.of() : List.copyOf(executedQueries);
        queryResults = queryResults == null ? List.of() : List.copyOf(queryResults);
        artifacts = artifacts == null ? List.of() : List.copyOf(artifacts);
    }

    public List<String> allExecutedQueries() {
        if (!executedQueries.isEmpty()) {
            return executedQueries;
        }
        if (executedSql != null && !executedSql.isBlank()) {
            return List.of(executedSql);
        }
        return List.of();
    }

    public List<QueryResult> allQueryResults() {
        if (!queryResults.isEmpty()) {
            return queryResults;
        }
        if (queryResult != null) {
            return List.of(queryResult);
        }
        return List.of();
    }
}
