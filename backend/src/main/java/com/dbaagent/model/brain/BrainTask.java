package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "brain_tasks", indexes = {
    @Index(name = "idx_brain_tasks_connection", columnList = "connectionId"),
    @Index(name = "idx_brain_tasks_status", columnList = "status"),
    @Index(name = "idx_brain_tasks_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainTask {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private Status status;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String summary;

    @Column
    private String tableName;

    @Column
    private String tableSchema;

    @Column
    private String tableLabel;

    @Column
    private Integer bcnfScore;

    @Column(columnDefinition = "TEXT")
    private String issuesJson;

    @Column(columnDefinition = "TEXT")
    private String suggestionsJson;

    @Column
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime updatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    protected void onUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public enum TaskType {
        BCNF_REVIEW
    }

    public enum Status {
        OPEN,
        DONE
    }
}
