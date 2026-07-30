package com.dbaagent.service.agent;

import com.dbaagent.service.ConversationCarryoverDecision;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.util.PromptIntentSignals;

import java.util.List;
import java.util.Map;

final class ClarificationPolicy {

    String clarificationForUnderspecifiedPrompt(
        String question,
        ResolvedConversationContext resolvedConversationContext
    ) {
        return clarificationForUnderspecifiedPrompt(
            question,
            resolvedConversationContext,
            ConversationCarryoverDecision.empty()
        );
    }

    String clarificationForUnderspecifiedPrompt(
        String question,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        String normalized = PromptIntentSignals.normalize(question);
        if (normalized.isBlank()) {
            return null;
        }

        Map<String, Object> priorResolved = resolvedConversationContext == null
            ? Map.of()
            : resolvedConversationContext.resolvedContext();
        boolean hasPriorMetric = notBlank(stringValue(priorResolved.get("metric")));
        boolean hasPriorTimeframe = notBlank(stringValue(priorResolved.get("timeframe")));
        boolean reusesPriorScope = carryoverDecision != null && carryoverDecision.reusesPriorScope();
        if (carryoverDecision != null
            && carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION) {
            return null;
        }
        boolean hasMetric = PromptIntentSignals.hasExplicitMetric(question) || hasPriorMetric;
        boolean hasTimeWindow = PromptIntentSignals.hasExplicitTimeWindow(question) || hasPriorTimeframe;
        boolean hasTimeGrain = PromptIntentSignals.hasExplicitTimeGrain(question) || reusesPriorScope;

        if (PromptIntentSignals.isTrendQuestion(question) && !hasTimeWindow) {
            return "I need one clarification before I run SQL safely: which time period or date range should define this trend?";
        }

        if (PromptIntentSignals.isRankingQuestion(question) && !hasMetric) {
            return "I need one clarification before I run SQL safely: which metric, amount, or revenue definition and which time period should define this ranking?";
        }

        if (PromptIntentSignals.isComparisonQuestion(question) && (!hasMetric || !hasTimeWindow)) {
            return "I need one clarification before I run SQL safely: which metric and which time period or date range should define this comparison?";
        }

        if (PromptIntentSignals.isTrendQuestion(question) && !hasTimeGrain && !hasTimeWindow && !reusesPriorScope) {
            return "I need one clarification before I run SQL safely: which time period or date range should define this trend?";
        }

        return null;
    }

    String clarificationForGenerationFailure(
        TemporalResolutionPolicy.Decision temporalDecision,
        boolean filterNeedsClarification,
        String filterClarificationMessage,
        JoinPathResolutionPolicy.Decision joinDecision,
        String fallback
    ) {
        if (joinDecision != null && joinDecision.shouldClarifyAfterFailure()) {
            return joinDecision.clarificationMessage();
        }
        if (filterNeedsClarification && filterClarificationMessage != null && !filterClarificationMessage.isBlank()) {
            return filterClarificationMessage;
        }
        if (temporalDecision != null && temporalDecision.shouldClarifyAfterFailure()) {
            return temporalDecision.clarificationMessage();
        }
        return fallback;
    }

    String clarificationForExecutionFailure(
        TemporalResolutionPolicy.Decision temporalDecision,
        boolean filterNeedsClarification,
        String filterClarificationMessage,
        JoinPathResolutionPolicy.Decision joinDecision,
        String executionError
    ) {
        String targeted = clarificationForGenerationFailure(
            temporalDecision,
            filterNeedsClarification,
            filterClarificationMessage,
            joinDecision,
            "I need one clarification before I can run SQL safely: please confirm the missing business timestamp, join path, or filter semantics."
        );
        if (executionError == null || executionError.isBlank()) {
            return targeted;
        }
        return targeted + " Current blocker: " + executionError;
    }

    List<String> rankedAlternatives(
        TemporalResolutionPolicy.Decision temporalDecision,
        JoinPathResolutionPolicy.Decision joinDecision
    ) {
        if (joinDecision != null && !joinDecision.discardedAlternatives().isEmpty()) {
            return joinDecision.discardedAlternatives();
        }
        if (temporalDecision != null && !temporalDecision.alternatives().isEmpty()) {
            return temporalDecision.alternatives();
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }
}
