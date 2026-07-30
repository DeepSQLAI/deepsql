package com.dbaagent.service.brain.core;

import com.dbaagent.model.brain.BrainTask;
import com.dbaagent.model.brain.BrainTaskRequest;
import com.dbaagent.model.brain.BrainTaskResponse;
import com.dbaagent.repository.brain.BrainTaskRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrainTaskService {
    private static final TypeReference<List<String>> LIST_TYPE = new TypeReference<>() {};
    private final BrainTaskRepository brainTaskRepository;
    private final ObjectMapper objectMapper;

    public List<BrainTaskResponse> getTasks(String connectionId, String status) {
        List<BrainTask> tasks;
        if (status != null && !status.isBlank()) {
            BrainTask.Status resolvedStatus = resolveStatus(status);
            tasks = brainTaskRepository.findByConnectionIdAndStatusOrderByCreatedAtDesc(connectionId, resolvedStatus);
        } else {
            tasks = brainTaskRepository.findByConnectionIdOrderByCreatedAtDesc(connectionId);
        }
        return tasks.stream().map(this::toResponse).toList();
    }

    public BrainTaskResponse createTask(BrainTaskRequest request) {
        if (request == null || request.getConnectionId() == null || request.getConnectionId().isBlank()) {
            throw new IllegalArgumentException("connectionId is required");
        }
        BrainTask.TaskType taskType = resolveTaskType(request.getTaskType());
        String tableName = normalize(request.getTableName());
        if (tableName != null) {
            BrainTask existing = brainTaskRepository
                .findTopByConnectionIdAndTaskTypeAndStatusAndTableName(
                    request.getConnectionId(),
                    taskType,
                    BrainTask.Status.OPEN,
                    tableName
                )
                .orElse(null);
            if (existing != null) {
                return toResponse(existing);
            }
        }

        BrainTask task = BrainTask.builder()
            .connectionId(request.getConnectionId())
            .taskType(taskType)
            .status(BrainTask.Status.OPEN)
            .tableName(request.getTableName())
            .tableSchema(request.getTableSchema())
            .tableLabel(request.getTableLabel())
            .bcnfScore(request.getBcnfScore())
            .summary(buildSummary(taskType, request))
            .issuesJson(writeJson(request.getIssues()))
            .suggestionsJson(writeJson(request.getSuggestions()))
            .createdBy(request.getCreatedBy())
            .build();
        return toResponse(brainTaskRepository.save(task));
    }

    public BrainTaskResponse updateStatus(String taskId, String status) {
        BrainTask task = brainTaskRepository.findById(taskId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found"));
        task.setStatus(resolveStatus(status));
        return toResponse(brainTaskRepository.save(task));
    }

    public String getConnectionId(String taskId) {
        return brainTaskRepository.findById(taskId)
            .map(BrainTask::getConnectionId)
            .orElseThrow(() -> new IllegalArgumentException("Task not found"));
    }

    private BrainTask.TaskType resolveTaskType(String value) {
        if (value == null || value.isBlank()) {
            return BrainTask.TaskType.BCNF_REVIEW;
        }
        try {
            return BrainTask.TaskType.valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return BrainTask.TaskType.BCNF_REVIEW;
        }
    }

    private BrainTask.Status resolveStatus(String value) {
        if (value == null || value.isBlank()) {
            return BrainTask.Status.OPEN;
        }
        return BrainTask.Status.valueOf(value.trim().toUpperCase(Locale.ROOT));
    }

    private String buildSummary(BrainTask.TaskType taskType, BrainTaskRequest request) {
        if (taskType == BrainTask.TaskType.BCNF_REVIEW) {
            String label = request.getTableLabel() != null ? request.getTableLabel() : request.getTableName();
            if (request.getBcnfScore() != null) {
                return "BCNF review: " + label + " (score " + request.getBcnfScore() + ")";
            }
            return "BCNF review: " + label;
        }
        return "Brain task";
    }

    private String writeJson(List<String> values) {
        if (values == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(values);
        } catch (Exception e) {
            log.warn("Failed to serialize task list", e);
            return null;
        }
    }

    private List<String> parseJson(String json) {
        if (json == null || json.isBlank()) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(json, LIST_TYPE);
        } catch (Exception e) {
            log.warn("Failed to parse task list", e);
            return new ArrayList<>();
        }
    }

    private BrainTaskResponse toResponse(BrainTask task) {
        return BrainTaskResponse.builder()
            .id(task.getId())
            .connectionId(task.getConnectionId())
            .taskType(task.getTaskType() != null ? task.getTaskType().name() : null)
            .status(task.getStatus() != null ? task.getStatus().name() : null)
            .summary(task.getSummary())
            .tableName(task.getTableName())
            .tableSchema(task.getTableSchema())
            .tableLabel(task.getTableLabel())
            .bcnfScore(task.getBcnfScore())
            .issues(parseJson(task.getIssuesJson()))
            .suggestions(parseJson(task.getSuggestionsJson()))
            .createdBy(task.getCreatedBy())
            .createdAt(task.getCreatedAt())
            .updatedAt(task.getUpdatedAt())
            .build();
    }

    private String normalize(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        return value.trim();
    }
}
