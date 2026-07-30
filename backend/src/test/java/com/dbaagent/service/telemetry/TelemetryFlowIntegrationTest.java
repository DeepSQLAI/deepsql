package com.dbaagent.service.telemetry;

import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.Map;
import java.util.concurrent.TimeUnit;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.awaitility.Awaitility.await;

/**
 * Unlike most @SpringBootTest classes here this one runs on the default profile, so
 * application-test.properties never applies and it needs its own copy of the Azure Search
 * exclusion. Without it {@code AzureVectorStoreAutoConfiguration} builds
 * {@code searchIndexClient} eagerly from the (correctly) blank
 * {@code spring.ai.vectorstore.azure.url} and fails context refresh with
 * "'endpoint' must be a valid URL" — nothing to do with telemetry.
 */
@SpringBootTest(properties = {
    "azure.search.enabled=false",
    "spring.autoconfigure.exclude="
        + "org.springframework.ai.vectorstore.azure.autoconfigure.AzureVectorStoreAutoConfiguration"
})
class TelemetryFlowIntegrationTest {

    private static WireMockServer wireMock;

    @BeforeAll
    static void startWireMock() {
        wireMock = new WireMockServer(wireMockConfig().port(0));
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/batch/"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
    }

    @AfterAll
    static void stopWireMock() {
        if (wireMock != null) wireMock.stop();
    }

    @DynamicPropertySource
    static void wireProperties(DynamicPropertyRegistry registry) {
        registry.add("deepsql.telemetry.posthog-project-key", () -> "phc_test_key");
        registry.add("deepsql.telemetry.posthog-host", () -> "http://localhost:" + wireMock.port());
        registry.add("deepsql.telemetry.flush-interval-ms", () -> "200");
        // Push heartbeat far into the future so this test only sees the explicit capture.
        registry.add("deepsql.telemetry.heartbeat-initial-delay-ms", () -> "3600000");
    }

    @Autowired private TelemetryClient telemetryClient;

    @Test
    void capturedEventReachesPostHogWithinFlushWindow() {
        telemetryClient.capture("connection.created", Map.of(
                "db_dialect", "postgres",
                "ssh_enabled", false));

        await().atMost(5, TimeUnit.SECONDS).untilAsserted(() ->
                wireMock.verify(postRequestedFor(urlEqualTo("/batch/"))
                        .withRequestBody(containing("\"event\":\"connection.created\""))));
    }
}
