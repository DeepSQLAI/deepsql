package com.dbaagent.service.telemetry;

import com.dbaagent.model.InstallTelemetryIdentity;
import com.dbaagent.repository.InstallTelemetryIdentityRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.Optional;

/**
 * Public API for emitting telemetry events from anywhere in the backend.
 *
 *   capture(event, props)  — buffer an event for async flush
 *   identify(traits)        — update the install's profile traits (PostHog group)
 *
 * Opt-out is enforced here, before any work. If telemetry is disabled the
 * call is a no-op — no buffer growth, no string formatting, no sink call.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TelemetryClient {

    private final TelemetryEnvelopeBuilder envelopeBuilder;
    private final TelemetryEventBuffer buffer;
    private final TelemetrySink sink;
    private final TelemetryProperties properties;
    private final InstallTelemetryIdentityRepository repository;

    public void capture(String event, Map<String, Object> props) {
        if (!isEnabled()) return;
        Optional<TelemetryEvent> built = envelopeBuilder.build(event, props == null ? Map.of() : props);
        built.ifPresent(buffer::offer);
    }

    public void identify(Map<String, Object> traits) {
        if (!isEnabled()) return;
        Optional<InstallTelemetryIdentity> identity = repository.findById(1);
        if (identity.isEmpty()) {
            log.warn("TelemetryClient.identify: install identity not bootstrapped");
            return;
        }
        sink.identify(identity.get().getInstallId(), traits == null ? Map.of() : traits);
    }

    private boolean isEnabled() {
        return properties.isEffectivelyEnabled(
                System.getenv("DO_NOT_TRACK"),
                System.getenv("DEEPSQL_TELEMETRY_DISABLED"));
    }
}
