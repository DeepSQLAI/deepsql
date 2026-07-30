package com.dbaagent.service;

import com.dbaagent.service.agent.AgentExecutionContext;

import java.util.List;
import java.util.Map;

public record ResolvedConversationContext(
    String matchedContextId,
    String matchedRouteType,
    String matchedStateStatus,
    String anchorQuestion,
    String chainSummary,
    Map<String, Object> resolvedContext,
    List<Map<String, Object>> selectedEntities,
    Map<String, Object> resultSummary,
    String sourceSql,
    List<AgentExecutionContext.ConversationTurn> conversationHistory,
    double relevanceScore
) {

    public static ResolvedConversationContext empty() {
        return new ResolvedConversationContext(
            null,
            null,
            null,
            null,
            null,
            Map.of(),
            List.of(),
            Map.of(),
            null,
            List.of(),
            0.0d
        );
    }

    public boolean hasMatchedContext() {
        return matchedContextId != null && !matchedContextId.isBlank();
    }
}
