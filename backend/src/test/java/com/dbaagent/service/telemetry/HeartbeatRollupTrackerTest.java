package com.dbaagent.service.telemetry;

import com.dbaagent.model.QueryExecutionOrigin;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class HeartbeatRollupTrackerTest {

    @Test
    void firstSnapshotReportsAllCumulativeCountsAsDelta() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);
        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        counters.incrementQuery(QueryExecutionOrigin.CHAT, "postgres");
        counters.incrementBrainRetrieval();
        counters.incrementSlowQueryRun(true);

        HeartbeatRollupTracker tracker = new HeartbeatRollupTracker(registry);

        Map<String, Object> roll = tracker.snapshotAndReset();

        assertThat(roll.get("queries_interval_total")).isEqualTo(2L);
        assertThat(roll.get("queries_interval_source_mcp")).isEqualTo(1L);
        assertThat(roll.get("queries_interval_source_chat")).isEqualTo(1L);
        assertThat(roll.get("queries_interval_source_ui")).isEqualTo(0L);
        assertThat(roll.get("queries_interval_dialect_postgres")).isEqualTo(2L);
        assertThat(roll.get("queries_interval_dialect_mysql")).isEqualTo(0L);
        assertThat(roll.get("brain_retrievals_interval_total")).isEqualTo(1L);
        assertThat(roll.get("slow_query_ingestion_runs_interval")).isEqualTo(1L);
    }

    @Test
    void secondSnapshotReportsOnlyDelta() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);
        HeartbeatRollupTracker tracker = new HeartbeatRollupTracker(registry);

        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        tracker.snapshotAndReset();  // baseline = 1

        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        Map<String, Object> roll = tracker.snapshotAndReset();

        assertThat(roll.get("queries_interval_total")).isEqualTo(2L);
        assertThat(roll.get("queries_interval_source_mcp")).isEqualTo(2L);
    }

    @Test
    void zeroActivityReturnsZeros() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        HeartbeatRollupTracker tracker = new HeartbeatRollupTracker(registry);

        Map<String, Object> roll = tracker.snapshotAndReset();

        assertThat(roll.get("queries_interval_total")).isEqualTo(0L);
        assertThat(roll.get("brain_retrievals_interval_total")).isEqualTo(0L);
        assertThat(roll.get("slow_query_ingestion_runs_interval")).isEqualTo(0L);
    }
}
