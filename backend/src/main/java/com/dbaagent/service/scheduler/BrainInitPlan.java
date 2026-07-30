package com.dbaagent.service.scheduler;

import com.dbaagent.model.InitStage;

import java.util.List;

public record BrainInitPlan(
    BrainInitPlanMode mode,
    InitStage startedFromStage,
    boolean skipped,
    String reason,
    List<String> dirtySources
) {
    public static BrainInitPlan quickVerifySkip(String reason, List<String> dirtySources) {
        return new BrainInitPlan(
            BrainInitPlanMode.QUICK_VERIFY,
            null,
            true,
            reason,
            dirtySources == null ? List.of() : List.copyOf(dirtySources)
        );
    }

    public static BrainInitPlan resumeFailed(InitStage stage) {
        return new BrainInitPlan(
            BrainInitPlanMode.RESUME_FAILED,
            stage,
            false,
            "Resuming failed Brain init from " + stage,
            List.of("failedRun")
        );
    }

    public static BrainInitPlan refresh(InitStage stage, String reason, List<String> dirtySources) {
        return new BrainInitPlan(
            BrainInitPlanMode.FULL_OR_PARTIAL_REFRESH,
            stage,
            false,
            reason,
            dirtySources == null ? List.of() : List.copyOf(dirtySources)
        );
    }
}
