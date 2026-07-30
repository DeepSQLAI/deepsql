package com.dbaagent.service.telemetry;

import com.dbaagent.repository.CredentialRepository;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.data.redis.connection.RedisConnectionFactory;

import javax.sql.DataSource;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class InstallHeartbeatJobTest {

    @Test
    @SuppressWarnings("unchecked")
    void emitsHeartbeatWithCountsAndServicesUp() {
        TelemetryClient telemetryClient = mock(TelemetryClient.class);
        CredentialRepository connectionRepository = mock(CredentialRepository.class);
        HeartbeatRollupTracker rollupTracker = mock(HeartbeatRollupTracker.class);
        when(connectionRepository.count()).thenReturn(7L);
        when(rollupTracker.snapshotAndReset()).thenReturn(new HashMap<>());

        // No reachable dependencies in this unit test: probes fail gracefully,
        // so services_up should still always contain the self-witness "backend".
        DataSource dataSource = mock(DataSource.class);
        ObjectProvider<RedisConnectionFactory> redisProvider = mock(ObjectProvider.class);
        when(redisProvider.getIfAvailable()).thenReturn(null);

        InstallHeartbeatJob job = new InstallHeartbeatJob(
                telemetryClient, connectionRepository, rollupTracker,
                dataSource, redisProvider, "http://127.0.0.1:1/");

        job.emitHeartbeat();

        ArgumentCaptor<Map<String, Object>> propsCaptor = ArgumentCaptor.forClass(Map.class);
        verify(telemetryClient).capture(eq("install.heartbeat"), propsCaptor.capture());
        Map<String, Object> props = propsCaptor.getValue();
        assertEquals(7, props.get("connections_count"));
        assertTrue(((List<String>) props.get("services_up")).contains("backend"));
        assertEquals(false, props.get("frontend_reachable"));
    }
}
