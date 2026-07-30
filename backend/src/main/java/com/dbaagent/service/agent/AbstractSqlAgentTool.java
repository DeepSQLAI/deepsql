package com.dbaagent.service.agent;

import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.QueryExecutionOrigin;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutorService;

public abstract class AbstractSqlAgentTool implements AgentTool {

    private final QueryExecutorService queryExecutorService;

    protected AbstractSqlAgentTool(QueryExecutorService queryExecutorService) {
        this.queryExecutorService = queryExecutorService;
    }

    protected QueryResult executeQuery(String connectionId, String sql, Integer limit) {
        QueryRequest request = new QueryRequest();
        request.setQuery(sql);
        request.setLimit(limit);
        request.setTimeoutSeconds(30);
        request.setExecutionOrigin(QueryExecutionOrigin.CHAT);
        try {
            return queryExecutorService.executeQuery(connectionId, request, QueryExecutionContext.chat());
        } catch (java.sql.SQLException e) {
            throw new IllegalStateException("Agent SQL execution failed: " + e.getMessage(), e);
        }
    }

    protected String quote(String value) {
        return "'" + value.replace("'", "''") + "'";
    }
}
