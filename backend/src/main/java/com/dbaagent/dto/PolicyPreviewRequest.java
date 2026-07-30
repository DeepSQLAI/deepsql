package com.dbaagent.dto;

import lombok.Data;

@Data
public class PolicyPreviewRequest {
    private String connectionId;
    private String plainEnglishPolicy;
}
