package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import com.dbaagent.service.QueryExecutorService;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SubscriptionRevenueAdjustmentTool extends AbstractSqlAgentTool {

    public SubscriptionRevenueAdjustmentTool(QueryExecutorService queryExecutorService) {
        super(queryExecutorService);
    }

    @Override
    public String name() {
        return "subscription_revenue_adjustment_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        String sql = buildSql(step.params());
        QueryResult queryResult = executeQuery(context.connectionId(), sql, 100);
        context.putMemory("revenueAdjustmentResult", queryResult);
        return new AgentToolResult(
            new AgentObservation(
                "subscription_revenue_adjustments",
                "Fetched obvious adjustment-like credit rows for the same revenue window",
                Map.of("rowCount", queryResult.getRowCount() != null ? queryResult.getRowCount() : 0)
            ),
            queryResult,
            sql,
            0.82
        );
    }

    private String buildSql(Map<String, Object> params) {
        String groupBy = String.valueOf(params.getOrDefault("groupBy", "CURRENCY")).toUpperCase(Locale.ROOT);
        String dateFrom = String.valueOf(params.get("dateFrom"));
        String dateToExclusive = String.valueOf(params.get("dateToExclusive"));
        @SuppressWarnings("unchecked")
        List<String> countryFilters = (List<String>) params.getOrDefault("countryFilters", List.of());
        @SuppressWarnings("unchecked")
        List<String> currencyFilters = (List<String>) params.getOrDefault("currencyFilters", List.of());

        boolean joinAccounts = "COUNTRY".equals(groupBy) || !countryFilters.isEmpty();
        String dimensionExpr = "COUNTRY".equals(groupBy)
            ? "a.billing_address_country"
            : "al.currency";

        List<String> where = new ArrayList<>();
        where.add("al.mode = 'SUBSCRIPTION'");
        where.add("al.type = 'CREDIT'");
        where.add("al.status = 'SUCCESS'");
        where.add("COALESCE(al.currency, '') <> ''");
        where.add("al.date >= " + quote(dateFrom));
        where.add("al.date < " + quote(dateToExclusive));
        where.add("(LOWER(COALESCE(al.description, '')) LIKE '%revised%' OR LOWER(COALESCE(al.description, '')) LIKE '%reset%' OR LOWER(COALESCE(al.description, '')) LIKE '%test%' OR LOWER(COALESCE(al.description, '')) LIKE '%deactivation%')");
        if (!countryFilters.isEmpty()) {
            where.add("a.billing_address_country IN (" + countryFilters.stream().map(this::quote).collect(Collectors.joining(", ")) + ")");
        }
        if (!currencyFilters.isEmpty()) {
            where.add("al.currency IN (" + currencyFilters.stream().map(this::quote).collect(Collectors.joining(", ")) + ")");
        }

        return "SELECT " + dimensionExpr + " AS dimension_key, COUNT(*) AS adjustment_like_rows, ROUND(SUM(al.amount), 2) AS adjustment_like_amount " +
            "FROM ACCOUNT_LEDGER al " +
            (joinAccounts ? "JOIN ACCOUNTS a ON a.group_id = al.group_id " : "") +
            "WHERE " + String.join(" AND ", where) + " " +
            "GROUP BY " + dimensionExpr + " " +
            "ORDER BY adjustment_like_amount DESC, dimension_key";
    }
}
