package com.dbaagent.controller;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class McpControllerOriginTest {

    @Test
    void mcpEndpointSetsMcpOriginOnQueryRequest() {
        java.nio.file.Path src = java.nio.file.Paths.get(
            "src/main/java/com/dbaagent/controller/McpController.java");
        String body;
        try { body = java.nio.file.Files.readString(src); }
        catch (Exception e) { throw new RuntimeException(e); }

        assertThat(body).contains("QueryExecutionOrigin.MCP");
        assertThat(body).contains("QueryExecutionContext.mcp(");
        assertThat(body).doesNotContain("QueryExecutionContext.chat()");
    }
}
