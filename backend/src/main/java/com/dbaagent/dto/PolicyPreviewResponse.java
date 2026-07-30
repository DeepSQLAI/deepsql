package com.dbaagent.dto;

import lombok.Builder;
import lombok.Value;

import java.util.List;

@Value
@Builder
public class PolicyPreviewResponse {
    List<String> blockedSensitivityCategories;
    List<String> deniedTables;
    List<String> deniedColumns;
    List<String> impactedTables;
    List<String> impactedColumns;
    boolean blockMode;
    boolean redactMode;
}
