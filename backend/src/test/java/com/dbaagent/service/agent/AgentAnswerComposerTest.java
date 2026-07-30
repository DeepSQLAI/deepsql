package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class AgentAnswerComposerTest {

    private final AgentAnswerComposer composer = new AgentAnswerComposer();

    @Test
    void composeUniversal_returnsStoredUniversalMessageAndResult() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.UNIVERSAL_CHAT,
            "Answer safely",
            List.of(new AgentPlanStep("universal", "Resolve", "universal_chat_tool", Map.of()))
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "question", null, "mysql");
        QueryResult queryResult = new QueryResult(
            List.of("hotel_id", "events"),
            List.of(List.of("h-1", 44)),
            1,
            null,
            true,
            12L,
            "SELECT ..."
        );
        context.putMemory("universalMessage", "Hotels with the steepest usage drop are ...");
        context.putMemory("universalPrimaryResult", queryResult);
        context.putMemory("universalConfidence", 0.91d);

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("usage drop");
        assertThat(result.primaryResult()).isEqualTo(queryResult);
        assertThat(result.confidence()).isEqualTo(0.91d);
        assertThat(result.intent()).isEqualTo(AgentIntent.UNIVERSAL_CHAT);
    }

    @Test
    void composeUniversal_fallsBackToSafeFailureMessageWhenToolProducedNone() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.UNIVERSAL_CHAT,
            "Answer safely",
            List.of(new AgentPlanStep("universal", "Resolve", "universal_chat_tool", Map.of()))
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "question", null, "mysql");

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("did not collect enough verified evidence");
        assertThat(result.confidence()).isEqualTo(0.55d);
    }

    @Test
    void composeMetadataGeneral_surfacesAnchorTablesAndProposedSchemaWhenNoDirectModuleExists() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze schema metadata",
            List.of(
                new AgentPlanStep(
                    "vault-lookup",
                    "Check cached metadata in vault DB",
                    "vault_metadata_lookup_tool",
                    Map.of("brainTopic", "SCHEMA")
                )
            )
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "what are best tables to use to build task management module, or propose new tables", null, "mysql");
        context.recordToolExecution("vault_metadata_lookup_tool", new AgentToolResult(
            new AgentObservation(
                "vault_general",
                "Found metadata for 507 tables",
                Map.of(
                    "sufficient", true,
                    "tableCount", 507,
                    "noDirectModuleTablesFound", true,
                    "suggestedExistingTables", List.of(
                        Map.of("table", "INTELL_USERS", "reason", "best existing assignee/owner table if staff users live here", "columns", List.of("id", "hotel_id", "email")),
                        Map.of("table", "HOTEL", "reason", "useful scope table if tasks are hotel-level operational work", "columns", List.of("id", "name"))
                    ),
                    "proposedTables", List.of(
                        Map.of("table", "TASKS", "purpose", "core task record with title, description, priority, due date, status, owner, and optional booking/hotel links"),
                        Map.of("table", "TASK_ACTIVITY", "purpose", "immutable audit/activity stream for status changes, assignment changes, and reminders")
                    )
                )
            ),
            null,
            null,
            0.82
        ));

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("I do **not** see an obvious task-management module");
        assertThat(result.message()).contains("`INTELL_USERS`");
        assertThat(result.message()).contains("`HOTEL`");
        assertThat(result.message()).contains("`TASKS`");
        assertThat(result.message()).contains("`TASK_ACTIVITY`");
    }

    @Test
    void composeMetadataAnalysis_tableColumns_staysAnchoredOnRequestedTable() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze schema metadata",
            List.of(
                new AgentPlanStep(
                    "vault-lookup",
                    "Check cached metadata in vault DB",
                    "vault_metadata_lookup_tool",
                    Map.of("brainTopic", "SCHEMA")
                )
            )
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "What columns are there in HOTEL table?", null, "mysql");
        context.recordToolExecution("vault_metadata_lookup_tool", new AgentToolResult(
            new AgentObservation(
                "vault_schema",
                "Found 3 columns for HOTEL",
                Map.of(
                    "sufficient", true,
                    "answerType", "table_columns",
                    "tableName", "HOTEL",
                    "columnCount", 3,
                    "columns", List.of(
                        Map.of("column", "id", "type", "bigint", "primaryKey", true, "nullable", false),
                        Map.of("column", "name", "type", "varchar", "primaryKey", false, "nullable", false),
                        Map.of("column", "country", "type", "varchar", "primaryKey", false, "nullable", true)
                    )
                )
            ),
            null,
            null,
            0.96
        ));

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("Table `HOTEL` has **3 columns**");
        assertThat(result.message()).contains("Columns:");
        assertThat(result.message()).contains("`id` — `bigint`; primary key; not null");
        assertThat(result.message()).doesNotContain("| Column | Type | Attributes |");
        assertThat(result.message()).doesNotContain("Closest Existing Tables");
    }

    @Test
    void composeMetadataAnalysis_tableKeyColumns_staysAnchoredOnRequestedTable() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze key columns metadata",
            List.of(
                new AgentPlanStep(
                    "vault-lookup",
                    "Check cached metadata in vault DB",
                    "vault_metadata_lookup_tool",
                    Map.of("brainTopic", "KEY_COLUMNS")
                )
            )
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "Show me all key columns in ROOM_RESERVATIONS table", null, "mysql");
        context.recordToolExecution("vault_metadata_lookup_tool", new AgentToolResult(
            new AgentObservation(
                "vault_key_columns",
                "Found 4 exact key columns for ROOM_RESERVATIONS",
                Map.of(
                    "sufficient", true,
                    "answerType", "table_key_columns",
                    "tableName", "ROOM_RESERVATIONS",
                    "columnCount", 4,
                    "keyColumns", List.of(
                        Map.of("column", "id", "roles", List.of("Primary key"), "summary", "Primary key"),
                        Map.of("column", "booking_id", "roles", List.of("References BOOKINGS.id"), "summary", "References BOOKINGS.id"),
                        Map.of("column", "hotel_id", "roles", List.of("References HOTEL.id"), "summary", "References HOTEL.id"),
                        Map.of("column", "rate_plan_id", "roles", List.of("References RATE_PLAN.id"), "summary", "References RATE_PLAN.id")
                    )
                )
            ),
            null,
            null,
            0.96
        ));

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("`ROOM_RESERVATIONS`");
        assertThat(result.message()).contains("The most relevant key columns");
        assertThat(result.message()).contains("`booking_id` —");
        assertThat(result.message()).contains("References HOTEL.id");
        assertThat(result.message()).doesNotContain("| Column | Why it matters |");
        assertThat(result.message()).doesNotContain("Closest Existing Tables");
    }

    @Test
    void composeMetadataAnalysis_schemaSnapshotCount_formatsVaultAnswer() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze schema metadata",
            List.of(
                new AgentPlanStep(
                    "vault-lookup",
                    "Check cached metadata in vault DB",
                    "vault_metadata_lookup_tool",
                    Map.of("brainTopic", "SCHEMA")
                )
            )
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "How many schema snapshots are there for aws_sf_rds connection?", null, "postgres");
        context.recordToolExecution("vault_metadata_lookup_tool", new AgentToolResult(
            new AgentObservation(
                "vault_schema",
                "Found 14 schema snapshots for aws_sf_rds",
                Map.of(
                    "sufficient", true,
                    "answerType", "schema_snapshot_count",
                    "connectionId", "conn-2",
                    "connectionName", "aws_sf_rds",
                    "snapshotCount", 14L,
                    "latestCapturedAt", "2026-04-11T09:30:00",
                    "latestSnapshotType", "SCHEDULED"
                )
            ),
            null,
            null,
            0.98
        ));

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("`aws_sf_rds`");
        assertThat(result.message()).contains("**14 schema snapshots**");
        assertThat(result.message()).contains("SCHEDULED");
        assertThat(result.message()).contains("Vault DB schema snapshot history");
    }

    @Test
    void composeMetadataAnalysis_liveTableRowCountFormatsExactTableAnswer() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze schema metadata",
            List.of(
                new AgentPlanStep(
                    "vault-lookup",
                    "Check cached metadata in vault DB",
                    "vault_metadata_lookup_tool",
                    Map.of("brainTopic", "SCHEMA")
                ),
                new AgentPlanStep(
                    "live-query",
                    "Query live database metadata catalogs",
                    "live_metadata_query_tool",
                    Map.of("brainTopic", "SCHEMA")
                )
            )
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "How many rows in ACCOUNTS table?", null, "mysql");
        context.recordToolExecution("vault_metadata_lookup_tool", new AgentToolResult(
            new AgentObservation(
                "vault_schema",
                "Cached schema is missing row count for ACCOUNTS",
                Map.of(
                    "sufficient", false,
                    "answerType", "table_row_count",
                    "tableName", "ACCOUNTS"
                )
            ),
            null,
            null,
            0.1
        ));
        context.recordToolExecution("live_metadata_query_tool", new AgentToolResult(
            new AgentObservation(
                "live_metadata_result",
                "Queried live mysql metadata: 1 rows returned",
                Map.of(
                    "rowCount", 1,
                    "dbType", "mysql",
                    "topic", "SCHEMA",
                    "answerType", "table_row_count",
                    "tableName", "ACCOUNTS"
                )
            ),
            null,
            "SELECT TABLE_ROWS FROM information_schema.TABLES",
            0.85
        ));
        context.putMemory("liveMetadataAnswerType", "table_row_count");
        context.putMemory("liveMetadataTableName", "ACCOUNTS");
        context.putMemory("liveMetadataSql", "SELECT TABLE_ROWS FROM information_schema.TABLES");
        context.putMemory("liveMetadataResult", new QueryResult(
            List.of("TABLE_NAME", "row_count"),
            List.of(List.of("ACCOUNTS", 9419333L)),
            1,
            null,
            false,
            12L,
            "SELECT TABLE_ROWS FROM information_schema.TABLES"
        ));

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("Table `ACCOUNTS` has an estimated **9419333 rows** from the live database catalogs.");
        assertThat(result.message()).doesNotContain("Closest Existing Tables");
    }

    @Test
    void composeMetadataAnalysis_liveTableIndexesFormatsExactTableAnswer() {
        AgentPlan plan = new AgentPlan(
            AgentIntent.METADATA_ANALYSIS,
            "Analyze schema metadata",
            List.of(
                new AgentPlanStep(
                    "vault-lookup",
                    "Check cached metadata in vault DB",
                    "vault_metadata_lookup_tool",
                    Map.of("brainTopic", "SCHEMA")
                ),
                new AgentPlanStep(
                    "live-query",
                    "Query live database metadata catalogs",
                    "live_metadata_query_tool",
                    Map.of("brainTopic", "SCHEMA")
                )
            )
        );

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "What indexes are there on ACCOUNTS table?", null, "mysql");
        context.recordToolExecution("vault_metadata_lookup_tool", new AgentToolResult(
            new AgentObservation(
                "vault_schema",
                "Cached schema is missing index metadata for ACCOUNTS",
                Map.of(
                    "sufficient", false,
                    "answerType", "table_indexes",
                    "tableName", "ACCOUNTS"
                )
            ),
            null,
            null,
            0.1
        ));
        context.recordToolExecution("live_metadata_query_tool", new AgentToolResult(
            new AgentObservation(
                "live_metadata_result",
                "Queried live mysql metadata: 2 rows returned",
                Map.of(
                    "rowCount", 2,
                    "dbType", "mysql",
                    "topic", "SCHEMA",
                    "answerType", "table_indexes",
                    "tableName", "ACCOUNTS"
                )
            ),
            null,
            "SELECT INDEX_NAME FROM information_schema.STATISTICS",
            0.85
        ));
        context.putMemory("liveMetadataAnswerType", "table_indexes");
        context.putMemory("liveMetadataTableName", "ACCOUNTS");
        context.putMemory("liveMetadataSql", "SELECT INDEX_NAME FROM information_schema.STATISTICS");
        context.putMemory("liveMetadataResult", new QueryResult(
            List.of("INDEX_NAME", "columns", "is_unique", "INDEX_TYPE"),
            List.of(
                List.of("accounts_pkey", "id", "YES", "BTREE"),
                List.of("idx_accounts_group_id", "group_id", "NO", "BTREE")
            ),
            2,
            null,
            false,
            14L,
            "SELECT INDEX_NAME FROM information_schema.STATISTICS"
        ));

        AgentExecutionResult result = composer.compose(plan, context);

        assertThat(result.message()).contains("Table `ACCOUNTS` has **2 indexes** in the live database catalogs.");
        assertThat(result.message()).contains("accounts_pkey");
        assertThat(result.message()).contains("idx_accounts_group_id");
        assertThat(result.message()).contains("unique");
        assertThat(result.message()).contains("btree");
        assertThat(result.message()).doesNotContain("Closest Existing Tables");
    }
}
