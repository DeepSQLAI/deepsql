package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import com.dbaagent.service.QueryExecutorService;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class UsageDropAnalysisTool extends AbstractSqlAgentTool {

    public UsageDropAnalysisTool(QueryExecutorService queryExecutorService) {
        super(queryExecutorService);
    }

    @Override
    public String name() {
        return "usage_drop_analysis_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        String sql = buildSql(step.params());
        QueryResult queryResult = executeQuery(context.connectionId(), sql, 50);
        List<String> hotelIds = extractHotelIds(queryResult);
        context.putMemory("churnCandidateHotelIds", hotelIds);
        context.putMemory("usageDropResult", queryResult);
        return new AgentToolResult(
            new AgentObservation(
                "usage_drop_candidates",
                "Measured recent usage decline for candidate hotels",
                Map.of("hotelIds", hotelIds, "rowCount", queryResult.getRowCount() != null ? queryResult.getRowCount() : 0)
            ),
            queryResult,
            sql,
            hotelIds.isEmpty() ? 0.5 : 0.9
        );
    }

    private String buildSql(Map<String, Object> params) {
        int baselineDays = (Integer) params.getOrDefault("baselineDays", 28);
        int recentDays = (Integer) params.getOrDefault("recentDays", 7);
        int baselineMinimum = (Integer) params.getOrDefault("baselineMinimum", 20);
        int topN = (Integer) params.getOrDefault("topN", 10);

        LocalDate today = LocalDate.now();
        LocalDate recentStart = today.minusDays(recentDays - 1L);
        LocalDate recentEndExclusive = today.plusDays(1);
        LocalDate baselineStart = recentStart.minusDays(baselineDays);
        String recentStartTs = recentStart + " 00:00:00";
        String recentEndExclusiveTs = recentEndExclusive + " 00:00:00";
        String baselineStartTs = baselineStart + " 00:00:00";

        return "WITH prior_usage AS (" +
            " SELECT mhl.hotel_id, COUNT(*) AS prior_usage " +
            " FROM META_HOTEL_LOGS mhl " +
            " WHERE mhl.log_timestamp >= '" + baselineStartTs + "' AND mhl.log_timestamp < '" + recentStartTs + "' " +
            " GROUP BY mhl.hotel_id " +
            " HAVING COUNT(*) >= " + baselineMinimum +
            "), recent_usage AS (" +
            " SELECT mhl.hotel_id, COUNT(*) AS recent_usage " +
            " FROM META_HOTEL_LOGS mhl " +
            " WHERE mhl.log_timestamp >= '" + recentStartTs + "' AND mhl.log_timestamp < '" + recentEndExclusiveTs + "' " +
            " GROUP BY mhl.hotel_id " +
            "), candidate_hotels AS (" +
            " SELECT h.id AS hotel_id, h.name AS hotel_name, pu.prior_usage, COALESCE(ru.recent_usage, 0) AS recent_usage, " +
            "   ROUND(pu.prior_usage / " + (double) baselineDays + ", 2) AS prior_daily_avg, " +
            "   ROUND(COALESCE(ru.recent_usage, 0) / " + (double) recentDays + ", 2) AS recent_daily_avg, " +
            "   ROUND((1 - ((COALESCE(ru.recent_usage, 0) / " + (double) recentDays + ") / NULLIF((pu.prior_usage / " + (double) baselineDays + "), 0))) * 100, 2) AS drop_pct " +
            " FROM prior_usage pu " +
            " LEFT JOIN recent_usage ru ON ru.hotel_id = pu.hotel_id " +
            " JOIN HOTEL h ON h.id = CAST(pu.hotel_id AS CHAR) " +
            " WHERE LOWER(COALESCE(h.name, '')) NOT LIKE '%test%' " +
            "   AND COALESCE(h.name, '') NOT REGEXP '^(DCN-|CRM-|RJT-|WLG-)' " +
            ") " +
            "SELECT hotel_id, hotel_name, prior_usage, prior_daily_avg, recent_usage, recent_daily_avg, drop_pct " +
            "FROM candidate_hotels " +
            "WHERE drop_pct IS NOT NULL AND drop_pct >= 50 " +
            "ORDER BY drop_pct DESC, prior_usage DESC " +
            "LIMIT " + topN;
    }

    private List<String> extractHotelIds(QueryResult queryResult) {
        if (queryResult == null || queryResult.getRows() == null || queryResult.getColumns() == null) {
            return List.of();
        }
        int hotelIdIndex = queryResult.getColumns().indexOf("hotel_id");
        if (hotelIdIndex < 0) {
            return List.of();
        }
        return queryResult.getRows().stream()
            .map(row -> row.size() > hotelIdIndex && row.get(hotelIdIndex) != null ? String.valueOf(row.get(hotelIdIndex)) : null)
            .filter(value -> value != null && !value.isBlank())
            .collect(Collectors.toList());
    }
}
