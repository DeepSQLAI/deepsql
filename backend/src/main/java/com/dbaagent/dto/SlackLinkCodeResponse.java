package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

import java.time.LocalDateTime;

@Value
@Builder
public class SlackLinkCodeResponse {
    String code;
    LocalDateTime createdAt;
    LocalDateTime expiresAt;
}
