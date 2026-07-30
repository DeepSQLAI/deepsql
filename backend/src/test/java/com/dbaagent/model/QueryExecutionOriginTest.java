package com.dbaagent.model;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class QueryExecutionOriginTest {

    @Test
    void enumIncludesPhase25Sources() {
        assertThat(QueryExecutionOrigin.values())
            .containsExactlyInAnyOrder(
                QueryExecutionOrigin.CHAT,
                QueryExecutionOrigin.EDITOR,
                QueryExecutionOrigin.INTERNAL,
                QueryExecutionOrigin.MCP,
                QueryExecutionOrigin.SCHEDULED,
                QueryExecutionOrigin.API
            );
    }

    @Test
    void normalizedReturnsInternalForNull() {
        assertThat(QueryExecutionOrigin.normalized(null))
            .isEqualTo(QueryExecutionOrigin.INTERNAL);
    }

    @Test
    void normalizedIsIdentityForNonNull() {
        assertThat(QueryExecutionOrigin.normalized(QueryExecutionOrigin.MCP))
            .isEqualTo(QueryExecutionOrigin.MCP);
    }
}
