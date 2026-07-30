package com.dbaagent.service;

import com.dbaagent.repository.CredentialRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PerformanceRecommendationRefreshSchedulerTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private IndexRecommendationService indexRecommendationService;
    @Mock private PerformanceActionAggregatorService performanceActionAggregatorService;

    @InjectMocks private PerformanceRecommendationRefreshScheduler scheduler;

    @Test
    void refreshAllConnections_refreshesEachConnection() {
        ReflectionTestUtils.setField(scheduler, "refreshEnabled", true);
        when(credentialRepository.findAllIds()).thenReturn(List.of("conn-1", "conn-2"));

        scheduler.refreshAllConnections();

        verify(indexRecommendationService).refreshRecommendations("conn-1");
        verify(indexRecommendationService).refreshRecommendations("conn-2");
        verify(performanceActionAggregatorService).refreshActions("conn-1");
        verify(performanceActionAggregatorService).refreshActions("conn-2");
    }

    @Test
    void refreshAllConnections_continuesWhenOneConnectionFails() {
        ReflectionTestUtils.setField(scheduler, "refreshEnabled", true);
        when(credentialRepository.findAllIds()).thenReturn(List.of("conn-1", "conn-2"));
        doThrow(new RuntimeException("boom")).when(indexRecommendationService).refreshRecommendations("conn-1");

        scheduler.refreshAllConnections();

        verify(indexRecommendationService).refreshRecommendations("conn-1");
        verify(indexRecommendationService).refreshRecommendations("conn-2");
        verify(performanceActionAggregatorService, never()).refreshActions("conn-1");
        verify(performanceActionAggregatorService).refreshActions("conn-2");
    }

    @Test
    void refreshAllConnections_skipsWhenDisabled() {
        ReflectionTestUtils.setField(scheduler, "refreshEnabled", false);

        scheduler.refreshAllConnections();

        verify(credentialRepository, never()).findAllIds();
    }
}
