package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.service.telemetry.TelemetryCounters;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.CacheManager;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Verifies that QueryExecutorService increments the telemetry counter
 * tagged by source + dialect at the 3-arg executeQuery seam.
 *
 * Uses direct instantiation (no Spring context) to keep the test fast
 * and avoid @MockBean which was removed in Spring Boot 4.
 */
@ExtendWith(MockitoExtension.class)
class QueryExecutorServiceCounterTest {

    @Mock ConnectionService connectionService;
    @Mock CredentialService credentialService;
    @Mock SchemaSnapshotService schemaSnapshotService;
    @Mock CacheManager cacheManager;
    @Mock CacheMetricsService cacheMetricsService;
    @Mock QueryLineageService queryLineageService;
    @Mock DatabaseProviderRegistry providerRegistry;
    @Mock QueryExecutionPolicyService queryExecutionPolicyService;
    @Mock UserDataAccessPolicyService userDataAccessPolicyService;
    @Mock com.dbaagent.repository.KeyColumnAnalysisRepository keyColumnAnalysisRepository;

    SimpleMeterRegistry meterRegistry;
    TelemetryCounters telemetryCounters;
    QueryExecutorService queryExecutorService;

    @BeforeEach
    void setUp() {
        meterRegistry = new SimpleMeterRegistry();
        telemetryCounters = new TelemetryCounters(meterRegistry);
        queryExecutorService = new QueryExecutorService(
            connectionService,
            credentialService,
            schemaSnapshotService,
            cacheManager,
            cacheMetricsService,
            queryLineageService,
            providerRegistry,
            queryExecutionPolicyService,
            userDataAccessPolicyService,
            telemetryCounters,
            keyColumnAnalysisRepository,
            new RunningQueryRegistry()
        );
    }

    @Test
    void incrementsQueriesExecutedCounterTaggedBySourceAndDialect() throws Exception {
        ConnectionRequest conn = new ConnectionRequest();
        conn.setDbType("postgresql");
        when(credentialService.getDecryptedConnection(any())).thenReturn(conn);
        when(providerRegistry.getCanonicalName("postgresql")).thenReturn("postgres");
        // Policy enforcement: return permissive decision
        when(queryExecutionPolicyService.enforce(any(), any(), any()))
            .thenReturn(new QueryExecutionPolicyService.PolicyDecision(List.of(), false, "SELECT"));

        QueryRequest req = new QueryRequest();
        req.setQuery("SELECT 1");
        QueryExecutionContext ctx = QueryExecutionContext.mcp("svc");

        try {
            queryExecutorService.executeQuery("conn-1", req, ctx);
        } catch (Exception ignored) {
            // JDBC call will fail in the test harness — counter increment happens
            // before connection acquisition, so we still expect the metric.
        }

        assertThat(meterRegistry.counter(TelemetryCounters.QUERIES_EXECUTED,
                "source", "mcp", "dialect", "postgres").count())
            .isGreaterThanOrEqualTo(1.0);
    }
}
