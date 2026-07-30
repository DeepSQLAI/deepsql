package com.dbaagent.model;

public enum QueryExecutionOrigin {
    CHAT,
    EDITOR,
    INTERNAL,
    MCP,
    SCHEDULED,
    API;

    public static QueryExecutionOrigin normalized(QueryExecutionOrigin origin) {
        return origin == null ? INTERNAL : origin;
    }
}
