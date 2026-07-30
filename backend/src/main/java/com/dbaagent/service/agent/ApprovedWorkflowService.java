package com.dbaagent.service.agent;

import com.dbaagent.dto.AgentRunTraceResponse;
import com.dbaagent.model.ApprovedAgentWorkflow;
import com.dbaagent.model.ChatTurnContext;
import com.dbaagent.repository.ApprovedAgentWorkflowRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class ApprovedWorkflowService {

    private static final TypeReference<List<Map<String, Object>>> STEP_PARAMS_TYPE = new TypeReference<>() {};
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9]+");
    private static final Pattern FOLLOW_UP_APPROVAL_PATTERN = Pattern.compile(
        "(?i)\\b(it|its|that|those|them|these|same|same one|same table|same metric|same thing|do it|do this|use that|use those|which one|what about|how about|and for|what if|that table|that column|that query|those columns|those tables|those keys|one of those|one of them|instead|use .* instead|don't|dont|do not)\\b"
    );
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "to", "for", "of", "in", "on", "this", "that",
        "these", "those", "is", "are", "was", "were", "be", "been", "it", "its", "by",
        "with", "from", "give", "show", "get", "basically", "such", "they", "them", "their",
        "what", "which", "can", "you", "we", "our", "about", "recently", "so", "far", "ago",
        "me", "find", "list", "fetch", "retrieve", "return", "tell"
    );

    private final ApprovedAgentWorkflowRepository approvedAgentWorkflowRepository;
    private final ChatTurnContextRepository chatTurnContextRepository;
    private final AgentRunService agentRunService;
    private final ObjectMapper objectMapper;

    @Transactional
    public Optional<ApprovedAgentWorkflow> approveRun(String connectionId, String agentRunId) {
        if (connectionId == null || connectionId.isBlank() || agentRunId == null || agentRunId.isBlank()) {
            return Optional.empty();
        }

        Optional<AgentRunTraceResponse> traceOptional = agentRunService.getTrace(agentRunId);
        if (traceOptional.isEmpty()) {
            return Optional.empty();
        }

        AgentRunTraceResponse trace = traceOptional.get();
        if (!connectionId.equals(trace.getConnectionId()) || trace.getIntent() == null || trace.getIntent().isBlank()) {
            return Optional.empty();
        }

        String normalizedQuestion = normalizeQuestion(trace.getQuestion());
        if (normalizedQuestion.isBlank()) {
            return Optional.empty();
        }

        List<String> tools = trace.getSteps() == null
            ? List.of()
            : trace.getSteps().stream().map(AgentRunTraceResponse.StepDto::getToolName).filter(tool -> tool != null && !tool.isBlank()).toList();

        Optional<ChatTurnContext> approvalContext = resolveApprovalContext(trace);
        if (approvalContext.isPresent()) {
            ApprovedAgentWorkflow saved = persistApprovedWorkflow(
                connectionId,
                trace,
                approvalContext.get().getCurrentQuestion(),
                tools,
                agentRunId,
                approvalContext.get()
            );
            return Optional.of(saved);
        }

        List<String> approvalQuestions = resolveApprovalQuestions(trace, agentRunId);
        ApprovedAgentWorkflow primarySaved = null;

        for (String approvalQuestion : approvalQuestions) {
            ApprovedAgentWorkflow saved = persistApprovedWorkflow(
                connectionId,
                trace,
                approvalQuestion,
                tools,
                agentRunId,
                null
            );
            if (primarySaved == null) {
                primarySaved = saved;
            }
        }

        return Optional.ofNullable(primarySaved);
    }

    @Transactional(readOnly = true)
    public Optional<ApprovedWorkflowMatch> findBestMatch(String connectionId, AgentIntent intent, String question) {
        if (connectionId == null || connectionId.isBlank() || intent == null || intent == AgentIntent.NONE) {
            return Optional.empty();
        }
        String normalizedQuestion = normalizeQuestion(question);
        if (normalizedQuestion.isBlank()) {
            return Optional.empty();
        }

        Set<String> targetTokens = tokenize(normalizedQuestion);
        if (targetTokens.isEmpty()) {
            return Optional.empty();
        }

        return approvedAgentWorkflowRepository.findByConnectionIdAndIntentOrderByLastApprovedAtDesc(connectionId, intent.name())
            .stream()
            .map(workflow -> new ApprovedWorkflowMatch(workflow, similarity(targetTokens, tokenize(workflow.getNormalizedQuestion()))))
            .filter(match -> match.similarityScore() >= 0.42d)
            .max(Comparator.comparingDouble(ApprovedWorkflowMatch::similarityScore));
    }

    public AgentPlan applyApprovedWorkflow(AgentPlan currentPlan, ApprovedWorkflowMatch match) {
        if (currentPlan == null || match == null || match.workflow() == null) {
            return currentPlan;
        }

        List<Map<String, Object>> savedStepParams = readStepParams(match.workflow().getStepParamsJson());
        if (savedStepParams.isEmpty()) {
            return currentPlan;
        }

        Map<String, Map<String, Object>> savedByKey = new HashMap<>();
        for (Map<String, Object> step : savedStepParams) {
            String stepKey = stringValue(step.get("stepKey"));
            String toolName = stringValue(step.get("toolName"));
            @SuppressWarnings("unchecked")
            Map<String, Object> params = step.get("params") instanceof Map<?, ?> raw ? (Map<String, Object>) raw : Map.of();
            if (stepKey != null && !stepKey.isBlank()) {
                savedByKey.put(stepKey, params);
            } else if (toolName != null && !toolName.isBlank()) {
                savedByKey.put(toolName, params);
            }
        }

        List<AgentPlanStep> mergedSteps = new ArrayList<>();
        for (AgentPlanStep step : currentPlan.steps()) {
            Map<String, Object> currentParams = step.params() == null ? Map.of() : step.params();
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> savedParams = savedByKey.getOrDefault(step.id(), savedByKey.get(step.toolName()));
            if (savedParams != null) {
                merged.putAll(savedParams);
            }
            merged.putAll(currentParams);
            mergedSteps.add(new AgentPlanStep(
                step.id(),
                step.title(),
                step.toolName(),
                Map.copyOf(merged),
                step.taskId(),
                step.dependsOn(),
                step.stepKind()
            ));
        }

        return new AgentPlan(currentPlan.intent(), currentPlan.goal(), currentPlan.tasks(), mergedSteps);
    }

    public String workflowHint(ApprovedWorkflowMatch match) {
        if (match == null || match.workflow() == null) {
            return null;
        }
        int approvals = match.workflow().getHelpfulCount() != null ? match.workflow().getHelpfulCount() : 0;
        return "Using a previously approved workflow (" + approvals + " helpful vote" + (approvals == 1 ? "" : "s") +
            ", similarity " + Math.round(match.similarityScore() * 100) + "%)";
    }

    private List<String> resolveApprovalQuestions(AgentRunTraceResponse trace, String agentRunId) {
        String currentQuestion = trace.getQuestion();
        if (currentQuestion == null || currentQuestion.isBlank()) {
            return List.of();
        }
        if (!looksLikeFollowUpApproval(currentQuestion) || trace.getChatId() == null || trace.getChatId().isBlank()) {
            return List.of(currentQuestion);
        }

        LinkedHashSet<String> candidates = new LinkedHashSet<>();
        List<com.dbaagent.model.AgentRun> chatRuns = agentRunService.getRunsForChat(trace.getChatId());
        int currentIndex = -1;
        for (int i = 0; i < chatRuns.size(); i++) {
            if (agentRunId.equals(chatRuns.get(i).getId())) {
                currentIndex = i;
                break;
            }
        }

        if (currentIndex > 0) {
            for (int i = currentIndex - 1; i >= 0; i--) {
                com.dbaagent.model.AgentRun candidate = chatRuns.get(i);
                if (candidate == null || candidate.getQuestion() == null || candidate.getQuestion().isBlank()) {
                    continue;
                }
                if (!trace.getIntent().equalsIgnoreCase(candidate.getIntent())) {
                    continue;
                }
                if (looksLikeFollowUpApproval(candidate.getQuestion())) {
                    continue;
                }
                candidates.add(candidate.getQuestion());
                break;
            }
        }

        if (candidates.isEmpty()) {
            candidates.add(currentQuestion);
        }
        return List.copyOf(candidates);
    }

    private ApprovedAgentWorkflow persistApprovedWorkflow(
            String connectionId,
            AgentRunTraceResponse trace,
            String approvalQuestion,
            List<String> tools,
            String agentRunId,
            ChatTurnContext approvalContext) {
        String anchorQuestion = approvalContext != null && approvalContext.getAnchorQuestion() != null
            && !approvalContext.getAnchorQuestion().isBlank()
            ? approvalContext.getAnchorQuestion()
            : approvalQuestion;
        String chainSummary = approvalContext != null ? approvalContext.getChainSummary() : null;
        String resolvedContextJson = approvalContext != null ? approvalContext.getResolvedContextJson() : null;
        String matchText = buildWorkflowMatchText(anchorQuestion, chainSummary, resolvedContextJson, approvalQuestion);
        String normalizedQuestion = normalizeQuestion(matchText);
        String signature = questionSignature(normalizedQuestion);
        List<Map<String, Object>> stepParams = buildStepParams(trace, anchorQuestion, approvalContext);

        ApprovedAgentWorkflow workflow = approvedAgentWorkflowRepository
            .findByConnectionIdAndIntentAndQuestionSignature(connectionId, trace.getIntent(), signature)
            .orElseGet(ApprovedAgentWorkflow::new);

        boolean isNew = workflow.getId() == null;
        workflow.setConnectionId(connectionId);
        workflow.setIntent(trace.getIntent());
        workflow.setExampleQuestion(anchorQuestion);
        workflow.setNormalizedQuestion(normalizedQuestion);
        workflow.setQuestionSignature(signature);
        workflow.setSourceContextId(approvalContext != null ? approvalContext.getId() : null);
        workflow.setAnchorQuestion(anchorQuestion);
        workflow.setChainSummary(chainSummary);
        workflow.setResolvedContextJson(resolvedContextJson);
        workflow.setGoal(trace.getGoal());
        workflow.setPlanSummary(trace.getPlanSummary());
        workflow.setToolsJson(writeJson(tools));
        workflow.setStepParamsJson(writeJson(stepParams));
        workflow.setLatestAgentRunId(agentRunId);
        workflow.setLastApprovedAt(LocalDateTime.now());
        if (isNew) {
            workflow.setHelpfulCount(1);
            workflow.setAverageConfidence(trace.getConfidence());
        } else {
            int helpfulCount = workflow.getHelpfulCount() != null ? workflow.getHelpfulCount() : 0;
            double existingConfidence = workflow.getAverageConfidence() != null ? workflow.getAverageConfidence() : 0.0;
            double incomingConfidence = trace.getConfidence() != null ? trace.getConfidence() : existingConfidence;
            workflow.setHelpfulCount(helpfulCount + 1);
            workflow.setAverageConfidence(((existingConfidence * helpfulCount) + incomingConfidence) / Math.max(helpfulCount + 1, 1));
        }

        ApprovedAgentWorkflow saved = approvedAgentWorkflowRepository.save(workflow);
        log.info("Approved agent workflow {} for connection {} intent {} using question '{}' (count={})",
            saved.getId(), connectionId, saved.getIntent(), anchorQuestion, saved.getHelpfulCount());
        return saved;
    }

    private Optional<ChatTurnContext> resolveApprovalContext(AgentRunTraceResponse trace) {
        if (trace == null) {
            return Optional.empty();
        }
        if (trace.getAssistantMessageId() != null && !trace.getAssistantMessageId().isBlank()) {
            Optional<ChatTurnContext> byAssistant = chatTurnContextRepository.findByAssistantMessageId(trace.getAssistantMessageId());
            if (byAssistant.isPresent()) {
                return byAssistant;
            }
        }
        if (trace.getChatId() == null || trace.getChatId().isBlank()) {
            return Optional.empty();
        }
        return chatTurnContextRepository.findTopByChatIdOrderByCreatedAtDesc(trace.getChatId());
    }

    private List<Map<String, Object>> readStepParams(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STEP_PARAMS_TYPE);
        } catch (Exception e) {
            log.debug("Failed to read approved workflow step params", e);
            return List.of();
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize approved workflow data", e);
        }
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        return tokenize(question).stream().sorted().reduce((left, right) -> left + " " + right).orElse("");
    }

    private Set<String> tokenize(String question) {
        String lower = canonicalizeQuestion(question);
        String[] rawTokens = NON_ALNUM.matcher(lower).replaceAll(" ").trim().split("\\s+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : rawTokens) {
            if (token == null || token.isBlank()) {
                continue;
            }
            if (STOP_WORDS.contains(token)) {
                continue;
            }
            if (token.length() < 2) {
                continue;
            }
            if (token.chars().allMatch(Character::isDigit)) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private String canonicalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        String normalized = question.toLowerCase(Locale.ROOT).trim();
        normalized = normalized
            .replaceAll("\\blast\\s+(one|1)\\s+week\\b", "window7days")
            .replaceAll("\\bpast\\s+(one|1)\\s+week\\b", "window7days")
            .replaceAll("\\blast\\s+7\\s+days\\b", "window7days")
            .replaceAll("\\bpast\\s+7\\s+days\\b", "window7days")
            .replaceAll("\\blast\\s+(one|1)\\s+month\\b", "window30days")
            .replaceAll("\\bpast\\s+(one|1)\\s+month\\b", "window30days")
            .replaceAll("\\blast\\s+30\\s+days\\b", "window30days")
            .replaceAll("\\bpast\\s+30\\s+days\\b", "window30days")
            .replaceAll("\\bthis\\s+month\\b", "thismonth")
            .replaceAll("\\bthis\\s+week\\b", "thisweek")
            .replaceAll("\\bmonth\\s+to\\s+date\\b", "monthtodate")
            .replaceAll("\\bmtd\\b", "monthtodate");
        return normalized;
    }

    private double similarity(Set<String> left, Set<String> right) {
        if (left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new LinkedHashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new LinkedHashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0d : ((double) intersection.size() / union.size());
    }

    private String questionSignature(String normalizedQuestion) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(normalizedQuestion.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash approved workflow question", e);
        }
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private boolean looksLikeFollowUpApproval(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.trim().toLowerCase(Locale.ROOT);
        return FOLLOW_UP_APPROVAL_PATTERN.matcher(normalized).find();
    }

    private String buildWorkflowMatchText(
        String anchorQuestion,
        String chainSummary,
        String resolvedContextJson,
        String approvalQuestion
    ) {
        StringBuilder sb = new StringBuilder();
        if (anchorQuestion != null && !anchorQuestion.isBlank()) {
            sb.append(anchorQuestion).append(' ');
        }
        if (chainSummary != null && !chainSummary.isBlank()) {
            sb.append(chainSummary).append(' ');
        }
        if (resolvedContextJson != null && !resolvedContextJson.isBlank()) {
            sb.append(resolvedContextJson).append(' ');
        }
        if (approvalQuestion != null && !approvalQuestion.isBlank()) {
            sb.append(approvalQuestion);
        }
        return sb.toString().trim();
    }

    private List<Map<String, Object>> buildStepParams(
        AgentRunTraceResponse trace,
        String approvalQuestion,
        ChatTurnContext approvalContext
    ) {
        if (trace.getSteps() == null) {
            return List.of();
        }

        return trace.getSteps().stream().map(step -> {
            Map<String, Object> params = new LinkedHashMap<>();
            if (step.getParams() != null) {
                params.putAll(step.getParams());
            }
            if (step.getExecutedSql() != null && !step.getExecutedSql().isBlank()) {
                params.put("approvedSql", step.getExecutedSql());
            }
            if ("universal_chat_tool".equals(step.getToolName()) && approvalQuestion != null && !approvalQuestion.isBlank()) {
                params.put("approvedQuestion", approvalQuestion);
                if (approvalContext != null && approvalContext.getChainSummary() != null && !approvalContext.getChainSummary().isBlank()) {
                    params.put("approvedChainSummary", approvalContext.getChainSummary());
                }
                if (approvalContext != null && approvalContext.getResolvedContextJson() != null && !approvalContext.getResolvedContextJson().isBlank()) {
                    params.put("approvedResolvedContext", approvalContext.getResolvedContextJson());
                }
            }

            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("stepKey", step.getStepKey());
            entry.put("toolName", step.getToolName());
            entry.put("params", params);
            return entry;
        }).toList();
    }
}
