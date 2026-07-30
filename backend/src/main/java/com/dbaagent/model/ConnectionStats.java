package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionStats {
    private Integer activeConnections;
    private Integer maxConnections;
    private Double poolUtilization;
    private Long connectionWaitTime;
}
