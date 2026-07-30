package com.dbaagent.model.code;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Entity
@Table(name = "code_scan_source", indexes = {
    @Index(name = "idx_code_scan_source_connection", columnList = "connectionId,active")
})
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeScanSource {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private String id;

    @Column(nullable = false)
    private String connectionId;

    @Column(nullable = false, length = 200)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(length = 64)
    private String archiveSha256;

    @Column
    private Long totalBytes;

    @Column
    private Integer fileCount;

    @Column(length = 40)
    private String scheduleCron;

    @Column(nullable = false)
    @Builder.Default
    private Boolean active = Boolean.TRUE;

    @Column(length = 120)
    private String createdBy;

    @Column(nullable = false)
    private LocalDateTime createdAt;

    @Column
    private LocalDateTime lastScannedAt;

    /** Free-text focus the user wants the scanner to prioritise. */
    @Column(columnDefinition = "TEXT")
    private String focusText;

    /** sha256(focusText + ambiguity-derived focus) — lets the UI detect "stale focus". */
    @Column(length = 64)
    private String focusHash;

    @Column
    private LocalDateTime focusUpdatedAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (active == null) {
            active = Boolean.TRUE;
        }
    }

    public enum Kind {
        UPLOAD,
        GIT
    }
}
