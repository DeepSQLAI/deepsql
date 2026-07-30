package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.springframework.lang.Nullable;

import java.util.List;

public record AgentExecutionResult(
    @Nullable String runId,
    AgentIntent intent,
    String message,
    @Nullable QueryResult primaryResult,
    String planSummary,
    List<String> executedQueries,
    List<String> toolsUsed,
    double confidence,
    List<AgentTaskResult> taskResults,
    @Nullable PromptIntent promptIntent,
    @Nullable AnswerContract answerContract,
    @Nullable VerificationReport verificationReport
) {
    public AgentExecutionResult(
        @Nullable String runId,
        AgentIntent intent,
        String message,
        @Nullable QueryResult primaryResult,
        String planSummary,
        List<String> executedQueries,
        List<String> toolsUsed,
        double confidence
    ) {
        this(runId, intent, message, primaryResult, planSummary, executedQueries, toolsUsed, confidence, List.of(), null, null, null);
    }

    public AgentExecutionResult(
        @Nullable String runId,
        AgentIntent intent,
        String message,
        @Nullable QueryResult primaryResult,
        String planSummary,
        List<String> executedQueries,
        List<String> toolsUsed,
        double confidence,
        List<AgentTaskResult> taskResults
    ) {
        this(runId, intent, message, primaryResult, planSummary, executedQueries, toolsUsed, confidence, taskResults, null, null, null);
    }

    public AgentExecutionResult {
        executedQueries = executedQueries == null ? List.of() : List.copyOf(executedQueries);
        toolsUsed = toolsUsed == null ? List.of() : List.copyOf(toolsUsed);
        taskResults = taskResults == null ? List.of() : List.copyOf(taskResults);
    }
}
