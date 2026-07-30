package com.dbaagent.service.telemetry;

import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

class TelemetryEventBufferTest {

    private TelemetryEvent sample() {
        return TelemetryEvent.builder()
                .event("connection.created")
                .ts(OffsetDateTime.now())
                .envelope(TelemetryEnvelope.builder().installId(UUID.randomUUID()).build())
                .properties(Map.of())
                .build();
    }

    @Test
    void offerAndDrainReturnsEverythingInOrder() {
        TelemetryEventBuffer buffer = new TelemetryEventBuffer(100);
        TelemetryEvent a = sample(), b = sample(), c = sample();

        buffer.offer(a);
        buffer.offer(b);
        buffer.offer(c);

        List<TelemetryEvent> drained = buffer.drain(10);
        assertEquals(List.of(a, b, c), drained);
        assertEquals(0, buffer.size());
    }

    @Test
    void drainRespectsMaxBatchSize() {
        TelemetryEventBuffer buffer = new TelemetryEventBuffer(100);
        for (int i = 0; i < 5; i++) buffer.offer(sample());

        List<TelemetryEvent> drained = buffer.drain(2);

        assertEquals(2, drained.size());
        assertEquals(3, buffer.size());
    }

    @Test
    void dropsOldestWhenCapacityExceeded() {
        TelemetryEventBuffer buffer = new TelemetryEventBuffer(2);
        TelemetryEvent a = sample(), b = sample(), c = sample();

        buffer.offer(a);
        buffer.offer(b);
        buffer.offer(c);

        List<TelemetryEvent> drained = buffer.drain(10);
        assertEquals(List.of(b, c), drained);
        assertEquals(1, buffer.droppedCount());
    }
}
