package com.dbaagent.service.telemetry;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Default sink when telemetry is disabled or no PostHog key is configured.
 *
 * In practice {@code send()} and {@code identify()} are never invoked with
 * data: {@code TelemetryClient.isEnabled()} short-circuits before any event
 * reaches the buffer, so the scheduled flush job has nothing to drain. The
 * methods exist only to satisfy the {@link TelemetrySink} contract. A Phase-2
 * audit endpoint that wants to see "what WOULD be sent" must populate the
 * buffer itself rather than rely on this sink.
 */
@Slf4j
public class NoOpTelemetrySink implements TelemetrySink {

    @Override
    public void send(List<TelemetryEvent> batch) {
        log.debug("NoOpTelemetrySink: would have sent {} events", batch.size());
    }

    @Override
    public void identify(UUID installId, Map<String, Object> traits) {
        log.debug("NoOpTelemetrySink: would have identified install_id={}", installId);
    }
}
