package com.dbaagent.service;

import com.dbaagent.model.DatabaseConnection;
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
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Admin Run Now / triggerDigest must use the hybrid personalized path,
 * not legacy-only sendDailyDigest().
 */
@ExtendWith(MockitoExtension.class)
class DigestTriggerHybridTest {

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
    }

    @Test
    void sendDailyDigestAsync_invokesHybridNotLegacy() {
        SlackDailyDigestService spyService = spy(service);
        doNothing().when(spyService).sendDailyDigestHybrid();

        spyService.sendDailyDigestAsync();

        verify(spyService, timeout(3000)).sendDailyDigestHybrid();
        verify(spyService, never()).sendDailyDigest();
    }

    @Test
    void triggerDigest_withPrefsAndNoChannelBindings_mentionsPersonalizedDm() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("ec2-replica");
        when(credentialService.getAllConnections()).thenReturn(List.of(connection));
        when(channelBindingRepository.findAll()).thenReturn(List.of());
        when(preferenceRepository.hasAnyPreferences()).thenReturn(true);
        when(slackRuntimeSettingsService.current()).thenReturn(
            new SlackRuntimeSettingsService.SlackRuntimeConfig(true, false, null, "xoxb-test", null, null));

        SlackDailyDigestService spyService = spy(service);
        doNothing().when(spyService).sendDailyDigestAsync();

        SlackDailyDigestService.TriggerResult result = spyService.triggerDigest();

        assertThat(result.triggered()).isTrue();
        assertThat(result.message()).containsIgnoringCase("personalized");
        assertThat(result.message()).containsIgnoringCase("DM");
        assertThat(result.message()).doesNotContain("only appear in the app");
        verify(spyService).sendDailyDigestAsync();
    }

    @Test
    void triggerDigest_legacyWithoutBindings_stillMentionsChannelBindings() {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId("conn-1");
        when(credentialService.getAllConnections()).thenReturn(List.of(connection));
        when(channelBindingRepository.findAll()).thenReturn(List.of());
        when(channelBindingRepository.count()).thenReturn(0L);
        when(preferenceRepository.hasAnyPreferences()).thenReturn(false);
        when(slackRuntimeSettingsService.current()).thenReturn(
            new SlackRuntimeSettingsService.SlackRuntimeConfig(true, false, null, "xoxb-test", null, null));

        SlackDailyDigestService spyService = spy(service);
        doNothing().when(spyService).sendDailyDigestAsync();

        SlackDailyDigestService.TriggerResult result = spyService.triggerDigest();

        assertThat(result.triggered()).isTrue();
        assertThat(result.message()).contains("No Slack channel bindings");
        assertThat(result.message()).doesNotContainIgnoringCase("personalized");
    }
}
