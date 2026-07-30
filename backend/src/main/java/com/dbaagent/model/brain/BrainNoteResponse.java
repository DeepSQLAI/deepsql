package com.dbaagent.model.brain;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainNoteResponse {
    private String id;
    private String connectionId;
    private String scopeType;
    private String tableName;
    private String columnName;
    private String noteText;
    private String createdBy;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
    private Integer confidenceScore;
    private Boolean stale;
    private String staleReason;
    private String source;  // "USER", "AI_GENERATED", "CSV_IMPORT", "CODE_DERIVED"

    /**
     * Source-file provenance for CODE_DERIVED notes — list of
     * {path, startLine, endLine, rationale}. Null/empty for USER and
     * AI_GENERATED notes (which have no code lineage).
     */
    private List<Map<String, Object>> sourceFiles;
}
