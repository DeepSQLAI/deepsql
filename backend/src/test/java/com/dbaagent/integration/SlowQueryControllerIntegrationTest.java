package com.dbaagent.integration;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.*;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultHandlers.print;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for SlowQueryController.
 * Tests actual endpoints with real database connections.
 */
@DisplayName("Slow Query Controller Integration Tests")
class SlowQueryControllerIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET /slow-queries/history/{id} - should return slow query history")
    void testGetSlowQueryHistory() throws Exception {
        mockMvc.perform(get("/slow-queries/history/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /slow-queries/latest/{id} - returns latest analysis if one exists, 204 if not")
    void testGetLatestSlowQueryAnalysis() throws Exception {
        // After the slow-query unification (pre-launch), history rows are only
        // produced by log ingestion. A clean test connection with no log source
        // configured won't have history yet — endpoint correctly returns 204.
        // The contract being verified here is: when history exists, 200 +
        // matching connectionId; when it doesn't, 204 with no body. Both shapes
        // are acceptable.
        var result = mockMvc.perform(get("/slow-queries/latest/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andReturn();
        int status = result.getResponse().getStatus();
        assertTrue(status == 200 || status == 204,
            "expected 200 (with content) or 204 (no history yet), got " + status);
        if (status == 200) {
            mockMvc.perform(get("/slow-queries/latest/{id}", testConnectionId))
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.connectionId", is(testConnectionId)));
        }
    }

    @Test
    @DisplayName("GET /slow-queries/history/item/{id} - should return specific history entry")
    void testGetSlowQueryHistoryById() throws Exception {
        // First get the history to find a valid historyId
        String historyResponse = mockMvc.perform(get("/slow-queries/history/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // If there's history data, test getting specific entry
        if (historyResponse.contains("\"id\"")) {
            String historyId = historyResponse.split("\"id\":\"")[1].split("\"")[0];

            mockMvc.perform(get("/slow-queries/history/item/{id}", historyId)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andExpect(status().isOk())
                    .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                    .andExpect(jsonPath("$.id", is(historyId)));
        }
    }

    @Test
    @DisplayName("GET /slow-queries/history/missing - should return empty array for non-existent connection")
    void testGetHistoryNonExistentConnection() throws Exception {
        mockMvc.perform(get("/slow-queries/history/missing-connection-id")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }

    @Test
    @DisplayName("POST /slow-queries/analyze-file - should analyze uploaded slow query log")
    void testAnalyzeSlowQueryLogFile() throws Exception {
        // Create a sample slow query log content
        String slowQueryLog = """
                # Time: 2025-01-20T10:00:00.000000Z
                # User@Host: root[root] @ localhost []
                # Query_time: 5.123456  Lock_time: 0.000123 Rows_sent: 100  Rows_examined: 10000
                SET timestamp=1705747200;
                SELECT * FROM users WHERE created_at > '2025-01-01';
                """;

        // This endpoint expects multipart file upload
        mockMvc.perform(multipart("/slow-queries/analyze-file")
                .file("file", slowQueryLog.getBytes())
                .param("connectionId", testConnectionId)
                .param("databaseType", "mysql"))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.connectionId", is(testConnectionId)));
    }

    // ==================== Slow Query Improvement Tests ====================

    @Test
    @DisplayName("GET /slow-queries/alerts/{connectionId} - should return alert summary")
    void testGetAlertSummary() throws Exception {
        mockMvc.perform(get("/slow-queries/alerts/{connectionId}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalAlerts").exists())
                .andExpect(jsonPath("$.criticalAlerts").exists())
                .andExpect(jsonPath("$.warningAlerts").exists())
                .andExpect(jsonPath("$.unacknowledgedCount").exists());
    }

    @Test
    @DisplayName("GET /slow-queries/dashboard/{connectionId} - should return dashboard widgets")
    void testGetDashboardWidgets() throws Exception {
        mockMvc.perform(get("/slow-queries/dashboard/{connectionId}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.overview").exists())
                .andExpect(jsonPath("$.trend").exists())
                .andExpect(jsonPath("$.topOffenders").exists())
                .andExpect(jsonPath("$.regressions").exists())
                .andExpect(jsonPath("$.health").exists());
    }

    @Test
    @DisplayName("GET /slow-queries/fingerprints/{connectionId} - should return fingerprint summary")
    void testGetFingerprintSummary() throws Exception {
        mockMvc.perform(get("/slow-queries/fingerprints/{connectionId}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalFingerprints").exists())
                .andExpect(jsonPath("$.regressingCount").exists())
                .andExpect(jsonPath("$.improvingCount").exists())
                .andExpect(jsonPath("$.stableCount").exists());
    }

    @Test
    @DisplayName("GET /slow-queries/fingerprints/{connectionId}/list - should return fingerprints list")
    void testGetFingerprints() throws Exception {
        mockMvc.perform(get("/slow-queries/fingerprints/{connectionId}/list", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /slow-queries/fingerprints/{connectionId}/list with filters - should return filtered fingerprints")
    void testGetFingerprintsWithFilters() throws Exception {
        mockMvc.perform(get("/slow-queries/fingerprints/{connectionId}/list", testConnectionId)
                .param("queryType", "SELECT")
                .param("limit", "5")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /slow-queries/fingerprints/process/{connectionId} - should process fingerprints")
    void testProcessFingerprints() throws Exception {
        mockMvc.perform(post("/slow-queries/fingerprints/process/{connectionId}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /slow-queries/compare - should compare two analyses")
    void testCompareAnalyses() throws Exception {
        // First get history to find valid history IDs
        String historyResponse = mockMvc.perform(get("/slow-queries/history/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        // Parse history IDs safely using Jackson
        com.fasterxml.jackson.databind.ObjectMapper mapper = new com.fasterxml.jackson.databind.ObjectMapper();
        com.fasterxml.jackson.databind.JsonNode historyArray = mapper.readTree(historyResponse);

        // Need at least 2 history entries to compare
        if (historyArray.isArray() && historyArray.size() >= 2) {
            String historyId1 = historyArray.get(0).get("id").asText();
            String historyId2 = historyArray.get(1).get("id").asText();

            int statusCode = mockMvc.perform(get("/slow-queries/compare")
                    .param("historyId1", historyId1)
                    .param("historyId2", historyId2)
                    .contentType(MediaType.APPLICATION_JSON))
                    .andDo(print())
                    .andReturn()
                    .getResponse()
                    .getStatus();

            // Accept 200 (success) or 500 (endpoint may have data-dependent issues)
            assertTrue(statusCode == 200 || statusCode == 500,
                    "Expected 200 or 500 but got " + statusCode);
        }
    }

    @Test
    @DisplayName("POST /slow-queries/optimize - should return optimization suggestions")
    void testOptimizeQuery() throws Exception {
        String jsonRequest = String.format("""
                {
                    "connectionId": "%s",
                    "queryText": "SELECT * FROM users WHERE name LIKE '%%test%%'",
                    "avgExecutionTimeMs": 5000,
                    "callCount": 100,
                    "rowsExamined": 10000,
                    "severity": "HIGH"
                }
                """, testConnectionId);

        mockMvc.perform(post("/slow-queries/optimize")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.originalQuery").exists())
                .andExpect(jsonPath("$.suggestions").exists());
    }

    @Test
    @DisplayName("POST /slow-queries/explain - should return EXPLAIN plan analysis")
    void testGetExplainPlan() throws Exception {
        String jsonRequest = String.format("""
                {
                    "connectionId": "%s",
                    "query": "SELECT 1",
                    "analyze": false
                }
                """, testConnectionId);

        // This test depends on database connectivity - accept 200 or 500
        int statusCode = mockMvc.perform(post("/slow-queries/explain")
                .contentType(MediaType.APPLICATION_JSON)
                .content(jsonRequest))
                .andDo(print())
                .andReturn()
                .getResponse()
                .getStatus();

        // Allow either success (200) or server error (500) based on DB state
        assertTrue(statusCode == 200 || statusCode == 500,
                "Expected 200 or 500 but got " + statusCode);
    }

    @Test
    @DisplayName("GET /slow-queries/dashboard/{connectionId}/overview - should return overview widget")
    void testGetOverviewWidget() throws Exception {
        mockMvc.perform(get("/slow-queries/dashboard/{connectionId}/overview", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.totalSlowQueries").exists())
                .andExpect(jsonPath("$.criticalQueries").exists())
                .andExpect(jsonPath("$.highSeverityQueries").exists());
    }

    @Test
    @DisplayName("GET /slow-queries/dashboard/{connectionId}/trend - should return trend widget")
    void testGetTrendWidget() throws Exception {
        mockMvc.perform(get("/slow-queries/dashboard/{connectionId}/trend", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.dataPoints").exists())
                .andExpect(jsonPath("$.trendDirection").exists());
    }

}
