package com.dbaagent.service.migration;

public record DdlFacts(
        DdlOperation operation,
        String table,
        String column,
        String newColumnName,
        String dataType,
        String defaultExpression,
        String defaultFunction,
        boolean notNull,
        boolean notValid,
        boolean concurrently,
        String referencedTable,
        String rawSql) {}
