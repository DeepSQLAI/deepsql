package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import com.dbaagent.service.QueryExecutorService;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class PropertyStatusTool extends AbstractSqlAgentTool {

    public PropertyStatusTool(QueryExecutorService queryExecutorService) {
        super(queryExecutorService);
    }

    @Override
    public String name() {
        return "property_status_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        List<String> hotelIds = context.getMemory("churnCandidateHotelIds");
        if (hotelIds == null || hotelIds.isEmpty()) {
            return new AgentToolResult(
                new AgentObservation("property_status", "No churn candidates to validate property status for", Map.of()),
                null,
                null,
                0.6
            );
        }

        String sql = buildSql(hotelIds);
        QueryResult queryResult = executeQuery(context.connectionId(), sql, 50);
        context.putMemory("propertyStatusResult", queryResult);
        return new AgentToolResult(
            new AgentObservation(
                "property_status",
                "Fetched hotel and property status for churn candidates",
                Map.of("rowCount", queryResult.getRowCount() != null ? queryResult.getRowCount() : 0)
            ),
            queryResult,
            sql,
            0.9
        );
    }

    private String buildSql(List<String> hotelIds) {
        String idList = hotelIds.stream().map(this::quote).collect(Collectors.joining(", "));
        return "SELECT h.id AS hotel_id, h.name AS hotel_name, h.onboarding_status, hp.property_status, hp.inactive_date, hp.inactive_reason " +
            "FROM HOTEL h " +
            "LEFT JOIN (" +
            "  SELECT hp1.* FROM HOTEL_PRICING hp1 " +
            "  JOIN (SELECT hotel_id, MAX(id) AS max_id FROM HOTEL_PRICING GROUP BY hotel_id) latest " +
            "    ON latest.hotel_id = hp1.hotel_id AND latest.max_id = hp1.id" +
            ") hp ON hp.hotel_id = CAST(h.id AS UNSIGNED) " +
            "WHERE h.id IN (" + idList + ")";
    }
}
