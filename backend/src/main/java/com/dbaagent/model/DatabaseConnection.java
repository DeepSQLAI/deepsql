package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "encrypted_credentials")
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DatabaseConnection {
    @Id
    @Column(length = 36)
    private String id;

    @Column(name = "connection_name", nullable = false)
    private String connectionName;

    @Column(name = "db_type", nullable = false, length = 50)
    private String dbType; // "mysql" or "postgres"

    @Lob
    @Column(name = "encrypted_host", nullable = false)
    private byte[] encryptedHost;

    @Lob
    @Column(name = "encrypted_port")
    private byte[] encryptedPort;

    @Lob
    @Column(name = "encrypted_database", nullable = false)
    private byte[] encryptedDatabase;

    @Lob
    @Column(name = "encrypted_username", nullable = false)
    private byte[] encryptedUsername;

    @Lob
    @Column(name = "encrypted_password", nullable = false)
    private byte[] encryptedPassword;

    @Lob
    @Column(name = "encrypted_ssl_config")
    private byte[] encryptedSslConfig;

    // SSL Certificate Configuration (new granular SSL mode)
    @Lob
    @Column(name = "encrypted_ssl_mode")
    private byte[] encryptedSslMode;

    @Lob
    @Column(name = "encrypted_ssl_ca_cert")
    private byte[] encryptedSslCaCertificate;

    @Lob
    @Column(name = "encrypted_ssl_client_cert")
    private byte[] encryptedSslClientCertificate;

    @Lob
    @Column(name = "encrypted_ssl_client_key")
    private byte[] encryptedSslClientKey;

    @Lob
    @Column(name = "encrypted_ssl_client_key_passphrase")
    private byte[] encryptedSslClientKeyPassphrase;

    // SSH Tunnel Configuration
    @Column(name = "ssh_enabled")
    private Boolean sshEnabled = false;

    @Column(name = "ssh_auth_type", length = 20)
    private String sshAuthType = "PASSWORD"; // PASSWORD or PRIVATE_KEY

    @Lob
    @Column(name = "encrypted_ssh_host")
    private byte[] encryptedSshHost;

    @Lob
    @Column(name = "encrypted_ssh_port")
    private byte[] encryptedSshPort;

    @Lob
    @Column(name = "encrypted_ssh_username")
    private byte[] encryptedSshUsername;

    @Lob
    @Column(name = "encrypted_ssh_password")
    private byte[] encryptedSshPassword;

    @Lob
    @Column(name = "encrypted_ssh_private_key")
    private byte[] encryptedSshPrivateKey;

    @Lob
    @Column(name = "encrypted_ssh_passphrase")
    private byte[] encryptedSshPassphrase;

    @Column(name = "owner_username")
    private String ownerUsername;

    // Cloud Provider Context (for feature gating)
    @Column(name = "cloud_provider", length = 20)
    private String cloudProvider; // aws, azure, gcp, self-hosted, null

    @Column(name = "managed_service", length = 30)
    private String managedService; // rds, aurora, cloud-sql, azure-flexible, azure-single, null

    // Instance sizing context (for tuning calculations)
    @Column(name = "instance_class", length = 64)
    private String instanceClass; // e.g., AWS: db.m6g.4xlarge, Azure: GP_Gen5_8, GCP: db-n1-standard-4

    @Column(name = "instance_vcpus")
    private Integer instanceVcpus; // vCPU/vCore count

    @Column(name = "instance_memory_gb")
    private Double instanceMemoryGb; // total RAM in GB

    @Column(name = "storage_type", length = 30)
    private String storageType; // e.g., gp3, io1, premium-ssd, pd-ssd

    @Column(name = "storage_max_iops")
    private Integer storageMaxIops; // provisioned IOPS (if applicable)

    @Column(name = "enable_data_sampling")
    private Boolean enableDataSampling = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "last_used")
    private LocalDateTime lastUsed;
}
