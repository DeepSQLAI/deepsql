package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.List;

@Entity
@Table(name = "company_knowledge_entry", indexes = {
    @Index(name = "idx_company_knowledge_connection", columnList = "connectionId"),
    @Index(name = "idx_company_knowledge_type", columnList = "connectionId,entryType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompanyKnowledgeEntry {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40)
    private EntryType entryType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> linkedTables;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<String> linkedColumns;

    @Transient
    private String coverageStatus;

    @Transient
    private String joinCoverageStatus;

    @Transient
    private List<String> mentionedTables;

    @Transient
    private List<String> mentionedColumns;

    @Transient
    private List<String> unlinkedMentions;

    @Transient
    private List<String> invalidMentions;

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

    public enum EntryType {
        COMPANY_CONTEXT,
        WORKFLOW,
        BUSINESS_RULE,
        METRIC,
        GLOSSARY
    }
}
