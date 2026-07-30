package com.dbaagent.service;

import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.IndexRecommendationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.InvalidDataAccessResourceUsageException;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexAdvisorServiceTest {

    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private PerformanceMonitoringService performanceMonitoringService;
    @Mock private IndexRecommendationRepository recommendationRepository;
    @Mock private DatabaseProviderRegistry providerRegistry;

    private IndexAdvisorService service;

    @BeforeEach
    void setUp() {
        service = new IndexAdvisorService(
            connectionService, credentialService, performanceMonitoringService,
            recommendationRepository, providerRegistry);
    }

    /**
     * The original bug: a missing column on index_recommendations made the
     * recommendation query throw, which (under the old @Transactional) took the
     * whole health report down with an opaque 500 — even the catalog-derived
     * parts that don't touch that table.
     *
     * Now the report must DEGRADE: catalog pieces (unused / duplicate) come
     * through, pendingRecommendationCount falls back to 0, and the response
     * flags `degraded` so the caller knows the rec store was unreadable.
     */
    @Test
    void healthReport_degradesGracefully_whenRecommendationTableIsBroken() {
        when(performanceMonitoringService.getUnusedIndexes(anyString())).thenReturn(List.of(
            Map.of("indexName", "idx_a", "tableName", "t1", "indexSizeBytes", 1024L)
        ));
        when(performanceMonitoringService.getDuplicateIndexes(anyString())).thenReturn(List.of(
            Map.of("index1Name", "idx_b", "index2Name", "idx_c", "tableName", "t1")
        ));
        // Simulate "column kind does not exist" — the exact failure shape.
        when(recommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(anyString(), any()))
            .thenThrow(new InvalidDataAccessResourceUsageException("column ire1_0.kind does not exist"));
        // getOverallIndexStats opens a real target connection; let credential lookup
        // fail so it returns an empty map (it catches internally) — keeps the test hermetic.
        when(credentialService.getDecryptedConnection(anyString()))
            .thenThrow(new RuntimeException("no creds in unit test"));

        Map<String, Object> report = service.getIndexHealthReport("c1");

        // Catalog-derived pieces survived
        assertThat(report.get("unusedIndexCount")).isEqualTo(1);
        assertThat(report.get("unusedIndexWastedBytes")).isEqualTo(1024L);
        assertThat(report.get("duplicateIndexCount")).isEqualTo(1);
        // Entity read degraded, not fatal
        assertThat(report.get("pendingRecommendationCount")).isEqualTo(0);
        assertThat(report.get("pendingRecommendations")).isEqualTo(List.of());
        @SuppressWarnings("unchecked")
        List<String> degraded = (List<String>) report.get("degraded");
        assertThat(degraded).containsExactly("pendingRecommendations");
        // Still produces a score/grade/summary
        assertThat(report).containsKeys("healthScore", "healthGrade", "summary");
    }

    @Test
    void healthReport_fullyPopulated_whenEverythingHealthy() {
        when(performanceMonitoringService.getUnusedIndexes(anyString())).thenReturn(List.of());
        when(performanceMonitoringService.getDuplicateIndexes(anyString())).thenReturn(List.of());
        when(recommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(anyString(), any()))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection(anyString()))
            .thenThrow(new RuntimeException("no creds in unit test")); // stats degrade silently

        Map<String, Object> report = service.getIndexHealthReport("c1");

        assertThat(report.get("unusedIndexCount")).isEqualTo(0);
        assertThat(report.get("pendingRecommendationCount")).isEqualTo(0);
        // No degradation flag when the entity read succeeds
        assertThat(report).doesNotContainKey("degraded");
        assertThat(report.get("healthScore")).isEqualTo(100);
        assertThat(report.get("healthGrade")).isEqualTo("A");
    }
}
