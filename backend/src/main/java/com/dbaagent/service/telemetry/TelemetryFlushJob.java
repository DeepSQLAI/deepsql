package com.dbaagent.service.telemetry;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Drains the in-memory buffer to the configured sink on a fixed cadence.
 * Default 10 s — matches PostHog's recommended batching window.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class TelemetryFlushJob {

    private final TelemetryEventBuffer buffer;
    private final TelemetrySink sink;

    @Value("${deepsql.telemetry.batch-size:100}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${deepsql.telemetry.flush-interval-ms:10000}")
    public void flush() {
        while (true) {
            List<TelemetryEvent> batch = buffer.drain(batchSize);
            if (batch.isEmpty()) return;
            try {
                sink.send(batch);
                // Ops observability: surface every successful emit so operators
                // (and dashboards backed by log scraping) can see telemetry is
                // flowing. One line per batch — not per event.
                log.info("telemetry.flush sent batch of {} events: [{}]",
                        batch.size(),
                        batch.stream().map(TelemetryEvent::event).collect(Collectors.joining(",")));
            } catch (Exception e) {
                // Lose THIS batch (events were already drained), but keep draining
                // — a single failed POST must not abandon the rest of the buffer.
                log.warn("Telemetry flush: dropped batch of {} events: {}", batch.size(), e.getMessage());
            }
        }
    }
}
