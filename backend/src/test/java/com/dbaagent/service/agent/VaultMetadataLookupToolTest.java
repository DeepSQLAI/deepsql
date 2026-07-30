package com.dbaagent.service.agent;

import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.TableRelationshipClassificationRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.service.SemanticModelService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VaultMetadataLookupToolTest {

    @Mock private InferredTableRelationshipRepository inferredRelRepo;
    @Mock private TableRelationshipClassificationRepository relClassRepo;
    @Mock private KeyColumnAnalysisRepository keyColumnRepo;
    @Mock private SlowQueryHistoryRepository slowQueryRepo;
    @Mock private TableClassificationRepository tableClassRepo;
    @Mock private GrowthAnomalyRepository growthRepo;
    @Mock private WorkloadProfileRepository workloadRepo;
    @Mock private KnobRankingRepository knobRepo;
    @Mock private SemanticModelService semanticModelService;
    @Mock private CredentialRepository credentialRepository;
    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;

    @Test
    void execute_schemaSnapshotQuestion_resolvesTargetConnectionAndReturnsCount() {
        VaultMetadataLookupTool tool = new VaultMetadataLookupTool(
            inferredRelRepo,
            relClassRepo,
            keyColumnRepo,
            slowQueryRepo,
            tableClassRepo,
            growthRepo,
            workloadRepo,
            knobRepo,
            semanticModelService,
            credentialRepository,
            schemaSnapshotRepository
        );

        DatabaseConnection active = new DatabaseConnection();
        active.setId("vault-1");
        active.setConnectionName("mylocalpg");

        DatabaseConnection target = new DatabaseConnection();
        target.setId("conn-2");
        target.setConnectionName("aws_sf_rds");

        when(credentialRepository.findAllByOrderByCreatedAtDesc()).thenReturn(List.of(target, active));
        when(schemaSnapshotRepository.countByConnectionId("conn-2")).thenReturn(14L);
        when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc("conn-2"))
            .thenReturn(Optional.of(SchemaSnapshot.builder()
                .connectionId("conn-2")
                .capturedAt(LocalDateTime.of(2026, 4, 11, 9, 30))
                .snapshotType(SchemaSnapshot.SnapshotType.SCHEDULED)
                .schemaJson("{}")
                .build()));

        AgentExecutionContext context = new AgentExecutionContext("vault-1", "how many schema snapshots are there for aws_sf_rds connection?", null, "postgres");
        AgentToolResult result = tool.execute(
            new AgentPlanStep("vault-lookup", "Check cached metadata in vault DB", "vault_metadata_lookup_tool", Map.of("brainTopic", "SCHEMA")),
            context
        );

        assertThat(result.observation()).isNotNull();
        assertThat(result.observation().data()).containsEntry("answerType", "schema_snapshot_count");
        assertThat(result.observation().data()).containsEntry("connectionName", "aws_sf_rds");
        assertThat(result.observation().data()).containsEntry("snapshotCount", 14L);
        assertThat(context.<Boolean>getMemory("vaultDataSufficient")).isTrue();
    }
}
