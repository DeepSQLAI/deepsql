package com.dbaagent.service.agent;

import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class MetadataResultSynthesisTool implements AgentTool {

    private final MetadataAnswerSynthesisService metadataAnswerSynthesisService;

    public MetadataResultSynthesisTool(MetadataAnswerSynthesisService metadataAnswerSynthesisService) {
        this.metadataAnswerSynthesisService = metadataAnswerSynthesisService;
    }

    @Override
    public String name() {
        return "metadata_result_synthesis_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        VerifiedAnswer verifiedAnswer = metadataAnswerSynthesisService.synthesize(context);
        context.putMemory("metadataFinalAnswer", verifiedAnswer);
        if (verifiedAnswer.verificationReport() != null) {
            context.recordVerificationReport(verifiedAnswer.verificationReport());
        }

        return new AgentToolResult(
            new AgentObservation(
                "metadata_final_answer",
                verifiedAnswer.renderedMessage(),
                verifiedAnswer.observationData()
            ),
            null,
            verifiedAnswer.answerContract() != null ? verifiedAnswer.answerContract().executedSql() : null,
            verifiedAnswer.evidence() != null ? verifiedAnswer.evidence().confidence() : 0.9
        );
    }
}
