package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AssignableConnectionOption {
    String connectionId;
    String connectionName;
    String dbType;
}
