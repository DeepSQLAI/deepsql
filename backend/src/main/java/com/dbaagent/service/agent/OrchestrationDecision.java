package com.dbaagent.service.agent;

import java.util.List;

public record OrchestrationDecision(
    PromptIntent.Domain domain,
    String userGoal,
    ThreadMode threadMode,
    boolean needsClarification,
    String clarificationQuestion,
    List<String> planOutline,
    OrchestrationAction nextAction,
    List<String> successCriteria,
    double confidence,
    String decisionSource,
    String reason
) {
    public enum ThreadMode {
        FRESH,
        REUSE_SCOPE,
        RESET_TOPIC,
        CLARIFICATION_REPLY
    }

    public OrchestrationDecision {
        domain = domain == null ? PromptIntent.Domain.GENERAL : domain;
        threadMode = threadMode == null ? ThreadMode.FRESH : threadMode;
        clarificationQuestion = clarificationQuestion == null ? "" : clarificationQuestion;
        planOutline = planOutline == null ? List.of() : List.copyOf(planOutline);
        successCriteria = successCriteria == null ? List.of() : List.copyOf(successCriteria);
        decisionSource = decisionSource == null || decisionSource.isBlank() ? "FALLBACK" : decisionSource;
        reason = reason == null ? "" : reason;
    }

    public String summarize() {
        StringBuilder summary = new StringBuilder();
        summary.append("Domain: ").append(domain.name())
            .append("\nThread mode: ").append(threadMode.name())
            .append("\nGoal: ").append(userGoal);
        if (!planOutline.isEmpty()) {
            summary.append("\nPlan outline: ").append(String.join(" -> ", planOutline));
        }
        if (nextAction != null && nextAction.hasTool()) {
            summary.append("\nNext action: ").append(nextAction.tool());
        }
        if (!successCriteria.isEmpty()) {
            summary.append("\nSuccess criteria: ").append(String.join("; ", successCriteria));
        }
        return summary.toString();
    }
}
