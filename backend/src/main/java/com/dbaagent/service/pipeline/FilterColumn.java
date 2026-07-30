package com.dbaagent.service.pipeline;

public record FilterColumn(String table, String column) {
    public String qualifiedName() {
        return table + "." + column;
    }
}
