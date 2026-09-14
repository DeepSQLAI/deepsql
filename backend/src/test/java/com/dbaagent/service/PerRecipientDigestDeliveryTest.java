package com.dbaagent.service;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.Role;
import com.dbaagent.model.SlackDigestLog;
import com.dbaagent.model.User;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.model.digest.DigestAssemblyResult;
import com.dbaagent.model.digest.DigestInsight;
import com.dbaagent.model.digest.InsightCategory;
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
import com.dbaagent.repository.UserDigestPreferenceRepository;
import com.dbaagent.repository.UserRepository;
import com.dbaagent.service.digest.DigestInsightAssemblerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Tests for per-recipient personalized digest delivery (PR3).
 *
 * <h3>Key scenarios:</h3>
 * <ul>
 *   <li>Two users with different personas get different digests for same connection</li>
 *   <li>EXEC persona gets tight 3-bullet executive summary</li>
 *   <li>EMAIL prefs are skipped (not a fake delivery path)</li>
 *   <li>Proper logging with recipient details</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PerRecipientDigestDeliveryTest {

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
    @Mock private UserDigestPreferenceRepository preferenceRepository;
    @Mock private DigestInsightAssemblerService assemblerService;
    @Mock private UserRepository userRepository;

    private SlackDailyDigestService service;

    @BeforeEach
    void setUp() {
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
            indexRecommendationService,
            preferenceRepository,
            assemblerService,
            userRepository
        );

        // Slack disabled: generate + log without opening DMs (keeps this unit-level).
        lenient().when(slackRuntimeSettingsService.current()).thenReturn(
            new SlackRuntimeSettingsService.SlackRuntimeConfig(false, false, null, null, null, null));
        lenient().when(digestLogRepository.findTopByConnectionIdOrderBySentAtDesc(anyString()))
            .thenReturn(Optional.empty());
        lenient().when(digestLogRepository.save(any(SlackDigestLog.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(credentialService.getAllConnections()).thenReturn(List.of());
    }

    @Test
    void twoUsersWithDifferentPersonas_getDifferentDigests() {
        String connectionId = "conn-123";

        UserDigestPreference dbaPreference = UserDigestPreference.builder()
            .id(1L)
            .username("alice_dba")
            .connectionId(connectionId)
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.DBA)
            .build();

        UserDigestPreference execPreference = UserDigestPreference.builder()
            .id(2L)
            .username("bob_exec")
            .connectionId(connectionId)
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.EXEC)
            .build();

        when(preferenceRepository.findEnabledForConnection(connectionId))
            .thenReturn(List.of(dbaPreference, execPreference));

        User dbaUser = new User();
        dbaUser.setUsername("alice_dba");
        dbaUser.setRole("DBA");

        User execUser = new User();
        execUser.setUsername("bob_exec");
        execUser.setRole("ADMIN");

        when(userRepository.findByUsernameIgnoreCase("alice_dba")).thenReturn(Optional.of(dbaUser));
        when(userRepository.findByUsernameIgnoreCase("bob_exec")).thenReturn(Optional.of(execUser));

        DigestAssemblyResult dbaResult = createDbaDigest();
        DigestAssemblyResult execResult = createExecDigest();

        when(assemblerService.assembleDigest(
            eq("alice_dba"), eq(connectionId), eq(Role.DBA), eq(PersonaTag.DBA), any()
        )).thenReturn(dbaResult);

        when(assemblerService.assembleDigest(
            eq("bob_exec"), eq(connectionId), eq(Role.ADMIN), eq(PersonaTag.EXEC), any()
        )).thenReturn(execResult);

        SlackDailyDigestService.PersonalizedDeliveryResult result =
            service.sendPersonalizedDigests(connectionId);

        assertThat(result.recipients()).isEqualTo(2);
        assertThat(result.generated()).isEqualTo(2);
        // Slack is disabled in this test, so nothing is posted — only generated + logged.
        assertThat(result.sent()).isEqualTo(0);

        verify(assemblerService).assembleDigest(
            eq("alice_dba"), eq(connectionId), eq(Role.DBA), eq(PersonaTag.DBA), any());
        verify(assemblerService).assembleDigest(
            eq("bob_exec"), eq(connectionId), eq(Role.ADMIN), eq(PersonaTag.EXEC), any());

        ArgumentCaptor<SlackDigestLog> captor = ArgumentCaptor.forClass(SlackDigestLog.class);
        verify(digestLogRepository, org.mockito.Mockito.times(2)).save(captor.capture());

        List<SlackDigestLog> logs = captor.getAllValues();
        SlackDigestLog aliceLog = logs.stream()
            .filter(l -> "alice_dba".equals(l.getRecipientUsername())).findFirst().orElseThrow();
        SlackDigestLog bobLog = logs.stream()
            .filter(l -> "bob_exec".equals(l.getRecipientUsername())).findFirst().orElseThrow();

        assertThat(aliceLog.isPersonalized()).isTrue();
        assertThat(aliceLog.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(aliceLog.getRecipientRole()).isEqualTo("DBA");
        assertThat(aliceLog.getDeliveryMethod()).isEqualTo(DigestDeliveryMethod.SLACK_DM);
        assertThat(aliceLog.getContent()).contains("CRITICAL");

        assertThat(bobLog.isPersonalized()).isTrue();
        assertThat(bobLog.getPersonaTag()).isEqualTo(PersonaTag.EXEC);
        assertThat(bobLog.getRecipientRole()).isEqualTo("ADMIN");
        assertThat(bobLog.getContent()).contains("EXECUTIVE SUMMARY");
        assertThat(bobLog.getContent()).contains("ACTION NEEDED");
        assertThat(bobLog.getContent().lines().filter(line -> line.startsWith("• ")).count())
            .isLessThanOrEqualTo(3);
    }

    @Test
    void execPersona_getsTightThreeBulletSummary() {
        DigestAssemblyResult execResult = createExecDigest();

        String message = ReflectionTestUtils.invokeMethod(
            service, "formatPersonalizedDigest", execResult, "prod-db", PersonaTag.EXEC);

        assertThat(message).contains("EXECUTIVE SUMMARY");
        assertThat(message).contains("ACTION NEEDED");
        assertThat(message).contains("Review and approve index recommendation");
        assertThat(message.lines().filter(line -> line.startsWith("• ")).count())
            .isLessThanOrEqualTo(3);
        // EXEC payload stays short — no full insight dump.
        assertThat(message).doesNotContain("[sig:");
    }

    @Test
    void emailPreference_isSkippedNotDelivered() {
        UserDigestPreference emailPref = UserDigestPreference.builder()
            .id(9L)
            .username("carol")
            .connectionId("conn-123")
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.EMAIL)
            .personaTag(PersonaTag.EXEC)
            .build();

        when(preferenceRepository.findEnabledForConnection("conn-123"))
            .thenReturn(List.of(emailPref));

        SlackDailyDigestService.PersonalizedDeliveryResult result =
            service.sendPersonalizedDigests("conn-123");

        assertThat(result.recipients()).isEqualTo(1);
        assertThat(result.generated()).isEqualTo(0);
        verify(assemblerService, never()).assembleDigest(anyString(), anyString(), any(), any(), any());
        verify(digestLogRepository, never()).save(any());
    }

    @Test
    void customRole_doesNotNpe_andLogsStoredRoleCode() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .id(3L)
            .username("analyst")
            .connectionId("conn-123")
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.DATA_ENG)
            .build();

        when(preferenceRepository.findEnabledForConnection("conn-123"))
            .thenReturn(List.of(pref));

        User custom = new User();
        custom.setUsername("analyst");
        custom.setRole("ANALYST"); // not a built-in Role
        when(userRepository.findByUsernameIgnoreCase("analyst")).thenReturn(Optional.of(custom));

        when(assemblerService.assembleDigest(
            eq("analyst"), eq("conn-123"), eq(Role.DEVELOPER), eq(PersonaTag.DATA_ENG), any()
        )).thenReturn(DigestAssemblyResult.empty("analyst", "conn-123", Role.DEVELOPER, PersonaTag.DATA_ENG));

        service.sendPersonalizedDigests("conn-123");

        ArgumentCaptor<SlackDigestLog> captor = ArgumentCaptor.forClass(SlackDigestLog.class);
        verify(digestLogRepository).save(captor.capture());
        assertThat(captor.getValue().getRecipientRole()).isEqualTo("ANALYST");
        assertThat(captor.getValue().isPersonalized()).isTrue();
    }

    @Test
    void digestLog_containsRecipientDetails() {
        SlackDigestLog logEntry = new SlackDigestLog();
        logEntry.setConnectionId("conn-123");
        logEntry.setRecipientUsername("alice_dba");
        logEntry.setRecipientRole("DBA");
        logEntry.setPersonaTag(PersonaTag.DBA);
        logEntry.setDeliveryMethod(DigestDeliveryMethod.SLACK_DM);
        logEntry.setPersonalized(true);
        logEntry.setStatus("SENT");

        assertThat(logEntry.getRecipientUsername()).isEqualTo("alice_dba");
        assertThat(logEntry.getRecipientRole()).isEqualTo("DBA");
        assertThat(logEntry.getPersonaTag()).isEqualTo(PersonaTag.DBA);
        assertThat(logEntry.isPersonalized()).isTrue();
        assertThat(logEntry.isPerUserDelivery()).isTrue();
    }

    @Test
    void legacyDigest_hasNoRecipientDetails() {
        SlackDigestLog logEntry = new SlackDigestLog();
        logEntry.setConnectionId("conn-123");
        logEntry.setChannelId("C123456");
        logEntry.setPersonalized(false);
        logEntry.setStatus("SENT");

        assertThat(logEntry.getRecipientUsername()).isNull();
        assertThat(logEntry.getRecipientRole()).isNull();
        assertThat(logEntry.getPersonaTag()).isNull();
        assertThat(logEntry.isPersonalized()).isFalse();
        assertThat(logEntry.isPerUserDelivery()).isFalse();
    }

    @Test
    void preferenceForConnection_appliesCorrectly() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .connectionId("conn-123")
            .enabled(true)
            .build();

        assertThat(pref.appliesTo("conn-123")).isTrue();
        assertThat(pref.appliesTo("conn-456")).isFalse();
    }

    @Test
    void preferenceWithNullConnection_appliesToAll() {
        UserDigestPreference pref = UserDigestPreference.builder()
            .username("alice")
            .connectionId(null)
            .enabled(true)
            .build();

        assertThat(pref.appliesTo("conn-123")).isTrue();
        assertThat(pref.appliesTo("conn-456")).isTrue();
    }

    @Test
    void assemblyResult_emptyDigest_indicatesEmpty() {
        DigestAssemblyResult empty = DigestAssemblyResult.empty(
            "alice", "conn-123", Role.DBA, PersonaTag.DBA);

        assertThat(empty.isEmpty()).isTrue();
        assertThat(empty.getInsights()).isEmpty();
        assertThat(empty.getHeadline()).isEqualTo("No new insights since your last digest");
    }

    private DigestAssemblyResult createDbaDigest() {
        List<DigestInsight> insights = List.of(
            createInsight(InsightCategory.LOCK_CONCURRENCY, "Critical lock wait", 92),
            createInsight(InsightCategory.QUERY_PERFORMANCE, "Plan regression", 85),
            createInsight(InsightCategory.INDEX_RECOMMENDATIONS, "3 index recommendations", 75),
            createInsight(InsightCategory.CONFIG_TUNING, "join_collapse_limit adjustment", 65),
            createInsight(InsightCategory.GROWTH_ANOMALIES, "Table growth detected", 55)
        );

        return DigestAssemblyResult.builder()
            .username("alice_dba")
            .connectionId("conn-123")
            .role(Role.DBA)
            .personaTag(PersonaTag.DBA)
            .assembledAt(LocalDateTime.now())
            .insights(insights)
            .totalCandidates(10)
            .build();
    }

    private DigestAssemblyResult createExecDigest() {
        List<DigestInsight> insights = List.of(
            createInsight(InsightCategory.COST_CAPACITY, "Storage cost up 15%", 75),
            createInsight(InsightCategory.QUERY_PERFORMANCE, "Top regression: 3x slowdown", 85),
            createInsight(InsightCategory.GROWTH_ANOMALIES, "Capacity planning needed", 70)
        );

        List<String> execSummary = List.of(
            "⚠️ 1 critical issue requires attention",
            "📉 Top regression: 3x slowdown on orders query",
            "💰 Storage cost up 15% this month"
        );

        return DigestAssemblyResult.builder()
            .username("bob_exec")
            .connectionId("conn-123")
            .role(Role.ADMIN)
            .personaTag(PersonaTag.EXEC)
            .assembledAt(LocalDateTime.now())
            .insights(insights)
            .executiveSummary(execSummary)
            .decisionAsk("Review and approve index recommendation for orders table")
            .totalCandidates(10)
            .build();
    }

    private DigestInsight createInsight(InsightCategory category, String headline, int severity) {
        return DigestInsight.builder()
            .category(category)
            .headline(headline)
            .severity(severity)
            .timestamp(LocalDateTime.now())
            .actionable(true)
            .signatureKey(category.name() + ":" + headline.hashCode())
            .build();
    }
}
