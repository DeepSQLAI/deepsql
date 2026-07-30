package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.ResolvedConversationContext;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class AgentExecutionContext {
    public record ConversationTurn(String role, String content) {}

    private final String connectionId;
    private final String question;
    private final String effectiveQuestion;
    private final String chatId;
    private final List<ConversationTurn> conversationHistory;
    private final ResolvedConversationContext resolvedConversationContext;
    private final PromptIntent promptIntent;
    private final SchemaMetadata schema;
    private final String dbType;
    private final Map<String, Object> workingMemory = new HashMap<>();
    private final List<String> executedQueries = new ArrayList<>();
    private final List<QueryResult> queryResults = new ArrayList<>();
    private final List<AgentObservation> observations = new ArrayList<>();
    private final List<String> toolsUsed = new ArrayList<>();
    private final Map<String, AgentTaskResult> taskResults = new LinkedHashMap<>();
    private final List<VerificationReport> verificationReports = new ArrayList<>();

    public AgentExecutionContext(
            String connectionId,
            String question,
            String effectiveQuestion,
            String chatId,
            List<ConversationTurn> conversationHistory,
            ResolvedConversationContext resolvedConversationContext,
            PromptIntent promptIntent,
            SchemaMetadata schema,
            String dbType) {
        this.connectionId = connectionId;
        this.question = question;
        this.effectiveQuestion = effectiveQuestion;
        this.chatId = chatId;
        this.conversationHistory = conversationHistory == null ? List.of() : List.copyOf(conversationHistory);
        this.resolvedConversationContext = resolvedConversationContext == null ? ResolvedConversationContext.empty() : resolvedConversationContext;
        this.promptIntent = promptIntent == null ? PromptIntent.unsupported() : promptIntent;
        this.schema = schema;
        this.dbType = dbType;
    }

    public AgentExecutionContext(String connectionId, String question, SchemaMetadata schema, String dbType) {
        this(connectionId, question, question, null, List.of(), ResolvedConversationContext.empty(), PromptIntent.unsupported(), schema, dbType);
    }

    public AgentExecutionContext(
            String connectionId,
            String question,
            String effectiveQuestion,
            String chatId,
            List<ConversationTurn> conversationHistory,
            ResolvedConversationContext resolvedConversationContext,
            SchemaMetadata schema,
            String dbType) {
        this(connectionId, question, effectiveQuestion, chatId, conversationHistory, resolvedConversationContext, PromptIntent.unsupported(), schema, dbType);
    }

    public String connectionId() {
        return connectionId;
    }

    public String question() {
        return question;
    }

    public String effectiveQuestion() {
        return effectiveQuestion;
    }

    public String chatId() {
        return chatId;
    }

    public List<ConversationTurn> conversationHistory() {
        return conversationHistory;
    }

    public ResolvedConversationContext resolvedConversationContext() {
        return resolvedConversationContext;
    }

    public PromptIntent promptIntent() {
        return promptIntent;
    }

    public SchemaMetadata schema() {
        return schema;
    }

    public String dbType() {
        return dbType;
    }

    public void putMemory(String key, Object value) {
        workingMemory.put(key, value);
    }

    @SuppressWarnings("unchecked")
    public <T> T getMemory(String key) {
        return (T) workingMemory.get(key);
    }

    public Map<String, Object> workingMemory() {
        return workingMemory;
    }

    public void recordToolExecution(String toolName, AgentToolResult result) {
        toolsUsed.add(toolName);
        observations.add(result.observation());
        executedQueries.addAll(result.allExecutedQueries());
        queryResults.addAll(result.allQueryResults());
    }

    public List<String> executedQueries() {
        return List.copyOf(executedQueries);
    }

    public List<QueryResult> queryResults() {
        return List.copyOf(queryResults);
    }

    public List<AgentObservation> observations() {
        return List.copyOf(observations);
    }

    public List<String> toolsUsed() {
        return List.copyOf(toolsUsed);
    }

    public void recordTaskResult(AgentTaskResult taskResult) {
        if (taskResult == null || taskResult.taskId() == null || taskResult.taskId().isBlank()) {
            return;
        }
        taskResults.put(taskResult.taskId(), taskResult);
    }

    public AgentTaskResult getTaskResult(String taskId) {
        return taskResults.get(taskId);
    }

    public List<AgentTaskResult> taskResults() {
        return List.copyOf(taskResults.values());
    }

    public void recordVerificationReport(VerificationReport verificationReport) {
        if (verificationReport != null) {
            verificationReports.add(verificationReport);
        }
    }

    public List<VerificationReport> verificationReports() {
        return List.copyOf(verificationReports);
    }
}
