package com.dbaagent.service.telemetry;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryEventTest {

    @Test
    void serializesEnvelopeAndPropertiesAsJson() throws Exception {
        TelemetryEnvelope envelope = TelemetryEnvelope.builder()
                .installId(UUID.fromString("11111111-1111-1111-1111-111111111111"))
                .installVersion("1.0.5")
                .installRelease("ga")
                .source("backend")
                .sourceVersion("1.0.5")
                .userHash("ab39c0d1")
                .agent("claude-code")
                .build();

        TelemetryEvent event = TelemetryEvent.builder()
                .event("connection.created")
                .ts(OffsetDateTime.parse("2026-05-22T14:34:59.812Z"))
                .envelope(envelope)
                .properties(Map.of("db_dialect", "postgres", "ssh_enabled", true))
                .build();

        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        String json = mapper.writeValueAsString(event);
        assertTrue(json.contains("\"event\":\"connection.created\""));
        assertTrue(json.contains("\"install_id\":\"11111111-1111-1111-1111-111111111111\""));
        assertTrue(json.contains("\"db_dialect\":\"postgres\""));
    }
}
