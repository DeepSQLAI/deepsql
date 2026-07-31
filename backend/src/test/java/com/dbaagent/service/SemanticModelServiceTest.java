package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.ColumnValueCache;
import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.QueryExample;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SemanticJoinModelRepository;
import com.dbaagent.repository.SemanticTableModelRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.TableRelationshipClassificationRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.lenient;

@ExtendWith(MockitoExtension.class)
class SemanticModelServiceTest {

    @Mock private SchemaScannerService schemaScannerService;
    @Mock private TableClassificationRepository tableClassificationRepository;
    @Mock private SchemaDocumentationRepository schemaDocumentationRepository;
    @Mock private KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    @Mock private ColumnProfileRepository columnProfileRepository;
    @Mock private ColumnValueCacheRepository columnValueCacheRepository;
    @Mock private CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    @Mock private InferredTableRelationshipRepository inferredTableRelationshipRepository;
    @Mock private TableRelationshipClassificationRepository tableRelationshipClassificationRepository;
    @Mock private QueryExampleRepository queryExampleRepository;
    @Mock private SemanticTableModelRepository semanticTableModelRepository;
    @Mock private SemanticJoinModelRepository semanticJoinModelRepository;

    private SemanticModelService semanticModelService;

    @BeforeEach
    void setUp() {
        semanticModelService = new SemanticModelService(
            schemaScannerService,
            tableClassificationRepository,
            schemaDocumentationRepository,
            keyColumnAnalysisRepository,
            columnProfileRepository,
            columnValueCacheRepository,
            companyKnowledgeEntryRepository,
            inferredTableRelationshipRepository,
            tableRelationshipClassificationRepository,
            queryExampleRepository,
            semanticTableModelRepository,
            semanticJoinModelRepository,
            new ObjectMapper()
        );
        lenient().when(companyKnowledgeEntryRepository.findByConnectionId("conn-1")).thenReturn(List.of());
    }

    @Test
    void rebuildSemanticModelBuildsStructuredTablesAndJoins() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("postgresql");
        schema.setDatabaseName("analytics");
        schema.setTables(List.of(
            table("ORDERS",
                column("id", "bigint", true),
                column("customer_id", "bigint", false),
                column("order_status", "varchar", false),
                column("order_total", "numeric", false),
                column("created_at", "timestamp", false)
            ),
            table("CUSTOMERS",
                column("id", "bigint", true),
                column("email", "varchar", false),
                column("country", "varchar", false)
            )
        ));
        schema.setRelationships(List.of(relationship("ORDERS", "customer_id", "CUSTOMERS", "id")));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            TableClassification.builder()
                .connectionId("conn-1")
                .tableName("ORDERS")
                .tableRole("FACT")
                .businessDomain("TRANSACTION")
                .build(),
            TableClassification.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMERS")
                .tableRole("DIMENSION")
                .businessDomain("CUSTOMER")
                .build()
        ));
        when(schemaDocumentationRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.TABLE)
                .objectName("ORDERS")
                .description("Customer purchase orders")
                .businessTerms("orders,purchases")
                .source(DocumentationSource.USER)
                .createdAt(LocalDateTime.now())
                .build()
        ));
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("conn-1")).thenReturn(List.of(
            KeyColumnAnalysis.builder()
                .connectionId("conn-1")
                .tableName("ORDERS")
                .columnName("customer_id")
                .importanceScore(BigDecimal.valueOf(90))
                .build()
        ));
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnValueCacheRepository.findByConnectionIdAndIsLowCardinalityTrue("conn-1")).thenReturn(List.of(
            ColumnValueCache.builder()
                .connectionId("conn-1")
                .tableName("ORDERS")
                .columnName("order_status")
                .isLowCardinality(true)
                .allValues("[\"PENDING\",\"PAID\",\"REFUNDED\"]")
                .distinctCount(3L)
                .build()
        ));
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of(
            InferredTableRelationship.builder()
                .connectionId("conn-1")
                .sourceTable("ORDERS")
                .sourceColumn("customer_id")
                .targetTable("CUSTOMERS")
                .targetColumn("id")
                .confidenceScore(BigDecimal.valueOf(75))
                .build()
        ));
        when(companyKnowledgeEntryRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            CompanyKnowledgeEntry.builder()
                .connectionId("conn-1")
                .title("Order lifecycle")
                .entryType(CompanyKnowledgeEntry.EntryType.WORKFLOW)
                .content("Orders represent completed purchases and order_status is the main lifecycle filter.")
                .linkedTables(List.of("ORDERS"))
                .linkedColumns(List.of("ORDERS.order_status"))
                .build()
        ));
        when(tableRelationshipClassificationRepository.findLatestByConnectionIdOrderBySourceTableAsc("conn-1")).thenReturn(List.of());
        when(queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue("conn-1")).thenReturn(List.of(
            QueryExample.builder()
                .connectionId("conn-1")
                .naturalLanguage("Top customers by order total")
                .sql("SELECT ...")
                .successful(true)
                .verified(true)
                .tablesUsed("ORDERS,CUSTOMERS")
                .dbType("postgresql")
                .createdAt(LocalDateTime.now())
                .build()
        ));

        var summary = semanticModelService.rebuildSemanticModel("conn-1");

        assertThat(summary.tablesBuilt()).isEqualTo(2);
        assertThat(summary.joinsBuilt()).isEqualTo(1);
        assertThat(summary.verifiedPatterns()).isEqualTo(1);

        ArgumentCaptor<Iterable<SemanticTableModel>> tableCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(semanticTableModelRepository).saveAll(tableCaptor.capture());
        List<SemanticTableModel> savedTables = toList(tableCaptor.getValue());

        SemanticTableModel orders = savedTables.stream()
            .filter(tableModel -> "ORDERS".equals(tableModel.getTableName()))
            .findFirst()
            .orElseThrow();
        assertThat(orders.getTableRole()).isEqualTo("FACT");
        assertThat(orders.getBusinessDescription()).contains("Customer purchase orders");
        assertThat(orders.getBusinessDescription()).contains("Order lifecycle");
        assertThat(orders.getTimeColumns()).contains("created_at");
        assertThat(orders.getTemporalSemantics())
            .extracting(item -> item.get("column"), item -> item.get("label"))
            .contains(tuple("created_at", "creation time"));
        assertThat(orders.getMetricColumns()).contains("order_total");
        assertThat(orders.getKeyColumns()).contains("customer_id", "id");
        assertThat(orders.getFilterColumns()).isNotEmpty();
        assertThat(orders.getBusinessTerms()).contains("orders", "Order lifecycle");
        assertThat(orders.getSourceSummary()).contains("company_knowledge");

        ArgumentCaptor<Iterable<SemanticJoinModel>> joinCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(semanticJoinModelRepository).saveAll(joinCaptor.capture());
        List<SemanticJoinModel> savedJoins = toList(joinCaptor.getValue());
        assertThat(savedJoins).singleElement().satisfies(join -> {
            assertThat(join.getJoinExpression()).isEqualTo("ORDERS.customer_id = CUSTOMERS.id");
            assertThat(join.getEvidenceSource()).isEqualTo("FOREIGN_KEY");
            assertThat(join.getPreferred()).isTrue();
        });
    }

    @Test
    void buildSemanticModelContextPrioritizesMentionedTables() {
        when(semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            SemanticTableModel.builder()
                .connectionId("conn-1")
                .tableName("ORDERS")
                .tableRole("FACT")
                .businessDescription("Customer purchase orders")
                .grainDescription("One row per order.")
                .timeColumns(List.of("created_at"))
                .metricColumns(List.of("order_total"))
                .build(),
            SemanticTableModel.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMERS")
                .tableRole("DIMENSION")
                .grainDescription("One row per customer.")
                .build()
        ));
        when(semanticJoinModelRepository.findByConnectionIdAndTables(any(), any())).thenReturn(List.of(
            SemanticJoinModel.builder()
                .connectionId("conn-1")
                .sourceTable("ORDERS")
                .sourceColumn("customer_id")
                .targetTable("CUSTOMERS")
                .targetColumn("id")
                .joinExpression("ORDERS.customer_id = CUSTOMERS.id")
                .evidenceSource("FOREIGN_KEY")
                .preferred(true)
                .build()
        ));
        when(queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue("conn-1")).thenReturn(List.of());
        when(companyKnowledgeEntryRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            CompanyKnowledgeEntry.builder()
                .connectionId("conn-1")
                .title("Revenue glossary")
                .entryType(CompanyKnowledgeEntry.EntryType.GLOSSARY)
                .content("Order revenue should be interpreted as gross order_total before refunds.")
                .linkedTables(List.of("ORDERS"))
                .build()
        ));

        String context = semanticModelService.buildSemanticModelContext(
            "conn-1",
            "Show order revenue by customer",
            java.util.Set.of()
        );

        assertThat(context).contains("=== SEMANTIC MODEL ===");
        assertThat(context).contains("ORDERS");
        assertThat(context).contains("One row per order.");
        assertThat(context).contains("ORDERS.customer_id = CUSTOMERS.id");
        assertThat(context).contains("Company knowledge:");
        assertThat(context).contains("Revenue glossary");
    }

    @Test
    void findRelevantTables_prefersCoreHotelEntityForOnboardingPrompt() {
        when(semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            SemanticTableModel.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMERS")
                .tableRole("DIMENSION")
                .businessDescription("Core customer entity. subscription_start_date marks when the customer contract starts.")
                .businessTerms("customer, property")
                .timeColumns(List.of("subscription_start_date"))
                .confidenceScore(BigDecimal.valueOf(92))
                .build(),
            SemanticTableModel.builder()
                .connectionId("conn-1")
                .tableName("PRODUCT_SERVICES")
                .tableRole("FACT")
                .businessDescription("Operational service records for each customer")
                .timeColumns(List.of("last_updated"))
                .confidenceScore(BigDecimal.valueOf(90))
                .build()
        ));

        List<SemanticTableModel> relevant = semanticModelService.findRelevantTables(
            "conn-1",
            "How many customers are onboarded in the last 3 days?",
            java.util.Set.of()
        );

        assertThat(relevant).isNotEmpty();
        assertThat(relevant.getFirst().getTableName()).isEqualTo("CUSTOMERS");
    }

    @Test
    void findRelevantTables_prefersEventLogForUsageDeclinePrompt() {
        when(semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            SemanticTableModel.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMER_ORDERS")
                .tableRole("FACT")
                .businessDescription("Booking facts per reservation")
                .metricColumns(List.of("booking_amount"))
                .timeColumns(List.of("booking_made_on"))
                .confidenceScore(BigDecimal.valueOf(96))
                .build(),
            SemanticTableModel.builder()
                .connectionId("conn-1")
                .tableName("USER_LOGS")
                .tableRole("EVENT_LOG")
                .businessDescription("Usage activity and event logs for customers and users")
                .businessTerms("usage, activity, logs")
                .timeColumns(List.of("event_occurred_at"))
                .filterColumns(List.of(Map.of("column", "action_type")))
                .confidenceScore(BigDecimal.valueOf(88))
                .build()
        ));

        List<SemanticTableModel> relevant = semanticModelService.findRelevantTables(
            "conn-1",
            "what are the customers most likely churn? usage steeply dropped recently",
            java.util.Set.of()
        );

        assertThat(relevant).isNotEmpty();
        assertThat(relevant.getFirst().getTableName()).isEqualTo("USER_LOGS");
    }

    @Test
    void rebuildSemanticModel_usesColumnDocumentationWhenTableDocumentationMissing() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("CUSTOMERS",
                column("id", "bigint", true),
                column("subscription_start_date", "timestamp", false),
                column("last_updated", "timestamp", false)
            )
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            TableClassification.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMERS")
                .tableRole("DIMENSION")
                .businessDomain("SUPPLY")
                .build()
        ));
        when(schemaDocumentationRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.COLUMN)
                .objectName("subscription_start_date")
                .parentObject("analytics_db.CUSTOMERS")
                .description("Date/time when the property's subscription or commercial agreement started.")
                .businessTerms("onboarded, subscription start")
                .source(DocumentationSource.USER)
                .createdAt(LocalDateTime.now())
                .build()
        ));
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnValueCacheRepository.findByConnectionIdAndIsLowCardinalityTrue("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(tableRelationshipClassificationRepository.findLatestByConnectionIdOrderBySourceTableAsc("conn-1")).thenReturn(List.of());
        when(queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue("conn-1")).thenReturn(List.of());

        semanticModelService.rebuildSemanticModel("conn-1");

        ArgumentCaptor<Iterable<SemanticTableModel>> tableCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(semanticTableModelRepository).saveAll(tableCaptor.capture());
        List<SemanticTableModel> savedTables = toList(tableCaptor.getValue());
        SemanticTableModel customer = savedTables.stream()
            .filter(tableModel -> "CUSTOMERS".equals(tableModel.getTableName()))
            .findFirst()
            .orElseThrow();

        assertThat(customer.getBusinessDescription()).contains("subscription_start_date");
        assertThat(customer.getBusinessTerms()).contains("onboarded");
        assertThat(customer.getTimeColumns()).contains("subscription_start_date");
    }

    @Test
    void rebuildSemanticModel_matchesSchemaQualifiedDocumentationForCurrentTable() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        TableMetadata customer = table("CUSTOMERS",
            column("id", "bigint", true),
            column("subscription_start_date", "timestamp", false),
            column("onboarding_status", "varchar", false)
        );
        customer.setSchema("analytics_db");
        schema.setTables(List.of(customer));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            TableClassification.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMERS")
                .tableRole("DIMENSION")
                .businessDomain("SUPPLY")
                .build()
        ));
        when(schemaDocumentationRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.TABLE)
                .objectName("analytics_db.CUSTOMERS")
                .description("Core customer entity used for onboarding and commercial lifecycle tracking.")
                .businessTerms("customer, property")
                .source(DocumentationSource.AI_GENERATED)
                .createdAt(LocalDateTime.now())
                .build(),
            SchemaDocumentation.builder()
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.COLUMN)
                .objectName("onboarding_status")
                .parentObject("analytics_db.CUSTOMERS")
                .description("Lifecycle status of the customer onboarding flow.")
                .businessTerms("active, inactive, onboarding status")
                .source(DocumentationSource.AI_GENERATED)
                .createdAt(LocalDateTime.now())
                .build()
        ));
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnValueCacheRepository.findByConnectionIdAndIsLowCardinalityTrue("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(tableRelationshipClassificationRepository.findLatestByConnectionIdOrderBySourceTableAsc("conn-1")).thenReturn(List.of());
        when(queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue("conn-1")).thenReturn(List.of());

        semanticModelService.rebuildSemanticModel("conn-1");

        ArgumentCaptor<Iterable<SemanticTableModel>> tableCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(semanticTableModelRepository).saveAll(tableCaptor.capture());
        List<SemanticTableModel> savedTables = toList(tableCaptor.getValue());
        SemanticTableModel savedHotel = savedTables.stream()
            .filter(model -> "CUSTOMERS".equals(model.getTableName()))
            .findFirst()
            .orElseThrow();

        assertThat(savedHotel.getBusinessDescription()).contains("Core customer entity");
        assertThat(savedHotel.getBusinessTerms()).contains("property");
        assertThat(savedHotel.getTimeColumns()).contains("subscription_start_date");
        assertThat(savedHotel.getFilterColumns())
            .extracting(item -> item.get("column"))
            .contains("onboarding_status");
    }

    @Test
    void rebuildSemanticModel_timeColumnsRemainStrictlyTemporal() throws Exception {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDbType("mysql");
        schema.setTables(List.of(
            table("CUSTOMER_ORDERS",
                column("id", "bigint", true),
                column("booking_amount", "numeric", false),
                column("booking_made_on", "bigint", false),
                column("action_type", "varchar", false)
            )
        ));

        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-1")).thenReturn(List.of(
            TableClassification.builder()
                .connectionId("conn-1")
                .tableName("CUSTOMER_ORDERS")
                .tableRole("FACT")
                .timestampColumns(List.of(
                    Map.of("column", "booking_amount"),
                    Map.of("column", "booking_made_on")
                ))
                .build()
        ));
        when(schemaDocumentationRepository.findByConnectionId("conn-1")).thenReturn(List.of(
            SchemaDocumentation.builder()
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.COLUMN)
                .objectName("booking_amount")
                .parentObject("CUSTOMER_ORDERS")
                .description("Revenue amount for the booking.")
                .businessTerms("amount, revenue")
                .source(DocumentationSource.USER)
                .createdAt(LocalDateTime.now())
                .build(),
            SchemaDocumentation.builder()
                .connectionId("conn-1")
                .objectType(SchemaDocumentation.DocumentationType.COLUMN)
                .objectName("booking_made_on")
                .parentObject("CUSTOMER_ORDERS")
                .description("Timestamp when the booking event occurred.")
                .businessTerms("booking timestamp, booking time")
                .source(DocumentationSource.USER)
                .createdAt(LocalDateTime.now())
                .build()
        ));
        when(keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc("conn-1")).thenReturn(List.of());
        when(columnProfileRepository.findByConnectionId("conn-1")).thenReturn(List.of());
        when(columnValueCacheRepository.findByConnectionIdAndIsLowCardinalityTrue("conn-1")).thenReturn(List.of());
        when(inferredTableRelationshipRepository.findByConnectionIdOrderByConfidenceScoreDesc("conn-1")).thenReturn(List.of());
        when(tableRelationshipClassificationRepository.findLatestByConnectionIdOrderBySourceTableAsc("conn-1")).thenReturn(List.of());
        when(queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue("conn-1")).thenReturn(List.of());

        semanticModelService.rebuildSemanticModel("conn-1");

        ArgumentCaptor<Iterable<SemanticTableModel>> tableCaptor = ArgumentCaptor.forClass(Iterable.class);
        verify(semanticTableModelRepository).saveAll(tableCaptor.capture());
        List<SemanticTableModel> savedTables = toList(tableCaptor.getValue());
        SemanticTableModel bookings = savedTables.stream()
            .filter(model -> "CUSTOMER_ORDERS".equals(model.getTableName()))
            .findFirst()
            .orElseThrow();

        assertThat(bookings.getTimeColumns()).contains("booking_made_on");
        assertThat(bookings.getTimeColumns()).doesNotContain("booking_amount");
    }

    private TableMetadata table(String name, ColumnMetadata... columns) {
        TableMetadata table = new TableMetadata();
        table.setName(name);
        table.setColumns(List.of(columns));
        return table;
    }

    private ColumnMetadata column(String name, String type, boolean primaryKey) {
        ColumnMetadata column = new ColumnMetadata();
        column.setName(name);
        column.setDataType(type);
        column.setPrimaryKey(primaryKey);
        return column;
    }

    private RelationshipMetadata relationship(String fromTable, String fromColumn, String toTable, String toColumn) {
        RelationshipMetadata relationship = new RelationshipMetadata();
        relationship.setFromTable(fromTable);
        relationship.setFromColumn(fromColumn);
        relationship.setToTable(toTable);
        relationship.setToColumn(toColumn);
        return relationship;
    }

    private <T> List<T> toList(Iterable<T> iterable) {
        List<T> values = new ArrayList<>();
        iterable.forEach(values::add);
        return values;
    }
}
