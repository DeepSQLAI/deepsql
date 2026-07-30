package com.dbaagent.model.brain;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "brain_score_snapshot", indexes = {
    @Index(name = "idx_brain_score_snapshot_connection", columnList = "connectionId"),
    @Index(name = "idx_brain_score_snapshot_created", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainScoreSnapshot {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private Integer overallScore;

    @Column(nullable = false)
    private Integer tableCount;

    @Column(nullable = false)
    private Integer columnCount;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
