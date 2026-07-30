package com.dbaagent.service.telemetry;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class SlowQueryIngestionTelemetryTest {

    @Test
    @SuppressWarnings("unchecked")
    void successfulRunEmitsCompletedEventWithRowsAndDuration() {
        TelemetryClient client = mock(TelemetryClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);
        SlowQueryIngestionTelemetry telemetry = new SlowQueryIngestionTelemetry(client, counters);

        int rows = telemetry.timeAndEmit("postgres", () -> {
            try { Thread.sleep(5); } catch (InterruptedException ignored) {}
            return 42;
        });

        assertThat(rows).isEqualTo(42);

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).capture(eq("slow_query.ingestion_run.completed"), cap.capture());

        Map<String, Object> props = cap.getValue();
        assertThat(props.get("dialect")).isEqualTo("postgres");
        assertThat(props.get("rows_ingested")).isEqualTo(42);
        assertThat(props.get("success")).isEqualTo(true);
        assertThat((Integer) props.get("run_duration_ms")).isGreaterThanOrEqualTo(0);

        assertThat(registry.counter(TelemetryCounters.SLOW_QUERY_RUNS, "success", "true").count())
            .isEqualTo(1.0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void failingRunEmitsCompletedEventWithSuccessFalseAndRethrows() {
        TelemetryClient client = mock(TelemetryClient.class);
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);
        SlowQueryIngestionTelemetry telemetry = new SlowQueryIngestionTelemetry(client, counters);

        try {
            telemetry.timeAndEmit("mysql", () -> { throw new RuntimeException("boom"); });
        } catch (RuntimeException expected) {
            assertThat(expected).hasMessage("boom");
        }

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).capture(eq("slow_query.ingestion_run.completed"), cap.capture());
        Map<String, Object> props = cap.getValue();
        assertThat(props.get("success")).isEqualTo(false);
        assertThat(props.get("dialect")).isEqualTo("mysql");
        assertThat(props.get("rows_ingested")).isEqualTo(0);

        assertThat(registry.counter(TelemetryCounters.SLOW_QUERY_RUNS, "success", "false").count())
            .isEqualTo(1.0);
    }
}
