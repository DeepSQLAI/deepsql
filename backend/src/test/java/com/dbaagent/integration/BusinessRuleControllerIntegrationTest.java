package com.dbaagent.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@DisplayName("Business Rule Controller Integration Test")
@TestPropertySource(properties = {
    "spring.task.scheduling.enabled=false",
    "azure.search.enabled=false"
})
class BusinessRuleControllerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Test
    @DisplayName("Learns and resolves connection-scoped SQL guardrails")
    void learnsAndResolvesGuardrailsForConfiguredConnection() throws Exception {
        String question = "how much subscription revenue we collected for all of 2025 for united states account";
        String teaching = "For subscription revenue use ACCOUNTS and ACCOUNTS_LEDGER, join on group_id, type=CREDIT and mode=SUBSCRIPTION instead of CUSTOMERS and PRODUCT_PRICING";

        String learnPayload = objectMapper.writeValueAsString(Map.of(
            "text", teaching,
            "createdBy", "integration-test"
        ));

        mockMvc.perform(post("/business-rules/connection/{connectionId}/learn", testConnectionId)
                .contentType(MediaType.APPLICATION_JSON)
                .content(learnPayload))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connectionId").value(testConnectionId))
            .andExpect(jsonPath("$.learnedCount").isNumber())
            .andExpect(jsonPath("$.activeRuleCount").isNumber());

        MvcResult result = mockMvc.perform(get("/business-rules/connection/{connectionId}", testConnectionId)
                .param("question", question))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.connectionId").value(testConnectionId))
            .andExpect(jsonPath("$.applicableGuardrailCount").isNumber())
            .andExpect(jsonPath("$.activeRuleCount").isNumber())
            .andReturn();

        String body = result.getResponse().getContentAsString().toLowerCase();
        assertTrue(body.contains("accounts"));
        assertTrue(body.contains("accounts_ledger"));
        assertTrue(body.contains("subscription"));
    }
}
