package com.dbaagent.service.telemetry;

import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.repository.CredentialRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.*;

class InstallHeartbeatJobRollupTest {

    @Test
    @SuppressWarnings("unchecked")
    void heartbeatIncludesPhase25Rollups() {
        TelemetryClient client = mock(TelemetryClient.class);
        CredentialRepository repo = mock(CredentialRepository.class);
        when(repo.count()).thenReturn(3L);

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        TelemetryCounters counters = new TelemetryCounters(registry);
        HeartbeatRollupTracker tracker = new HeartbeatRollupTracker(registry);

        counters.incrementQuery(QueryExecutionOrigin.MCP, "postgres");
        counters.incrementBrainRetrieval();

        // postgres probe: a DataSource whose connection reports valid
        DataSource dataSource = mock(DataSource.class);
        Connection conn = mock(Connection.class);
        try {
            when(dataSource.getConnection()).thenReturn(conn);
            when(conn.isValid(anyInt())).thenReturn(true);
        } catch (Exception ignored) {}

        // valkey probe: a RedisConnectionFactory that PONGs
        RedisConnectionFactory redisFactory = mock(RedisConnectionFactory.class);
        RedisConnection redisConn = mock(RedisConnection.class);
        when(redisFactory.getConnection()).thenReturn(redisConn);
        when(redisConn.ping()).thenReturn("PONG");
        @SuppressWarnings("unchecked")
        ObjectProvider<RedisConnectionFactory> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(redisFactory);

        // frontend probe: unreachable URL -> false (no frontend in unit test)
        InstallHeartbeatJob job = new InstallHeartbeatJob(
                client, repo, tracker, dataSource, redisProvider,
                "http://127.0.0.1:1/");
        job.emitHeartbeat();

        ArgumentCaptor<Map<String, Object>> cap = ArgumentCaptor.forClass(Map.class);
        verify(client).capture(eq("install.heartbeat"), cap.capture());

        Map<String, Object> props = cap.getValue();
        assertThat(props).containsKey("queries_interval_total")
                         .containsKey("queries_interval_source_mcp")
                         .containsKey("queries_interval_dialect_postgres")
                         .containsKey("brain_retrievals_interval_total")
                         .containsKey("slow_query_ingestion_runs_interval")
                         .containsKey("services_up")
                         .containsKey("frontend_reachable");
        assertThat(props.get("queries_interval_total")).isEqualTo(1L);
        assertThat(props.get("brain_retrievals_interval_total")).isEqualTo(1L);
        // backend (self) + postgres + valkey probed healthy; frontend unreachable
        assertThat((List<String>) props.get("services_up"))
                .containsExactly("backend", "postgres", "valkey");
        assertThat(props.get("frontend_reachable")).isEqualTo(false);
    }
}
