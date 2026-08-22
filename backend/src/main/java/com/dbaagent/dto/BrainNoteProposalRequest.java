package com.dbaagent.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class BrainNoteProposalRequest {
    private String connectionId;
    private String question;
    private String answer;
}
