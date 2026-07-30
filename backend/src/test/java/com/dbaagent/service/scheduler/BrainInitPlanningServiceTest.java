package com.dbaagent.service.scheduler;

import com.dbaagent.model.ConnectionInitStatus;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.InitStage;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.ConnectionInitStatusRepository;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.RagDocumentStateService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.VectorSearchService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class BrainInitPlanningServiceTest {

    @Mock private ConnectionInitStatusRepository initStatusRepository;
    @Mock private CredentialRepository credentialRepository;
    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;
    @Mock private ColumnProfileRepository columnProfileRepository;
    @Mock private QueryLineageRepository queryLineageRepository;
    @Mock private SlowQueryHistoryRepository slowQueryHistoryRepository;
    @Mock private SchemaDocumentationRepository schemaDocumentationRepository;
    @Mock private CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    @Mock private RagDocumentStateService ragDocumentStateService;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private VectorSearchService vectorSearchService;

    private BrainInitPlanningService service;

    @BeforeEach
    void setUp() {
        service = new BrainInitPlanningService(
            initStatusRepository,
            credentialRepository,
            schemaSnapshotRepository,
            columnProfileRepository,
            queryLineageRepository,
            slowQueryHistoryRepository,
            schemaDocumentationRepository,
            companyKnowledgeEntryRepository,
            ragDocumentStateService,
            schemaScannerService,
            vectorSearchService,
            new ObjectMapper()
        );
        ReflectionTestUtils.setField(service, "refreshIntervalHours", 24L);
        ReflectionTestUtils.setField(service, "profileTtlHours", 24L);
    }

    @Test
    void planRefresh_withFreshInitAndNoDirtySources_returnsQuickVerifySkip() {
        String connectionId = "conn-1";
        LocalDateTime now = LocalDateTime.now();
        when(initStatusRepository.findById(connectionId)).thenReturn(Optional.of(completedStatus(connectionId, now.minusHours(2))));
        when(credentialRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, true)));
        when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(connectionId))
            .thenReturn(Optional.of(SchemaSnapshot.builder().connectionId(connectionId).schemaHash("abc123").capturedAt(now.minusHours(2)).build()));
        when(columnProfileRepository.countByConnectionId(connectionId)).thenReturn(10L);
        when(columnProfileRepository.findLatestProfiledAt(connectionId)).thenReturn(now.minusHours(2).minusMinutes(30));
        when(queryLineageRepository.findLatestCreatedAt(connectionId)).thenReturn(null);
        when(slowQueryHistoryRepository.findLatestCreatedAt(connectionId)).thenReturn(null);
        when(schemaDocumentationRepository.findLatestTouchedAt(connectionId)).thenReturn(now.minusHours(2).minusMinutes(5));
        when(companyKnowledgeEntryRepository.findLatestTouchedAt(connectionId)).thenReturn(null);

        BrainInitPlan plan = service.planRefresh(connectionId, false);

        assertThat(plan.mode()).isEqualTo(BrainInitPlanMode.QUICK_VERIFY);
        assertThat(plan.skipped()).isTrue();
        assertThat(plan.dirtySources()).isEmpty();
    }

    @Test
    void planRefresh_withRecentSchemaDocChange_startsAtRagEmbedding() {
        String connectionId = "conn-2";
        LocalDateTime now = LocalDateTime.now();
        when(initStatusRepository.findById(connectionId)).thenReturn(Optional.of(completedStatus(connectionId, now.minusHours(1))));
        when(credentialRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, true)));
        when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(connectionId))
            .thenReturn(Optional.of(SchemaSnapshot.builder().connectionId(connectionId).schemaHash("abc123").capturedAt(now.minusHours(1)).build()));
        when(columnProfileRepository.countByConnectionId(connectionId)).thenReturn(10L);
        when(columnProfileRepository.findLatestProfiledAt(connectionId)).thenReturn(now.minusHours(1).minusMinutes(30));
        when(queryLineageRepository.findLatestCreatedAt(connectionId)).thenReturn(null);
        when(slowQueryHistoryRepository.findLatestCreatedAt(connectionId)).thenReturn(null);
        when(schemaDocumentationRepository.findLatestTouchedAt(connectionId)).thenReturn(now);
        when(companyKnowledgeEntryRepository.findLatestTouchedAt(connectionId)).thenReturn(null);

        BrainInitPlan plan = service.planRefresh(connectionId, false);

        assertThat(plan.mode()).isEqualTo(BrainInitPlanMode.FULL_OR_PARTIAL_REFRESH);
        assertThat(plan.startedFromStage()).isEqualTo(InitStage.RAG_EMBEDDING);
        assertThat(plan.dirtySources()).containsExactly("docsDirty");
    }

    @Test
    void planRefresh_withNewQueryEvidence_startsAtKeyColumnAnalysis() {
        String connectionId = "conn-3";
        LocalDateTime now = LocalDateTime.now();
        when(initStatusRepository.findById(connectionId)).thenReturn(Optional.of(completedStatus(connectionId, now.minusHours(3))));
        when(credentialRepository.findById(connectionId)).thenReturn(Optional.of(connection(connectionId, true)));
        when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(connectionId))
            .thenReturn(Optional.of(SchemaSnapshot.builder().connectionId(connectionId).schemaHash("abc123").capturedAt(now.minusHours(3)).build()));
        when(columnProfileRepository.countByConnectionId(connectionId)).thenReturn(10L);
        when(columnProfileRepository.findLatestProfiledAt(connectionId)).thenReturn(now.minusHours(3).minusMinutes(30));
        when(queryLineageRepository.findLatestCreatedAt(connectionId)).thenReturn(now.minusMinutes(5));
        when(slowQueryHistoryRepository.findLatestCreatedAt(connectionId)).thenReturn(null);
        when(schemaDocumentationRepository.findLatestTouchedAt(connectionId)).thenReturn(now.minusHours(3).minusMinutes(5));
        when(companyKnowledgeEntryRepository.findLatestTouchedAt(connectionId)).thenReturn(null);

        BrainInitPlan plan = service.planRefresh(connectionId, false);

        assertThat(plan.startedFromStage()).isEqualTo(InitStage.KEY_COLUMN_ANALYSIS);
        assertThat(plan.dirtySources()).containsExactly("queryEvidenceDirty");
    }

    @Test
    void planRefresh_withFailedRun_returnsResumeFailedPlan() {
        String connectionId = "conn-4";
        Map<String, ConnectionInitStatus.StageTimingEntry> timings = new HashMap<>();
        timings.put(InitStage.RAG_EMBEDDING.name(),
            new ConnectionInitStatus.StageTimingEntry("2026-03-30T10:06:00Z", "2026-03-30T10:06:30Z", 30_000));

        when(initStatusRepository.findById(connectionId)).thenReturn(Optional.of(ConnectionInitStatus.builder()
            .connectionId(connectionId)
            .currentStage(InitStage.FAILED)
            .activeRunId(UUID.randomUUID())
            .stageTimings(timings)
            .build()));

        BrainInitPlan plan = service.planRefresh(connectionId, false);

        assertThat(plan.mode()).isEqualTo(BrainInitPlanMode.RESUME_FAILED);
        assertThat(plan.startedFromStage()).isEqualTo(InitStage.RAG_EMBEDDING);
        assertThat(plan.skipped()).isFalse();
    }

    @Test
    void planRefresh_withForce_startsFromSchemaScan() {
        String connectionId = "conn-5";
        when(initStatusRepository.findById(connectionId)).thenReturn(Optional.of(completedStatus(connectionId, LocalDateTime.now())));

        BrainInitPlan plan = service.planRefresh(connectionId, true);

        assertThat(plan.mode()).isEqualTo(BrainInitPlanMode.FULL_OR_PARTIAL_REFRESH);
        assertThat(plan.startedFromStage()).isEqualTo(InitStage.SCHEMA_SCAN);
        assertThat(plan.dirtySources()).containsExactly("force");
    }

    private ConnectionInitStatus completedStatus(String connectionId, LocalDateTime completedAt) {
        Map<String, Map<String, Object>> stageDetails = new HashMap<>();
        stageDetails.put(InitStage.SCHEMA_SCAN.name(), Map.of("schemaFingerprint", "abc123"));
        stageDetails.put(InitStage.DATA_SAMPLING.name(), Map.of(
            "samplingEnabled", true,
            "profiledAtWatermark", completedAt.minusMinutes(30).toString()
        ));
        stageDetails.put(InitStage.KEY_COLUMN_ANALYSIS.name(), Map.of(
            "queryEvidenceWatermark", completedAt.minusHours(1).toString()
        ));
        stageDetails.put(InitStage.RAG_EMBEDDING.name(), Map.of(
            "schemaDocsWatermark", completedAt.minusMinutes(5).toString()
        ));
        return ConnectionInitStatus.builder()
            .connectionId(connectionId)
            .currentStage(InitStage.COMPLETED)
            .completedAt(completedAt)
            .stageDetails(stageDetails)
            .build();
    }

    private DatabaseConnection connection(String connectionId, boolean samplingEnabled) {
        DatabaseConnection connection = new DatabaseConnection();
        connection.setId(connectionId);
        connection.setEnableDataSampling(samplingEnabled);
        return connection;
    }
}
