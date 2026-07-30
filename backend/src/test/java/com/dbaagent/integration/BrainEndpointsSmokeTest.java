package com.dbaagent.integration;

import com.dbaagent.model.brain.BrainNoteRequest;
import com.dbaagent.model.brain.BrainTaskRequest;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.model.SchemaMetadata;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;

/**
 * Smoke test for all BrainController endpoints.
 * Asserts that none of the endpoints return HTTP 500 for the test connection.
 *
 * Split into batched test methods (~10 requests each) to reduce peak memory
 * pressure and allow GC between groups.
 */
@DisplayName("Brain Endpoints Smoke Test")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class BrainEndpointsSmokeTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SchemaScannerService schemaScannerService;

    private String connectionId;
    private String tableName = "users";
    private String columnName = "id";
    private String noteId = "missing-note";
    private String taskId = "missing-task";
    private String simulationId = "missing-simulation";
    private String patternId = "missing-pattern";
    private String experimentId = "missing-experiment";

    @BeforeAll
    void init() {
        connectionId = getTestConnectionId();
        try {
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            if (schema != null && schema.getTables() != null && !schema.getTables().isEmpty()) {
                tableName = schema.getTables().get(0).getName();
                if (schema.getTables().get(0).getColumns() != null &&
                    !schema.getTables().get(0).getColumns().isEmpty()) {
                    columnName = schema.getTables().get(0).getColumns().get(0).getName();
                }
            }
        } catch (Exception ignored) {
            // Fall back to defaults if schema scan fails
        }

        // Create seed data for subsequent tests
        try { noteId = createNote(); } catch (Exception ignored) {}
        try { taskId = createTask(); } catch (Exception ignored) {}
        try { simulationId = runScalabilitySimulation(); } catch (Exception ignored) {}
        try { patternId = fetchPatternId(); } catch (Exception ignored) {}
        try { experimentId = startExperiment(); } catch (Exception ignored) {}
    }

    @Test
    @Order(1)
    @DisplayName("Batch 1: Core understanding, notes, tasks, key columns")
    void batch1_coreAndNotesAndTasks() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, get("/brain/understanding/{id}", connectionId), "GET /brain/understanding");
        recordIf500(failures, post("/brain/profile/{id}", connectionId), "POST /brain/profile");
        recordIf500(failures, post("/brain/ambiguity")
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(Map.of(
                "connectionId", connectionId,
                "columnName", columnName,
                "preferredTable", tableName
            ))), "POST /brain/ambiguity");
        recordIf500(failures, post("/brain/schema/rescan/{id}", connectionId), "POST /brain/schema/rescan");

        recordIf500(failures, get("/brain/notes/{id}", connectionId), "GET /brain/notes");
        if (!"missing-id".equals(noteId)) {
            recordIf500(failures, post("/brain/notes/{id}", noteId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new BrainNoteRequest(
                    connectionId, "TABLE", tableName, null, "updated note", "test"
                ))), "POST /brain/notes/{noteId}");
            recordIf500(failures, post("/brain/notes/{id}/delete", noteId), "POST /brain/notes/{noteId}/delete");
        }

        recordIf500(failures, get("/brain/tasks/{id}", connectionId), "GET /brain/tasks");
        if (!"missing-task".equals(taskId)) {
            recordIf500(failures, post("/brain/tasks/{id}/status", taskId)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"status\":\"DONE\"}"), "POST /brain/tasks/{taskId}/status");
        }

        recordIf500(failures, get("/brain/key-columns/{id}", connectionId), "GET /brain/key-columns");
        recordIf500(failures, post("/brain/key-columns/analyze/{id}", connectionId), "POST /brain/key-columns/analyze");
        recordIf500(failures, post("/brain/key-columns/anti-pattern/{id}/acknowledge", "test-pattern"),
            "POST /brain/key-columns/anti-pattern/{patternId}/acknowledge");
        recordIf500(failures, post("/brain/key-columns/cleanup/{id}", connectionId), "POST /brain/key-columns/cleanup");

        assertNoFailures(failures, "Batch 1");
    }

    @Test
    @Order(2)
    @DisplayName("Batch 2: Schema classification, relationships, anti-patterns")
    void batch2_schemaAndRelationships() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, get("/brain/schema-classification/{id}", connectionId), "GET /brain/schema-classification");
        recordIf500(failures, post("/brain/schema-classification/analyze/{id}", connectionId),
            "POST /brain/schema-classification/analyze");
        recordIf500(failures, get("/brain/schema-classification/tables/{id}", connectionId),
            "GET /brain/schema-classification/tables");
        recordIf500(failures, get("/brain/schema-classification/fact-tables/{id}", connectionId),
            "GET /brain/schema-classification/fact-tables");
        recordIf500(failures, get("/brain/schema-classification/dimensions/{id}", connectionId),
            "GET /brain/schema-classification/dimensions");

        recordIf500(failures, get("/brain/inferred-relationships/{id}", connectionId),
            "GET /brain/inferred-relationships");
        recordIf500(failures, post("/brain/inferred-relationships/analyze/{id}", connectionId),
            "POST /brain/inferred-relationships/analyze");
        recordIf500(failures, post("/brain/inferred-relationships/{id}/validate", "missing-id")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\":\"VALIDATED\"}"),
            "POST /brain/inferred-relationships/{id}/validate");
        recordIf500(failures, get("/brain/query-anti-patterns/{id}", connectionId),
            "GET /brain/query-anti-patterns");
        recordIf500(failures, post("/brain/query-anti-patterns/analyze/{id}", connectionId),
            "POST /brain/query-anti-patterns/analyze");
        recordIf500(failures, get("/brain/query-anti-patterns/by-severity/{id}/{severity}", connectionId, "HIGH"),
            "GET /brain/query-anti-patterns/by-severity");

        assertNoFailures(failures, "Batch 2");
    }

    @Test
    @Order(3)
    @DisplayName("Batch 3: Scalability, scores, enhanced classifications")
    void batch3_scalabilityAndScores() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, get("/brain/scalability/{id}", connectionId), "GET /brain/scalability");
        recordIf500(failures, get("/brain/scalability/latest/{id}", connectionId), "GET /brain/scalability/latest");
        recordIf500(failures, post("/brain/scalability/simulate/{id}", connectionId), "POST /brain/scalability/simulate");
        if (!"missing-id".equals(simulationId)) {
            recordIf500(failures, get("/brain/scalability/predictions/{id}", simulationId),
                "GET /brain/scalability/predictions");
        }
        recordIf500(failures, get("/brain/scalability/high-risk/{id}", connectionId),
            "GET /brain/scalability/high-risk");
        recordIf500(failures, get("/brain/score/latest/{id}", connectionId), "GET /brain/score/latest");
        recordIf500(failures, get("/brain/score/history/{id}", connectionId), "GET /brain/score/history");
        recordIf500(failures, post("/brain/score/calculate/{id}", connectionId), "POST /brain/score/calculate");

        recordIf500(failures, get("/brain/access-patterns/{id}", connectionId), "GET /brain/access-patterns");
        recordIf500(failures, get("/brain/table-anti-patterns/{id}", connectionId), "GET /brain/table-anti-patterns");
        recordIf500(failures, get("/brain/temporal-classification/{id}", connectionId),
            "GET /brain/temporal-classification");
        recordIf500(failures, get("/brain/health-scores/{id}", connectionId), "GET /brain/health-scores");

        assertNoFailures(failures, "Batch 3");
    }

    @Test
    @Order(4)
    @DisplayName("Batch 4: Business domains, sensitivity, partitioning, relationships")
    void batch4_classificationsExtended() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, get("/brain/business-domains/{id}", connectionId), "GET /brain/business-domains");
        recordIf500(failures, get("/brain/data-sensitivity/{id}", connectionId), "GET /brain/data-sensitivity");
        recordIf500(failures, get("/brain/partition-readiness/{id}", connectionId), "GET /brain/partition-readiness");
        recordIf500(failures, get("/brain/relationship-classification/{id}", connectionId),
            "GET /brain/relationship-classification");
        recordIf500(failures, get("/brain/relationship-classification/integrity-issues/{id}", connectionId),
            "GET /brain/relationship-classification/integrity-issues");
        recordIf500(failures, get("/brain/relationship-classification/missing-indexes/{id}", connectionId),
            "GET /brain/relationship-classification/missing-indexes");
        recordIf500(failures, get("/brain/schema-classification/tables-by-sensitivity/{id}/{level}", connectionId, "HIGH"),
            "GET /brain/schema-classification/tables-by-sensitivity");
        recordIf500(failures, get("/brain/schema-classification/tables-by-domain/{id}/{domain}", connectionId, "CORE"),
            "GET /brain/schema-classification/tables-by-domain");
        recordIf500(failures, get("/brain/schema-classification/partition-candidates/{id}", connectionId),
            "GET /brain/schema-classification/partition-candidates");
        recordIf500(failures, get("/brain/schema-classification/tables-with-anti-patterns/{id}", connectionId),
            "GET /brain/schema-classification/tables-with-anti-patterns");
        recordIf500(failures, get("/brain/schema-classification/low-health-tables/{id}", connectionId),
            "GET /brain/schema-classification/low-health-tables");

        assertNoFailures(failures, "Batch 4");
    }

    @Test
    @Order(5)
    @DisplayName("Batch 5: Phase 6 classifications and filtered endpoints")
    void batch5_phase6AndFiltered() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, get("/brain/data-lifecycle/{id}", connectionId), "GET /brain/data-lifecycle");
        recordIf500(failures, get("/brain/cache-affinity/{id}", connectionId), "GET /brain/cache-affinity");
        recordIf500(failures, get("/brain/query-complexity/{id}", connectionId), "GET /brain/query-complexity");
        recordIf500(failures, get("/brain/schema-evolution-risk/{id}", connectionId),
            "GET /brain/schema-evolution-risk");
        recordIf500(failures, get("/brain/denormalization-candidates/{id}", connectionId),
            "GET /brain/denormalization-candidates");
        recordIf500(failures, get("/brain/cost-attribution/{id}", connectionId), "GET /brain/cost-attribution");
        recordIf500(failures, get("/brain/sharding-readiness/{id}", connectionId), "GET /brain/sharding-readiness");
        recordIf500(failures, get("/brain/data-quality/{id}", connectionId), "GET /brain/data-quality");
        recordIf500(failures, get("/brain/dependency-criticality/{id}", connectionId),
            "GET /brain/dependency-criticality");
        recordIf500(failures, get("/brain/growth-prediction/{id}", connectionId), "GET /brain/growth-prediction");

        recordIf500(failures, get("/brain/schema-classification/tables-by-lifecycle/{id}/{lifecycle}", connectionId, "ACTIVE"),
            "GET /brain/schema-classification/tables-by-lifecycle");
        recordIf500(failures, get("/brain/schema-classification/high-cache-value/{id}", connectionId),
            "GET /brain/schema-classification/high-cache-value");
        recordIf500(failures, get("/brain/schema-classification/schema-risk/{id}/{level}", connectionId, "HIGH"),
            "GET /brain/schema-classification/schema-risk");
        recordIf500(failures, get("/brain/schema-classification/denormalization-candidates/{id}", connectionId),
            "GET /brain/schema-classification/denormalization-candidates");
        recordIf500(failures, get("/brain/schema-classification/sharding-ready/{id}", connectionId),
            "GET /brain/schema-classification/sharding-ready");
        recordIf500(failures, get("/brain/schema-classification/low-quality/{id}", connectionId),
            "GET /brain/schema-classification/low-quality");
        recordIf500(failures, get("/brain/schema-classification/high-dependency-criticality/{id}", connectionId),
            "GET /brain/schema-classification/high-dependency-criticality");
        recordIf500(failures, get("/brain/schema-classification/rapid-growth/{id}", connectionId),
            "GET /brain/schema-classification/rapid-growth");
        recordIf500(failures, get("/brain/schema-classification/cost-tier/{id}/{tier}", connectionId, "MEDIUM"),
            "GET /brain/schema-classification/cost-tier");

        assertNoFailures(failures, "Batch 5");
    }

    @Test
    @Order(6)
    @DisplayName("Batch 6: Column values, workload intelligence")
    void batch6_columnValuesAndWorkload() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, get("/brain/column-values/{id}", connectionId), "GET /brain/column-values");
        recordIf500(failures, get("/brain/column-values/{id}/stats", connectionId),
            "GET /brain/column-values/stats");
        recordIf500(failures, post("/brain/column-values/{id}/refresh", connectionId),
            "POST /brain/column-values/refresh");
        recordIf500(failures, post("/brain/column-values/embed-all"),
            "POST /brain/column-values/embed-all");

        recordIf500(failures, post("/brain/workload/collect/{id}", connectionId), "POST /brain/workload/collect");
        recordIf500(failures, post("/brain/workload/characterize/{id}", connectionId),
            "POST /brain/workload/characterize");
        recordIf500(failures, get("/brain/workload/profile/{id}", connectionId),
            "GET /brain/workload/profile");
        recordIf500(failures, get("/brain/workload/status/{id}", connectionId),
            "GET /brain/workload/status");
        recordIf500(failures, get("/brain/workload/similar/{id}", connectionId),
            "GET /brain/workload/similar");

        assertNoFailures(failures, "Batch 6");
    }

    @Test
    @Order(7)
    @DisplayName("Batch 7: Config tuning, column statistics")
    void batch7_configAndStatistics() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, post("/brain/config/knobs/{id}", connectionId), "POST /brain/config/knobs");
        recordIf500(failures, get("/brain/config/top-knobs/{id}", connectionId), "GET /brain/config/top-knobs");
        recordIf500(failures, get("/brain/config/rankings/{id}", connectionId), "GET /brain/config/rankings");
        recordIf500(failures, post("/brain/config/recommendations/{id}", connectionId),
            "POST /brain/config/recommendations");
        recordIf500(failures, post("/brain/config/experiments/{id}", connectionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildExperimentRequest())),
            "POST /brain/config/experiments");
        if (!"missing-id".equals(experimentId)) {
            recordIf500(failures, post("/brain/config/experiments/{id}/complete", experimentId),
                "POST /brain/config/experiments/{experimentId}/complete");
        }
        recordIf500(failures, get("/brain/config/experiments/{id}", connectionId),
            "GET /brain/config/experiments");
        recordIf500(failures, get("/brain/config/experiments/{id}/success-rate", connectionId),
            "GET /brain/config/experiments/success-rate");

        recordIf500(failures, post("/brain/statistics/{id}/tables/{table}", connectionId, tableName),
            "POST /brain/statistics/{connectionId}/tables/{tableName}");
        recordIf500(failures, get("/brain/statistics/{id}", connectionId), "GET /brain/statistics");
        recordIf500(failures, get("/brain/statistics/{id}/high-cardinality", connectionId),
            "GET /brain/statistics/high-cardinality");
        recordIf500(failures, get("/brain/statistics/{id}/skewed", connectionId),
            "GET /brain/statistics/skewed");
        recordIf500(failures, post("/brain/statistics/{id}/estimate", connectionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildEstimateRequest())),
            "POST /brain/statistics/estimate");
        recordIf500(failures, post("/brain/statistics/{id}/refresh", connectionId),
            "POST /brain/statistics/refresh");
        recordIf500(failures, get("/brain/statistics/{id}/accuracy", connectionId),
            "GET /brain/statistics/accuracy");

        assertNoFailures(failures, "Batch 7");
    }

    @Test
    @Order(8)
    @DisplayName("Batch 8: Executions, patterns, ML overview, query intelligence")
    void batch8_executionsAndPatterns() throws Exception {
        List<String> failures = new ArrayList<>();

        recordIf500(failures, post("/brain/executions/{id}", connectionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildExecutionRequest())),
            "POST /brain/executions");
        recordIf500(failures, get("/brain/executions/{id}", connectionId), "GET /brain/executions");
        recordIf500(failures, get("/brain/executions/{id}/cardinality-errors", connectionId),
            "GET /brain/executions/cardinality-errors");
        recordIf500(failures, get("/brain/calibration/{id}", connectionId), "GET /brain/calibration");
        recordIf500(failures, get("/brain/executions/{id}/multiple-plans", connectionId),
            "GET /brain/executions/multiple-plans");
        recordIf500(failures, delete("/brain/calibration/{id}", connectionId), "DELETE /brain/calibration");

        recordIf500(failures, post("/brain/patterns/{id}/suggestions", connectionId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(buildQueryRequest())),
            "POST /brain/patterns/suggestions");
        recordIf500(failures, get("/brain/patterns/{id}/reliable", connectionId),
            "GET /brain/patterns/reliable");
        recordIf500(failures, get("/brain/patterns/{id}/most-used", connectionId),
            "GET /brain/patterns/most-used");
        recordIf500(failures, get("/brain/patterns/{id}/with-optimizations", connectionId),
            "GET /brain/patterns/with-optimizations");
        recordIf500(failures, get("/brain/patterns/{id}/stats", connectionId),
            "GET /brain/patterns/stats");
        if (!"missing-id".equals(patternId)) {
            recordIf500(failures, post("/brain/patterns/{id}/feedback", patternId).param("wasSuccessful", "true"),
                "POST /brain/patterns/feedback");
        }
        recordIf500(failures, post("/brain/patterns/{id}/cleanup", connectionId),
            "POST /brain/patterns/cleanup");

        recordIf500(failures, get("/brain/ml-overview/{id}", connectionId), "GET /brain/ml-overview");
        recordIf500(failures, delete("/brain/query-intelligence/{id}/executions", connectionId),
            "DELETE /brain/query-intelligence/executions");
        recordIf500(failures, post("/brain/query-intelligence/{id}/backfill", connectionId),
            "POST /brain/query-intelligence/backfill");
        recordIf500(failures, post("/brain/query-intelligence/{id}/explain-backfill", connectionId),
            "POST /brain/query-intelligence/explain-backfill");
        recordIf500(failures, get("/brain/query-intelligence/{id}/explain-progress", connectionId),
            "GET /brain/query-intelligence/explain-progress");
        recordIf500(failures, post("/brain/query-intelligence/{id}/explain-cancel", connectionId),
            "POST /brain/query-intelligence/explain-cancel");

        assertNoFailures(failures, "Batch 8");
    }

    // ==================== HELPERS ====================

    private void assertNoFailures(List<String> failures, String batchName) {
        if (!failures.isEmpty()) {
            throw new AssertionError(batchName + " - Brain endpoints returned 500:\n" + String.join("\n", failures));
        }
    }

    private void recordIf500(
        List<String> failures,
        MockHttpServletRequestBuilder request,
        String label
    ) throws Exception {
        MvcResult result = mockMvc.perform(request).andReturn();
        int status = result.getResponse().getStatus();
        if (status == 500) {
            String body = result.getResponse().getContentAsString();
            failures.add(label + " -> 500" + (body == null || body.isBlank() ? "" : (": " + body)));
        }
    }

    private String createNote() throws Exception {
        BrainNoteRequest request = new BrainNoteRequest(connectionId, "TABLE", tableName, null, "test note", "test");
        MvcResult result = mockMvc.perform(post("/brain/notes")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andReturn();
        return extractId(result);
    }

    private String createTask() throws Exception {
        BrainTaskRequest request = BrainTaskRequest.builder()
            .connectionId(connectionId)
            .taskType("BCNF_REVIEW")
            .tableName(tableName)
            .tableSchema(null)
            .tableLabel(tableName)
            .bcnfScore(85)
            .issues(java.util.List.of("Normalization check"))
            .suggestions(java.util.List.of("Review composite keys"))
            .createdBy("test")
            .build();
        MvcResult result = mockMvc.perform(post("/brain/tasks")
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
            .andReturn();
        return extractId(result);
    }

    private String runScalabilitySimulation() throws Exception {
        MvcResult result = mockMvc.perform(post("/brain/scalability/simulate/{id}", connectionId))
            .andReturn();
        return extractFirstId(result);
    }

    private String fetchPatternId() throws Exception {
        MvcResult result = mockMvc.perform(get("/brain/patterns/{id}/reliable", connectionId))
            .andReturn();
        return extractFirstId(result);
    }

    private String startExperiment() throws Exception {
        MvcResult result = mockMvc.perform(post("/brain/config/experiments/{id}", connectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(buildExperimentRequest())))
            .andReturn();
        return extractId(result);
    }

    private Map<String, Object> buildExperimentRequest() {
        Map<String, Map<String, String>> knobChanges = new HashMap<>();
        knobChanges.put("max_connections", Map.of("old", "100", "new", "120"));
        Map<String, Object> request = new HashMap<>();
        request.put("knobChanges", knobChanges);
        request.put("recommendationId", "test-recommendation");
        return request;
    }

    private Map<String, Object> buildEstimateRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("tableName", tableName);
        request.put("columnName", columnName);
        request.put("value", "1");
        return request;
    }

    private Map<String, Object> buildExecutionRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("query", "SELECT * FROM " + tableName + " LIMIT 1");
        request.put("actualExecutionMs", 12.5);
        request.put("actualRows", 1);
        return request;
    }

    private Map<String, Object> buildQueryRequest() {
        Map<String, Object> request = new HashMap<>();
        request.put("query", "SELECT * FROM " + tableName + " LIMIT 10");
        return request;
    }

    private String extractId(MvcResult result) {
        try {
            JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
            JsonNode id = node.get("id");
            if (id != null && !id.isNull()) {
                return id.asText();
            }
        } catch (Exception ignored) {
        }
        return "missing-id";
    }

    private String extractFirstId(MvcResult result) {
        try {
            JsonNode node = objectMapper.readTree(result.getResponse().getContentAsString());
            if (node.isArray() && node.size() > 0 && node.get(0).get("id") != null) {
                return node.get(0).get("id").asText();
            }
        } catch (Exception ignored) {
        }
        return "missing-id";
    }
}
