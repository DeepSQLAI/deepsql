package com.dbaagent.service;

import com.dbaagent.model.AuthLoginChallenge;
import com.dbaagent.model.ConnectionAccessGrant;
import com.dbaagent.model.ConnectionAccessLevel;
import com.dbaagent.model.DatabaseEvent;
import com.dbaagent.model.LockContention;
import com.dbaagent.model.QueryFingerprint;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Demo print test — assembles a realistic-looking digest using only the four NEW sections
 * (security, concurrency, silent waste, newcomers) with synthetic data, and prints the
 * rendered Slack markdown to stdout so a developer can eyeball the output without running
 * the full backend.
 */
@ExtendWith(MockitoExtension.class)
class SlackDailyDigestDemoPrintTest {

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
        service = new SlackDailyDigestService(
            slackRuntimeSettingsService, channelBindingRepository, credentialService, connectionService,
            performanceInsightsService, slowQueryService, slowQueryHistoryService, slowQueryInsightsService,
            slowQueryAnalyticsService,
            actionAggregatorService, sqlParserService, queryExecutorService, tableGrowthMonitoringService,
            schemaChangeTrackingService, tableStatsHistoryRepository, growthAnomalyRepository,
            capacityForecastRepository, schemaChangeRepository, digestLogRepository,
            slackUserLinkService,
            lockContentionRepository, queryFingerprintRepository, databaseEventRepository,
            connectionAccessGrantRepository, authLoginChallengeRepository, indexAdvisorService,
            indexRecommendationService
        );
    }

    @Test
    void demo_printAllFourNewSections() {
        String connectionId = "demo-conn";
        LocalDateTime since = LocalDateTime.now().minusHours(24);
        Object style = ReflectionTestUtils.invokeMethod(service, "chooseStyle", connectionId);

        // ── SECURITY ───────────────────────────────────────────────────────────
        DatabaseEvent ddl1 = DatabaseEvent.builder()
            .id("evt-1").connectionId(connectionId)
            .eventTimestamp(LocalDateTime.now().withHour(2).withMinute(14))
            .eventType(DatabaseEvent.EventType.SCHEMA_CHANGE)
            .eventName("ALTER TABLE user_bookings ADD COLUMN deleted_at TIMESTAMP")
            .initiatedBy("app_admin").build();
        DatabaseEvent ddl2 = DatabaseEvent.builder()
            .id("evt-2").connectionId(connectionId)
            .eventTimestamp(LocalDateTime.now().withHour(3).withMinute(47))
            .eventType(DatabaseEvent.EventType.INDEX_DROP)
            .eventName("DROP INDEX idx_bookings_status_old")
            .initiatedBy("migration_bot").build();

        ConnectionAccessGrant grant = new ConnectionAccessGrant();
        grant.setUsername("alice");
        grant.setAccessLevel(ConnectionAccessLevel.FULL_CONTENT);
        grant.setGrantedBy("admin@stayflexi.com");
        grant.setCreatedAt(LocalDateTime.now().minusHours(2));

        AuthLoginChallenge fail1 = new AuthLoginChallenge();
        fail1.setEmail("attacker@example.com");
        fail1.setChallengeState("FAILED");
        fail1.setCreatedAt(LocalDateTime.now().minusMinutes(45));
        AuthLoginChallenge fail2 = new AuthLoginChallenge();
        fail2.setEmail("test.user@example.com");
        fail2.setChallengeState("FAILED");
        fail2.setCreatedAt(LocalDateTime.now().minusMinutes(20));

        lenient().when(databaseEventRepository.findRecentEvents(eq(connectionId), any())).thenReturn(List.of(ddl1, ddl2));
        lenient().when(connectionAccessGrantRepository.findRecentForDigest(eq(connectionId), any())).thenReturn(List.of(grant));
        lenient().when(authLoginChallengeRepository.countFailedSince(any())).thenReturn(7L);
        lenient().when(authLoginChallengeRepository.findRecentFailures(any())).thenReturn(List.of(fail1, fail2));

        // ── CONCURRENCY ────────────────────────────────────────────────────────
        LockContention lc1 = LockContention.builder()
            .id("lc-1").connectionId(connectionId)
            .blockingPid("3214").blockedPid("3987")
            .blockingUser("worker_user").blockedUser("api_reader")
            .blockingQuery("UPDATE bookings SET status = 'CHECKED_IN' WHERE booking_id = ?")
            .lockType("ROW_EXCLUSIVE").lockMode("EXCLUSIVE")
            .tableName("bookings").waitDurationSeconds(125L)
            .resolved(false).severity(LockContention.Severity.CRITICAL)
            .detectedAt(LocalDateTime.now().minusMinutes(20)).build();
        LockContention lc2 = LockContention.builder()
            .id("lc-2").connectionId(connectionId)
            .blockingPid("4001").blockedPid("4123")
            .blockingUser("etl_user").blockedUser("dashboard_user")
            .blockingQuery("DELETE FROM third_party_integration_logs WHERE created_at < ?")
            .lockType("ACCESS_EXCLUSIVE").lockMode("EXCLUSIVE")
            .tableName("third_party_integration_logs").waitDurationSeconds(48L)
            .resolved(true).severity(LockContention.Severity.HIGH)
            .detectedAt(LocalDateTime.now().minusHours(3)).build();
        when(lockContentionRepository.findRecentForDigest(eq(connectionId), any())).thenReturn(List.of(lc1, lc2));

        // ── SILENT WASTE ───────────────────────────────────────────────────────
        when(indexAdvisorService.getIndexHealthReport(anyString())).thenReturn(Map.of(
            "unusedIndexWastedBytes", 18L * (1L << 30),
            "unusedIndexes", List.of(
                Map.of("indexName", "idx_users_email_legacy", "tableName", "users", "indexSizeBytes", 7L * (1L << 30)),
                Map.of("indexName", "idx_orders_status_old", "tableName", "orders", "indexSizeBytes", 6L * (1L << 30)),
                Map.of("indexName", "idx_bookings_dt_v1", "tableName", "user_bookings", "indexSizeBytes", 5L * (1L << 30))
            )
        ));

        TableStatsHistory bloated1 = new TableStatsHistory();
        bloated1.setTableName("audit_log");
        bloated1.setBloatBytes(8L * (1L << 30));
        bloated1.setBloatPercent(45.0);
        bloated1.setSizeBytes(20L * (1L << 30));
        TableStatsHistory bloated2 = new TableStatsHistory();
        bloated2.setTableName("isha_logs_utility");
        bloated2.setBloatBytes(15L * (1L << 30));
        bloated2.setBloatPercent(28.0);
        bloated2.setSizeBytes(72L * (1L << 30));

        Object growthData = makeGrowthDataWithSnapshots(List.of(bloated1, bloated2));

        // ── NEWCOMERS ──────────────────────────────────────────────────────────
        QueryFingerprint newcomer1 = QueryFingerprint.builder()
            .id("qf-1").connectionId(connectionId).fingerprint("abc")
            .normalizedQuery("SELECT pricing.* FROM new_pricing_engine_v2 pricing WHERE tenant_id = ? AND date_range && ?")
            .currentAvgTimeMs(450.0).currentCallCount(2_400_000L)
            .firstSeenAt(LocalDateTime.now().minusHours(6))
            .lastSeenAt(LocalDateTime.now()).observationCount(1)
            .affectedTables(List.of("new_pricing_engine_v2", "tenants"))
            .build();
        QueryFingerprint newcomer2 = QueryFingerprint.builder()
            .id("qf-2").connectionId(connectionId).fingerprint("def")
            .normalizedQuery("INSERT INTO promo_redemptions (code, user_id, redeemed_at) VALUES (?, ?, ?)")
            .currentAvgTimeMs(82.0).currentCallCount(180_000L)
            .firstSeenAt(LocalDateTime.now().minusHours(11))
            .lastSeenAt(LocalDateTime.now()).observationCount(1)
            .affectedTables(List.of("promo_redemptions"))
            .build();
        when(queryFingerprintRepository.findRecentlySeen(eq(connectionId), any())).thenReturn(List.of(newcomer1, newcomer2));

        // ── ASSEMBLE ───────────────────────────────────────────────────────────
        StringBuilder sb = new StringBuilder();
        sb.append("*🗄️ DB Health Briefing — demo-conn*\n");
        sb.append("_demo run · synthetic data — exercising new digest sections_\n");
        sb.append("────────────────────────\n\n");

        ReflectionTestUtils.invokeMethod(service, "appendPotentialSavingsSection", sb, style, connectionId, growthData, null, since);
        ReflectionTestUtils.invokeMethod(service, "appendSecuritySection", sb, style, connectionId, since);
        ReflectionTestUtils.invokeMethod(service, "appendConcurrencySection", sb, style, connectionId, since);
        ReflectionTestUtils.invokeMethod(service, "appendSilentWasteSection", sb, style, connectionId, growthData);
        ReflectionTestUtils.invokeMethod(service, "appendNewcomersSection", sb, style, connectionId, since);

        sb.append("_Powered by DeepSQL · reply with any question about your DB_\n");

        String digest = sb.toString();

        // Print it loud and clear
        System.out.println();
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println("  RENDERED DIGEST (synthetic data, new sections only)");
        System.out.println("  total length: " + digest.length() + " chars");
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println(digest);
        System.out.println("═══════════════════════════════════════════════════════════════════════");
        System.out.println();

        // Sanity assertions — content only, since titles rotate per digest count
        assertThat(digest).contains("app_admin");
        assertThat(digest).contains("worker_user");
        assertThat(digest).contains("idx_users_email_legacy");
        assertThat(digest).contains("audit_log");
        assertThat(digest).contains("new_pricing_engine_v2");
        assertThat(digest).contains("/mo");
        assertThat(digest).contains("$");
        // Savings header — match any rotation variant via regex
        assertThat(digest).matches("(?s).*(SAVINGS|MONEY ON THE TABLE|COST RECOVERY).*");
        // Concurrency header — match any rotation variant
        assertThat(digest).matches("(?s).*(CONCURRENCY|LOCK & WAIT|BLOCKING ACTIVITY).*");
    }

    private Object makeGrowthDataWithSnapshots(List<TableStatsHistory> snapshots) {
        try {
            Class<?> growthClass = Class.forName("com.dbaagent.service.SlackDailyDigestService$GrowthDigestData");
            java.lang.reflect.Constructor<?> ctor = growthClass.getDeclaredConstructors()[0];
            ctor.setAccessible(true);
            return ctor.newInstance(snapshots, List.of(), java.util.Map.of(), java.util.Map.of(), false);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException(e);
        }
    }
}
