package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.springframework.lang.Nullable;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentTaskResult(
    String taskId,
    String title,
    AgentTaskKind kind,
    List<String> dependsOn,
    String status,
    @Nullable String message,
    String summary,
    List<String> executedQueries,
    @Nullable QueryResult primaryResult,
    Map<String, Object> derivedValues,
    double confidence
) {
    public AgentTaskResult {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        executedQueries = executedQueries == null ? List.of() : List.copyOf(executedQueries);
        derivedValues = derivedValues == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(derivedValues));
    }

    public boolean completed() {
        return "COMPLETED".equalsIgnoreCase(status);
    }

    public boolean needsClarification() {
        return "CLARIFICATION".equalsIgnoreCase(status);
    }
}
