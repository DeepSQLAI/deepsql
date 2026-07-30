package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.Map;

@Entity
@Table(name = "playbook_tools")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PlaybookTool {

    @Id
    private String id;

    @Column(nullable = false, unique = true, length = 100)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "db_type", length = 20)
    private String dbType; // 'mysql', 'postgres', 'all'

    @Column(length = 50)
    private String category; // 'monitoring', 'analysis', 'inspection'

    @Column(name = "is_readonly")
    private Boolean isReadonly = true;

    @Column(name = "requires_params")
    private Boolean requiresParams = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "param_schema", columnDefinition = "JSON")
    private Map<String, Object> paramSchema;

    @Column(name = "sql_template", columnDefinition = "TEXT")
    private String sqlTemplate;

    @Column(name = "created_at")
    private LocalDateTime createdAt;

    @PrePersist
    protected void onCreate() {
        if (id == null) {
            id = java.util.UUID.randomUUID().toString();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }
}
