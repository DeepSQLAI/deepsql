package com.dbaagent.integration;

import com.dbaagent.model.ChatRequest;
import com.dbaagent.model.ChatResponse;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.opentest4j.TestAbortedException;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.DynamicTest.dynamicTest;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * SQL accuracy integration tests that validate the chat pipeline generates
 * correct SQL for domain-specific queries against the local MySQL database.
 *
 * Each test case is defined in sql-accuracy-test-cases.json with:
 * - prompt: the natural language question
 * - expectedTables: table names that MUST appear in the generated SQL
 * - expectedColumns: column names that SHOULD appear in the SQL
 * - expectedClauses: SQL clause patterns (GROUP BY, JOIN, SUM, etc.)
 * - forbiddenTables: tables that must NOT appear (specificity check)
 *
 * Validation strategy: structural correctness over exact string matching.
 * We check the model picked the right tables, columns, and SQL constructs
 * rather than expecting a specific SQL string (which is fragile with LLMs).
 *
 * Requirements:
 * - Local MySQL connection (6be6ae30-ba7e-4887-b72e-1a95da01f926) reachable
 * - Azure OpenAI credentials configured in application-test.properties
 * - Schema training completed (RAG data available)
 *
 * Run: mvn test -Dtest="SqlAccuracyIntegrationTest"
 */
@DisplayName("SQL Accuracy Integration Tests")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SqlAccuracyIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private ChatModel chatModel;

    private static final long LLM_TIMEOUT_MS = 60_000;

    private List<SqlTestCase> testCases;
    private String sqlAccuracyConnectionId;
    private String prerequisiteSkipReason;

    @BeforeAll
    void loadTestCases() throws Exception {
        sqlAccuracyConnectionId = requireTestConnectionId("SQL accuracy integration tests");
        try (InputStream is = getClass().getResourceAsStream("/sql-accuracy-test-cases.json")) {
            assertNotNull(is, "sql-accuracy-test-cases.json not found on classpath");
            testCases = objectMapper.readValue(is, new TypeReference<List<SqlTestCase>>() {});
        }
        try {
            ensureLlmDeploymentAvailable();
        } catch (TestAbortedException aborted) {
            prerequisiteSkipReason = aborted.getMessage();
            return;
        }
        System.out.printf("%n=== SQL Accuracy Test Suite ===%n");
        System.out.printf("Loaded %d test cases%n", testCases.size());
        System.out.printf("Connection: %s%n%n", sqlAccuracyConnectionId);
    }

    private void ensureLlmDeploymentAvailable() {
        try {
            var response = chatModel.call(new Prompt(List.of(new UserMessage("Reply with OK."))));
            String content = response.getResult() != null && response.getResult().getOutput() != null
                    ? response.getResult().getOutput().getText()
                    : "";
            if (content == null || content.isBlank()) {
                throw new IllegalStateException("LLM health probe returned an empty completion");
            }
        } catch (Exception e) {
            throw new TestAbortedException(
                "Skipping SQL accuracy integration tests because the configured live LLM deployment is unavailable: "
                    + rootCauseMessage(e),
                e
            );
        }
    }

    private String rootCauseMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() != null ? current.getMessage() : current.toString();
    }

    @TestFactory
    @DisplayName("SQL Accuracy Tests")
    Collection<DynamicTest> sqlAccuracyTests() {
        if (prerequisiteSkipReason != null) {
            return List.of(dynamicTest(
                "[prerequisites] SQL accuracy suite skipped",
                () -> {
                    throw new TestAbortedException(prerequisiteSkipReason);
                }));
        }

        assertNotNull(testCases, "sql-accuracy-test-cases.json must be loaded before executing SQL accuracy tests");
        assertFalse(testCases.isEmpty(), "sql-accuracy-test-cases.json must contain at least one SQL accuracy test case");

        List<DynamicTest> tests = new ArrayList<>();
        for (SqlTestCase tc : testCases) {
            tests.add(dynamicTest(
                    "[" + tc.id + "] " + tc.prompt,
                    () -> runSqlAccuracyTest(tc)));
        }
        return tests;
    }

    private void runSqlAccuracyTest(SqlTestCase tc) throws Exception {
        System.out.printf("--- [%s] %s ---%n", tc.id, tc.description);
        if (tc.setupPrompt != null && !tc.setupPrompt.isBlank()) {
            System.out.printf("  Setup prompt: %s%n", tc.setupPrompt);
            System.out.printf("  Follow-up prompt: %s%n", tc.followUpPrompt);
        } else {
            System.out.printf("  Prompt: %s%n", tc.prompt);
        }
        long startTime = System.currentTimeMillis();
        ChatResponse response;

        if (tc.setupPrompt != null && !tc.setupPrompt.isBlank()) {
            ChatResponse setupResponse = sendChatRequest(tc.setupPrompt, null);
            assertTrue(setupResponse.isSuccess(),
                    "[" + tc.id + "] Setup response should be successful. Message: " + setupResponse.getMessage());

            validateSqlResponse(
                    tc.id,
                    "setup",
                    setupResponse,
                    coalesce(tc.setupExpectedTables, tc.expectedTables),
                    coalesce(tc.setupExpectedColumns, tc.expectedColumns),
                    coalesce(tc.setupExpectedClauses, tc.expectedClauses),
                    coalesce(tc.setupForbiddenTables, tc.forbiddenTables)
            );

            assertNotNull(tc.followUpPrompt,
                    "[" + tc.id + "] followUpPrompt is required when setupPrompt is provided");
            assertFalse(tc.followUpPrompt.isBlank(),
                    "[" + tc.id + "] followUpPrompt must not be blank when setupPrompt is provided");

            response = sendChatRequest(tc.followUpPrompt, setupResponse.getChatId());
        } else {
            response = sendChatRequest(tc.prompt, null);
        }

        long durationMs = System.currentTimeMillis() - startTime;

        System.out.printf("  Duration: %dms%n", durationMs);
        System.out.printf("  Success: %s%n", response.isSuccess());

        // 1. Response must be successful
        assertTrue(response.isSuccess(),
                "[" + tc.id + "] Response should be successful. Message: " + response.getMessage());

        // 2. Must complete within timeout
        assertTrue(durationMs < LLM_TIMEOUT_MS,
                "[" + tc.id + "] Should complete within " + LLM_TIMEOUT_MS + "ms, took " + durationMs + "ms");

        if (Boolean.TRUE.equals(tc.expectClarification)) {
            String firstMessage = response.getMessage() == null ? "" : response.getMessage();
            String firstSql = response.getSql();

            boolean didClarify = (firstSql == null || firstSql.isBlank())
                    && !firstMessage.isBlank()
                    && firstMessage.contains("?");

            if (didClarify) {
                System.out.printf("  Clarification: YES (as expected)%n");

                if (tc.expectedClarificationKeywords != null && !tc.expectedClarificationKeywords.isEmpty()) {
                    String upperMessage = firstMessage.toUpperCase();
                    long matched = tc.expectedClarificationKeywords.stream()
                            .filter(k -> upperMessage.contains(k.toUpperCase()))
                            .count();
                    assertTrue(matched >= 1,
                            "[" + tc.id + "] Clarification message missing expected keywords: "
                                    + tc.expectedClarificationKeywords + "\nMessage: " + firstMessage);
                }

                assertNotNull(tc.followUpPrompt,
                        "[" + tc.id + "] followUpPrompt is required when expectClarification=true");
                assertFalse(tc.followUpPrompt.isBlank(),
                        "[" + tc.id + "] followUpPrompt must not be blank");

                ChatResponse followUpResponse = sendChatRequest(tc.followUpPrompt, response.getChatId());
                assertTrue(followUpResponse.isSuccess(),
                        "[" + tc.id + "] Follow-up response unsuccessful");

                validateSqlResponse(
                        tc.id,
                        "follow-up",
                        followUpResponse,
                        coalesce(tc.followUpExpectedTables, tc.expectedTables),
                        coalesce(tc.followUpExpectedColumns, tc.expectedColumns),
                        coalesce(tc.followUpExpectedClauses, tc.expectedClauses),
                        coalesce(tc.followUpForbiddenTables, tc.forbiddenTables)
                );
            } else {
                // LLM skipped clarification — accept but only validate structural SQL correctness.
                // Table/column expectations are based on the disambiguated follow-up prompt, so an
                // ambiguous first-turn answer may legitimately pick different tables.
                System.out.printf("  Clarification: NO (LLM answered directly, validating SQL structure)%n");

                String directSql = response.getSql();
                String directMessage = response.getMessage();
                String sqlToCheck = directSql != null ? directSql : directMessage;
                assertNotNull(sqlToCheck,
                        "[" + tc.id + "] (direct-answer) Both sql and message are null");

                // Only validate clauses (structural correctness) — not tables/columns
                List<String> clauses = coalesce(tc.followUpExpectedClauses, tc.expectedClauses);
                if (clauses != null && !clauses.isEmpty()) {
                    String sqlUpper = sqlToCheck.toUpperCase();
                    long matched = clauses.stream()
                            .filter(c -> sqlUpper.contains(c.toUpperCase()))
                            .count();
                    double passRate = (double) matched / clauses.size();
                    System.out.printf("  Clause check (direct-answer): %d/%d (%.0f%%)%n",
                            matched, clauses.size(), passRate * 100);
                    assertTrue(passRate >= 0.5,
                            "[" + tc.id + "] (direct-answer) Clause pass rate %.0f%% < 50%%: expected %s"
                                    .formatted(passRate * 100, clauses));
                }
            }
        } else if (Boolean.TRUE.equals(tc.expectNoSql)) {
            validateNoSqlResponse(
                    tc.id,
                    "single-turn",
                    response,
                    tc.expectedMessageKeywords
            );
        } else {
            validateSqlResponse(
                    tc.id,
                    "single-turn",
                    response,
                    tc.expectedTables,
                    tc.expectedColumns,
                    tc.expectedClauses,
                    tc.forbiddenTables
            );
        }
    }

    private ChatResponse sendChatRequest(String prompt, String chatId) throws Exception {
        ChatRequest request = new ChatRequest();
        request.setConnectionId(sqlAccuracyConnectionId);
        request.setMessage(prompt);
        if (chatId != null && !chatId.isBlank()) {
            request.setChatId(chatId);
        }

        MvcResult mvcResult = mockMvc.perform(post(apiPath("/chat"))
                        .contextPath(CONTEXT_PATH)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andReturn();

        int statusCode = mvcResult.getResponse().getStatus();
        String responseJson = mvcResult.getResponse().getContentAsString();
        assertEquals(200, statusCode,
                "Expected 200 from /chat for prompt '" + prompt + "' but got "
                        + statusCode + " with body: " + responseJson);
        return objectMapper.readValue(responseJson, ChatResponse.class);
    }

    private <T> List<T> coalesce(List<T> preferred, List<T> fallback) {
        return preferred != null ? preferred : fallback;
    }

    private void validateNoSqlResponse(
            String testId,
            String phase,
            ChatResponse response,
            List<String> expectedMessageKeywords
    ) {
        String sql = response.getSql();
        String message = response.getMessage();

        assertTrue(sql == null || sql.isBlank(),
                "[" + testId + "] (" + phase + ") Expected no SQL for metadata-routed question, but got: " + sql);
        assertNotNull(message,
                "[" + testId + "] (" + phase + ") Expected a metadata answer message");
        assertFalse(message.isBlank(),
                "[" + testId + "] (" + phase + ") Expected a non-empty metadata answer message");

        System.out.printf("  SQL (%s): <none>%n", phase);
        System.out.printf("  Message (%s): %s%n",
                phase,
                message.replace("\n", " ").substring(0, Math.min(message.length(), 200)));

        if (expectedMessageKeywords != null && !expectedMessageKeywords.isEmpty()) {
            String upperMessage = message.toUpperCase();
            long matched = expectedMessageKeywords.stream()
                    .filter(keyword -> upperMessage.contains(keyword.toUpperCase()))
                    .count();
            double passRate = (double) matched / expectedMessageKeywords.size();
            System.out.printf("  Message keywords (%s): %d/%d (%.0f%%)%n",
                    phase, matched, expectedMessageKeywords.size(), passRate * 100);
            assertTrue(passRate >= 0.5,
                    "[" + testId + "] (" + phase + ") Message keyword pass rate %.0f%% < 50%%: expected %s"
                            .formatted(passRate * 100, expectedMessageKeywords));
        }

        System.out.println();
    }

    private void validateSqlResponse(
            String testId,
            String phase,
            ChatResponse response,
            List<String> expectedTables,
            List<String> expectedColumns,
            List<String> expectedClauses,
            List<String> forbiddenTables
    ) {
        String sql = response.getSql();
        String message = response.getMessage();

        String sqlToCheck = sql != null ? sql : message;
        assertNotNull(sqlToCheck,
                "[" + testId + "] (" + phase + ") Both sql and message are null");

        String sqlUpper = sqlToCheck.toUpperCase();
        System.out.printf("  SQL (%s): %s%n",
                phase,
                sql != null
                        ? sql.replace("\n", " ").substring(0, Math.min(sql.length(), 200))
                        : "(embedded in message)");

        List<String> failures = new ArrayList<>();
        List<String> passes = new ArrayList<>();

        for (String table : expectedTables) {
            if (sqlUpper.contains(table.toUpperCase())) passes.add("TABLE:" + table);
            else failures.add("TABLE:" + table);
        }

        for (String col : expectedColumns) {
            if (sqlUpper.contains(col.toUpperCase())) passes.add("COL:" + col);
            else failures.add("COL:" + col);
        }

        for (String clause : expectedClauses) {
            if (sqlUpper.contains(clause.toUpperCase())) passes.add("CLAUSE:" + clause);
            else failures.add("CLAUSE:" + clause);
        }

        for (String table : forbiddenTables) {
            if (sqlUpper.contains(table.toUpperCase())) failures.add("FORBIDDEN:" + table);
            else passes.add("NOT:" + table);
        }

        int total = passes.size() + failures.size();
        System.out.printf("  Assertions (%s): %d/%d passed%n", phase, passes.size(), total);
        if (!failures.isEmpty()) {
            System.out.printf("  FAILURES (%s): %s%n", phase, failures);
        }

        List<String> tableFailures = failures.stream()
                .filter(f -> f.startsWith("TABLE:"))
                .toList();
        assertTrue(tableFailures.isEmpty(),
                "[" + testId + "] (" + phase + ") Missing expected tables in SQL: " + tableFailures +
                        "\nSQL: " + (sql != null ? sql : "(in message)") +
                        "\nMessage: " + message);

        long nonTableChecks = passes.size() + failures.size()
                - expectedTables.size() - forbiddenTables.size();
        long nonTablePasses = passes.stream()
                .filter(p -> !p.startsWith("TABLE:") && !p.startsWith("NOT:"))
                .count();
        if (nonTableChecks > 0) {
            double passRate = (double) nonTablePasses / nonTableChecks;
            assertTrue(passRate >= 0.5,
                    "[" + testId + "] (" + phase + ") Column/clause pass rate %.0f%% < 50%%: failures=%s"
                            .formatted(passRate * 100, failures));
        }

        List<String> forbiddenFailures = failures.stream()
                .filter(f -> f.startsWith("FORBIDDEN:"))
                .toList();
        assertTrue(forbiddenFailures.isEmpty(),
                "[" + testId + "] (" + phase + ") Forbidden tables found in SQL: " + forbiddenFailures);

        System.out.println();
    }

    /**
     * Test case POJO loaded from sql-accuracy-test-cases.json.
     */
    static class SqlTestCase {
        public String id;
        public String category;
        public String prompt;
        public List<String> expectedTables = List.of();
        public List<String> expectedColumns = List.of();
        public List<String> expectedClauses = List.of();
        public List<String> forbiddenTables = List.of();
        public Boolean expectNoSql = false;
        public List<String> expectedMessageKeywords = List.of();
        public Boolean expectClarification = false;
        public List<String> expectedClarificationKeywords = List.of();
        public String setupPrompt;
        public List<String> setupExpectedTables;
        public List<String> setupExpectedColumns;
        public List<String> setupExpectedClauses;
        public List<String> setupForbiddenTables;
        public String followUpPrompt;
        public List<String> followUpExpectedTables;
        public List<String> followUpExpectedColumns;
        public List<String> followUpExpectedClauses;
        public List<String> followUpForbiddenTables;
        public String description;

        @Override
        public String toString() {
            return id + ": " + prompt;
        }
    }
}
