package com.dbaagent.service.pipeline;

public record AdaptedSqlResult(
    String adaptedSql,
    String syntheticResponse,
    String originalQuestion,
    double similarityScore
) {}
