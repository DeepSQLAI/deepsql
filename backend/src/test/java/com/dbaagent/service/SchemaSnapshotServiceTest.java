package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.DatabaseObject;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SchemaSnapshotServiceTest {

    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;
    @Mock private SchemaScannerService schemaScannerService;

    private SchemaSnapshotService schemaSnapshotService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        schemaSnapshotService = new SchemaSnapshotService(
            schemaSnapshotRepository,
            schemaScannerService,
            org.mockito.Mockito.mock(com.dbaagent.service.SchemaIntrospectionService.class),
            objectMapper
        );
    }

    @Test
    void getLatestDatabaseObjects_returnsTablesAndViewsFromLatestSnapshot() throws Exception {
        SchemaMetadata metadata = new SchemaMetadata();
        metadata.setDatabaseName("analytics_db");
        metadata.setTables(List.of(
            new TableMetadata(
                "ACCOUNTS",
                "analytics_db",
                "table",
                42L,
                1024L,
                List.of(
                    new ColumnMetadata("account_id", "bigint", null, false, true, null, 1),
                    new ColumnMetadata("account_name", "varchar", 255L, true, false, null, 2)
                ),
                List.of()
            ),
            new TableMetadata(
                "HOTEL_VIEW",
                "analytics_db",
                "view",
                0L,
                0L,
                List.of(
                    new ColumnMetadata("property_name", "varchar", 255L, true, false, null, 1)
                ),
                List.of()
            )
        ));

        SchemaSnapshot snapshot = SchemaSnapshot.builder()
            .id("snap-1")
            .connectionId("conn-1")
            .schemaJson(objectMapper.writeValueAsString(metadata))
            .build();

        when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc("conn-1"))
            .thenReturn(Optional.of(snapshot));

        List<DatabaseObject> objects = schemaSnapshotService.getLatestDatabaseObjects("conn-1");

        assertThat(objects).hasSize(2);
        assertThat(objects.get(0).getName()).isEqualTo("ACCOUNTS");
        assertThat(objects.get(0).getSchema()).isEqualTo("analytics_db");
        assertThat(objects.get(0).getType()).isEqualTo("table");
        assertThat(objects.get(0).getColumns()).extracting("name")
            .containsExactly("account_id", "account_name");
        assertThat(objects.get(1).getType()).isEqualTo("view");
    }

    @Test
    void getLatestDatabaseObjects_returnsEmptyListWhenSnapshotMissing() {
        when(schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc("conn-1"))
            .thenReturn(Optional.empty());

        assertThat(schemaSnapshotService.getLatestDatabaseObjects("conn-1")).isEmpty();
    }
}
