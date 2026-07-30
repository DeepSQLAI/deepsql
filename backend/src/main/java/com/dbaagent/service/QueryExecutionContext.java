package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;

public record QueryExecutionContext(
    QueryExecutionOrigin origin,
    MutationMode mutationMode,
    String actorUsername,
    boolean actorIsAdmin,
    boolean mutationConfirmed
) {

    public enum MutationMode {
        READ_ONLY_ONLY,
        MAY_MUTATE
    }

    public static QueryExecutionContext chat() {
        return new QueryExecutionContext(
            QueryExecutionOrigin.CHAT,
            MutationMode.READ_ONLY_ONLY,
            QueryActorContextHolder.currentUsername(),
            false,
            false
        );
    }

    public static QueryExecutionContext editor(String actorUsername, boolean actorIsAdmin, boolean mutationConfirmed) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.EDITOR,
            actorIsAdmin ? MutationMode.MAY_MUTATE : MutationMode.READ_ONLY_ONLY,
            actorUsername,
            actorIsAdmin,
            mutationConfirmed
        );
    }

    public static QueryExecutionContext internal() {
        return new QueryExecutionContext(
            QueryExecutionOrigin.INTERNAL,
            MutationMode.MAY_MUTATE,
            null,
            true,
            true
        );
    }

    public static QueryExecutionContext mcp(String actorUsername) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.MCP,
            MutationMode.READ_ONLY_ONLY,
            actorUsername,
            false,
            false
        );
    }

    public static QueryExecutionContext scheduled() {
        return new QueryExecutionContext(
            QueryExecutionOrigin.SCHEDULED,
            MutationMode.MAY_MUTATE,
            null,
            true,
            true
        );
    }

    public static QueryExecutionContext api(String actorUsername) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.API,
            MutationMode.READ_ONLY_ONLY,
            actorUsername,
            false,
            false
        );
    }
}
