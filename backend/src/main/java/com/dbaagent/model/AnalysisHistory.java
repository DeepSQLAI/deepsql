package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Entity class for storing EXPLAIN Plan analysis history
 * Persists analysis results to H2 database for cross-session access
 */
@Entity
@Table(name = "analysis_history", indexes = {
    @Index(name = "idx_analysis_history_connection_id", columnList = "connectionId"),
    @Index(name = "idx_analysis_history_created_at", columnList = "createdAt"),
    @Index(name = "idx_analysis_history_user_id", columnList = "userId")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AnalysisHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column
    private String userId;  // Optional: for multi-user scenarios

    @Column(nullable = false, columnDefinition = "TEXT")
    private String query;

    @Column(nullable = false)
    private Boolean useAnalyze;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String analysisData;  // JSON string of full analysis object

    @Column
    private Integer performanceScore;

    @Column
    private Integer issueCount;

    @Column(columnDefinition = "TEXT")
    private String tablesUsed;

    @Column(columnDefinition = "TEXT")
    private String columnsUsed;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = LocalDateTime.now();
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
