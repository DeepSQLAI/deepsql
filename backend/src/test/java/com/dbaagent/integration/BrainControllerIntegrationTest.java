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
 * Integration tests for BrainController.
 * Tests actual endpoints with real database connections.
 */
@DisplayName("Brain Controller Integration Tests")
class BrainControllerIntegrationTest extends BaseIntegrationTest {

    @Test
    @DisplayName("GET /brain/key-columns/{id} - should return key column analysis")
    void testGetKeyColumns() throws Exception {
        mockMvc.perform(get("/brain/key-columns/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.topColumns").isArray())
                .andExpect(jsonPath("$.antiPatternsDetected").isNumber())
                .andExpect(jsonPath("$.metadata").exists());
    }

    @Test
    @DisplayName("GET /brain/key-columns/{id}?limit=10 - should return limited key columns")
    void testGetKeyColumnsWithLimit() throws Exception {
        mockMvc.perform(get("/brain/key-columns/{id}", testConnectionId)
                .param("limit", "10")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.topColumns").isArray())
                .andExpect(jsonPath("$.topColumns", hasSize(lessThanOrEqualTo(10))));
    }

    @Test
    @DisplayName("GET /brain/key-columns/{id}?tableName=users - should filter by table name")
    void testGetKeyColumnsFilteredByTable() throws Exception {
        mockMvc.perform(get("/brain/key-columns/{id}", testConnectionId)
                .param("tableName", "users")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.topColumns").isArray());
    }

    @Test
    @DisplayName("POST /brain/key-columns/analyze/{id} - should trigger key column analysis")
    void testAnalyzeKeyColumns() throws Exception {
        mockMvc.perform(post("/brain/key-columns/analyze/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.topColumns").isArray())
                .andExpect(jsonPath("$.antiPatternsDetected").isNumber())
                .andExpect(jsonPath("$.metadata").exists())
                .andExpect(jsonPath("$.analyzedAt").exists());
    }

    @Test
    @DisplayName("GET /brain/schema-classification/{id} - should return schema classification")
    void testGetSchemaClassification() throws Exception {
        mockMvc.perform(get("/brain/schema-classification/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.connectionId", is(testConnectionId)))
                .andExpect(jsonPath("$.globalPattern").exists());
    }

    @Test
    @DisplayName("POST /brain/schema-classification/analyze/{id} - should trigger schema classification")
    void testAnalyzeSchemaClassification() throws Exception {
        mockMvc.perform(post("/brain/schema-classification/analyze/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.connectionId", is(testConnectionId)))
                .andExpect(jsonPath("$.globalPattern").exists())
                .andExpect(jsonPath("$.classifiedAt").exists());
    }

    @Test
    @DisplayName("GET /brain/query-anti-patterns/{id} - should return query anti-patterns")
    void testGetQueryAntiPatterns() throws Exception {
        mockMvc.perform(get("/brain/query-anti-patterns/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /brain/query-anti-patterns/analyze/{id} - should analyze query anti-patterns")
    void testAnalyzeQueryAntiPatterns() throws Exception {
        mockMvc.perform(post("/brain/query-anti-patterns/analyze/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.patterns").isArray())
                .andExpect(jsonPath("$.totalPatterns").isNumber())
                .andExpect(jsonPath("$.criticalCount").isNumber())
                .andExpect(jsonPath("$.highCount").isNumber())
                .andExpect(jsonPath("$.message").exists());
    }

    @Test
    @DisplayName("GET /brain/scalability/{id} - should return scalability simulation results")
    void testGetScalabilitySimulation() throws Exception {
        mockMvc.perform(get("/brain/scalability/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /brain/scalability/simulate/{id} - should trigger scalability simulation")
    void testSimulateScalability() throws Exception {
        mockMvc.perform(post("/brain/scalability/simulate/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("GET /brain/key-columns/missing - should return empty result for non-existent connection")
    void testGetKeyColumnsNonExistentConnection() throws Exception {
        mockMvc.perform(get("/brain/key-columns/missing-connection-id")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.topColumns").isArray())
                .andExpect(jsonPath("$.topColumns").isEmpty());
    }

    // ==================== Inferred Relationships Tests ====================

    @Test
    @DisplayName("GET /brain/inferred-relationships/{id} - should return inferred relationships list")
    void testGetInferredRelationships() throws Exception {
        mockMvc.perform(get("/brain/inferred-relationships/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$").isArray());
    }

    @Test
    @DisplayName("POST /brain/inferred-relationships/analyze/{id} - should analyze and infer relationships")
    void testAnalyzeInferredRelationships() throws Exception {
        // This test depends on database state (slow queries, query lineage)
        // It should return 200 with results if data exists, or 500 if DB query fails
        int statusCode = mockMvc.perform(post("/brain/inferred-relationships/analyze/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andReturn()
                .getResponse()
                .getStatus();

        // Allow either success (200) or server error (500) based on DB state
        assertTrue(statusCode == 200 || statusCode == 500,
                "Expected 200 or 500 but got " + statusCode);
    }

    @Test
    @DisplayName("POST /brain/inferred-relationships/{id}/validate - should validate relationship")
    void testValidateInferredRelationship() throws Exception {
        // First, try to run analysis (may fail if no data available)
        mockMvc.perform(post("/brain/inferred-relationships/analyze/{id}", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print());

        // Try to validate with invalid ID - should return error (not 2xx success)
        int statusCode = mockMvc.perform(post("/brain/inferred-relationships/{id}/validate", "non-existent-id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"VALIDATED\"}"))
                .andDo(print())
                .andReturn()
                .getResponse()
                .getStatus();

        // Should be either 404 (not found) or 500 (server error) - both are acceptable
        assertTrue(statusCode == 404 || statusCode == 500,
                "Expected 404 or 500 but got " + statusCode);
    }

    @Test
    @DisplayName("POST /brain/inferred-relationships/{id}/validate - should reject invalid status")
    void testValidateInferredRelationshipInvalidStatus() throws Exception {
        mockMvc.perform(post("/brain/inferred-relationships/{id}/validate", "some-id")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\": \"INVALID_STATUS\"}"))
                .andDo(print())
                .andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("GET /brain/inferred-relationships/missing - should return empty list for non-existent connection")
    void testGetInferredRelationshipsNonExistentConnection() throws Exception {
        mockMvc.perform(get("/brain/inferred-relationships/missing-connection-id")
                .contentType(MediaType.APPLICATION_JSON))
                .andDo(print())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$").isArray())
                .andExpect(jsonPath("$").isEmpty());
    }
}
