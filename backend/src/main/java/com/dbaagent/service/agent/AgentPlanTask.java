package com.dbaagent.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentPlanTask(
    String taskId,
    String title,
    AgentTaskKind kind,
    List<String> dependsOn,
    String toolName,
    String question,
    String outputContract,
    Map<String, Object> params
) {
    public AgentPlanTask {
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
    }
}
