package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * Execution origin + mutation privileges for a SQL run.
 *
 * <p>{@code actorMayMutate} is true for built-in ADMIN and DBA (wired from
 * {@code AccessControlService.currentUserMayMutateSql()}). It gates Editor/MCP
 * DDL/DML; chat stays {@link MutationMode#READ_ONLY_ONLY}. The same flag is
 * consulted by data-access policy during query execution so mutators are not
 * blocked by schema redaction meant for read-only roles.
 */
public record QueryExecutionContext(
    QueryExecutionOrigin origin,
    MutationMode mutationMode,
    String actorUsername,
    boolean actorMayMutate,
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

    public static QueryExecutionContext editor(String actorUsername, boolean actorMayMutate, boolean mutationConfirmed) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.EDITOR,
            actorMayMutate ? MutationMode.MAY_MUTATE : MutationMode.READ_ONLY_ONLY,
            actorUsername,
            actorMayMutate,
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

    public static QueryExecutionContext mcp(String actorUsername, boolean actorMayMutate) {
        return mcp(actorUsername, actorMayMutate, false);
    }

    /**
     * MCP / coding-agent SQL. Developers stay read-only. Admins and DBAs may run
     * non-destructive DDL/DML after the same confirmation gate as the Editor.
     * DROP and TRUNCATE stay blocked in {@link QueryExecutionPolicyService}.
     */
    public static QueryExecutionContext mcp(
        String actorUsername,
        boolean actorMayMutate,
        boolean mutationConfirmed
    ) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.MCP,
            actorMayMutate ? MutationMode.MAY_MUTATE : MutationMode.READ_ONLY_ONLY,
            actorUsername,
            actorMayMutate,
            mutationConfirmed
        );
    }

    public static QueryExecutionContext forSqlSurface(
        boolean mcpBearer,
        String actorUsername,
        boolean actorMayMutate,
        boolean mutationConfirmed
    ) {
        if (mcpBearer) {
            return mcp(actorUsername, actorMayMutate, mutationConfirmed);
        }
        return editor(actorUsername, actorMayMutate, mutationConfirmed);
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

    /**
     * API / dashboard SQL is always read-only. The {@code actorMayMutate} flag here
     * only influences data-access policy bypass (admins), not mutation mode.
     */
    public static QueryExecutionContext api(String actorUsername, boolean actorMayMutate) {
        return new QueryExecutionContext(
            QueryExecutionOrigin.API,
            MutationMode.READ_ONLY_ONLY,
            actorUsername,
            actorMayMutate,
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
