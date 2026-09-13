package com.dbaagent.service;

import com.dbaagent.model.QueryExecutionOrigin;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExecutionContextTest {

    @Test
    void mcpFactoryProducesReadOnlyContextWithMcpOrigin() {
        QueryExecutionContext ctx = QueryExecutionContext.mcp("user-1");
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.READ_ONLY_ONLY);
        assertThat(ctx.actorUsername()).isEqualTo("user-1");
        assertThat(ctx.actorMayMutate()).isFalse();
        assertThat(ctx.mutationConfirmed()).isFalse();
    }

    @Test
    void mcpFactoryHonoursMayMutateFlagFromSecurityContext() {
        QueryExecutionContext ctx = QueryExecutionContext.mcp("admin", true);
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(ctx.actorUsername()).isEqualTo("admin");
        assertThat(ctx.actorMayMutate()).isTrue();
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.MAY_MUTATE);
        assertThat(ctx.mutationConfirmed()).isFalse();
    }

    @Test
    void mcpDbaMayMutateWithConfirmation() {
        QueryExecutionContext ctx = QueryExecutionContext.mcp("dba", true, true);
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.MAY_MUTATE);
        assertThat(ctx.actorMayMutate()).isTrue();
        assertThat(ctx.mutationConfirmed()).isTrue();
    }

    @Test
    void mcpAdminConfirmedFactoryPassesConfirmationFlag() {
        QueryExecutionContext ctx = QueryExecutionContext.mcp("admin", true, true);
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.MAY_MUTATE);
        assertThat(ctx.mutationConfirmed()).isTrue();
    }

    @Test
    void mcpNonMutatorRemainsReadOnlyEvenWhenConfirmed() {
        QueryExecutionContext ctx = QueryExecutionContext.mcp("dev", false, true);
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.READ_ONLY_ONLY);
        assertThat(ctx.actorMayMutate()).isFalse();
    }

    @Test
    void editorDbaGetsMayMutateMode() {
        QueryExecutionContext ctx = QueryExecutionContext.editor("dba", true, false);
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.EDITOR);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.MAY_MUTATE);
        assertThat(ctx.actorMayMutate()).isTrue();
    }

    @Test
    void forSqlSurfaceSelectsMcpOrEditorOrigin() {
        QueryExecutionContext mcp = QueryExecutionContext.forSqlSurface(true, "admin", true, true);
        assertThat(mcp.origin()).isEqualTo(QueryExecutionOrigin.MCP);
        assertThat(mcp.mutationConfirmed()).isTrue();

        QueryExecutionContext editor = QueryExecutionContext.forSqlSurface(false, "admin", true, true);
        assertThat(editor.origin()).isEqualTo(QueryExecutionOrigin.EDITOR);
        assertThat(editor.mutationConfirmed()).isTrue();
    }

    @Test
    void scheduledFactoryProducesMayMutateInternalActor() {
        QueryExecutionContext ctx = QueryExecutionContext.scheduled();
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.SCHEDULED);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.MAY_MUTATE);
        assertThat(ctx.actorUsername()).isNull();
        assertThat(ctx.actorMayMutate()).isTrue();
        assertThat(ctx.mutationConfirmed()).isTrue();
    }

    @Test
    void apiFactoryProducesReadOnlyContextWithApiOrigin() {
        QueryExecutionContext ctx = QueryExecutionContext.api("svc-account");
        assertThat(ctx.origin()).isEqualTo(QueryExecutionOrigin.API);
        assertThat(ctx.mutationMode()).isEqualTo(QueryExecutionContext.MutationMode.READ_ONLY_ONLY);
        assertThat(ctx.actorUsername()).isEqualTo("svc-account");
    }
}
