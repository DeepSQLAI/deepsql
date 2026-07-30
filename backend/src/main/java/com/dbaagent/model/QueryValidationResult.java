package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.ArrayList;
import java.util.List;

/**
 * Query validation result with safety checks
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class QueryValidationResult {
    private Boolean isSafe;
    private List<String> warnings;
    private List<String> errors;
    private String queryType;  // SELECT, UPDATE, DELETE, DDL
    private Boolean requiresConfirmation;
    private String recommendation;

    public static QueryValidationResult safe() {
        return QueryValidationResult.builder()
                .isSafe(true)
                .warnings(new ArrayList<>())
                .errors(new ArrayList<>())
                .requiresConfirmation(false)
                .build();
    }

    public static QueryValidationResult unsafe(String error) {
        QueryValidationResult result = new QueryValidationResult();
        result.setIsSafe(false);
        result.setErrors(List.of(error));
        result.setWarnings(new ArrayList<>());
        return result;
    }

    public void addWarning(String warning) {
        if (warnings == null) {
            warnings = new ArrayList<>();
        }
        warnings.add(warning);
    }

    public void addError(String error) {
        if (errors == null) {
            errors = new ArrayList<>();
        }
        errors.add(error);
        isSafe = false;
    }
}
