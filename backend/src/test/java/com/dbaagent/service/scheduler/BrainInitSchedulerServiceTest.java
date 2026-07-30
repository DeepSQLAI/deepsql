package com.dbaagent.service.scheduler;

import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.InitStage;
import com.dbaagent.repository.ConnectionInitHistoryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.service.TrainingJobService;
import com.dbaagent.service.VectorSearchService;
import com.github.kagkarlsson.scheduler.SchedulerClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrainInitSchedulerServiceTest {

    @Mock private SchedulerClient schedulerClient;
    @Mock private ConnectionInitStatusRepository initStatusRepo;
    @Mock private ConnectionInitHistoryRepository initHistoryRepo;
    @Mock private TrainingJobService trainingJobService;
    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private BrainInitPlanningService brainInitPlanningService;
    @Mock private VectorSearchService vectorSearchService;
    @Mock private InferredTableRelationshipRepository inferredRelationshipRepository;

    private BrainInitSchedulerService service;

    @BeforeEach
    void setUp() {
        service = new BrainInitSchedulerService(
            schedulerClient,
            initStatusRepo,
            initHistoryRepo,
            trainingJobService,
            jdbcTemplate,
            brainInitPlanningService,
            vectorSearchService,
            inferredRelationshipRepository
        );
    }

    @Test
    void resumeFailedInit_resumesFromLatestStartedStageAndPrunesDownstreamState() {
        UUID oldRunId = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var stageTimings = new HashMap<String, ConnectionInitStatus.StageTimingEntry>();
        stageTimings.put(InitStage.SCHEMA_SCAN.name(),
            new ConnectionInitStatus.StageTimingEntry("2026-03-30T10:00:00Z", "2026-03-30T10:01:00Z", 60_000));
        stageTimings.put(InitStage.DATA_SAMPLING.name(),
            new ConnectionInitStatus.StageTimingEntry("2026-03-30T10:01:00Z", "2026-03-30T10:05:00Z", 240_000));
        stageTimings.put(InitStage.KEY_COLUMN_ANALYSIS.name(),
            new ConnectionInitStatus.StageTimingEntry("2026-03-30T10:05:00Z", "2026-03-30T10:06:00Z", 60_000));
        stageTimings.put(InitStage.RAG_EMBEDDING.name(),
            new ConnectionInitStatus.StageTimingEntry("2026-03-30T10:06:00Z", "2026-03-30T10:06:30Z", 30_000));

        var stageDetails = new HashMap<String, Map<String, Object>>();
        stageDetails.put(InitStage.KEY_COLUMN_ANALYSIS.name(), Map.of("columnsAnalyzed", 123));
        stageDetails.put(InitStage.RAG_EMBEDDING.name(), Map.of("documentsIndexed", 603));
        stageDetails.put(InitStage.BRAIN_ANALYSIS.name(), Map.of("method", "stale"));

        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.FAILED)
            .progressPercent(92)
            .stageMessage("Indexing table user_bookings for retrieval (500/500)")
            .startedAt(LocalDateTime.of(2026, 3, 30, 15, 0))
            .completedAt(LocalDateTime.of(2026, 3, 30, 15, 10))
            .errorMessage("IllegalStateException: 0 usable embeddings")
            .stageTimings(stageTimings)
            .stageDetails(stageDetails)
            .cancelRequested(false)
            .activeRunId(oldRunId)
            .build();

        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        InitStage resumedStage = service.resumeFailedInit("conn-1");

        assertEquals(InitStage.RAG_EMBEDDING, resumedStage);
        assertEquals(InitStage.RAG_EMBEDDING, status.getCurrentStage());
        assertEquals(80, status.getProgressPercent());
        assertEquals("Resuming RAG_EMBEDDING...", status.getStageMessage());
        assertNull(status.getCompletedAt());
        assertNull(status.getErrorMessage());
        assertNotEquals(oldRunId, status.getActiveRunId());

        assertTrue(status.getStageTimings().containsKey(InitStage.KEY_COLUMN_ANALYSIS.name()));
        assertFalse(status.getStageTimings().containsKey(InitStage.RAG_EMBEDDING.name()));
        assertFalse(status.getStageDetails().containsKey(InitStage.RAG_EMBEDDING.name()));
        assertFalse(status.getStageDetails().containsKey(InitStage.BRAIN_ANALYSIS.name()));

        verify(trainingJobService).broadcastInitProgress("conn-1", status);
        verify(schedulerClient).scheduleIfNotExists(any());
    }

    @Test
    void resumeFailedInit_rejectsNonFailedStatus() {
        var status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.DATA_SAMPLING)
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));

        var error = assertThrows(IllegalStateException.class,
            () -> service.resumeFailedInit("conn-1"));

        assertTrue(error.getMessage().contains("not in a failed state"));
    }

    @Test
    void planAndScheduleInit_skipsWhenPlannerReturnsQuickVerify() {
        when(brainInitPlanningService.planRefresh("conn-1", false))
            .thenReturn(BrainInitPlan.quickVerifySkip("Up to date", List.of()));

        BrainInitPlan plan = service.planAndScheduleInit("conn-1", false);

        assertTrue(plan.skipped());
        assertEquals(BrainInitPlanMode.QUICK_VERIFY, plan.mode());
    }

    @Test
    void planAndScheduleInit_schedulesPartialRefreshFromDirtyStage() {
        when(brainInitPlanningService.planRefresh("conn-1", false))
            .thenReturn(BrainInitPlan.refresh(
                InitStage.RAG_EMBEDDING,
                "Detected dirty sources: docsDirty",
                List.of("docsDirty")
            ));

        ConnectionInitStatus status = ConnectionInitStatus.builder()
            .connectionId("conn-1")
            .currentStage(InitStage.COMPLETED)
            .stageTimings(new HashMap<>())
            .stageDetails(new HashMap<>())
            .build();
        when(initStatusRepo.findById("conn-1")).thenReturn(Optional.of(status));
        when(initStatusRepo.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        BrainInitPlan plan = service.planAndScheduleInit("conn-1", false);

        assertFalse(plan.skipped());
        assertEquals(InitStage.RAG_EMBEDDING, plan.startedFromStage());
        assertEquals(InitStage.RAG_EMBEDDING, status.getCurrentStage());
        verify(schedulerClient).scheduleIfNotExists(any());
    }
}
