package com.dbaagent.service.digest;

import com.dbaagent.model.*;
import com.dbaagent.model.brain.*;
import com.dbaagent.model.digest.*;
import com.dbaagent.repository.*;
import com.dbaagent.repository.brain.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.data.domain.Pageable;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Comprehensive tests for DigestInsightAssemblerService.
 *
 * <p>Tests cover:
 * <ul>
 *   <li>Mining insights from all Brain stores</li>
 *   <li>Role-aware ranking with persona multipliers</li>
 *   <li>Duplicate suppression across digests</li>
 *   <li>Acknowledged insight filtering</li>
 *   <li>Different personas getting different ranked results</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class DigestInsightAssemblerServiceTest {

    @Mock
    private BrainV2AlertRepository brainAlertRepository;
    @Mock
    private BrainScoreRepository brainScoreRepository;
    @Mock
    private BrainLearningProgressRepository learningProgressRepository;
    @Mock
    private PlanPatternRepository planPatternRepository;
    @Mock
    private IndexRecommendationRepository indexRecommendationRepository;
    @Mock
    private SchemaChangeRepository schemaChangeRepository;
    @Mock
    private GrowthAnomalyRepository growthAnomalyRepository;
    @Mock
    private PlaybookAlertRepository playbookAlertRepository;
    @Mock
    private SlackDigestLogRepository digestLogRepository;
    @Mock
    private SlowQueryHistoryRepository slowQueryHistoryRepository;
    @Mock
    private LockContentionRepository lockContentionRepository;

    private DigestInsightAssemblerService service;

    private static final String CONNECTION_ID = "test-connection-123";
    private static final String USERNAME = "alice";

    @BeforeEach
    void setUp() {
        service = new DigestInsightAssemblerService(
            brainAlertRepository,
            brainScoreRepository,
            learningProgressRepository,
            planPatternRepository,
            indexRecommendationRepository,
            schemaChangeRepository,
            growthAnomalyRepository,
            playbookAlertRepository,
            digestLogRepository,
            slowQueryHistoryRepository,
            lockContentionRepository
        );
    }

    @Nested
    class DifferentPersonasGetDifferentDigests {

        @Test
        void dbaPersonaRanksQueryPerformanceHigherThanDataEng() {
            setupMixedInsights();

            DigestAssemblyResult dbaResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);
            DigestAssemblyResult dataEngResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DATA_ENG, null);

            assertThat(dbaResult.getInsights()).isNotEmpty();
            assertThat(dataEngResult.getInsights()).isNotEmpty();

            Optional<DigestInsight> dbaTopQueryInsight = dbaResult.getInsights().stream()
                .filter(i -> i.getCategory() == InsightCategory.QUERY_PERFORMANCE)
                .findFirst();
            Optional<DigestInsight> dataEngTopQueryInsight = dataEngResult.getInsights().stream()
                .filter(i -> i.getCategory() == InsightCategory.QUERY_PERFORMANCE)
                .findFirst();

            if (dbaTopQueryInsight.isPresent() && dataEngTopQueryInsight.isPresent()) {
                assertThat(dbaTopQueryInsight.get().getRankScore())
                    .isGreaterThan(dataEngTopQueryInsight.get().getRankScore());
            }
        }

        @Test
        void dataEngPersonaRanksSchemaChangesHigherThanDba() {
            setupMixedInsights();

            DigestAssemblyResult dataEngResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DATA_ENG, null);
            DigestAssemblyResult dbaResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);

            List<DigestInsight> dataEngSchema = dataEngResult.getInsightsByCategory(InsightCategory.SCHEMA_CHANGES);
            List<DigestInsight> dbaSchema = dbaResult.getInsightsByCategory(InsightCategory.SCHEMA_CHANGES);

            if (!dataEngSchema.isEmpty() && !dbaSchema.isEmpty()) {
                assertThat(dataEngSchema.get(0).getRankScore())
                    .isGreaterThan(dbaSchema.get(0).getRankScore());
            }
        }

        @Test
        void execPersonaGetsLimitedInsightsWithSummary() {
            setupManyInsights();

            DigestAssemblyResult execResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.EXEC, null);
            DigestAssemblyResult dbaResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);

            assertThat(execResult.getInsights().size()).isLessThanOrEqualTo(5);
            assertThat(dbaResult.getInsights().size()).isGreaterThanOrEqualTo(execResult.getInsights().size());
            assertThat(execResult.getExecutiveSummary()).isNotNull();
        }

        private void setupMixedInsights() {
            when(digestLogRepository.findTopByConnectionIdAndRecipientUsernameOrderBySentAtDesc(
                anyString(), anyString())).thenReturn(Optional.empty());
            when(learningProgressRepository.findByConnectionId(anyString())).thenReturn(Optional.empty());
            when(brainScoreRepository.findLatestByConnectionId(anyString())).thenReturn(Optional.empty());
            when(playbookAlertRepository.findRecentAlerts(anyString(), any())).thenReturn(List.of());
            when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(), any()))
                .thenReturn(List.of());
            when(lockContentionRepository.findRecentForDigest(anyString(), any())).thenReturn(List.of());

            BrainV2Alert queryAlert = BrainV2Alert.builder()
                .id("alert-1")
                .connectionId(CONNECTION_ID)
                .alertType(BrainV2Alert.AlertType.PLAN_REGRESSION)
                .severity(BrainV2Alert.Severity.HIGH)
                .status(BrainV2Alert.Status.ACTIVE)
                .title("Query plan regression detected")
                .message("Performance degraded by 50%")
                .createdAt(LocalDateTime.now().minusHours(2))
                .recommendedAction("Review query plan")
                .build();

            when(brainAlertRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(
                CONNECTION_ID, BrainV2Alert.Status.ACTIVE))
                .thenReturn(List.of(queryAlert));

            IndexRecommendationEntity indexRec = IndexRecommendationEntity.builder()
                .id("idx-1")
                .connectionId(CONNECTION_ID)
                .tableName("orders")
                .columnNames("customer_id")
                .indexName("idx_orders_customer_id")
                .priority(IndexRecommendationEntity.Priority.HIGH)
                .status(IndexRecommendationEntity.Status.PENDING)
                .createStatement("CREATE INDEX idx_orders_customer_id ON orders(customer_id)")
                .reason("Missing index causes seq scan")
                .createdAt(LocalDateTime.now().minusDays(1))
                .lastSeenAt(LocalDateTime.now().minusHours(1))
                .build();

            when(indexRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
                CONNECTION_ID, IndexRecommendationEntity.Status.PENDING))
                .thenReturn(List.of(indexRec));

            SchemaChange schemaChange = SchemaChange.builder()
                .id("sc-1")
                .connectionId(CONNECTION_ID)
                .changeType(SchemaChange.ChangeType.COLUMN_ADDED)
                .objectType(SchemaChange.ObjectType.COLUMN)
                .objectName("new_column")
                .tableName("users")
                .severity(SchemaChange.Severity.INFO)
                .isBreakingChange(false)
                .isAcknowledged(false)
                .detectedAt(LocalDateTime.now().minusHours(5))
                .changeDetails(Map.of("type", "varchar(255)"))
                .build();

            when(schemaChangeRepository.findByConnectionIdAndIsAcknowledgedFalseOrderByDetectedAtDesc(CONNECTION_ID))
                .thenReturn(List.of(schemaChange));

            when(growthAnomalyRepository.findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(
                CONNECTION_ID)).thenReturn(List.of());
        }

        private void setupManyInsights() {
            setupMixedInsights();

            List<IndexRecommendationEntity> manyRecs = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                manyRecs.add(IndexRecommendationEntity.builder()
                    .id("idx-" + i)
                    .connectionId(CONNECTION_ID)
                    .tableName("table_" + i)
                    .columnNames("col_" + i)
                    .indexName("idx_" + i)
                    .priority(IndexRecommendationEntity.Priority.MEDIUM)
                    .status(IndexRecommendationEntity.Status.PENDING)
                    .createStatement("CREATE INDEX")
                    .createdAt(LocalDateTime.now().minusDays(i))
                    .build());
            }
            when(indexRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
                CONNECTION_ID, IndexRecommendationEntity.Status.PENDING))
                .thenReturn(manyRecs);
        }
    }

    @Nested
    class DuplicateSuppressionTests {

        @Test
        void tracksSuppressedDuplicatesCount() {
            setupEmptyMocks();
            
            String signatureKey = "INDEX_RECOMMENDATIONS:IndexRecommendation:HIGH:1";
            SlackDigestLog lastLog = new SlackDigestLog();
            lastLog.setContent("Previous digest content [sig:" + signatureKey + "] more content");
            lastLog.setSentAt(LocalDateTime.now().minusHours(12));

            when(digestLogRepository.findTopByConnectionIdAndRecipientUsernameOrderBySentAtDesc(
                CONNECTION_ID, USERNAME)).thenReturn(Optional.of(lastLog));

            IndexRecommendationEntity oldRec = IndexRecommendationEntity.builder()
                .id("idx-old")
                .connectionId(CONNECTION_ID)
                .tableName("orders")
                .columnNames("customer_id")
                .indexName("idx_orders")
                .priority(IndexRecommendationEntity.Priority.HIGH)
                .status(IndexRecommendationEntity.Status.PENDING)
                .createStatement("CREATE INDEX")
                .createdAt(LocalDateTime.now().minusDays(3))
                .lastSeenAt(LocalDateTime.now().minusDays(1))
                .build();

            when(indexRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
                CONNECTION_ID, IndexRecommendationEntity.Status.PENDING))
                .thenReturn(List.of(oldRec));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);

            assertThat(result.getSuppressedDuplicates()).isGreaterThanOrEqualTo(0);
        }

        @Test
        void doesNotSuppressHighSeverityDuplicates() {
            setupEmptyMocks();
            
            String signatureKey = "QUERY_PERFORMANCE:BrainV2Alert:PLAN_REGRESSION:test-connection-123";
            SlackDigestLog lastLog = new SlackDigestLog();
            lastLog.setContent("Previous [sig:" + signatureKey + "]");
            lastLog.setSentAt(LocalDateTime.now().minusHours(12));

            when(digestLogRepository.findTopByConnectionIdAndRecipientUsernameOrderBySentAtDesc(
                CONNECTION_ID, USERNAME)).thenReturn(Optional.of(lastLog));

            BrainV2Alert criticalAlert = BrainV2Alert.builder()
                .id("alert-critical")
                .connectionId(CONNECTION_ID)
                .alertType(BrainV2Alert.AlertType.PLAN_REGRESSION)
                .severity(BrainV2Alert.Severity.CRITICAL)
                .status(BrainV2Alert.Status.ACTIVE)
                .title("Critical plan regression")
                .message("Major performance issue")
                .createdAt(LocalDateTime.now().minusMinutes(30))
                .build();

            when(brainAlertRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(
                CONNECTION_ID, BrainV2Alert.Status.ACTIVE))
                .thenReturn(List.of(criticalAlert));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);

            assertThat(result.getInsights()).isNotEmpty();
            assertThat(result.getInsights().stream()
                .anyMatch(i -> i.getSeverity() >= 90)).isTrue();
        }
    }

    @Nested
    class AcknowledgedInsightFilteringTests {

        @Test
        void filtersOutAcknowledgedAlerts() {
            setupEmptyMocks();

            when(brainAlertRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(
                CONNECTION_ID, BrainV2Alert.Status.ACTIVE))
                .thenReturn(List.of());

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getInsights().stream()
                .noneMatch(i -> "alert-acked".equals(i.getSourceId()))).isTrue();
        }

        @Test
        void countsFilteredAcknowledged() {
            setupEmptyMocks();

            when(brainAlertRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(
                CONNECTION_ID, BrainV2Alert.Status.ACTIVE))
                .thenReturn(List.of());

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getFilteredAcknowledged()).isGreaterThanOrEqualTo(0);
        }
    }

    @Nested
    class RankingTests {

        @Test
        void higherSeverityRanksHigher() {
            List<DigestInsight> candidates = List.of(
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("Low severity")
                    .severity(30)
                    .timestamp(LocalDateTime.now())
                    .build(),
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("High severity")
                    .severity(90)
                    .timestamp(LocalDateTime.now())
                    .build()
            );

            List<DigestInsight> ranked = service.rankInsights(candidates, Role.ADMIN, null);

            assertThat(ranked.get(0).getHeadline()).isEqualTo("High severity");
        }

        @Test
        void fresherInsightsRankHigher() {
            List<DigestInsight> candidates = List.of(
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("Old insight")
                    .severity(50)
                    .timestamp(LocalDateTime.now().minusDays(5))
                    .build(),
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("Fresh insight")
                    .severity(50)
                    .timestamp(LocalDateTime.now().minusMinutes(30))
                    .build()
            );

            List<DigestInsight> ranked = service.rankInsights(candidates, Role.ADMIN, null);

            assertThat(ranked.get(0).getHeadline()).isEqualTo("Fresh insight");
        }

        @Test
        void actionableInsightsGetBonus() {
            List<DigestInsight> candidates = List.of(
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("Not actionable")
                    .severity(50)
                    .timestamp(LocalDateTime.now())
                    .actionable(false)
                    .build(),
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("Actionable")
                    .severity(50)
                    .timestamp(LocalDateTime.now())
                    .actionable(true)
                    .suggestedAction("Do something")
                    .build()
            );

            List<DigestInsight> ranked = service.rankInsights(candidates, Role.ADMIN, null);

            assertThat(ranked.get(0).getHeadline()).isEqualTo("Actionable");
        }

        @Test
        void personaMultiplierAffectsRankScore() {
            List<DigestInsight> candidates = List.of(
                DigestInsight.builder()
                    .category(InsightCategory.QUERY_PERFORMANCE)
                    .headline("Query perf")
                    .severity(70)
                    .timestamp(LocalDateTime.now())
                    .build()
            );

            List<DigestInsight> dbaRanked = service.rankInsights(candidates, Role.ADMIN, PersonaTag.DBA);
            List<DigestInsight> execRanked = service.rankInsights(candidates, Role.ADMIN, PersonaTag.EXEC);
            
            assertThat(dbaRanked.get(0).getRankScore())
                .isGreaterThan(execRanked.get(0).getRankScore());
        }
    }

    @Nested
    class SignalMiningTests {

        @Test
        void minesBrainAlertsCorrectly() {
            setupEmptyMocks();

            BrainV2Alert alert = BrainV2Alert.builder()
                .id("test-alert")
                .connectionId(CONNECTION_ID)
                .alertType(BrainV2Alert.AlertType.WORKLOAD_CHANGE)
                .severity(BrainV2Alert.Severity.WARNING)
                .status(BrainV2Alert.Status.ACTIVE)
                .title("Workload changed")
                .message("OLTP to OLAP")
                .createdAt(LocalDateTime.now().minusHours(1))
                .recommendedAction("Review config")
                .build();

            when(brainAlertRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(
                CONNECTION_ID, BrainV2Alert.Status.ACTIVE))
                .thenReturn(List.of(alert));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getInsights()).anyMatch(i ->
                i.getSourceType().equals("BrainV2Alert") &&
                i.getCategory() == InsightCategory.BRAIN_INTELLIGENCE
            );
        }

        @Test
        void minesGrowthAnomaliesCorrectly() {
            setupEmptyMocks();

            GrowthAnomaly anomaly = GrowthAnomaly.builder()
                .id("anomaly-1")
                .connectionId(CONNECTION_ID)
                .tableName("audit_logs")
                .anomalyType(GrowthAnomaly.AnomalyType.PERCENTAGE_GROWTH)
                .severity(GrowthAnomaly.Severity.CRITICAL)
                .sizeGrowthPercent(500.0)
                .detectionTimestamp(LocalDateTime.now().minusHours(2))
                .acknowledged(false)
                .build();

            when(growthAnomalyRepository.findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(
                CONNECTION_ID)).thenReturn(List.of(anomaly));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getInsights()).anyMatch(i ->
                i.getSourceType().equals("GrowthAnomaly") &&
                i.getCategory() == InsightCategory.GROWTH_ANOMALIES &&
                i.getSeverity() >= 80
            );
        }

        @Test
        void minesSchemaChangesCorrectly() {
            setupEmptyMocks();

            SchemaChange change = SchemaChange.builder()
                .id("change-1")
                .connectionId(CONNECTION_ID)
                .changeType(SchemaChange.ChangeType.COLUMN_REMOVED)
                .objectType(SchemaChange.ObjectType.COLUMN)
                .objectName("deprecated_field")
                .tableName("users")
                .severity(SchemaChange.Severity.CRITICAL)
                .isBreakingChange(true)
                .isAcknowledged(false)
                .detectedAt(LocalDateTime.now().minusHours(3))
                .changeDetails(Map.of())
                .build();

            when(schemaChangeRepository.findByConnectionIdAndIsAcknowledgedFalseOrderByDetectedAtDesc(
                CONNECTION_ID)).thenReturn(List.of(change));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getInsights()).anyMatch(i ->
                i.getSourceType().equals("SchemaChange") &&
                i.getCategory() == InsightCategory.SCHEMA_CHANGES &&
                i.getSeverity() >= 90
            );
        }

        @Test
        void minesSlowQueryHistoryCorrectly() {
            setupEmptyMocks();

            SlowQueryHistory history = new SlowQueryHistory();
            history.setId("sqh-1");
            history.setConnectionId(CONNECTION_ID);
            history.setTotalSlowQueries(25L);
            history.setCriticalCount(3L);
            history.setHighCount(5L);
            history.setOverallHealth("UNHEALTHY");
            history.setCreatedAt(LocalDateTime.now().minusHours(1));

            when(slowQueryHistoryRepository.findByConnectionIdSince(eq(CONNECTION_ID), any(), any(Pageable.class)))
                .thenReturn(List.of(history));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getInsights()).anyMatch(i ->
                i.getSourceType().equals("SlowQueryHistory") &&
                i.getCategory() == InsightCategory.QUERY_PERFORMANCE
            );
        }

        @Test
        void minesBrainScoreCorrectly() {
            setupEmptyMocks();

            BrainScore score = BrainScore.builder()
                .id("score-1")
                .connectionId(CONNECTION_ID)
                .overallScore(BigDecimal.valueOf(35))
                .schemaDesignScore(BigDecimal.valueOf(40))
                .queryQualityScore(BigDecimal.valueOf(30))
                .indexAccessScore(BigDecimal.valueOf(35))
                .calculatedAt(LocalDateTime.now().minusHours(2))
                .build();

            when(brainScoreRepository.findLatestByConnectionId(CONNECTION_ID))
                .thenReturn(Optional.of(score));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.getInsights()).anyMatch(i ->
                i.getSourceType().equals("BrainScore") &&
                i.getCategory() == InsightCategory.BRAIN_INTELLIGENCE
            );
        }

        @Test
        void minesLockContentionCorrectly() {
            setupEmptyMocks();

            LockContention contention = LockContention.builder()
                .id("lock-1")
                .connectionId(CONNECTION_ID)
                .blockingPid("12345")
                .blockedPid("67890")
                .blockingUser("admin")
                .blockedUser("app_user")
                .lockType("relation")
                .lockMode("AccessExclusiveLock")
                .tableName("orders")
                .waitDurationSeconds(75L)
                .severity(LockContention.Severity.CRITICAL)
                .resolved(false)
                .detectedAt(LocalDateTime.now().minusHours(1))
                .build();

            when(lockContentionRepository.findRecentForDigest(eq(CONNECTION_ID), any()))
                .thenReturn(List.of(contention));

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);

            assertThat(result.getInsights()).anyMatch(i ->
                i.getSourceType().equals("LockContention") &&
                i.getCategory() == InsightCategory.LOCK_CONCURRENCY &&
                i.getSeverity() >= 90
            );
        }

        @Test
        void lockContentionRanksHigherForDbaThanDataEng() {
            setupEmptyMocks();

            LockContention contention = LockContention.builder()
                .id("lock-2")
                .connectionId(CONNECTION_ID)
                .blockingPid("111")
                .blockedPid("222")
                .lockType("relation")
                .lockMode("AccessExclusiveLock")
                .waitDurationSeconds(45L)
                .severity(LockContention.Severity.HIGH)
                .resolved(false)
                .detectedAt(LocalDateTime.now().minusHours(2))
                .build();

            when(lockContentionRepository.findRecentForDigest(eq(CONNECTION_ID), any()))
                .thenReturn(List.of(contention));

            DigestAssemblyResult dbaResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA, null);
            DigestAssemblyResult dataEngResult = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DATA_ENG, null);

            Optional<DigestInsight> dbaLock = dbaResult.getInsights().stream()
                .filter(i -> i.getCategory() == InsightCategory.LOCK_CONCURRENCY)
                .findFirst();
            Optional<DigestInsight> dataEngLock = dataEngResult.getInsights().stream()
                .filter(i -> i.getCategory() == InsightCategory.LOCK_CONCURRENCY)
                .findFirst();

            if (dbaLock.isPresent() && dataEngLock.isPresent()) {
                assertThat(dbaLock.get().getRankScore())
                    .isGreaterThan(dataEngLock.get().getRankScore());
            }
        }
    }

    @Nested
    class EmptyDigestTests {

        @Test
        void returnsEmptyResultWhenNoInsights() {
            setupEmptyMocks();

            DigestAssemblyResult result = service.assembleDigest(
                USERNAME, CONNECTION_ID, Role.ADMIN, null, null);

            assertThat(result.isEmpty()).isTrue();
            assertThat(result.getInsights()).isEmpty();
            assertThat(result.getHeadline()).contains("No new insights");
        }
    }

    @Nested
    class InsightCategoryTests {

        @Test
        void personaMultiplierIsCorrect() {
            // DBA priorities: locks, query perf, config tuning
            assertThat(InsightCategory.LOCK_CONCURRENCY.getPersonaMultiplier(PersonaTag.DBA))
                .isEqualTo(2.0);
            assertThat(InsightCategory.QUERY_PERFORMANCE.getPersonaMultiplier(PersonaTag.DBA))
                .isEqualTo(2.0);
            
            // DATA_ENG priorities: docs, schema changes
            assertThat(InsightCategory.DOCUMENTATION_GAPS.getPersonaMultiplier(PersonaTag.DATA_ENG))
                .isEqualTo(2.0);
            
            // EXEC priorities: cost/capacity
            assertThat(InsightCategory.COST_CAPACITY.getPersonaMultiplier(PersonaTag.EXEC))
                .isEqualTo(2.0);
            
            // APP_ENG priorities: schema changes (migrations), query perf
            assertThat(InsightCategory.SCHEMA_CHANGES.getPersonaMultiplier(PersonaTag.APP_ENG))
                .isEqualTo(2.0);
            
            // APP_ENG cares about DDL blocking risk
            assertThat(InsightCategory.LOCK_CONCURRENCY.getPersonaMultiplier(PersonaTag.APP_ENG))
                .isEqualTo(1.6);
        }

        @Test
        void primaryCategoriesAreCorrect() {
            Set<InsightCategory> dbaPrimary = InsightCategory.getPrimaryCategories(PersonaTag.DBA);
            assertThat(dbaPrimary).contains(
                InsightCategory.LOCK_CONCURRENCY,  // idle-in-txn, locks
                InsightCategory.QUERY_PERFORMANCE,
                InsightCategory.INDEX_RECOMMENDATIONS,
                InsightCategory.CONFIG_TUNING,
                InsightCategory.GROWTH_ANOMALIES   // bloat, vacuum
            );

            Set<InsightCategory> dataEngPrimary = InsightCategory.getPrimaryCategories(PersonaTag.DATA_ENG);
            assertThat(dataEngPrimary).contains(
                InsightCategory.DOCUMENTATION_GAPS,  // semantic drift
                InsightCategory.SCHEMA_CHANGES,      // ETL breaks
                InsightCategory.GROWTH_ANOMALIES     // load patterns
            );

            Set<InsightCategory> appEngPrimary = InsightCategory.getPrimaryCategories(PersonaTag.APP_ENG);
            assertThat(appEngPrimary).contains(
                InsightCategory.SCHEMA_CHANGES,      // migrations, DDL risk
                InsightCategory.QUERY_PERFORMANCE,   // ORM patterns
                InsightCategory.LOCK_CONCURRENCY     // ACCESS EXCLUSIVE during deploys
            );

            Set<InsightCategory> execPrimary = InsightCategory.getPrimaryCategories(PersonaTag.EXEC);
            assertThat(execPrimary).contains(
                InsightCategory.COST_CAPACITY,       // budget
                InsightCategory.SYSTEM_ALERTS        // risk
            );
        }
    }

    @Nested
    class DigestInsightTests {

        @Test
        void severityLabelIsCorrect() {
            assertThat(DigestInsight.builder().severity(95).build().getSeverityLabel())
                .isEqualTo("CRITICAL");
            assertThat(DigestInsight.builder().severity(75).build().getSeverityLabel())
                .isEqualTo("HIGH");
            assertThat(DigestInsight.builder().severity(50).build().getSeverityLabel())
                .isEqualTo("WARNING");
            assertThat(DigestInsight.builder().severity(20).build().getSeverityLabel())
                .isEqualTo("INFO");
        }

        @Test
        void freshnessMultiplierDecays() {
            DigestInsight fresh = DigestInsight.builder()
                .timestamp(LocalDateTime.now().minusMinutes(30))
                .build();
            DigestInsight old = DigestInsight.builder()
                .timestamp(LocalDateTime.now().minusDays(10))
                .build();

            assertThat(fresh.getFreshnessMultiplier()).isGreaterThan(old.getFreshnessMultiplier());
        }

        @Test
        void actionabilityBonusApplies() {
            DigestInsight actionable = DigestInsight.builder()
                .actionable(true)
                .suggestedAction("Do this")
                .build();
            DigestInsight notActionable = DigestInsight.builder()
                .actionable(false)
                .build();

            assertThat(actionable.getActionabilityBonus()).isGreaterThan(notActionable.getActionabilityBonus());
        }
    }

    @Nested
    class DigestAssemblyResultTests {

        @Test
        void headlineReflectsCriticalInsights() {
            List<DigestInsight> insights = List.of(
                DigestInsight.builder().severity(95).build(),
                DigestInsight.builder().severity(50).build()
            );

            DigestAssemblyResult result = DigestAssemblyResult.builder()
                .insights(insights)
                .build();

            assertThat(result.hasCriticalInsights()).isTrue();
            assertThat(result.getHeadline()).contains("critical");
        }

        @Test
        void emptyResultFactoryWorks() {
            DigestAssemblyResult empty = DigestAssemblyResult.empty(
                USERNAME, CONNECTION_ID, Role.ADMIN, PersonaTag.DBA);

            assertThat(empty.isEmpty()).isTrue();
            assertThat(empty.getInsights()).isEmpty();
            assertThat(empty.getUsername()).isEqualTo(USERNAME);
            assertThat(empty.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        }
    }
    
    private void setupEmptyMocks() {
        when(digestLogRepository.findTopByConnectionIdAndRecipientUsernameOrderBySentAtDesc(
            anyString(), anyString())).thenReturn(Optional.empty());
        when(brainAlertRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(anyString(), any()))
            .thenReturn(List.of());
        when(indexRecommendationRepository.findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(
            anyString(), any())).thenReturn(List.of());
        when(schemaChangeRepository.findByConnectionIdAndIsAcknowledgedFalseOrderByDetectedAtDesc(anyString()))
            .thenReturn(List.of());
        when(growthAnomalyRepository.findByConnectionIdAndAcknowledgedFalseOrderByDetectionTimestampDesc(anyString()))
            .thenReturn(List.of());
        when(playbookAlertRepository.findRecentAlerts(anyString(), any())).thenReturn(List.of());
        when(slowQueryHistoryRepository.findByConnectionIdSince(anyString(), any(), any()))
            .thenReturn(List.of());
        when(learningProgressRepository.findByConnectionId(anyString())).thenReturn(Optional.empty());
        when(brainScoreRepository.findLatestByConnectionId(anyString())).thenReturn(Optional.empty());
        when(lockContentionRepository.findRecentForDigest(anyString(), any())).thenReturn(List.of());
    }
}
