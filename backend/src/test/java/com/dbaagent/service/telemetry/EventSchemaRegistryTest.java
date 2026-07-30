package com.dbaagent.service.telemetry;

import org.junit.jupiter.api.Test;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.core.io.ClassPathResource;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.assertj.core.api.Assertions.assertThat;

class EventSchemaRegistryTest {

    private final EventSchemaRegistry registry = new EventSchemaRegistry();

    @Test
    void acceptsKnownEventWithKnownProperties() {
        var result = registry.validate("connection.created",
                Map.of("db_dialect", "postgres", "ssh_enabled", true));
        assertTrue(result.valid());
        assertTrue(result.rejectedKeys().isEmpty());
    }

    @Test
    void rejectsUnknownEvent() {
        var result = registry.validate("totally.fake.event", Map.of());
        assertFalse(result.valid());
    }

    @Test
    void dropsUnknownPropertyKeys() {
        var result = registry.validate("connection.created",
                Map.of("db_dialect", "postgres", "bogus_key", "leaked"));
        assertTrue(result.valid());
        assertEquals(java.util.List.of("bogus_key"), result.rejectedKeys());
    }

    @Test
    void schemaIncludesSlowQueryIngestionRunCompleted() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
            new ClassPathResource("telemetry/events-v1.schema.json").getInputStream()
        );
        JsonNode event = root.path("events").path("slow_query.ingestion_run.completed");
        assertThat(event.isMissingNode()).as("event registered").isFalse();
        JsonNode props = event.path("properties");
        assertThat(props.has("dialect")).isTrue();
        assertThat(props.has("rows_ingested")).isTrue();
        assertThat(props.has("run_duration_ms")).isTrue();
        assertThat(props.has("success")).isTrue();
    }

    @Test
    void heartbeatGainsPhase25RollupProperties() throws Exception {
        JsonNode root = new ObjectMapper().readTree(
            new ClassPathResource("telemetry/events-v1.schema.json").getInputStream()
        );
        JsonNode props = root.path("events").path("install.heartbeat").path("properties");
        assertThat(props.has("queries_interval_total")).isTrue();
        assertThat(props.has("queries_interval_source_ui")).isTrue();
        assertThat(props.has("queries_interval_dialect_postgres")).isTrue();
        assertThat(props.has("brain_retrievals_interval_total")).isTrue();
        assertThat(props.has("slow_query_ingestion_runs_interval")).isTrue();
    }
}
