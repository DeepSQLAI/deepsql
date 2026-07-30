package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class UserConnectionAccessResponse {
    Long userId;
    String username;
    List<ConnectionAccessGrantResponse> assignments;
    List<AssignableConnectionOption> assignableConnections;
}
