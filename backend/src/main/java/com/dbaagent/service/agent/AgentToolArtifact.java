package com.dbaagent.service.agent;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

public record AgentToolArtifact(
    String artifactType,
    String key,
    Map<String, Object> payload
) {
    public AgentToolArtifact {
        payload = payload == null ? Map.of() : Collections.unmodifiableMap(new LinkedHashMap<>(payload));
    }
}
