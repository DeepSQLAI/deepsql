package com.dbaagent.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "installs_telemetry")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InstallTelemetryIdentity {

    @Id
    @Column(name = "id")
    private Integer id;

    @Column(name = "install_id", nullable = false, unique = true)
    private UUID installId;

    @Column(name = "install_secret", nullable = false)
    private byte[] installSecret;

    @Column(name = "install_token", nullable = false, length = 64)
    private String installToken;

    @Column(name = "company_name", nullable = false, length = 128)
    private String companyName;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    /**
     * When {@code install.bootstrapped} was successfully handed to the telemetry
     * pipeline for this install. NULL means the event has not been reported yet
     * (e.g. the identity row was created while the sink was NoOp during the
     * 2026-05-23..05-26 key-rollout window, or the row predates telemetry). The
     * bootstrap re-emits {@code install.bootstrapped} whenever this is NULL, so
     * the "new install" signal is not permanently lost to the persistent volume.
     */
    @Column(name = "bootstrap_reported_at")
    private OffsetDateTime bootstrapReportedAt;
}
