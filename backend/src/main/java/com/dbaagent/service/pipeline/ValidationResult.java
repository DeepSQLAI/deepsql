package com.dbaagent.service.pipeline;

public record ValidationResult(
    boolean valid,
    String error,
    String explainPlan
) {
    public static ValidationResult valid(String explainPlan) {
        return new ValidationResult(true, null, explainPlan);
    }

    public static ValidationResult invalid(String error) {
        return new ValidationResult(false, error, null);
    }
}
