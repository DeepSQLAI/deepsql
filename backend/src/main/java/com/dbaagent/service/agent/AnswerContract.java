package com.dbaagent.service.agent;

import java.util.List;

public record AnswerContract(
    String title,
    String summary,
    List<String> primaryFindings,
    List<String> supportingEvidence,
    String executedSql,
    List<String> verificationNotes,
    List<String> gapsOrCaveats,
    String followUpPrompt
) {
    public AnswerContract {
        primaryFindings = primaryFindings == null ? List.of() : List.copyOf(primaryFindings);
        supportingEvidence = supportingEvidence == null ? List.of() : List.copyOf(supportingEvidence);
        verificationNotes = verificationNotes == null ? List.of() : List.copyOf(verificationNotes);
        gapsOrCaveats = gapsOrCaveats == null ? List.of() : List.copyOf(gapsOrCaveats);
    }
}
