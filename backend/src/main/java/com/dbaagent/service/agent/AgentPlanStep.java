package com.dbaagent.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public record AgentPlanStep(
    String id,
    String title,
    String toolName,
    Map<String, Object> params,
    String taskId,
    List<String> dependsOn,
    String stepKind
) {
    public AgentPlanStep(String id, String title, String toolName, Map<String, Object> params) {
        this(id, title, toolName, params, null, List.of(), "tool");
    }

    public AgentPlanStep {
        params = params == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(params));
        dependsOn = dependsOn == null ? List.of() : List.copyOf(dependsOn);
        stepKind = stepKind == null || stepKind.isBlank() ? "tool" : stepKind;
    }
}
