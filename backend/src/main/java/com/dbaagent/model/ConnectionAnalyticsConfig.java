package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Per-connection configuration for slow-query analytics.
 *
 * Each connection identifies a customer/tenant by a single consistent column
 * ({@code tenantColumn}, e.g. {@code customer_id}). The optional lookup fields let
 * DeepSQL resolve a tenant id to a human-readable name by running a cheap
 * {@code SELECT <nameCol> FROM <table> WHERE <idCol> = ?} against the target
 * database.
 */
@Entity
@Table(name = "connection_analytics_config")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ConnectionAnalyticsConfig {

    /** Primary key — the connection id this config belongs to. */
    @Id
    @Column(name = "connection_id")
    private String connectionId;

    /** The column that identifies a customer/tenant, e.g. {@code customer_id}. */
    @Column(name = "tenant_column", length = 128)
    private String tenantColumn;

    /** Table to resolve a tenant id to a name, e.g. {@code customers}. */
    @Column(name = "customer_lookup_table", length = 256)
    private String customerLookupTable;

    /** Id column in the lookup table, e.g. {@code id}. */
    @Column(name = "customer_lookup_id_col", length = 128)
    private String customerLookupIdCol;

    /** Name column in the lookup table, e.g. {@code name}. */
    @Column(name = "customer_lookup_name_col", length = 256)
    private String customerLookupNameCol;

    /** When false, the daily slow-query analysis job skips this connection. */
    @Column(name = "daily_analysis_enabled", nullable = false)
    private boolean dailyAnalysisEnabled;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    /**
     * Not persisted — set to true when the values returned to the UI were
     * auto-detected from the schema rather than read from a saved row, so the
     * panel can show an "auto-detected" hint instead of an empty form.
     */
    @Transient
    private boolean autoDetected;

    /** True when enough is configured to resolve tenant id → customer name. */
    public boolean hasNameLookup() {
        return customerLookupTable != null && !customerLookupTable.isBlank()
            && customerLookupIdCol != null && !customerLookupIdCol.isBlank()
            && customerLookupNameCol != null && !customerLookupNameCol.isBlank();
    }

    /** True when a tenant column is configured, so customer attribution can run. */
    public boolean hasTenantColumn() {
        return tenantColumn != null && !tenantColumn.isBlank();
    }

    @PrePersist
    void prePersist() {
        LocalDateTime now = LocalDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
