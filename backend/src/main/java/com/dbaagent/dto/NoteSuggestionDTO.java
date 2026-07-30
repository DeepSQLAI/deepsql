package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * DTO for suggested notes based on Brain analysis.
 * Recommends which tables/columns need documentation.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class NoteSuggestionDTO {

    private String scopeType;        // TABLE or COLUMN
    private String tableName;
    private String columnName;       // null for table suggestions
    private String priority;         // P0, P1, P2
    private String reason;           // Human-readable reason
    private List<String> indicators; // Data points supporting suggestion
    private Double score;            // 0-100 suggestion score
    private String suggestedPrompt;  // Template/hint for the note content

    /**
     * Response wrapper for suggested notes endpoint
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Response {
        private List<NoteSuggestionDTO> suggestions;
        private int totalCount;
        private String generatedAt;
    }
}
