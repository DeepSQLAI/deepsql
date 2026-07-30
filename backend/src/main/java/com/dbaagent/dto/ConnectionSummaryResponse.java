package com.dbaagent.dto;

import lombok.Data;

@Data
public class ConnectionSummaryResponse {
    private String id;
    private String connectionName;
    private String dbType;
    private String host;
    private Integer port;
    private String database;
    private String username;
    private Boolean ssl;
    private String sslMode;
    private Boolean sshEnabled;
    private String sshAuthType;
    private String sshHost;
    private Integer sshPort;
    private String sshUsername;
    private String cloudProvider;
    private String managedService;
    private String instanceClass;
    private Integer instanceVcpus;
    private Double instanceMemoryGb;
    private String storageType;
    private Integer storageMaxIops;
    private Boolean enableDataSampling;
    private String ownerUsername;
    private String ownershipType;
    private String accessLevel;
    private Boolean canManageConfig;
    private Boolean canManageContent;
}
