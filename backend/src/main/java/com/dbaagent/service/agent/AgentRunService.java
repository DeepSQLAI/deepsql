package com.dbaagent.service.agent;

import com.dbaagent.dto.AgentRunTraceResponse;
import com.dbaagent.model.AgentObservationEntity;
import com.dbaagent.model.AgentRun;
import com.dbaagent.model.AgentRunStep;
import com.dbaagent.repository.AgentObservationRepository;
import com.dbaagent.repository.AgentRunRepository;
import com.dbaagent.repository.AgentRunStepRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class AgentRunService {

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_OF_MAPS_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};

    private final AgentRunRepository agentRunRepository;
    private final AgentRunStepRepository agentRunStepRepository;
    private final AgentObservationRepository agentObservationRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public AgentRun startRun(String connectionId, String chatId, String question, AgentPlan plan) {
        AgentRun run = new AgentRun();
        run.setChatId(chatId);
        run.setConnectionId(connectionId);
        run.setQuestion(question);
        run.setIntent(plan.intent().name());
        run.setGoal(plan.goal());
        run.setPlanSummary(plan.summarize());
        run.setPlanTasksJson(writeJson(plan.tasks()));
        run.setStatus("RUNNING");
        return agentRunRepository.save(run);
    }

    @Transactional
    public void recordStep(String runId, int stepIndex, AgentPlanStep step, AgentToolResult result) {
        AgentRunStep persistedStep = new AgentRunStep();
        persistedStep.setRunId(runId);
        persistedStep.setStepIndex(stepIndex);
        persistedStep.setStepKey(step.id());
        persistedStep.setTaskId(step.taskId());
        persistedStep.setTitle(step.title());
        persistedStep.setToolName(step.toolName());
        persistedStep.setStepKind(step.stepKind());
        persistedStep.setStatus("COMPLETED");
        persistedStep.setParamsJson(writeJson(step.params()));
        persistedStep.setDependsOnJson(writeJson(step.dependsOn()));
        persistedStep.setExecutedSql(result.executedSql());
        persistedStep.setExecutedSqlJson(writeJson(result.allExecutedQueries()));
        persistedStep.setArtifactsJson(writeJson(result.artifacts()));
        persistedStep.setConfidence(result.confidence());
        persistedStep = agentRunStepRepository.save(persistedStep);

        if (result.observation() != null) {
            AgentObservationEntity observation = new AgentObservationEntity();
            observation.setRunId(runId);
            observation.setStepId(persistedStep.getId());
            observation.setObservationType(result.observation().type());
            observation.setSummary(result.observation().summary());
            observation.setDataJson(writeJson(result.observation().data()));
            agentObservationRepository.save(observation);
        }
    }

    @Transactional
    public void completeRun(String runId, AgentExecutionResult result) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus("COMPLETED");
            run.setConfidence(result.confidence());
            run.setFinalMessage(result.message());
            run.setCompletedAt(LocalDateTime.now());
            agentRunRepository.save(run);
        });
    }

    @Transactional
    public void failRun(String runId, Exception error) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.setStatus("FAILED");
            run.setFinalMessage(error != null ? error.getMessage() : "Unknown agent run failure");
            run.setCompletedAt(LocalDateTime.now());
            agentRunRepository.save(run);
        });
    }

    @Transactional
    public void attachChatArtifacts(String runId, String chatId, String userMessageId, String assistantMessageId) {
        agentRunRepository.findById(runId).ifPresent(run -> {
            run.setChatId(chatId);
            run.setUserMessageId(userMessageId);
            run.setAssistantMessageId(assistantMessageId);
            agentRunRepository.save(run);
        });
    }

    @Transactional(readOnly = true)
    public Optional<AgentRunTraceResponse> getTrace(String runId) {
        return agentRunRepository.findById(runId).map(this::toTraceResponse);
    }

    @Transactional(readOnly = true)
    public Optional<AgentRun> getRun(String runId) {
        return agentRunRepository.findById(runId);
    }

    @Transactional(readOnly = true)
    public List<AgentRun> getRunsForChat(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return List.of();
        }
        return agentRunRepository.findByChatIdOrderByCreatedAtAsc(chatId);
    }

    @Transactional(readOnly = true)
    public Optional<AgentRun> getLatestRunForChat(String chatId) {
        if (chatId == null || chatId.isBlank()) {
            return Optional.empty();
        }
        return agentRunRepository.findTopByChatIdOrderByCreatedAtDesc(chatId);
    }

    private AgentRunTraceResponse toTraceResponse(AgentRun run) {
        List<AgentRunStep> steps = agentRunStepRepository.findByRunIdOrderByStepIndexAsc(run.getId());
        Map<String, AgentObservationEntity> observationByStepId = agentObservationRepository
            .findByRunIdOrderByCreatedAtAsc(run.getId())
            .stream()
            .filter(observation -> observation.getStepId() != null)
            .collect(Collectors.toMap(AgentObservationEntity::getStepId, Function.identity(), (left, right) -> left, LinkedHashMap::new));

        AgentRunTraceResponse response = new AgentRunTraceResponse();
        response.setId(run.getId());
        response.setChatId(run.getChatId());
        response.setConnectionId(run.getConnectionId());
        response.setQuestion(run.getQuestion());
        response.setIntent(run.getIntent());
        response.setGoal(run.getGoal());
        response.setPlanSummary(run.getPlanSummary());
        response.setStatus(run.getStatus());
        response.setConfidence(run.getConfidence());
        response.setFinalMessage(run.getFinalMessage());
        response.setUserMessageId(run.getUserMessageId());
        response.setAssistantMessageId(run.getAssistantMessageId());
        response.setCreatedAt(run.getCreatedAt());
        response.setCompletedAt(run.getCompletedAt());
        response.setTasks(readJsonList(run.getPlanTasksJson()));
        response.setSteps(steps.stream().map(step -> toStepDto(step, observationByStepId.get(step.getId()))).toList());
        return response;
    }

    private AgentRunTraceResponse.StepDto toStepDto(AgentRunStep step, AgentObservationEntity observation) {
        AgentRunTraceResponse.StepDto dto = new AgentRunTraceResponse.StepDto();
        dto.setId(step.getId());
        dto.setStepIndex(step.getStepIndex());
        dto.setStepKey(step.getStepKey());
        dto.setTaskId(step.getTaskId());
        dto.setTitle(step.getTitle());
        dto.setToolName(step.getToolName());
        dto.setStepKind(step.getStepKind());
        dto.setStatus(step.getStatus());
        dto.setParams(readJsonMap(step.getParamsJson()));
        dto.setExecutedSql(step.getExecutedSql());
        dto.setExecutedQueries(readJsonStringList(step.getExecutedSqlJson()));
        dto.setDependsOn(readJsonStringList(step.getDependsOnJson()));
        dto.setArtifacts(readJsonList(step.getArtifactsJson()));
        dto.setConfidence(step.getConfidence());
        dto.setCreatedAt(step.getCreatedAt());
        if (observation != null) {
            AgentRunTraceResponse.ObservationDto observationDto = new AgentRunTraceResponse.ObservationDto();
            observationDto.setId(observation.getId());
            observationDto.setType(observation.getObservationType());
            observationDto.setSummary(observation.getSummary());
            observationDto.setData(readJsonMap(observation.getDataJson()));
            observationDto.setCreatedAt(observation.getCreatedAt());
            dto.setObservation(observationDto);
        }
        return dto;
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            log.warn("Failed to serialize agent trace payload", e);
            return null;
        }
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize agent trace payload", e);
            return Map.of("raw", json);
        }
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LIST_OF_MAPS_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize agent trace list payload", e);
            return List.of(Map.of("raw", json));
        }
    }

    private List<String> readJsonStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to deserialize agent trace string list payload", e);
            return List.of(json);
        }
    }
}
