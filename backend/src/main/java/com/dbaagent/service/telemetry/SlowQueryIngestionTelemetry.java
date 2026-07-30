package com.dbaagent.service.telemetry;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/**
 * Wraps a slow-query ingestion polling cycle and emits a single
 * slow_query.ingestion_run.completed event capturing rows + duration + outcome.
 *
 * Failures emit the event (with success=false, rows_ingested=0) and rethrow,
 * so we never silently swallow ingestion errors.
 */
@Component
@RequiredArgsConstructor
public class SlowQueryIngestionTelemetry {

    private final TelemetryClient telemetryClient;
    private final TelemetryCounters counters;

    public int timeAndEmit(String dialect, Supplier<Integer> work) {
        long started = System.currentTimeMillis();
        int rows = 0;
        boolean success = false;
        try {
            rows = work.get();
            success = true;
            return rows;
        } finally {
            long durationMs = System.currentTimeMillis() - started;
            String normalizedDialect = TelemetryCounters.dialectTag(dialect);

            Map<String, Object> props = new HashMap<>();
            props.put("dialect", normalizedDialect);
            props.put("rows_ingested", rows);
            props.put("run_duration_ms", (int) Math.min(durationMs, Integer.MAX_VALUE));
            props.put("success", success);

            telemetryClient.capture("slow_query.ingestion_run.completed", props);
            counters.incrementSlowQueryRun(success);
        }
    }
}
