package com.dbaagent.service.agent;

import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.service.QueryExecutorService;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UsageDropAnalysisToolTest {

    @Test
    void execute_buildsIndexFriendlyTimestampPredicate() throws Exception {
        QueryExecutorService queryExecutorService = mock(QueryExecutorService.class);
        UsageDropAnalysisTool tool = new UsageDropAnalysisTool(queryExecutorService);
        QueryResult queryResult = new QueryResult(
            List.of("hotel_id"),
            List.of(List.of("33160")),
            1,
            1L,
            false,
            10L,
            "SELECT 1"
        );

        when(queryExecutorService.executeQuery(eq("conn-1"), org.mockito.ArgumentMatchers.any(QueryRequest.class)))
            .thenReturn(queryResult);

        AgentExecutionContext context = new AgentExecutionContext("conn-1", "Which hotels are about to churn?", null, "mysql");
        tool.execute(new AgentPlanStep("usage-drop", "Measure usage drop", "usage_drop_analysis_tool", Map.of()), context);

        ArgumentCaptor<QueryRequest> requestCaptor = ArgumentCaptor.forClass(QueryRequest.class);
        verify(queryExecutorService).executeQuery(eq("conn-1"), requestCaptor.capture());

        String sql = requestCaptor.getValue().getQuery();
        assertTrue(sql.contains("FROM META_HOTEL_LOGS"));
        assertTrue(sql.contains("mhl.log_timestamp >="));
        assertFalse(sql.contains("DATE("));
    }
}
