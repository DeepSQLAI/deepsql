package com.dbaagent;

import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.StatsCollectorService;
import com.dbaagent.service.VisualizationService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

import java.util.Collections;

import org.springframework.test.annotation.DirtiesContext;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ApiSmokeTest {
    private static final String CONTEXT_PATH = "/api";

    @Autowired
    private WebApplicationContext context;

    private MockMvc mockMvc;

    @MockitoBean
    private CredentialService credentialService;

    @MockitoBean
    private ConnectionService connectionService;

    @MockitoBean
    private SchemaScannerService schemaScannerService;

    @MockitoBean
    private VisualizationService visualizationService;

    @MockitoBean
    private StatsCollectorService statsCollectorService;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context).build();
    }

    @Test
    void listConnectionsReturnsOk() throws Exception {
        when(credentialService.getAllConnections()).thenReturn(Collections.emptyList());

        mockMvc.perform(get("/api/connections").contextPath(CONTEXT_PATH))
            .andExpect(status().isOk());
    }

    @Test
    void schemaReturnsNotFoundWhenMissingConnection() throws Exception {
        when(credentialService.connectionExists("missing")).thenReturn(false);

        mockMvc.perform(get("/api/connections/missing/schema").contextPath(CONTEXT_PATH))
            .andExpect(status().isNotFound());
    }

    @Test
    void statsReturnsNotFoundWhenMissingConnection() throws Exception {
        when(credentialService.connectionExists("missing")).thenReturn(false);

        mockMvc.perform(get("/api/connections/missing/stats").contextPath(CONTEXT_PATH))
            .andExpect(status().isNotFound());
    }
}
