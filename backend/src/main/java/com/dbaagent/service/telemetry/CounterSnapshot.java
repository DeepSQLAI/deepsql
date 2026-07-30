package com.dbaagent.service.telemetry;

import java.util.Map;

/**
 * Immutable snapshot of cumulative counter values at a single instant.
 * Used by HeartbeatRollupTracker to compute deltas between heartbeat fires.
 */
public record CounterSnapshot(
    long queriesTotal,
    Map<String, Long> queriesBySource,
    Map<String, Long> queriesByDialect,
    long brainRetrievals,
    long slowQueryRuns
) {
    public static CounterSnapshot zero() {
        return new CounterSnapshot(0L, Map.of(), Map.of(), 0L, 0L);
    }
}
