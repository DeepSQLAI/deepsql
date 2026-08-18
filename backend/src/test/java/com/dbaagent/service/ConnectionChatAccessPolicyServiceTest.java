package com.dbaagent.service;

import com.dbaagent.dto.PolicyPreviewResponse;
import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.ConnectionChatAccessPolicyRepository;
import com.dbaagent.repository.TableClassificationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;

import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ConnectionChatAccessPolicyServiceTest {

    @Mock private ConnectionChatAccessPolicyRepository policyRepository;
    @Mock private TableClassificationRepository tableClassificationRepository;
    @Mock private SchemaScannerService schemaScannerService;
    @Mock private ObjectProvider<SchemaScannerService> schemaScannerServiceProvider;
    @Mock private SecurityEventService securityEventService;

    private ConnectionChatAccessPolicyService service;

    @BeforeEach
    void setUp() throws SQLException {
        service = new ConnectionChatAccessPolicyService(
            policyRepository,
            tableClassificationRepository,
            schemaScannerServiceProvider,
            securityEventService
        );
        when(schemaScannerServiceProvider.getIfAvailable()).thenReturn(schemaScannerService);

        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            schemaTable(null, "customer_profiles", "email", "varchar", "phone_number", "varchar", "full_name", "varchar"),
            schemaTable(null, "payment_profiles", "credit_card_last4", "varchar", "bank_account_masked", "varchar"),
            schemaTable(null, "bookings", "booking_id", "varchar", "status", "varchar")
        ));
        lenient().when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        lenient().when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(anyString()))
            .thenReturn(List.of(
                classification(
                    "customer_profiles",
                    "PII_MEDIUM",
                    List.of(
                        Map.of("column", "email", "type", "PII_MEDIUM"),
                        Map.of("column", "phone_number", "type", "PII_MEDIUM")
                    )
                ),
                classification(
                    "payment_profiles",
                    "FINANCIAL",
                    List.of(
                        Map.of("column", "credit_card_last4", "type", "FINANCIAL"),
                        Map.of("column", "bank_account_masked", "type", "FINANCIAL")
                    )
                )
            ));
    }

    @Test
    void previewPolicy_normalizesPiiAndFinancialRestrictions() {
        PolicyPreviewResponse preview = service.previewPolicy(
            "conn-1",
            "This user should not access customer PII or financial data like emails, phone numbers, bank details, credit card details."
        );

        assertThat(preview.getBlockedSensitivityCategories()).contains("PII_HIGH", "PII_MEDIUM", "FINANCIAL");
        assertThat(preview.getImpactedColumns())
            .contains("customer_profiles.email", "customer_profiles.phone_number", "payment_profiles.credit_card_last4");
        assertThat(preview.isBlockMode()).isTrue();
        assertThat(preview.isRedactMode()).isTrue();
    }

    @Test
    void previewPolicy_scopesTypedColumnConstraintsToAllowedSchema() throws SQLException {
        SchemaMetadata multiSchema = new SchemaMetadata();
        multiSchema.setTables(List.of(
            schemaTable("crm", "customers", "amount", "numeric", "name", "varchar"),
            schemaTable("sales", "orders", "amount", "numeric", "currency", "varchar"),
            schemaTable("marts", "fct_enrollment", "amount", "numeric", "currency", "varchar"),
            schemaTable("marts", "dim_ott_subscription", "amount", "numeric", "currency", "varchar")
        ));
        when(schemaScannerService.scanSchema("conn-ms")).thenReturn(multiSchema);
        lenient().when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-ms"))
            .thenReturn(List.of());

        String policyText = """
            This user should have access only to schema marts. In this table, the user cannot query \
            integer or float amount columns but can query columns that are string and represent currency code. \
            Strictly, The user cannot access any other schema other than marts
            """;

        PolicyPreviewResponse preview = service.previewPolicy("conn-ms", policyText);

        assertThat(preview.getImpactedColumns())
            .contains("marts.fct_enrollment.amount", "marts.dim_ott_subscription.amount")
            .doesNotContain(
                "crm.customers.amount",
                "sales.orders.amount",
                "marts.fct_enrollment.currency",
                "marts.dim_ott_subscription.currency"
            );
    }

    @Test
    void previewPolicy_appliesTypedColumnConstraintsToAnyColumnName() throws SQLException {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            schemaTable("hr", "employees", "salary", "numeric", "email", "varchar"),
            schemaTable("finance", "ledger", "amount", "numeric", "account_code", "varchar")
        ));
        when(schemaScannerService.scanSchema("conn-hr")).thenReturn(schema);
        lenient().when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-hr"))
            .thenReturn(List.of());

        PolicyPreviewResponse preview = service.previewPolicy(
            "conn-hr",
            "This user should have access only to schema hr. The user cannot query numeric salary columns."
        );

        assertThat(preview.getImpactedColumns())
            .contains("hr.employees.salary")
            .doesNotContain("finance.ledger.amount", "hr.employees.email");
    }

    @Test
    void previewPolicy_typeOnlyConstraintIsStillSchemaScoped() throws SQLException {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            schemaTable("finance", "ledger", "amount", "numeric", "account_code", "varchar"),
            schemaTable("sales", "orders", "amount", "numeric", "status", "varchar")
        ));
        when(schemaScannerService.scanSchema("conn-fin")).thenReturn(schema);
        lenient().when(tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc("conn-fin"))
            .thenReturn(List.of());

        PolicyPreviewResponse preview = service.previewPolicy(
            "conn-fin",
            "Access only to schema finance. Redact numeric columns."
        );

        assertThat(preview.getImpactedColumns())
            .contains("finance.ledger.amount")
            .doesNotContain("sales.orders.amount", "finance.ledger.account_code");
    }

    @Test
    void previewPolicy_resolvesExplicitTableAndColumnMentions() {
        PolicyPreviewResponse preview = service.previewPolicy(
            "conn-1",
            "Block access to payment_profiles and customer_profiles.email for this user."
        );

        assertThat(preview.getDeniedTables()).contains("payment_profiles");
        assertThat(preview.getDeniedColumns()).contains("customer_profiles.email");
        assertThat(preview.getImpactedTables()).contains("payment_profiles");
        assertThat(preview.getImpactedColumns()).contains("customer_profiles.email");
    }

    private TableMetadata schemaTable(String schema, String name, String... columnSpecs) {
        TableMetadata table = new TableMetadata();
        table.setSchema(schema);
        table.setName(name);
        java.util.List<ColumnMetadata> columnMetadata = new java.util.ArrayList<>();
        for (int i = 0; i < columnSpecs.length; i += 2) {
            String columnName = columnSpecs[i];
            String dataType = i + 1 < columnSpecs.length ? columnSpecs[i + 1] : "varchar";
            columnMetadata.add(new ColumnMetadata(columnName, dataType, null, true, false, null, 0));
        }
        table.setColumns(columnMetadata);
        return table;
    }

    private TableClassification classification(String tableName, String sensitivityLevel, List<Map<String, Object>> sensitiveColumns) {
        TableClassification classification = new TableClassification();
        classification.setConnectionId("conn-1");
        classification.setTableName(tableName);
        classification.setSensitivityLevel(sensitivityLevel);
        classification.setSensitiveColumns(sensitiveColumns);
        return classification;
    }
}
