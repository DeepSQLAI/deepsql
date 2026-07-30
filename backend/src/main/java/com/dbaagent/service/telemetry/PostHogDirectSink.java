package com.dbaagent.service.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Phase-1 direct sink: POSTs to PostHog Cloud's /batch/ endpoint.
 * Phase-2 replaces this with a relay-mediated sink (see spec §8).
 *
 * PostHog API reference:
 *   https://posthog.com/docs/api/post-only-endpoints
 */
@Slf4j
public class PostHogDirectSink implements TelemetrySink {

    private static final Duration TIMEOUT = Duration.ofSeconds(10);

    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(5))
            .build();
    private final ObjectMapper mapper;
    private final String projectKey;
    private final String posthogHost;

    public PostHogDirectSink(ObjectMapper mapper, String projectKey, String posthogHost) {
        this.mapper = mapper;
        this.projectKey = projectKey;
        this.posthogHost = posthogHost;
    }

    @Override
    public void send(List<TelemetryEvent> batch) {
        if (batch.isEmpty()) return;
        if (projectKey.isEmpty()) {
            log.warn("PostHogDirectSink: no project key configured; dropping {} events", batch.size());
            return;
        }
        List<Map<String, Object>> posthogEvents = new ArrayList<>(batch.size());
        for (TelemetryEvent ev : batch) {
            posthogEvents.add(toPostHogEvent(ev));
        }
        Map<String, Object> payload = Map.of(
                "api_key", projectKey,
                "batch",   posthogEvents);
        postJson("/batch/", payload);
    }

    @Override
    public void identify(UUID installId, Map<String, Object> traits) {
        if (projectKey.isEmpty()) return;
        Map<String, Object> groupIdentify = Map.of(
                "event", "$groupidentify",
                "distinct_id", installId.toString(),
                "properties", Map.of(
                        "$group_type", "install",
                        "$group_key",  installId.toString(),
                        "$group_set",  traits));
        postJson("/batch/", Map.of("api_key", projectKey, "batch", List.of(groupIdentify)));
    }

    private Map<String, Object> toPostHogEvent(TelemetryEvent ev) {
        Map<String, Object> props = new HashMap<>(ev.properties());
        props.put("install_version", ev.envelope().installVersion());
        props.put("install_release", ev.envelope().installRelease());
        props.put("source",          ev.envelope().source());
        props.put("source_version",  ev.envelope().sourceVersion());
        if (ev.envelope().userHash() != null)    props.put("user_hash",    ev.envelope().userHash());
        if (ev.envelope().agent() != null)       props.put("agent",        ev.envelope().agent());
        if (ev.envelope().companyName() != null) props.put("company_name", ev.envelope().companyName());
        props.put("$groups", Map.of("install", ev.envelope().installId().toString()));

        // Promote install-identity fields to Person-level properties so the
        // PostHog Person view and `person.*` dashboard filters see them.
        // PostHog auto-updates the Person record from $set on every event.
        Map<String, Object> personSet = new HashMap<>();
        personSet.put("install_version", ev.envelope().installVersion());
        personSet.put("install_release", ev.envelope().installRelease());
        if (ev.envelope().companyName() != null) {
            personSet.put("company_name", ev.envelope().companyName());
        }
        props.put("$set", personSet);

        Map<String, Object> out = new HashMap<>();
        out.put("event", ev.event());
        out.put("timestamp", ev.ts().toString());
        out.put("distinct_id", ev.envelope().installId().toString());
        out.put("properties", props);
        return out;
    }

    private void postJson(String path, Object body) {
        try {
            String json = mapper.writeValueAsString(body);
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(posthogHost + path))
                    .timeout(TIMEOUT)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json))
                    .build();
            HttpResponse<String> resp = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (resp.statusCode() >= 400) {
                log.warn("PostHogDirectSink: {} returned {}: {}", path, resp.statusCode(), resp.body());
            }
        } catch (Exception e) {
            log.warn("PostHogDirectSink: failed to POST {}: {}", path, e.getMessage());
        }
    }
}
