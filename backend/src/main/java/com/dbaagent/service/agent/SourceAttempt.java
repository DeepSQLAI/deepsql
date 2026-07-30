package com.dbaagent.service.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public record SourceAttempt(
    String toolName,
    String sourceFamily,
    String status,
    String summary,
    Instant attemptedAt,
    Map<String, Object> metadata
) {
    public SourceAttempt {
        toolName = toolName == null ? "" : toolName;
        sourceFamily = sourceFamily == null ? "unknown" : sourceFamily;
        status = status == null ? "UNKNOWN" : status;
        summary = summary == null ? "" : summary;
        attemptedAt = attemptedAt == null ? Instant.now() : attemptedAt;
        metadata = metadata == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(metadata));
    }
}
