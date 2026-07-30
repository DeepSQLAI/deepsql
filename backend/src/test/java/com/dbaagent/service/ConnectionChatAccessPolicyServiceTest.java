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
            table("customer_profiles", "email", "phone_number", "full_name"),
            table("payment_profiles", "credit_card_last4", "bank_account_masked"),
            table("bookings", "booking_id", "status")
        ));
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
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

    private TableMetadata table(String name, String... columns) {
        TableMetadata table = new TableMetadata();
        table.setName(name);
        table.setColumns(
            java.util.Arrays.stream(columns)
                .map(column -> new ColumnMetadata(column, "varchar", null, true, false, null, 0))
                .toList()
        );
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
