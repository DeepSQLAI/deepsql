package com.dbaagent.service.telemetry;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TelemetryFlushJobTest {

    @Mock private TelemetryEventBuffer buffer;
    @Mock private TelemetrySink sink;
    @InjectMocks private TelemetryFlushJob job;

    private TelemetryEvent sample() {
        return TelemetryEvent.builder()
                .event("connection.created")
                .ts(OffsetDateTime.now())
                .envelope(TelemetryEnvelope.builder().installId(UUID.randomUUID()).build())
                .properties(Map.of())
                .build();
    }

    @Test
    void flushSendsBatchedEventsToSink() {
        List<TelemetryEvent> batch = List.of(sample(), sample());
        when(buffer.drain(anyInt())).thenReturn(batch).thenReturn(List.of());

        job.flush();

        verify(sink).send(batch);
    }

    @Test
    void flushIsNoOpWhenBufferEmpty() {
        when(buffer.drain(anyInt())).thenReturn(List.of());

        job.flush();

        verifyNoInteractions(sink);
    }

    @Test
    void flushKeepsDrainingUntilBufferIsEmpty() {
        List<TelemetryEvent> first = List.of(sample(), sample());
        List<TelemetryEvent> second = List.of(sample());
        when(buffer.drain(anyInt())).thenReturn(first).thenReturn(second).thenReturn(List.of());

        job.flush();

        verify(sink).send(first);
        verify(sink).send(second);
    }

    @Test
    void flushContinuesDrainingAfterSinkException() {
        List<TelemetryEvent> first = List.of(sample(), sample());
        List<TelemetryEvent> second = List.of(sample());
        when(buffer.drain(anyInt())).thenReturn(first).thenReturn(second).thenReturn(List.of());
        doThrow(new RuntimeException("posthog 502")).when(sink).send(first);

        job.flush();

        // Failed batch is dropped, but the loop must keep draining and ship
        // the next batch — otherwise a single transient POST failure abandons
        // every queued event.
        verify(sink).send(first);
        verify(sink).send(second);
    }
}
