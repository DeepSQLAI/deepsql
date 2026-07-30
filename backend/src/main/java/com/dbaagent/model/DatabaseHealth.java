package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseHealth {
    private Double bufferPoolHitRatio;
    private Double cacheHitRatio;
    private Long lockWaitTime;
    private Integer deadlockCount;
}
