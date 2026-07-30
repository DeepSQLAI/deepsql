package com.dbaagent.service.agent;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record EvidenceItem(
    PromptIntent.Domain domain,
    String sourceFamily,
    String sourceName,
    String answerType,
    List<Map<String, Object>> rows,
    Map<String, Object> payload,
    Set<String> supportingObjects,
    String freshness,
    double confidence,
    double coverage,
    String failureReason
) {
    public EvidenceItem {
        rows = rows == null ? List.of() : List.copyOf(rows);
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
        supportingObjects = supportingObjects == null ? Set.of() : Set.copyOf(supportingObjects);
        freshness = freshness == null ? "unknown" : freshness;
        failureReason = failureReason == null ? "" : failureReason;
    }

    public boolean sufficient() {
        return failureReason.isBlank() && confidence >= 0.5d;
    }
}
