package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.pipeline.ResolvedContext;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class JoinPathResolutionPolicyTest {

    private final JoinPathResolutionPolicy policy = new JoinPathResolutionPolicy();

    @Test
    void enhanceResolution_usesSchemaRelationshipsToKeepRequestedEntityInScope() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("CUSTOMER_ORDERS",
                col("id"),
                col("order_amount"),
                col("user_email")),
            table("CONTACT_MAPPING",
                col("order_id"),
                col("user_name"),
                col("email"))
        ));
        schema.setRelationships(List.of(
            new RelationshipMetadata(
                "guest_booking",
                "CONTACT_MAPPING",
                "order_id",
                "CUSTOMER_ORDERS",
                "id",
                "many-to-one",
                "fk_guest_booking"
            )
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("CUSTOMER_ORDERS"),
            Map.of("CUSTOMER_ORDERS", List.of("order_amount", "user_email")),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        JoinPathResolutionPolicy.Decision decision = policy.enhanceResolution(
            "Show booking amounts with guest emails for each booking",
            schema,
            resolvedContext,
            List.of(),
            List.of()
        );

        assertThat(decision.hasEnhancement()).isTrue();
        assertThat(decision.resolvedContext().tables()).contains("CUSTOMER_ORDERS", "CONTACT_MAPPING");
        assertThat(decision.chosenJoinConditions())
            .contains("CONTACT_MAPPING.order_id = CUSTOMER_ORDERS.id");
        List<String> guestMappingColumns = decision.resolvedContext().columns().entrySet().stream()
            .filter(entry -> "CONTACT_MAPPING".equalsIgnoreCase(entry.getKey()))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());
        List<String> userBookingColumns = decision.resolvedContext().columns().entrySet().stream()
            .filter(entry -> "CUSTOMER_ORDERS".equalsIgnoreCase(entry.getKey()))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());

        assertThat(guestMappingColumns)
            .contains("user_name", "email");
        assertThat(userBookingColumns)
            .doesNotContain("user_email");
    }

    @Test
    void enhanceResolution_addsPersonEntityTableEvenWhenCurrentContextAlreadyHasTwoTables() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("CUSTOMER_ORDERS",
                col("id"),
                col("customer_id"),
                col("order_amount"),
                col("user_email")),
            table("CUSTOMERS",
                col("id"),
                col("name")),
            table("CONTACT_MAPPING",
                col("order_id"),
                col("user_name"),
                col("email"))
        ));
        schema.setRelationships(List.of(
            new RelationshipMetadata("ub_hotel", "CUSTOMER_ORDERS", "customer_id", "CUSTOMERS", "id", "many-to-one", "fk_ub_hotel"),
            new RelationshipMetadata("guest_booking", "CONTACT_MAPPING", "order_id", "CUSTOMER_ORDERS", "id", "many-to-one", "fk_guest_booking")
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("CUSTOMER_ORDERS", "CUSTOMERS"),
            Map.of(
                "CUSTOMER_ORDERS", List.of("order_amount"),
                "CUSTOMERS", List.of("name")
            ),
            List.of(),
            List.of("CUSTOMER_ORDERS.customer_id = CUSTOMERS.id"),
            ResolvedContext.Confidence.MEDIUM
        );

        JoinPathResolutionPolicy.Decision decision = policy.enhanceResolution(
            "Show top customers by total booking amount with their names and emails",
            schema,
            resolvedContext,
            List.of(),
            List.of()
        );

        assertThat(decision.hasEnhancement()).isTrue();
        assertThat(decision.resolvedContext().tables()).contains("CUSTOMER_ORDERS", "CUSTOMERS", "CONTACT_MAPPING");
        assertThat(decision.chosenJoinConditions())
            .contains("CONTACT_MAPPING.order_id = CUSTOMER_ORDERS.id");
    }

    @Test
    void enhanceResolution_infersForeignKeyStyleJoinWhenExplicitJoinGraphIsMissing() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("CUSTOMER_ORDERS",
                col("id"),
                col("order_amount"),
                col("order_made_on")),
            table("CONTACT_MAPPING",
                col("order_id"),
                col("user_name"),
                col("email"))
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("CUSTOMER_ORDERS"),
            Map.of("CUSTOMER_ORDERS", List.of("order_amount")),
            List.of(),
            List.of(),
            ResolvedContext.Confidence.MEDIUM
        );

        JoinPathResolutionPolicy.Decision decision = policy.enhanceResolution(
            "Show top customers by total order amount with their names and emails",
            schema,
            resolvedContext,
            List.of(),
            List.of()
        );

        assertThat(decision.hasEnhancement()).isTrue();
        assertThat(decision.resolvedContext().tables()).contains("CUSTOMER_ORDERS", "CONTACT_MAPPING");
        assertThat(decision.chosenJoinConditions())
            .contains("CONTACT_MAPPING.order_id = CUSTOMER_ORDERS.id");
    }

    private static TableMetadata table(String name, ColumnMetadata... columns) {
        TableMetadata table = new TableMetadata();
        table.setName(name);
        table.setColumns(List.of(columns));
        return table;
    }

    private static ColumnMetadata col(String name) {
        return new ColumnMetadata(name, "varchar", null, true, false, null, 1);
    }
}
