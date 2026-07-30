package com.dbaagent.service;

import com.dbaagent.dto.AgentRunTraceResponse;
import com.dbaagent.model.ChatMessage;
import com.dbaagent.model.ChatTurnContext;
import com.dbaagent.model.QueryResult;
import com.dbaagent.repository.ChatMessageRepository;
import com.dbaagent.repository.ChatTurnContextRepository;
import com.dbaagent.service.agent.AgentExecutionContext;
import com.dbaagent.service.agent.AgentRunService;
import com.dbaagent.util.QueryNormalizer;
import com.dbaagent.util.PromptIntentSignals;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ConversationContextService {

    public record TurnSnapshotRequest(
        String connectionId,
        String chatId,
        String userMessageId,
        String assistantMessageId,
        String userQuestion,
        String effectiveQuestion,
        String assistantMessage,
        String routeType,
        String intent,
        String sourceSql,
        QueryResult queryResult,
        Double confidenceScore,
        String agentRunId,
        boolean success,
        ResolvedConversationContext priorContext,
        String threadMode,
        String sourceTier,
        List<String> evidenceSummaries,
        String stopReason
    ) {}

    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};
    private static final TypeReference<List<Map<String, Object>>> LIST_MAP_TYPE = new TypeReference<>() {};
    private static final Pattern NON_ALNUM = Pattern.compile("[^a-z0-9_]+");
    private static final Pattern FOLLOW_UP_PATTERN = Pattern.compile(
        "(?i)\\b(it|its|that|those|them|these|same|same one|same table|same metric|same thing|do it|do this|use that|use those|which one|what about|how about|and for|what if|that table|that column|that query|those columns|those tables|those keys|one of those|one of them|instead|continue|keep|for these|for those|for them|first one|second one|third one|first query|second query|third query|previous query|earlier query|prior query|full query|full sql|query text)\\b"
    );
    private static final Pattern TIMEFRAME_PATTERN = Pattern.compile(
        "(?i)\\b(last\\s+\\d+\\s+(?:day|days|week|weeks|month|months|year|years)|past\\s+\\d+\\s+(?:day|days|week|weeks|month|months|year|years)|today|yesterday|this\\s+week|this\\s+month|this\\s+year|month\\s+to\\s+date|mtd|week\\s+to\\s+date|wtd)\\b"
    );
    private static final Pattern GROUP_BY_PATTERN = Pattern.compile("(?i)\\bgroup\\s+by\\s+([a-zA-Z0-9_.,\\s]+)");
    private static final Pattern ORDER_BY_PATTERN = Pattern.compile("(?i)\\border\\s+by\\s+([a-zA-Z0-9_.,\\s]+)");
    private static final Pattern TEMPORAL_COLUMN_PATTERN = Pattern.compile("(?i)\\bwhere\\b[\\s\\S]*?([a-zA-Z0-9_\\.]+(?:date|time|created|updated|_on|_at))\\s*(?:>=|>|<=|<|between)");
    private static final Set<String> STOP_WORDS = Set.of(
        "the", "a", "an", "and", "or", "to", "for", "of", "in", "on", "this", "that",
        "these", "those", "is", "are", "was", "were", "be", "been", "it", "its", "by",
        "with", "from", "give", "show", "get", "such", "they", "them", "their", "what",
        "which", "can", "you", "we", "our", "about", "me", "find", "list", "fetch", "retrieve",
        "return", "tell", "please", "need", "could", "would", "should", "have", "has", "had"
    );
    private static final int MAX_RELATED_CONTEXTS = 24;
    private static final int MAX_HISTORY_TURNS = 6;

    private final ChatTurnContextRepository chatTurnContextRepository;
    private final ChatMessageRepository chatMessageRepository;
    private final AgentRunService agentRunService;
    private final ChatQuestionRoutingService chatQuestionRoutingService;
    private final ObjectMapper objectMapper;

    public Optional<ChatTurnContext> findByAssistantMessageId(String assistantMessageId) {
        if (assistantMessageId == null || assistantMessageId.isBlank()) {
            return Optional.empty();
        }
        return chatTurnContextRepository.findByAssistantMessageId(assistantMessageId);
    }

    public ResolvedConversationContext resolveRelatedContext(String connectionId, String chatId, String question) {
        if (chatId == null || chatId.isBlank() || question == null || question.isBlank()) {
            return ResolvedConversationContext.empty();
        }

        List<ChatTurnContext> recent = chatTurnContextRepository.findByChatIdOrderByCreatedAtDesc(chatId)
            .stream()
            .limit(MAX_RELATED_CONTEXTS)
            .toList();
        if (recent.isEmpty()) {
            return ResolvedConversationContext.empty();
        }

        if (looksLikePriorQueryReference(question)) {
            Optional<ChatTurnContext> sqlBackedContext = recent.stream()
                .filter(candidate -> candidate != null)
                .filter(candidate -> candidate.getSourceSql() != null && !candidate.getSourceSql().isBlank())
                .filter(candidate -> !"FAILED".equalsIgnoreCase(candidate.getStateStatus()))
                .findFirst();
            if (sqlBackedContext.isPresent()) {
                Map<String, ChatTurnContext> byId = recent.stream()
                    .filter(context -> context.getId() != null)
                    .collect(Collectors.toMap(ChatTurnContext::getId, context -> context, (left, right) -> left, LinkedHashMap::new));
                return buildResolvedContext(sqlBackedContext.get(), byId, 0.96d);
            }
        }

        Set<String> questionTokens = tokenize(question);
        boolean followUpCue = hasFollowUpCue(question);
        String routeType = chatQuestionRoutingService.classify(question).type().name();

        ChatTurnContext best = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int index = 0; index < recent.size(); index++) {
            ChatTurnContext candidate = recent.get(index);
            double score = scoreCandidate(candidate, questionTokens, followUpCue, routeType, index);
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
            }
        }

        double threshold = followUpCue ? 0.24d : 0.34d;
        if (best == null || bestScore < threshold) {
            return ResolvedConversationContext.empty();
        }

        Map<String, ChatTurnContext> byId = recent.stream()
            .filter(context -> context.getId() != null)
            .collect(Collectors.toMap(ChatTurnContext::getId, context -> context, (left, right) -> left, LinkedHashMap::new));
        return buildResolvedContext(best, byId, bestScore);
    }

    private ResolvedConversationContext buildResolvedContext(
        ChatTurnContext best,
        Map<String, ChatTurnContext> byId,
        double score
    ) {
        List<ChatTurnContext> chain = buildChain(best, byId);
        Map<String, Object> mergedResolvedContext = mergeChainResolvedContext(chain);
        List<Map<String, Object>> selectedEntities = latestNonEmptySelectedEntities(chain);
        Map<String, Object> resultSummary = latestNonEmptyResultSummary(chain);
        String sourceSql = latestNonBlank(chain, ChatTurnContext::getSourceSql);
        String anchorQuestion = firstNonBlank(chain, ChatTurnContext::getAnchorQuestion);
        String chainSummary = latestNonBlank(chain, ChatTurnContext::getChainSummary);

        return new ResolvedConversationContext(
            best.getId(),
            best.getRouteType(),
            best.getStateStatus(),
            anchorQuestion,
            chainSummary,
            mergedResolvedContext,
            selectedEntities,
            resultSummary,
            sourceSql,
            buildConversationHistory(chain),
            score
        );
    }

    public ConversationCarryoverDecision decideCarryover(
        String question,
        ChatQuestionRoutingService.QuestionRoute currentRoute,
        ResolvedConversationContext context
    ) {
        if (context == null || !context.hasMatchedContext()) {
            return ConversationCarryoverDecision.empty();
        }

        String normalizedQuestion = question == null ? "" : question.trim();
        String currentRouteType = currentRoute != null ? currentRoute.type().name() : null;
        boolean followUpCue = hasFollowUpCue(normalizedQuestion);
        boolean clarificationAnswer = looksLikeClarificationAnswer(normalizedQuestion, context);
        boolean routeMismatch = currentRouteType != null
            && context.matchedRouteType() != null
            && !currentRouteType.equalsIgnoreCase(context.matchedRouteType());
        boolean explicitTopicReset = isExplicitTopicReset(normalizedQuestion, currentRoute, context);

        if (explicitTopicReset || (routeMismatch && !followUpCue && !clarificationAnswer)) {
            return new ConversationCarryoverDecision(
                ConversationCarryoverDecision.ReuseMode.NEW_INTENT,
                context.matchedContextId(),
                context.matchedRouteType(),
                context.matchedStateStatus(),
                List.of(),
                List.of(),
                null,
                null,
                List.of(),
                Math.max(0.0d, context.relevanceScore() - 0.10d),
                explicitTopicReset
                    ? "The current turn changes topic or intent and should not inherit the prior scope."
                    : "The matched prior context belongs to a different route family, so scope reuse is suppressed."
            );
        }

        if (clarificationAnswer) {
            return new ConversationCarryoverDecision(
                ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION,
                context.matchedContextId(),
                context.matchedRouteType(),
                context.matchedStateStatus(),
                stringList(context.resolvedContext().get("tables")),
                stringList(context.resolvedContext().get("joinConditions")),
                stringValue(context.resolvedContext().get("chosenTemporalColumn")),
                stringValue(context.resolvedContext().get("metric")),
                stringList(context.resolvedContext().get("filters")),
                Math.max(0.0d, context.relevanceScore()),
                "The current message looks like an answer to the pending clarification and should be applied to the prior scope."
            );
        }

        if (followUpCue || context.relevanceScore() >= 0.68d) {
            return new ConversationCarryoverDecision(
                ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE,
                context.matchedContextId(),
                context.matchedRouteType(),
                context.matchedStateStatus(),
                stringList(context.resolvedContext().get("tables")),
                stringList(context.resolvedContext().get("joinConditions")),
                stringValue(context.resolvedContext().get("chosenTemporalColumn")),
                stringValue(context.resolvedContext().get("metric")),
                stringList(context.resolvedContext().get("filters")),
                Math.max(0.0d, context.relevanceScore()),
                "The current turn narrows or extends the prior scope and should reuse the resolved tables, joins, and business definitions."
            );
        }

        return new ConversationCarryoverDecision(
            ConversationCarryoverDecision.ReuseMode.NONE,
            context.matchedContextId(),
            context.matchedRouteType(),
            context.matchedStateStatus(),
            List.of(),
            List.of(),
            null,
            null,
            List.of(),
            Math.max(0.0d, context.relevanceScore()),
            "Prior context exists, but the current turn does not clearly request scope reuse."
        );
    }

    public String buildEffectiveQuestion(String actualUserQuestion, ResolvedConversationContext context) {
        return buildEffectiveQuestion(actualUserQuestion, context, ConversationCarryoverDecision.empty());
    }

    public String buildEffectiveQuestion(
        String actualUserQuestion,
        ResolvedConversationContext context,
        ConversationCarryoverDecision carryoverDecision
    ) {
        if (actualUserQuestion == null || actualUserQuestion.isBlank() || context == null || !context.hasMatchedContext()) {
            return actualUserQuestion;
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Resolved related conversation context:\n");
        if (carryoverDecision != null && carryoverDecision.reuseMode() != ConversationCarryoverDecision.ReuseMode.NONE) {
            sb.append("- Carry-over mode: ").append(carryoverDecision.reuseMode().name()).append('\n');
            if (carryoverDecision.rationale() != null && !carryoverDecision.rationale().isBlank()) {
                sb.append("- Carry-over rationale: ").append(cap(cleanWhitespace(carryoverDecision.rationale()), 260)).append('\n');
            }
            if (carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE) {
                sb.append("- Preserve the prior tables, joins, timeframe, and metric unless the user explicitly changes them.\n");
            } else if (carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION) {
                sb.append("- Treat the current message as the answer to the prior clarification and continue the same scope.\n");
            }
        }
        if (context.anchorQuestion() != null && !context.anchorQuestion().isBlank()) {
            sb.append("- Anchor question: ").append(context.anchorQuestion()).append('\n');
        }
        if (context.chainSummary() != null && !context.chainSummary().isBlank()) {
            sb.append("- Chain summary: ").append(cap(context.chainSummary(), 700)).append('\n');
        }

        appendResolvedContext(sb, context.resolvedContext());
        appendSelectedEntities(sb, context.selectedEntities());

        if (context.sourceSql() != null && !context.sourceSql().isBlank()) {
            sb.append("- Prior scope SQL available and should be reused when the user refers to previous results.\n");
        }

        sb.append("Current user request: ").append(actualUserQuestion);
        return sb.toString();
    }

    @Transactional
    public ChatTurnContext recordTurn(TurnSnapshotRequest request) {
        if (request == null
            || isBlank(request.chatId())
            || isBlank(request.connectionId())
            || isBlank(request.userMessageId())
            || isBlank(request.assistantMessageId())
            || isBlank(request.userQuestion())) {
            throw new IllegalArgumentException("Conversation turn snapshot requires chat, connection, message ids, and user question");
        }

        ChatTurnContext context = chatTurnContextRepository.findByAssistantMessageId(request.assistantMessageId())
            .orElseGet(ChatTurnContext::new);

        Optional<AgentRunTraceResponse> trace = Optional.ofNullable(request.agentRunId())
            .filter(id -> !id.isBlank())
            .flatMap(agentRunService::getTrace);

        Map<String, Object> priorResolvedContext = request.priorContext() != null
            ? request.priorContext().resolvedContext()
            : Map.of();
        Map<String, Object> currentResolvedContext = extractResolvedContext(request, trace.orElse(null));
        Map<String, Object> mergedResolvedContext = mergeResolvedContext(priorResolvedContext, currentResolvedContext);

        List<Map<String, Object>> currentSelectedEntities = extractSelectedEntities(request.queryResult(), request.sourceSql());
        List<Map<String, Object>> selectedEntities = !currentSelectedEntities.isEmpty()
            ? currentSelectedEntities
            : (request.priorContext() != null ? request.priorContext().selectedEntities() : List.of());

        Map<String, Object> currentResultSummary = buildResultSummary(request.queryResult());
        Map<String, Object> resultSummary = !currentResultSummary.isEmpty()
            ? currentResultSummary
            : (request.priorContext() != null ? request.priorContext().resultSummary() : Map.of());

        String anchorQuestion = request.priorContext() != null && request.priorContext().hasMatchedContext()
            && request.priorContext().anchorQuestion() != null && !request.priorContext().anchorQuestion().isBlank()
            ? request.priorContext().anchorQuestion()
            : request.userQuestion();

        String questionSummary = summarizeQuestion(request.userQuestion());
        String answerSummary = summarizeAnswer(request.assistantMessage());
        String sourceSql = firstNonBlank(request.sourceSql(), request.priorContext() != null ? request.priorContext().sourceSql() : null);
        String chainSummary = buildChainSummary(
            anchorQuestion,
            request.userQuestion(),
            request.assistantMessage(),
            mergedResolvedContext,
            selectedEntities,
            resultSummary,
            sourceSql,
            request.priorContext() != null ? request.priorContext().chainSummary() : null
        );

        context.setChatId(request.chatId());
        context.setConnectionId(request.connectionId());
        context.setUserMessageId(request.userMessageId());
        context.setAssistantMessageId(request.assistantMessageId());
        context.setParentContextId(request.priorContext() != null && request.priorContext().hasMatchedContext()
            ? request.priorContext().matchedContextId()
            : null);
        context.setStateStatus(resolveStateStatus(request.success(), request.assistantMessage()));
        context.setRouteType(firstNonBlank(request.routeType(), trace.map(AgentRunTraceResponse::getIntent).orElse(null)));
        context.setIntent(firstNonBlank(request.intent(), trace.map(AgentRunTraceResponse::getIntent).orElse(null)));
        context.setAnchorQuestion(anchorQuestion);
        context.setCurrentQuestion(request.userQuestion());
        context.setQuestionSummary(questionSummary);
        context.setAnswerSummary(answerSummary);
        context.setChainSummary(chainSummary);
        context.setResolvedContextJson(writeJson(mergedResolvedContext));
        context.setSelectedEntitiesJson(writeJson(selectedEntities));
        context.setResultSummaryJson(writeJson(resultSummary));
        context.setSourceSql(sourceSql);
        context.setTopicSignature(buildTopicSignature(anchorQuestion, chainSummary, mergedResolvedContext, selectedEntities));
        context.setConfidenceScore(request.confidenceScore());
        return chatTurnContextRepository.save(context);
    }

    private double scoreCandidate(
        ChatTurnContext candidate,
        Set<String> questionTokens,
        boolean followUpCue,
        String routeType,
        int recencyIndex
    ) {
        if (candidate == null) {
            return Double.NEGATIVE_INFINITY;
        }

        Set<String> signatureTokens = tokenize(candidate.getTopicSignature());
        Set<String> resolvedTokens = extractResolvedTokens(candidate);
        Set<String> entityTokens = extractSelectedEntityTokens(candidate);
        Set<String> summaryTokens = tokenize(candidate.getChainSummary() + " " + candidate.getQuestionSummary());
        Map<String, Object> resolvedContext = readJsonMap(candidate.getResolvedContextJson());
        boolean referencesFailure = containsAny(questionTokens, Set.of("error", "failed", "failure", "clarify", "clarification"));

        double score = 0.0d;
        score += jaccard(questionTokens, signatureTokens) * 0.40d;
        score += jaccard(questionTokens, summaryTokens) * 0.20d;
        score += jaccard(questionTokens, resolvedTokens) * 0.18d;
        score += jaccard(questionTokens, entityTokens) * 0.12d;

        if (followUpCue && (!entityTokens.isEmpty() || (candidate.getSourceSql() != null && !candidate.getSourceSql().isBlank()))) {
            score += 0.18d;
        }
        if (followUpCue
            && candidate.getSourceSql() != null
            && !candidate.getSourceSql().isBlank()
            && containsAny(questionTokens, Set.of("query", "sql", "scan", "rows", "first", "second", "third", "previous", "earlier", "prior"))) {
            score += 0.18d;
        }
        if ("RESOLVED".equalsIgnoreCase(candidate.getStateStatus())) {
            score += 0.05d;
        }
        if ("CLARIFICATION".equalsIgnoreCase(candidate.getStateStatus()) && followUpCue) {
            score += 0.10d;
            if (!isBlank(stringValue(resolvedContext.get("chosenTemporalColumn")))
                && containsAny(questionTokens, Set.of("date", "time", "column", "onboarding", "created", "updated", "only"))) {
                score += 0.08d;
            }
            if (!stringList(resolvedContext.get("filters")).isEmpty()
                && containsAny(questionTokens, tokenize(String.join(" ", stringList(resolvedContext.get("filters")))))) {
                score += 0.05d;
            }
        }
        if ("FAILED".equalsIgnoreCase(candidate.getStateStatus()) && !referencesFailure) {
            score -= followUpCue ? 0.28d : 0.20d;
        }
        if (!followUpCue && candidate.getRouteType() != null && routeType != null
            && !routeType.equalsIgnoreCase(candidate.getRouteType())) {
            score -= 0.32d;
            if ("BRAIN_METADATA".equalsIgnoreCase(routeType) || "BRAIN_METADATA".equalsIgnoreCase(candidate.getRouteType())) {
                score -= 0.12d;
            }
        }
        if (isExplicitTopicReset(questionTokens, routeType, candidate)) {
            score -= 0.45d;
        }

        score += Math.max(0.0d, 0.08d - (recencyIndex * 0.01d));
        return score;
    }

    private List<ChatTurnContext> buildChain(ChatTurnContext leaf, Map<String, ChatTurnContext> byId) {
        ArrayDeque<ChatTurnContext> deque = new ArrayDeque<>();
        Set<String> visited = new HashSet<>();
        ChatTurnContext current = leaf;
        while (current != null && current.getId() != null && visited.add(current.getId())) {
            deque.addFirst(current);
            current = current.getParentContextId() == null ? null : byId.get(current.getParentContextId());
        }
        return List.copyOf(deque);
    }

    private List<AgentExecutionContext.ConversationTurn> buildConversationHistory(List<ChatTurnContext> chain) {
        if (chain == null || chain.isEmpty()) {
            return List.of();
        }
        List<AgentExecutionContext.ConversationTurn> turns = new ArrayList<>();
        int start = Math.max(0, chain.size() - (MAX_HISTORY_TURNS / 2));
        for (ChatTurnContext context : chain.subList(start, chain.size())) {
            String userContent = chatMessageRepository.findById(context.getUserMessageId())
                .map(ChatMessage::getContent)
                .orElse(context.getCurrentQuestion());
            if (userContent != null && !userContent.isBlank()) {
                turns.add(new AgentExecutionContext.ConversationTurn("user", cap(cleanWhitespace(userContent), 500)));
            }

            String assistantContent = chatMessageRepository.findById(context.getAssistantMessageId())
                .map(ChatMessage::getContent)
                .orElse(context.getAnswerSummary());
            if (assistantContent != null && !assistantContent.isBlank()) {
                turns.add(new AgentExecutionContext.ConversationTurn("assistant", cap(cleanWhitespace(assistantContent), 700)));
            }
        }
        return List.copyOf(turns);
    }

    private Map<String, Object> mergeChainResolvedContext(List<ChatTurnContext> chain) {
        Map<String, Object> merged = new LinkedHashMap<>();
        for (ChatTurnContext context : chain) {
            merged = mergeResolvedContext(merged, readJsonMap(context.getResolvedContextJson()));
        }
        return merged;
    }

    private List<Map<String, Object>> latestNonEmptySelectedEntities(List<ChatTurnContext> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            List<Map<String, Object>> selected = readJsonList(chain.get(i).getSelectedEntitiesJson());
            if (!selected.isEmpty()) {
                return selected;
            }
        }
        return List.of();
    }

    private Map<String, Object> latestNonEmptyResultSummary(List<ChatTurnContext> chain) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            Map<String, Object> summary = readJsonMap(chain.get(i).getResultSummaryJson());
            if (!summary.isEmpty()) {
                return summary;
            }
        }
        return Map.of();
    }

    private String buildChainSummary(
        String anchorQuestion,
        String currentQuestion,
        String assistantMessage,
        Map<String, Object> resolvedContext,
        List<Map<String, Object>> selectedEntities,
        Map<String, Object> resultSummary,
        String sourceSql,
        String priorChainSummary
    ) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(anchorQuestion)) {
            sb.append("Anchor: ").append(cleanWhitespace(anchorQuestion)).append(". ");
        }
        if (!isBlank(priorChainSummary)) {
            sb.append("Prior chain: ").append(cap(cleanWhitespace(priorChainSummary), 280)).append(". ");
        }
        if (!isBlank(currentQuestion)) {
            sb.append("Latest request: ").append(cleanWhitespace(currentQuestion)).append(". ");
        }
        appendResolvedContextSummary(sb, resolvedContext);
        if (selectedEntities != null && !selectedEntities.isEmpty()) {
            List<String> labels = selectedEntities.stream()
                .map(entity -> firstNonBlank(stringValue(entity.get("displayLabel")), stringValue(entity.get("keyValue"))))
                .filter(value -> value != null && !value.isBlank())
                .limit(5)
                .toList();
            if (!labels.isEmpty()) {
                sb.append("Selected scope: ").append(String.join(", ", labels)).append(". ");
            }
        }
        if (resultSummary != null && !resultSummary.isEmpty()) {
            Object rowCount = resultSummary.get("rowCount");
            if (rowCount != null) {
                sb.append("Last result row count: ").append(rowCount).append(". ");
            }
        }
        if (!isBlank(stringValue(resolvedContext.get("sourceTier")))) {
            sb.append("Evidence tier: ").append(stringValue(resolvedContext.get("sourceTier"))).append(". ");
        }
        if (!isBlank(sourceSql)) {
            sb.append("Source SQL available for the current scope. ");
        }
        if (!isBlank(assistantMessage)) {
            sb.append("Answer summary: ").append(cap(summarizeAnswer(assistantMessage), 240)).append(".");
        }
        return cap(cleanWhitespace(sb.toString()), 900);
    }

    private void appendResolvedContextSummary(StringBuilder sb, Map<String, Object> resolvedContext) {
        if (resolvedContext == null || resolvedContext.isEmpty()) {
            return;
        }
        List<String> tables = stringList(resolvedContext.get("tables"));
        if (!tables.isEmpty()) {
            sb.append("Tables: ").append(String.join(", ", tables.stream().limit(5).toList())).append(". ");
        }
        List<String> filters = stringList(resolvedContext.get("filters"));
        if (!filters.isEmpty()) {
            sb.append("Filters: ").append(String.join(", ", filters.stream().limit(5).toList())).append(". ");
        }
        String timeframe = stringValue(resolvedContext.get("timeframe"));
        if (!isBlank(timeframe)) {
            sb.append("Timeframe: ").append(timeframe).append(". ");
        }
        String metric = stringValue(resolvedContext.get("metric"));
        if (!isBlank(metric)) {
            sb.append("Metric: ").append(metric).append(". ");
        }
    }

    private Map<String, Object> extractResolvedContext(TurnSnapshotRequest request, AgentRunTraceResponse trace) {
        Map<String, Object> resolved = new LinkedHashMap<>();
        Set<String> tables = new LinkedHashSet<>();
        Set<String> columns = new LinkedHashSet<>();
        Set<String> filters = new LinkedHashSet<>();
        Set<String> entities = new LinkedHashSet<>();
        Set<String> grouping = new LinkedHashSet<>();
        Set<String> ordering = new LinkedHashSet<>();
        Set<String> joinConditions = new LinkedHashSet<>();

        if (!isBlank(request.sourceSql())) {
            for (String table : QueryNormalizer.extractTableNames(request.sourceSql())) {
                if (!isBlank(table)) {
                    String normalized = normalizeIdentifier(table);
                    tables.add(normalized);
                    entities.add(singularize(normalized));
                }
            }
            Matcher temporalMatcher = TEMPORAL_COLUMN_PATTERN.matcher(request.sourceSql());
            if (temporalMatcher.find()) {
                resolved.put("chosenTemporalColumn", normalizeIdentifier(temporalMatcher.group(1)));
            }

            Matcher groupMatcher = GROUP_BY_PATTERN.matcher(request.sourceSql());
            if (groupMatcher.find()) {
                grouping.addAll(splitSqlList(groupMatcher.group(1)));
            }
            Matcher orderMatcher = ORDER_BY_PATTERN.matcher(request.sourceSql());
            if (orderMatcher.find()) {
                ordering.addAll(splitSqlList(orderMatcher.group(1)));
            }
        }

        if (request.queryResult() != null && request.queryResult().getColumns() != null) {
            request.queryResult().getColumns().stream()
                .filter(Objects::nonNull)
                .map(this::normalizeIdentifier)
                .filter(value -> !value.isBlank())
                .forEach(columns::add);
        }

        String timeframe = extractTimeframe(request.userQuestion());
        if (!isBlank(timeframe)) {
            resolved.put("timeframe", timeframe);
        }
        if (!isBlank(request.threadMode())) {
            resolved.put("threadMode", request.threadMode());
        }
        if (!isBlank(request.sourceTier())) {
            resolved.put("sourceTier", request.sourceTier());
        }
        if (!isBlank(request.stopReason())) {
            resolved.put("stopReason", request.stopReason());
        }
        if (request.evidenceSummaries() != null && !request.evidenceSummaries().isEmpty()) {
            resolved.put("evidenceSummaries", request.evidenceSummaries().stream()
                .filter(Objects::nonNull)
                .filter(value -> !value.isBlank())
                .limit(10)
                .toList());
        }

        String metric = extractMetric(request.userQuestion(), request.queryResult());
        if (!isBlank(metric)) {
            resolved.put("metric", metric);
        }

        extractQuestionFilters(request.userQuestion()).forEach(filters::add);

        if (trace != null && trace.getSteps() != null) {
            for (AgentRunTraceResponse.StepDto step : trace.getSteps()) {
                if (!isBlank(step.getExecutedSql())) {
                    for (String table : QueryNormalizer.extractTableNames(step.getExecutedSql())) {
                        if (!isBlank(table)) {
                            String normalized = normalizeIdentifier(table);
                            tables.add(normalized);
                            entities.add(singularize(normalized));
                        }
                    }
                }
                if (step.getObservation() != null && step.getObservation().getData() != null) {
                    mergeObservationContext(step.getObservation().getData(), resolved, tables, columns, filters, grouping, ordering, entities, joinConditions);
                }
            }
        }

        if (!tables.isEmpty()) {
            resolved.put("tables", List.copyOf(tables));
        }
        if (!columns.isEmpty()) {
            resolved.put("columns", List.copyOf(columns));
        }
        if (!filters.isEmpty()) {
            resolved.put("filters", List.copyOf(filters));
        }
        if (!entities.isEmpty()) {
            resolved.put("entities", List.copyOf(entities));
        }
        if (!grouping.isEmpty()) {
            resolved.put("grouping", List.copyOf(grouping));
        }
        if (!ordering.isEmpty()) {
            resolved.put("ordering", List.copyOf(ordering));
        }
        if (!joinConditions.isEmpty()) {
            resolved.put("joinConditions", List.copyOf(joinConditions));
        }
        return resolved;
    }

    private void mergeObservationContext(
        Map<String, Object> observationData,
        Map<String, Object> resolved,
        Set<String> tables,
        Set<String> columns,
        Set<String> filters,
        Set<String> grouping,
        Set<String> ordering,
        Set<String> entities,
        Set<String> joinConditions
    ) {
        stringList(observationData.get("resolvedTables")).forEach(table -> {
            String normalized = normalizeIdentifier(table);
            if (!normalized.isBlank()) {
                tables.add(normalized);
                entities.add(singularize(normalized));
            }
        });
        stringList(observationData.get("resolvedColumns")).forEach(column -> {
            String normalized = normalizeIdentifier(column);
            if (!normalized.isBlank()) {
                columns.add(normalized);
            }
        });
        stringList(observationData.get("filterColumns")).forEach(filter -> {
            String normalized = normalizeIdentifier(filter);
            if (!normalized.isBlank()) {
                filters.add(normalized);
            }
        });
        stringList(observationData.get("grouping")).forEach(grouping::add);
        stringList(observationData.get("ordering")).forEach(ordering::add);
        stringList(firstNonNull(observationData.get("chosenJoinPath"), observationData.get("joinConditions"))).forEach(joinConditions::add);
        if (observationData.get("timeframe") != null && resolved.get("timeframe") == null) {
            resolved.put("timeframe", stringValue(observationData.get("timeframe")));
        }
        if (observationData.get("metric") != null && resolved.get("metric") == null) {
            resolved.put("metric", stringValue(observationData.get("metric")));
        }
        if (observationData.get("chosenTemporalColumn") != null && resolved.get("chosenTemporalColumn") == null) {
            resolved.put("chosenTemporalColumn", normalizeIdentifier(stringValue(observationData.get("chosenTemporalColumn"))));
        }
    }

    private List<Map<String, Object>> extractSelectedEntities(QueryResult queryResult, String sourceSql) {
        if (queryResult == null || queryResult.getColumns() == null || queryResult.getColumns().isEmpty()
            || queryResult.getRows() == null || queryResult.getRows().isEmpty()) {
            return List.of();
        }

        List<String> columns = queryResult.getColumns();
        int keyIndex = findPreferredIndex(columns, name -> "id".equalsIgnoreCase(name) || name.toLowerCase(Locale.ROOT).endsWith("_id"));
        int labelIndex = findPreferredIndex(columns, name -> {
            String normalized = name.toLowerCase(Locale.ROOT);
            return !normalized.endsWith("_id")
                && (normalized.contains("name")
                    || normalized.contains("title")
                    || normalized.contains("property")
                    || normalized.contains("hotel"));
        });
        if (labelIndex < 0) {
            labelIndex = findFirstStringLikeIndex(queryResult.getRows());
        }

        String table = null;
        String[] tables = sourceSql == null ? new String[0] : QueryNormalizer.extractTableNames(sourceSql);
        if (tables.length > 0) {
            table = normalizeIdentifier(tables[0]);
        }

        List<Map<String, Object>> selected = new ArrayList<>();
        int limit = Math.min(queryResult.getRows().size(), 12);
        for (int i = 0; i < limit; i++) {
            List<Object> row = queryResult.getRows().get(i);
            if (row == null || row.isEmpty()) {
                continue;
            }
            Map<String, Object> handle = new LinkedHashMap<>();
            if (!isBlank(table)) {
                handle.put("table", table);
                handle.put("entityType", singularize(table));
            }
            if (keyIndex >= 0 && keyIndex < row.size()) {
                handle.put("keyColumn", columns.get(keyIndex));
                handle.put("keyValue", row.get(keyIndex));
            }
            if (labelIndex >= 0 && labelIndex < row.size()) {
                handle.put("displayLabel", String.valueOf(row.get(labelIndex)));
                handle.put("displayColumn", columns.get(labelIndex));
            } else {
                handle.put("displayLabel", String.valueOf(row.getFirst()));
            }
            selected.add(handle);
        }
        return List.copyOf(selected);
    }

    private Map<String, Object> buildResultSummary(QueryResult queryResult) {
        if (queryResult == null) {
            return Map.of();
        }
        Map<String, Object> summary = new LinkedHashMap<>();
        if (queryResult.getRowCount() != null) {
            summary.put("rowCount", queryResult.getRowCount());
        }
        if (queryResult.getTotalRowCount() != null) {
            summary.put("totalRowCount", queryResult.getTotalRowCount());
        }
        if (queryResult.getColumns() != null && !queryResult.getColumns().isEmpty()) {
            summary.put("columns", queryResult.getColumns().stream().limit(10).toList());
        }
        if (queryResult.getRows() != null && !queryResult.getRows().isEmpty() && queryResult.getColumns() != null) {
            List<Map<String, Object>> preview = new ArrayList<>();
            int rowLimit = Math.min(queryResult.getRows().size(), 3);
            int columnLimit = Math.min(queryResult.getColumns().size(), 6);
            for (int rowIndex = 0; rowIndex < rowLimit; rowIndex++) {
                List<Object> row = queryResult.getRows().get(rowIndex);
                Map<String, Object> rowMap = new LinkedHashMap<>();
                for (int colIndex = 0; colIndex < columnLimit && colIndex < row.size(); colIndex++) {
                    rowMap.put(queryResult.getColumns().get(colIndex), row.get(colIndex));
                }
                preview.add(rowMap);
            }
            summary.put("preview", preview);
        }
        return summary;
    }

    private Map<String, Object> mergeResolvedContext(Map<String, Object> prior, Map<String, Object> current) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (prior != null) {
            merged.putAll(prior);
        }
        if (current == null || current.isEmpty()) {
            return merged;
        }

        mergeListField(merged, current, "entities");
        mergeListField(merged, current, "tables");
        mergeListField(merged, current, "columns");
        mergeListField(merged, current, "filters");
        mergeListField(merged, current, "grouping");
        mergeListField(merged, current, "ordering");
        mergeListField(merged, current, "joinConditions");
        overwriteIfPresent(merged, current, "timeframe");
        overwriteIfPresent(merged, current, "metric");
        overwriteIfPresent(merged, current, "chosenTemporalColumn");
        return merged;
    }

    private void mergeListField(Map<String, Object> target, Map<String, Object> incoming, String key) {
        LinkedHashSet<String> values = new LinkedHashSet<>(stringList(target.get(key)));
        values.addAll(stringList(incoming.get(key)));
        if (!values.isEmpty()) {
            target.put(key, List.copyOf(values));
        }
    }

    private void overwriteIfPresent(Map<String, Object> target, Map<String, Object> incoming, String key) {
        Object value = incoming.get(key);
        if (value == null) {
            return;
        }
        String stringValue = stringValue(value);
        if (!isBlank(stringValue)) {
            target.put(key, stringValue);
        }
    }

    private void appendResolvedContext(StringBuilder sb, Map<String, Object> resolvedContext) {
        if (resolvedContext == null || resolvedContext.isEmpty()) {
            return;
        }
        List<String> tables = stringList(resolvedContext.get("tables"));
        if (!tables.isEmpty()) {
            sb.append("- Resolved tables: ").append(String.join(", ", tables.stream().limit(6).toList())).append('\n');
        }
        List<String> columns = stringList(resolvedContext.get("columns"));
        if (!columns.isEmpty()) {
            sb.append("- Relevant columns: ").append(String.join(", ", columns.stream().limit(8).toList())).append('\n');
        }
        List<String> filters = stringList(resolvedContext.get("filters"));
        if (!filters.isEmpty()) {
            sb.append("- Filters already established: ").append(String.join(", ", filters.stream().limit(8).toList())).append('\n');
        }
        List<String> joins = stringList(resolvedContext.get("joinConditions"));
        if (!joins.isEmpty()) {
            sb.append("- Validated join path: ").append(String.join("; ", joins.stream().limit(4).toList())).append('\n');
        }
        String timeframe = stringValue(resolvedContext.get("timeframe"));
        if (!isBlank(timeframe)) {
            sb.append("- Timeframe: ").append(timeframe).append('\n');
        }
        String metric = stringValue(resolvedContext.get("metric"));
        if (!isBlank(metric)) {
            sb.append("- Metric: ").append(metric).append('\n');
        }
        String temporalColumn = stringValue(resolvedContext.get("chosenTemporalColumn"));
        if (!isBlank(temporalColumn)) {
            sb.append("- Chosen temporal column: ").append(temporalColumn).append('\n');
        }
    }

    private void appendSelectedEntities(StringBuilder sb, List<Map<String, Object>> selectedEntities) {
        if (selectedEntities == null || selectedEntities.isEmpty()) {
            return;
        }
        List<String> labels = selectedEntities.stream()
            .map(entity -> firstNonBlank(stringValue(entity.get("displayLabel")), stringValue(entity.get("keyValue"))))
            .filter(value -> value != null && !value.isBlank())
            .limit(8)
            .toList();
        if (!labels.isEmpty()) {
            sb.append("- Previously selected result scope: ").append(String.join(", ", labels)).append('\n');
        }
    }

    private String buildTopicSignature(
        String anchorQuestion,
        String chainSummary,
        Map<String, Object> resolvedContext,
        List<Map<String, Object>> selectedEntities
    ) {
        StringBuilder sb = new StringBuilder();
        if (!isBlank(anchorQuestion)) {
            sb.append(anchorQuestion).append(' ');
        }
        if (!isBlank(chainSummary)) {
            sb.append(chainSummary).append(' ');
        }
        for (String value : stringList(resolvedContext.get("tables"))) {
            sb.append(value).append(' ');
        }
        for (String value : stringList(resolvedContext.get("entities"))) {
            sb.append(value).append(' ');
        }
        for (String value : stringList(resolvedContext.get("filters"))) {
            sb.append(value).append(' ');
        }
        for (String value : stringList(resolvedContext.get("joinConditions"))) {
            sb.append(value).append(' ');
        }
        String timeframe = stringValue(resolvedContext.get("timeframe"));
        if (!isBlank(timeframe)) {
            sb.append(timeframe).append(' ');
        }
        String metric = stringValue(resolvedContext.get("metric"));
        if (!isBlank(metric)) {
            sb.append(metric).append(' ');
        }
        if (selectedEntities != null) {
            selectedEntities.stream()
                .map(entity -> firstNonBlank(stringValue(entity.get("displayLabel")), stringValue(entity.get("entityType"))))
                .filter(value -> value != null && !value.isBlank())
                .limit(8)
                .forEach(value -> sb.append(value).append(' '));
        }
        return cleanWhitespace(sb.toString());
    }

    private Set<String> extractResolvedTokens(ChatTurnContext context) {
        Map<String, Object> resolvedContext = readJsonMap(context.getResolvedContextJson());
        StringBuilder sb = new StringBuilder();
        stringList(resolvedContext.get("tables")).forEach(value -> sb.append(value).append(' '));
        stringList(resolvedContext.get("entities")).forEach(value -> sb.append(value).append(' '));
        stringList(resolvedContext.get("filters")).forEach(value -> sb.append(value).append(' '));
        stringList(resolvedContext.get("columns")).forEach(value -> sb.append(value).append(' '));
        stringList(resolvedContext.get("joinConditions")).forEach(value -> sb.append(value).append(' '));
        String timeframe = stringValue(resolvedContext.get("timeframe"));
        if (!isBlank(timeframe)) {
            sb.append(timeframe).append(' ');
        }
        String metric = stringValue(resolvedContext.get("metric"));
        if (!isBlank(metric)) {
            sb.append(metric).append(' ');
        }
        return tokenize(sb.toString());
    }

    private Object firstNonNull(Object first, Object second) {
        return first != null ? first : second;
    }

    private Set<String> extractSelectedEntityTokens(ChatTurnContext context) {
        StringBuilder sb = new StringBuilder();
        for (Map<String, Object> entity : readJsonList(context.getSelectedEntitiesJson())) {
            String label = firstNonBlank(stringValue(entity.get("displayLabel")), stringValue(entity.get("entityType")), stringValue(entity.get("table")));
            if (!isBlank(label)) {
                sb.append(label).append(' ');
            }
        }
        return tokenize(sb.toString());
    }

    private Set<String> tokenize(String value) {
        if (value == null || value.isBlank()) {
            return Set.of();
        }
        String[] raw = NON_ALNUM.matcher(value.toLowerCase(Locale.ROOT)).replaceAll(" ").trim().split("\\s+");
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : raw) {
            if (token == null || token.isBlank() || STOP_WORDS.contains(token) || token.length() < 2) {
                continue;
            }
            tokens.add(token);
        }
        return tokens;
    }

    private boolean hasFollowUpCue(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT).trim();
        return FOLLOW_UP_PATTERN.matcher(normalized).find()
            || normalized.startsWith("and ")
            || normalized.startsWith("also ")
            || normalized.startsWith("then ")
            || normalized.startsWith("same ")
            || normalized.startsWith("now ")
            || normalized.startsWith("only ")
            || normalized.startsWith("just ")
            || normalized.startsWith("continue ")
            || normalized.startsWith("keep ");
    }

    private boolean looksLikePriorQueryReference(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT).trim();
        return normalized.contains("full query")
            || normalized.contains("full sql")
            || normalized.contains("query text")
            || normalized.contains("previous query")
            || normalized.contains("earlier query")
            || normalized.contains("prior query")
            || normalized.contains("first one")
            || normalized.contains("second one")
            || normalized.contains("third one")
            || normalized.matches(".*\\bshow\\b.*\\b(query|sql)\\b.*");
    }

    private boolean looksLikeClarificationAnswer(String question, ResolvedConversationContext context) {
        if (question == null || question.isBlank() || context == null || !context.hasMatchedContext()) {
            return false;
        }

        String normalized = question.toLowerCase(Locale.ROOT).trim();
        boolean priorClarification = "CLARIFICATION".equalsIgnoreCase(context.matchedStateStatus());
        boolean shortAnswer = tokenize(question).size() <= 7;
        boolean explicitTimeWindow = PromptIntentSignals.hasExplicitTimeWindow(question);
        boolean columnSelection = normalized.contains("use ")
            || normalized.contains("column")
            || normalized.contains("timestamp")
            || normalized.contains("date column")
            || normalized.contains("booking_made_on")
            || normalized.contains("created_at")
            || normalized.contains("updated_at");
        boolean filterAnswer = normalized.contains("cancelled")
            || normalized.contains("canceled")
            || normalized.contains("active")
            || normalized.contains("inactive")
            || normalized.contains("india");

        return priorClarification && (explicitTimeWindow || shortAnswer || columnSelection || filterAnswer);
    }

    private boolean isExplicitTopicReset(
        String question,
        ChatQuestionRoutingService.QuestionRoute currentRoute,
        ResolvedConversationContext context
    ) {
        if (question == null || question.isBlank() || currentRoute == null || context == null || !context.hasMatchedContext()) {
            return false;
        }
        if (hasFollowUpCue(question) || looksLikeClarificationAnswer(question, context)) {
            return false;
        }

        String currentRouteType = currentRoute.type().name();
        if (currentRouteType.equalsIgnoreCase(context.matchedRouteType())) {
            return false;
        }

        String normalized = question.toLowerCase(Locale.ROOT);
        if (currentRoute.isBrainMetadata()) {
            return normalized.contains("table")
                || normalized.contains("columns")
                || normalized.contains("schema")
                || normalized.contains("relationship")
                || normalized.contains("pattern")
                || normalized.contains("dimension")
                || normalized.contains("fact");
        }
        if (currentRoute.isBiQuery() && "BRAIN_METADATA".equalsIgnoreCase(context.matchedRouteType())) {
            return true;
        }
        return false;
    }

    private boolean isExplicitTopicReset(Set<String> questionTokens, String routeType, ChatTurnContext candidate) {
        if (candidate == null || routeType == null) {
            return false;
        }
        if (questionTokens == null || questionTokens.isEmpty()) {
            return false;
        }
        if (routeType.equalsIgnoreCase(candidate.getRouteType())) {
            return false;
        }
        if ("BRAIN_METADATA".equalsIgnoreCase(routeType)) {
            return containsAny(questionTokens, Set.of("table", "tables", "column", "columns", "schema", "relationship", "pattern", "fact", "dimension"));
        }
        if ("BRAIN_METADATA".equalsIgnoreCase(candidate.getRouteType())) {
            return true;
        }
        return false;
    }

    private double jaccard(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0.0d;
        }
        Set<String> intersection = new HashSet<>(left);
        intersection.retainAll(right);
        Set<String> union = new HashSet<>(left);
        union.addAll(right);
        return union.isEmpty() ? 0.0d : ((double) intersection.size() / union.size());
    }

    private boolean containsAny(Set<String> haystack, Set<String> needles) {
        if (haystack == null || haystack.isEmpty() || needles == null || needles.isEmpty()) {
            return false;
        }
        for (String needle : needles) {
            if (haystack.contains(needle)) {
                return true;
            }
        }
        return false;
    }

    private String summarizeQuestion(String question) {
        return cap(cleanWhitespace(question), 220);
    }

    private String summarizeAnswer(String answer) {
        if (answer == null || answer.isBlank()) {
            return "";
        }
        String cleaned = answer
            .replaceAll("(?s)```sql\\s*.*?```", " ")
            .replaceAll("(?s)```.*?```", " ")
            .replaceAll("(?m)^\\s*[-*]\\s*", "")
            .replaceAll("\\s+", " ")
            .trim();
        return cap(cleaned, 320);
    }

    private String resolveStateStatus(boolean success, String assistantMessage) {
        if (!success) {
            return "FAILED";
        }
        if (assistantMessage == null || assistantMessage.isBlank()) {
            return "FAILED";
        }
        String lower = assistantMessage.toLowerCase(Locale.ROOT);
        if (lower.contains("need one clarification")
            || lower.contains("which column")
            || lower.contains("which table")
            || lower.contains("could you tell me")
            || lower.contains("confirm the exact")
            || lower.contains("do you mean")) {
            return "CLARIFICATION";
        }
        if (lower.contains("i encountered an error") || lower.contains("couldn't produce a valid result")) {
            return "FAILED";
        }
        return "RESOLVED";
    }

    private String extractTimeframe(String question) {
        if (question == null || question.isBlank()) {
            return null;
        }
        Matcher matcher = TIMEFRAME_PATTERN.matcher(question);
        return matcher.find() ? cleanWhitespace(matcher.group(1)) : null;
    }

    private String extractMetric(String question, QueryResult queryResult) {
        if (question != null) {
            String lower = question.toLowerCase(Locale.ROOT);
            for (String metric : List.of("booking volume", "bookings", "revenue", "subscription", "cancellations",
                "hotels", "properties", "payments", "arr", "mrr", "funnel", "conversion")) {
                if (lower.contains(metric)) {
                    return metric;
                }
            }
        }
        if (queryResult != null && queryResult.getColumns() != null) {
            for (String column : queryResult.getColumns()) {
                if (column != null) {
                    String lower = column.toLowerCase(Locale.ROOT);
                    if (lower.contains("count") || lower.contains("revenue") || lower.contains("amount") || lower.contains("booking")) {
                        return normalizeIdentifier(column);
                    }
                }
            }
        }
        return null;
    }

    private Set<String> extractQuestionFilters(String question) {
        if (question == null || question.isBlank()) {
            return Set.of();
        }
        String lower = question.toLowerCase(Locale.ROOT);
        LinkedHashSet<String> filters = new LinkedHashSet<>();
        for (String token : List.of("active", "inactive", "cancelled", "canceled", "confirmed", "pending", "india")) {
            if (lower.contains(token)) {
                filters.add(token);
            }
        }
        return filters;
    }

    private List<String> splitSqlList(String raw) {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        return List.of(raw.split(",")).stream()
            .map(this::normalizeIdentifier)
            .filter(value -> !value.isBlank())
            .toList();
    }

    private int findPreferredIndex(List<String> columns, java.util.function.Predicate<String> predicate) {
        if (columns == null || columns.isEmpty()) {
            return -1;
        }
        for (int i = 0; i < columns.size(); i++) {
            String column = columns.get(i);
            if (column != null && predicate.test(column)) {
                return i;
            }
        }
        return -1;
    }

    private int findFirstStringLikeIndex(List<List<Object>> rows) {
        if (rows == null || rows.isEmpty()) {
            return -1;
        }
        List<Object> firstRow = rows.getFirst();
        for (int i = 0; i < firstRow.size(); i++) {
            Object value = firstRow.get(i);
            if (value instanceof String string && !string.isBlank()) {
                return i;
            }
        }
        return firstRow.isEmpty() ? -1 : 0;
    }

    private Map<String, Object> readJsonMap(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return objectMapper.readValue(json, MAP_TYPE);
        } catch (Exception e) {
            log.debug("Failed to read JSON object from chat turn context", e);
            return Map.of();
        }
    }

    private List<Map<String, Object>> readJsonList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, LIST_MAP_TYPE);
        } catch (Exception e) {
            log.debug("Failed to read JSON list from chat turn context", e);
            return List.of();
        }
    }

    private String writeJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize chat turn context", e);
        }
    }

    private List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(this::stringValue)
                .filter(item -> item != null && !item.isBlank())
                .map(this::normalizeIdentifier)
                .filter(item -> !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private String firstNonBlank(String... values) {
        if (values == null) {
            return null;
        }
        for (String value : values) {
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String latestNonBlank(List<ChatTurnContext> chain, java.util.function.Function<ChatTurnContext, String> extractor) {
        for (int i = chain.size() - 1; i >= 0; i--) {
            String value = extractor.apply(chain.get(i));
            if (!isBlank(value)) {
                return value;
            }
        }
        return null;
    }

    private String firstNonBlank(List<ChatTurnContext> chain, java.util.function.Function<ChatTurnContext, String> extractor) {
        return chain.stream()
            .map(extractor)
            .filter(value -> value != null && !value.isBlank())
            .findFirst()
            .orElse(null);
    }

    private String normalizeIdentifier(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value.trim().replaceAll("[`\"]", "");
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < cleaned.length() - 1) {
            cleaned = cleaned.substring(dotIndex + 1);
        }
        return cleaned.trim().toLowerCase(Locale.ROOT);
    }

    private String singularize(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return "";
        }
        String normalized = normalizeIdentifier(tableName);
        if (normalized.endsWith("ies") && normalized.length() > 3) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }
        if (normalized.endsWith("s") && normalized.length() > 1) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String cleanWhitespace(String value) {
        return value == null ? "" : value.replaceAll("\\s+", " ").trim();
    }

    private String cap(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
