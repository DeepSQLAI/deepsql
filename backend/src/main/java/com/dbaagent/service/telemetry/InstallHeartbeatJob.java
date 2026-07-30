package com.dbaagent.service.telemetry;

import com.dbaagent.repository.CredentialRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Emits install.heartbeat every 30 minutes with current install state.
 *
 * services_up is probed each fire: backend (self), postgres (vault DataSource),
 * valkey (Redis connection factory), and frontend (best-effort HTTP probe of
 * the in-network frontend service). A service appears in the list only if its
 * probe succeeds; frontend_reachable is also emitted as an explicit boolean.
 * Each probe is independently guarded and time-bounded so a single sick
 * dependency can neither stall nor fail the heartbeat.
 */
@Component
@Slf4j
public class InstallHeartbeatJob {

    private final TelemetryClient telemetryClient;
    private final CredentialRepository connectionRepository;
    private final HeartbeatRollupTracker rollupTracker;
    private final DataSource dataSource;
    private final ObjectProvider<RedisConnectionFactory> redisFactoryProvider;
    private final String frontendHealthUrl;
    private final HttpClient httpClient;

    public InstallHeartbeatJob(
            TelemetryClient telemetryClient,
            CredentialRepository connectionRepository,
            HeartbeatRollupTracker rollupTracker,
            DataSource dataSource,
            ObjectProvider<RedisConnectionFactory> redisFactoryProvider,
            @Value("${deepsql.telemetry.frontend-health-url:http://frontend:80/}") String frontendHealthUrl) {
        this.telemetryClient = telemetryClient;
        this.connectionRepository = connectionRepository;
        this.rollupTracker = rollupTracker;
        this.dataSource = dataSource;
        this.redisFactoryProvider = redisFactoryProvider;
        this.frontendHealthUrl = frontendHealthUrl;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(2))
                .build();
    }

    @Scheduled(fixedRateString  = "${deepsql.telemetry.heartbeat-interval-ms:1800000}",
               initialDelayString = "${deepsql.telemetry.heartbeat-initial-delay-ms:60000}")
    public void emitHeartbeat() {
        boolean frontendReachable = probeFrontend();

        Map<String, Object> props = new HashMap<>();
        props.put("services_up", probeServices(frontendReachable));
        props.put("frontend_reachable", frontendReachable);
        props.put("connections_count", saturateToInt(connectionRepository.count()));
        props.put("uptime_seconds", saturateToInt(ManagementFactory.getRuntimeMXBean().getUptime() / 1000));
        props.putAll(rollupTracker.snapshotAndReset());
        telemetryClient.capture("install.heartbeat", props);
    }

    /**
     * Probe each dependency the backend can reach and report the healthy ones.
     * Order is stable (backend, postgres, valkey, frontend) for readable dashboards.
     */
    private List<String> probeServices(boolean frontendReachable) {
        List<String> up = new ArrayList<>();
        up.add("backend"); // self-witness: if this runs, the backend is up
        if (probePostgres()) up.add("postgres");
        if (probeValkey())   up.add("valkey");
        if (frontendReachable) up.add("frontend");
        return up;
    }

    private boolean probePostgres() {
        try (Connection c = dataSource.getConnection()) {
            return c.isValid(2);
        } catch (Exception e) {
            log.debug("heartbeat: postgres probe failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean probeValkey() {
        RedisConnectionFactory factory = redisFactoryProvider.getIfAvailable();
        if (factory == null) {
            return false;
        }
        try (RedisConnection conn = factory.getConnection()) {
            String pong = conn.ping();
            return pong != null && "PONG".equalsIgnoreCase(pong);
        } catch (Exception e) {
            log.debug("heartbeat: valkey probe failed: {}", e.getMessage());
            return false;
        }
    }

    private boolean probeFrontend() {
        try {
            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(frontendHealthUrl))
                    .timeout(Duration.ofSeconds(2))
                    .GET()
                    .build();
            HttpResponse<Void> resp = httpClient.send(req, HttpResponse.BodyHandlers.discarding());
            // Any HTTP response (even a 404 from the SPA host) proves the
            // frontend container is serving — we only care that it answers.
            return resp.statusCode() > 0;
        } catch (Exception e) {
            log.debug("heartbeat: frontend probe failed: {}", e.getMessage());
            return false;
        }
    }

    /** Clamp a non-negative long to int range — never wrap to negative on overflow. */
    private static int saturateToInt(long value) {
        if (value < 0) return 0;
        return value > Integer.MAX_VALUE ? Integer.MAX_VALUE : (int) value;
    }
}
