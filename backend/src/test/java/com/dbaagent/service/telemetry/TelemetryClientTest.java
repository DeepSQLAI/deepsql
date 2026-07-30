package com.dbaagent.service.telemetry;

import com.dbaagent.model.InstallTelemetryIdentity;
import com.dbaagent.repository.InstallTelemetryIdentityRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryClientTest {

    @Mock private TelemetryEnvelopeBuilder builder;
    @Mock private TelemetryEventBuffer buffer;
    @Mock private TelemetrySink sink;
    @Mock private TelemetryProperties properties;
    @Mock private InstallTelemetryIdentityRepository repository;

    private TelemetryClient client;

    @BeforeEach
    void setup() {
        client = new TelemetryClient(builder, buffer, sink, properties, repository);
    }

    @Test
    void capturesValidEventToBuffer() {
        when(properties.isEffectivelyEnabled(any(), any())).thenReturn(true);
        TelemetryEvent built = mock(TelemetryEvent.class);
        when(builder.build("connection.created", Map.of("db_dialect", "postgres")))
                .thenReturn(Optional.of(built));

        client.capture("connection.created", Map.of("db_dialect", "postgres"));

        verify(buffer).offer(built);
    }

    @Test
    void skipsEventWhenTelemetryDisabled() {
        when(properties.isEffectivelyEnabled(any(), any())).thenReturn(false);

        client.capture("connection.created", Map.of());

        verifyNoInteractions(builder, buffer);
    }

    @Test
    void skipsEventWhenBuilderRejects() {
        when(properties.isEffectivelyEnabled(any(), any())).thenReturn(true);
        when(builder.build(any(), any())).thenReturn(Optional.empty());

        client.capture("totally.fake.event", Map.of());

        verifyNoInteractions(buffer);
    }

    @Test
    void identifySendsToSinkDirectly() {
        when(properties.isEffectivelyEnabled(any(), any())).thenReturn(true);
        UUID installId = UUID.randomUUID();
        InstallTelemetryIdentity identity = InstallTelemetryIdentity.builder()
                .id(1).installId(installId).build();
        when(repository.findById(1)).thenReturn(Optional.of(identity));

        client.identify(Map.of("hostname", "host-1"));

        verify(sink).identify(eq(installId), eq(Map.of("hostname", "host-1")));
    }
}
