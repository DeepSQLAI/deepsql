package com.dbaagent.config;

import com.dbaagent.service.telemetry.NoOpTelemetrySink;
import com.dbaagent.service.telemetry.PostHogDirectSink;
import com.dbaagent.service.telemetry.TelemetryProperties;
import com.dbaagent.service.telemetry.TelemetrySink;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the active TelemetrySink based on opt-out gates.
 *
 * Resolution order:
 *   1. DO_NOT_TRACK env var truthy             → NoOpTelemetrySink
 *   2. DEEPSQL_TELEMETRY_DISABLED env truthy   → NoOpTelemetrySink
 *   3. Admin toggle (properties.enabled=false) → NoOpTelemetrySink
 *   4. No PostHog project key configured       → NoOpTelemetrySink (with warning)
 *   5. Otherwise                               → PostHogDirectSink
 *
 * This decision is made once at startup. Live re-wiring on admin-toggle is a
 * Phase-2 concern (requires the audit endpoint).
 */
@Configuration
@Slf4j
public class TelemetryConfig {

    @Bean
    public TelemetrySink telemetrySink(TelemetryProperties properties, ObjectMapper mapper) {
        boolean effectivelyEnabled = properties.isEffectivelyEnabled(
                System.getenv("DO_NOT_TRACK"),
                System.getenv("DEEPSQL_TELEMETRY_DISABLED"));
        if (!effectivelyEnabled) {
            log.info("Telemetry disabled via opt-out gates; using NoOpTelemetrySink");
            return new NoOpTelemetrySink();
        }
        if (properties.getPosthogProjectKey() == null || properties.getPosthogProjectKey().isEmpty()) {
            log.warn("Telemetry enabled but deepsql.telemetry.posthog-project-key not set; "
                   + "events will buffer locally and drop. Falling back to NoOpTelemetrySink.");
            return new NoOpTelemetrySink();
        }
        log.info("Telemetry enabled; using PostHogDirectSink → {}", properties.getPosthogHost());
        return new PostHogDirectSink(mapper, properties.getPosthogProjectKey(), properties.getPosthogHost());
    }
}
