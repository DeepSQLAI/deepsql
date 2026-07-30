package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class ConnectionAccessGrantResponse {
    String connectionId;
    String connectionName;
    String dbType;
    String accessLevel;
    ConnectionChatAccessPolicyResponse chatAccessPolicy;
    String grantedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
}
