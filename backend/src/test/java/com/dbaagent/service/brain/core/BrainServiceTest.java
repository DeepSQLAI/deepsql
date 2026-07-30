package com.dbaagent.service.brain.core;

import com.dbaagent.model.TrainingJobHistory;
import com.dbaagent.model.TrainingJobStatus;
import com.dbaagent.model.brain.BrainUnderstandingResponse;
import com.dbaagent.repository.*;
import com.dbaagent.repository.brain.BrainScoreSnapshotRepository;
import com.dbaagent.service.SchemaDriftListener;
import com.dbaagent.service.SqlUsageService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("BrainService Unit Tests")
class BrainServiceTest {

    @Mock private SchemaDocumentationRepository schemaDocumentationRepository;
    @Mock private QueryExampleRepository queryExampleRepository;
    @Mock private TrainingJobHistoryRepository trainingJobHistoryRepository;
    @Mock private AnalysisHistoryRepository analysisHistoryRepository;
    @Mock private BrainScoreSnapshotRepository brainScoreSnapshotRepository;
    @Mock private ColumnProfileRepository columnProfileRepository;
    @Mock private ColumnDisambiguationRepository columnDisambiguationRepository;
    @Mock private QueryLineageRepository queryLineageRepository;
    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;
    @Mock private ObjectMapper objectMapper;
    @Mock private SqlUsageService sqlUsageService;
    @Mock private SchemaDriftListener schemaDriftListener;

    private BrainService brainService;

    @BeforeEach
    void setUp() {
        brainService = new BrainService(
            schemaDocumentationRepository,
            queryExampleRepository,
            trainingJobHistoryRepository,
            analysisHistoryRepository,
            brainScoreSnapshotRepository,
            columnProfileRepository,
            columnDisambiguationRepository,
            queryLineageRepository,
            schemaSnapshotRepository,
            objectMapper,
            sqlUsageService,
            schemaDriftListener
        );
    }

    // ─── getUnderstanding ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("getUnderstanding")
    class GetUnderstanding {

        @BeforeEach
        void stubEmptyRepos() {
            when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(anyString()))
                .thenReturn(Optional.empty());
            when(schemaDocumentationRepository.findByConnectionId(anyString()))
                .thenReturn(List.of());
            when(columnProfileRepository.findByConnectionId(anyString()))
                .thenReturn(List.of());
            when(columnDisambiguationRepository.findByConnectionId(anyString()))
                .thenReturn(List.of());
            when(queryLineageRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());
            when(queryExampleRepository.findRecentSuccessful(anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());
            when(analysisHistoryRepository.findByConnectionIdSince(anyString(), any(LocalDateTime.class)))
                .thenReturn(List.of());
            when(trainingJobHistoryRepository
                .findTopByConnectionIdAndStatusOrderByCompletedAtDesc(anyString(), any()))
                .thenReturn(Optional.empty());
            when(brainScoreSnapshotRepository.findTopByConnectionIdOrderByCreatedAtDesc(anyString()))
                .thenReturn(Optional.empty());
            when(brainScoreSnapshotRepository.findByConnectionIdOrderByCreatedAtDesc(anyString(), any(Pageable.class)))
                .thenReturn(List.of());
        }

        @Test
        @DisplayName("returns valid response with 0 tables and 0 columns when no schema exists")
        void returnsEmptyResponse_whenNoSchemaSnapshot() {
            BrainUnderstandingResponse response = brainService.getUnderstanding("conn-1");

            assertThat(response).isNotNull();
            assertThat(response.getConnectionId()).isEqualTo("conn-1");
            assertThat(response.getTableCount()).isZero();
            assertThat(response.getColumnCount()).isZero();
            assertThat(response.getTables()).isEmpty();
            assertThat(response.getColumns()).isEmpty();
            assertThat(response.getNeedsInput()).isEmpty();
        }

        @Test
        @DisplayName("returns overall score of 0 when there are no tables")
        void returnsZeroScore_whenNoTables() {
            BrainUnderstandingResponse response = brainService.getUnderstanding("conn-1");

            assertThat(response.getOverallScore()).isZero();
        }

        @Test
        @DisplayName("trend is steady and delta is 0 when score history is empty")
        void trendIsSteady_whenNoHistory() {
            BrainUnderstandingResponse response = brainService.getUnderstanding("conn-1");

            assertThat(response.getTrendDirection()).isEqualTo("steady");
            assertThat(response.getTrendDelta()).isZero();
        }

        @Test
        @DisplayName("lastTrainingAt is null when no completed training job exists")
        void lastTrainingAtIsNull_whenNoCompletedJob() {
            BrainUnderstandingResponse response = brainService.getUnderstanding("conn-1");

            assertThat(response.getLastTrainingAt()).isNull();
        }

        @Test
        @DisplayName("lastTrainingAt is set when a completed training job exists")
        void lastTrainingAtIsSet_whenCompletedJobExists() {
            LocalDateTime completedAt = LocalDateTime.of(2026, 1, 15, 10, 0);
            TrainingJobHistory job = new TrainingJobHistory();
            job.setCompletedAt(completedAt);

            when(trainingJobHistoryRepository
                .findTopByConnectionIdAndStatusOrderByCompletedAtDesc(
                    eq("conn-1"), eq(TrainingJobStatus.Status.COMPLETED)))
                .thenReturn(Optional.of(job));

            BrainUnderstandingResponse response = brainService.getUnderstanding("conn-1");

            assertThat(response.getLastTrainingAt()).isEqualTo(completedAt);
        }

        @Test
        @DisplayName("saves a new score snapshot on first call when none exists")
        void savesSnapshot_onFirstCall() {
            brainService.getUnderstanding("conn-1");

            verify(brainScoreSnapshotRepository).save(any());
        }

        @Test
        @DisplayName("does not interact with schemaDriftListener when schema is empty")
        void noSchemaDriftEvents_whenSchemaIsEmpty() {
            brainService.getUnderstanding("conn-1");

            verifyNoInteractions(schemaDriftListener);
        }
    }
}
