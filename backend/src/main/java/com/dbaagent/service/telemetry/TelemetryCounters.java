package com.dbaagent.service.telemetry;

import com.dbaagent.model.QueryExecutionOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.stereotype.Component;

/**
 * Central registry of Phase-2.5 cumulative Micrometer counters.
 *
 * Counter names are stable wire identifiers — the heartbeat rollup tracker
 * reads them by name to compute per-heartbeat-interval deltas. Do not rename without updating
 * HeartbeatRollupTracker in lockstep.
 */
@Component
public class TelemetryCounters {

    public static final String QUERIES_EXECUTED = "deepsql.telemetry.queries.executed";
    public static final String BRAIN_RETRIEVALS = "deepsql.telemetry.brain.retrievals";
    public static final String SLOW_QUERY_RUNS  = "deepsql.telemetry.slow_query.runs";

    private final MeterRegistry registry;

    public TelemetryCounters(MeterRegistry registry) {
        this.registry = registry;
    }

    public void incrementQuery(QueryExecutionOrigin origin, String dialect) {
        registry.counter(QUERIES_EXECUTED,
            "source",  sourceTag(origin),
            "dialect", dialectTag(dialect)
        ).increment();
    }

    public void incrementBrainRetrieval() {
        registry.counter(BRAIN_RETRIEVALS).increment();
    }

    public void incrementSlowQueryRun(boolean success) {
        registry.counter(SLOW_QUERY_RUNS, "success", Boolean.toString(success)).increment();
    }

    public static String sourceTag(QueryExecutionOrigin origin) {
        if (origin == null) return "unknown";
        return switch (origin) {
            case CHAT      -> "chat";
            case EDITOR    -> "ui";
            case INTERNAL  -> "internal";
            case MCP       -> "mcp";
            case SCHEDULED -> "scheduled";
            case API       -> "api";
        };
    }

    public static String dialectTag(String dbType) {
        if (dbType == null || dbType.isBlank()) return "unknown";
        String lower = dbType.toLowerCase();
        if (lower.startsWith("postgres")) return "postgres";
        if (lower.equals("mysql") || lower.equals("mariadb")) return "mysql";
        return "unknown";
    }
}
