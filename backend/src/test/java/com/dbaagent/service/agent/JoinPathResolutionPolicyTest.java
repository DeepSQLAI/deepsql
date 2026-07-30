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
            table("USER_BOOKINGS",
                col("id"),
                col("booking_amount"),
                col("user_email")),
            table("GUEST_MAPPING",
                col("booking_id"),
                col("user_name"),
                col("email"))
        ));
        schema.setRelationships(List.of(
            new RelationshipMetadata(
                "guest_booking",
                "GUEST_MAPPING",
                "booking_id",
                "USER_BOOKINGS",
                "id",
                "many-to-one",
                "fk_guest_booking"
            )
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("USER_BOOKINGS"),
            Map.of("USER_BOOKINGS", List.of("booking_amount", "user_email")),
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
        assertThat(decision.resolvedContext().tables()).contains("USER_BOOKINGS", "GUEST_MAPPING");
        assertThat(decision.chosenJoinConditions())
            .contains("GUEST_MAPPING.booking_id = USER_BOOKINGS.id");
        List<String> guestMappingColumns = decision.resolvedContext().columns().entrySet().stream()
            .filter(entry -> "GUEST_MAPPING".equalsIgnoreCase(entry.getKey()))
            .map(java.util.Map.Entry::getValue)
            .findFirst()
            .orElse(List.of());
        List<String> userBookingColumns = decision.resolvedContext().columns().entrySet().stream()
            .filter(entry -> "USER_BOOKINGS".equalsIgnoreCase(entry.getKey()))
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
            table("USER_BOOKINGS",
                col("id"),
                col("hotel_id"),
                col("booking_amount"),
                col("user_email")),
            table("HOTEL",
                col("id"),
                col("name")),
            table("GUEST_MAPPING",
                col("booking_id"),
                col("user_name"),
                col("email"))
        ));
        schema.setRelationships(List.of(
            new RelationshipMetadata("ub_hotel", "USER_BOOKINGS", "hotel_id", "HOTEL", "id", "many-to-one", "fk_ub_hotel"),
            new RelationshipMetadata("guest_booking", "GUEST_MAPPING", "booking_id", "USER_BOOKINGS", "id", "many-to-one", "fk_guest_booking")
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("USER_BOOKINGS", "HOTEL"),
            Map.of(
                "USER_BOOKINGS", List.of("booking_amount"),
                "HOTEL", List.of("name")
            ),
            List.of(),
            List.of("USER_BOOKINGS.hotel_id = HOTEL.id"),
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
        assertThat(decision.resolvedContext().tables()).contains("USER_BOOKINGS", "HOTEL", "GUEST_MAPPING");
        assertThat(decision.chosenJoinConditions())
            .contains("GUEST_MAPPING.booking_id = USER_BOOKINGS.id");
    }

    @Test
    void enhanceResolution_infersForeignKeyStyleJoinWhenExplicitJoinGraphIsMissing() {
        SchemaMetadata schema = new SchemaMetadata();
        schema.setTables(List.of(
            table("USER_BOOKINGS",
                col("id"),
                col("booking_amount"),
                col("booking_made_on")),
            table("GUEST_MAPPING",
                col("booking_id"),
                col("user_name"),
                col("email"))
        ));

        ResolvedContext resolvedContext = new ResolvedContext(
            List.of("USER_BOOKINGS"),
            Map.of("USER_BOOKINGS", List.of("booking_amount")),
            List.of(),
            List.of(),
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
        assertThat(decision.resolvedContext().tables()).contains("USER_BOOKINGS", "GUEST_MAPPING");
        assertThat(decision.chosenJoinConditions())
            .contains("GUEST_MAPPING.booking_id = USER_BOOKINGS.id");
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
