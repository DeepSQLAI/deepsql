package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

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
            resolveActorUsername(),
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
        return mcp(actorUsername, false);
    }

    public static QueryExecutionContext mcp(String actorUsername, boolean actorIsAdmin) {
        return mcp(actorUsername, actorIsAdmin, false);
    }

    /**
     * MCP / coding-agent SQL. Developers stay read-only. Admins may run
     * non-destructive DDL/DML after the same confirmation gate as the Editor.
     * DROP and TRUNCATE stay blocked in {@link QueryExecutionPolicyService}.
     */
    public static QueryExecutionContext mcp(
        String actorUsername,
        boolean actorIsAdmin,
        boolean mutationConfirmed
    ) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.MCP,
            actorIsAdmin ? MutationMode.MAY_MUTATE : MutationMode.READ_ONLY_ONLY,
            actorUsername,
            actorIsAdmin,
            mutationConfirmed
        );
    }

    public static QueryExecutionContext forSqlSurface(
        boolean mcpBearer,
        String actorUsername,
        boolean actorIsAdmin,
        boolean mutationConfirmed
    ) {
        if (mcpBearer) {
            return mcp(actorUsername, actorIsAdmin, mutationConfirmed);
        }
        return editor(actorUsername, actorIsAdmin, mutationConfirmed);
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
        return api(actorUsername, false);
    }

    public static QueryExecutionContext api(String actorUsername, boolean actorIsAdmin) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.API,
            MutationMode.READ_ONLY_ONLY,
            actorUsername,
            actorIsAdmin,
            false
        );
    }

    private static String resolveActorUsername() {
        String fromHolder = QueryActorContextHolder.currentUsername();
        if (fromHolder != null && !fromHolder.isBlank()) {
            return fromHolder;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated()) {
            return null;
        }
        String name = authentication.getName();
        if (name == null || name.isBlank() || "anonymousUser".equals(name)) {
            return null;
        }
        return name;
    }
}
