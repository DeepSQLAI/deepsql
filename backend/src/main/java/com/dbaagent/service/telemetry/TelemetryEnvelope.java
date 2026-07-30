package com.dbaagent.service.telemetry;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Builder;

import java.util.UUID;

@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TelemetryEnvelope(
        @JsonProperty("install_id")      UUID installId,
        @JsonProperty("install_version") String installVersion,
        @JsonProperty("install_release") String installRelease,
        @JsonProperty("source")          String source,
        @JsonProperty("source_version")  String sourceVersion,
        @JsonProperty("user_hash")       String userHash,
        @JsonProperty("agent")           String agent,
        @JsonProperty("company_name")    String companyName
) { }
