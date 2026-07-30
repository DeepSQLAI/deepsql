package com.dbaagent.service.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.*;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;

class PostHogDirectSinkTest {

    private WireMockServer wireMock;
    private PostHogDirectSink sink;

    @BeforeEach
    void setup() {
        wireMock = new WireMockServer(wireMockConfig().dynamicPort());
        wireMock.start();
        wireMock.stubFor(post(urlEqualTo("/batch/"))
                .willReturn(aResponse().withStatus(200).withBody("{\"status\":\"ok\"}")));
        sink = new PostHogDirectSink(new ObjectMapper(),
                "phc_test_key", "http://localhost:" + wireMock.port());
    }

    @AfterEach
    void teardown() {
        wireMock.stop();
    }

    @Test
    void postsBatchEventsToPostHogBatchEndpoint() {
        TelemetryEnvelope envelope = TelemetryEnvelope.builder()
                .installId(UUID.randomUUID())
                .installVersion("1.0.5")
                .source("backend")
                .companyName("acme.com")
                .build();
        TelemetryEvent event = TelemetryEvent.builder()
                .event("connection.created")
                .ts(OffsetDateTime.now())
                .envelope(envelope)
                .properties(Map.of("db_dialect", "postgres"))
                .build();

        sink.send(List.of(event));

        wireMock.verify(postRequestedFor(urlEqualTo("/batch/"))
                .withRequestBody(containing("\"event\":\"connection.created\""))
                .withRequestBody(containing("\"api_key\":\"phc_test_key\""))
                // company_name flattened to top-level PostHog property — what
                // dashboards filter on.
                .withRequestBody(containing("\"company_name\":\"acme.com\""))
                // and promoted to a Person-level trait via $set so the Person
                // properties view and person.* filters see it.
                .withRequestBody(matching("(?s).*\"\\$set\".*\"company_name\":\"acme.com\".*")));
    }

    @Test
    void identifyPostsToBatchWithGroupIdentify() {
        UUID installId = UUID.randomUUID();
        sink.identify(installId, Map.of("hostname", "host-1", "region", "us-east-1"));

        wireMock.verify(postRequestedFor(urlEqualTo("/batch/"))
                .withRequestBody(containing("\"event\":\"$groupidentify\""))
                .withRequestBody(containing("\"$group_type\":\"install\""))
                .withRequestBody(containing("\"$group_key\":\"" + installId + "\""))
                .withRequestBody(containing("\"hostname\":\"host-1\""))
                .withRequestBody(containing("\"region\":\"us-east-1\"")));
    }

    @Test
    void identifyOmitsApiKeyFromInnerBatchItem() {
        UUID installId = UUID.randomUUID();
        sink.identify(installId, Map.of("hostname", "host-1"));

        // api_key must appear exactly once — in the envelope, not in the inner event.
        wireMock.verify(postRequestedFor(urlEqualTo("/batch/"))
                .withRequestBody(matching("(?s)^(?!.*\"api_key\".*\"api_key\").*$")));
    }
}
