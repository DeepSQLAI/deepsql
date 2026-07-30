package com.dbaagent.service.telemetry;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Sink contract — both Phase-1 PostHogDirectSink and the future
 * relay-side PostHogSink / OtelLgtmSink implement this.
 *
 * Both methods are blocking but expected to be quick (PostHog batch API
 * returns p99 < 200 ms). Callers should not call from request-handling
 * threads — the buffered flush job is the only legitimate caller.
 */
public interface TelemetrySink {

    void send(List<TelemetryEvent> batch);

    void identify(UUID installId, Map<String, Object> traits);
}
