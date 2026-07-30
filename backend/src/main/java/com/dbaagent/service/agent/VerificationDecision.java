package com.dbaagent.service.agent;

import org.springframework.lang.Nullable;

public record VerificationDecision(
    boolean passed,
    boolean acceptedInsufficiency,
    boolean readyForSynthesis,
    boolean shouldClarify,
    boolean shouldContinue,
    double confidence,
    String reason,
    String criticNotes,
    @Nullable VerificationReport deterministicReport
) {
    public VerificationDecision {
        reason = reason == null ? "" : reason;
        criticNotes = criticNotes == null ? "" : criticNotes;
    }

    public static VerificationDecision continueLoop(String reason) {
        return new VerificationDecision(false, false, false, false, true, 0.45, reason, "", null);
    }
}
