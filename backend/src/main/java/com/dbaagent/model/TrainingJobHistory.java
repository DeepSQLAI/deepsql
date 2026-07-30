package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(
    name = "training_job_history",
    indexes = {
        @Index(name = "idx_training_job_connection", columnList = "connection_id"),
        @Index(name = "idx_training_job_started_at", columnList = "started_at")
    }
)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingJobHistory {
    public enum JobType {
        SCHEMA
    }

    @Id
    @Column(name = "job_id", length = 36)
    private String jobId;

    @Column(name = "connection_id", nullable = false)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false)
    private JobType jobType;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private TrainingJobStatus.Status status;

    @Column(name = "message", columnDefinition = "TEXT")
    private String message;

    @Column(name = "error", columnDefinition = "TEXT")
    private String error;

    @Column(name = "total_tables")
    private Integer totalTables;

    @Column(name = "processed_tables")
    private Integer processedTables;

    @Column(name = "progress_percent")
    private Integer progressPercent;

    @Column(name = "embedding_count")
    private Integer embeddingCount;

    @Column(name = "azure_document_count")
    private Integer azureDocumentCount;

    @Column(name = "embedding_dimensions")
    private Integer embeddingDimensions;

    @Column(name = "table_timings", columnDefinition = "TEXT")
    private String tableTimings;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        updatedAt = createdAt;
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }
}
