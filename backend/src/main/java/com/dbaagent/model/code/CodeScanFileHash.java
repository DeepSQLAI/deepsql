package com.dbaagent.model.code;

import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.io.Serializable;
import java.time.LocalDateTime;
import java.util.Objects;

/**
 * Per-file content hash recorded after each successful code scan, keyed by
 * (source_id, relative_path). Lets re-scans skip files whose sha256 hasn't
 * changed since the last successful job.
 */
@Entity
@Table(name = "code_scan_file_hash", indexes = {
    @Index(name = "idx_code_scan_file_hash_source", columnList = "sourceId,lastScannedAt")
})
@IdClass(CodeScanFileHash.PK.class)
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CodeScanFileHash {

    @Id
    @Column(nullable = false, length = 36)
    private String sourceId;

    @Id
    @Column(nullable = false, length = 500)
    private String relativePath;

    @Column(nullable = false, length = 64)
    private String sha256;

    @Column(length = 36)
    private String lastJobId;

    @Column(nullable = false)
    private LocalDateTime lastScannedAt;

    @PrePersist
    @PreUpdate
    void touch() {
        if (lastScannedAt == null) lastScannedAt = LocalDateTime.now();
    }

    /** Composite key (source_id, relative_path). */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class PK implements Serializable {
        private String sourceId;
        private String relativePath;

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof PK pk)) return false;
            return Objects.equals(sourceId, pk.sourceId) && Objects.equals(relativePath, pk.relativePath);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sourceId, relativePath);
        }
    }
}
