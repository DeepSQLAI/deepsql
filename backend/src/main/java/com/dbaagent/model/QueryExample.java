package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "query_examples", indexes = {
    @Index(name = "idx_query_examples_connection_id", columnList = "connectionId"),
    @Index(name = "idx_query_examples_created_at", columnList = "createdAt")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryExample {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false, length = 1000)
    private String naturalLanguage;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String sql;

    @Column(nullable = false)
    private String dbType; // mysql or postgres

    @Column
    private Integer rowCount;

    @Column
    private Long executionTimeMs;

    @Column(nullable = false)
    private Boolean successful;

    @Column
    private String userId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column(columnDefinition = "TEXT")
    private String tablesUsed; // Comma-separated list of tables

    @Column(columnDefinition = "TEXT")
    private String columnsUsed; // Comma-separated list of table.column

    @Column
    private Double relevanceScore; // For similarity ranking

    @Column
    private Boolean verified; // false = auto-trained, true = user-confirmed via thumbs-up

    @Column
    private LocalDateTime verifiedAt;

    @Column
    private Boolean rejected; // true = user explicitly downvoted this SQL example

    @Column
    private LocalDateTime rejectedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (verified == null) {
            verified = false;
        }
        if (rejected == null) {
            rejected = false;
        }
        if (Boolean.TRUE.equals(rejected)) {
            verified = false;
            verifiedAt = null;
        }
    }
}
