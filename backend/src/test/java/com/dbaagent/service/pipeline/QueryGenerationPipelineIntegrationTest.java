package com.dbaagent.service.pipeline;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SqlExecutionPipeline;
import com.dbaagent.service.TrainingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.core.io.ClassPathResource;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class QueryGenerationPipelineIntegrationTest {

    @Mock private TrainingService trainingService;
    @Mock private ConnectionService connectionService;
    @Mock private SqlExecutionPipeline sqlExecutionPipeline;
    @Mock private ChatClient chatClient;
    @Mock private ColumnValueFetcher columnValueFetcher;
    @Mock private SqlValidator sqlValidator;
    @Mock private SemanticModelService semanticModelService;

    private QueryGenerationPipeline pipeline;

    @BeforeEach
    void setUp() {
        pipeline = new QueryGenerationPipeline(
            trainingService, connectionService, sqlExecutionPipeline, chatClient,
            new ClassPathResource("prompts/sql-adaptation-prompt.st"),
            new ClassPathResource("prompts/table-resolution-prompt.st"),
            0.92, columnValueFetcher, sqlValidator, semanticModelService
        );
        // @Value not processed in unit tests — enable pipeline by default
        ReflectionTestUtils.setField(pipeline, "pipelineEnabled", true);
        mockLlmResponses("{}");
    }

    @Test
    void fullPipeline_noHistoryMatch_returnsEnrichedContext() {
        when(trainingService.cachedRetrieveRelevant(anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(columnValueFetcher.fetch(anyString(), anyString(), anyList()))
            .thenReturn(ColumnValueContext.empty());

        var schema = new SchemaMetadata();
        schema.setTables(List.of());
        var ctx = new PipelineContext(
            "conn-1", "Show all bookings", "POSTGRESQL",
            "schema text", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.execute(ctx);

        assertThat(result.historyMatched()).isFalse();
        assertThat(result.stepsExecuted()).contains("history_match");
        assertThat(result.stepsExecuted()).containsAnyOf("table_resolution", "table_resolution_fastpath");
        assertThat(result.stepsExecuted()).contains("value_fetch", "sql_generation");
        assertThat(result.totalDurationMs()).isGreaterThanOrEqualTo(0);
    }

    @Test
    void progressEventsEmittedInOrder() {
        when(trainingService.cachedRetrieveRelevant(anyString(), anyString(), anyInt()))
            .thenReturn(List.of());
        when(columnValueFetcher.fetch(anyString(), anyString(), anyList()))
            .thenReturn(ColumnValueContext.empty());

        var events = new ArrayList<String>();
        PipelineProgressListener listener = (step, message, metadata) -> events.add(step);

        var schema = new SchemaMetadata();
        schema.setTables(List.of());
        var ctx = new PipelineContext(
            "conn-1", "Show all bookings", "POSTGRESQL",
            "schema text", schema, null, "", "", "", "", "",
            List.of(), listener
        );

        pipeline.execute(ctx);

        assertThat(events).isNotEmpty();
        assertThat(events.getFirst()).isEqualTo("history_match");
    }

    @Test
    void pipelineDisabled_returnsEmptyResult() {
        ReflectionTestUtils.setField(pipeline, "pipelineEnabled", false);

        var ctx = new PipelineContext(
            "conn-1", "Show bookings", "POSTGRESQL",
            "", null, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.execute(ctx);

        assertThat(result.historyMatched()).isFalse();
        assertThat(result.stepsExecuted()).containsExactly("disabled");
        verifyNoInteractions(trainingService, columnValueFetcher, sqlValidator);
    }

    @Test
    void historyMatchValidationFailureFallsBackToFullPipeline() {
        var doc = mock(com.dbaagent.model.TrainingDataEmbedding.class);
        when(doc.getScore()).thenReturn(0.99);
        when(doc.getType()).thenReturn(com.dbaagent.model.TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE);
        when(doc.getContent()).thenReturn("Show bookings\nSELECT * FROM bookings");
        when(doc.getMetadata()).thenReturn("{\"question\":\"Show bookings\"}");

        when(trainingService.cachedRetrieveRelevant(anyString(), anyString(), anyInt()))
            .thenReturn(List.of(doc));
        mockLlmResponses(
            "SELECT * FROM bookings WHERE status = 'CONFIRMED'",
            """
            {
              "tables": ["bookings"],
              "columns": {"bookings": ["status"]},
              "filterColumns": [{"table": "bookings", "column": "status"}],
              "joinConditions": [],
              "confidence": "HIGH"
            }
            """
        );
        when(sqlValidator.validate(anyString(), anyString(), anyString()))
            .thenReturn(ValidationResult.invalid("column not found"));
        when(columnValueFetcher.fetch(anyString(), anyString(), anyList()))
            .thenReturn(ColumnValueContext.empty());

        var schema = new SchemaMetadata();
        schema.setTables(List.of());
        var ctx = new PipelineContext(
            "conn-1", "Show reservation status breakdown", "POSTGRESQL",
            "schema text", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.execute(ctx);

        assertThat(result.historyMatched()).isFalse();
        assertThat(result.stepsExecuted()).contains("history_match", "validation");
        assertThat(result.stepsExecuted()).containsAnyOf("table_resolution", "table_resolution_fastpath");
        verify(sqlValidator).validate(eq("conn-1"), contains("SELECT"), eq("POSTGRESQL"));
        verify(columnValueFetcher).fetch(anyString(), anyString(), anyList());
    }

    @Test
    void resolveContextOnly_skipsHistoryMatchButStillFetchesColumnValues() {
        var schema = new SchemaMetadata();
        schema.setTables(List.of(buildTable("bookings", "status", "booking_amount")));
        mockLlmResponses("""
            {
              "tables": ["bookings"],
              "columns": {"bookings": ["status"]},
              "filterColumns": [{"table": "bookings", "column": "status"}],
              "joinConditions": [],
              "confidence": "HIGH"
            }
            """);

        var filterColumns = List.of(new FilterColumn("bookings", "status"));
        when(columnValueFetcher.fetch("conn-1", "POSTGRESQL", filterColumns))
            .thenReturn(new ColumnValueContext(
                Map.of("bookings.status", List.of("CONFIRMED", "CANCELLED")),
                "bookings.status: CONFIRMED, CANCELLED",
                10L,
                List.of()
            ));

        var ctx = new PipelineContext(
            "conn-1", "Show reservation status breakdown", "POSTGRESQL",
            "schema text", schema, null, "", "", "", "", "",
            List.of(), PipelineProgressListener.NOOP
        );

        var result = pipeline.resolveContextOnly(ctx);

        assertThat(result.stepsExecuted()).doesNotContain("history_match");
        assertThat(result.stepsExecuted()).contains("table_resolution", "value_fetch", "sql_generation");
        assertThat(result.columnValueContext().valueMap()).containsEntry(
            "bookings.status", List.of("CONFIRMED", "CANCELLED"));
        verify(columnValueFetcher).fetch("conn-1", "POSTGRESQL", filterColumns);
        verifyNoInteractions(trainingService, sqlValidator);
    }

    private void mockLlmResponses(String... contents) {
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var callResponseSpecs = Arrays.stream(contents)
            .map(content -> {
                var callResponseSpec = mock(ChatClient.CallResponseSpec.class);
                lenient().when(callResponseSpec.content()).thenReturn(content);
                return callResponseSpec;
            })
            .toArray(ChatClient.CallResponseSpec[]::new);
        lenient().when(chatClient.prompt()).thenReturn(requestSpec);
        lenient().when(requestSpec.system(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.user(anyString())).thenReturn(requestSpec);
        lenient().when(requestSpec.call()).thenReturn(callResponseSpecs[0], Arrays.copyOfRange(callResponseSpecs, 1, callResponseSpecs.length));
    }

    private com.dbaagent.model.TableMetadata buildTable(String tableName, String... columns) {
        var table = new com.dbaagent.model.TableMetadata();
        table.setName(tableName);
        table.setColumns(Arrays.stream(columns).map(columnName -> {
            var column = new com.dbaagent.model.ColumnMetadata();
            column.setName(columnName);
            return column;
        }).toList());
        return table;
    }
}
