package com.dbaagent.service;

import com.dbaagent.model.DigestDeliveryMethod;
import com.dbaagent.model.PersonaTag;
import com.dbaagent.model.Role;
import com.dbaagent.model.SlackDigestLog;
import com.dbaagent.model.User;
import com.dbaagent.model.UserDigestPreference;
import com.dbaagent.model.digest.DigestAssemblyResult;
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

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Scheduling honesty: the minute tick must honor per-user cron expressions
 * (and timezones) rather than blasting every preference on the global cron.
 */
@ExtendWith(MockitoExtension.class)
class PerRecipientDigestCronSchedulingTest {

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

        ReflectionTestUtils.setField(service, "globalDigestCron", "0 0 9 * * *");
        ReflectionTestUtils.setField(service, "digestAdminsOnly", true);

        lenient().when(slackRuntimeSettingsService.current()).thenReturn(
            new SlackRuntimeSettingsService.SlackRuntimeConfig(false, false, null, null, null, null));
        lenient().when(digestLogRepository.findTopByConnectionIdOrderBySentAtDesc(anyString()))
            .thenReturn(Optional.empty());
        lenient().when(digestLogRepository.save(any(SlackDigestLog.class)))
            .thenAnswer(inv -> inv.getArgument(0));
        lenient().when(digestLogRepository.existsByPreferenceIdAndConnectionIdAndSentAtGreaterThanEqual(
            any(), anyString(), any())).thenReturn(false);
        lenient().when(digestLogRepository.existsByConnectionIdAndRecipientUsernameAndSentAtGreaterThanEqual(
            anyString(), anyString(), any())).thenReturn(false);
        lenient().when(credentialService.getAllConnections()).thenReturn(List.of());
        lenient().when(channelBindingRepository.findAll()).thenReturn(List.of());
    }

    @Test
    void differentCrons_onlyDueUserIsDelivered() {
        Instant atEightUtc = ZonedDateTime.of(2026, 9, 8, 8, 0, 0, 0, ZoneId.of("UTC")).toInstant();

        UserDigestPreference eightAm = UserDigestPreference.builder()
            .id(1L)
            .username("alice")
            .connectionId("conn-1")
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.DBA)
            .cronExpression("0 0 8 * * *")
            .timezone("UTC")
            .build();

        UserDigestPreference nineAm = UserDigestPreference.builder()
            .id(2L)
            .username("bob")
            .connectionId("conn-1")
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.EXEC)
            .cronExpression("0 0 9 * * *")
            .timezone("UTC")
            .build();

        when(preferenceRepository.countByEnabledTrue()).thenReturn(2L);
        when(preferenceRepository.findByEnabledTrueAndDeliveryMethod(DigestDeliveryMethod.SLACK_DM))
            .thenReturn(List.of(eightAm, nineAm));

        stubUser("alice", "DBA");
        stubUser("bob", "ADMIN");

        when(assemblerService.assembleDigest(eq("alice"), eq("conn-1"), eq(Role.DBA), eq(PersonaTag.DBA), any()))
            .thenReturn(DigestAssemblyResult.empty("alice", "conn-1", Role.DBA, PersonaTag.DBA));

        service.processDigestTick(atEightUtc);

        verify(assemblerService, times(1)).assembleDigest(
            eq("alice"), eq("conn-1"), eq(Role.DBA), eq(PersonaTag.DBA), any());
        verify(assemblerService, never()).assembleDigest(
            eq("bob"), anyString(), any(), any(), any());

        ArgumentCaptor<SlackDigestLog> captor = ArgumentCaptor.forClass(SlackDigestLog.class);
        verify(digestLogRepository, times(1)).save(captor.capture());
        assertThat(captor.getValue().getRecipientUsername()).isEqualTo("alice");
    }

    @Test
    void legacyWithoutPrefs_skipsPerUserPath() {
        Instant atTen = ZonedDateTime.of(2026, 9, 8, 10, 0, 0, 0, ZoneId.of("UTC")).toInstant();

        when(preferenceRepository.countByEnabledTrue()).thenReturn(0L);

        service.processDigestTick(atTen);

        verify(preferenceRepository, never()).findByEnabledTrueAndDeliveryMethod(any());
        verify(assemblerService, never()).assembleDigest(anyString(), anyString(), any(), any(), any());
        verify(digestLogRepository, never()).save(any());
    }

    @Test
    void timezoneHandling_firesInUserZoneNotUtc() {
        // 09:00 America/New_York in Sep = 13:00 UTC
        Instant utcThirteen = ZonedDateTime.of(2026, 9, 8, 13, 0, 0, 0, ZoneId.of("UTC")).toInstant();

        UserDigestPreference nyNine = UserDigestPreference.builder()
            .id(3L)
            .username("carol")
            .connectionId("conn-ny")
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .personaTag(PersonaTag.APP_ENG)
            .cronExpression("0 0 9 * * *")
            .timezone("America/New_York")
            .build();

        when(preferenceRepository.countByEnabledTrue()).thenReturn(1L);
        when(preferenceRepository.findByEnabledTrueAndDeliveryMethod(DigestDeliveryMethod.SLACK_DM))
            .thenReturn(List.of(nyNine));
        // Global cron 09:00 UTC is not due at 13:00 UTC
        lenient().when(preferenceRepository.findEnabledForConnection(anyString())).thenReturn(List.of(nyNine));

        stubUser("carol", "DEVELOPER");
        when(assemblerService.assembleDigest(
            eq("carol"), eq("conn-ny"), eq(Role.DEVELOPER), eq(PersonaTag.APP_ENG), any()))
            .thenReturn(DigestAssemblyResult.empty("carol", "conn-ny", Role.DEVELOPER, PersonaTag.APP_ENG));

        service.processDigestTick(utcThirteen);

        verify(assemblerService).assembleDigest(
            eq("carol"), eq("conn-ny"), eq(Role.DEVELOPER), eq(PersonaTag.APP_ENG), any());
    }

    @Test
    void alreadyDeliveredThisWindow_isSkipped() {
        Instant atNine = ZonedDateTime.of(2026, 9, 8, 9, 0, 0, 0, ZoneId.of("UTC")).toInstant();

        UserDigestPreference pref = UserDigestPreference.builder()
            .id(4L)
            .username("dave")
            .connectionId("conn-1")
            .enabled(true)
            .deliveryMethod(DigestDeliveryMethod.SLACK_DM)
            .cronExpression("0 0 9 * * *")
            .timezone("UTC")
            .build();

        when(preferenceRepository.countByEnabledTrue()).thenReturn(1L);
        when(preferenceRepository.findByEnabledTrueAndDeliveryMethod(DigestDeliveryMethod.SLACK_DM))
            .thenReturn(List.of(pref));
        when(digestLogRepository.existsByPreferenceIdAndConnectionIdAndSentAtGreaterThanEqual(
            eq(4L), eq("conn-1"), any(LocalDateTime.class))).thenReturn(true);

        service.processDigestTick(atNine);

        verify(assemblerService, never()).assembleDigest(anyString(), anyString(), any(), any(), any());
        verify(digestLogRepository, never()).save(any());
    }

    private void stubUser(String username, String role) {
        User user = new User();
        user.setUsername(username);
        user.setRole(role);
        lenient().when(userRepository.findByUsernameIgnoreCase(username)).thenReturn(Optional.of(user));
    }
}
