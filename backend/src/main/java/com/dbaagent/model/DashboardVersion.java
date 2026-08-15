package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "dashboard_versions", indexes = {
    @Index(name = "idx_dashboard_versions_dashboard_id", columnList = "dashboardId")
})
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DashboardVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false)
    private UUID dashboardId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String dashboardConfig;

    @Column(length = 255)
    private String name;

    // What produced this snapshot: AGENT_BUILD (agent finished a turn) or MANUAL_EDIT
    // (Source tab Apply) — shown in the History list so a restore point reads as
    // "before this edit" rather than an anonymous timestamp.
    @Column(nullable = false, length = 32)
    private String trigger;

    @CreationTimestamp
    @Column(nullable = false, updatable = false)
    private LocalDateTime createdAt;
}
