package com.dbaagent.service.agent;

import com.dbaagent.service.ConversationCarryoverDecision;
import com.dbaagent.service.ResolvedConversationContext;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public record ThreadContextPack(
    String rawQuestion,
    String effectiveQuestion,
    String routeTypeHint,
    String brainTopicHint,
    PromptIntent promptIntentHint,
    ConversationCarryoverDecision carryoverHint,
    ResolvedConversationContext matchedContext,
    List<AgentExecutionContext.ConversationTurn> recentConversationHistory
) {
    public ThreadContextPack {
        promptIntentHint = promptIntentHint == null ? PromptIntent.unsupported() : promptIntentHint;
        carryoverHint = carryoverHint == null ? ConversationCarryoverDecision.empty() : carryoverHint;
        matchedContext = matchedContext == null ? ResolvedConversationContext.empty() : matchedContext;
        recentConversationHistory = recentConversationHistory == null ? List.of() : List.copyOf(recentConversationHistory);
    }

    public static ThreadContextPack of(
        String rawQuestion,
        String effectiveQuestion,
        String routeTypeHint,
        String brainTopicHint,
        PromptIntent promptIntentHint,
        ConversationCarryoverDecision carryoverHint,
        ResolvedConversationContext matchedContext,
        List<AgentExecutionContext.ConversationTurn> recentConversationHistory
    ) {
        return new ThreadContextPack(
            rawQuestion,
            effectiveQuestion,
            routeTypeHint,
            brainTopicHint,
            promptIntentHint,
            carryoverHint,
            matchedContext,
            recentConversationHistory
        );
    }

    public boolean hasMatchedContext() {
        return matchedContext != null && matchedContext.hasMatchedContext();
    }

    public Map<String, Object> toPromptPayload() {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("rawQuestion", rawQuestion);
        payload.put("effectiveQuestion", effectiveQuestion);
        payload.put("routeTypeHint", routeTypeHint);
        payload.put("brainTopicHint", brainTopicHint);
        payload.put("promptIntentHint", renderPromptIntentHint());
        payload.put("carryoverHint", renderCarryoverHint());
        payload.put("matchedContext", renderMatchedContext());
        payload.put("recentConversationHistory", recentConversationHistory.stream()
            .filter(Objects::nonNull)
            .map(turn -> Map.of(
                "role", turn.role() == null ? "user" : turn.role(),
                "content", cap(turn.content(), 280)
            ))
            .toList());
        return payload;
    }

    private Map<String, Object> renderPromptIntentHint() {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("domain", promptIntentHint.domain().name());
        hint.put("taskType", promptIntentHint.taskType().name());
        hint.put("requestedOutput", promptIntentHint.requestedOutput().name());
        hint.put("subjectTypes", promptIntentHint.subjectTypes().stream().map(Enum::name).toList());
        return hint;
    }

    private Map<String, Object> renderCarryoverHint() {
        Map<String, Object> hint = new LinkedHashMap<>();
        hint.put("reuseMode", carryoverHint.reuseMode().name());
        hint.put("reuseConfidence", carryoverHint.reuseConfidence());
        if (!carryoverHint.preferredTables().isEmpty()) {
            hint.put("preferredTables", carryoverHint.preferredTables());
        }
        if (!carryoverHint.preferredJoinPath().isEmpty()) {
            hint.put("preferredJoinPath", carryoverHint.preferredJoinPath());
        }
        if (carryoverHint.preferredMetric() != null && !carryoverHint.preferredMetric().isBlank()) {
            hint.put("preferredMetric", carryoverHint.preferredMetric());
        }
        if (carryoverHint.preferredTemporalColumn() != null && !carryoverHint.preferredTemporalColumn().isBlank()) {
            hint.put("preferredTemporalColumn", carryoverHint.preferredTemporalColumn());
        }
        if (!carryoverHint.preferredFilters().isEmpty()) {
            hint.put("preferredFilters", carryoverHint.preferredFilters());
        }
        if (carryoverHint.rationale() != null && !carryoverHint.rationale().isBlank()) {
            hint.put("rationale", cap(carryoverHint.rationale(), 260));
        }
        return hint;
    }

    private Map<String, Object> renderMatchedContext() {
        if (!hasMatchedContext()) {
            return Map.of("available", false);
        }
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("available", true);
        rendered.put("matchedRouteType", matchedContext.matchedRouteType());
        rendered.put("matchedStateStatus", matchedContext.matchedStateStatus());
        rendered.put("anchorQuestion", cap(matchedContext.anchorQuestion(), 260));
        rendered.put("chainSummary", cap(matchedContext.chainSummary(), 500));
        rendered.put("resolvedContext", matchedContext.resolvedContext());
        if (!matchedContext.selectedEntities().isEmpty()) {
            rendered.put("selectedEntities", matchedContext.selectedEntities().stream()
                .map(entity -> Map.of(
                    "displayLabel", Objects.toString(entity.get("displayLabel"), ""),
                    "entityType", Objects.toString(entity.get("entityType"), "")
                ))
                .toList());
        }
        if (matchedContext.resultSummary() != null && !matchedContext.resultSummary().isEmpty()) {
            rendered.put("resultSummary", matchedContext.resultSummary());
        }
        if (matchedContext.sourceSql() != null && !matchedContext.sourceSql().isBlank()) {
            rendered.put("sourceSql", cap(matchedContext.sourceSql(), 600));
        }
        rendered.put("relevanceScore", matchedContext.relevanceScore());
        return rendered;
    }

    private static String cap(String value, int maxLen) {
        if (value == null) {
            return null;
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
