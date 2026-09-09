package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class BrainNoteProposalResponse {
    private String scopeType;
    private String tableName;
    private String columnName;
    private String bubbleLabel;
    private String excerpt;
    private String proposedNoteText;
    /** NEW, MERGE, or SKIP (SKIP is omitted from the Agent UI). */
    private String action;
    private String existingNoteId;
    private String existingNoteText;
    private String overlapReason;
}
