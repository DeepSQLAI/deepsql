package com.dbaagent.service.agent;

import java.util.LinkedHashMap;
import java.util.Map;

public record VerifiedAnswer(
    PromptIntent promptIntent,
    EvidenceBundle evidence,
    VerificationReport verificationReport,
    AnswerContract answerContract,
    String toolName,
    String stepTitle,
    String stepKind
) {
    public String renderedMessage() {
        if (answerContract == null) {
            return "";
        }
        return answerContract.summary() != null ? answerContract.summary() : "";
    }

    public Map<String, Object> observationData() {
        Map<String, Object> data = new LinkedHashMap<>();
        if (promptIntent != null) {
            data.put("promptIntent", Map.of(
                "domain", promptIntent.domain().name(),
                "taskType", promptIntent.taskType().name(),
                "subjectTypes", promptIntent.subjectTypes().stream().map(Enum::name).toList(),
                "requestedOutput", promptIntent.requestedOutput().name(),
                "requiresSql", promptIntent.requiresSql(),
                "requiresLiveMetadata", promptIntent.requiresLiveMetadata(),
                "requiresCachedMetadata", promptIntent.requiresCachedMetadata(),
                "requiresDocs", promptIntent.requiresDocs(),
                "constraints", promptIntent.constraints()
            ));
        }
        if (evidence != null) {
            data.put("evidence", Map.of(
                "intentDomain", evidence.intentDomain().name(),
                "evidenceKind", evidence.evidenceKind(),
                "source", evidence.source().name(),
                "answerType", evidence.answerType(),
                "coverage", evidence.coverage(),
                "confidence", evidence.confidence(),
                "freshness", evidence.freshness() == null ? "" : evidence.freshness(),
                "supportingObjectNames", evidence.supportingObjectNames(),
                "sufficient", evidence.sufficient(),
                "insufficiencyMessage", evidence.insufficiencyMessage() == null ? "" : evidence.insufficiencyMessage()
            ));
        }
        if (verificationReport != null) {
            data.put("verification", Map.of(
                "passed", verificationReport.passed(),
                "verifiedInsufficiency", verificationReport.verifiedInsufficiency(),
                "failureReason", verificationReport.failureReason() == null ? "" : verificationReport.failureReason(),
                "intentMatchScore", verificationReport.intentMatchScore(),
                "coverageScore", verificationReport.coverageScore(),
                "sourceStrength", verificationReport.sourceStrength().name(),
                "recommendedFallback", verificationReport.recommendedFallback().name(),
                "notes", verificationReport.notes()
            ));
        }
        if (answerContract != null) {
            data.put("answerContract", Map.of(
                "title", answerContract.title() == null ? "" : answerContract.title(),
                "summary", answerContract.summary() == null ? "" : answerContract.summary(),
                "primaryFindings", answerContract.primaryFindings(),
                "supportingEvidence", answerContract.supportingEvidence(),
                "executedSql", answerContract.executedSql() == null ? "" : answerContract.executedSql(),
                "verificationNotes", answerContract.verificationNotes(),
                "gapsOrCaveats", answerContract.gapsOrCaveats(),
                "followUpPrompt", answerContract.followUpPrompt() == null ? "" : answerContract.followUpPrompt()
            ));
        }
        return data;
    }
}
