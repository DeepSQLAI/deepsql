package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class SlackLinkedConnectionResponse {
    String connectionId;
    String connectionName;
    String accessLevel;
    String ownershipType;
}
