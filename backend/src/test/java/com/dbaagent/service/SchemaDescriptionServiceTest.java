package com.dbaagent.service;

import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaDocumentation.DocumentationType;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.repository.SchemaDocumentationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.*;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.chat.client.ChatClient;

import java.util.*;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SchemaDescriptionServiceTest {

    @Mock private ChatClient.Builder chatClientBuilder;
    @Mock private ChatClient chatClient;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private SchemaDocumentationRepository schemaDocRepo;
    @Mock private com.dbaagent.repository.ColumnProfileRepository columnProfileRepo;
    @Mock private com.dbaagent.repository.InferredTableRelationshipRepository inferredRelationshipRepository;
    @Mock private TrainingService trainingService;
    @Mock private ConnectionService connectionService;
    @Mock private com.dbaagent.provider.DatabaseProviderRegistry providerRegistry;

    private SchemaDescriptionService service;

    @BeforeEach
    void setUp() {
        when(chatClientBuilder.build()).thenReturn(chatClient);
        lenient().when(inferredRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc(anyString()))
            .thenReturn(List.of());
        service = new SchemaDescriptionService(
            chatClientBuilder, schemaScannerService, schemaDocRepo,
            columnProfileRepo, inferredRelationshipRepository, trainingService, connectionService,
            providerRegistry, 4
        );
    }

    @Test
    void skipsTablesWithExistingUserDocumentation() throws Exception {
        // Given: table "orders" already has USER documentation
        var userDoc = SchemaDocumentation.builder()
            .connectionId("conn1")
            .objectType(DocumentationType.TABLE)
            .objectName("orders")
            .description("User-written description")
            .source(DocumentationSource.USER)
            .build();
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of(userDoc));

        // Mock schema with one table "orders"
        var schema = buildSchemaWithTables("orders");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);

        // When
        var result = service.generateDescriptions("conn1", null);

        // Then: no LLM calls made (table was skipped)
        verifyNoInteractions(chatClient);
        assertThat(result.getTablesSkipped()).isEqualTo(1);
        assertThat(result.getTablesProcessed()).isEqualTo(0);
    }

    @Test
    void generatesDescriptionsForNewTables() throws Exception {
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of());
        when(schemaDocRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);

        var schema = buildSchemaWithTables("users");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);

        // Mock LLM call chain
        mockLlmResponse("""
            [{"tableName":"users","tableDescription":"Stores registered user accounts",
              "businessTerms":"customers,accounts","confidence":0.85,
              "columns":[
                {"name":"id","description":"Unique user identifier","confidence":0.95},
                {"name":"email","description":"User email for login","confidence":0.9}
              ]}]
            """);

        var result = service.generateDescriptions("conn1", null);

        // Verify: 1 table doc + 2 column docs = 3 saves
        verify(schemaDocRepo, times(3)).save(argThat(doc ->
            doc.getSource() == DocumentationSource.AI_GENERATED &&
            doc.getConfidence() != null &&
            doc.getDescription() != null && !doc.getDescription().isBlank()
        ));
        // Verify RAG embedding called for each doc
        verify(trainingService, times(3)).upsertDocumentationEmbedding(any());
        assertThat(result.getTablesProcessed()).isEqualTo(1);
    }

    @Test
    void deltaOnlyModeTreatsSchemaQualifiedDocOnSameConnectionAsExistingCoverage() throws Exception {
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn1")
                .objectType(DocumentationType.TABLE)
                .objectName("analytics_db.CUSTOMERS")
                .description("Existing AI description")
                .source(DocumentationSource.AI_GENERATED)
                .build()
        ));

        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        TableMetadata customer = buildTable("CUSTOMERS");
        customer.setSchema("analytics_db");
        schema.setTables(List.of(customer));
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);

        var result = service.generateDescriptions("conn1", null);

        verifyNoInteractions(chatClient);
        assertThat(result.getCurrentTables()).isEqualTo(1);
        assertThat(result.getMatchedExistingAiTableDocs()).isEqualTo(1);
        assertThat(result.getUnmatchedExistingAiTableDocs()).isEqualTo(0);
        assertThat(result.getTablesSkipped()).isEqualTo(1);
    }

    @Test
    void deltaOnlyModeIgnoresAiDocFromAnotherConnection() throws Exception {
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn-2")
                .objectType(DocumentationType.TABLE)
                .objectName("CUSTOMERS")
                .description("Other connection doc")
                .source(DocumentationSource.AI_GENERATED)
                .build()
        ));
        when(schemaDocRepo.save(any())).thenAnswer(i -> i.getArgument(0));

        var schema = buildSchemaWithTables("CUSTOMERS");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);
        mockLlmResponse("""
            [{"tableName":"CUSTOMERS","tableDescription":"Hotel master record","confidence":0.81,"columns":[]}]
            """);

        var result = service.generateDescriptions("conn1", null);

        verify(chatClient, times(1)).prompt();
        assertThat(result.getMatchedExistingAiTableDocs()).isEqualTo(0);
        assertThat(result.getTablesProcessed()).isEqualTo(1);
    }

    @Test
    void forceRegenerateOverridesDeltaOnlyModeAndRegeneratesExistingAiDocs() throws Exception {
        // Given: table "users" already has an AI-generated doc
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn1")
                .objectType(DocumentationType.TABLE)
                .objectName("users")
                .description("Old AI description")
                .source(DocumentationSource.AI_GENERATED)
                .build()
        ));
        when(schemaDocRepo.findByConnectionIdAndObjectTypeAndObjectNameAndSource(any(), any(), any(), any()))
            .thenReturn(Optional.empty());
        when(schemaDocRepo.save(any())).thenAnswer(i -> i.getArgument(0));
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);
        var schema = buildSchemaWithTables("users");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);
        mockLlmResponse("""
            [{"tableName":"users","tableDescription":"Refreshed user description",
              "confidence":0.9,"columns":[]}]
            """);

        // When: forceRegenerate=true
        var result = service.generateDescriptions("conn1", null, null, true);

        // Then: LLM was called and table was regenerated (not skipped)
        verify(chatClient, times(1)).prompt();
        assertThat(result.getTablesProcessed()).isEqualTo(1);
        assertThat(result.getTablesSkipped()).isEqualTo(0);
    }

    @Test
    void incrementalModeOnlyProcessesSpecifiedTables() throws Exception {
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of());
        var schema = buildSchemaWithTables("users", "orders", "products");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);
        mockLlmResponse("[]");

        service.generateDescriptions("conn1", List.of("orders"));

        // Verify only 1 batch call (not 3 tables)
        verify(chatClient, times(1)).prompt();
    }

    @Test
    void nullDescriptionFromLlmSkipsTable() throws Exception {
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of());
        var schema = buildSchemaWithTables("users");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);
        mockLlmResponse("""
            [{"tableName":"users","tableDescription":null,"confidence":0.3,"columns":[]}]
            """);

        var result = service.generateDescriptions("conn1", null);

        // Null description should be skipped (NOT NULL constraint)
        verify(schemaDocRepo, never()).save(any());
    }

    @Test
    void allBatchesFailingThrowsForSmallSchema() throws Exception {
        when(schemaDocRepo.findByConnectionId("conn1")).thenReturn(List.of());
        // 2 tables = 1 batch (batch size 10) — fewer than 5 batches
        var schema = buildSchemaWithTables("users", "orders");
        when(schemaScannerService.scanSchema("conn1")).thenReturn(schema);
        when(connectionService.isDataSamplingEnabled("conn1")).thenReturn(false);

        // Make LLM call throw an exception
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenThrow(new RuntimeException("LLM unavailable"));

        assertThatThrownBy(() -> service.generateDescriptions("conn1", null))
            .isInstanceOf(RuntimeException.class)
            .hasMessageContaining("aborted");
    }

    private SchemaMetadata buildSchemaWithTables(String... tableNames) {
        var schema = new SchemaMetadata();
        schema.setDatabaseName("test_db");
        schema.setDbType("postgresql");
        List<TableMetadata> tables = new ArrayList<>();
        for (String name : tableNames) {
            tables.add(buildTable(name));
        }
        schema.setTables(tables);
        return schema;
    }

    private TableMetadata buildTable(String name) {
        var table = new TableMetadata();
        table.setName(name);
        table.setType("table");
        table.setColumns(List.of(
            buildColumn("id", "bigint", true),
            buildColumn("email", "varchar", false)
        ));
        return table;
    }

    private ColumnMetadata buildColumn(String name, String type, boolean pk) {
        var col = new ColumnMetadata();
        col.setName(name);
        col.setDataType(type);
        col.setPrimaryKey(pk);
        col.setNullable(!pk);
        return col;
    }

    private void mockLlmResponse(String json) {
        // Mock the ChatClient fluent chain: chatClient.prompt().system(x).user(x).call().content()
        var requestSpec = mock(ChatClient.ChatClientRequestSpec.class);
        var callResponseSpec = mock(ChatClient.CallResponseSpec.class);
        when(chatClient.prompt()).thenReturn(requestSpec);
        when(requestSpec.system(anyString())).thenReturn(requestSpec);
        when(requestSpec.user(anyString())).thenReturn(requestSpec);
        when(requestSpec.call()).thenReturn(callResponseSpec);
        when(callResponseSpec.content()).thenReturn(json);
    }
}
