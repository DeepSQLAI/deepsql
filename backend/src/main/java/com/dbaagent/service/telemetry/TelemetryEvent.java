package com.dbaagent.service.telemetry;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.time.OffsetDateTime;
import java.util.Map;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryEvent(
        @JsonProperty("event") String event,

        @JsonProperty("ts")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        OffsetDateTime ts,

        @JsonProperty("envelope")   TelemetryEnvelope envelope,
        @JsonProperty("properties") Map<String, Object> properties
) { }
