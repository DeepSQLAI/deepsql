package com.dbaagent.model;

import lombok.Data;

@Data
public class McpReadOnlyExplainRequest {
    private String connectionId;
    private String query;
}
