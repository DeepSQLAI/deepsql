package com.dbaagent.dto;

import lombok.Data;

@Data
public class ConnectionChatAccessPolicyUpsertRequest {
    private String plainEnglishPolicy;
    private Boolean active;
}
