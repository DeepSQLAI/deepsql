package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TrainingJobStatus {
    public enum Status {
        IDLE,
        PENDING,
        RUNNING,
        COMPLETED,
        FAILED,
        CANCELLED,
        REJECTED
    }

    private String jobId;
    private String connectionId;
    private Status status;
    private Integer totalTables;
    private Integer processedTables;
    private Integer progressPercent;
    private String currentTable;
    private String message;
    private String error;
    private LocalDateTime startedAt;
    private LocalDateTime completedAt;
}
