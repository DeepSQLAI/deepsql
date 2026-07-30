package com.dbaagent.model;

import lombok.Data;

@Data
public class McpReadOnlyQueryRequest {
    private String connectionId;
    private String query;
    private Integer limit;
    private Integer timeoutSeconds;
}
