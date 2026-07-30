package com.dbaagent.service.agent;

public record ExhaustedSource(
    String sourceFamily,
    String reason
) {
    public ExhaustedSource {
        sourceFamily = sourceFamily == null ? "unknown" : sourceFamily;
        reason = reason == null ? "" : reason;
    }
}
