package com.dbaagent.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ChatResponse {
    private String message;
    private String answer;
    private String sql;  // Optional: the SQL query that was executed
    private Object data; // Optional: any data results
    private boolean success;
    private String chatId; // The chat session this response belongs to

    /** Analysis plan extracted from the agent's first-pass response (brain-agent only). */
    private String plan;

    /** All SQL queries that were executed during this turn (brain-agent only). */
    private List<String> executedQueries;

    /** Tools that were used during an agentic workflow. */
    private List<String> toolsUsed;

    /** Confidence score for the returned answer when available. */
    private Double confidence;

    /** Execution mode used for the response such as fast-path, agentic, or legacy. */
    private String mode;

    /** Persisted agent run identifier for trace inspection. */
    private String agentRunId;

    /** Structured subtask outputs for multi-part agentic turns. */
    private List<ChatResultSet> resultSets;

    /** Unified run metadata for the chat turn. */
    private RunInfo run;

    /** Unified artifacts emitted by the run. */
    private Artifacts artifacts;

    /** UI visibility hints for compact/admin trace rendering. */
    private UiHints ui;

    public ChatResponse(String message, boolean success) {
        this.message = message;
        this.answer = message;
        this.success = success;
    }

    public void setMessage(String message) {
        this.message = message;
        this.answer = message;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class RunInfo {
        private String runId;
        private String status;
        private String threadMode;
        private String domain;
        private Long durationMs;
        private String stopReason;
        private boolean verified;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class Artifacts {
        private List<String> sql;
        private List<ChatResultSet> resultSets;
        private List<String> evidenceSummaries;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UiHints {
        private boolean adminTraceAvailable;
        private String defaultVisibility;
    }
}
