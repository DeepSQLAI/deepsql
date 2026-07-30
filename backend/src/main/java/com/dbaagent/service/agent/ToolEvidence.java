package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.springframework.lang.Nullable;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public record ToolEvidence(
    String stepId,
    String toolName,
    String observationType,
    String summary,
    String status,
    List<String> executedQueries,
    @Nullable QueryResult primaryResult,
    Map<String, Object> payload,
    double confidence,
    boolean material,
    @Nullable VerificationReport verificationReport
) {
    public ToolEvidence {
        executedQueries = executedQueries == null ? List.of() : List.copyOf(executedQueries);
        payload = payload == null ? Map.of() : Map.copyOf(new LinkedHashMap<>(payload));
    }

    public static ToolEvidence from(
        AgentPlanStep step,
        AgentToolResult result,
        @Nullable VerificationReport verificationReport
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (result != null && result.observation() != null && result.observation().data() != null) {
            payload.putAll(result.observation().data());
        }
        String status = deriveStatus(payload);
        boolean material = result != null
            && ((result.queryResult() != null)
                || !result.allExecutedQueries().isEmpty()
                || verificationReport != null
                || payload.containsKey("evidenceBundle")
                || payload.containsKey("verificationReport")
                || payload.containsKey("accepted")
                || payload.containsKey("sufficient"));
        return new ToolEvidence(
            step.id(),
            step.toolName(),
            result != null && result.observation() != null ? result.observation().type() : "",
            result != null && result.observation() != null ? result.observation().summary() : "No observation recorded",
            status,
            result == null ? List.of() : result.allExecutedQueries(),
            result == null ? null : result.queryResult(),
            payload,
            result == null ? 0.0 : result.confidence(),
            material,
            verificationReport
        );
    }

    private static String deriveStatus(Map<String, Object> payload) {
        if (payload == null || payload.isEmpty()) {
            return "COMPLETED";
        }
        Object status = payload.get("status");
        if (status != null) {
            return status.toString();
        }
        if (Boolean.TRUE.equals(payload.get("clarification"))) {
            return "CLARIFICATION";
        }
        if (Boolean.TRUE.equals(payload.get("accepted"))) {
            return "COMPLETED";
        }
        return "COMPLETED";
    }

    public String compactSummary() {
        StringBuilder sb = new StringBuilder();
        sb.append(toolName).append(": ").append(summary);
        if (verificationReport != null) {
            sb.append(" [verified=")
                .append(verificationReport.passed() || verificationReport.verifiedInsufficiency())
                .append(", intent=")
                .append(String.format("%.2f", verificationReport.intentMatchScore()))
                .append(", coverage=")
                .append(String.format("%.2f", verificationReport.coverageScore()))
                .append("]");
        }
        return sb.toString();
    }
}
