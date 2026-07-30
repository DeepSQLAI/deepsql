package com.dbaagent.service;

import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.ColumnAntiPatternRepository;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.CompositeIndexRecommendationRepository;
import com.dbaagent.repository.IndexRecommendationEvidenceRepository;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.index.CompositeIndexPlanner;
import com.dbaagent.service.index.ExplainPlanEvidenceCollector;
import com.dbaagent.service.index.HypotheticalCostEstimator;
import com.dbaagent.service.index.QueryEvidenceExtractor;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class IndexRecommendationServiceTest {

    @Mock private IndexRecommendationRepository recommendationRepository;
    @Mock private AnalysisHistoryRepository analysisHistoryRepository;
    @Mock private SlowQueryHistoryRepository slowQueryHistoryRepository;
    @Mock private QueryExecutorService queryExecutorService;
    @Mock private CredentialService credentialService;
    @Mock private ConnectionService connectionService;
    @Mock private DatabaseProviderRegistry providerRegistry;
    @Mock private PerformanceMonitoringService performanceMonitoringService;
    @Mock private CompositeIndexPlanner compositeIndexPlanner;
    @Mock private ExplainPlanEvidenceCollector explainCollector;
    @Mock private IndexRecommendationEvidenceRepository evidenceRepository;
    @Mock private InferredTableRelationshipRepository inferredTableRelationshipRepository;
    @Mock private CompositeIndexRecommendationRepository compositeIndexRecommendationRepository;
    @Mock private ColumnAntiPatternRepository columnAntiPatternRepository;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private ColumnValueCacheRepository columnValueCacheRepository;
    @Mock private com.dbaagent.service.SchemaIntrospectionService schemaIntrospectionService;

    private QueryEvidenceExtractor evidenceExtractor;
    private IndexRecommendationService service;

    @BeforeEach
    void setUp() {
        evidenceExtractor = new QueryEvidenceExtractor();
        service = new IndexRecommendationService(
            recommendationRepository,
            analysisHistoryRepository,
            slowQueryHistoryRepository,
            new ObjectMapper(),
            queryExecutorService,
            credentialService,
            connectionService,
            providerRegistry,
            performanceMonitoringService,
            schemaIntrospectionService,
            evidenceExtractor,
            compositeIndexPlanner,
            explainCollector,
            evidenceRepository,
            inferredTableRelationshipRepository,
            compositeIndexRecommendationRepository,
            columnAntiPatternRepository,
            keyColumnAnalysisRepository,
            columnValueCacheRepository,
            Optional.<HypotheticalCostEstimator>empty()
        );
        // @Value fields aren't populated in a constructor-only test; supply
        // values matching application.properties defaults.
        ReflectionTestUtils.setField(service, "analysisHistoryBatch", 1000);
        ReflectionTestUtils.setField(service, "slowQueryHistoryBatch", 1000);
        ReflectionTestUtils.setField(service, "analysisLookbackDays", 30);
        ReflectionTestUtils.setField(service, "stalenessDays", 14);
    }

    @Test
    void recordRecurrenceBumpsCounterAndLastSeen() {
        LocalDateTime original = LocalDateTime.now().minusHours(3);
        IndexRecommendationEntity rec = IndexRecommendationEntity.builder()
            .connectionId("c1")
            .tableName("orders")
            .columnNames("status")
            .indexName("idx_orders_status")
            .createStatement("CREATE INDEX idx_orders_status ON orders (status);")
            .priority(IndexRecommendationEntity.Priority.MEDIUM)
            .status(IndexRecommendationEntity.Status.PENDING)
            .estimatedImpact(20)
            .reason("seed")
            .affectedQueries(1)
            .occurrenceCount(2)
            .firstSeenAt(original)
            .lastSeenAt(original)
            .build();

        rec.recordRecurrence();

        assertThat(rec.getOccurrenceCount()).isEqualTo(3);
        assertThat(rec.getLastSeenAt()).isAfter(original);
        assertThat(rec.getFirstSeenAt()).isEqualTo(original);
    }

    @Test
    void netBenefitClampsToZero() {
        IndexRecommendationEntity rec = IndexRecommendationEntity.builder()
            .connectionId("c1")
            .tableName("orders")
            .columnNames("status")
            .indexName("idx_orders_status")
            .createStatement("CREATE INDEX idx_orders_status ON orders (status);")
            .priority(IndexRecommendationEntity.Priority.HIGH)
            .status(IndexRecommendationEntity.Status.PENDING)
            .workloadScoreMs(100L)
            .writeCostScore(250L) // higher than workload
            .build();
        assertThat(rec.netBenefitMs()).isZero();

        rec.setWriteCostScore(40L);
        assertThat(rec.netBenefitMs()).isEqualTo(60L);
    }

    @Test
    void getTopRecommendationsClampsLimitAndDelegatesToRepository() {
        when(recommendationRepository.findTopPending(eq("c1"), any(Pageable.class)))
            .thenReturn(List.of());

        // Under-floor → 1
        service.getTopRecommendations("c1", 0);
        // Normal
        service.getTopRecommendations("c1", 5);
        // Over-ceiling → MAX_TOP_LIMIT
        service.getTopRecommendations("c1", 9_999);

        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        verify(recommendationRepository, org.mockito.Mockito.times(3))
            .findTopPending(eq("c1"), captor.capture());

        List<Pageable> pageables = captor.getAllValues();
        assertThat(pageables.get(0).getPageSize()).isEqualTo(1);
        assertThat(pageables.get(1).getPageSize()).isEqualTo(5);
        assertThat(pageables.get(2).getPageSize()).isEqualTo(IndexRecommendationService.MAX_TOP_LIMIT);
        pageables.forEach(p -> assertThat(p.getPageNumber()).isZero());
    }

    @Test
    void refreshRecommendationsAgesOutStalePendingRows() {
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test"));
        when(recommendationRepository.deleteStalePending(eq("c1"), any(LocalDateTime.class)))
            .thenReturn(2);
        // getStatsResetAgeSeconds isn't reached when getUnusedIndexes returns
        // empty (Mockito's default); no stub needed.

        service.refreshRecommendations("c1");

        // The legacy wipe of PENDING is NOT called — accumulator state stays.
        verify(recommendationRepository, org.mockito.Mockito.never())
            .deleteByConnectionIdAndStatus(eq("c1"), eq(IndexRecommendationEntity.Status.PENDING));
        // The staleness sweep IS called with a past cutoff.
        ArgumentCaptor<LocalDateTime> cutoffCaptor = ArgumentCaptor.forClass(LocalDateTime.class);
        verify(recommendationRepository).deleteStalePending(eq("c1"), cutoffCaptor.capture());
        assertThat(cutoffCaptor.getValue()).isBefore(LocalDateTime.now());
    }

    @Test
    void unusedIndexProbeProducesHighPriorityDropForLargeIdleIndex() {
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test"));
        // 30-day stats window → not a recent reset.
        when(performanceMonitoringService.getStatsResetAgeSeconds(anyString()))
            .thenReturn(Optional.of(30 * 86400L));

        Map<String, Object> bigUnused = new LinkedHashMap<>();
        bigUnused.put("tableName", "orders");
        bigUnused.put("indexName", "idx_orders_legacy");
        bigUnused.put("indexSizeBytes", 2_000_000_000L);
        bigUnused.put("indexSize", "1.9 GB");

        when(performanceMonitoringService.getUnusedIndexes("c1"))
            .thenReturn(List.of(bigUnused));
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), eq("orders"), anyString(), eq(IndexRecommendationEntity.Kind.DROP_INDEX)))
            .thenReturn(List.of());
        when(recommendationRepository.save(any(IndexRecommendationEntity.class)))
            .thenAnswer(invocation -> invocation.getArgument(0));

        service.generateRecommendations("c1");

        ArgumentCaptor<IndexRecommendationEntity> saved = ArgumentCaptor.forClass(IndexRecommendationEntity.class);
        verify(recommendationRepository).save(saved.capture());
        IndexRecommendationEntity rec = saved.getValue();

        assertThat(rec.getKind()).isEqualTo(IndexRecommendationEntity.Kind.DROP_INDEX);
        assertThat(rec.getTableName()).isEqualTo("orders");
        assertThat(rec.getIndexName()).isEqualTo("idx_orders_legacy");
        assertThat(rec.getCreateStatement()).startsWith("DROP INDEX idx_orders_legacy");
        assertThat(rec.getPriority()).isEqualTo(IndexRecommendationEntity.Priority.HIGH);
        assertThat(rec.getReason()).contains("not been used");
        assertThat(rec.getReason()).doesNotContain("STATS RESET");
    }

    @Test
    void unusedIndexDropIsDemotedAfterRecentStatsReset() {
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test"));
        // 2 days since reset → less than the 7-day threshold; signal is suspect.
        when(performanceMonitoringService.getStatsResetAgeSeconds(anyString()))
            .thenReturn(Optional.of(2 * 86400L));

        Map<String, Object> bigUnused = new LinkedHashMap<>();
        bigUnused.put("tableName", "orders");
        bigUnused.put("indexName", "idx_orders_legacy");
        bigUnused.put("indexSizeBytes", 2_000_000_000L);
        bigUnused.put("indexSize", "1.9 GB");

        when(performanceMonitoringService.getUnusedIndexes("c1")).thenReturn(List.of(bigUnused));
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), anyString(), anyString(), any())).thenReturn(List.of());
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateRecommendations("c1");

        ArgumentCaptor<IndexRecommendationEntity> saved = ArgumentCaptor.forClass(IndexRecommendationEntity.class);
        verify(recommendationRepository).save(saved.capture());
        IndexRecommendationEntity rec = saved.getValue();

        // Without the reset guard this would be HIGH (1.9 GiB). With it: LOW.
        assertThat(rec.getPriority()).isEqualTo(IndexRecommendationEntity.Priority.LOW);
        assertThat(rec.getReason()).contains("STATS RESET");
    }

    @Test
    void getTopRecommendationsDefaultLimitConstantIsFive() {
        assertThat(IndexRecommendationService.DEFAULT_TOP_LIMIT).isEqualTo(5);
    }

    @Test
    void getTopRecommendationsRequestsFirstPage() {
        when(recommendationRepository.findTopPending(eq("c1"), any(Pageable.class)))
            .thenReturn(List.of());

        service.getTopRecommendations("c1", IndexRecommendationService.DEFAULT_TOP_LIMIT);

        verify(recommendationRepository).findTopPending(eq("c1"), eq(PageRequest.of(0, 5)));
    }

    @Test
    void inferredJoinRelationshipsFlowIntoCandidates() {
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test"));
        // getUnusedIndexes returns default empty list; reset-age stub not needed.

        com.dbaagent.model.InferredTableRelationship rel = com.dbaagent.model.InferredTableRelationship.builder()
            .connectionId("c1")
            .sourceTable("orders")
            .sourceColumn("customer_id")
            .targetTable("customers")
            .targetColumn("id")
            .joinCount(250)
            .distinctQueryCount(12)
            .confidenceScore(new BigDecimal("95.00"))
            .build();
        when(inferredTableRelationshipRepository.findHighConfidenceRelationships(eq("c1"), any(BigDecimal.class)))
            .thenReturn(List.of(rel));
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), anyString(), anyString(), eq(IndexRecommendationEntity.Kind.CREATE_INDEX)))
            .thenReturn(List.of());
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateRecommendations("c1");

        ArgumentCaptor<IndexRecommendationEntity> saved = ArgumentCaptor.forClass(IndexRecommendationEntity.class);
        verify(recommendationRepository).save(saved.capture());
        IndexRecommendationEntity rec = saved.getValue();

        assertThat(rec.getTableName()).isEqualTo("orders");
        assertThat(rec.getColumnNames()).contains("customer_id");
        assertThat(rec.getPriority()).isEqualTo(IndexRecommendationEntity.Priority.HIGH);
        assertThat(rec.getReason()).contains("Inferred JOIN");
        assertThat(rec.getWorkloadScoreMs()).isGreaterThan(0L);
    }

    // -----------------------------------------------------------------------
    // Existing-index coverage gate — the ERECEIPT_BOOKING(booking_id)
    // regression and every adjacent corner case.
    // -----------------------------------------------------------------------

    /** Set up just enough of the pipeline so a single inferred-JOIN candidate
     *  is generated for {@code orders.<column>}. Pair with a coverage stub to
     *  exercise the redundancy filter. */
    private void seedInferredJoinCandidate(String table, String column) {
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test")); // skip schema walk

        com.dbaagent.model.InferredTableRelationship rel = com.dbaagent.model.InferredTableRelationship.builder()
            .connectionId("c1")
            .sourceTable(table)
            .sourceColumn(column)
            .targetTable("dim")
            .targetColumn("id")
            .joinCount(250)
            .distinctQueryCount(12)
            .confidenceScore(new BigDecimal("95.00"))
            .build();
        when(inferredTableRelationshipRepository.findHighConfidenceRelationships(eq("c1"), any(BigDecimal.class)))
            .thenReturn(List.of(rel));
    }

    @Test
    void redundantPrimaryKeyCandidateIsSkipped() {
        // The ERECEIPT_BOOKING(booking_id) regression: PK is already an
        // implicit index. A candidate for the same single column must not be
        // emitted as a CREATE.
        seedInferredJoinCandidate("ereceipt_booking", "booking_id");
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("ereceipt_booking", List.of(List.of("booking_id"))));

        service.generateRecommendations("c1");

        verify(recommendationRepository, org.mockito.Mockito.never())
            .save(any(IndexRecommendationEntity.class));
    }

    @Test
    void redundantUniqueIndexCandidateIsSkipped() {
        // Unique constraints become indexes — should also block a redundant CREATE.
        seedInferredJoinCandidate("users", "email");
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("users", List.of(List.of("email"))));

        service.generateRecommendations("c1");
        verify(recommendationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void singleColumnCandidateIsSkippedWhenLeadingPrefixOfComposite() {
        // Composite index (customer_id, status) already covers WHERE customer_id = ?
        // — B-tree leading-prefix optimization. Don't emit a single-column candidate.
        seedInferredJoinCandidate("orders", "customer_id");
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("orders", List.of(List.of("customer_id", "status"))));

        service.generateRecommendations("c1");
        verify(recommendationRepository, org.mockito.Mockito.never()).save(any());
    }

    @Test
    void wrongColumnOrderIsNotConsideredCovered() {
        // Existing index (a, b) does NOT cover a (b, a) candidate — different B-tree.
        // For this test we drive the candidate via inferred-join (single column).
        // A single-column candidate for `status` is NOT covered by (customer_id, status):
        // status is not the leading column.
        seedInferredJoinCandidate("orders", "status");
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("orders", List.of(List.of("customer_id", "status"))));
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), anyString(), anyString(), eq(IndexRecommendationEntity.Kind.CREATE_INDEX)))
            .thenReturn(List.of());
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateRecommendations("c1");

        ArgumentCaptor<IndexRecommendationEntity> saved = ArgumentCaptor.forClass(IndexRecommendationEntity.class);
        verify(recommendationRepository).save(saved.capture());
        assertThat(saved.getValue().getTableName()).isEqualTo("orders");
        assertThat(saved.getValue().getColumnNames()).isEqualTo("status");
    }

    @Test
    void candidateOnUncoveredTableIsAllowedThrough() {
        // Coverage map has a different table — our candidate's table isn't in it.
        seedInferredJoinCandidate("orders", "customer_id");
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("payments", List.of(List.of("user_id"))));
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), anyString(), anyString(), eq(IndexRecommendationEntity.Kind.CREATE_INDEX)))
            .thenReturn(List.of());
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateRecommendations("c1");
        verify(recommendationRepository).save(any());
    }

    @Test
    void emptyCoverageMapDoesNotBlockAnything() {
        // Probe failure → empty map. Don't drop everything; let the per-path
        // filters (schema walker) handle it. The inferred-JOIN candidate should
        // still get saved.
        seedInferredJoinCandidate("orders", "customer_id");
        when(performanceMonitoringService.getIndexCoverage("c1")).thenReturn(Map.of());
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), anyString(), anyString(), eq(IndexRecommendationEntity.Kind.CREATE_INDEX)))
            .thenReturn(List.of());
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateRecommendations("c1");
        verify(recommendationRepository).save(any());
    }

    @Test
    void partialIndexCandidateOnPrimaryKeyIsSkipped() {
        // The code_scan_source(id) regression: the skew analyzer produced a
        // partial-index candidate on a PK column. PKs serve every value in
        // O(log n) — a partial on the same column adds no value. The
        // coverage filter must skip it.
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test"));
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("code_scan_source", List.of(List.of("id"))));

        // Drive the partial-index emitter: a heavily-skewed KeyColumnAnalysis
        // row + a dominant-value cache entry, just like the production path.
        com.dbaagent.model.KeyColumnAnalysis kca = com.dbaagent.model.KeyColumnAnalysis.builder()
            .connectionId("c1")
            .tableName("code_scan_source")
            .columnName("id")
            .skewCoefficient(1.0)        // sentinel from KeyColumnAnalysisService
            .hasUnusedIndex(false)
            .indexName(null)             // looks unindexed to the per-path filter
            .importanceScore(new BigDecimal("90"))
            .build();
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("c1"))
            .thenReturn(List.of(kca));
        com.dbaagent.model.ColumnValueCache cache = com.dbaagent.model.ColumnValueCache.builder()
            .connectionId("c1")
            .tableName("code_scan_source")
            .columnName("id")
            .sampleValues("[\"42\"]")
            .build();
        when(columnValueCacheRepository.findByConnectionIdAndTableNameAndColumnName(
                eq("c1"), eq("code_scan_source"), eq("id")))
            .thenReturn(Optional.of(cache));

        service.generateRecommendations("c1");

        verify(recommendationRepository, org.mockito.Mockito.never())
            .save(any(IndexRecommendationEntity.class));
    }

    @Test
    void dropIndexCandidateIsNotFilteredEvenIfPresentInCoverage() {
        // DROP_INDEX recommendations are precisely about indexes that DO exist —
        // the coverage filter must not block them.
        when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class), any(Pageable.class)))
            .thenReturn(List.of());
        when(credentialService.getDecryptedConnection("c1"))
            .thenThrow(new RuntimeException("no creds in unit test"));
        when(performanceMonitoringService.getStatsResetAgeSeconds(anyString()))
            .thenReturn(Optional.of(30 * 86400L)); // long stats window

        Map<String, Object> bigUnused = new LinkedHashMap<>();
        bigUnused.put("tableName", "orders");
        bigUnused.put("indexName", "idx_orders_legacy");
        bigUnused.put("indexSizeBytes", 2_000_000_000L);
        bigUnused.put("indexSize", "1.9 GB");
        when(performanceMonitoringService.getUnusedIndexes("c1")).thenReturn(List.of(bigUnused));

        // Coverage map "knows about" this index — but DROP recs are exempt.
        when(performanceMonitoringService.getIndexCoverage("c1"))
            .thenReturn(Map.of("orders", List.of(List.of("idx_orders_legacy"))));
        when(recommendationRepository.findByConnectionIdAndTableNameAndColumnNamesAndKind(
                eq("c1"), anyString(), anyString(), eq(IndexRecommendationEntity.Kind.DROP_INDEX)))
            .thenReturn(List.of());
        when(recommendationRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        service.generateRecommendations("c1");

        ArgumentCaptor<IndexRecommendationEntity> saved = ArgumentCaptor.forClass(IndexRecommendationEntity.class);
        verify(recommendationRepository).save(saved.capture());
        assertThat(saved.getValue().getKind()).isEqualTo(IndexRecommendationEntity.Kind.DROP_INDEX);
    }
}
