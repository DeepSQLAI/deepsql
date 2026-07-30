package com.dbaagent.service.telemetry;

import com.dbaagent.model.QueryExecutionOrigin;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.time.Duration;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

/**
 * Runs on the default profile, so it needs its own copy of the Azure Search exclusion —
 * see {@link TelemetryFlowIntegrationTest} for why.
 */
@SpringBootTest(properties = {
    "spring.flyway.enabled=false",
    "azure.search.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.ai.vectorstore.azure.autoconfigure.AzureVectorStoreAutoConfiguration",
    "deepsql.telemetry.posthog-project-key=phc_test_e2e",
    "deepsql.telemetry.flush-interval-ms=100",
    "deepsql.telemetry.heartbeat-interval-ms=200",
    "deepsql.telemetry.heartbeat-initial-delay-ms=100"
})
class Phase25TelemetryIntegrationTest {

    private static WireMockServer wireMock;

    @DynamicPropertySource
    static void overridePostHogEndpoint(DynamicPropertyRegistry registry) {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/batch/"))
            .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
        registry.add("deepsql.telemetry.posthog-host",
            () -> "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void resetWireMock() {
        wireMock.resetRequests();
    }

    @Autowired TelemetryCounters counters;
    @Autowired SlowQueryIngestionTelemetry ingestionTelemetry;

    @Test
    void counters_heartbeat_and_slowQueryIngestion_emitExpectedPostHogEvents() {
        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        counters.incrementQuery(QueryExecutionOrigin.CHAT, "mysql");
        counters.incrementBrainRetrieval();

        ingestionTelemetry.timeAndEmit("postgres", () -> 17);

        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/batch/"))
                .withRequestBody(containing("\"event\":\"slow_query.ingestion_run.completed\""))
                .withRequestBody(containing("\"dialect\":\"postgres\""))
                .withRequestBody(containing("\"rows_ingested\":17"))
                .withRequestBody(containing("\"success\":true")));
        });

        Awaitility.await().atMost(Duration.ofSeconds(8)).untilAsserted(() -> {
            wireMock.verify(postRequestedFor(urlEqualTo("/batch/"))
                .withRequestBody(containing("\"event\":\"install.heartbeat\""))
                .withRequestBody(containing("queries_interval_total"))
                .withRequestBody(containing("queries_interval_source_ui"))
                .withRequestBody(containing("brain_retrievals_interval_total"))
                .withRequestBody(containing("slow_query_ingestion_runs_interval")));
        });
    }
}
