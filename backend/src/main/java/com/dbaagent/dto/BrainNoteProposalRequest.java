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
    /** Previous assistant answer. Required for a proposal — clean first turns stay quiet. */
    private String priorAnswer;
}
