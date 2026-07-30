package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SlackDailyDigestServiceOriginTest {

    @Test
    void slackDigestSqlExecutionIsTaggedScheduled() {
        java.nio.file.Path src = java.nio.file.Paths.get(
            "src/main/java/com/dbaagent/service/SlackDailyDigestService.java");
        String body;
        try { body = java.nio.file.Files.readString(src); }
        catch (Exception e) { throw new RuntimeException(e); }

        assertThat(body).contains("QueryExecutionContext.scheduled()");
        assertThat(body).doesNotContain(
            "queryExecutorService.executeQuery(connectionId, new QueryRequest(sql, 20, 20))");
    }
}
