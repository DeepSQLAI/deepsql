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
import java.util.Map;

@Entity
@Table(name = "schema_documentation", indexes = {
    @Index(name = "idx_connection_doc", columnList = "connectionId"),
    @Index(name = "idx_object_type", columnList = "objectType")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SchemaDocumentation {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private DocumentationType objectType; // TABLE, COLUMN, BUSINESS_TERM

    @Column(nullable = false)
    private String objectName; // e.g., "USER_BOOKINGS" or "order_date"

    @Column
    private String parentObject; // For columns, the table name

    @Column(nullable = false, columnDefinition = "TEXT")
    private String description;

    @Column(columnDefinition = "TEXT")
    private String businessTerms; // Comma-separated aliases/synonyms

    @Column(columnDefinition = "TEXT")
    private String examples; // Example values or usage

    @Column(name = "source", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private DocumentationSource source = DocumentationSource.USER;

    @Column(name = "confidence")
    private Double confidence;

    /**
     * Provenance for CODE_DERIVED rows: list of {path, startLine, endLine, rationale}
     * carried over from the approved {@code code_knowledge_suggestion}. Lets the UI
     * answer "where did this come from?" and lets future drift code spot orphans
     * when the source-of-truth file is gone.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "source_files", columnDefinition = "jsonb")
    private List<Map<String, Object>> sourceFiles;

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

    public enum DocumentationType {
        TABLE,
        COLUMN,
        BUSINESS_TERM
    }
}
