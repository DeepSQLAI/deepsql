package com.dbaagent.service.telemetry;

import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Bounded FIFO buffer of pending telemetry events.
 *
 * Bounded so a long DeepSQL outage cannot exhaust JVM heap.
 * Drops oldest on overflow. Spillover-to-disk is a Phase-2 concern, not here.
 *
 * Thread-safe — emit threads call offer(), the scheduled flush job calls drain().
 */
@Component
@Slf4j
public class TelemetryEventBuffer {

    private final Deque<TelemetryEvent> events = new LinkedList<>();
    private final int capacity;
    private final AtomicLong droppedCount = new AtomicLong(0);

    public TelemetryEventBuffer(@Value("${deepsql.telemetry.buffer-capacity:5000}") int capacity) {
        this.capacity = capacity;
    }

    public synchronized void offer(TelemetryEvent event) {
        if (events.size() >= capacity) {
            events.pollFirst();
            droppedCount.incrementAndGet();
        }
        events.addLast(event);
    }

    public synchronized List<TelemetryEvent> drain(int maxBatchSize) {
        if (events.isEmpty()) return List.of();
        int take = Math.min(maxBatchSize, events.size());
        List<TelemetryEvent> out = new ArrayList<>(take);
        for (int i = 0; i < take; i++) out.add(events.pollFirst());
        return out;
    }

    public synchronized int size() {
        return events.size();
    }

    public long droppedCount() {
        return droppedCount.get();
    }
}
