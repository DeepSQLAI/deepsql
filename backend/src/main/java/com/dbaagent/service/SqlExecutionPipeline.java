package com.dbaagent.service;

import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.QueryExecutionOrigin;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.client.advisor.vectorstore.QuestionAnswerAdvisor;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.springframework.ai.chat.memory.ChatMemory.CONVERSATION_ID;

@Service
@Slf4j
public class SqlExecutionPipeline {

    private final ChatClient chatClient;
    private final QueryExecutorService queryExecutorService;
    private final FeedbackService feedbackService;
    private final BusinessRuleMemoryService businessRuleMemoryService;

    @Nullable
    @Autowired(required = false)
    private QuestionAnswerAdvisor questionAnswerAdvisor;

    @Autowired
    public SqlExecutionPipeline(
            ChatModel chatModel,
            QueryExecutorService queryExecutorService,
            FeedbackService feedbackService,
            BusinessRuleMemoryService businessRuleMemoryService) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.queryExecutorService = queryExecutorService;
        this.feedbackService = feedbackService;
        this.businessRuleMemoryService = businessRuleMemoryService;
    }

    @Value("${app.chat.sql-fix.max-attempts:3}")
    private int sqlFixMaxAttempts;

    @Value("${app.chat.auto-learn.max-feedback-context-chars:4000}")
    private int maxFeedbackContextChars;

    public record SqlRepairResult(
        boolean success,
        String correctedSql,
        QueryResult queryResult,
        String finalResponse
    ) {
    }

    public String extractSqlFromResponse(String content) {
        List<String> extractedQueries = extractAllSqlFromResponse(content);
        return extractedQueries.isEmpty() ? null : extractedQueries.get(0);
    }

    public List<String> extractAllSqlFromResponse(String content) {
        if (content == null || content.isBlank()) {
            return List.of();
        }

        Pattern pattern = Pattern.compile("```sql\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);
        Matcher matcher = pattern.matcher(content);
        List<String> queries = new ArrayList<>();
        while (matcher.find()) {
            String extracted = matcher.group(1).trim();
            if (!extracted.isBlank()) {
                queries.add(extracted);
            }
        }

        if (!queries.isEmpty()) {
            log.debug("Extracted {} SQL block(s) from model response", queries.size());
            return queries;
        }

        log.debug("No SQL code block found in response");
        return List.of();
    }

    public boolean isAutoExecutableQuery(String sql) {
        String normalized = normalizeSqlForAutoExecution(sql);
        if (normalized.isEmpty()) {
            return false;
        }

        String lower = normalized.toLowerCase();
        return lower.startsWith("select")
            || lower.startsWith("show")
            || lower.startsWith("with")
            || lower.startsWith("explain")
            || lower.startsWith("describe")
            || lower.startsWith("desc");
    }

    private String normalizeSqlForAutoExecution(String sql) {
        if (sql == null) {
            return "";
        }

        String normalized = sql.trim();
        while (!normalized.isEmpty()) {
            if (normalized.startsWith("--")) {
                int newline = normalized.indexOf('\n');
                normalized = newline >= 0 ? normalized.substring(newline + 1).trim() : "";
                continue;
            }
            if (normalized.startsWith("/*")) {
                int end = normalized.indexOf("*/");
                normalized = end >= 0 ? normalized.substring(end + 2).trim() : "";
                continue;
            }
            break;
        }
        return normalized;
    }

    /**
     * Formats multiple query results into a combined AI-readable string.
     * Each block is labelled with its step number and query.
     */
    public String formatMultipleQueryResults(List<String> sqls, List<QueryResult> results) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < results.size(); i++) {
            sb.append("=== Query ").append(i + 1).append(" ===\n");
            if (i < sqls.size()) {
                sb.append("SQL: ").append(sqls.get(i), 0, Math.min(200, sqls.get(i).length())).append("\n");
            }
            sb.append(formatQueryResultForAI(results.get(i)));
            sb.append("\n\n");
        }
        return sb.toString().trim();
    }

    /**
     * Extract full error message from exception, including nested causes
     */
    public String getFullErrorMessage(Exception e) {
        StringBuilder sb = new StringBuilder();
        Throwable current = e;
        while (current != null) {
            if (sb.length() > 0) {
                sb.append(" -> ");
            }
            sb.append(current.getMessage());
            current = current.getCause();
        }
        return sb.toString();
    }

    public SqlRepairResult attemptSqlRepairAndExecute(
            String connectionId,
            String userQuestion,
            String dbType,
            List<Message> baseMessages,
            String originalAssistantResponse,
            String failedSql,
            String initialError,
            String conversationId,
            boolean useChatMemoryAdvisor,
            boolean useQuestionAnswerAdvisor,
            String explicitBusinessRule,
            List<BusinessRuleMemoryService.SqlGuardrail> guardrails,
            String dbSpecificRules) {
        String feedbackContext = feedbackService.buildFeedbackContext(connectionId);
        if (explicitBusinessRule != null && !explicitBusinessRule.isBlank()) {
            feedbackContext = feedbackContext + "\n- [CURRENT USER CORRECTION] " + explicitBusinessRule + "\n";
        }
        if (feedbackContext.length() > maxFeedbackContextChars) {
            feedbackContext = feedbackContext.substring(0, maxFeedbackContextChars);
        }

        String currentSql = failedSql;
        String currentError = initialError;

        for (int attempt = 1; attempt <= sqlFixMaxAttempts; attempt++) {
            List<Message> fixMessages = new ArrayList<>(baseMessages);
            fixMessages.add(new AssistantMessage(originalAssistantResponse));
            fixMessages.add(new SystemMessage(
                buildSqlRepairPrompt(
                    userQuestion,
                    dbType,
                    currentSql,
                    currentError,
                    feedbackContext,
                    attempt,
                    dbSpecificRules
                )
            ));

            String fixResponse;
            try {
                fixResponse = chatClient.prompt()
                    .messages(fixMessages)
                    .advisors(advisorSpec -> {
                        if (useChatMemoryAdvisor) {
                            advisorSpec.param(CONVERSATION_ID, conversationId);
                        }
                        if (useQuestionAnswerAdvisor) {
                            advisorSpec.advisors(questionAnswerAdvisor);
                        }
                    })
                    .call()
                    .content();
            } catch (Exception modelError) {
                currentError = "Failed to generate corrected SQL: " + modelError.getMessage();
                continue;
            }

            String candidateSql = extractSqlFromResponse(fixResponse);
            if (candidateSql == null || candidateSql.isBlank()) {
                currentError = "Model did not return SQL in correction attempt " + attempt;
                continue;
            }
            if (currentSql != null && candidateSql.trim().equalsIgnoreCase(currentSql.trim())) {
                currentError = "Model returned the same SQL in correction attempt " + attempt;
                continue;
            }

            // Every repaired candidate must pass deterministic business guardrails before execution.
            BusinessRuleMemoryService.SqlGuardrailEvaluation guardrailCheck =
                businessRuleMemoryService.evaluateSql(candidateSql, guardrails);
            if (!guardrailCheck.passed()) {
                currentSql = candidateSql;
                currentError = "Corrected SQL still violates learned guardrails: " + guardrailCheck.summary();
                continue;
            }

            try {
                QueryRequest retryRequest = new QueryRequest();
                retryRequest.setQuery(candidateSql);
                retryRequest.setLimit(100);
                retryRequest.setExecutionOrigin(QueryExecutionOrigin.CHAT);
                QueryResult retryResult = queryExecutorService.executeQuery(
                    connectionId,
                    retryRequest,
                    QueryExecutionContext.chat()
                );

                if (shouldRetryOnSuspiciousEmptyResult(userQuestion, retryResult, attempt)) {
                    currentSql = candidateSql;
                    currentError = "Query executed but returned no rows. Re-evaluate joins and filters.";
                    continue;
                }

                fixMessages.add(new AssistantMessage(fixResponse));
                fixMessages.add(new SystemMessage(
                    "The corrected query was executed successfully. Here are the results:\n" +
                    formatQueryResultForAI(retryResult) +
                    "\n\nPlease provide a final summarized answer to the user based on these results."
                ));

                String finalResponse = chatClient.prompt()
                    .messages(fixMessages)
                    .advisors(advisorSpec -> {
                        if (useChatMemoryAdvisor) {
                            advisorSpec.param(CONVERSATION_ID, conversationId);
                        }
                        if (useQuestionAnswerAdvisor) {
                            advisorSpec.advisors(questionAnswerAdvisor);
                        }
                    })
                    .call()
                    .content();

                return new SqlRepairResult(true, candidateSql, retryResult, finalResponse);
            } catch (Exception retryException) {
                if (retryException instanceof QueryExecutionPolicyException policyException
                    && policyException.isChatReadOnlyBlock()) {
                    return new SqlRepairResult(false, candidateSql, null, policyException.getMessage());
                }
                currentSql = candidateSql;
                currentError = getFullErrorMessage(retryException);
                log.warn("SQL correction attempt {} failed: {}", attempt, currentError);
            }
        }

        String failureResponse = "I encountered an error executing the query: " + initialError +
            "\n\nI attempted " + sqlFixMaxAttempts +
            " SQL correction attempts using your schema and learned business rules, but couldn't produce a valid result." +
            "\nLast error: " + (currentError != null ? currentError : "unknown");

        return new SqlRepairResult(false, failedSql, null, failureResponse);
    }

    public boolean shouldAttemptSparseResultRefinement(
            String userQuestion,
            QueryResult result,
            String feedbackContext,
            String explicitBusinessRule) {
        if (sqlFixMaxAttempts <= 0 || !isSparseResult(result)) {
            return false;
        }

        String lowerQuestion = userQuestion != null ? userQuestion.toLowerCase() : "";
        boolean likelyMetricQuestion = lowerQuestion.contains("how much")
            || lowerQuestion.contains("total")
            || lowerQuestion.contains("revenue")
            || lowerQuestion.contains("count")
            || lowerQuestion.contains("sum")
            || lowerQuestion.contains("all of");
        if (!likelyMetricQuestion) {
            return false;
        }

        return (feedbackContext != null && !feedbackContext.isBlank())
            || (explicitBusinessRule != null && !explicitBusinessRule.isBlank());
    }

    public SqlRepairResult attemptSparseResultRefinement(
            String connectionId,
            String userQuestion,
            String dbType,
            List<Message> baseMessages,
            String originalAssistantResponse,
            String initialSql,
            QueryResult initialResult,
            String conversationId,
            boolean useChatMemoryAdvisor,
            boolean useQuestionAnswerAdvisor,
            String feedbackContext,
            String explicitBusinessRule,
            List<BusinessRuleMemoryService.SqlGuardrail> guardrails,
            String dbSpecificRules) {
        String mergedFeedbackContext = feedbackContext != null ? feedbackContext : "";
        if (explicitBusinessRule != null && !explicitBusinessRule.isBlank()
                && !mergedFeedbackContext.contains(explicitBusinessRule)) {
            mergedFeedbackContext = mergedFeedbackContext + "\n- [CURRENT USER CORRECTION] " + explicitBusinessRule + "\n";
        }
        if (mergedFeedbackContext.length() > maxFeedbackContextChars) {
            mergedFeedbackContext = mergedFeedbackContext.substring(0, maxFeedbackContextChars);
        }

        String currentSql = initialSql;
        QueryResult currentResult = initialResult;

        for (int attempt = 1; attempt <= sqlFixMaxAttempts; attempt++) {
            List<Message> refinementMessages = new ArrayList<>(baseMessages);
            refinementMessages.add(new AssistantMessage(originalAssistantResponse));
            refinementMessages.add(new SystemMessage(
                buildSparseResultRefinementPrompt(
                    userQuestion,
                    dbType,
                    currentSql,
                    currentResult,
                    mergedFeedbackContext,
                    attempt,
                    dbSpecificRules
                )
            ));

            String refinementResponse;
            try {
                refinementResponse = chatClient.prompt()
                    .messages(refinementMessages)
                    .advisors(advisorSpec -> {
                        if (useChatMemoryAdvisor) {
                            advisorSpec.param(CONVERSATION_ID, conversationId);
                        }
                        if (useQuestionAnswerAdvisor) {
                            advisorSpec.advisors(questionAnswerAdvisor);
                        }
                    })
                    .call()
                    .content();
            } catch (Exception modelError) {
                log.warn("Sparse-result refinement attempt {} failed to produce SQL: {}", attempt, modelError.getMessage());
                continue;
            }

            String candidateSql = extractSqlFromResponse(refinementResponse);
            if (candidateSql == null || candidateSql.isBlank()) {
                continue;
            }
            if (currentSql != null && candidateSql.trim().equalsIgnoreCase(currentSql.trim())) {
                continue;
            }

            // Keep sparse-result refinement bounded by the same learned constraints.
            BusinessRuleMemoryService.SqlGuardrailEvaluation guardrailCheck =
                businessRuleMemoryService.evaluateSql(candidateSql, guardrails);
            if (!guardrailCheck.passed()) {
                currentSql = candidateSql;
                continue;
            }

            try {
                QueryRequest retryRequest = new QueryRequest();
                retryRequest.setQuery(candidateSql);
                retryRequest.setLimit(100);
                retryRequest.setExecutionOrigin(QueryExecutionOrigin.CHAT);
                QueryResult retryResult = queryExecutorService.executeQuery(
                    connectionId,
                    retryRequest,
                    QueryExecutionContext.chat()
                );

                if (isSparseResult(retryResult)) {
                    currentSql = candidateSql;
                    currentResult = retryResult;
                    continue;
                }

                refinementMessages.add(new AssistantMessage(refinementResponse));
                refinementMessages.add(new SystemMessage(
                    "The refined query was executed successfully. Here are the results:\n" +
                    formatQueryResultForAI(retryResult) +
                    "\n\nPlease provide a final summarized answer to the user based on these results."
                ));

                String finalResponse = chatClient.prompt()
                    .messages(refinementMessages)
                    .advisors(advisorSpec -> {
                        if (useChatMemoryAdvisor) {
                            advisorSpec.param(CONVERSATION_ID, conversationId);
                        }
                        if (useQuestionAnswerAdvisor) {
                            advisorSpec.advisors(questionAnswerAdvisor);
                        }
                    })
                    .call()
                    .content();

                return new SqlRepairResult(true, candidateSql, retryResult, finalResponse);
            } catch (Exception retryError) {
                if (retryError instanceof QueryExecutionPolicyException policyException
                    && policyException.isChatReadOnlyBlock()) {
                    return new SqlRepairResult(false, candidateSql, currentResult, policyException.getMessage());
                }
                currentSql = candidateSql;
                log.warn("Sparse-result refinement attempt {} execution failed: {}", attempt, getFullErrorMessage(retryError));
            }
        }

        return new SqlRepairResult(false, initialSql, initialResult, null);
    }

    private String buildSqlRepairPrompt(
            String userQuestion,
            String dbType,
            String failedSql,
            String errorMessage,
            String feedbackContext,
            int attempt,
            String dbSpecificRules) {
        return "SQL CORRECTION ATTEMPT " + attempt + ":\n\n" +
            "User question:\n" + userQuestion + "\n\n" +
            "Failed SQL:\n" + failedSql + "\n\n" +
            "Execution error:\n" + errorMessage + "\n\n" +
            "Learned business context (if any):\n" +
            (feedbackContext == null || feedbackContext.isBlank() ? "none" : feedbackContext) + "\n\n" +
            "Instructions:\n" +
            "1. Generate corrected SQL for the same user intent.\n" +
            "2. Respect the learned business context and prior corrections.\n" +
            "3. Use explicit JOIN conditions and fully qualified column names.\n" +
            "4. Return ONLY a SQL code block: ```sql ... ```\n\n" +
            "Database rules:\n" + dbSpecificRules;
    }

    private boolean shouldRetryOnSuspiciousEmptyResult(String userQuestion, QueryResult result, int attempt) {
        if (attempt >= sqlFixMaxAttempts || result == null || result.getRows() == null) {
            return false;
        }
        if (!result.getRows().isEmpty()) {
            return false;
        }
        String lowerQuestion = userQuestion != null ? userQuestion.toLowerCase() : "";
        return lowerQuestion.contains("how much")
            || lowerQuestion.contains("total")
            || lowerQuestion.contains("revenue")
            || lowerQuestion.contains("count")
            || lowerQuestion.contains("all of");
    }

    private boolean isSparseResult(QueryResult result) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return true;
        }

        return result.getRows().stream()
            .noneMatch(row -> row != null && row.stream()
                .anyMatch(value -> {
                    if (value == null) {
                        return false;
                    }
                    if (value instanceof String strValue) {
                        return !strValue.isBlank();
                    }
                    return true;
                }));
    }

    private String buildSparseResultRefinementPrompt(
            String userQuestion,
            String dbType,
            String previousSql,
            QueryResult previousResult,
            String feedbackContext,
            int attempt,
            String dbSpecificRules) {
        return "SEMANTIC SQL REFINEMENT ATTEMPT " + attempt + ":\n\n" +
            "User question:\n" + userQuestion + "\n\n" +
            "Previously executed SQL:\n" + previousSql + "\n\n" +
            "Previous result summary:\n" + formatQueryResultForAI(previousResult) + "\n\n" +
            "Learned business context:\n" +
            (feedbackContext == null || feedbackContext.isBlank() ? "none" : feedbackContext) + "\n\n" +
            "Instructions:\n" +
            "1. Keep the same business intent; only fix joins/filters/value assumptions that may be too strict or reversed.\n" +
            "2. Respect learned business rules exactly.\n" +
            "3. Use explicit JOIN conditions and fully qualified column names.\n" +
            "4. Return ONLY a SQL code block: ```sql ... ```\n\n" +
            "Database rules:\n" + dbSpecificRules;
    }

    public String formatQueryResultForAI(QueryResult result) {
        if (result == null || result.getRows() == null || result.getRows().isEmpty()) {
            return "No rows returned.";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Columns: ").append(String.join(", ", result.getColumns())).append("\n");
        sb.append("Rows (showing up to 10):\n");

        int rowCount = Math.min(result.getRows().size(), 10);
        for (int i = 0; i < rowCount; i++) {
            List<Object> row = result.getRows().get(i);
            sb.append("- ").append(row.stream().map(String::valueOf).collect(Collectors.joining(", "))).append("\n");
        }

        if (result.getRows().size() > 10) {
            sb.append("... and ").append(result.getRows().size() - 10).append(" more rows.");
        }

        return sb.toString();
    }
}
