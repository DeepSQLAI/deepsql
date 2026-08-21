package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;
import java.util.List;

@Value
@Builder
public class ConnectionChatAccessPolicyResponse {
    Long id;
    String connectionId;
    String username;
    String plainEnglishPolicy;
    List<String> blockedSensitivityCategories;
    List<String> deniedTables;
    List<String> deniedColumns;
    List<String> allowedSchemas;
    boolean allowAggregates;
    boolean blockMode;
    boolean redactMode;
    boolean active;
    String updatedBy;
    LocalDateTime createdAt;
    LocalDateTime updatedAt;
    List<String> impactedTables;
    List<String> impactedColumns;
}
