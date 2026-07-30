package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Key-value configuration store for runtime system settings.
 * Sensitive values (e.g. API keys) are AES-GCM encrypted before storage.
 */
@Entity
@Table(name = "system_config")
@Data
@NoArgsConstructor
public class SystemConfig {

    @Id
    @Column(length = 128)
    private String key;

    /** Plaintext for non-sensitive values; Base64(AES-GCM ciphertext) for sensitive ones. */
    @Column(name = "value_data", columnDefinition = "TEXT")
    private String valueData;

    @Column(name = "is_sensitive", nullable = false)
    private boolean sensitive;

    @Column(length = 512)
    private String description;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @PrePersist
    @PreUpdate
    protected void touch() {
        updatedAt = LocalDateTime.now();
    }

    public SystemConfig(String key, String valueData, boolean sensitive, String description) {
        this.key         = key;
        this.valueData   = valueData;
        this.sensitive   = sensitive;
        this.description = description;
    }
}
