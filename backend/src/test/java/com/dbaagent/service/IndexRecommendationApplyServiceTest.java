package com.dbaagent.service;

import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.IndexRecommendationEvidence;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.IndexRecommendationEvidenceRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.service.index.HypotheticalCostEstimator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexRecommendationApplyServiceTest {

    @Mock private IndexRecommendationRepository recommendationRepository;
    @Mock private IndexRecommendationEvidenceRepository evidenceRepository;
    @Mock private ConnectionService connectionService;
    @Mock private CredentialService credentialService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private HypotheticalCostEstimator hypotheticalCostEstimator;

    private IndexRecommendationApplyService applyService;

    @BeforeEach
    void setUp() {
        applyService = new IndexRecommendationApplyService(
            recommendationRepository,
            evidenceRepository,
            connectionService,
            credentialService,
            providerRegistry,
            Optional.of(hypotheticalCostEstimator)
        );
    }

    @Test
    void applyModeWithoutConfirmIsBlocked() {
        IndexRecommendationEntity rec = IndexRecommendationEntity.builder()
            .id("r1")
            .connectionId("c1")
            .tableName("orders")
            .columnNames("status")
            .indexName("idx_orders_status")
            .createStatement("CREATE INDEX idx_orders_status ON orders (status);")
            .kind(IndexRecommendationEntity.Kind.CREATE_INDEX)
            .priority(IndexRecommendationEntity.Priority.HIGH)
            .status(IndexRecommendationEntity.Status.PENDING)
            .build();
        when(recommendationRepository.findById("r1")).thenReturn(Optional.of(rec));

        IndexRecommendationApplyService.ApplyResult result =
            applyService.apply("r1", IndexRecommendationApplyService.Mode.APPLY, false);

        assertThat(result.status()).isEqualTo(IndexRecommendationApplyService.Status.BLOCKED_NEEDS_CONFIRMATION);
        assertThat(result.message()).contains("confirm=true");
        // No DB connection acquired, no DDL executed.
    }

    @Test
    void notFoundReturnsNotFoundStatus() {
        when(recommendationRepository.findById("missing")).thenReturn(Optional.empty());

        IndexRecommendationApplyService.ApplyResult result =
            applyService.apply("missing", IndexRecommendationApplyService.Mode.DRY_RUN, false);

        assertThat(result.status()).isEqualTo(IndexRecommendationApplyService.Status.NOT_FOUND);
    }

    @Test
    void noUsableSamplesReturnsClearStatus() {
        IndexRecommendationEntity rec = IndexRecommendationEntity.builder()
            .id("r1")
            .connectionId("c1")
            .tableName("orders")
            .columnNames("status")
            .indexName("idx_orders_status")
            .createStatement("CREATE INDEX idx_orders_status ON orders (status);")
            .kind(IndexRecommendationEntity.Kind.CREATE_INDEX)
            .priority(IndexRecommendationEntity.Priority.HIGH)
            .status(IndexRecommendationEntity.Status.PENDING)
            .build();
        when(recommendationRepository.findById("r1")).thenReturn(Optional.of(rec));

        // Only parameterised samples — can't EXPLAIN them.
        IndexRecommendationEvidence ev = IndexRecommendationEvidence.builder()
            .recommendationId("r1")
            .queryFingerprint("abc")
            .exampleSql("SELECT * FROM orders WHERE status = $1")
            .calls(100L)
            .meanExecTimeMs(10.0)
            .totalExecTimeMs(1000.0)
            .role("WHERE_EQ")
            .build();
        when(evidenceRepository.findByRecommendationIdOrderByTotalExecTimeMsDesc(eq("r1"), any(Pageable.class)))
            .thenReturn(List.of(ev));

        IndexRecommendationApplyService.ApplyResult result =
            applyService.apply("r1", IndexRecommendationApplyService.Mode.DRY_RUN, false);

        assertThat(result.status()).isEqualTo(IndexRecommendationApplyService.Status.NO_USABLE_SAMPLES);
    }

    @Test
    void dryRunWithoutUsableSamplesDoesNotMutateTheDatabase() {
        // We never reach the connection-acquisition step, so no write can leak.
        IndexRecommendationEntity rec = IndexRecommendationEntity.builder()
            .id("r1")
            .connectionId("c1")
            .tableName("orders")
            .columnNames("status")
            .indexName("idx_orders_status")
            .createStatement("CREATE INDEX idx_orders_status ON orders (status);")
            .kind(IndexRecommendationEntity.Kind.CREATE_INDEX)
            .priority(IndexRecommendationEntity.Priority.HIGH)
            .status(IndexRecommendationEntity.Status.PENDING)
            .build();
        when(recommendationRepository.findById("r1")).thenReturn(Optional.of(rec));
        when(evidenceRepository.findByRecommendationIdOrderByTotalExecTimeMsDesc(anyString(), any(Pageable.class)))
            .thenReturn(List.of());

        IndexRecommendationApplyService.ApplyResult result =
            applyService.apply("r1", IndexRecommendationApplyService.Mode.DRY_RUN, false);

        assertThat(result.status()).isEqualTo(IndexRecommendationApplyService.Status.NO_USABLE_SAMPLES);
        // Critically: rec was not marked APPLIED.
        assertThat(rec.getStatus()).isEqualTo(IndexRecommendationEntity.Status.PENDING);
    }
}
