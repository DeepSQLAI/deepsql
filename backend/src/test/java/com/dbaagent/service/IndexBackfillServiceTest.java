package com.dbaagent.service;

import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.models.SearchResult;
import com.azure.search.documents.util.SearchPagedIterable;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IndexBackfillServiceTest {

    @Mock
    private AzureSearchService azureSearchService;

    private IndexBackfillService service;

    @BeforeEach
    void setUp() {
        service = new IndexBackfillService(azureSearchService, new ObjectMapper());
    }

    @Nested
    class ResolveTableNameForDocumentTests {

        @Test
        void schemaDdl_usesObjectNameWithResolveTableName() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc1")
                    .type("SCHEMA_DDL")
                    .objectName("public.orders")
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("public.orders", null, "SCHEMA_DDL", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
            verify(azureSearchService).resolveTableName("public.orders", null, "SCHEMA_DDL", "postgresql");
        }

        @Test
        void columnValues_usesObjectNameWithResolveTableName() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc2")
                    .type("COLUMN_VALUES")
                    .objectName("orders")
                    .dbType("mysql")
                    .build();

            when(azureSearchService.resolveTableName("orders", null, "COLUMN_VALUES", "mysql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
        }

        @Test
        void queryExample_returnsNull() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc3")
                    .type("QUERY_EXAMPLE")
                    .objectName("some query")
                    .dbType("postgresql")
                    .build();

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isNull();
            verify(azureSearchService, never()).resolveTableName(any(), any(), any(), any());
        }

        @Test
        void documentation_tableLevelDoc_usesObjectName() throws Exception {
            String metadata = new ObjectMapper().writeValueAsString(
                    Map.of("objectType", "TABLE", "objectName", "public.orders"));

            var doc = TrainingDataSearchDocument.builder()
                    .id("doc4")
                    .type("DOCUMENTATION")
                    .objectName("public.orders")
                    .metadata(metadata)
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("public.orders", null, "TABLE", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
            verify(azureSearchService).resolveTableName("public.orders", null, "TABLE", "postgresql");
        }

        @Test
        void documentation_columnLevelDoc_usesParentObject() throws Exception {
            String metadata = new ObjectMapper().writeValueAsString(
                    Map.of("objectType", "COLUMN", "objectName", "customer_id", "parentObject", "orders"));

            var doc = TrainingDataSearchDocument.builder()
                    .id("doc5")
                    .type("DOCUMENTATION")
                    .objectName("customer_id")
                    .metadata(metadata)
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("customer_id", "orders", "COLUMN", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
            verify(azureSearchService).resolveTableName("customer_id", "orders", "COLUMN", "postgresql");
        }

        @Test
        void documentation_columnWithQualifiedParent_preservesSchema() throws Exception {
            String metadata = new ObjectMapper().writeValueAsString(
                    Map.of("objectType", "COLUMN", "objectName", "id", "parentObject", "sales.orders"));

            var doc = TrainingDataSearchDocument.builder()
                    .id("doc6")
                    .type("DOCUMENTATION")
                    .objectName("id")
                    .metadata(metadata)
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("id", "sales.orders", "COLUMN", "postgresql"))
                    .thenReturn("sales.orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("sales.orders");
        }

        @Test
        void documentation_noMetadata_fallsBackToObjectName() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc7")
                    .type("DOCUMENTATION")
                    .objectName("orders")
                    .metadata(null)
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("orders", null, "DOCUMENTATION", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
        }

        @Test
        void documentation_invalidJsonMetadata_fallsBackToObjectName() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc8")
                    .type("DOCUMENTATION")
                    .objectName("orders")
                    .metadata("not-json")
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("orders", null, "DOCUMENTATION", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
        }

        @Test
        void mysql_documentation_alwaysStrippedToBare() throws Exception {
            String metadata = new ObjectMapper().writeValueAsString(
                    Map.of("objectType", "TABLE", "objectName", "mydb.product_services"));

            var doc = TrainingDataSearchDocument.builder()
                    .id("doc9")
                    .type("DOCUMENTATION")
                    .objectName("mydb.product_services")
                    .metadata(metadata)
                    .dbType("mysql")
                    .build();

            when(azureSearchService.resolveTableName("mydb.product_services", null, "TABLE", "mysql"))
                    .thenReturn("product_services");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("product_services");
        }

        @Test
        void relationship_usesObjectNameDirectly() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc10")
                    .type("RELATIONSHIP")
                    .objectName("orders")
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("orders", null, "RELATIONSHIP", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
        }

        @Test
        void businessTerm_usesObjectNameDirectly() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc11")
                    .type("BUSINESS_TERM")
                    .objectName("orders")
                    .dbType("postgresql")
                    .build();

            when(azureSearchService.resolveTableName("orders", null, "BUSINESS_TERM", "postgresql"))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
        }

        @Test
        void nullDbType_handledGracefully() {
            var doc = TrainingDataSearchDocument.builder()
                    .id("doc12")
                    .type("SCHEMA_DDL")
                    .objectName("orders")
                    .dbType(null)
                    .build();

            when(azureSearchService.resolveTableName("orders", null, "SCHEMA_DDL", null))
                    .thenReturn("orders");

            String result = service.resolveTableNameForDocument(doc);

            assertThat(result).isEqualTo("orders");
        }
    }

    @Nested
    class BackfillStatusTests {

        @Test
        void initialStatus_isIdle() {
            assertThat(service.getProgress().status())
                    .isEqualTo(IndexBackfillService.BackfillStatus.IDLE);
        }

        @Test
        void startBackfill_whenDisabled_returnsFailed() {
            when(azureSearchService.isEnabled()).thenReturn(false);

            var result = service.startBackfill();

            assertThat(result.status()).isEqualTo(IndexBackfillService.BackfillStatus.FAILED);
            assertThat(result.message()).contains("disabled");
        }

        @Test
        void startBackfill_whenAlreadyV2Active_returnsFailed() {
            when(azureSearchService.isEnabled()).thenReturn(true);
            when(azureSearchService.getMigrationPhase())
                    .thenReturn(AzureSearchService.MigrationPhase.V2_ACTIVE);

            var result = service.startBackfill();

            assertThat(result.status()).isEqualTo(IndexBackfillService.BackfillStatus.FAILED);
            assertThat(result.message()).contains("already");
        }
    }

    @Nested
    class BackfillOrchestrationTests {

        @Mock
        private SearchClient v1Client;

        @Mock
        private SearchClient v2Client;

        @Mock
        private SearchPagedIterable searchResults;

        @Test
        void backfill_createsV2Index() {
            when(azureSearchService.createV2Index()).thenReturn(v2Client);
            when(azureSearchService.getSearchClient()).thenReturn(v1Client);
            when(v1Client.search(eq("*"), any(SearchOptions.class), eq(Context.NONE)))
                    .thenReturn(searchResults);
            when(searchResults.stream()).thenReturn(Stream.empty());
            when(searchResults.getTotalCount()).thenReturn(0L);
            when(azureSearchService.getV2Client()).thenReturn(v2Client);

            service.runBackfillSync();

            verify(azureSearchService).createV2Index();
        }

        @Test
        void backfill_processesDocsAndWritesToV2() {
            when(azureSearchService.createV2Index()).thenReturn(v2Client);
            when(azureSearchService.getSearchClient()).thenReturn(v1Client);

            // Simulate v1 docs
            TrainingDataSearchDocument doc1 = TrainingDataSearchDocument.builder()
                    .id("d1").type("SCHEMA_DDL").objectName("orders").dbType("postgresql").build();
            TrainingDataSearchDocument doc2 = TrainingDataSearchDocument.builder()
                    .id("d2").type("QUERY_EXAMPLE").objectName("query").dbType("postgresql").build();

            SearchResult sr1 = mock(SearchResult.class);
            SearchResult sr2 = mock(SearchResult.class);
            when(sr1.getDocument(TrainingDataSearchDocument.class)).thenReturn(doc1);
            when(sr2.getDocument(TrainingDataSearchDocument.class)).thenReturn(doc2);

            when(v1Client.search(eq("*"), any(SearchOptions.class), eq(Context.NONE)))
                    .thenReturn(searchResults);
            when(searchResults.stream()).thenReturn(Stream.of(sr1, sr2));
            when(searchResults.getTotalCount()).thenReturn(2L);

            when(azureSearchService.resolveTableName("orders", null, "SCHEMA_DDL", "postgresql"))
                    .thenReturn("orders");
            when(azureSearchService.getV2Client()).thenReturn(v2Client);

            service.runBackfillSync();

            // Verify v2 write with enriched docs
            @SuppressWarnings("unchecked")
            ArgumentCaptor<List<TrainingDataSearchDocument>> captor = ArgumentCaptor.forClass(List.class);
            verify(v2Client).mergeOrUploadDocuments(captor.capture());

            List<TrainingDataSearchDocument> written = captor.getValue();
            assertThat(written).hasSize(2);

            // SCHEMA_DDL doc should have tableName resolved
            TrainingDataSearchDocument schemaDoc = written.stream()
                    .filter(d -> "d1".equals(d.getId())).findFirst().orElseThrow();
            assertThat(schemaDoc.getTableName()).isEqualTo("orders");

            // QUERY_EXAMPLE doc should have tableName null
            TrainingDataSearchDocument queryDoc = written.stream()
                    .filter(d -> "d2".equals(d.getId())).findFirst().orElseThrow();
            assertThat(queryDoc.getTableName()).isNull();
        }

        @Test
        void backfill_activatesV2_whenParityPasses() {
            when(azureSearchService.createV2Index()).thenReturn(v2Client);
            when(azureSearchService.getSearchClient()).thenReturn(v1Client);
            when(v1Client.search(eq("*"), any(SearchOptions.class), eq(Context.NONE)))
                    .thenReturn(searchResults);
            when(searchResults.stream()).thenReturn(Stream.empty());
            when(searchResults.getTotalCount()).thenReturn(0L);
            when(azureSearchService.getV2Client()).thenReturn(v2Client);

            // v2 count matches v1
            SearchPagedIterable v2Results = mock(SearchPagedIterable.class);
            when(v2Client.search(eq("*"), any(SearchOptions.class), eq(Context.NONE)))
                    .thenReturn(v2Results);
            when(v2Results.getTotalCount()).thenReturn(0L);

            service.runBackfillSync();

            verify(azureSearchService).activateV2();
        }
    }
}
