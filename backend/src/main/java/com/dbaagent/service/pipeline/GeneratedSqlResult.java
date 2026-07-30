package com.dbaagent.service.pipeline;

public record GeneratedSqlResult(
    String sql,
    String response,
    boolean clarificationRequested
) {}
