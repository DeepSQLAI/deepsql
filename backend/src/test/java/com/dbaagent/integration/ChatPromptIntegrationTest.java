package com.dbaagent.integration;

import com.dbaagent.model.ChatRequest;
import com.dbaagent.model.ChatResponse;
import com.dbaagent.support.RequiresLlm;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for Chat prompts testing accuracy and speed.
 *
 * <p>Tests various DBA question categories:
 * <ul>
 *   <li>Schema/Structure questions (fast-path)</li>
 *   <li>Slow query questions (fast-path)</li>
 *   <li>Index recommendation questions (fast-path)</li>
 *   <li>Workload type questions (fast-path)</li>
 *   <li>LLM-routed questions (complex queries requiring AI)</li>
 * </ul>
 *
 * <p>Configuration requirements:
 * <ul>
 *   <li>Database connection: Set {@code TEST_CONNECTION_ID} environment variable or
 *       configure {@code test.connection.id} in application-test.properties</li>
 *   <li>LLM credentials: Set {@code DEEPSQL_CHAT_PROVIDER} and {@code DEEPSQL_CHAT_API_KEY}
 *       environment variables, or configure via the setup wizard</li>
 * </ul>
 *
 * <p>Performance targets:
 * <ul>
 *   <li>Fast-path questions: &lt; 500ms (server-side, excluding test overhead)</li>
 *   <li>LLM questions: &lt; 30s (depends on LLM provider)</li>
 * </ul>
 *
 * <p>Tests in this class require LLM configuration and will be skipped if not available.
 */
@DisplayName("Chat Prompt Integration Tests")
@RequiresLlm(chat = true)
class ChatPromptIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    // Performance thresholds in milliseconds
    // Note: These include some test overhead (JSON serialization, MockMvc)
    // Server-side fast-path is typically <100ms; we use 500ms to account for overhead
    private static final long FAST_PATH_THRESHOLD_MS = 500;
    private static final long LLM_THRESHOLD_MS = 30000;

    /**
     * Helper to send a chat request and measure response time.
     * Timing excludes print() overhead for more accurate measurements.
     */
    private ChatTestResult sendChatRequest(String message) throws Exception {
        return sendChatRequest(message, false);
    }

    /**
     * Helper to send a chat request with optional verbose output.
     */
    private ChatTestResult sendChatRequest(String message, boolean verbose) throws Exception {
        String connectionId = requireTestConnectionId("chat prompt integration tests");

        ChatRequest request = new ChatRequest();
        request.setConnectionId(connectionId);
        request.setMessage(message);
        request.setChatId(UUID.randomUUID().toString());

        String requestJson = objectMapper.writeValueAsString(request);

        // Measure timing without print() overhead
        long startTime = System.currentTimeMillis();

        MvcResult mvcResult = mockMvc.perform(post(apiPath("/chat"))
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestJson))
                .andExpect(status().isOk())
                .andReturn();

        long endTime = System.currentTimeMillis();
        long durationMs = endTime - startTime;

        String responseJson = mvcResult.getResponse().getContentAsString();
        ChatResponse response = objectMapper.readValue(responseJson, ChatResponse.class);

        // Print after timing measurement if verbose
        if (verbose) {
            System.out.println("Request: " + requestJson);
            System.out.println("Response: " + responseJson);
        }

        return new ChatTestResult(response, durationMs);
    }

    /**
     * Helper to verify response contains expected keywords.
     */
    private void assertContainsKeywords(String response, String... keywords) {
        String lowerResponse = response.toLowerCase();
        for (String keyword : keywords) {
            assertTrue(lowerResponse.contains(keyword.toLowerCase()),
                    "Response should contain keyword: '" + keyword + "'\nActual response: " + response);
        }
    }

    /**
     * Helper to assert response is successful (not an error).
     */
    private void assertSuccessResponse(ChatTestResult result) {
        assertTrue(result.response.isSuccess(),
                "Response should be successful. Message: " + result.response.getMessage());
        assertNotNull(result.response.getMessage(),
                "Response message should not be null");
        assertFalse(result.response.getMessage().toLowerCase().contains("error processing"),
                "Response should not contain error message: " + result.response.getMessage());
    }

    /**
     * Helper to assert fast-path performance.
     */
    private void assertFastPathPerformance(ChatTestResult result, String testName) {
        assertTrue(result.durationMs < FAST_PATH_THRESHOLD_MS,
                testName + " should complete in <" + FAST_PATH_THRESHOLD_MS + "ms, took " + result.durationMs + "ms");
    }

    private void assertNoSqlGenerated(ChatTestResult result, String testName) {
        assertTrue(result.response.getSql() == null || result.response.getSql().isBlank(),
                testName + " should not generate SQL. SQL: " + result.response.getSql());
    }

    /**
     * Helper to print test results.
     */
    private void printTestResult(String testName, ChatTestResult result) {
        System.out.println("=== TEST: " + testName + " ===");
        System.out.println("Duration: " + result.durationMs + "ms");
        System.out.println("Success: " + result.response.isSuccess());
        System.out.println("Response: " + result.response.getMessage());
    }

    // ==================== SCHEMA/STRUCTURE QUESTIONS (FAST-PATH) ====================

    @Test
    @DisplayName("Schema: How many tables? - should return count quickly")
    void testTableCount() throws Exception {
        ChatTestResult result = sendChatRequest("How many tables?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Table count");
        assertTrue(result.response.getMessage().matches(".*\\d+.*"),
                "Response should contain a number");

        printTestResult("How many tables?", result);
    }

    @Test
    @DisplayName("Schema: What are my largest tables? - should return by row count")
    void testLargestTablesByRowCount() throws Exception {
        ChatTestResult result = sendChatRequest("What are my largest tables?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Largest tables by row count");
        assertContainsKeywords(result.response.getMessage(), "rows");

        printTestResult("What are my largest tables?", result);
    }

    @Test
    @DisplayName("Schema: What are my largest tables by size? - should return by bytes")
    void testLargestTablesBySize() throws Exception {
        ChatTestResult result = sendChatRequest("What are my largest tables by size?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Largest tables by size");
        // Should mention tables or size-related content
        String msg = result.response.getMessage().toLowerCase();
        assertTrue(msg.contains("table") || msg.contains("size") || msg.contains("kb") ||
                   msg.contains("mb") || msg.contains("gb") || msg.contains("bytes") ||
                   msg.matches(".*\\d+.*"),
                "Response should contain table/size information");

        printTestResult("What are my largest tables by size?", result);
    }

    @Test
    @DisplayName("Schema: Database overview - should return summary stats")
    void testDatabaseOverview() throws Exception {
        ChatTestResult result = sendChatRequest("Show me database overview");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Database overview");
        assertContainsKeywords(result.response.getMessage(), "table");

        printTestResult("Database overview", result);
    }

    @Test
    @DisplayName("Schema: What is the database type? - should return database type")
    void testDatabaseType() throws Exception {
        ChatTestResult result = sendChatRequest("What database type is this?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Database type");
        // Should mention some database type (mysql, postgres, etc.)
        String msg = result.response.getMessage().toLowerCase();
        assertTrue(msg.contains("mysql") || msg.contains("postgres") ||
                   msg.contains("postgresql") || msg.contains("database type") ||
                   msg.contains("mariadb") || msg.contains("aurora"),
                "Response should mention a database type");

        printTestResult("What database type?", result);
    }

    @Test
    @DisplayName("Brain routing: key column question should use stored metadata, not SQL")
    void testKeyColumnsUseMetadataRoute() throws Exception {
        ChatTestResult result = sendChatRequest("How many key columns do we have?");

        assertSuccessResponse(result);
        assertNoSqlGenerated(result, "Key column metadata route");
        assertContainsKeywords(result.response.getMessage(), "key", "column");

        printTestResult("How many key columns do we have?", result);
    }

    @Test
    @DisplayName("Brain routing: relationship question should use stored metadata, not SQL")
    void testRelationshipsUseMetadataRoute() throws Exception {
        ChatTestResult result = sendChatRequest("How are CUSTOMER_ORDERS and CONTACT_MAPPING related?");

        assertSuccessResponse(result);
        assertNoSqlGenerated(result, "Relationship metadata route");
        assertContainsKeywords(result.response.getMessage(), "customer_orders", "contact_mapping");

        printTestResult("How are CUSTOMER_ORDERS and CONTACT_MAPPING related?", result);
    }

    @Test
    @DisplayName("BI routing: revenue question should generate SQL on business tables")
    void testBiQuestionGeneratesSql() throws Exception {
        ChatTestResult result = sendChatRequest("Show total booking revenue by customer for the last 30 days");

        assertSuccessResponse(result);
        assertTrue(result.durationMs < LLM_THRESHOLD_MS,
                "BI question should complete in <" + LLM_THRESHOLD_MS + "ms, took " + result.durationMs + "ms");
        assertNotNull(result.response.getSql(), "BI question should generate SQL");
        assertFalse(result.response.getSql().isBlank(), "BI question should generate non-empty SQL");
        assertContainsKeywords(result.response.getSql(), "customer_orders", "sum", "group by");

        printTestResult("Show total booking revenue by customer for the last 30 days", result);
    }

    // ==================== SLOW QUERY QUESTIONS (FAST-PATH) ====================

    @Test
    @DisplayName("SlowQuery: What is my slowest query? - should return from ingested logs")
    void testSlowestQuery() throws Exception {
        ChatTestResult result = sendChatRequest("What is my slowest query?");

        assertSuccessResponse(result);
        // Assert fast-path performance when logs are ingested
        assertFastPathPerformance(result, "Slowest query");

        printTestResult("What is my slowest query?", result);
        if (result.durationMs < 100) {
            System.out.println(">>> FAST-PATH response detected (<100ms)");
        }
    }

    @Test
    @DisplayName("SlowQuery: Top 5 slow queries - should return ranked list")
    void testTop5SlowQueries() throws Exception {
        ChatTestResult result = sendChatRequest("Show me top 5 slow queries");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Top 5 slow queries");

        printTestResult("Top 5 slow queries", result);
    }

    @Test
    @DisplayName("SlowQuery: Query performance health - should return health status")
    void testQueryPerformanceHealth() throws Exception {
        ChatTestResult result = sendChatRequest("What is my query performance health?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Query performance health");

        printTestResult("Query performance health", result);
    }

    // ==================== INDEX RECOMMENDATION QUESTIONS (FAST-PATH) ====================

    @Test
    @DisplayName("Index: What indexes should I add? - should return recommendations")
    void testIndexRecommendations() throws Exception {
        ChatTestResult result = sendChatRequest("What indexes should I add?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Index recommendations");
        // Should mention index or recommendations
        assertTrue(
            result.response.getMessage().toLowerCase().contains("index") ||
            result.response.getMessage().toLowerCase().contains("recommend"),
            "Response should mention indexes or recommendations");

        printTestResult("What indexes should I add?", result);
    }

    @Test
    @DisplayName("Index: Missing indexes - should list missing indexes")
    void testMissingIndexes() throws Exception {
        ChatTestResult result = sendChatRequest("Are there any missing indexes?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Missing indexes");

        printTestResult("Missing indexes", result);
    }

    // ==================== WORKLOAD QUESTIONS (FAST-PATH) ====================

    @Test
    @DisplayName("Workload: What is my workload type? - should return OLTP/OLAP/etc")
    void testWorkloadType() throws Exception {
        ChatTestResult result = sendChatRequest("What is my workload type?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "Workload type");

        printTestResult("What is my workload type?", result);

        // Check if workload type detected
        String reply = result.response.getMessage().toLowerCase();
        if (reply.contains("oltp") || reply.contains("olap") || reply.contains("mixed") ||
            reply.contains("read") || reply.contains("write")) {
            System.out.println(">>> Workload type detected in response");
        }
    }

    @Test
    @DisplayName("Workload: Is this OLTP or OLAP? - should classify workload")
    void testOltpOrOlap() throws Exception {
        ChatTestResult result = sendChatRequest("Is this database OLTP or OLAP?");

        assertSuccessResponse(result);
        assertFastPathPerformance(result, "OLTP or OLAP");

        printTestResult("Is this OLTP or OLAP?", result);
    }

    // ==================== LLM-ROUTED QUESTIONS ====================

    @Test
    @DisplayName("LLM: Database version - should route to LLM for SQL execution")
    void testDatabaseVersion() throws Exception {
        ChatTestResult result = sendChatRequest("What is the database version?");

        assertSuccessResponse(result);
        assertTrue(result.durationMs < LLM_THRESHOLD_MS,
                "LLM question should complete in <" + LLM_THRESHOLD_MS + "ms, took " + result.durationMs + "ms");

        printTestResult("Database version", result);
    }

    @Test
    @DisplayName("LLM: Indexes on specific table - should route to LLM")
    void testIndexesOnTable() throws Exception {
        ChatTestResult result = sendChatRequest("Show me all indexes on the users table");

        assertSuccessResponse(result);
        assertTrue(result.durationMs < LLM_THRESHOLD_MS,
                "LLM question should complete in <" + LLM_THRESHOLD_MS + "ms, took " + result.durationMs + "ms");

        printTestResult("Indexes on users table", result);
    }

    @Test
    @DisplayName("LLM: Tables created last week - should route to LLM (temporal)")
    void testTablesCreatedLastWeek() throws Exception {
        ChatTestResult result = sendChatRequest("How many tables were created last week?");

        assertSuccessResponse(result);
        assertTrue(result.durationMs < LLM_THRESHOLD_MS,
                "LLM question should complete in <" + LLM_THRESHOLD_MS + "ms, took " + result.durationMs + "ms");

        printTestResult("Tables created last week", result);
    }

    @Test
    @DisplayName("LLM: Write a query - should generate SQL or return results")
    void testWriteQuery() throws Exception {
        ChatTestResult result = sendChatRequest("Write a query to count rows in the users table");

        assertSuccessResponse(result);
        assertTrue(result.durationMs < LLM_THRESHOLD_MS,
                "LLM question should complete in <" + LLM_THRESHOLD_MS + "ms, took " + result.durationMs + "ms");

        // Should contain SQL or query results (system auto-executes queries)
        String msg = result.response.getMessage().toLowerCase();
        boolean hasSql = msg.contains("select") || msg.contains("```sql") || msg.contains("count");
        boolean hasResults = msg.contains("row") || msg.contains("result") || msg.matches(".*\\d+.*");
        boolean hasTableInfo = msg.contains("user") || msg.contains("table");

        assertTrue(hasSql || hasResults || hasTableInfo,
                "Response should contain SQL, results, or table information");

        printTestResult("Write a query", result);
    }

    // ==================== EDGE CASES AND ERROR HANDLING ====================

    @Test
    @DisplayName("Edge: Empty message - should return error")
    void testEmptyMessage() throws Exception {
        String connectionId = requireTestConnectionId("chat prompt integration tests");
        ChatRequest request = new ChatRequest();
        request.setConnectionId(connectionId);
        request.setMessage("");

        mockMvc.perform(post(apiPath("/chat"))
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Edge: Missing connection ID - should return error")
    void testMissingConnectionId() throws Exception {
        ChatRequest request = new ChatRequest();
        request.setMessage("How many tables?");

        mockMvc.perform(post(apiPath("/chat"))
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("Edge: Very long question - should handle gracefully")
    void testVeryLongQuestion() throws Exception {
        String connectionId = requireTestConnectionId("chat prompt integration tests");
        String longQuestion = "How many tables " + "in the database ".repeat(100) + "?";

        ChatRequest request = new ChatRequest();
        request.setConnectionId(connectionId);
        request.setMessage(longQuestion);
        request.setChatId(UUID.randomUUID().toString());

        int status = mockMvc.perform(post(apiPath("/chat"))
                .contextPath(CONTEXT_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
                .andReturn()
                .getResponse()
                .getStatus();

        // Should either succeed (200) or return appropriate error
        assertTrue(status == 200 || status == 400 || status == 500,
                "Should handle long question gracefully, got status: " + status);
    }

    // ==================== PERFORMANCE BENCHMARK ====================

    @Test
    @DisplayName("Benchmark: Fast-path batch test - measure average response time")
    void testFastPathBatch() throws Exception {
        List<String> fastPathQuestions = Arrays.asList(
            "How many tables?",
            "What are my largest tables?",
            "Database overview",
            "What database type is this?"
        );

        // Warmup call to avoid JIT compilation overhead affecting measurements
        System.out.println("\n=== FAST-PATH BATCH BENCHMARK ===");
        System.out.println("Warmup call...");
        sendChatRequest("How many views?");

        long totalTime = 0;
        int successCount = 0;
        int fastPathCount = 0;

        System.out.println("Running benchmark:");
        for (String question : fastPathQuestions) {
            try {
                ChatTestResult result = sendChatRequest(question);
                assertTrue(result.response.isSuccess(),
                        "Question '" + question + "' should succeed");
                totalTime += result.durationMs;
                successCount++;
                if (result.durationMs < FAST_PATH_THRESHOLD_MS) {
                    fastPathCount++;
                }
                System.out.printf("  [%4dms] %s %s%n",
                        result.durationMs,
                        result.durationMs < FAST_PATH_THRESHOLD_MS ? "FAST" : "SLOW",
                        question);
            } catch (Exception e) {
                System.out.printf("  [FAILED] %s - %s%n", question, e.getMessage());
                fail("Question '" + question + "' failed: " + e.getMessage());
            }
        }

        if (successCount > 0) {
            long avgTime = totalTime / successCount;
            System.out.printf("%nResults:%n");
            System.out.printf("  Average response time: %dms (target: <%dms)%n",
                    avgTime, FAST_PATH_THRESHOLD_MS);
            System.out.printf("  Fast-path rate: %d/%d (%.0f%%)%n",
                    fastPathCount, successCount,
                    (fastPathCount * 100.0 / successCount));
            System.out.printf("  Success rate: %d/%d%n", successCount, fastPathQuestions.size());

            // Assert average time is under threshold (more realistic than every call)
            assertTrue(avgTime < FAST_PATH_THRESHOLD_MS,
                    "Average fast-path response time should be under " + FAST_PATH_THRESHOLD_MS +
                    "ms, was " + avgTime + "ms");

            // Assert at least 75% of calls were fast (allow for occasional slowness)
            double fastPathRate = (fastPathCount * 100.0 / successCount);
            assertTrue(fastPathRate >= 75,
                    "At least 75% of fast-path questions should complete under " +
                    FAST_PATH_THRESHOLD_MS + "ms, was " + fastPathRate + "%");
        }
    }

    // ==================== HELPER CLASSES ====================

    /**
     * Result wrapper for chat tests including timing info.
     */
    private static class ChatTestResult {
        final ChatResponse response;
        final long durationMs;

        ChatTestResult(ChatResponse response, long durationMs) {
            this.response = response;
            this.durationMs = durationMs;
        }
    }
}
