package com.dbaagent.model;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "column_profile", indexes = {
    @Index(name = "idx_column_profile_connection", columnList = "connectionId"),
    @Index(name = "idx_column_profile_table", columnList = "connectionId,tableName"),
    @Index(name = "idx_column_profile_column", columnList = "connectionId,tableName,columnName")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ColumnProfile {
    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false)
    private String tableName;

    @Column(nullable = false)
    private String columnName;

    @Column
    private String dataType;

    @Column
    private Long totalRows;

    @Column
    private Long nullCount;

    @Column
    private Long distinctCount;

    @Column(columnDefinition = "TEXT")
    private String minValue;

    @Column(columnDefinition = "TEXT")
    private String maxValue;

    @Column(columnDefinition = "TEXT")
    private String sampleValue;

    // Data skew analysis (BRAIN-design.md Section 3.2)
    @Column(columnDefinition = "TEXT")
    private String topValues;  // JSON: [{"value": "foo", "count": 1234, "percentage": 12.34}, ...]

    @Column
    private Double skewCoefficient;  // 0.0 (uniform) to 1.0 (highly skewed)

    @Column
    private LocalDateTime profiledAt;

    @PrePersist
    protected void onCreate() {
        if (profiledAt == null) {
            profiledAt = LocalDateTime.now();
        }
    }
}
