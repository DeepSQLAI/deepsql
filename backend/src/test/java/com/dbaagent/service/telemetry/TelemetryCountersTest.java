package com.dbaagent.service.telemetry;

import com.dbaagent.model.QueryExecutionOrigin;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class TelemetryCountersTest {

    @Test
    void sourceTagMapsCanonicalLowercase() {
        assertThat(TelemetryCounters.sourceTag(QueryExecutionOrigin.CHAT)).isEqualTo("chat");
        assertThat(TelemetryCounters.sourceTag(QueryExecutionOrigin.EDITOR)).isEqualTo("ui");
        assertThat(TelemetryCounters.sourceTag(QueryExecutionOrigin.INTERNAL)).isEqualTo("internal");
        assertThat(TelemetryCounters.sourceTag(QueryExecutionOrigin.MCP)).isEqualTo("mcp");
        assertThat(TelemetryCounters.sourceTag(QueryExecutionOrigin.SCHEDULED)).isEqualTo("scheduled");
        assertThat(TelemetryCounters.sourceTag(QueryExecutionOrigin.API)).isEqualTo("api");
        assertThat(TelemetryCounters.sourceTag(null)).isEqualTo("unknown");
    }

    @Test
    void normalizeDialectMapsAliases() {
        assertThat(TelemetryCounters.dialectTag("postgresql")).isEqualTo("postgres");
        assertThat(TelemetryCounters.dialectTag("POSTGRES")).isEqualTo("postgres");
        assertThat(TelemetryCounters.dialectTag("mysql")).isEqualTo("mysql");
        assertThat(TelemetryCounters.dialectTag("MariaDB")).isEqualTo("mysql");
        assertThat(TelemetryCounters.dialectTag("")).isEqualTo("unknown");
        assertThat(TelemetryCounters.dialectTag(null)).isEqualTo("unknown");
        assertThat(TelemetryCounters.dialectTag("oracle")).isEqualTo("unknown");
    }

    @Test
    void incrementQueryCounterTagsBySourceAndDialect() {
        MeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);

        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        counters.incrementQuery(QueryExecutionOrigin.CHAT, "mysql");

        assertThat(registry.counter("deepsql.telemetry.queries.executed", "source", "mcp", "dialect", "postgres").count())
            .isEqualTo(2.0);
        assertThat(registry.counter("deepsql.telemetry.queries.executed", "source", "chat", "dialect", "mysql").count())
            .isEqualTo(1.0);
    }

    @Test
    void incrementBrainRetrievalCountsTotal() {
        MeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);

        counters.incrementBrainRetrieval();
        counters.incrementBrainRetrieval();

        assertThat(registry.counter("deepsql.telemetry.brain.retrievals").count()).isEqualTo(2.0);
    }

    @Test
    void incrementSlowQueryRunCountsBySuccess() {
        MeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);

        counters.incrementSlowQueryRun(true);
        counters.incrementSlowQueryRun(false);
        counters.incrementSlowQueryRun(true);

        assertThat(registry.counter("deepsql.telemetry.slow_query.runs", "success", "true").count()).isEqualTo(2.0);
        assertThat(registry.counter("deepsql.telemetry.slow_query.runs", "success", "false").count()).isEqualTo(1.0);
    }
}
