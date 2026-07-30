package com.dbaagent.service.telemetry;

import com.dbaagent.model.InstallTelemetryIdentity;
import com.dbaagent.repository.InstallTelemetryIdentityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Builds a fully-stamped TelemetryEvent from a raw event name + properties.
 *
 * Mechanical redaction (see spec §7):
 *   1. Drops any property key in DENYLIST (defense against accidental leaks).
 *   2. Drops any property value that is a string longer than 256 chars.
 *   3. Drops any property key not in the event's published schema.
 *   4. Drops the event entirely if the event name is not in the schema.
 *
 * Each drop increments a `telemetry.redaction.dropped` counter so we can
 * surface the rate in the Schema Drift dashboard.
 */
@Component
@Slf4j
public class TelemetryEnvelopeBuilder {

    private static final Set<String> DENYLIST = Set.of(
            "sql", "sqltext", "query", "prompt", "response",
            "tablename", "columnname", "errormessage", "stacktrace",
            "email", "password", "host", "dbname");
    private static final int MAX_STRING_LENGTH = 256;

    /**
     * Allowed release tags. Anything outside this set is normalized to
     * "unknown" so PostHog dashboards always have a clean enum to filter on.
     */
    private static final Set<String> ALLOWED_RELEASES = Set.of("dev", "test", "ga", "prod");

    private final InstallTelemetryIdentityRepository repository;
    private final EventSchemaRegistry schema;
    private final MeterRegistry meters;
    private final String installVersion;
    private final String installRelease;

    public TelemetryEnvelopeBuilder(
            InstallTelemetryIdentityRepository repository,
            EventSchemaRegistry schema,
            MeterRegistry meters,
            @Value("${deepsql.version:dev}") String installVersion,
            @Value("${deepsql.release:dev}") String installRelease,
            @Value("${deepsql.telemetry.test-mode:false}") boolean testMode) {
        this.repository = repository;
        this.schema = schema;
        this.meters = meters;
        this.installVersion = installVersion;
        this.installRelease = resolveRelease(installRelease, testMode);
    }

    /**
     * Resolves the release tag stamped on every event.
     *
     *   test-mode=true                → "test"  (CI, local iteration, integration tests)
     *   raw ∈ {dev, test, ga, prod}   → raw    (lower-cased)
     *   anything else (or null)       → "unknown"
     *
     * Done once at construction time — the result is immutable for the
     * lifetime of the bean. A misconfigured release tag becomes visible
     * as the "unknown" series in PostHog within the first heartbeat,
     * rather than silently mixing with valid traffic.
     */
    private static String resolveRelease(String raw, boolean testMode) {
        if (testMode) return "test";
        if (raw == null) return "unknown";
        String lower = raw.trim().toLowerCase();
        return ALLOWED_RELEASES.contains(lower) ? lower : "unknown";
    }

    public Optional<TelemetryEvent> build(String event, Map<String, Object> rawProperties) {
        EventSchemaRegistry.ValidationResult validation = schema.validate(event, rawProperties);
        if (!validation.valid()) {
            log.debug("telemetry: dropping unknown event {}", event);
            meters.counter("telemetry.redaction.dropped", "event", event, "reason", "unknown_event").increment();
            return Optional.empty();
        }

        Map<String, Object> clean = new HashMap<>();
        int droppedCount = 0;
        for (Map.Entry<String, Object> entry : rawProperties.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();

            if (DENYLIST.contains(key.toLowerCase())) {
                meters.counter("telemetry.redaction.dropped", "event", event, "reason", "denylist").increment();
                droppedCount++; continue;
            }
            if (validation.rejectedKeys().contains(key)) {
                meters.counter("telemetry.redaction.dropped", "event", event, "reason", "schema_rejected").increment();
                droppedCount++; continue;
            }
            if (value instanceof String s && s.length() > MAX_STRING_LENGTH) {
                meters.counter("telemetry.redaction.dropped", "event", event, "reason", "oversize").increment();
                droppedCount++; continue;
            }

            clean.put(key, value);
        }
        if (droppedCount > 0) {
            log.debug("telemetry: dropped {} fields from event {}", droppedCount, event);
        }

        Optional<InstallTelemetryIdentity> identity = repository.findById(1);
        if (identity.isEmpty()) {
            log.warn("telemetry: install identity not bootstrapped yet, dropping event {}", event);
            return Optional.empty();
        }

        TelemetryEnvelope envelope = TelemetryEnvelope.builder()
                .installId(identity.get().getInstallId())
                .installVersion(installVersion)
                .installRelease(installRelease)
                .companyName(identity.get().getCompanyName())
                .source("backend")
                .sourceVersion(installVersion)
                .build();

        return Optional.of(TelemetryEvent.builder()
                .event(event)
                .ts(OffsetDateTime.now())
                .envelope(envelope)
                .properties(clean)
                .build());
    }
}
