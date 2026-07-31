package com.dbaagent.service;

import com.dbaagent.model.AuthLoginChallenge;
import com.dbaagent.model.ConnectionAccessGrant;
import com.dbaagent.model.ConnectionAccessLevel;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.DatabaseEvent;
import com.dbaagent.model.LockContention;
import com.dbaagent.model.QueryFingerprint;
import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.TableStatsHistory;
import com.dbaagent.repository.AuthLoginChallengeRepository;
import com.dbaagent.repository.CapacityForecastRepository;
import com.dbaagent.repository.ConnectionAccessGrantRepository;
import com.dbaagent.repository.DatabaseEventRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.LockContentionRepository;
import com.dbaagent.repository.QueryFingerprintRepository;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SlackChannelBindingRepository;
import com.dbaagent.repository.SlackDigestLogRepository;
import com.dbaagent.repository.TableStatsHistoryRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SlackDailyDigestServiceTest {

    @Mock private SlackRuntimeSettingsService slackRuntimeSettingsService;
    @Mock private SlackChannelBindingRepository channelBindingRepository;
    @Mock private CredentialService credentialService;
    @Mock private ConnectionService connectionService;
    @Mock private PerformanceInsightsService performanceInsightsService;
    @Mock private SlowQueryService slowQueryService;
    @Mock private SlowQueryHistoryService slowQueryHistoryService;
    @Mock private SlowQueryInsightsService slowQueryInsightsService;
    @Mock private SlowQueryAnalyticsService slowQueryAnalyticsService;
    @Mock private PerformanceActionAggregatorService actionAggregatorService;
    @Mock private EnhancedSqlParserService sqlParserService;
    @Mock private QueryExecutorService queryExecutorService;
    @Mock private TableGrowthMonitoringService tableGrowthMonitoringService;
    @Mock private SchemaChangeTrackingService schemaChangeTrackingService;
    @Mock private TableStatsHistoryRepository tableStatsHistoryRepository;
    @Mock private GrowthAnomalyRepository growthAnomalyRepository;
    @Mock private CapacityForecastRepository capacityForecastRepository;
    @Mock private SchemaChangeRepository schemaChangeRepository;
    @Mock private SlackDigestLogRepository digestLogRepository;
    @Mock private SlackUserLinkService slackUserLinkService;
    @Mock private LockContentionRepository lockContentionRepository;
    @Mock private QueryFingerprintRepository queryFingerprintRepository;
    @Mock private DatabaseEventRepository databaseEventRepository;
    @Mock private ConnectionAccessGrantRepository connectionAccessGrantRepository;
    @Mock private AuthLoginChallengeRepository authLoginChallengeRepository;
    @Mock private IndexAdvisorService indexAdvisorService;
    @Mock private IndexRecommendationService indexRecommendationService;

    private SlackDailyDigestService service;

    @BeforeEach
    void setUp() {
        SlackRuntimeSettingsService slackRuntimeSettingsService = org.mockito.Mockito.mock(SlackRuntimeSettingsService.class);
        service = new SlackDailyDigestService(
            slackRuntimeSettingsService,
            channelBindingRepository,
            credentialService,
            connectionService,
            performanceInsightsService,
            slowQueryService,
            slowQueryHistoryService,
            slowQueryInsightsService,
            slowQueryAnalyticsService,
            actionAggregatorService,
            sqlParserService,
            queryExecutorService,
            tableGrowthMonitoringService,
            schemaChangeTrackingService,
            tableStatsHistoryRepository,
            growthAnomalyRepository,
            capacityForecastRepository,
            schemaChangeRepository,
            digestLogRepository,
            slackUserLinkService,
            lockContentionRepository,
            queryFingerprintRepository,
            databaseEventRepository,
            connectionAccessGrantRepository,
            authLoginChallengeRepository,
            indexAdvisorService,
            indexRecommendationService
        );
    }

    @Test
    void prioritizeSchemaChanges_keepsInformationalSchemaChangesVisible() {
        SchemaChange infoTableAdded = SchemaChange.builder()
            .changeType(SchemaChange.ChangeType.TABLE_ADDED)
            .objectType(SchemaChange.ObjectType.TABLE)
            .objectName("v2_auth_config")
            .severity(SchemaChange.Severity.INFO)
            .isBreakingChange(false)
            .detectedAt(LocalDateTime.of(2026, 4, 17, 2, 30))
            .build();

        List<SchemaChange> prioritized = SlackDailyDigestService.prioritizeSchemaChanges(List.of(infoTableAdded));

        assertThat(prioritized).hasSize(1);
        assertThat(prioritized.getFirst().getObjectName()).isEqualTo("v2_auth_config");
        assertThat(prioritized.getFirst().getSeverity()).isEqualTo(SchemaChange.Severity.INFO);
    }

    @Test
    void prioritizeSchemaChanges_ordersBreakingChangesAheadOfInformationalOnes() {
        SchemaChange infoTableAdded = SchemaChange.builder()
            .changeType(SchemaChange.ChangeType.TABLE_ADDED)
            .objectType(SchemaChange.ObjectType.TABLE)
            .objectName("v2_auth_config")
            .severity(SchemaChange.Severity.INFO)
            .isBreakingChange(false)
            .detectedAt(LocalDateTime.of(2026, 4, 17, 2, 30))
            .build();

        SchemaChange breakingColumnRemoved = SchemaChange.builder()
            .changeType(SchemaChange.ChangeType.COLUMN_REMOVED)
            .objectType(SchemaChange.ObjectType.COLUMN)
            .objectName("legacy_flag")
            .tableName("settings")
            .severity(SchemaChange.Severity.CRITICAL)
            .isBreakingChange(true)
            .detectedAt(LocalDateTime.of(2026, 4, 17, 2, 31))
            .build();

        List<SchemaChange> prioritized = SlackDailyDigestService.prioritizeSchemaChanges(
            List.of(infoTableAdded, breakingColumnRemoved)
        );

        assertThat(prioritized).hasSize(2);
        assertThat(prioritized.getFirst().getObjectName()).isEqualTo("legacy_flag");
        assertThat(prioritized.get(1).getObjectName()).isEqualTo("v2_auth_config");
    }

    @Test
    void buildRichDigest_whenConnectionIsUnavailable_returnsConnectionUnavailableDigest() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-1");
        connection.setConnectionName("aws-rds-master");

        ConnectionRequest request = new ConnectionRequest();
        request.setConnectionName("aws-rds-master");
        request.setDbType("mysql");

        when(credentialService.getAllConnections()).thenReturn(List.of(connection));
        when(credentialService.getDecryptedConnection("conn-1")).thenReturn(request);
        when(connectionService.testConnection(request)).thenReturn(false);
        when(digestLogRepository.countByConnectionIdAndChannelIdIsNull("conn-1")).thenReturn(0L);

        String digest = ReflectionTestUtils.invokeMethod(service, "buildRichDigest", "conn-1");

        assertThat(digest).contains("aws-rds-master");
        assertThat(digest).contains("live database access unavailable");
        assertThat(digest).contains("DeepSQL could not reach this database during the digest run.");
        assertThat(digest).contains("No fresh slow-query, schema-delta, or growth updates were published");
        assertThat(digest).doesNotContain("SCHEMA DELTAS");
        assertThat(digest).doesNotContain("WHAT CHANGED");
        assertThat(digest).doesNotContain("QUERY PRESSURE BOARD");
    }

    // ─────────────────────────────────────────────────────────────────────
    // New section coverage: security, concurrency, silent waste, newcomers
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void securitySection_omitsHeaderWhenNothingHappened() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        when(databaseEventRepository.findRecentEvents(eq("conn-1"), any())).thenReturn(List.of());
        when(connectionAccessGrantRepository.findRecentForDigest(eq("conn-1"), any())).thenReturn(List.of());
        when(authLoginChallengeRepository.countFailedSince(any())).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(
            service, "appendSecuritySection", sb, style, "conn-1", LocalDateTime.now().minusHours(24));

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void securitySection_surfacesOffHoursDdlAndFailedLogins() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        DatabaseEvent ddl = DatabaseEvent.builder()
            .id("evt-1")
            .connectionId("conn-1")
            .eventTimestamp(LocalDateTime.now().withHour(2).withMinute(14))
            .eventType(DatabaseEvent.EventType.SCHEMA_CHANGE)
            .eventName("ALTER TABLE users ADD COLUMN deleted_at")
            .initiatedBy("app_admin")
            .build();

        AuthLoginChallenge failed = new AuthLoginChallenge();
        failed.setEmail("attacker@example.com");
        failed.setChallengeState("FAILED");
        failed.setCreatedAt(LocalDateTime.now().minusMinutes(30));

        lenient().when(databaseEventRepository.findRecentEvents(eq("conn-1"), any())).thenReturn(List.of(ddl));
        lenient().when(connectionAccessGrantRepository.findRecentForDigest(eq("conn-1"), any())).thenReturn(List.of());
        lenient().when(authLoginChallengeRepository.countFailedSince(any())).thenReturn(4L);
        lenient().when(authLoginChallengeRepository.findRecentFailures(any())).thenReturn(List.of(failed));

        ReflectionTestUtils.invokeMethod(
            service, "appendSecuritySection", sb, style, "conn-1", LocalDateTime.now().minusHours(24));

        String out = sb.toString();
        assertThat(out).contains("app_admin");
        assertThat(out).contains("SCHEMA_CHANGE");
        assertThat(out).contains("4");
        assertThat(out).contains("attacker@example.com");
    }

    @Test
    void securitySection_surfacesNewAccessGrants() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        ConnectionAccessGrant grant = new ConnectionAccessGrant();
        grant.setUsername("alice");
        grant.setAccessLevel(ConnectionAccessLevel.FULL_CONTENT);
        grant.setGrantedBy("admin@example.com");
        grant.setCreatedAt(LocalDateTime.now().minusHours(2));

        lenient().when(databaseEventRepository.findRecentEvents(eq("conn-1"), any())).thenReturn(List.of());
        lenient().when(connectionAccessGrantRepository.findRecentForDigest(eq("conn-1"), any())).thenReturn(List.of(grant));
        lenient().when(authLoginChallengeRepository.countFailedSince(any())).thenReturn(0L);

        ReflectionTestUtils.invokeMethod(
            service, "appendSecuritySection", sb, style, "conn-1", LocalDateTime.now().minusHours(24));

        String out = sb.toString();
        assertThat(out).contains("alice");
        assertThat(out).contains("FULL_CONTENT");
        assertThat(out).contains("admin@example.com");
    }

    @Test
    void concurrencySection_suppressedOnQuietDay() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");
        when(lockContentionRepository.findRecentForDigest(eq("conn-1"), any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
            service, "appendConcurrencySection", sb, style, "conn-1", LocalDateTime.now().minusHours(24));

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void concurrencySection_rendersBlockingDetails() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        LockContention contention = LockContention.builder()
            .id("lc-1")
            .connectionId("conn-1")
            .blockingPid("123")
            .blockedPid("456")
            .blockingUser("worker_user")
            .blockedUser("api_reader")
            .blockingQuery("UPDATE bookings SET status = ? WHERE booking_id = ?")
            .lockType("ROW_EXCLUSIVE")
            .lockMode("EXCLUSIVE")
            .tableName("bookings")
            .waitDurationSeconds(125L)
            .resolved(false)
            .severity(LockContention.Severity.CRITICAL)
            .detectedAt(LocalDateTime.now().minusMinutes(20))
            .build();

        when(lockContentionRepository.findRecentForDigest(eq("conn-1"), any())).thenReturn(List.of(contention));

        ReflectionTestUtils.invokeMethod(
            service, "appendConcurrencySection", sb, style, "conn-1", LocalDateTime.now().minusHours(24));

        String out = sb.toString();
        assertThat(out).contains("worker_user");
        assertThat(out).contains("api_reader");
        assertThat(out).contains("bookings");
        assertThat(out).contains("Critical");
        assertThat(out).contains("ROW_EXCLUSIVE");
    }

    @Test
    void silentWasteSection_suppressedWhenNothingReclaimable() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");
        when(indexAdvisorService.getIndexHealthReport(anyString())).thenReturn(java.util.Map.of(
            "unusedIndexWastedBytes", 100L,
            "unusedIndexes", List.of()
        ));
        Object growthData = makeEmptyGrowthData();

        ReflectionTestUtils.invokeMethod(
            service, "appendSilentWasteSection", sb, style, "conn-1", growthData);

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void silentWasteSection_rendersUnusedIndexesAndBloat() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        when(indexAdvisorService.getIndexHealthReport(anyString())).thenReturn(java.util.Map.of(
            "unusedIndexWastedBytes", 5L * (1L << 30), // 5 GB
            "unusedIndexes", List.of(
                java.util.Map.of("indexName", "idx_users_email_legacy", "tableName", "users", "indexSizeBytes", 3L * (1L << 30)),
                java.util.Map.of("indexName", "idx_orders_status_old", "tableName", "orders", "indexSizeBytes", 2L * (1L << 30))
            )
        ));

        TableStatsHistory bloated = new TableStatsHistory();
        bloated.setTableName("audit_log");
        bloated.setBloatBytes(8L * (1L << 30));
        bloated.setBloatPercent(45.0);
        bloated.setSizeBytes(20L * (1L << 30));

        Object growthData = makeGrowthDataWithSnapshots(List.of(bloated));

        ReflectionTestUtils.invokeMethod(
            service, "appendSilentWasteSection", sb, style, "conn-1", growthData);

        String out = sb.toString();
        assertThat(out).contains("reclaimable");
        assertThat(out).contains("idx_users_email_legacy");
        assertThat(out).contains("audit_log");
        assertThat(out).contains("45");
    }

    @Test
    void indexWinsSection_suppressedWhenNoRecommendations() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");
        when(indexRecommendationService.getTopRecommendationsWithEvidence(eq("conn-1"), anyInt()))
            .thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
            service, "appendIndexWinsSection", sb, style, "conn-1");

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void indexWinsSection_suppressedWhenOnlyDropCandidatesPresent() {
        // DROP candidates live in `savings`/`waste` — this section is for CREATEs only.
        // Mixing them in would duplicate the narrative.
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        com.dbaagent.model.IndexRecommendationEntity dropRec =
            com.dbaagent.model.IndexRecommendationEntity.builder()
                .id("d1")
                .connectionId("conn-1")
                .tableName("orders")
                .columnNames("idx_orders_legacy")
                .indexName("idx_orders_legacy")
                .createStatement("DROP INDEX idx_orders_legacy;")
                .kind(com.dbaagent.model.IndexRecommendationEntity.Kind.DROP_INDEX)
                .priority(com.dbaagent.model.IndexRecommendationEntity.Priority.MEDIUM)
                .build();

        when(indexRecommendationService.getTopRecommendationsWithEvidence(eq("conn-1"), anyInt()))
            .thenReturn(List.of(new IndexRecommendationService.TopRecommendationWithEvidence(dropRec, List.of())));

        ReflectionTestUtils.invokeMethod(
            service, "appendIndexWinsSection", sb, style, "conn-1");

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void indexWinsSection_rendersWorkloadWeightedCandidates_withEvidenceAndApplyCta() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        com.dbaagent.model.IndexRecommendationEntity hot =
            com.dbaagent.model.IndexRecommendationEntity.builder()
                .id("abcdef1234567890")
                .connectionId("conn-1")
                .tableName("orders")
                .columnNames("customer_id,status")
                .indexName("idx_orders_customer_id_status")
                .createStatement("CREATE INDEX idx_orders_customer_id_status ON orders (customer_id, status);")
                .kind(com.dbaagent.model.IndexRecommendationEntity.Kind.CREATE_INDEX)
                .priority(com.dbaagent.model.IndexRecommendationEntity.Priority.HIGH)
                .occurrenceCount(4)
                .workloadScoreMs(4_823_000L)  // ~1.3h
                .writeCostScore(142_000L)
                .hypopgReductionPct(75.0)
                .build();
        com.dbaagent.model.IndexRecommendationEvidence ev =
            com.dbaagent.model.IndexRecommendationEvidence.builder()
                .recommendationId("abcdef1234567890")
                .queryFingerprint("ev-1")
                .calls(4500L)
                .meanExecTimeMs(850.0)
                .totalExecTimeMs(3_825_000.0)
                .role("WHERE_EQ")
                .build();

        when(indexRecommendationService.getTopRecommendationsWithEvidence(eq("conn-1"), anyInt()))
            .thenReturn(List.of(
                new IndexRecommendationService.TopRecommendationWithEvidence(hot, List.of(ev))
            ));

        ReflectionTestUtils.invokeMethod(
            service, "appendIndexWinsSection", sb, style, "conn-1");

        String out = sb.toString();
        assertThat(out).contains("INDEX");                       // header rendered
        assertThat(out).contains("orders(customer_id,status)");  // target rendered
        assertThat(out).contains("HIGH");                        // priority
        assertThat(out).contains("seen 4×");                     // recurrence
        assertThat(out).contains("net=");                        // net-benefit
        assertThat(out).contains("HypoPG −75%");                 // planner-validated marker
        assertThat(out).contains("4,500 calls");                 // top contributing query
        assertThat(out).contains("WHERE_EQ");                    // predicate role
        assertThat(out).contains("deepsql indexes apply");       // actionable CTA
        assertThat(out).contains("abcdef12…");                   // truncated id surfaces in the CTA
    }

    @Test
    void newcomersSection_suppressedWhenNoRecentFingerprints() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");
        when(queryFingerprintRepository.findRecentlySeen(eq("conn-1"), any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
            service, "appendNewcomersSection", sb, style, "conn-1", LocalDateTime.now().minusHours(24));

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void newcomersSection_surfacesQueriesFirstSeenInWindow() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        LocalDateTime since = LocalDateTime.now().minusHours(24);
        QueryFingerprint freshHeavy = QueryFingerprint.builder()
            .id("qf-1")
            .connectionId("conn-1")
            .fingerprint("abc")
            .normalizedQuery("SELECT * FROM new_pricing_table WHERE tenant_id = ?")
            .currentAvgTimeMs(450.0)
            .currentCallCount(2_400_000L)
            .firstSeenAt(LocalDateTime.now().minusHours(6))
            .lastSeenAt(LocalDateTime.now())
            .observationCount(1)
            .build();

        QueryFingerprint old = QueryFingerprint.builder()
            .id("qf-2")
            .connectionId("conn-1")
            .fingerprint("def")
            .normalizedQuery("SELECT 1")
            .currentAvgTimeMs(2.0)
            .currentCallCount(10L)
            .firstSeenAt(LocalDateTime.now().minusDays(30))
            .lastSeenAt(LocalDateTime.now())
            .build();

        when(queryFingerprintRepository.findRecentlySeen(eq("conn-1"), any())).thenReturn(List.of(freshHeavy, old));

        ReflectionTestUtils.invokeMethod(
            service, "appendNewcomersSection", sb, style, "conn-1", since);

        String out = sb.toString();
        assertThat(out).contains("new_pricing_table");
        assertThat(out).doesNotContain("SELECT 1");
    }

    // ─────────────────────────────────────────────────────────────────────
    // Potential-savings section + cost / time formatter coverage
    // ─────────────────────────────────────────────────────────────────────

    @Test
    void savingsSection_suppressedWhenBelowDollarFloor() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        // Tiny waste that prices below $10/mo
        when(indexAdvisorService.getIndexHealthReport(anyString())).thenReturn(java.util.Map.of(
            "unusedIndexWastedBytes", 100L * (1L << 20), // 100 MB
            "unusedIndexes", List.of()
        ));
        when(queryFingerprintRepository.findRecentlySeen(eq("conn-1"), any())).thenReturn(List.of());

        ReflectionTestUtils.invokeMethod(
            service, "appendPotentialSavingsSection", sb, style, "conn-1", makeEmptyGrowthData(), null, LocalDateTime.now().minusHours(24));

        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void savingsSection_aggregatesStorageAndComputeAndShowsHeadlineDollar() {
        StringBuilder sb = new StringBuilder();
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", "conn-1");

        // 100 GB unused index → ~$11.50/mo storage waste
        when(indexAdvisorService.getIndexHealthReport(anyString())).thenReturn(java.util.Map.of(
            "unusedIndexWastedBytes", 100L * (1L << 30),
            "unusedIndexes", List.of(java.util.Map.of(
                "indexName", "idx_legacy", "tableName", "users", "indexSizeBytes", 100L * (1L << 30)))
        ));

        // One heavy fingerprint: 600ms × 4M calls = 2.4M seconds = 666 hours/day of CPU → BIG bill
        QueryFingerprint heavy = QueryFingerprint.builder()
            .id("qf-heavy").connectionId("conn-1").fingerprint("h")
            .normalizedQuery("SELECT * FROM huge_join")
            .currentAvgTimeMs(600.0).currentCallCount(4_000_000L)
            .firstSeenAt(LocalDateTime.now().minusHours(3))
            .lastSeenAt(LocalDateTime.now()).build();
        when(queryFingerprintRepository.findRecentlySeen(eq("conn-1"), any())).thenReturn(List.of(heavy));

        ReflectionTestUtils.invokeMethod(
            service, "appendPotentialSavingsSection", sb, style, "conn-1", makeEmptyGrowthData(), null, LocalDateTime.now().minusHours(24));

        String out = sb.toString();
        assertThat(out).contains("/mo");
        assertThat(out).contains("Storage:");
        assertThat(out).contains("Compute:");
        assertThat(out).contains("AWS RDS"); // disclaimer footer
        // Storage figure should be > $10
        assertThat(out).matches("(?s).*\\$1[0-9]+(\\.[0-9]+)?.*");
    }

    @Test
    void formatDbTime_rendersSecondsMinutesHoursDays() {
        // < 1 minute → seconds
        assertThat((String) ReflectionTestUtils.invokeMethod(SlackDailyDigestService.class, "formatDbTime", 30_000.0))
            .isEqualTo("30.0 s");
        // < 60 minutes → minutes
        assertThat((String) ReflectionTestUtils.invokeMethod(SlackDailyDigestService.class, "formatDbTime", 600_000.0))
            .isEqualTo("10.0 min");
        // < 24 hours → hours
        assertThat((String) ReflectionTestUtils.invokeMethod(SlackDailyDigestService.class, "formatDbTime", 3_600_000.0 * 5))
            .isEqualTo("5.0 hr");
        // ≥ 24 hours → days
        assertThat((String) ReflectionTestUtils.invokeMethod(SlackDailyDigestService.class, "formatDbTime", 3_600_000.0 * 72))
            .isEqualTo("3.0 days");
    }

    private Object makeEmptyGrowthData() {
        return makeGrowthDataWithSnapshots(List.of());
    }

    private Object makeGrowthDataWithSnapshots(List<TableStatsHistory> snapshots) {
        try {
            Class<?> growthClass = Class.forName("com.dbaagent.service.SlackDailyDigestService$GrowthDigestData");
            java.lang.reflect.Constructor<?> ctor = growthClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(
                snapshots, List.of(), java.util.Map.of(), java.util.Map.of(), false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
