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
public class SubscriptionRevenueCollectedTool extends AbstractSqlAgentTool {

    public SubscriptionRevenueCollectedTool(QueryExecutorService queryExecutorService) {
        super(queryExecutorService);
    }

    @Override
    public String name() {
        return "subscription_revenue_collected_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        String sql = buildSql(step.params());
        QueryResult queryResult = executeQuery(context.connectionId(), sql, 100);
        context.putMemory("revenueCollectedResult", queryResult);
        return new AgentToolResult(
            new AgentObservation(
                "subscription_revenue_collected",
                "Fetched collected subscription revenue rows grouped by " + step.params().getOrDefault("groupBy", "currency"),
                Map.of("rowCount", queryResult.getRowCount() != null ? queryResult.getRowCount() : 0)
            ),
            queryResult,
            sql,
            queryResult.getRowCount() != null && queryResult.getRowCount() > 0 ? 0.95 : 0.55
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
        if (!countryFilters.isEmpty()) {
            where.add("a.billing_address_country IN (" + countryFilters.stream().map(this::quote).collect(Collectors.joining(", ")) + ")");
        }
        if (!currencyFilters.isEmpty()) {
            where.add("al.currency IN (" + currencyFilters.stream().map(this::quote).collect(Collectors.joining(", ")) + ")");
        }

        return "SELECT " + dimensionExpr + " AS dimension_key, COUNT(*) AS credit_rows, ROUND(SUM(al.amount), 2) AS collected_amount " +
            "FROM ACCOUNT_LEDGER al " +
            (joinAccounts ? "JOIN ACCOUNTS a ON a.group_id = al.group_id " : "") +
            "WHERE " + String.join(" AND ", where) + " " +
            "GROUP BY " + dimensionExpr + " " +
            "ORDER BY collected_amount DESC, dimension_key";
    }
}
