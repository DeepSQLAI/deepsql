package com.dbaagent.dto;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Data
public class AgentRunTraceResponse {
    private String id;
    private String chatId;
    private String connectionId;
    private String question;
    private String intent;
    private String goal;
    private String planSummary;
    private String status;
    private Double confidence;
    private String finalMessage;
    private String userMessageId;
    private String assistantMessageId;
    private LocalDateTime createdAt;
    private LocalDateTime completedAt;
    private List<Map<String, Object>> tasks;
    private List<StepDto> steps;

    @Data
    public static class StepDto {
        private String id;
        private Integer stepIndex;
        private String stepKey;
        private String taskId;
        private String title;
        private String toolName;
        private String stepKind;
        private String status;
        private Map<String, Object> params;
        private String executedSql;
        private List<String> executedQueries;
        private List<String> dependsOn;
        private List<Map<String, Object>> artifacts;
        private Double confidence;
        private ObservationDto observation;
        private LocalDateTime createdAt;
    }

    @Data
    public static class ObservationDto {
        private String id;
        private String type;
        private String summary;
        private Map<String, Object> data;
        private LocalDateTime createdAt;
    }
}
