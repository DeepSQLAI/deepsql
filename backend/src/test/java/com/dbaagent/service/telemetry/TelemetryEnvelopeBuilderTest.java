package com.dbaagent.service.telemetry;

import com.dbaagent.model.InstallTelemetryIdentity;
import com.dbaagent.repository.InstallTelemetryIdentityRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class TelemetryEnvelopeBuilderTest {

    @Mock private InstallTelemetryIdentityRepository repository;
    @Mock private EventSchemaRegistry schema;

    private MeterRegistry meters;
    private TelemetryEnvelopeBuilder builder;

    @BeforeEach
    void setup() {
        meters = new SimpleMeterRegistry();
        InstallTelemetryIdentity identity = InstallTelemetryIdentity.builder()
                .id(1)
                .installId(UUID.fromString("00000000-0000-0000-0000-000000000001"))
                .installSecret(new byte[]{1, 2, 3})
                .installToken("dt_live_abc")
                .companyName("acme.com")
                .build();
        lenient().when(repository.findById(1)).thenReturn(Optional.of(identity));
        builder = new TelemetryEnvelopeBuilder(repository, schema, meters, "1.0.5", "ga", false);
    }

    @Test
    void stripsDenylistedKeysAndIncrementsCounter() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));
        Map<String, Object> props = new HashMap<>();
        props.put("db_dialect", "postgres");
        props.put("sql", "SELECT * FROM users");
        props.put("tableName", "customers");
        props.put("ssh_enabled", true);

        TelemetryEvent event = builder.build("connection.created", props).orElseThrow();

        assertEquals(2, event.properties().size());
        assertTrue(event.properties().containsKey("db_dialect"));
        assertTrue(event.properties().containsKey("ssh_enabled"));
        // Both dropped keys (sql, tableName) hit the DENYLIST → reason=denylist.
        assertEquals(2.0, meters.counter("telemetry.redaction.dropped",
                "event", "connection.created", "reason", "denylist").count());
    }

    @Test
    void stripsOversizeStrings() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));
        Map<String, Object> props = Map.of(
                "db_dialect", "postgres",
                "huge", "X".repeat(257));

        TelemetryEvent event = builder.build("connection.created", props).orElseThrow();

        assertEquals(1, event.properties().size());
        assertFalse(event.properties().containsKey("huge"));
    }

    @Test
    void dropsUnknownEventEntirely() {
        lenient().when(schema.validate(eq("totally.fake"), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(false, java.util.List.of()));

        Optional<TelemetryEvent> result = builder.build("totally.fake", Map.of());

        assertTrue(result.isEmpty());
    }

    @Test
    void dropsSchemaRejectedKeys() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true,
                        java.util.List.of("bogus_key")));
        Map<String, Object> props = Map.of("db_dialect", "postgres", "bogus_key", "x");

        TelemetryEvent event = builder.build("connection.created", props).orElseThrow();

        assertFalse(event.properties().containsKey("bogus_key"));
    }

    @Test
    void testModeOverridesReleaseToTest() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));
        TelemetryEnvelopeBuilder testBuilder = new TelemetryEnvelopeBuilder(
                repository, schema, meters, "1.0.5", "ga", true);

        TelemetryEvent event = testBuilder.build("connection.created", Map.of()).orElseThrow();

        // test-mode wins over the configured release — even if someone forgets
        // to set DEEPSQL_RELEASE in CI, events land tagged "test".
        assertEquals("test", event.envelope().installRelease());
    }

    @Test
    void normalizesUnrecognizedReleaseToUnknown() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));
        TelemetryEnvelopeBuilder weirdBuilder = new TelemetryEnvelopeBuilder(
                repository, schema, meters, "1.0.5", "production-v2", false);

        TelemetryEvent event = weirdBuilder.build("connection.created", Map.of()).orElseThrow();

        // Unrecognized tags become "unknown" rather than silently mixing
        // with valid traffic in PostHog dashboards.
        assertEquals("unknown", event.envelope().installRelease());
    }

    @Test
    void normalizesNullReleaseToUnknown() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));
        TelemetryEnvelopeBuilder nullBuilder = new TelemetryEnvelopeBuilder(
                repository, schema, meters, "1.0.5", null, false);

        TelemetryEvent event = nullBuilder.build("connection.created", Map.of()).orElseThrow();

        assertEquals("unknown", event.envelope().installRelease());
    }

    @Test
    void lowerCasesValidRelease() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));
        TelemetryEnvelopeBuilder upperBuilder = new TelemetryEnvelopeBuilder(
                repository, schema, meters, "1.0.5", "  GA  ", false);

        TelemetryEvent event = upperBuilder.build("connection.created", Map.of()).orElseThrow();

        assertEquals("ga", event.envelope().installRelease());
    }

    @Test
    void stampsCompanyNameFromInstallIdentity() {
        lenient().when(schema.validate(any(), any()))
                .thenReturn(new EventSchemaRegistry.ValidationResult(true, java.util.List.of()));

        TelemetryEvent event = builder.build("connection.created", Map.of()).orElseThrow();

        // Identity row in @BeforeEach was seeded with companyName="acme.com" —
        // every event picks it up via the envelope and lands in PostHog as a
        // top-level property after PostHogDirectSink flattening.
        assertEquals("acme.com", event.envelope().companyName());
    }
}
