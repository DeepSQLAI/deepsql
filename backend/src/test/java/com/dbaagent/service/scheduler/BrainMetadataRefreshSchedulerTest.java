package com.dbaagent.service.scheduler;

import com.dbaagent.model.InitStage;
import com.dbaagent.repository.CredentialRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrainMetadataRefreshSchedulerTest {

    @Mock private CredentialRepository credentialRepository;
    @Mock private BrainInitSchedulerService brainInitSchedulerService;
    @Mock private ObjectProvider<BrainInitSchedulerService> brainInitSchedulerServiceProvider;

    private BrainMetadataRefreshScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new BrainMetadataRefreshScheduler(
            credentialRepository,
            brainInitSchedulerServiceProvider
        );
        ReflectionTestUtils.setField(scheduler, "refreshEnabled", true);
        ReflectionTestUtils.setField(scheduler, "refreshIntervalHours", 24L);
        when(brainInitSchedulerServiceProvider.getIfAvailable()).thenReturn(brainInitSchedulerService);
    }

    @Test
    void refreshStaleConnections_schedulesOnlyNonSkippedPlans() {
        when(credentialRepository.findAllIds()).thenReturn(List.of("conn-missing", "conn-docs", "conn-fresh"));
        when(brainInitSchedulerService.planAndScheduleInit("conn-missing", false))
            .thenReturn(BrainInitPlan.refresh(InitStage.SCHEMA_SCAN, "missing", List.of("missingInit")));
        when(brainInitSchedulerService.planAndScheduleInit("conn-docs", false))
            .thenReturn(BrainInitPlan.refresh(InitStage.RAG_EMBEDDING, "docs", List.of("docsDirty")));
        when(brainInitSchedulerService.planAndScheduleInit("conn-fresh", false))
            .thenReturn(BrainInitPlan.quickVerifySkip("Up to date", List.of()));

        scheduler.refreshStaleConnections();

        verify(brainInitSchedulerService).planAndScheduleInit("conn-missing", false);
        verify(brainInitSchedulerService).planAndScheduleInit("conn-docs", false);
        verify(brainInitSchedulerService).planAndScheduleInit("conn-fresh", false);
    }

    @Test
    void refreshStaleConnections_handlesPlannerExceptions() {
        when(credentialRepository.findAllIds()).thenReturn(List.of("conn-active"));
        when(brainInitSchedulerService.planAndScheduleInit("conn-active", false))
            .thenThrow(new IllegalStateException("boom"));

        scheduler.refreshStaleConnections();

        verify(brainInitSchedulerService).planAndScheduleInit("conn-active", false);
    }
}
