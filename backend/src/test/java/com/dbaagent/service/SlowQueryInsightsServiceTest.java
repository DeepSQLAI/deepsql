package com.dbaagent.service;

import com.dbaagent.dto.KeyCustomerInfo;
import com.dbaagent.dto.KeyCustomerResult;
import com.dbaagent.dto.SlowQueryInsightsResponse;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.QueryPerformanceHistory;
import com.dbaagent.model.QueryPlanComparison;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.model.SlowQueryAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.QueryPerformanceHistoryRepository;
import com.dbaagent.repository.QueryPlanComparisonRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SlowQueryInsightsServiceTest {

    private SlowQueryHistoryRepository historyRepository;
    private SlowQueryHistoryService historyService;
    private QueryFingerprintRepository fingerprintRepository;
    private QueryPlanComparisonRepository planComparisonRepository;
    private QueryPerformanceHistoryRepository performanceHistoryRepository;
    private KeyCustomerService keyCustomerService;
    private SlowQueryInsightsService insightsService;

    @BeforeEach
    void setUp() {
        historyRepository = mock(SlowQueryHistoryRepository.class);
        historyService = mock(SlowQueryHistoryService.class);
        fingerprintRepository = mock(QueryFingerprintRepository.class);
        planComparisonRepository = mock(QueryPlanComparisonRepository.class);
        performanceHistoryRepository = mock(QueryPerformanceHistoryRepository.class);
        keyCustomerService = mock(KeyCustomerService.class);

        insightsService = new SlowQueryInsightsService(
            historyRepository,
            historyService,
            fingerprintRepository,
            planComparisonRepository,
            performanceHistoryRepository,
            keyCustomerService
        );
    }

    @Test
    void getInsights_buildsAllPhasesFromObservedData() {
        SlowQueryHistory history = new SlowQueryHistory();
        history.setId("hist-1");
        history.setConnectionId("conn-1");
        history.setCreatedAt(LocalDateTime.now().minusHours(2));

        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTotalSlowQueries(20L);
        analysis.setTopSlowQueries(List.of(
            buildSlowQuery(
                "q-1",
                "SELECT * FROM bookings WHERE hotel_id = ?",
                "SELECT * FROM bookings WHERE hotel_id = 42",
                SlowQuery.Severity.CRITICAL,
                850.0,
                30L,
                120000L,
                400L
            ),
            buildSlowQuery(
                "q-2",
                "SELECT * FROM orders WHERE tenant_id = ?",
                "SELECT * FROM orders WHERE tenant_id = 9",
                SlowQuery.Severity.HIGH,
                320.0,
                25L,
                50000L,
                800L
            )
        ));

        QueryPerformanceHistory perf = QueryPerformanceHistory.builder()
            .connectionId("conn-1")
            .queryHash("q-1")
            .executionTimeMs(900.0)
            .lockWaitMs(120.0)
            .executionTimestamp(LocalDateTime.now().minusHours(1))
            .build();

        KeyCustomerInfo skewItem = KeyCustomerInfo.builder()
            .id("kc-1")
            .tableName("bookings")
            .columnName("hotel_id")
            .displayValue("42")
            .slowQueryCount(1)
            .criticalCount(1)
            .highCount(0)
            .matchingQueryIds(List.of("q-1"))
            .worstQueryPreview("SELECT * FROM bookings WHERE hotel_id = ?")
            .build();
        KeyCustomerResult keyCustomerResult = KeyCustomerResult.builder()
            .connectionId("conn-1")
            .totalSlowQueriesAnalyzed(2)
            .queriesCapped(false)
            .keyCustomers(List.of(skewItem))
            .build();

        when(historyRepository.findByConnectionIdSince(eq("conn-1"), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of(history));
        when(historyService.getAnalysisData(history)).thenReturn(analysis);
        when(performanceHistoryRepository.findRecentHistory(eq("conn-1"), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of(perf));
        when(planComparisonRepository.findTop50ByConnectionIdOrderByComparedAtDesc("conn-1"))
            .thenReturn(List.of());
        when(keyCustomerService.analyze(eq("conn-1"), anyInt(), isNull()))
            .thenReturn(Optional.of(keyCustomerResult));

        SlowQueryInsightsResponse response = insightsService.getInsights("conn-1", "7d", 10);

        assertNotNull(response);
        assertNotNull(response.getMetadata());
        assertEquals(1, response.getMetadata().getHistoriesAnalyzed());
        assertEquals(2, response.getMetadata().getQueryObservationsAnalyzed());
        assertEquals("7d", response.getMetadata().getWindow());

        assertNotNull(response.getRemediation());
        assertFalse(response.getRemediation().getItems().isEmpty());

        assertNotNull(response.getHotspots());
        assertFalse(response.getHotspots().getScanWaste().isEmpty());
        assertTrue(response.getHotspots().isLockDataAvailable());
        assertFalse(response.getHotspots().getLockHotspots().isEmpty());

        assertNotNull(response.getSkew());
        assertFalse(response.getSkew().getItems().isEmpty());

        assertNotNull(response.getTailRisk());
        assertFalse(response.getTailRisk().getTailRisks().isEmpty());

        assertNotNull(response.getPlanDrift());
    }

    @Test
    void getPlanDriftInsights_prefersPlanComparisonTableWhenAvailable() {
        QueryPlanComparison comparison = QueryPlanComparison.builder()
            .id("cmp-1")
            .connectionId("conn-1")
            .queryHash("q-plan")
            .baselinePlanId("plan-A")
            .currentPlanId("plan-B")
            .planChanged(true)
            .costChangePercent(175.0)
            .severity(QueryPlanComparison.Severity.CRITICAL)
            .comparedAt(LocalDateTime.now().minusHours(1))
            .build();

        QueryFingerprint fingerprint = QueryFingerprint.builder()
            .fingerprint("q-plan")
            .normalizedQuery("SELECT * FROM orders WHERE tenant_id = ?")
            .build();

        when(historyRepository.findByConnectionIdSince(eq("conn-1"), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(planComparisonRepository.findTop50ByConnectionIdOrderByComparedAtDesc("conn-1"))
            .thenReturn(List.of(comparison));
        when(fingerprintRepository.findByConnectionIdAndFingerprint("conn-1", "q-plan"))
            .thenReturn(Optional.of(fingerprint));

        SlowQueryInsightsResponse.PlanDriftInsights response =
            insightsService.getPlanDriftInsights("conn-1", "7d", 10);

        assertNotNull(response);
        assertTrue(response.isFromPlanComparisonTable());
        assertEquals(1, response.getTotalPlanDriftQueries());
        assertEquals(1, response.getCriticalRegressions());
        assertEquals("q-plan", response.getItems().get(0).getQueryId());
    }

    @Test
    void getInsights_defaultsInvalidWindowAndEmptyDataSafely() {
        when(historyRepository.findByConnectionIdSince(eq("conn-1"), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(planComparisonRepository.findTop50ByConnectionIdOrderByComparedAtDesc("conn-1"))
            .thenReturn(List.of());
        when(performanceHistoryRepository.findRecentHistory(eq("conn-1"), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(keyCustomerService.analyze(eq("conn-1"), anyInt(), isNull()))
            .thenReturn(Optional.empty());

        SlowQueryInsightsResponse response = insightsService.getInsights("conn-1", "nonsense", -1);

        assertNotNull(response);
        assertNotNull(response.getMetadata());
        assertEquals("7d", response.getMetadata().getWindow());
        assertEquals(0, response.getMetadata().getHistoriesAnalyzed());
        assertNotNull(response.getRemediation());
        assertTrue(response.getRemediation().getItems().isEmpty());
    }

    private SlowQuery buildSlowQuery(
        String queryId,
        String normalizedQuery,
        String sampleQuery,
        SlowQuery.Severity severity,
        double avgExecutionMs,
        long callCount,
        long rowsExamined,
        long rowsSent
    ) {
        SlowQuery query = new SlowQuery();
        query.setQueryId(queryId);
        query.setNormalizedQuery(normalizedQuery);
        query.setSampleQuery(sampleQuery);
        query.setSeverity(severity);
        query.setAvgExecutionTimeMs(avgExecutionMs);
        query.setCallCount(callCount);
        query.setRowsExamined(rowsExamined);
        query.setRowsSent(rowsSent);
        query.setAffectedTables(List.of("bookings"));
        query.setTotalExecutionTimeMs(avgExecutionMs * callCount);
        return query;
    }
}
