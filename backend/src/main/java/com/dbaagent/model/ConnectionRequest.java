package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionRequest {
    private String id; // Connection ID (optional, used for updates/deletes)
    private String connectionName;
    private String dbType; // "mysql" or "postgres"
    private String host;
    private Integer port;
    private String database;
    private String username;
    private String password;
    private Boolean ssl;

    // SSL Configuration (new granular SSL mode)
    private String sslMode;                  // "none", "server-only", "server-client"
    private String sslCaCertificate;         // PEM-encoded CA cert
    private String sslClientCertificate;     // PEM-encoded client cert
    private String sslClientKey;             // PEM-encoded client key
    private String sslClientKeyPassphrase;   // Passphrase for encrypted key

    // SSH Tunnel Configuration
    private Boolean sshEnabled = false;
    private String sshAuthType = "PASSWORD"; // PASSWORD or PRIVATE_KEY
    private String sshHost;
    private Integer sshPort = 22;
    private String sshUsername;
    private String sshPassword;
    private String sshPrivateKey;
    private String sshPassphrase;

    // Cloud Provider Context (for feature gating)
    private String cloudProvider; // aws, azure, gcp, self-hosted, null
    private String managedService; // rds, aurora, cloud-sql, azure-flexible, azure-single, null

    // Instance sizing context (for tuning calculations)
    private String instanceClass; // e.g., db.m6g.4xlarge, GP_Gen5_8, db-n1-standard-4
    private Integer instanceVcpus; // vCPU/vCore count
    private Double instanceMemoryGb; // total RAM in GB
    private String storageType; // e.g., gp3, io1, premium-ssd, pd-ssd
    private Integer storageMaxIops; // provisioned IOPS (if applicable)

    private Boolean enableDataSampling; // null = use default (true for save, preserve existing for update)

    /**
     * Get effective SSL mode, deriving from legacy ssl field if sslMode not set.
     * @return "none", "server-only", or "server-client"
     */
    public String getEffectiveSslMode() {
        if (sslMode != null && !sslMode.isBlank()) {
            return sslMode;
        }
        // Legacy fallback: derive from ssl boolean
        return Boolean.TRUE.equals(ssl) ? "server-only" : "none";
    }

    /**
     * Get effective SSL enabled state for backward compatibility.
     * Derives from sslMode if set, otherwise uses ssl field.
     */
    public Boolean getEffectiveSsl() {
        if (sslMode != null && !sslMode.isBlank()) {
            return !"none".equals(sslMode);
        }
        return ssl;
    }
}


