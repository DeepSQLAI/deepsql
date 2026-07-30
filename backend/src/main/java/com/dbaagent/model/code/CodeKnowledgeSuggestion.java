package com.dbaagent.model.code;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "code_knowledge_suggestion", indexes = {
    @Index(name = "idx_code_suggestion_connection_status", columnList = "connectionId,status,createdAt"),
    @Index(name = "idx_code_suggestion_job", columnList = "jobId"),
    @Index(name = "idx_code_suggestion_target", columnList = "connectionId,targetKind,targetObject")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeKnowledgeSuggestion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String jobId;

    @Column(nullable = false)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TargetKind targetKind;

    @Column(length = 255)
    private String targetObject;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> payload;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> linkedTables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> linkedColumns;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<Map<String, Object>> sourceFiles;

    @Column(nullable = false)
    @Builder.Default
    private Double confidence = 0.0;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    @Builder.Default
    private Status status = Status.PENDING;

    @Column(columnDefinition = "TEXT")
    private String decisionNote;

    @Column(length = 120)
    private String decidedBy;

    @Column
    private LocalDateTime decidedAt;

    @Column(length = 36)
    private String appliedDocId;

    @Column(length = 36)
    private String appliedEntryId;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (status == null) {
            status = Status.PENDING;
        }
    }

    public enum TargetKind {
        SCHEMA_DOC,
        KNOWLEDGE_ENTRY
    }

    public enum Status {
        PENDING,
        APPROVED,
        REJECTED,
        SUPERSEDED
    }
}
