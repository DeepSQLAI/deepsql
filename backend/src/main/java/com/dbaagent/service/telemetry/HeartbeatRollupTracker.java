package com.dbaagent.service.telemetry;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Computes per-heartbeat-interval deltas of Phase-2.5 cumulative counters by
 * diffing the current registry against the last heartbeat-fire snapshot, then
 * advancing the baseline.
 *
 * The emitted rollup keys are named "*_interval_*" deliberately: each value is
 * the activity SINCE THE PREVIOUS HEARTBEAT (the interval between fires —
 * default 30 min), NOT a 24h rolling total. To get a total over any time
 * range, sum the interval values across the heartbeats in that range
 * (PostHog: SUM aggregation over the property). A true sliding-window total is
 * a Phase-3 concern.
 */
@Component
public class HeartbeatRollupTracker {

    private final MeterRegistry registry;
    private final AtomicReference<CounterSnapshot> lastSnapshot =
        new AtomicReference<>(CounterSnapshot.zero());

    public HeartbeatRollupTracker(MeterRegistry registry) {
        this.registry = registry;
    }

    // Closed enums (QueryExecutionOrigin -> sourceTag; provider dialects). Emitting
    // a stable flat key per value — always present, 0-filled — lets PostHog SUM each
    // breakdown with point-and-click Trends instead of HogQL JSON extraction.
    static final List<String> SOURCES =
        List.of("ui", "chat", "mcp", "scheduled", "api", "internal", "unknown");
    static final List<String> DIALECTS =
        List.of("postgres", "mysql", "unknown");

    public Map<String, Object> snapshotAndReset() {
        CounterSnapshot current = capture();
        CounterSnapshot previous = lastSnapshot.getAndSet(current);

        Map<String, Long> bySource  = diffMap(current.queriesBySource(),  previous.queriesBySource());
        Map<String, Long> byDialect = diffMap(current.queriesByDialect(), previous.queriesByDialect());

        Map<String, Object> rollup = new HashMap<>();
        rollup.put("queries_interval_total", current.queriesTotal() - previous.queriesTotal());
        // Flat top-level per-source / per-dialect counts (stable columns, 0-filled).
        for (String s : SOURCES)  rollup.put("queries_interval_source_" + s,  bySource.getOrDefault(s, 0L));
        for (String d : DIALECTS) rollup.put("queries_interval_dialect_" + d, byDialect.getOrDefault(d, 0L));
        rollup.put("brain_retrievals_interval_total", current.brainRetrievals() - previous.brainRetrievals());
        rollup.put("slow_query_ingestion_runs_interval", current.slowQueryRuns() - previous.slowQueryRuns());
        return rollup;
    }

    private CounterSnapshot capture() {
        long total = 0L;
        Map<String, Long> bySource  = new HashMap<>();
        Map<String, Long> byDialect = new HashMap<>();

        for (Counter c : registry.find(TelemetryCounters.QUERIES_EXECUTED).counters()) {
            long n = (long) c.count();
            total += n;
            String src = tagValue(c, "source");
            String dia = tagValue(c, "dialect");
            if (src != null) bySource.merge(src,  n, Long::sum);
            if (dia != null) byDialect.merge(dia, n, Long::sum);
        }

        long brain = (long) registry.find(TelemetryCounters.BRAIN_RETRIEVALS)
            .counters().stream().mapToDouble(Counter::count).sum();
        long slow = (long) registry.find(TelemetryCounters.SLOW_QUERY_RUNS)
            .counters().stream().mapToDouble(Counter::count).sum();

        return new CounterSnapshot(total, bySource, byDialect, brain, slow);
    }

    private static String tagValue(Counter c, String key) {
        for (Tag t : c.getId().getTags()) {
            if (t.getKey().equals(key)) return t.getValue();
        }
        return null;
    }

    private static Map<String, Long> diffMap(Map<String, Long> now, Map<String, Long> then) {
        Map<String, Long> out = new HashMap<>();
        for (Map.Entry<String, Long> e : now.entrySet()) {
            long delta = e.getValue() - then.getOrDefault(e.getKey(), 0L);
            if (delta > 0) out.put(e.getKey(), delta);
        }
        return out;
    }
}
