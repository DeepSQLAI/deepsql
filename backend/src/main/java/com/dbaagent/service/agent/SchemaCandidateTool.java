package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Predicate;
import java.util.stream.Collectors;

@Service
public class SchemaCandidateTool implements AgentTool {

    @Override
    public String name() {
        return "schema_candidate_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        SchemaMetadata schema = context.schema();
        Map<String, List<String>> categories = new LinkedHashMap<>();
        categories.put("guestAccountCore", findTables(schema, name -> matchesAny(name,
            "USER", "USER_BOOKINGS", "PAYMENTS", "PAYMENT_REFUNDS", "PAYMENT_INVOICES",
            "BOOKING_FEES", "HOTEL_SERVICES", "ORDERS", "ORDER_PAYMENT")));
        categories.put("folioAndInvoice", findTables(schema, name -> matchesAny(name,
            "ISHA_FOLIO_DETAIL", "CUSTOM_FOLIO_CONFIG", "CUSTOM_BOOKING_INVOICE_NUMBERS",
            "CUSTOM_INVOICE_NUMBER_CONFIG", "FOLIO_INVOICE_CONFIG", "SHOP_INVOICE_CONFIG", "ISHA_ERP_INVOICE")));
        categories.put("customerEnrichment", findTables(schema, name -> matchesAny(name,
            "GUEST_COMPANY_DETAILS", "CUSTOMER_NOTES", "DO_NOT_RENT_STATUS", "HOTEL")));
        categories.put("platformBilling", findTables(schema, name -> matchesAny(name,
            "ACCOUNTS", "ACCOUNT_LEDGER", "ACCOUNT_LEDGER_CREDIT", "ACCOUNT_PAYMENT_METHODS",
            "ACCOUNT_PAYMENT_SCHEDULE", "HOTEL_PRICING")));

        context.putMemory("accountsCandidateCategories", categories);
        return new AgentToolResult(
            new AgentObservation(
                "accounts_schema_candidates",
                "Bucketed schema tables into customer ledger versus platform billing domains",
                Map.of("categories", categories)
            ),
            null,
            null,
            categories.values().stream().mapToInt(List::size).sum() > 0 ? 0.9 : 0.45
        );
    }

    private List<String> findTables(SchemaMetadata schema, Predicate<String> predicate) {
        if (schema == null || schema.getTables() == null) {
            return List.of();
        }
        return schema.getTables().stream()
            .map(TableMetadata::getName)
            .filter(name -> name != null && predicate.test(name.toUpperCase(Locale.ROOT)))
            .distinct()
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private boolean matchesAny(String tableName, String... candidates) {
        for (String candidate : candidates) {
            if (tableName.equalsIgnoreCase(candidate)) {
                return true;
            }
        }
        return false;
    }
}
