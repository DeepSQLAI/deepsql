package com.dbaagent.service.agent;

public record AgentProgressEvent(
    String key,
    String label,
    String detail,
    String status,
    Integer stepIndex,
    String toolName,
    String runId,
    String planSummary,
    Double confidence
) {
}
