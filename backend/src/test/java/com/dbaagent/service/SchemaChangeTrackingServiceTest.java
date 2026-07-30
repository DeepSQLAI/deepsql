package com.dbaagent.service;

import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SchemaDriftConfigRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(MockitoExtension.class)
class SchemaChangeTrackingServiceTest {

    @Mock private SchemaSnapshotRepository snapshotRepository;
    @Mock private SchemaChangeRepository changeRepository;
    @Mock private SchemaDriftConfigRepository driftConfigRepository;
    @Mock private SchemaIntrospectionService introspectionService;

    @Test
    void detectChanges_supportsArrayShapedTableSnapshots() {
        SchemaChangeTrackingService service = newService();

        SchemaSnapshot previous = snapshot("snap-1", """
            {
              "tables": [
                {"name": "users"},
                {"name": "roles"}
              ]
            }
            """);

        SchemaSnapshot latest = snapshot("snap-2", """
            {
              "tables": [
                {"name": "users"},
                {"name": "roles"},
                {"name": "v2_auth_config"}
              ]
            }
            """);

        List<SchemaChange> changes = service.detectChanges(previous, latest);

        assertThat(changes)
            .extracting(SchemaChange::getChangeType, SchemaChange::getObjectName)
            .contains(org.assertj.core.groups.Tuple.tuple(
                SchemaChange.ChangeType.TABLE_ADDED,
                "v2_auth_config"
            ));
    }

    @Test
    void detectChanges_supportsAddedColumnsInArrayShapedTableSnapshots() {
        SchemaChangeTrackingService service = newService();

        SchemaSnapshot previous = snapshot("snap-1", """
            {
              "tables": [
                {
                  "name": "v2_auth_config",
                  "columns": [
                    {"name": "id", "dataType": "uuid", "columnType": "uuid", "isNullable": false}
                  ]
                }
              ]
            }
            """);

        SchemaSnapshot latest = snapshot("snap-2", """
            {
              "tables": [
                {
                  "name": "v2_auth_config",
                  "columns": [
                    {"name": "id", "dataType": "uuid", "columnType": "uuid", "isNullable": false},
                    {"name": "is_enabled", "dataType": "boolean", "columnType": "boolean", "isNullable": false}
                  ]
                }
              ]
            }
            """);

        List<SchemaChange> changes = service.detectChanges(previous, latest);

        assertThat(changes)
            .extracting(SchemaChange::getChangeType, SchemaChange::getObjectName, SchemaChange::getTableName)
            .contains(org.assertj.core.groups.Tuple.tuple(
                SchemaChange.ChangeType.COLUMN_ADDED,
                "is_enabled",
                "v2_auth_config"
            ));
    }

    @Test
    void detectChanges_supportsMixedSnapshotShapesForOtherSchemaObjects() {
        SchemaChangeTrackingService service = newService();

        SchemaSnapshot previous = snapshot("snap-1", """
            {
              "tables": {
                "v2_auth_config": {
                  "name": "v2_auth_config",
                  "indexes": [],
                  "constraints": [],
                  "foreignKeys": []
                }
              }
            }
            """);

        SchemaSnapshot latest = snapshot("snap-2", """
            {
              "tables": [
                {
                  "name": "v2_auth_config",
                  "indexes": [
                    {"name": "idx_v2_auth_config_org_id", "columns": ["org_id"], "isUnique": false, "isPrimary": false}
                  ],
                  "constraints": [
                    {"name": "chk_v2_auth_config_state", "type": "CHECK", "columns": ["state"]}
                  ],
                  "foreignKeys": [
                    {"name": "fk_v2_auth_config_org", "column": "org_id", "referencedTable": "org", "referencedColumn": "id"}
                  ]
                }
              ]
            }
            """);

        List<SchemaChange> changes = service.detectChanges(previous, latest);

        assertThat(changes)
            .extracting(SchemaChange::getChangeType, SchemaChange::getObjectName, SchemaChange::getTableName)
            .contains(
                org.assertj.core.groups.Tuple.tuple(
                    SchemaChange.ChangeType.INDEX_ADDED,
                    "idx_v2_auth_config_org_id",
                    "v2_auth_config"
                ),
                org.assertj.core.groups.Tuple.tuple(
                    SchemaChange.ChangeType.CONSTRAINT_ADDED,
                    "chk_v2_auth_config_state",
                    "v2_auth_config"
                ),
                org.assertj.core.groups.Tuple.tuple(
                    SchemaChange.ChangeType.FOREIGN_KEY_ADDED,
                    "fk_v2_auth_config_org",
                    "v2_auth_config"
                )
            );
    }

    private SchemaChangeTrackingService newService() {
        return new SchemaChangeTrackingService(
            snapshotRepository,
            changeRepository,
            driftConfigRepository,
            introspectionService,
            new ObjectMapper(),
            org.mockito.Mockito.mock(SchemaDriftListener.class),
            org.mockito.Mockito.mock(com.dbaagent.service.SchemaSnapshotService.class)
        );
    }

    private SchemaSnapshot snapshot(String id, String schemaJson) {
        return SchemaSnapshot.builder()
            .id(id)
            .connectionId("conn-1")
            .schemaJson(schemaJson)
            .build();
    }
}
