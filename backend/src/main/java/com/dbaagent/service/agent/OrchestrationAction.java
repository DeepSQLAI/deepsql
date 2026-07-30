package com.dbaagent.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public record OrchestrationAction(
    String thoughtSummary,
    String tool,
    Map<String, Object> toolArgs,
    String whyThisTool,
    String expectedEvidence,
    boolean shouldStop,
    boolean needsReplan,
    String clarificationQuestion
) {
    public OrchestrationAction {
        toolArgs = toolArgs == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(toolArgs));
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
    }

    public static OrchestrationAction stop(String reason) {
        return new OrchestrationAction(
            reason,
            "",
            Map.of(),
            reason,
            "",
            true,
            false,
            ""
        );
    }

    public boolean hasTool() {
        return tool != null && !tool.isBlank();
    }
}
