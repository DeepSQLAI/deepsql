package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.QueryResult;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.BusinessRuleMemoryService;
import com.dbaagent.service.ChatRetrievalContextService;
import com.dbaagent.service.ChatContextAssembler;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ConversationCarryoverDecision;
import com.dbaagent.service.FeedbackService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.QueryExecutionPolicyException;
import com.dbaagent.service.RetrievedContextResult;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.SchemaTableMatchUtil;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SqlExecutionPipeline;
import com.dbaagent.service.pipeline.PipelineContext;
import com.dbaagent.service.pipeline.PipelineProgressListener;
import com.dbaagent.service.pipeline.PipelineResult;
import com.dbaagent.service.pipeline.QueryGenerationPipeline;
import com.dbaagent.service.pipeline.ResolvedContext;
import com.dbaagent.util.PatternUtil;
import com.dbaagent.util.PromptIntentSignals;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.prompt.PromptTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

@Service
@Slf4j
public class UniversalChatTool extends AbstractSqlAgentTool {

    private static final Pattern SQL_CODE_BLOCK = Pattern.compile("```sql\\s*([\\s\\S]*?)\\s*```", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESTRUCTIVE_REQUEST_PATTERN = Pattern.compile("^\\s*(delete|drop|truncate|update|insert|alter)\\b", Pattern.CASE_INSENSITIVE);
    private static final List<String> TIME_KEYWORDS = List.of(
        "last ", "past ", "today", "yesterday", "this month", "month", "week", "day", "date", "daily",
        "hour", "recent", "so far", "mtd", "year"
    );
    private static final Map<String, List<String>> FACT_FAMILY_TERMS = Map.of(
        "booking", List.of(" booking ", " bookings ", " reservation ", " reservations ", " stay ", " stays "),
        "revenue", List.of(" revenue ", " revenues ", " adr ", " arr "),
        "payment", List.of(" payment ", " payments ", " payout ", " payouts ", " settlement ", " settlements "),
        "order", List.of(" order ", " orders ", " order line ", " order lines "),
        "invoice", List.of(" invoice ", " invoices ", " billing ", " billings "),
        "refund", List.of(" refund ", " refunds ", " chargeback ", " chargebacks "),
        "transaction", List.of(" transaction ", " transactions ", " txn ", " txns ")
    );

    private final QueryGenerationPipeline queryGenerationPipeline;
    private final SqlExecutionPipeline sqlExecutionPipeline;
    private final ChatContextAssembler contextAssembler;
    private final FeedbackService feedbackService;
    private final BusinessRuleMemoryService businessRuleMemoryService;
    private final SemanticModelService semanticModelService;
    private final ChatRetrievalContextService chatRetrievalContextService;
    private final AnswerVerificationService answerVerificationService;
    private final ChatClient chatClient;
    private final Resource systemPromptResource;
    private final boolean explainValidationEnabled;
    private final TemporalResolutionPolicy temporalResolutionPolicy = new TemporalResolutionPolicy();
    private final JoinPathResolutionPolicy joinPathResolutionPolicy = new JoinPathResolutionPolicy();
    private final ClarificationPolicy clarificationPolicy = new ClarificationPolicy();

    public UniversalChatTool(
            QueryExecutorService queryExecutorService,
            QueryGenerationPipeline queryGenerationPipeline,
            SqlExecutionPipeline sqlExecutionPipeline,
            ChatContextAssembler contextAssembler,
            FeedbackService feedbackService,
            BusinessRuleMemoryService businessRuleMemoryService,
            SemanticModelService semanticModelService,
            ChatRetrievalContextService chatRetrievalContextService,
            AnswerVerificationService answerVerificationService,
            ChatModel chatModel,
            @Value("classpath:prompts/dba-system-prompt.st") Resource systemPromptResource,
            @Value("${app.pipeline.explain-validation.enabled:true}") boolean explainValidationEnabled) {
        super(queryExecutorService);
        this.queryGenerationPipeline = queryGenerationPipeline;
        this.sqlExecutionPipeline = sqlExecutionPipeline;
        this.contextAssembler = contextAssembler;
        this.feedbackService = feedbackService;
        this.businessRuleMemoryService = businessRuleMemoryService;
        this.semanticModelService = semanticModelService;
        this.chatRetrievalContextService = chatRetrievalContextService;
        this.answerVerificationService = answerVerificationService;
        this.chatClient = ChatClient.builder(chatModel).build();
        this.systemPromptResource = systemPromptResource;
        this.explainValidationEnabled = explainValidationEnabled;
    }

    @Override
    public String name() {
        return "universal_chat_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        SchemaMetadata schema = context.schema();
        String taskId = resolveTaskId(step);
        String taskTitle = resolveTaskTitle(step);
        AgentTaskKind taskKind = resolveTaskKind(step);
        List<String> taskDependencies = resolveTaskDependencies(step);
        if (schema == null) {
            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                "FAILED",
                "I need live schema context before I can reason through that request safely.",
                null,
                List.of(),
                0.25,
                "Schema context missing for universal chat workflow",
                Map.of("requiresSchema", true)
            );
        }

        String question = stringValue(step.params().getOrDefault("taskQuestion", context.question()));
        if (question == null || question.isBlank()) {
            question = context.question();
        }
        boolean stepOverridesQuestion = question != null
            && !question.isBlank()
            && context.question() != null
            && !question.equals(context.question());
        String effectiveQuestion = !stepOverridesQuestion && context.effectiveQuestion() != null && !context.effectiveQuestion().isBlank()
            ? context.effectiveQuestion()
            : question;
        String routeType = String.valueOf(step.params().getOrDefault("routeType", ChatQuestionRoutingService.RouteType.GENERAL.name()));
        boolean dataRequest = Boolean.TRUE.equals(step.params().get("dataRequest")) || isDataRetrievalQuestion(effectiveQuestion);
        if (isDestructiveRequest(question)) {
            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                "CLARIFICATION",
                "I can't execute DELETE/UPDATE-style requests in chat. DeepSQL chat is read-only and safe by design. If you want, I can help you identify the affected rows with a read-only SELECT first.",
                null,
                List.of(),
                0.99,
                "Refused destructive request and kept the interaction read-only",
                Map.of("generatedSql", false, "readOnlyRefusal", true, "routeType", routeType, "dataRequest", dataRequest)
            );
        }

        Set<ChatContextAssembler.ContextType> neededContext = contextAssembler.determineNeededContext(effectiveQuestion);
        RetrievedContextResult sharedRetrievedContext = context.getMemory("sharedRetrievedContext");
        RetrievedContextResult retrievedContext = sharedRetrievedContext != null
            ? sharedRetrievedContext
            : chatRetrievalContextService.buildContext(context.connectionId(), effectiveQuestion, schema);
        Set<String> ragTableNames = retrievedContext.ragTableNames() != null
            ? retrievedContext.ragTableNames()
            : Set.of();

        String schemaContext = contextAssembler.buildSchemaContext(context.connectionId(), schema, effectiveQuestion, ragTableNames);
        String classificationContext = neededContext.contains(ChatContextAssembler.ContextType.CLASSIFICATION)
            ? contextAssembler.buildClassificationContext(context.connectionId()) : "";
        String semanticContext = neededContext.contains(ChatContextAssembler.ContextType.SEMANTIC_MODEL)
            ? contextAssembler.buildSemanticModelContext(context.connectionId(), effectiveQuestion, ragTableNames) : "";
        if (!semanticContext.isBlank()) {
            classificationContext = semanticContext + "\n" + classificationContext;
        }
        String performanceContext = contextAssembler.buildPerformanceInsightsContext(context.connectionId(), neededContext, effectiveQuestion);
        String brainContext = neededContext.contains(ChatContextAssembler.ContextType.BRAIN_INSIGHTS)
            ? contextAssembler.buildBrainContext(context.connectionId()) : "";
        String feedbackContext = buildFeedbackContext(context.connectionId(), effectiveQuestion, schema);
        String dbSpecificRules = contextAssembler.buildDatabaseSpecificRules(schema.getDbType());

        PipelineResult pipelineResult = dataRequest
            ? queryGenerationPipeline.resolveContextOnly(new PipelineContext(
                context.connectionId(),
                effectiveQuestion,
                schema.getDbType(),
                schemaContext,
                schema,
                retrievedContext,
                feedbackContext,
                classificationContext,
                performanceContext,
                brainContext,
                dbSpecificRules,
                List.of(),
                PipelineProgressListener.NOOP
            ))
            : new PipelineResult(null, null, false, ResolvedContext.empty(), com.dbaagent.service.pipeline.ColumnValueContext.empty(), null, List.of(), 0L);

        String columnValueContext = pipelineResult.columnValueContext() != null
            ? safeCap(pipelineResult.columnValueContext().formattedContext(), 2000) : "";
        String resolutionHints = pipelineResult.resolvedContext() != null
            ? safeCap(queryGenerationPipeline.buildResolutionHints(context.connectionId(), pipelineResult.resolvedContext()), 1200) : "";
        String companyKnowledgeContext = safeCap(retrievedContext.companyKnowledgeContext(), 3200);
        String trainingContext = safeCap(retrievedContext.trainingContext(), 3200);

        String[] budgeted = contextAssembler.applyTokenBudget(
            schemaContext,
            classificationContext,
            performanceContext,
            brainContext,
            feedbackContext,
            companyKnowledgeContext,
            trainingContext,
            dbSpecificRules,
            columnValueContext,
            resolutionHints
        );
        String systemPrompt = buildSystemPromptFromTemplate(
            schema.getDbType(),
            dbSpecificRules,
            budgeted[0],
            budgeted[1],
            budgeted[2],
            budgeted[3],
            budgeted[4],
            budgeted[5],
            budgeted[6],
            budgeted[7],
            budgeted[8]
        );

        List<Message> baseMessages = new ArrayList<>();
        baseMessages.add(new SystemMessage(systemPrompt));
        String routingInstruction = buildRoutingInstruction(routeType);
        if (!routingInstruction.isBlank()) {
            baseMessages.add(new SystemMessage(routingInstruction));
        }
        baseMessages.add(new SystemMessage(
            "SUBTASK BOUNDARY: Solve only this planned subtask in this step: `" + safeCap(effectiveQuestion, 220) + "`." +
                " Do not branch into another requested part, and if you generate SQL, return at most one executable SQL block."
        ));
        ConversationCarryoverDecision carryoverDecision = conversationCarryoverDecision(context);
        String resolvedConversationInstruction = buildResolvedConversationInstruction(
            context.resolvedConversationContext(),
            carryoverDecision
        );
        if (!resolvedConversationInstruction.isBlank()) {
            baseMessages.add(new SystemMessage(resolvedConversationInstruction));
        }
        String dependencyInstruction = buildDependencyInstruction(context, taskDependencies);
        if (!dependencyInstruction.isBlank()) {
            baseMessages.add(new SystemMessage(dependencyInstruction));
        }
        baseMessages.addAll(buildConversationHistoryMessages(context.conversationHistory()));
        if (dataRequest) {
            baseMessages.add(new SystemMessage(
                "DATA INTEGRITY MANDATE: This question needs real data. Generate a read-only SQL query inside ```sql``` and use only visible tables and columns."
            ));
        }
        List<Message> approvedBaseMessages = new ArrayList<>(baseMessages);
        approvedBaseMessages.add(new UserMessage(question));

        Optional<AgentToolResult> approvedReuse = tryApprovedWorkflowReuse(
            step.params(),
            context,
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            question,
            effectiveQuestion,
            schema.getDbType(),
            approvedBaseMessages
        );
        if (approvedReuse.isPresent()) {
            return approvedReuse.get();
        }

        Optional<AgentToolResult> priorSourceDisplay = tryPriorSourceSqlDisplay(
            context,
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            effectiveQuestion,
            carryoverDecision
        );
        if (priorSourceDisplay.isPresent()) {
            return priorSourceDisplay.get();
        }

        Optional<AgentToolResult> priorSourceReuse = tryPriorSourceSqlReuse(
            context,
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            question,
            effectiveQuestion,
            schema.getDbType(),
            approvedBaseMessages,
            routeType,
            dataRequest,
            carryoverDecision
        );
        if (priorSourceReuse.isPresent()) {
            return priorSourceReuse.get();
        }

        Set<String> conversationFocusTables = resolvedConversationTables(context.resolvedConversationContext(), carryoverDecision);
        Set<String> focusHints = new LinkedHashSet<>(ragTableNames);
        focusHints.addAll(conversationFocusTables);
        List<String> priorJoinConditions = resolvedConversationJoinConditions(context.resolvedConversationContext(), carryoverDecision);
        ResolvedContext resolvedContext = pipelineResult.resolvedContext();
        JoinPathResolutionPolicy.Decision joinDecision = joinPathResolutionPolicy.enhanceResolution(
            effectiveQuestion,
            schema,
            resolvedContext,
            semanticModelService != null
                ? semanticModelService.getSemanticJoins(context.connectionId(), resolvedContext.tables())
                : List.of(),
            priorJoinConditions
        );
        if (joinDecision != null && joinDecision.hasEnhancement()) {
            resolvedContext = joinDecision.resolvedContext();
        }

        SourceOfTruthDecision sourceOfTruthDecision = resolveSourceOfTruthDecision(
            context.connectionId(),
            effectiveQuestion,
            schema,
            resolvedContext,
            focusHints
        );
        Optional<SemanticTemporalEntity> semanticEntity = resolveSemanticEntity(
            context.connectionId(),
            effectiveQuestion,
            schema,
            resolvedContext,
            focusHints,
            sourceOfTruthDecision.tableName()
        );
        TemporalResolution temporalResolution = resolveTemporalContext(
            context.connectionId(),
            effectiveQuestion,
            semanticEntity.orElse(null),
            schema,
            resolvedContext,
            focusHints,
            resolvedConversationChosenTemporal(context.resolvedConversationContext(), carryoverDecision)
        );

        FilterResolution filterResolution = resolveEntityFilterContext(effectiveQuestion, semanticEntity.orElse(null));
        Map<String, Object> resolutionTrace = buildResolutionTrace(
            resolvedContext,
            semanticEntity.orElse(null),
            temporalResolution,
            filterResolution,
            joinDecision,
            sourceOfTruthDecision,
            carryoverDecision
        );

        String promptClarification = dataRequest
            ? clarificationPolicy.clarificationForUnderspecifiedPrompt(
                effectiveQuestion,
                context.resolvedConversationContext(),
                carryoverDecision
            )
            : null;
        if (promptClarification != null && !promptClarification.isBlank()) {
            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                "CLARIFICATION",
                promptClarification,
                null,
                List.of(),
                0.71,
                "Prompt clarification policy stopped early because the request was missing a concrete metric, timeframe, or comparison definition",
                mergeData(
                    resolutionTrace,
                    Map.of(
                        "routeType", routeType,
                        "dataRequest", true,
                        "generatedSql", false,
                        "clarification", true,
                        "clarificationReason", "underspecified_prompt"
                    )
                )
            );
        }

        semanticEntity.map(this::buildSemanticEntityDirective)
            .filter(directive -> directive != null && !directive.isBlank())
            .ifPresent(directive -> baseMessages.add(new SystemMessage("ENTITY RESOLUTION: " + directive)));
        if (temporalResolution.directive() != null && !temporalResolution.directive().isBlank()) {
            baseMessages.add(new SystemMessage("TEMPORAL RESOLUTION: " + temporalResolution.directive()));
        }
        if (!temporalResolution.candidateColumns().isEmpty()) {
            baseMessages.add(new SystemMessage(
                "TEMPORAL ALTERNATIVES: If SQL validation fails, reconsider these business timestamp candidates: "
                    + String.join(", ", temporalResolution.candidateColumns())
            ));
        }
        if (filterResolution.directive() != null && !filterResolution.directive().isBlank()) {
            baseMessages.add(new SystemMessage("FILTER SEMANTICS: " + filterResolution.directive()));
        }
        if (joinDecision != null && joinDecision.hasEnhancement() && !joinDecision.chosenJoinConditions().isEmpty()) {
            baseMessages.add(new SystemMessage(
                // Hyphenated so this heading does not read as `JOIN <TABLE>` to the
                // schema-agnostic guard, which scans this file for hardcoded SQL table
                // references. Identical to the model; keeps the guard strict.
                "JOIN-PATH RESOLUTION: Use these validated joins to keep all requested entities in scope: "
                    + String.join("; ", joinDecision.chosenJoinConditions())
            ));
        }
        if (sourceOfTruthDecision.hasDirective()) {
            baseMessages.add(new SystemMessage("SOURCE OF TRUTH RESOLUTION: " + sourceOfTruthDecision.directive()));
        }
        String joinedDetailDirective = buildJoinedDetailDirective(effectiveQuestion, resolvedContext, joinDecision);
        if (!joinedDetailDirective.isBlank()) {
            baseMessages.add(new SystemMessage(joinedDetailDirective));
        }
        String aggregationJoinGuard = buildAggregationJoinGuard(effectiveQuestion, resolvedContext);
        if (!aggregationJoinGuard.isBlank()) {
            baseMessages.add(new SystemMessage(aggregationJoinGuard));
        }
        String rowPreviewGuard = buildRowPreviewGuard(effectiveQuestion, taskKind);
        if (!rowPreviewGuard.isBlank()) {
            baseMessages.add(new SystemMessage(rowPreviewGuard));
        }
        baseMessages.add(new UserMessage(question));

        String firstResponse = chatClient.prompt()
            .messages(baseMessages)
            .call()
            .content();

        List<String> sqlBlocks = new ArrayList<>(sqlExecutionPipeline.extractAllSqlFromResponse(firstResponse));
        String sql = sqlBlocks.isEmpty() ? null : sqlBlocks.getFirst();

        if (sql == null || sql.isBlank()) {
            if (dataRequest && !isClarifyingQuestionResponse(firstResponse)) {
                String clarification = clarificationPolicy.clarificationForGenerationFailure(
                    unwrapTemporalDecision(temporalResolution),
                    filterResolution.needsClarification(),
                    filterResolution.clarificationMessage(),
                    joinDecision,
                    "I couldn't generate a safe SQL query from the current schema context. Please narrow the metric, entity, or timeframe."
                );
                return storeMessage(
                    context,
                    taskId,
                    taskTitle,
                    taskKind,
                    taskDependencies,
                    "CLARIFICATION",
                    clarification,
                    null,
                    List.of(),
                    0.62,
                    "No executable SQL was produced for a data request, so the tool asked the narrowest unresolved clarification",
                    mergeData(
                        resolutionTrace,
                        Map.of(
                            "routeType", routeType,
                            "dataRequest", true,
                            "generatedSql", false,
                            "clarification", true,
                            "clarificationAlternatives", clarificationPolicy.rankedAlternatives(unwrapTemporalDecision(temporalResolution), joinDecision)
                        )
                    )
                );
            }

            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                "COMPLETED",
                firstResponse,
                null,
                List.of(),
                0.72,
                "Answered without SQL using bounded schema-aware reasoning",
                mergeData(
                    resolutionTrace,
                    Map.of("routeType", routeType, "dataRequest", dataRequest, "generatedSql", false)
                )
            );
        }

        if (hasUnresolvedSqlPlaceholder(sql)) {
            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                "CLARIFICATION",
                clarificationPolicy.clarificationForGenerationFailure(
                    unwrapTemporalDecision(temporalResolution),
                    filterResolution.needsClarification(),
                    filterResolution.clarificationMessage(),
                    joinDecision,
                    "I need one clarification before I can run SQL safely: please confirm the exact business timestamp, join path, or filter semantics for this request."
                ),
                null,
                List.of(),
                0.78,
                "Blocked SQL execution because the generated SQL still contained unresolved placeholders",
                mergeData(
                    resolutionTrace,
                    Map.of("routeType", routeType, "generatedSql", true, "placeholders", true, "clarification", true)
                )
            );
        }

        return executeAndSummarize(
            context,
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            question,
            schema.getDbType(),
            sql,
            firstResponse,
            baseMessages,
            false,
            temporalResolution.directive() != null,
            resolutionTrace,
            unwrapTemporalDecision(temporalResolution),
            filterResolution,
            joinDecision
        );
    }

    private Optional<AgentToolResult> tryApprovedWorkflowReuse(
            Map<String, Object> stepParams,
            AgentExecutionContext context,
            String taskId,
            String taskTitle,
            AgentTaskKind taskKind,
            List<String> taskDependencies,
            String question,
            String effectiveQuestion,
            String dbType,
            List<Message> baseMessages) {
        String approvedSql = stringValue(stepParams.get("approvedSql"));
        String approvedQuestion = stringValue(stepParams.get("approvedQuestion"));
        if (approvedSql == null || approvedSql.isBlank() || approvedQuestion == null || approvedQuestion.isBlank()) {
            return Optional.empty();
        }

        Optional<com.dbaagent.service.pipeline.AdaptedSqlResult> adapted = queryGenerationPipeline
            .adaptApprovedExample(approvedQuestion, approvedSql, effectiveQuestion);
        if (adapted.isEmpty()) {
            return Optional.empty();
        }

        String adaptedSql = adapted.get().adaptedSql();
        if (adaptedSql == null || adaptedSql.isBlank() || hasUnresolvedSqlPlaceholder(adaptedSql)) {
            return Optional.empty();
        }

        log.info("Reusing approved workflow SQL for universal chat question '{}' based on approved question '{}'",
            question, approvedQuestion);
        return Optional.of(executeAndSummarize(
            context,
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            question,
            dbType,
            adaptedSql,
            adapted.get().syntheticResponse(),
            baseMessages,
            true,
            false,
            Map.of(),
            TemporalResolutionPolicy.Decision.none(),
            FilterResolution.none(),
            JoinPathResolutionPolicy.Decision.none(ResolvedContext.empty())
        ));
    }

    private Optional<AgentToolResult> tryPriorSourceSqlDisplay(
        AgentExecutionContext context,
        String taskId,
        String taskTitle,
        AgentTaskKind taskKind,
        List<String> taskDependencies,
        String effectiveQuestion,
        ConversationCarryoverDecision carryoverDecision
    ) {
        ResolvedConversationContext resolvedConversationContext = context.resolvedConversationContext();
        if (resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()
            || carryoverDecision == null
            || carryoverDecision.reuseMode() != ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE
            || !looksLikePriorQueryDisplayFollowUp(effectiveQuestion)) {
            return Optional.empty();
        }

        String priorSql = resolvePriorQuerySql(resolvedConversationContext);
        if (priorSql == null || priorSql.isBlank()) {
            return Optional.empty();
        }

        String message = "Here is the full SQL text from the earlier query in this thread:\n\n```sql\n"
            + priorSql
            + "\n```";
        String summary = "Returned the prior full SQL text from vault conversation context";
        double confidence = 0.95d;

        Map<String, Object> derivedValues = new LinkedHashMap<>();
        derivedValues.put("reusedPriorSourceSql", true);
        if (resolvedConversationContext.anchorQuestion() != null && !resolvedConversationContext.anchorQuestion().isBlank()) {
            derivedValues.put("anchorQuestion", resolvedConversationContext.anchorQuestion());
        }
        if (resolvedConversationContext.matchedContextId() != null && !resolvedConversationContext.matchedContextId().isBlank()) {
            derivedValues.put("matchedContextId", resolvedConversationContext.matchedContextId());
        }

        AgentTaskResult taskResult = new AgentTaskResult(
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            "COMPLETED",
            message,
            summary,
            List.of(),
            null,
            derivedValues,
            confidence
        );
        context.recordTaskResult(taskResult);
        if (!isMultiTaskMode(context)) {
            context.putMemory("universalMessage", message);
            context.putMemory("universalPrimaryResult", null);
            context.putMemory("universalConfidence", confidence);
            context.putMemory("universalResultSets", List.of(taskResult));
        }
        context.putMemory("verifiedAnswerContract", new AnswerContract(
            null,
            message,
            List.of(),
            List.of("Reused the prior SQL text stored in vault conversation context."),
            priorSql,
            List.of(),
            List.of(),
            null
        ));

        List<AgentToolArtifact> artifacts = List.of(new AgentToolArtifact(
            "task_result",
            taskId,
            Map.of(
                "taskId", taskId,
                "title", taskTitle,
                "status", "COMPLETED",
                "summary", summary,
                "reusedPriorSourceSql", true
            )
        ));
        Map<String, Object> observationData = new LinkedHashMap<>();
        observationData.put("reusedPriorSourceSql", true);
        if (resolvedConversationContext.anchorQuestion() != null && !resolvedConversationContext.anchorQuestion().isBlank()) {
            observationData.put("anchorQuestion", safeCap(resolvedConversationContext.anchorQuestion(), 220));
        }

        return Optional.of(new AgentToolResult(
            new AgentObservation(
                "universal_chat",
                summary,
                observationData
            ),
            null,
            null,
            List.of(),
            List.of(),
            artifacts,
            confidence
        ));
    }

    private Optional<AgentToolResult> tryPriorSourceSqlReuse(
            AgentExecutionContext context,
            String taskId,
            String taskTitle,
            AgentTaskKind taskKind,
            List<String> taskDependencies,
            String question,
            String effectiveQuestion,
            String dbType,
            List<Message> baseMessages,
            String routeType,
            boolean dataRequest,
            ConversationCarryoverDecision carryoverDecision) {
        ResolvedConversationContext resolvedConversationContext = context.resolvedConversationContext();
        if (!dataRequest
            || resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()
            || carryoverDecision == null
            || carryoverDecision.reuseMode() != ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE) {
            return Optional.empty();
        }

        String priorSql = resolvedConversationContext.sourceSql();
        if (priorSql == null || priorSql.isBlank()) {
            return Optional.empty();
        }

        String priorQuestion = resolvedConversationContext.anchorQuestion();
        if (priorQuestion == null || priorQuestion.isBlank()) {
            return Optional.empty();
        }

        if (!shouldReusePriorSourceSql(effectiveQuestion, resolvedConversationContext, carryoverDecision, priorSql)) {
            return Optional.empty();
        }

        Optional<com.dbaagent.service.pipeline.AdaptedSqlResult> adapted = queryGenerationPipeline
            .adaptApprovedExample(priorQuestion, priorSql, effectiveQuestion);
        if (adapted.isEmpty()) {
            return Optional.empty();
        }

        String adaptedSql = adapted.get().adaptedSql();
        if (adaptedSql == null || adaptedSql.isBlank() || hasUnresolvedSqlPlaceholder(adaptedSql)) {
            return Optional.empty();
        }

        log.info("Reusing prior conversation SQL for narrowing follow-up question '{}' based on anchor question '{}'",
            question, priorQuestion);

        Set<String> carryoverTables = resolvedConversationTables(resolvedConversationContext, carryoverDecision);
        List<String> carryoverJoinConditions = resolvedConversationJoinConditions(resolvedConversationContext, carryoverDecision);
        String carryoverTemporalColumn = resolvedConversationChosenTemporal(resolvedConversationContext, carryoverDecision);
        Map<String, Object> reuseResolutionTrace = new LinkedHashMap<>();
        reuseResolutionTrace.put("routeType", routeType);
        reuseResolutionTrace.put("dataRequest", true);
        reuseResolutionTrace.put("generatedSql", true);
        reuseResolutionTrace.put("priorSourceSqlReuse", true);
        reuseResolutionTrace.put("priorAnchorQuestion", safeCap(priorQuestion, 220));
        reuseResolutionTrace.put("reuseMode", carryoverDecision.reuseMode().name());
        reuseResolutionTrace.put("reuseConfidence", carryoverDecision.reuseConfidence());
        if (carryoverDecision.rationale() != null && !carryoverDecision.rationale().isBlank()) {
            reuseResolutionTrace.put("carryoverRationale", safeCap(carryoverDecision.rationale(), 240));
        }
        if (!carryoverTables.isEmpty()) {
            reuseResolutionTrace.put("resolvedTables", List.copyOf(carryoverTables));
        }
        if (!carryoverJoinConditions.isEmpty()) {
            reuseResolutionTrace.put("joinConditions", List.copyOf(carryoverJoinConditions));
            reuseResolutionTrace.put("chosenJoinPath", List.copyOf(carryoverJoinConditions));
        }
        if (carryoverTemporalColumn != null && !carryoverTemporalColumn.isBlank()) {
            reuseResolutionTrace.put("chosenTemporalColumn", carryoverTemporalColumn);
        }

        return Optional.of(executeAndSummarize(
            context,
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            question,
            dbType,
            adaptedSql,
            adapted.get().syntheticResponse(),
            baseMessages,
            false,
            false,
            reuseResolutionTrace,
            TemporalResolutionPolicy.Decision.none(),
            FilterResolution.none(),
            JoinPathResolutionPolicy.Decision.none(ResolvedContext.empty())
        ));
    }

    private boolean looksLikePriorQueryDisplayFollowUp(String effectiveQuestion) {
        String normalized = PromptIntentSignals.normalize(effectiveQuestion);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains("full query")
            || normalized.contains("full sql")
            || normalized.contains("query text")
            || PatternUtil.containsPattern(normalized, "\\bshow\\b.*\\b(query|sql)\\b");
    }

    private String resolvePriorQuerySql(ResolvedConversationContext resolvedConversationContext) {
        if (resolvedConversationContext == null) {
            return null;
        }
        if (resolvedConversationContext.sourceSql() != null && !resolvedConversationContext.sourceSql().isBlank()) {
            return resolvedConversationContext.sourceSql().trim();
        }
        List<AgentExecutionContext.ConversationTurn> history = resolvedConversationContext.conversationHistory();
        for (int i = history.size() - 1; i >= 0; i--) {
            AgentExecutionContext.ConversationTurn turn = history.get(i);
            if (turn == null || !"assistant".equalsIgnoreCase(turn.role()) || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String extracted = extractSqlFromAssistantContent(turn.content());
            if (extracted != null && !extracted.isBlank()) {
                return extracted;
            }
        }
        return null;
    }

    private String extractSqlFromAssistantContent(String content) {
        if (content == null || content.isBlank()) {
            return null;
        }
        java.util.regex.Matcher codeBlock = SQL_CODE_BLOCK.matcher(content);
        if (codeBlock.find()) {
            String sql = codeBlock.group(1);
            return sql == null || sql.isBlank() ? null : sql.trim();
        }
        java.util.regex.Matcher statement = Pattern.compile("(?is)\\b(select|with|insert|update|delete)\\b[\\s\\S]*").matcher(content);
        if (statement.find()) {
            return statement.group().trim();
        }
        return null;
    }

    private List<Message> buildConversationHistoryMessages(List<AgentExecutionContext.ConversationTurn> history) {
        if (history == null || history.isEmpty()) {
            return List.of();
        }

        List<Message> messages = new ArrayList<>();
        for (AgentExecutionContext.ConversationTurn turn : history) {
            if (turn == null || turn.content() == null || turn.content().isBlank()) {
                continue;
            }
            String content = safeCap(turn.content(), 700);
            if ("assistant".equalsIgnoreCase(turn.role())) {
                messages.add(new AssistantMessage(content));
            } else if ("system".equalsIgnoreCase(turn.role())) {
                messages.add(new SystemMessage(content));
            } else {
                messages.add(new UserMessage(content));
            }
        }
        return messages;
    }

    private ConversationCarryoverDecision conversationCarryoverDecision(AgentExecutionContext context) {
        if (context == null) {
            return ConversationCarryoverDecision.empty();
        }
        ConversationCarryoverDecision decision = context.getMemory("conversationCarryoverDecision");
        return decision == null ? ConversationCarryoverDecision.empty() : decision;
    }

    private String buildResolvedConversationInstruction(
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        if (resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()
            || carryoverDecision == null
            || !carryoverDecision.reusesPriorScope()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Use this resolved follow-up context instead of re-inferring prior turns from scratch.\n");
        sb.append("Carry-over mode: ").append(carryoverDecision.reuseMode().name()).append('\n');
        if (carryoverDecision.rationale() != null && !carryoverDecision.rationale().isBlank()) {
            sb.append("Carry-over rationale: ").append(safeCap(carryoverDecision.rationale(), 220)).append('\n');
        }
        if (carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE) {
            sb.append("This turn narrows the prior scope. Preserve the same tables, joins, metric, and temporal meaning unless the user explicitly changes them.\n");
        } else if (carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION) {
            sb.append("This turn answers the prior clarification. Apply the answer to the existing scope before doing any fresh table discovery.\n");
        }
        if (resolvedConversationContext.anchorQuestion() != null && !resolvedConversationContext.anchorQuestion().isBlank()) {
            sb.append("Anchor question: ").append(safeCap(resolvedConversationContext.anchorQuestion(), 220)).append('\n');
        }
        if (resolvedConversationContext.chainSummary() != null && !resolvedConversationContext.chainSummary().isBlank()) {
            sb.append("Chain summary: ").append(safeCap(resolvedConversationContext.chainSummary(), 900)).append('\n');
        }
        List<String> tables = stringList(resolvedConversationContext.resolvedContext().get("tables"));
        if (!tables.isEmpty()) {
            sb.append("Resolved tables: ").append(String.join(", ", tables.stream().limit(6).toList())).append('\n');
        }
        List<String> filters = stringList(resolvedConversationContext.resolvedContext().get("filters"));
        if (!filters.isEmpty()) {
            sb.append("Resolved filters: ").append(String.join(", ", filters.stream().limit(6).toList())).append('\n');
        }
        String timeframe = stringValue(resolvedConversationContext.resolvedContext().get("timeframe"));
        if (timeframe != null && !timeframe.isBlank()) {
            sb.append("Resolved timeframe: ").append(timeframe).append('\n');
        }
        String metric = stringValue(resolvedConversationContext.resolvedContext().get("metric"));
        if (metric != null && !metric.isBlank()) {
            sb.append("Resolved metric: ").append(metric).append('\n');
        }
        String temporalColumn = stringValue(resolvedConversationContext.resolvedContext().get("chosenTemporalColumn"));
        if (temporalColumn != null && !temporalColumn.isBlank()) {
            sb.append("Resolved temporal column: ").append(temporalColumn).append('\n');
        }
        List<String> joinConditions = stringList(resolvedConversationContext.resolvedContext().get("joinConditions"));
        if (!joinConditions.isEmpty()) {
            sb.append("Resolved join path: ").append(String.join("; ", joinConditions.stream().limit(4).toList())).append('\n');
        }
        List<String> grouping = stringList(resolvedConversationContext.resolvedContext().get("grouping"));
        if (!grouping.isEmpty()) {
            sb.append("Resolved grouping: ").append(String.join(", ", grouping.stream().limit(6).toList())).append('\n');
        }
        List<String> ordering = stringList(resolvedConversationContext.resolvedContext().get("ordering"));
        if (!ordering.isEmpty()) {
            sb.append("Resolved ordering: ").append(String.join(", ", ordering.stream().limit(6).toList())).append('\n');
        }
        if (resolvedConversationContext.selectedEntities() != null && !resolvedConversationContext.selectedEntities().isEmpty()) {
            List<String> labels = resolvedConversationContext.selectedEntities().stream()
                .map(entity -> stringValue(entity.get("displayLabel")))
                .filter(label -> label != null && !label.isBlank())
                .limit(8)
                .toList();
            if (!labels.isEmpty()) {
                sb.append("Selected result scope: ").append(String.join(", ", labels)).append('\n');
                if (carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE) {
                    sb.append("When the follow-up says these/those/them, treat this selected result scope as the exact cohort to preserve while answering the new metric or filter.\n");
                }
            }
        }
        if (resolvedConversationContext.sourceSql() != null && !resolvedConversationContext.sourceSql().isBlank()) {
            sb.append("Previous source SQL defined the scope and should be treated as the baseline query for pronouns like these/those/them.\n");
            if (carryoverDecision.reuseMode() == ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE) {
                sb.append("For narrowing follow-ups, make the smallest safe change to that prior SQL instead of rebuilding the scope from scratch.\n");
            }
            sb.append("Previous source SQL (truncated): ").append(safeCap(resolvedConversationContext.sourceSql(), 1200)).append('\n');
        }
        return sb.toString().trim();
    }

    private Set<String> resolvedConversationTables(
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        if (resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()
            || carryoverDecision == null
            || !carryoverDecision.reusesPriorScope()) {
            return Set.of();
        }
        Set<String> tables = new LinkedHashSet<>();
        stringList(resolvedConversationContext.resolvedContext().get("tables")).stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .forEach(tables::add);
        return Set.copyOf(tables);
    }

    private List<String> resolvedConversationJoinConditions(
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        if (resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()
            || carryoverDecision == null
            || !carryoverDecision.reusesPriorScope()) {
            return List.of();
        }
        return stringList(resolvedConversationContext.resolvedContext().get("joinConditions"));
    }

    private String resolvedConversationChosenTemporal(
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        if (resolvedConversationContext == null
            || !resolvedConversationContext.hasMatchedContext()
            || carryoverDecision == null
            || !carryoverDecision.reusesPriorScope()) {
            return null;
        }
        return stringValue(resolvedConversationContext.resolvedContext().get("chosenTemporalColumn"));
    }

    private Map<String, Object> buildResolutionTrace(
            ResolvedContext resolvedContext,
            SemanticTemporalEntity semanticEntity,
            TemporalResolution temporalResolution,
            FilterResolution filterResolution,
            JoinPathResolutionPolicy.Decision joinDecision,
            SourceOfTruthDecision sourceOfTruthDecision,
            ConversationCarryoverDecision carryoverDecision) {
        Map<String, Object> trace = new LinkedHashMap<>();
        LinkedHashSet<String> resolvedTables = new LinkedHashSet<>();
        LinkedHashSet<String> resolvedColumns = new LinkedHashSet<>();
        LinkedHashSet<String> joinConditions = new LinkedHashSet<>();
        if (carryoverDecision != null && carryoverDecision.reuseMode() != ConversationCarryoverDecision.ReuseMode.NONE) {
            trace.put("reuseMode", carryoverDecision.reuseMode().name());
            trace.put("reuseConfidence", carryoverDecision.reuseConfidence());
            if (carryoverDecision.rationale() != null && !carryoverDecision.rationale().isBlank()) {
                trace.put("carryoverRationale", carryoverDecision.rationale());
            }
            resolvedTables.addAll(carryoverDecision.preferredTables());
            joinConditions.addAll(carryoverDecision.preferredJoinPath());
        }
        if (resolvedContext != null) {
            resolvedTables.addAll(resolvedContext.tables());
            resolvedColumns.addAll(resolvedContext.columns().values().stream().flatMap(List::stream).distinct().toList());
            joinConditions.addAll(resolvedContext.joinConditions());
        }
        if (!resolvedTables.isEmpty()) {
            trace.put("resolvedTables", List.copyOf(resolvedTables));
        }
        if (!resolvedColumns.isEmpty()) {
            trace.put("resolvedColumns", List.copyOf(resolvedColumns));
        }
        if (!joinConditions.isEmpty()) {
            trace.put("joinConditions", List.copyOf(joinConditions));
        }
        if (semanticEntity != null && semanticEntity.table() != null) {
            trace.put("primaryEntityTable", semanticEntity.table().getName());
            trace.put("primaryEntityRole", semanticEntity.semanticTable() != null ? semanticEntity.semanticTable().getTableRole() : null);
            trace.put("sourceOfTruthMode", semanticEntity.isDerivedLike() ? "derived" : "source_of_truth");
            if (semanticEntity.semanticTable() != null && semanticEntity.semanticTable().getTemporalSemantics() != null) {
                trace.put("temporalSemantics", semanticEntity.semanticTable().getTemporalSemantics());
            }
        }
        if (temporalResolution != null) {
            if (temporalResolution.chosenColumn() != null) {
                trace.put("chosenTemporalColumn", temporalResolution.chosenColumn());
            }
            if (!temporalResolution.candidateColumns().isEmpty()) {
                trace.put("temporalCandidates", temporalResolution.candidateColumns());
            }
            if (temporalResolution.rationale() != null) {
                trace.put("temporalRationale", temporalResolution.rationale());
            }
            trace.put("temporalClarificationDeferred", temporalResolution.isAmbiguous());
        }
        if (filterResolution != null && !filterResolution.candidateColumns().isEmpty()) {
            trace.put("filterCandidates", filterResolution.candidateColumns());
            trace.put("filterClarificationDeferred", filterResolution.needsClarification());
        }
        if (joinDecision != null) {
            if (!joinDecision.chosenJoinConditions().isEmpty()) {
                joinConditions.addAll(joinDecision.chosenJoinConditions());
                trace.put("chosenJoinPath", List.copyOf(joinConditions));
            }
            if (!joinDecision.discardedAlternatives().isEmpty()) {
                trace.put("joinAlternatives", joinDecision.discardedAlternatives());
            }
            if (joinDecision.rationale() != null) {
                trace.put("joinPathRationale", joinDecision.rationale());
            }
            if (!joinDecision.addedTables().isEmpty()) {
                trace.put("joinExpandedTables", joinDecision.addedTables());
                resolvedTables.addAll(joinDecision.addedTables());
                trace.put("resolvedTables", List.copyOf(resolvedTables));
            }
        } else if (!joinConditions.isEmpty()) {
            trace.put("chosenJoinPath", List.copyOf(joinConditions));
        }
        if (sourceOfTruthDecision != null && sourceOfTruthDecision.tableName() != null && !sourceOfTruthDecision.tableName().isBlank()) {
            trace.put("chosenSourceOfTruthTable", sourceOfTruthDecision.tableName());
            if (sourceOfTruthDecision.rationale() != null) {
                trace.put("sourceOfTruthRationale", sourceOfTruthDecision.rationale());
            }
            if (!sourceOfTruthDecision.alternatives().isEmpty()) {
                trace.put("sourceOfTruthAlternatives", sourceOfTruthDecision.alternatives());
            }
        }
        return trace;
    }

    private Map<String, Object> mergeData(Map<String, Object> base, Map<String, Object> additional) {
        Map<String, Object> merged = new LinkedHashMap<>();
        if (base != null) {
            merged.putAll(base);
        }
        if (additional != null) {
            merged.putAll(additional);
        }
        return merged;
    }

    private AgentToolResult executeAndSummarize(
            AgentExecutionContext context,
            String taskId,
            String taskTitle,
            AgentTaskKind taskKind,
            List<String> taskDependencies,
            String question,
            String dbType,
            String candidateSql,
            String modelResponse,
            List<Message> baseMessages,
            boolean historyMatched,
            boolean usedTemporalHint,
            Map<String, Object> resolutionTrace,
            TemporalResolutionPolicy.Decision temporalDecision,
            FilterResolution filterResolution,
            JoinPathResolutionPolicy.Decision joinDecision) {
        String sqlToExecute = candidateSql;

        try {
            if (explainValidationEnabled) {
                var validation = queryGenerationPipeline.validateSql(context.connectionId(), sqlToExecute, dbType);
                if (!validation.valid()) {
                    throw new IllegalStateException("EXPLAIN validation: " + validation.error());
                }
            }

            QueryResult queryResult = executeQuery(context.connectionId(), sqlToExecute, 100);
            String finalMessage = summarizeQueryResults(baseMessages, modelResponse, queryResult);
            double confidence = historyMatched ? 0.97 : (usedTemporalHint ? 0.94 : 0.9);
            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                    "COMPLETED",
                    finalMessage,
                    queryResult,
                    List.of(sqlToExecute),
                    confidence,
                    historyMatched ? "Executed approved/history-matched SQL successfully"
                        : "Generated, validated, and executed SQL successfully",
                mergeData(
                    resolutionTrace,
                    Map.of(
                        "generatedSql", true,
                        "historyMatched", historyMatched,
                        "usedTemporalHint", usedTemporalHint,
                        "rowCount", queryResult != null ? queryResult.getRowCount() : 0
                    )
                )
            );
        } catch (Exception executionError) {
            if (executionError instanceof QueryExecutionPolicyException policyException
                && policyException.isChatReadOnlyBlock()) {
                return storeMessage(
                    context,
                    taskId,
                    taskTitle,
                    taskKind,
                    taskDependencies,
                    "CLARIFICATION",
                    policyException.getMessage(),
                    null,
                    sqlToExecute == null || sqlToExecute.isBlank() ? List.of() : List.of(sqlToExecute),
                    0.99,
                    "Blocked non-read-only SQL from chat at the shared execution policy layer",
                    mergeData(
                        resolutionTrace,
                        Map.of("generatedSql", true, "readOnlyRefusal", true, "errorCode", policyException.getErrorCode())
                    )
                );
            }
            String errorMessage = executionError.getMessage() != null ? executionError.getMessage() : executionError.toString();
            log.warn("Universal agent SQL execution failed for '{}': {}", question, errorMessage);

            SqlExecutionPipeline.SqlRepairResult repairResult = sqlExecutionPipeline.attemptSqlRepairAndExecute(
                context.connectionId(),
                question,
                dbType,
                baseMessages,
                modelResponse,
                sqlToExecute,
                errorMessage,
                "agentic-" + context.connectionId(),
                false,
                false,
                "",
                businessRuleMemoryService.resolveApplicableGuardrails(context.connectionId(), question, context.schema()),
                contextAssembler.buildDatabaseSpecificRules(dbType)
            );

            if (repairResult.success()) {
                double confidence = historyMatched ? 0.93 : 0.88;
                return storeMessage(
                    context,
                    taskId,
                    taskTitle,
                    taskKind,
                    taskDependencies,
                    "COMPLETED",
                    repairResult.finalResponse(),
                    repairResult.queryResult(),
                    List.of(repairResult.correctedSql()),
                    confidence,
                    "Recovered from SQL generation error by repairing and re-running the query",
                    mergeData(
                        resolutionTrace,
                        Map.of(
                            "generatedSql", true,
                            "historyMatched", historyMatched,
                            "repaired", true,
                            "rowCount", repairResult.queryResult() != null ? repairResult.queryResult().getRowCount() : 0
                        )
                    )
                );
            }

            String clarification = clarificationPolicy.clarificationForExecutionFailure(
                temporalDecision,
                filterResolution.needsClarification(),
                filterResolution.clarificationMessage(),
                joinDecision,
                errorMessage
            );
            if (clarification != null && !clarification.isBlank()
                && (joinDecision != null && joinDecision.shouldClarifyAfterFailure()
                    || temporalDecision != null && temporalDecision.shouldClarifyAfterFailure()
                    || filterResolution.needsClarification())) {
                return storeMessage(
                    context,
                    taskId,
                    taskTitle,
                    taskKind,
                    taskDependencies,
                    "CLARIFICATION",
                    clarification,
                    null,
                    sqlToExecute == null || sqlToExecute.isBlank() ? List.of() : List.of(sqlToExecute),
                    0.58,
                    "Execution and repair exhausted the safe path, so the tool asked for the remaining unresolved decision",
                    mergeData(
                        resolutionTrace,
                        Map.of(
                            "generatedSql", true,
                            "repaired", false,
                            "clarification", true,
                            "error", errorMessage,
                            "clarificationAlternatives", clarificationPolicy.rankedAlternatives(temporalDecision, joinDecision)
                        )
                    )
                );
            }

            return storeMessage(
                context,
                taskId,
                taskTitle,
                taskKind,
                taskDependencies,
                "FAILED",
                repairResult.finalResponse(),
                null,
                sqlToExecute == null || sqlToExecute.isBlank() ? List.of() : List.of(sqlToExecute),
                0.48,
                "SQL generation failed and repair could not recover a safe executable query",
                mergeData(
                    resolutionTrace,
                    Map.of("generatedSql", true, "repaired", false, "error", errorMessage)
                )
            );
        }
    }

    private AgentToolResult storeMessage(
            AgentExecutionContext context,
            String taskId,
            String taskTitle,
            AgentTaskKind taskKind,
            List<String> taskDependencies,
            String status,
            String message,
            QueryResult queryResult,
            List<String> executedQueries,
            double confidence,
            String summary,
            Map<String, Object> data) {
        Map<String, Object> observationData = new LinkedHashMap<>();
        if (data != null) {
            observationData.putAll(data);
        }
        observationData.put("status", status);
        if ("CLARIFICATION".equalsIgnoreCase(status)) {
            observationData.put("clarificationMessage", message);
        }
        Map<String, Object> derivedValues = new LinkedHashMap<>();
        derivedValues.putAll(observationData);
        if (queryResult != null) {
            derivedValues.put("resultPreview", buildResultPreview(queryResult));
        }

        PromptIntent promptIntent = context.promptIntent();
        if (promptIntent != null && promptIntent.domain() != PromptIntent.Domain.UNSUPPORTED) {
            EvidenceBundle evidence = buildEvidenceBundle(promptIntent, status, queryResult, executedQueries, confidence, observationData);
            VerificationReport verificationReport = answerVerificationService.verify(
                promptIntent,
                evidence,
                context.resolvedConversationContext()
            );
            context.recordVerificationReport(verificationReport);
            AnswerContract answerContract = new AnswerContract(
                taskTitle,
                message,
                List.of(),
                summarizeSupportingEvidence(evidence),
                executedQueries.isEmpty() ? null : executedQueries.getFirst(),
                verificationReport.notes(),
                evidence.insufficiencyMessage() != null ? List.of(evidence.insufficiencyMessage()) : List.of(),
                verificationReport.accepted() ? null : verificationFallbackPrompt(verificationReport)
            );
            context.putMemory("verifiedAnswerContract", answerContract);
            derivedValues.put("promptIntent", renderPromptIntent(promptIntent));
            derivedValues.put("evidenceBundle", renderEvidenceBundle(evidence));
            derivedValues.put("verificationReport", renderVerificationReport(verificationReport));
        }

        AgentTaskResult taskResult = new AgentTaskResult(
            taskId,
            taskTitle,
            taskKind,
            taskDependencies,
            status,
            message,
            summary,
            executedQueries,
            queryResult,
            derivedValues,
            confidence
        );
        context.recordTaskResult(taskResult);

        if (!isMultiTaskMode(context)) {
            context.putMemory("universalMessage", message);
            context.putMemory("universalPrimaryResult", queryResult);
            context.putMemory("universalConfidence", confidence);
            context.putMemory("universalResultSets", List.of(taskResult));
        }

        Map<String, Object> artifactPayload = new LinkedHashMap<>();
        artifactPayload.put("taskId", taskId);
        artifactPayload.put("title", taskTitle);
        artifactPayload.put("kind", taskKind.name());
        artifactPayload.put("status", status);
        artifactPayload.put("dependsOn", taskDependencies);
        artifactPayload.put("summary", summary);
        artifactPayload.put("message", safeCap(message, 800));
        artifactPayload.put("executedQueries", executedQueries);
        artifactPayload.put("confidence", confidence);
        if (queryResult != null) {
            artifactPayload.put("resultPreview", buildResultPreview(queryResult));
        }

        List<AgentToolArtifact> artifacts = List.of(new AgentToolArtifact(
            "task_result",
            taskId,
            artifactPayload
        ));
        return new AgentToolResult(
            new AgentObservation("universal_chat", summary, derivedValues),
            queryResult,
            executedQueries.isEmpty() ? null : executedQueries.getFirst(),
            executedQueries,
            queryResult == null ? List.of() : List.of(queryResult),
            artifacts,
            confidence
        );
    }

    private EvidenceBundle buildEvidenceBundle(
        PromptIntent promptIntent,
        String status,
        QueryResult queryResult,
        List<String> executedQueries,
        double confidence,
        Map<String, Object> data
    ) {
        EvidenceBundle.Source source = queryResult != null || !executedQueries.isEmpty()
            ? EvidenceBundle.Source.SQL_RESULT
            : promptIntent.requiresDocs() ? EvidenceBundle.Source.COMPANY_KNOWLEDGE : EvidenceBundle.Source.SEMANTIC_MODEL;
        Set<String> supportingObjectNames = extractSupportingObjectNames(data);
        if ("FAILED".equalsIgnoreCase(status) || "CLARIFICATION".equalsIgnoreCase(status)) {
            return EvidenceBundle.insufficient(
                promptIntent.domain(),
                "universal_chat",
                source,
                promptIntent.requiresSql() ? "sql_result" : "text_answer",
                data == null ? Map.of() : data,
                0.7,
                confidence,
                "runtime",
                supportingObjectNames,
                "The universal chat flow did not reach a fully verified answer state."
            );
        }
        return EvidenceBundle.sufficient(
            promptIntent.domain(),
            "universal_chat",
            source,
            promptIntent.requiresSql() ? "sql_result" : "text_answer",
            queryResult != null ? List.of(Map.of(
                "rowCount", queryResult.getRows() != null ? queryResult.getRows().size() : 0,
                "columns", queryResult.getColumns() != null ? queryResult.getColumns() : List.of()
            )) : List.of(),
            data == null ? Map.of() : data,
            queryResult != null || !executedQueries.isEmpty() ? 0.9 : 0.7,
            confidence,
            "runtime",
            executedQueries.isEmpty() ? null : executedQueries.getFirst(),
            supportingObjectNames
        );
    }

    private Set<String> extractSupportingObjectNames(Map<String, Object> data) {
        if (data == null || data.isEmpty()) {
            return Set.of();
        }
        LinkedHashSet<String> supportingObjectNames = new LinkedHashSet<>();
        collectSupportingObjectNames(data.get("resolvedTables"), supportingObjectNames);
        collectSupportingObjectNames(data.get("joinExpandedTables"), supportingObjectNames);
        collectSupportingObjectNames(data.get("matchedTables"), supportingObjectNames);
        collectSupportingObjectNames(data.get("chosenSourceOfTruthTable"), supportingObjectNames);
        collectSupportingObjectNames(data.get("primaryEntityTable"), supportingObjectNames);
        return supportingObjectNames.isEmpty() ? Set.of() : Set.copyOf(supportingObjectNames);
    }

    private void collectSupportingObjectNames(Object value, Set<String> supportingObjectNames) {
        if (value == null) {
            return;
        }
        if (value instanceof Collection<?> collection) {
            collection.stream()
                .filter(Objects::nonNull)
                .map(String::valueOf)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .forEach(supportingObjectNames::add);
            return;
        }
        String singleValue = String.valueOf(value).trim();
        if (!singleValue.isBlank()) {
            supportingObjectNames.add(singleValue);
        }
    }

    private boolean shouldReusePriorSourceSql(
        String effectiveQuestion,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision,
        String priorSql
    ) {
        String normalized = PromptIntentSignals.normalize(effectiveQuestion);
        if (normalized.isBlank()) {
            return false;
        }
        if (looksLikePureNarrowingFollowUp(normalized)) {
            return true;
        }

        String priorMetric = carryoverDecision != null && carryoverDecision.preferredMetric() != null && !carryoverDecision.preferredMetric().isBlank()
            ? carryoverDecision.preferredMetric()
            : resolvedConversationContext == null
                ? null
                : stringValue(resolvedConversationContext.resolvedContext().get("metric"));
        boolean priorHadMetric = priorMetric != null && !priorMetric.isBlank();
        String lowerPriorSql = priorSql == null ? "" : priorSql.toLowerCase(Locale.ROOT);
        boolean priorSqlLooksAggregated = lowerPriorSql.matches("(?s).*\\b(count|sum|avg|min|max)\\s*\\(.*")
            || lowerPriorSql.contains(" group by ");
        boolean currentRequestsMetric = PromptIntentSignals.hasExplicitMetric(effectiveQuestion)
            || normalized.contains(" total ")
            || normalized.contains(" per ")
            || normalized.contains(" by ");
        Set<String> requestedFamilies = requestedMetricFamilies(normalized);
        if (!requestedFamilies.isEmpty()
            && !priorScopeSupportsRequestedFamilies(requestedFamilies, lowerPriorSql, resolvedConversationContext, carryoverDecision)) {
            return false;
        }

        if (currentRequestsMetric && !priorHadMetric && !priorSqlLooksAggregated) {
            return false;
        }
        return true;
    }

    private boolean looksLikePureNarrowingFollowUp(String normalizedQuestion) {
        return normalizedQuestion.contains(" only ")
            || normalizedQuestion.contains(" just ")
            || normalizedQuestion.contains(" include ")
            || normalizedQuestion.contains(" exclude ")
            || normalizedQuestion.contains(" except ")
            || normalizedQuestion.contains(" without ")
            || normalizedQuestion.contains(" where ")
            || normalizedQuestion.contains(" status ")
            || normalizedQuestion.contains(" cancelled ")
            || normalizedQuestion.contains(" canceled ")
            || normalizedQuestion.contains(" confirmed ")
            || normalizedQuestion.contains(" pending ")
            || normalizedQuestion.contains(" failed ");
    }

    private boolean priorScopeSupportsRequestedFamilies(
        Set<String> requestedFamilies,
        String lowerPriorSql,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision
    ) {
        StringBuilder priorArtifacts = new StringBuilder(lowerPriorSql == null ? "" : lowerPriorSql);
        if (carryoverDecision != null) {
            if (carryoverDecision.preferredMetric() != null && !carryoverDecision.preferredMetric().isBlank()) {
                priorArtifacts.append(' ').append(carryoverDecision.preferredMetric().toLowerCase(Locale.ROOT));
            }
            carryoverDecision.preferredTables().forEach(table -> priorArtifacts.append(' ').append(table.toLowerCase(Locale.ROOT)));
            carryoverDecision.preferredJoinPath().forEach(join -> priorArtifacts.append(' ').append(join.toLowerCase(Locale.ROOT)));
        }
        if (resolvedConversationContext != null) {
            stringList(resolvedConversationContext.resolvedContext().get("tables"))
                .forEach(table -> priorArtifacts.append(' ').append(table.toLowerCase(Locale.ROOT)));
            stringList(resolvedConversationContext.resolvedContext().get("joinConditions"))
                .forEach(join -> priorArtifacts.append(' ').append(join.toLowerCase(Locale.ROOT)));
            appendLowercaseArtifact(priorArtifacts, resolvedConversationContext.resolvedContext().get("metric"));
            appendLowercaseArtifact(priorArtifacts, resolvedConversationContext.resolvedContext().get("chosenSourceOfTruthTable"));
            appendLowercaseArtifact(priorArtifacts, resolvedConversationContext.resolvedContext().get("primaryEntityTable"));
        }
        String lowerPriorArtifacts = priorArtifacts.toString();
        return requestedFamilies.stream()
            .allMatch(family -> FACT_FAMILY_TERMS.getOrDefault(family, List.of()).stream()
                .anyMatch(lowerPriorArtifacts::contains));
    }

    private Set<String> requestedMetricFamilies(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return Set.of();
        }
        Set<String> families = new LinkedHashSet<>();
        FACT_FAMILY_TERMS.forEach((family, terms) -> {
            if (terms.stream().anyMatch(normalizedQuestion::contains)) {
                families.add(family);
            }
        });
        return Set.copyOf(families);
    }

    private void appendLowercaseArtifact(StringBuilder priorArtifacts, Object value) {
        if (value == null) {
            return;
        }
        String text = String.valueOf(value).trim();
        if (!text.isBlank()) {
            priorArtifacts.append(' ').append(text.toLowerCase(Locale.ROOT));
        }
    }

    private List<String> summarizeSupportingEvidence(EvidenceBundle evidence) {
        if (evidence == null) {
            return List.of();
        }
        List<String> support = new ArrayList<>();
        support.add("Evidence source: " + evidence.source().name());
        if (evidence.sourceQuery() != null && !evidence.sourceQuery().isBlank()) {
            support.add("Executed SQL was used to verify the answer.");
        }
        return support;
    }

    private Map<String, Object> renderPromptIntent(PromptIntent promptIntent) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("domain", promptIntent.domain().name());
        rendered.put("taskType", promptIntent.taskType().name());
        rendered.put("subjectTypes", promptIntent.subjectTypes().stream().map(Enum::name).toList());
        rendered.put("requestedOutput", promptIntent.requestedOutput().name());
        return rendered;
    }

    private Map<String, Object> renderEvidenceBundle(EvidenceBundle evidence) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("source", evidence.source().name());
        rendered.put("evidenceKind", evidence.evidenceKind());
        rendered.put("answerType", evidence.answerType());
        rendered.put("coverage", evidence.coverage());
        rendered.put("confidence", evidence.confidence());
        rendered.put("sufficient", evidence.sufficient());
        rendered.put("insufficiencyMessage", evidence.insufficiencyMessage());
        return rendered;
    }

    private Map<String, Object> renderVerificationReport(VerificationReport verificationReport) {
        Map<String, Object> rendered = new LinkedHashMap<>();
        rendered.put("passed", verificationReport.passed());
        rendered.put("verifiedInsufficiency", verificationReport.verifiedInsufficiency());
        rendered.put("failureReason", verificationReport.failureReason());
        rendered.put("intentMatchScore", verificationReport.intentMatchScore());
        rendered.put("coverageScore", verificationReport.coverageScore());
        rendered.put("sourceStrength", verificationReport.sourceStrength().name());
        rendered.put("recommendedFallback", verificationReport.recommendedFallback().name());
        rendered.put("notes", verificationReport.notes());
        return rendered;
    }

    private String verificationFallbackPrompt(VerificationReport verificationReport) {
        if (verificationReport == null) {
            return null;
        }
        return switch (verificationReport.recommendedFallback()) {
            case SQL_REPAIR -> "I need a more specific metric, entity, or timeframe to verify this with SQL.";
            case CLARIFY -> "A bit more detail would help me verify the exact answer you need.";
            case LIVE_METADATA, PERFORMANCE_ADVISOR, NONE -> null;
        };
    }

    private String resolveTaskId(AgentPlanStep step) {
        if (step.taskId() != null && !step.taskId().isBlank()) {
            return step.taskId();
        }
        String fromParams = stringValue(step.params().get("taskId"));
        if (fromParams != null && !fromParams.isBlank()) {
            return fromParams;
        }
        return step.id();
    }

    private String resolveTaskTitle(AgentPlanStep step) {
        String fromParams = stringValue(step.params().get("taskTitle"));
        if (fromParams != null && !fromParams.isBlank()) {
            return fromParams;
        }
        return step.title();
    }

    private AgentTaskKind resolveTaskKind(AgentPlanStep step) {
        String kind = stringValue(step.params().get("taskKind"));
        if (kind == null || kind.isBlank()) {
            return AgentTaskKind.DATA_QUERY;
        }
        try {
            return AgentTaskKind.valueOf(kind.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException ignored) {
            return AgentTaskKind.DATA_QUERY;
        }
    }

    private List<String> resolveTaskDependencies(AgentPlanStep step) {
        if (step.dependsOn() != null && !step.dependsOn().isEmpty()) {
            return step.dependsOn();
        }
        return stringList(step.params().get("dependsOn"));
    }

    @SuppressWarnings("unchecked")
    private boolean isMultiTaskMode(AgentExecutionContext context) {
        List<AgentPlanTask> tasks = context.getMemory("planTasks");
        if (tasks == null) {
            return false;
        }
        long executableTaskCount = tasks.stream()
            .filter(task -> task.kind() != AgentTaskKind.SYNTHESIS)
            .count();
        return executableTaskCount > 1;
    }

    private String buildDependencyInstruction(AgentExecutionContext context, List<String> taskDependencies) {
        if (taskDependencies == null || taskDependencies.isEmpty()) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("DEPENDENCY CONTEXT: Reuse the scoped findings from earlier tasks when this subtask says things like those/them/these.\n");
        for (String dependency : taskDependencies) {
            AgentTaskResult dependencyResult = context.getTaskResult(dependency);
            if (dependencyResult == null) {
                continue;
            }
            sb.append("- ")
                .append(dependencyResult.taskId())
                .append(" (")
                .append(dependencyResult.title())
                .append(") status=")
                .append(dependencyResult.status())
                .append('\n');
            if (dependencyResult.message() != null && !dependencyResult.message().isBlank()) {
                sb.append("  Summary: ").append(safeCap(dependencyResult.message(), 500)).append('\n');
            }
            if (dependencyResult.primaryResult() != null) {
                sb.append("  Result preview:\n")
                    .append(sqlExecutionPipeline.formatQueryResultForAI(dependencyResult.primaryResult()))
                    .append('\n');
            }
        }
        return sb.toString().trim();
    }

    private Map<String, Object> buildResultPreview(QueryResult queryResult) {
        Map<String, Object> preview = new LinkedHashMap<>();
        preview.put("columns", queryResult.getColumns());
        preview.put("rows", queryResult.getRows() == null ? List.of() : queryResult.getRows().stream().limit(10).toList());
        preview.put("rowCount", queryResult.getRowCount());
        preview.put("isLimited", queryResult.getIsLimited());
        preview.put("executionTimeMs", queryResult.getExecutionTimeMs());
        return preview;
    }

    private String summarizeQueryResults(List<Message> baseMessages, String firstResponse, QueryResult queryResult) {
        List<Message> summaryMessages = new ArrayList<>(baseMessages);
        if (firstResponse != null && !firstResponse.isBlank()) {
            summaryMessages.add(new AssistantMessage(firstResponse));
        }
        summaryMessages.add(new SystemMessage(
            "The SQL query executed successfully. Use these exact results to answer the user. " +
                "Do not include another SQL block.\n\n" + sqlExecutionPipeline.formatQueryResultForAI(queryResult)
        ));
        return chatClient.prompt()
            .messages(summaryMessages)
            .call()
            .content();
    }

    private String buildFeedbackContext(String connectionId, String question, SchemaMetadata schema) {
        List<BusinessRuleMemoryService.SqlGuardrail> guardrails = new ArrayList<>(
            businessRuleMemoryService.resolveApplicableGuardrails(connectionId, question, schema)
        );
        String feedbackContext = feedbackService.buildFeedbackContext(connectionId);
        String guardrailContext = businessRuleMemoryService.buildGuardrailContext(guardrails);
        if (!guardrailContext.isBlank()) {
            feedbackContext = feedbackContext + guardrailContext;
        }
        return feedbackContext;
    }

    private String buildSystemPromptFromTemplate(
            String dbType,
            String dbSpecificRules,
            String schemaContext,
            String classificationContext,
            String performanceContext,
            String brainContext,
            String feedbackContext,
            String companyKnowledgeContext,
            String trainingContext,
            String columnValueContext,
            String resolutionHints) {
        try {
            PromptTemplate template = new PromptTemplate(systemPromptResource);
            Map<String, Object> params = new HashMap<>();
            params.put("dbType", dbType != null ? dbType.toUpperCase(Locale.ROOT) : "SQL");
            params.put("dbSpecificRules", safe(dbSpecificRules));
            params.put("schemaContext", safe(schemaContext));
            params.put("classificationContext", safe(classificationContext));
            params.put("performanceContext", safe(performanceContext));
            params.put("brainContext", safe(brainContext));
            params.put("feedbackContext", safe(feedbackContext));
            params.put("companyKnowledgeContext", safe(companyKnowledgeContext));
            params.put("trainingContext", safe(trainingContext));
            params.put("columnValueContext", safe(columnValueContext));
            params.put("resolutionHints", safe(resolutionHints));
            return template.render(params);
        } catch (Exception e) {
            log.warn("Failed to build universal chat system prompt from template: {}", e.getMessage());
            return "You are a database reasoning agent. Use only the provided schema and metadata context. " +
                "Do not invent tables, columns, or business data.\n\nSchema Context:\n" + safe(schemaContext) +
                safe(classificationContext) + safe(performanceContext) + safe(brainContext)
                + safe(feedbackContext) + safe(companyKnowledgeContext) + safe(trainingContext);
        }
    }

    private String buildRoutingInstruction(String routeType) {
        if (routeType == null || routeType.isBlank()) {
            return "";
        }
        if (ChatQuestionRoutingService.RouteType.BRAIN_METADATA.name().equalsIgnoreCase(routeType)) {
            return "ROUTING DECISION: Answer from cached metadata and live metadata catalogs only. Do not touch business tables.";
        }
        if (ChatQuestionRoutingService.RouteType.BI_QUERY.name().equalsIgnoreCase(routeType)) {
            return "ROUTING DECISION: This is a data question. Use application tables, bounded SQL, and deterministic schema reasoning.";
        }
        return "ROUTING DECISION: Use schema context first. If the question requires data, generate safe read-only SQL.";
    }

    private boolean isDestructiveRequest(String question) {
        return question != null && DESTRUCTIVE_REQUEST_PATTERN.matcher(question).find();
    }

    private boolean containsAny(String text, String... needles) {
        if (text == null) {
            return false;
        }
        for (String needle : needles) {
            if (needle != null && !needle.isBlank() && text.contains(needle.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private List<String> stringList(Object value) {
        if (value instanceof Collection<?> collection) {
            return collection.stream()
                .map(this::stringValue)
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(item -> !item.isBlank())
                .toList();
        }
        return List.of();
    }

    private Set<String> mergeFocusTables(ResolvedContext resolvedContext, Collection<String> additionalFocusTables) {
        Set<String> focusTables = new LinkedHashSet<>();
        if (resolvedContext != null && resolvedContext.tables() != null) {
            focusTables.addAll(resolvedContext.tables());
        }
        if (additionalFocusTables != null) {
            additionalFocusTables.stream()
                .filter(Objects::nonNull)
                .map(String::trim)
                .filter(value -> !value.isBlank())
                .forEach(focusTables::add);
        }
        return focusTables;
    }

    private TemporalResolution resolveTemporalContext(
            String connectionId,
            String question,
            SemanticTemporalEntity semanticEntity,
            SchemaMetadata schema,
            ResolvedContext resolvedContext) {
        return resolveTemporalContext(connectionId, question, semanticEntity, schema, resolvedContext, Set.of(), null);
    }

    private TemporalResolution resolveTemporalContext(
            String connectionId,
            String question,
            SemanticTemporalEntity semanticEntity,
            SchemaMetadata schema,
            ResolvedContext resolvedContext,
            Collection<String> additionalFocusTables,
            String priorChosenTemporalColumn) {
        if (!mentionsTimeWindow(question)) {
            return TemporalResolution.none();
        }

        SemanticTemporalEntity effectiveEntity = semanticEntity;
        if (effectiveEntity == null) {
            effectiveEntity = resolveSemanticEntity(connectionId, question, schema, resolvedContext, additionalFocusTables).orElse(null);
        }

        if (effectiveEntity != null && effectiveEntity.hasTemporalCandidate()) {
            TemporalResolution semanticResolution = resolveTemporalWithinEntity(question, effectiveEntity);
            if (!semanticResolution.isNone()) {
                return semanticResolution;
            }
        }

        List<TableMetadata> candidateTables = resolveCandidateTables(connectionId, question, schema, resolvedContext, additionalFocusTables);
        if (candidateTables.isEmpty()) {
            return TemporalResolution.none();
        }

        String lowerQuestion = question.toLowerCase(Locale.ROOT);
        List<TemporalResolutionPolicy.Candidate> candidates = new ArrayList<>();
        Map<String, String> candidateDataTypes = new HashMap<>();
        for (TableMetadata table : candidateTables) {
            if (table.getColumns() == null) {
                continue;
            }
            for (ColumnMetadata column : table.getColumns()) {
                int score = scoreTemporalColumn(lowerQuestion, table.getName(), column);
                if (score > 0) {
                    String qualifiedName = table.getName() + "." + column.getName();
                    candidateDataTypes.put(qualifiedName.toLowerCase(Locale.ROOT), column.getDataType());
                    candidates.add(new TemporalResolutionPolicy.Candidate(
                        table.getName(),
                        column.getName(),
                        score,
                        false,
                        inferTemporalSemanticLabel(table.getName(), column.getName(), null)
                    ));
                }
            }
        }

        if (candidates.isEmpty()) {
            return TemporalResolution.none();
        }
        String entityHint = effectiveEntity != null && effectiveEntity.table() != null ? effectiveEntity.table().getName() : null;
        TemporalResolutionPolicy.Decision decision = temporalResolutionPolicy.resolve(
            question,
            entityHint,
            candidates,
            priorChosenTemporalColumn
        );
        if (decision.canAttemptWithoutClarification() && decision.chosenQualifiedColumn() != null) {
            int dotIndex = decision.chosenQualifiedColumn().indexOf('.');
            if (dotIndex > 0) {
                String chosenTable = decision.chosenQualifiedColumn().substring(0, dotIndex);
                if (entityHint == null || !chosenTable.equalsIgnoreCase(entityHint)) {
                    decision = temporalResolutionPolicy.resolve(
                        question,
                        chosenTable,
                        candidates,
                        priorChosenTemporalColumn
                    );
                }
            }
        }
        TemporalResolution wrappedDecision = wrapTemporalDecision(decision);
        if (!wrappedDecision.isNone()
            && wrappedDecision.directive() != null
            && wrappedDecision.chosenColumn() != null
            && isEpochLikeTemporalColumn(
                wrappedDecision.chosenColumn(),
                candidateDataTypes.get(wrappedDecision.chosenColumn().toLowerCase(Locale.ROOT))
            )) {
            wrappedDecision = TemporalResolution.directive(
                wrappedDecision.directive()
                    + " This timestamp is stored as epoch-style numeric time, so compare it to UNIX_TIMESTAMP(...) values in WHERE clauses instead of wrapping the column in FROM_UNIXTIME(...).",
                wrappedDecision.candidateColumns(),
                wrappedDecision.rationale(),
                wrappedDecision.chosenColumn(),
                wrappedDecision.isAmbiguous()
            );
        }
        return wrappedDecision;
    }

    private TemporalResolution resolveTemporalWithinEntity(String question, SemanticTemporalEntity entity) {
        return wrapTemporalDecision(temporalResolutionPolicy.resolve(
            question,
            entity.table().getName(),
            rankEntityTemporalCandidates(question, entity),
            null
        ));
    }

    private FilterResolution resolveEntityFilterContext(String question, SemanticTemporalEntity entity) {
        if (entity == null || !isBusinessStateQuestion(question)) {
            return FilterResolution.none();
        }

        List<FilterCandidate> candidates = rankEntityFilterCandidates(question, entity);
        if (candidates.isEmpty()) {
            return FilterResolution.none();
        }

        FilterCandidate best = candidates.getFirst();
        FilterCandidate second = candidates.size() > 1 ? candidates.get(1) : null;
        if (best.score() >= 90 && (second == null || best.score() >= second.score() + 18)) {
            return FilterResolution.directive(
                "Treat `" + entity.table().getName() + "` as the primary business entity and use `"
                    + best.qualifiedName() + "` for lifecycle or state filters in this request."
            );
        }

        List<String> topCandidates = candidates.stream()
            .limit(4)
            .map(FilterCandidate::qualifiedName)
            .toList();
        return FilterResolution.clarify(
            "I need one clarification before I run SQL safely for `" + entity.table().getName()
                + "`: which column defines the business state for this request? Likely candidates are "
                + String.join(", ", topCandidates) + ".",
            topCandidates
        );
    }

    private List<FilterCandidate> rankEntityFilterCandidates(String question, SemanticTemporalEntity entity) {
        if (entity == null || entity.table() == null || entity.table().getColumns() == null) {
            return List.of();
        }

        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<FilterCandidate> candidates = new ArrayList<>();
        for (ColumnMetadata column : entity.table().getColumns()) {
            if (column == null || column.getName() == null) {
                continue;
            }
            int score = scoreEntityFilterColumn(lowerQuestion, column.getName(), entity);
            if (score > 0) {
                candidates.add(new FilterCandidate(entity.table().getName(), column.getName(), score));
            }
        }

        return new ArrayList<>(candidates.stream()
            .collect(java.util.stream.Collectors.toMap(
                candidate -> candidate.qualifiedName().toLowerCase(Locale.ROOT),
                candidate -> candidate,
                (left, right) -> left.score() >= right.score() ? left : right,
                LinkedHashMap::new
            ))
            .values()).stream()
            .sorted(Comparator.comparingInt(FilterCandidate::score)
                .reversed()
                .thenComparing(FilterCandidate::qualifiedName))
            .toList();
    }

    private int scoreEntityFilterColumn(String lowerQuestion, String columnName, SemanticTemporalEntity entity) {
        if (columnName == null || entity == null) {
            return 0;
        }

        String normalizedColumn = normalizeToken(columnName);
        if (normalizedColumn.isBlank()) {
            return 0;
        }
        if (normalizedColumn.endsWith("_id") || "id".equals(normalizedColumn) || normalizedColumn.contains("date") || normalizedColumn.contains("time")) {
            return 0;
        }

        int score = 0;
        if (entity.isPreferredFilterColumn(columnName)) {
            score += 95;
        }
        if (normalizedColumn.contains("status")) {
            score += 85;
        }
        if (normalizedColumn.contains("state")) {
            score += 75;
        }
        if (normalizedColumn.contains("lifecycle") || normalizedColumn.contains("stage")) {
            score += 70;
        }
        if (normalizedColumn.contains("active") || normalizedColumn.contains("enabled")) {
            score += 60;
        }
        if ((lowerQuestion.contains("active") || lowerQuestion.contains("inactive"))
            && (normalizedColumn.contains("status")
                || normalizedColumn.contains("state")
                || normalizedColumn.contains("active")
                || normalizedColumn.contains("enabled"))) {
            score += 25;
        }
        if (entity.hasBusinessDescription()) {
            String normalizedDescription = SchemaTableMatchUtil.normalizeQuestion(entity.semanticTable().getBusinessDescription());
            if (normalizedDescription.contains(" " + normalizedColumn + " ")) {
                score += 18;
            }
            if ((lowerQuestion.contains("active") || lowerQuestion.contains("inactive"))
                && (normalizedDescription.contains(" active ")
                    || normalizedDescription.contains(" inactive ")
                    || normalizedDescription.contains(" status ")
                    || normalizedDescription.contains(" lifecycle "))) {
                score += 14;
            }
        }
        return score;
    }

    private String buildSemanticEntityDirective(SemanticTemporalEntity entity) {
        if (entity == null || entity.table() == null) {
            return "";
        }

        StringBuilder sb = new StringBuilder();
        sb.append("Treat `").append(entity.table().getName()).append("` as the primary business entity for this question.");
        if (entity.hasBusinessDescription()) {
            sb.append(" Meaning: ").append(entity.semanticTable().getBusinessDescription());
        }
        List<String> preferredFilters = entity.preferredFilterColumns();
        if (!preferredFilters.isEmpty()) {
            sb.append(" Prefer entity-scoped filters such as ")
                .append(preferredFilters.stream()
                    .map(column -> "`" + entity.table().getName() + "." + column + "`")
                    .collect(java.util.stream.Collectors.joining(", ")))
                .append(".");
        }
        if (entity.semanticTable() != null && entity.semanticTable().getTimeColumns() != null && !entity.semanticTable().getTimeColumns().isEmpty()) {
            sb.append(" Preferred time columns: ")
                .append(entity.semanticTable().getTimeColumns().stream()
                    .map(column -> "`" + entity.table().getName() + "." + column + "`")
                    .collect(java.util.stream.Collectors.joining(", ")))
                .append(".");
        }
        if (entity.semanticTable() != null && entity.semanticTable().getTemporalSemantics() != null && !entity.semanticTable().getTemporalSemantics().isEmpty()) {
            sb.append(" Temporal meanings: ")
                .append(entity.semanticTable().getTemporalSemantics().stream()
                    .limit(4)
                    .map(item -> "`" + entity.table().getName() + "." + stringValue(item.get("column")) + "` (" + stringValue(item.get("label")) + ")")
                    .collect(java.util.stream.Collectors.joining(", ")))
                .append(".");
        }
        return sb.toString();
    }

    private Optional<SemanticTemporalEntity> resolveSemanticEntity(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext) {
        return resolveSemanticEntity(connectionId, question, schema, resolvedContext, Set.of(), null);
    }

    private Optional<SemanticTemporalEntity> resolveSemanticEntity(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext,
            Collection<String> additionalFocusTables) {
        return resolveSemanticEntity(connectionId, question, schema, resolvedContext, additionalFocusTables, null);
    }

    private Optional<SemanticTemporalEntity> resolveSemanticEntity(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext,
            Collection<String> additionalFocusTables,
            String preferredPrimaryTable) {
        if (semanticModelService == null || connectionId == null || connectionId.isBlank() || schema == null) {
            return Optional.empty();
        }

        Set<String> focusTables = new LinkedHashSet<>(mergeFocusTables(resolvedContext, additionalFocusTables));
        if (preferredPrimaryTable != null && !preferredPrimaryTable.isBlank()) {
            focusTables.add(preferredPrimaryTable);
        }
        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        if (!focusTables.isEmpty()) {
            List<SemanticTemporalEntity> focusEntities = semanticModelService.getSemanticTables(connectionId, List.copyOf(focusTables)).stream()
                .filter(Objects::nonNull)
                .filter(semanticTable -> semanticTable.getTableName() != null)
                .map(semanticTable -> resolveSchemaTable(schema, semanticTable.getTableName())
                    .map(schemaTable -> new SemanticTemporalEntity(schemaTable, semanticTable))
                    .orElse(null))
                .filter(Objects::nonNull)
                .toList();
            Optional<SemanticTemporalEntity> chosenFocusEntity = pickBestSemanticEntity(focusEntities, lowerQuestion, preferredPrimaryTable);
            if (chosenFocusEntity.isPresent()) {
                return chosenFocusEntity;
            }
        }
        List<SemanticTableModel> relevantTables = semanticModelService.findRelevantTables(connectionId, question, focusTables);
        List<SemanticTableModel> narrowedToFocus = focusTables.isEmpty()
            ? List.of()
            : relevantTables.stream()
                .filter(Objects::nonNull)
                .filter(table -> table.getTableName() != null)
                .filter(table -> focusTables.stream().anyMatch(focus -> focus.equalsIgnoreCase(table.getTableName())))
                .toList();

        List<SemanticTemporalEntity> relevantEntities = (narrowedToFocus.isEmpty() ? relevantTables : narrowedToFocus).stream()
            .filter(Objects::nonNull)
            .map(semanticTable -> resolveSchemaTable(schema, semanticTable.getTableName())
                .map(schemaTable -> new SemanticTemporalEntity(schemaTable, semanticTable))
                .orElse(null))
            .filter(Objects::nonNull)
            .toList();
        return pickBestSemanticEntity(relevantEntities, lowerQuestion, preferredPrimaryTable);
    }

    private Optional<SemanticTemporalEntity> pickBestSemanticEntity(
            Collection<SemanticTemporalEntity> entities,
            String lowerQuestion,
            String preferredPrimaryTable) {
        if (entities == null || entities.isEmpty()) {
            return Optional.empty();
        }
        return entities.stream()
            .filter(Objects::nonNull)
            .max(Comparator
                .comparingInt((SemanticTemporalEntity entity) ->
                    semanticEntityPriority(lowerQuestion, entity, preferredPrimaryTable))
                .thenComparing(entity -> entity.table().getName(), String.CASE_INSENSITIVE_ORDER));
    }

    private int semanticEntityPriority(
            String lowerQuestion,
            SemanticTemporalEntity entity,
            String preferredPrimaryTable) {
        if (entity == null || entity.table() == null) {
            return Integer.MIN_VALUE;
        }
        int score = 0;
        if (preferredPrimaryTable != null
            && !preferredPrimaryTable.isBlank()
            && preferredPrimaryTable.equalsIgnoreCase(entity.table().getName())) {
            score += 320;
        }
        score += sourceOfTruthEntityPriority(lowerQuestion, entity.table());
        score += scoreTableAgainstQuestion(lowerQuestion, entity.table());
        score += primaryEntityNamingBonus(lowerQuestion, entity.table());
        if (entity.semanticTable() != null && entity.semanticTable().getTableRole() != null) {
            String normalizedRole = entity.semanticTable().getTableRole().toLowerCase(Locale.ROOT);
            if (PromptIntentSignals.isActivityUsageQuestion(lowerQuestion) && normalizedRole.contains("event")) {
                score += 90;
            }
            if (PromptIntentSignals.isCommercialQuestion(lowerQuestion) && normalizedRole.contains("fact")) {
                score += 36;
            }
            if (isSourceOfTruthQuestion(lowerQuestion) && (normalizedRole.contains("aggregate") || normalizedRole.contains("summary"))) {
                score -= 95;
            }
        }
        if (entity.hasTemporalCandidate()) {
            score += 20;
        }
        if (entity.hasBusinessDescription()) {
            score += 10;
        }
        return score;
    }

    private int primaryEntityNamingBonus(String lowerQuestion, TableMetadata table) {
        if (lowerQuestion == null || lowerQuestion.isBlank() || table == null || table.getName() == null) {
            return 0;
        }

        List<String> tokens = new ArrayList<>(extractIdentifierTokens(table.getName()));
        if (tokens.isEmpty()) {
            return 0;
        }
        Set<String> questionTokens = extractMeaningfulTokens(lowerQuestion);
        List<String> entityTokens = tokens.stream()
            .filter(token -> !isSatelliteQualifierToken(token))
            .toList();
        boolean entityMentioned = entityTokens.stream()
            .anyMatch(questionTokens::contains);
        if (!entityMentioned) {
            return 0;
        }

        if (tokens.size() == 1) {
            return 70;
        }

        List<String> qualifierTokens = tokens.stream()
            .filter(this::isSatelliteQualifierToken)
            .toList();
        boolean mentionedSatelliteQualifier = qualifierTokens.stream()
            .anyMatch(questionTokens::contains);
        if (mentionedSatelliteQualifier) {
            return 16;
        }

        boolean satelliteLike = !qualifierTokens.isEmpty();
        return satelliteLike ? -180 : 18;
    }

    private boolean isSatelliteQualifierToken(String token) {
        return token != null && (
            token.equals("pricing")
                || token.equals("config")
                || token.equals("setting")
                || token.equals("history")
                || token.equals("log")
                || token.equals("mapping")
                || token.equals("summary")
                || token.equals("aggregate")
                || token.equals("aggregation")
                || token.equals("report")
                || token.equals("insight")
                || token.equals("analytics")
                || token.equals("status")
        );
    }

    private List<TemporalResolutionPolicy.Candidate> rankEntityTemporalCandidates(String question, SemanticTemporalEntity entity) {
        if (entity == null || entity.table() == null || entity.table().getColumns() == null) {
            return List.of();
        }

        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        List<TemporalResolutionPolicy.Candidate> candidates = new ArrayList<>();
        for (ColumnMetadata column : entity.table().getColumns()) {
            if (column == null || column.getName() == null) {
                continue;
            }
            int score = scoreTemporalColumn(lowerQuestion, entity.table().getName(), column);
            score += semanticTimeColumnBonus(lowerQuestion, column.getName(), entity);
            if (score > 0) {
                candidates.add(new TemporalResolutionPolicy.Candidate(
                    entity.table().getName(),
                    column.getName(),
                    score,
                    entity.isPreferredTimeColumn(column.getName()),
                    entity.temporalSemanticLabel(column.getName())
                ));
            }
        }

        return deduplicateTemporalCandidates(candidates);
    }

    private int semanticTimeColumnBonus(String lowerQuestion, String columnName, SemanticTemporalEntity entity) {
        if (columnName == null || entity == null) {
            return 0;
        }

        int score = 0;
        String normalizedColumn = normalizeToken(columnName);
        String semanticLabel = entity.temporalSemanticLabel(columnName);
        if (semanticLabel == null || semanticLabel.isBlank()) {
            semanticLabel = inferTemporalSemanticLabel(
                entity.table() != null ? entity.table().getName() : null,
                columnName,
                entity
            );
        }
        String normalizedLabel = semanticLabel == null ? "" : semanticLabel.toLowerCase(Locale.ROOT);
        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(lowerQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(lowerQuestion);
        if (entity.isPreferredTimeColumn(columnName)) {
            score += 42;
            if (entity.isPrimaryPreferredTimeColumn(columnName)) {
                score += 14;
            }
        }
        if (entity.hasBusinessDescription()) {
            String normalizedDescription = SchemaTableMatchUtil.normalizeQuestion(entity.semanticTable().getBusinessDescription());
            if (normalizedDescription.contains(" " + normalizedColumn + " ")) {
                score += 35;
            }
        }
        if (normalizedLabel.contains("booking/transaction")) {
            score += commercialQuestion ? 95 : 16;
        }
        if (normalizedLabel.contains("event/activity")) {
            score += activityQuestion ? 88 : 10;
        }
        if (normalizedLabel.contains("lifecycle/onboarding")) {
            score += isOnboardingQuestion(lowerQuestion) ? 110 : 6;
        }
        if (normalizedLabel.contains("cancellation/refund")
            && (lowerQuestion.contains("cancel") || lowerQuestion.contains("refund"))) {
            score += 72;
        }
        if (normalizedLabel.contains("update") && !mentionsUpdateSemantics(lowerQuestion)) {
            score -= 95;
        }
        if (isOnboardingQuestion(lowerQuestion)) {
            if (normalizedColumn.contains("subscription")) {
                score += 110;
            }
            if (normalizedColumn.contains("activation")) {
                score += 90;
            }
            if (normalizedColumn.contains("start")) {
                score += 60;
            }
            if (normalizedColumn.contains("updated")) {
                score -= 35;
            }
        }
        return score;
    }

    private TemporalResolution wrapTemporalDecision(TemporalResolutionPolicy.Decision decision) {
        if (decision == null) {
            return TemporalResolution.none();
        }
        if (!decision.canAttemptWithoutClarification() && decision.shouldClarifyAfterFailure()) {
            return TemporalResolution.clarify(decision.clarificationMessage(), decision.rankedCandidates(), decision.rationale(), null, true);
        }
        if (decision.hasDirective()) {
            return TemporalResolution.directive(
                decision.directive(),
                decision.rankedCandidates(),
                decision.rationale(),
                decision.chosenQualifiedColumn(),
                decision.ambiguous()
            );
        }
        return TemporalResolution.none();
    }

    private TemporalResolutionPolicy.Decision unwrapTemporalDecision(TemporalResolution temporalResolution) {
        if (temporalResolution == null) {
            return TemporalResolutionPolicy.Decision.none();
        }
        if (temporalResolution.chosenColumn() == null && temporalResolution.clarificationMessage() == null) {
            return TemporalResolutionPolicy.Decision.none();
        }
        return new TemporalResolutionPolicy.Decision(
            temporalResolution.chosenColumn(),
            temporalResolution.directive(),
            temporalResolution.candidateColumns(),
            temporalResolution.candidateColumns(),
            temporalResolution.isAmbiguous(),
            temporalResolution.clarificationMessage(),
            temporalResolution.rationale()
        );
    }

    private String inferTemporalSemanticLabel(String tableName, String columnName, SemanticTemporalEntity entity) {
        if (entity != null) {
            String semanticLabel = entity.temporalSemanticLabel(columnName);
            if (semanticLabel != null && !semanticLabel.isBlank()) {
                return semanticLabel;
            }
        }
        String normalizedColumn = normalizeToken(columnName);
        if (normalizedColumn.contains("occurred") || normalizedColumn.contains("logged")
            || normalizedColumn.contains("access") || normalizedColumn.contains("visit")
            || normalizedColumn.contains("session")) {
            return "event/activity time";
        }
        if (normalizedColumn.contains("booking") || normalizedColumn.contains("booked")
            || normalizedColumn.contains("paid") || normalizedColumn.contains("payment")
            || normalizedColumn.contains("invoice") || normalizedColumn.contains("charge")) {
            return "booking/transaction time";
        }
        if (normalizedColumn.contains("subscription") || normalizedColumn.contains("activation")
            || normalizedColumn.contains("start")) {
            return "lifecycle/onboarding start";
        }
        if (normalizedColumn.contains("cancel") || normalizedColumn.contains("refund")) {
            return "cancellation/refund time";
        }
        if (normalizedColumn.contains("updated") || normalizedColumn.contains("modified")) {
            return "update time";
        }
        if (normalizedColumn.contains("created")) {
            return "creation time";
        }
        if (looksLikeEventActivityTableName(tableName)) {
            return "event/activity time";
        }
        return "business date/time";
    }

    private List<TemporalResolutionPolicy.Candidate> deduplicateTemporalCandidates(List<TemporalResolutionPolicy.Candidate> candidates) {
        if (candidates == null || candidates.isEmpty()) {
            return List.of();
        }
        return new ArrayList<>(candidates.stream()
            .collect(java.util.stream.Collectors.toMap(
                candidate -> candidate.qualifiedName().toLowerCase(Locale.ROOT),
                candidate -> candidate,
                (left, right) -> left.score() >= right.score() ? left : right,
                LinkedHashMap::new
            ))
            .values()).stream()
            .sorted(Comparator.comparingInt(TemporalResolutionPolicy.Candidate::score)
                .reversed()
                .thenComparing(TemporalResolutionPolicy.Candidate::qualifiedName))
            .toList();
    }

    private boolean hasTemporalCandidate(TableMetadata table, SemanticTableModel semanticTable) {
        if (table == null || table.getColumns() == null) {
            return false;
        }

        if (semanticTable != null && semanticTable.getTimeColumns() != null && !semanticTable.getTimeColumns().isEmpty()) {
            Set<String> normalizedTimeColumns = semanticTable.getTimeColumns().stream()
                .filter(Objects::nonNull)
                .map(this::normalizeToken)
                .collect(java.util.stream.Collectors.toSet());
            boolean semanticMatch = table.getColumns().stream()
                .filter(Objects::nonNull)
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .map(this::normalizeToken)
                .anyMatch(normalizedTimeColumns::contains);
            if (semanticMatch) {
                return true;
            }
        }

        return table.getColumns().stream().anyMatch(this::isTemporalLikeColumn);
    }

    private Optional<TableMetadata> resolveSchemaTable(SchemaMetadata schema, String requestedTableName) {
        if (schema == null || schema.getTables() == null || requestedTableName == null || requestedTableName.isBlank()) {
            return Optional.empty();
        }

        return schema.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null && table.getName().equalsIgnoreCase(requestedTableName))
            .max(this::compareSchemaTableVariants);
    }

    private int compareSchemaTableVariants(TableMetadata left, TableMetadata right) {
        if (left == null || right == null) {
            return left == null ? -1 : 1;
        }
        long leftRows = left.getRowCount() != null ? left.getRowCount() : 0L;
        long rightRows = right.getRowCount() != null ? right.getRowCount() : 0L;
        int rowCompare = Long.compare(leftRows, rightRows);
        if (rowCompare != 0) {
            return rowCompare;
        }

        int leftColumns = left.getColumns() != null ? left.getColumns().size() : 0;
        int rightColumns = right.getColumns() != null ? right.getColumns().size() : 0;
        int columnCompare = Integer.compare(leftColumns, rightColumns);
        if (columnCompare != 0) {
            return columnCompare;
        }

        boolean leftUpper = left.getName().equals(left.getName().toUpperCase(Locale.ROOT));
        boolean rightUpper = right.getName().equals(right.getName().toUpperCase(Locale.ROOT));
        if (leftUpper != rightUpper) {
            return leftUpper ? 1 : -1;
        }
        return right.getName().compareToIgnoreCase(left.getName()) * -1;
    }

    private List<TableMetadata> resolveCandidateTables(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext) {
        return resolveCandidateTables(connectionId, question, schema, resolvedContext, Set.of());
    }

    private List<TableMetadata> resolveCandidateTables(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext,
            Collection<String> additionalFocusTables) {
        if (schema == null || schema.getTables() == null) {
            return List.of();
        }

        List<TableMetadata> semanticCandidates = resolveSemanticCandidateTables(
            connectionId,
            question,
            schema,
            resolvedContext,
            additionalFocusTables
        );
        Map<String, Integer> semanticCandidateRanks = new LinkedHashMap<>();
        for (int index = 0; index < semanticCandidates.size(); index++) {
            TableMetadata candidate = semanticCandidates.get(index);
            if (candidate != null && candidate.getName() != null) {
                semanticCandidateRanks.put(candidate.getName().toLowerCase(Locale.ROOT), index);
            }
        }

        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean timeWindowQuestion = mentionsTimeWindow(question);
        boolean aggregateQuestion = looksLikeAggregateQuestion(lowerQuestion);
        Set<String> resolvedNames = mergeFocusTables(resolvedContext, additionalFocusTables).stream()
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());

        Map<String, TableScore> bestByCanonicalName = new LinkedHashMap<>();
        for (TableMetadata table : schema.getTables()) {
            if (table == null || table.getName() == null) {
                continue;
            }
            int score = scoreTableAgainstQuestion(lowerQuestion, table);
            score += tableShapeBonus(table, aggregateQuestion, lowerQuestion);
            if (timeWindowQuestion) {
                score += temporalTableBonus(lowerQuestion, table);
            }
            if (resolvedNames.contains(table.getName().toLowerCase(Locale.ROOT))) {
                score += resolvedContextBoost(resolvedNames.size());
            }
            Integer semanticRank = semanticCandidateRanks.get(table.getName().toLowerCase(Locale.ROOT));
            if (semanticRank != null) {
                score += semanticCandidateBoost(semanticRank);
            }
            score += tablePopulationBonus(table);
            if (score <= 0) {
                continue;
            }

            String canonicalKey = table.getName().toLowerCase(Locale.ROOT);
            TableScore candidate = new TableScore(table, score);
            TableScore existing = bestByCanonicalName.get(canonicalKey);
            if (existing == null || compareTableScore(candidate, existing) > 0) {
                bestByCanonicalName.put(canonicalKey, candidate);
            }
        }

        List<TableMetadata> scoredTables = bestByCanonicalName.values().stream()
            .sorted(this::compareTableScoreDescending)
            .limit(3)
            .map(TableScore::table)
            .toList();
        if (!scoredTables.isEmpty()) {
            return scoredTables;
        }

        return schema.getTables().stream().limit(3).toList();
    }

    private boolean mentionsTimeWindow(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        return TIME_KEYWORDS.stream().anyMatch(normalized::contains);
    }

    private int scoreTemporalColumn(String lowerQuestion, String tableName, ColumnMetadata column) {
        if (column == null || column.getName() == null) {
            return 0;
        }
        String name = column.getName().toLowerCase(Locale.ROOT);
        if (name.contains("timezone") || name.contains("birth") || name.contains("dob")) {
            return 0;
        }

        String dataType = column.getDataType() == null ? "" : column.getDataType().toLowerCase(Locale.ROOT);
        if (!isStrictTemporalColumn(name, dataType)) {
            return 0;
        }

        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(lowerQuestion);
        boolean declineQuestion = PromptIntentSignals.isBehavioralDeclineQuestion(lowerQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(lowerQuestion);
        boolean eventActivityTable = looksLikeEventActivityTableName(tableName);
        boolean commercialTable = looksLikeCommercialTableName(tableName);
        int score = 0;

        if (dataType.contains("date") || dataType.contains("time")) {
            score += 55;
        } else {
            score += 32;
        }
        if (name.contains("created")) {
            score += 20;
        }
        if (name.contains("updated") || name.contains("last_updated")) {
            score += 6;
        }
        if (name.contains("occurred") || name.contains("logged") || name.contains("event") || name.contains("activity")) {
            score += activityQuestion ? 75 : 35;
        }
        if (name.contains("access") || name.contains("visit") || name.contains("login") || name.contains("session") || name.contains("seen")) {
            score += activityQuestion ? 68 : 28;
        }
        if (name.contains("booking") || name.contains("booked") || name.contains("paid") || name.contains("processed")
            || name.contains("refund") || name.contains("cancel") || name.contains("invoice") || name.contains("bill")) {
            score += commercialQuestion ? 58 : 12;
        }
        if (lowerQuestion.contains(" made ") && (name.contains("made") || name.contains("booking") || name.contains("booked"))) {
            score += 26;
        }
        if (name.contains("subscription")) {
            score += isOnboardingQuestion(lowerQuestion) ? 95 : 20;
        }
        if (name.contains("activation")) {
            score += isOnboardingQuestion(lowerQuestion) ? 85 : 18;
        }
        if (name.contains("start")) {
            score += isOnboardingQuestion(lowerQuestion) ? 55 : 8;
        }
        if (name.contains("checkin")) {
            score += lowerQuestion.contains("arrival") || lowerQuestion.contains("checkin") ? 60 : 15;
        }
        if (name.contains("checkout")) {
            score += lowerQuestion.contains("departure") || lowerQuestion.contains("checkout") ? 60 : 15;
        }
        if (name.contains("actual_")) {
            score += 5;
        }
        if (name.contains("display_")) {
            score -= 5;
        }
        if (eventActivityTable) {
            score += activityQuestion ? 34 : 10;
            if (declineQuestion) {
                score += 14;
            }
        }
        if (commercialTable && activityQuestion && !commercialQuestion) {
            score -= 40;
        }
        if (lowerQuestion.contains("updated") && name.contains("updated")) {
            score += 35;
        }
        if (!mentionsUpdateSemantics(lowerQuestion) && (name.contains("updated") || name.contains("last_updated"))) {
            score -= 55;
        }
        if ((name.contains("checkin") || name.contains("checkout"))
            && !lowerQuestion.contains("checkin")
            && !lowerQuestion.contains("checkout")
            && !lowerQuestion.contains("arrival")
            && !lowerQuestion.contains("departure")) {
            score -= 15;
        }
        return score;
    }

    private int scoreTableAgainstQuestion(String lowerQuestion, TableMetadata table) {
        if (table == null || table.getName() == null) {
            return 0;
        }
        Set<String> questionTokens = extractMeaningfulTokens(lowerQuestion);
        if (questionTokens.isEmpty()) {
            return 0;
        }

        int score = 0;
        Set<String> matchedQuestionTokens = new LinkedHashSet<>();

        for (String token : extractIdentifierTokens(table.getName())) {
            if (questionTokens.contains(token)) {
                score += 35;
                matchedQuestionTokens.add(token);
            }
        }

        if (table.getColumns() != null) {
            Set<String> matchedColumnTokens = new LinkedHashSet<>();
            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                for (String token : extractIdentifierTokens(column.getName())) {
                    if (questionTokens.contains(token) && matchedColumnTokens.add(token)) {
                        score += 18;
                        matchedQuestionTokens.add(token);
                    }
                }
            }
        }

        score += matchedQuestionTokens.size() * 20;
        score += wholeTablePhraseBonus(lowerQuestion, table);
        score += entitySpecificityAdjustment(lowerQuestion, table);
        score += compoundColumnMatchBonus(lowerQuestion, table);
        score += intentAlignedTableBonus(lowerQuestion, table);
        score += onboardingTableBias(lowerQuestion, table);
        score += sourceOfTruthTableBonus(lowerQuestion, table);
        score += requestedDetailCoverageBonus(lowerQuestion, table);
        return score;
    }

    private List<TableMetadata> resolveSemanticCandidateTables(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext) {
        return resolveSemanticCandidateTables(connectionId, question, schema, resolvedContext, Set.of());
    }

    private List<TableMetadata> resolveSemanticCandidateTables(
            String connectionId,
            String question,
            SchemaMetadata schema,
            ResolvedContext resolvedContext,
            Collection<String> additionalFocusTables) {
        if (semanticModelService == null || connectionId == null || connectionId.isBlank() || schema == null) {
            return List.of();
        }

        Set<String> focusTables = mergeFocusTables(resolvedContext, additionalFocusTables);

        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean timeWindowQuestion = mentionsTimeWindow(question);
        boolean aggregateQuestion = looksLikeAggregateQuestion(lowerQuestion);
        Set<String> resolvedNames = focusTables.stream()
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());

        Map<String, TableScore> bestByCanonicalName = new LinkedHashMap<>();
        for (SemanticTableModel semanticTable : semanticModelService.findRelevantTables(connectionId, question, focusTables)) {
            resolveSchemaTable(schema, semanticTable.getTableName()).ifPresent(table -> {
                String canonical = table.getName().toLowerCase(Locale.ROOT);
                int score = scoreTableAgainstQuestion(lowerQuestion, table);
                score += tableShapeBonus(table, aggregateQuestion, lowerQuestion);
                if (timeWindowQuestion) {
                    score += temporalTableBonus(lowerQuestion, table);
                }
                if (resolvedNames.contains(canonical)) {
                    score += resolvedContextBoost(resolvedNames.size());
                }
                score += tablePopulationBonus(table);

                TableScore candidate = new TableScore(table, score);
                TableScore existing = bestByCanonicalName.get(canonical);
                if (existing == null || compareTableScore(candidate, existing) > 0) {
                    bestByCanonicalName.put(canonical, candidate);
                }
            });
        }
        return bestByCanonicalName.values().stream()
            .sorted(this::compareTableScoreDescending)
            .limit(3)
            .map(TableScore::table)
            .toList();
    }

    private int entitySpecificityAdjustment(String lowerQuestion, TableMetadata table) {
        if (table == null || table.getName() == null || table.getName().isBlank()) {
            return 0;
        }

        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(lowerQuestion);
        List<String> tokens = extractIdentifierTokens(table.getName()).stream().toList();
        if (tokens.isEmpty()) {
            return 0;
        }

        String baseToken = tokens.getFirst();
        boolean baseMentioned = normalizedQuestion.contains(" " + baseToken + " ");
        if (!baseMentioned) {
            return 0;
        }

        if (tokens.size() == 1) {
            return 80;
        }

        int qualifierScore = 0;
        boolean qualifierMentioned = false;
        for (int index = 1; index < tokens.size(); index++) {
            String qualifier = tokens.get(index);
            if (normalizedQuestion.contains(" " + qualifier + " ")) {
                qualifierMentioned = true;
                qualifierScore += 28;
            }
        }
        if (qualifierMentioned) {
            return qualifierScore;
        }

        if (isSourceOfTruthQuestion(lowerQuestion) && !isDerivedLikeTableName(table.getName())) {
            return 12;
        }
        return -28;
    }

    private int wholeTablePhraseBonus(String lowerQuestion, TableMetadata table) {
        if (lowerQuestion == null || lowerQuestion.isBlank() || table == null || table.getName() == null) {
            return 0;
        }
        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(lowerQuestion);
        String spacedTableName = table.getName().toLowerCase(Locale.ROOT).replace('_', ' ');
        if (normalizedQuestion.contains(" " + spacedTableName + " ")) {
            return 56;
        }
        String singularizedPhrase = extractIdentifierTokens(table.getName()).stream()
            .map(this::normalizeToken)
            .filter(token -> token != null && !token.isBlank())
            .reduce((left, right) -> left + " " + right)
            .orElse("");
        if (!singularizedPhrase.isBlank() && normalizedQuestion.contains(" " + singularizedPhrase + " ")) {
            return 42;
        }
        return 0;
    }

    private int compoundColumnMatchBonus(String lowerQuestion, TableMetadata table) {
        if (lowerQuestion == null || lowerQuestion.isBlank() || table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }

        Set<String> questionTokens = new LinkedHashSet<>(extractMeaningfulTokens(lowerQuestion));
        questionTokens.removeAll(Set.of(
            "show", "list", "detail", "details", "last", "past", "time", "date", "trend",
            "report", "overview", "analysis", "top", "bottom", "many", "count", "average",
            "avg", "sum", "total"
        ));
        if (questionTokens.isEmpty()) {
            return 0;
        }

        int best = 0;
        for (ColumnMetadata column : table.getColumns()) {
            if (column == null || column.getName() == null) {
                continue;
            }
            Set<String> columnTokens = extractIdentifierTokens(column.getName());
            int matchCount = (int) columnTokens.stream().filter(questionTokens::contains).count();
            if (matchCount == 0) {
                continue;
            }

            int score = matchCount * 12;
            if (matchCount >= 2) {
                score += 92;
            }
            if (matchCount >= 3) {
                score += 28;
            }
            if (isMeasureLikeColumn(column) && !isAggregateLikeMeasureColumn(column)) {
                score += 18;
            }
            if (isAggregateLikeMeasureColumn(column)) {
                score -= 36;
            }
            if (isDescriptiveColumn(column.getName())) {
                score += 10;
            }
            best = Math.max(best, score);
        }
        return best;
    }

    private int semanticCandidateBoost(int rank) {
        return switch (rank) {
            case 0 -> 42;
            case 1 -> 30;
            case 2 -> 22;
            default -> 14;
        };
    }

    private int onboardingTableBias(String lowerQuestion, TableMetadata table) {
        if (!isOnboardingQuestion(lowerQuestion) || table == null || table.getName() == null) {
            return 0;
        }

        Set<String> tableTokens = extractIdentifierTokens(table.getName());
        int score = 0;
        if (tableTokens.size() <= 1) {
            score += 70;
        } else if (!containsQualifierMention(lowerQuestion, tableTokens)) {
            score -= 65;
        }

        if (table.getColumns() != null) {
            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                String lowerColumn = column.getName().toLowerCase(Locale.ROOT);
                if (lowerColumn.contains("subscription")) {
                    score += 70;
                }
                if (lowerColumn.contains("activation")) {
                    score += 55;
                }
                if (lowerColumn.contains("updated")) {
                    score -= 20;
                }
            }
        }
        return score;
    }

    private boolean containsQualifierMention(String lowerQuestion, Set<String> tableTokens) {
        if (lowerQuestion == null || tableTokens == null || tableTokens.size() <= 1) {
            return false;
        }
        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(lowerQuestion);
        boolean qualifierMentioned = false;
        boolean first = true;
        for (String token : tableTokens) {
            if (first) {
                first = false;
                continue;
            }
            if (normalizedQuestion.contains(" " + token + " ")) {
                qualifierMentioned = true;
            }
        }
        return qualifierMentioned;
    }

    private boolean isEventActivityTable(TableMetadata table) {
        if (table == null) {
            return false;
        }
        return looksLikeEventActivityTableName(table.getName())
            || (table.getColumns() != null && table.getColumns().stream()
                .filter(Objects::nonNull)
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .map(this::normalizeToken)
                .anyMatch(this::isEventActivityColumnToken));
    }

    private boolean isCommercialTable(TableMetadata table) {
        if (table == null) {
            return false;
        }
        return looksLikeCommercialTableName(table.getName())
            || (table.getColumns() != null && table.getColumns().stream()
                .filter(Objects::nonNull)
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .map(this::normalizeToken)
                .anyMatch(this::isCommercialColumnToken));
    }

    private boolean looksLikeEventActivityTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        Set<String> tokens = extractIdentifierTokens(tableName);
        return tokens.stream().anyMatch(token ->
            token.equals("log")
                || token.equals("event")
                || token.equals("usage")
                || token.equals("activity")
                || token.equals("session")
                || token.equals("audit")
                || token.equals("visit")
                || token.equals("access")
        );
    }

    private boolean looksLikeCommercialTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        Set<String> tokens = extractIdentifierTokens(tableName);
        return tokens.stream().anyMatch(token ->
            token.equals("booking")
                || token.equals("payment")
                || token.equals("invoice")
                || token.equals("order")
                || token.equals("ledger")
                || token.equals("revenue")
                || token.equals("commission")
                || token.equals("refund")
                || token.equals("charge")
                || token.equals("billing")
        );
    }

    private boolean isDerivedLikeTableName(String tableName) {
        if (tableName == null || tableName.isBlank()) {
            return false;
        }
        Set<String> tokens = extractIdentifierTokens(tableName);
        return tokens.stream().anyMatch(token ->
            token.equals("aggregate")
                || token.equals("aggregation")
                || token.equals("summary")
                || token.equals("rollup")
                || token.equals("trend")
                || token.equals("report")
                || token.equals("insight")
                || token.equals("analytics")
                || token.equals("snapshot")
                || token.equals("derived")
                || token.equals("stat")
        );
    }

    private boolean isEventActivityColumnToken(String token) {
        return token != null && (
            token.equals("event")
                || token.equals("usage")
                || token.equals("activity")
                || token.equals("session")
                || token.equals("login")
                || token.equals("logout")
                || token.equals("visit")
                || token.equals("access")
                || token.equals("action")
                || token.equals("audit")
                || token.equals("actor")
                || token.equals("user")
                || token.equals("member")
                || token.equals("device")
                || token.equals("source")
        );
    }

    private boolean isCommercialColumnToken(String token) {
        return token != null && (
            token.equals("booking")
                || token.equals("payment")
                || token.equals("invoice")
                || token.equals("order")
                || token.equals("ledger")
                || token.equals("revenue")
                || token.equals("commission")
                || token.equals("refund")
                || token.equals("charge")
                || token.equals("billing")
                || token.equals("price")
                || token.equals("amount")
                || token.equals("fee")
        );
    }

    private int intentAlignedTableBonus(String lowerQuestion, TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return 0;
        }

        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(lowerQuestion);
        boolean declineQuestion = PromptIntentSignals.isBehavioralDeclineQuestion(lowerQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(lowerQuestion);
        boolean aggregateQuestion = looksLikeAggregateQuestion(lowerQuestion);
        boolean eventActivityTable = isEventActivityTable(table);
        boolean commercialTable = isCommercialTable(table);
        boolean hasMeasure = table.getColumns().stream().anyMatch(this::isMeasureLikeColumn);
        boolean hasTemporal = table.getColumns().stream().anyMatch(this::isTemporalLikeColumn);

        int score = 0;
        if (activityQuestion) {
            if (eventActivityTable) {
                score += 95;
                if (hasTemporal) {
                    score += 25;
                }
                if (declineQuestion) {
                    score += 22;
                }
            }
            if (commercialTable && !commercialQuestion) {
                score -= 80;
            }
            if (hasMeasure && !commercialQuestion) {
                score -= 24;
            }
        } else if (aggregateQuestion) {
            if (hasMeasure) {
                score += commercialQuestion ? 60 : 30;
            } else if (commercialQuestion) {
                score -= 55;
            } else {
                score -= 18;
            }
        }
        if (hasMeasure && hasTemporal) {
            score += 18;
        }
        if (commercialQuestion && commercialTable) {
            score += 48;
        }
        if (eventActivityTable && !activityQuestion && commercialQuestion) {
            score -= 22;
        }
        return score;
    }

    private boolean mentionsUpdateSemantics(String lowerQuestion) {
        return lowerQuestion != null && (
            lowerQuestion.contains("updated")
                || lowerQuestion.contains("modified")
                || lowerQuestion.contains("last updated")
                || lowerQuestion.contains("recently changed")
        );
    }

    private boolean isBusinessStateQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(question);
        return normalizedQuestion.contains(" active ")
            || normalizedQuestion.contains(" inactive ")
            || normalizedQuestion.contains(" enabled ")
            || normalizedQuestion.contains(" disabled ")
            || normalizedQuestion.contains(" status ")
            || normalizedQuestion.contains(" state ")
            || normalizedQuestion.contains(" lifecycle ")
            || normalizedQuestion.contains(" stage ")
            || normalizedQuestion.contains(" live ");
    }

    private boolean isOnboardingQuestion(String lowerQuestion) {
        return lowerQuestion != null && (
            lowerQuestion.contains("onboard")
                || lowerQuestion.contains("onboarding")
                || lowerQuestion.contains("subscription")
                || lowerQuestion.contains("activation")
                || lowerQuestion.contains("activated")
                || lowerQuestion.contains("contract started")
                || lowerQuestion.contains("contract start")
        );
    }

    private int tableShapeBonus(TableMetadata table, boolean aggregateQuestion, String lowerQuestion) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }

        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(lowerQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(lowerQuestion);
        boolean hasIdentifier = table.getColumns().stream().anyMatch(this::isIdentifierLikeColumn);
        boolean hasTemporal = table.getColumns().stream().anyMatch(this::isTemporalLikeColumn);
        boolean hasMeasure = table.getColumns().stream().anyMatch(this::isMeasureLikeColumn);
        boolean eventActivityTable = isEventActivityTable(table);
        boolean commercialTable = isCommercialTable(table);

        int score = 0;
        if (aggregateQuestion) {
            if (hasMeasure) {
                score += 25;
            } else {
                score -= 20;
            }
        }
        if (hasTemporal) {
            score += 15;
        }
        if (hasIdentifier) {
            score += 10;
        }
        if (hasIdentifier && hasTemporal && hasMeasure) {
            score += 30;
        }
        if (activityQuestion) {
            if (eventActivityTable) {
                score += 55;
            }
            if (hasIdentifier && hasTemporal) {
                score += 18;
            }
            if (commercialTable && !commercialQuestion) {
                score -= 40;
            }
        }
        return score;
    }

    private int sourceOfTruthTableBonus(String lowerQuestion, TableMetadata table) {
        if (table == null || table.getName() == null) {
            return 0;
        }
        boolean countQuestion = lowerQuestion.contains("count") || lowerQuestion.contains("how many");
        boolean detailQuestion = lowerQuestion.contains(" detail ")
            || lowerQuestion.contains(" details ")
            || lowerQuestion.contains(" list ")
            || lowerQuestion.contains(" show ")
            || lowerQuestion.contains(" email ")
            || lowerQuestion.contains(" country ")
            || lowerQuestion.contains(" name ");
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(lowerQuestion);
        boolean trendSummaryQuestion = lowerQuestion.contains(" summary ")
            || lowerQuestion.contains(" pattern ")
            || lowerQuestion.contains(" report ")
            || lowerQuestion.contains(" overview ");
        boolean derivedLike = isDerivedLikeTableName(table.getName());
        boolean commercialTable = isCommercialTable(table);
        int score = 0;
        if ((countQuestion || detailQuestion || commercialQuestion) && !trendSummaryQuestion) {
            if (derivedLike) {
                score -= 125;
            }
            if (hasAggregateLikeMeasuresOnly(table)) {
                score -= 78;
            }
            if (hasRawBusinessMeasure(table)) {
                score += 24;
            }
            score += sourceOfTruthMeasureAlignmentBonus(lowerQuestion, table);
            score += sourceOfTruthAttributeSpecificityBonus(lowerQuestion, table);
            if (commercialTable) {
                score += countQuestion ? 48 : 24;
            }
            if (!derivedLike && countQuestion && table.getColumns() != null && table.getColumns().stream().anyMatch(this::isIdentifierLikeColumn)) {
                score += 18;
            }
        }
        if (trendSummaryQuestion && derivedLike) {
            score += 30;
        }
        return score;
    }

    private int sourceOfTruthEntityPriority(String lowerQuestion, TableMetadata table) {
        if (!isSourceOfTruthQuestion(lowerQuestion) || table == null) {
            return 0;
        }
        int score = 0;
        if (hasRawBusinessMeasure(table)) {
            score += 90;
        }
        if (hasAggregateLikeMeasuresOnly(table)) {
            score -= 120;
        }
        if (!isDerivedLikeTableName(table.getName())) {
            score += 18;
        }
        if (table.getColumns() != null && table.getColumns().stream().anyMatch(this::isIdentifierLikeColumn)) {
            score += 12;
        }
        return score;
    }

    private SourceOfTruthDecision resolveSourceOfTruthDecision(
        String connectionId,
        String question,
        SchemaMetadata schema,
        ResolvedContext resolvedContext,
        Collection<String> additionalFocusTables
    ) {
        if (!isSourceOfTruthQuestion(PromptIntentSignals.normalize(question))
            || schema == null
            || resolvedContext == null
            || resolvedContext.tables() == null
            || resolvedContext.tables().isEmpty()) {
            return SourceOfTruthDecision.none();
        }

        Map<String, TableMetadata> candidatesByName = new LinkedHashMap<>();
        Map<String, SemanticTableModel> semanticTablesByName = new LinkedHashMap<>();
        resolvedContext.tables().stream()
            .map(tableName -> resolveSchemaTable(schema, tableName).orElse(null))
            .filter(Objects::nonNull)
            .forEach(table -> candidatesByName.putIfAbsent(table.getName().toLowerCase(Locale.ROOT), table));
        if (semanticModelService != null && connectionId != null && !connectionId.isBlank()) {
            Set<String> focusTables = mergeFocusTables(resolvedContext, additionalFocusTables);
            semanticModelService.findRelevantTables(connectionId, question, focusTables).stream()
                .filter(Objects::nonNull)
                .filter(semanticTable -> semanticTable.getTableName() != null)
                .forEach(semanticTable -> semanticTablesByName.putIfAbsent(
                    semanticTable.getTableName().toLowerCase(Locale.ROOT),
                    semanticTable
                ));
            semanticTablesByName.values().stream()
                .map(semanticTable -> resolveSchemaTable(schema, semanticTable.getTableName()).orElse(null))
                .filter(Objects::nonNull)
                .forEach(table -> candidatesByName.putIfAbsent(table.getName().toLowerCase(Locale.ROOT), table));
            semanticModelService.getSemanticTables(connectionId, candidatesByName.values().stream().map(TableMetadata::getName).toList()).stream()
                .filter(Objects::nonNull)
                .filter(semanticTable -> semanticTable.getTableName() != null)
                .forEach(semanticTable -> semanticTablesByName.putIfAbsent(
                    semanticTable.getTableName().toLowerCase(Locale.ROOT),
                    semanticTable
                ));
        }
        List<TableMetadata> candidates = List.copyOf(candidatesByName.values());
        if (candidates.size() < 2) {
            return SourceOfTruthDecision.none();
        }

        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        boolean anyResolvedRawMeasureCandidate = candidates.stream()
            .anyMatch(table -> hasResolvedRawMeasureColumn(resolvedContext, table));
        List<TableScore> ranked = candidates.stream()
            .map(table -> new TableScore(
                table,
                scoreSourceOfTruthCandidate(
                    lowerQuestion,
                    table,
                    semanticTablesByName.get(table.getName().toLowerCase(Locale.ROOT)),
                    resolvedContext,
                    anyResolvedRawMeasureCandidate
                )))
            .sorted(this::compareTableScoreDescending)
            .toList();
        if (ranked.isEmpty()) {
            return SourceOfTruthDecision.none();
        }

        TableScore best = ranked.getFirst();
        TableScore second = ranked.size() > 1 ? ranked.get(1) : null;
        if (best.score() < 80 || (second != null && best.score() - second.score() < 20)) {
            return SourceOfTruthDecision.none();
        }

        List<String> rawMeasureColumns = best.table().getColumns() == null
            ? List.of()
            : best.table().getColumns().stream()
                .filter(this::isMeasureLikeColumn)
                .filter(column -> !isAggregateLikeMeasureColumn(column))
                .map(ColumnMetadata::getName)
                .limit(3)
                .toList();
        List<String> summaryAlternatives = ranked.stream()
            .skip(1)
            .map(TableScore::table)
            .filter(this::hasAggregateLikeMeasuresOnly)
            .map(TableMetadata::getName)
            .limit(3)
            .toList();

        StringBuilder directive = new StringBuilder("Use `")
            .append(best.table().getName())
            .append("` as the primary source-of-truth fact table for this request.");
        if (!rawMeasureColumns.isEmpty()) {
            directive.append(" Prefer raw business measure columns like ")
                .append(rawMeasureColumns.stream()
                    .map(column -> "`" + best.table().getName() + "." + column + "`")
                    .collect(java.util.stream.Collectors.joining(", ")))
                .append(".");
        }
        if (!summaryAlternatives.isEmpty()) {
            directive.append(" Do not aggregate pre-summarized measures from ")
                .append(summaryAlternatives.stream().map(table -> "`" + table + "`").collect(java.util.stream.Collectors.joining(", ")))
                .append(" when the raw fact table can answer the metric.");
        }
        directive.append(" Use other in-scope tables only for validated descriptive joins or filters.");

        String rationale = "Selected the in-scope table with the strongest raw business measure and resolved-column evidence while penalizing summary-style alternatives";
        List<String> alternatives = ranked.stream().skip(1).limit(3).map(score -> score.table().getName()).toList();
        return new SourceOfTruthDecision(best.table().getName(), directive.toString(), alternatives, rationale);
    }

    private int scoreSourceOfTruthCandidate(
            String lowerQuestion,
            TableMetadata table,
            SemanticTableModel semanticTable,
            ResolvedContext resolvedContext,
            boolean anyResolvedRawMeasureCandidate) {
        int score = sourceOfTruthEntityPriority(lowerQuestion, table) * 3;
        score += sourceOfTruthMeasureAlignmentBonus(lowerQuestion, table) * 2;
        score += sourceOfTruthAttributeSpecificityBonus(lowerQuestion, table) * 2;
        score += sourceOfTruthResolvedColumnBonus(lowerQuestion, resolvedContext, table);
        score += wholeTablePhraseBonus(lowerQuestion, table);
        score += entitySpecificityAdjustment(lowerQuestion, table);
        score += requestedDetailCoverageBonus(lowerQuestion, table) * 2;
        score += semanticSourceOfTruthRoleBonus(lowerQuestion, semanticTable);
        if (anyResolvedRawMeasureCandidate && !hasResolvedRawMeasureColumn(resolvedContext, table)) {
            score -= 110;
        }
        if (anyResolvedRawMeasureCandidate && hasAggregateLikeMeasuresOnly(table)) {
            score -= 95;
        }
        return score;
    }

    private int sourceOfTruthAttributeSpecificityBonus(String lowerQuestion, TableMetadata table) {
        if (!isSourceOfTruthQuestion(lowerQuestion) || table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }

        Set<String> questionTokens = new LinkedHashSet<>(extractMeaningfulTokens(lowerQuestion));
        questionTokens.removeAll(Set.of(
            "show", "list", "detail", "details", "last", "past", "time", "date", "trend",
            "report", "overview", "analysis", "top", "bottom", "many", "count", "number",
            "average", "avg", "sum", "per"
        ));
        if (questionTokens.isEmpty()) {
            return 0;
        }

        int best = 0;
        for (ColumnMetadata column : table.getColumns()) {
            if (column == null || column.getName() == null) {
                continue;
            }
            Set<String> columnTokens = extractIdentifierTokens(column.getName());
            int matchCount = (int) columnTokens.stream().filter(questionTokens::contains).count();
            if (matchCount == 0) {
                continue;
            }

            int score = matchCount * 14;
            if (matchCount >= 2) {
                score += 120;
            }
            if (matchCount >= 3) {
                score += 30;
            }
            if (isAggregateLikeMeasureColumn(column)) {
                score -= 44;
            } else if (isMeasureLikeColumn(column)) {
                score += 12;
            } else {
                score += 8;
            }
            best = Math.max(best, score);
        }
        return best;
    }

    private int semanticSourceOfTruthRoleBonus(String lowerQuestion, SemanticTableModel semanticTable) {
        if (!isSourceOfTruthQuestion(lowerQuestion) || semanticTable == null || semanticTable.getTableRole() == null) {
            return 0;
        }
        String normalizedRole = semanticTable.getTableRole().toLowerCase(Locale.ROOT);
        if (normalizedRole.contains("fact")) {
            return 120;
        }
        if (normalizedRole.contains("event")) {
            return PromptIntentSignals.isActivityUsageQuestion(lowerQuestion) ? 70 : 12;
        }
        if (normalizedRole.contains("dimension")) {
            return -95;
        }
        if (normalizedRole.contains("aggregate") || normalizedRole.contains("summary")) {
            return -135;
        }
        return 0;
    }

    private int requestedDetailCoverageBonus(String lowerQuestion, TableMetadata table) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }
        boolean wantsDetails = PromptIntentSignals.requestsDescriptiveAttributes(lowerQuestion)
            || PromptIntentSignals.requestsContactAttributes(lowerQuestion)
            || PromptIntentSignals.requestsPersonEntity(lowerQuestion);
        if (!wantsDetails) {
            return 0;
        }

        boolean wantsPerson = PromptIntentSignals.requestsPersonEntity(lowerQuestion);
        boolean wantsName = lowerQuestion.contains(" name ") || lowerQuestion.contains(" names ");
        boolean wantsEmail = lowerQuestion.contains(" email ")
            || lowerQuestion.contains(" emails ")
            || lowerQuestion.contains(" contact ")
            || lowerQuestion.contains(" contacts ");
        boolean hasName = table.getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(name -> name.contains("name"));
        boolean hasEmail = table.getColumns().stream()
            .filter(Objects::nonNull)
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(name -> name.contains("email") || name.contains("contact"));
        boolean personLike = looksLikePersonEntity(table.getName())
            || table.getColumns().stream()
                .filter(Objects::nonNull)
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .anyMatch(this::looksLikePersonEntity);

        int score = 0;
        if (wantsName && hasName) {
            score += 28;
        }
        if (wantsEmail && hasEmail) {
            score += 28;
        }
        if ((wantsName || wantsEmail) && hasName && hasEmail) {
            score += 34;
        }
        if (wantsPerson && personLike) {
            score += 62;
        }
        if (wantsPerson && wantsName && !hasName) {
            score -= 42;
        }
        if (wantsPerson && wantsEmail && !hasEmail) {
            score -= 34;
        }
        if (wantsPerson && (wantsName || wantsEmail) && !(hasName && hasEmail)) {
            score -= 36;
        }
        if (wantsPerson && !personLike && looksLikePropertyEntity(table.getName()) && !questionMentionsPropertyEntity(lowerQuestion)) {
            score -= 70;
        }
        return score;
    }

    private int sourceOfTruthResolvedColumnBonus(String lowerQuestion, ResolvedContext resolvedContext, TableMetadata table) {
        List<String> resolvedColumns = resolvedColumnsForTable(resolvedContext, table);
        if (resolvedColumns.isEmpty()) {
            return 0;
        }

        Set<String> questionTokens = new LinkedHashSet<>(extractMeaningfulTokens(lowerQuestion));
        questionTokens.removeAll(Set.of(
            "show", "list", "detail", "details", "last", "past", "time", "date", "trend",
            "report", "overview", "analysis", "top", "bottom", "many", "count", "number",
            "average", "avg", "sum", "per"
        ));

        int score = 0;
        boolean rawMeasure = false;
        boolean aggregateMeasure = false;
        boolean strongResolvedAttribute = false;
        for (String columnName : resolvedColumns) {
            ColumnMetadata schemaColumn = resolveTableColumn(table, columnName).orElse(null);
            if (schemaColumn == null) {
                continue;
            }
            if (isMeasureLikeColumn(schemaColumn)) {
                if (isAggregateLikeMeasureColumn(schemaColumn)) {
                    aggregateMeasure = true;
                    score += 16;
                } else {
                    rawMeasure = true;
                    score += 90;
                }
                continue;
            }

            int alignmentScore = resolvedColumnPromptAlignmentBonus(questionTokens, schemaColumn);
            score += alignmentScore;
            if (alignmentScore >= 70) {
                strongResolvedAttribute = true;
            }

            if (isIdentifierLikeColumn(schemaColumn)) {
                score += 6;
            }
            if (mentionsTimeWindow(lowerQuestion) && isTemporalLikeColumn(schemaColumn)) {
                score += 8;
            }
        }
        if (rawMeasure) {
            score += 45;
        } else if (aggregateMeasure) {
            score -= 20;
        }
        if (strongResolvedAttribute) {
            score += 18;
        }
        return score;
    }

    private int resolvedColumnPromptAlignmentBonus(Set<String> questionTokens, ColumnMetadata column) {
        if (column == null || column.getName() == null || questionTokens == null || questionTokens.isEmpty()) {
            return 0;
        }

        Set<String> columnTokens = extractIdentifierTokens(column.getName());
        int matchCount = (int) columnTokens.stream().filter(questionTokens::contains).count();
        if (matchCount == 0) {
            return 0;
        }

        int score = matchCount * 12;
        if (matchCount >= 2) {
            score += 72;
        }
        if (matchCount >= 3) {
            score += 20;
        }

        boolean compositePrompt = questionTokens.size() >= 2;
        boolean genericAlias = columnTokens.size() == 1
            && Set.of("status", "source", "type", "amount", "value", "name", "email", "contact")
                .contains(columnTokens.iterator().next());
        if (compositePrompt && matchCount == 1 && genericAlias) {
            score -= 24;
        }
        return score;
    }

    private boolean hasResolvedRawMeasureColumn(ResolvedContext resolvedContext, TableMetadata table) {
        return resolvedColumnsForTable(resolvedContext, table).stream()
            .map(columnName -> resolveTableColumn(table, columnName).orElse(null))
            .filter(Objects::nonNull)
            .anyMatch(column -> isMeasureLikeColumn(column) && !isAggregateLikeMeasureColumn(column));
    }

    private List<String> resolvedColumnsForTable(ResolvedContext resolvedContext, TableMetadata table) {
        if (resolvedContext == null
            || resolvedContext.columns() == null
            || resolvedContext.columns().isEmpty()
            || table == null
            || table.getName() == null) {
            return List.of();
        }

        return resolvedContext.columns().entrySet().stream()
            .filter(entry -> entry.getKey() != null && entry.getKey().equalsIgnoreCase(table.getName()))
            .findFirst()
            .map(Map.Entry::getValue)
            .orElse(List.of());
    }

    private Optional<ColumnMetadata> resolveTableColumn(TableMetadata table, String columnName) {
        if (table == null || table.getColumns() == null || columnName == null || columnName.isBlank()) {
            return Optional.empty();
        }
        return table.getColumns().stream()
            .filter(Objects::nonNull)
            .filter(column -> column.getName() != null && column.getName().equalsIgnoreCase(columnName))
            .findFirst();
    }

    private int sourceOfTruthMeasureAlignmentBonus(String lowerQuestion, TableMetadata table) {
        if (!isSourceOfTruthQuestion(lowerQuestion) || table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }

        Set<String> metricTokens = new LinkedHashSet<>(extractMeaningfulTokens(lowerQuestion));
        metricTokens.removeAll(Set.of(
            "total", "sum", "average", "avg", "count", "number", "many", "top", "bottom",
            "highest", "lowest", "most", "least", "show", "list", "detail", "details",
            "group", "grouped", "per", "each", "compare", "comparison", "trend", "period",
            "time", "date", "range"
        ));

        int bestRawScore = 0;
        int bestAggregateScore = 0;
        boolean sawAggregateLikeMatch = false;
        boolean sawRawMatch = false;

        for (ColumnMetadata column : table.getColumns()) {
            if (column == null || !isMeasureLikeColumn(column) || column.getName() == null) {
                continue;
            }

            Set<String> columnTokens = extractIdentifierTokens(column.getName());
            int matchCount = (int) columnTokens.stream().filter(metricTokens::contains).count();
            if (matchCount == 0 && metricTokens.isEmpty()) {
                matchCount = 1;
            }
            if (matchCount == 0) {
                continue;
            }

            int score = matchCount * 20;
            if (isAggregateLikeMeasureColumn(column)) {
                score -= 18;
                sawAggregateLikeMatch = true;
                bestAggregateScore = Math.max(bestAggregateScore, score);
            } else {
                score += 18;
                if (columnTokens.size() == matchCount) {
                    score += 8;
                }
                sawRawMatch = true;
                bestRawScore = Math.max(bestRawScore, score);
            }
        }

        if (sawRawMatch) {
            return bestRawScore;
        }
        if (sawAggregateLikeMatch) {
            return bestAggregateScore - 24;
        }
        return 0;
    }

    private boolean questionMentionsPropertyEntity(String lowerQuestion) {
        return lowerQuestion != null && (
            lowerQuestion.contains(" customer ")
                || lowerQuestion.contains(" customers ")
                || lowerQuestion.contains(" property ")
                || lowerQuestion.contains(" properties ")
                || lowerQuestion.contains(" account ")
                || lowerQuestion.contains(" accounts ")
        );
    }

    private boolean looksLikePersonEntity(String identifier) {
        String normalized = identifier == null ? "" : identifier.toLowerCase(Locale.ROOT);
        return normalized.contains("user")
            || normalized.contains("guest")
            || normalized.contains("customer")
            || normalized.contains("traveler")
            || normalized.contains("traveller")
            || normalized.contains("person")
            || normalized.contains("contact");
    }

    private boolean looksLikePropertyEntity(String identifier) {
        String normalized = identifier == null ? "" : identifier.toLowerCase(Locale.ROOT);
        return normalized.contains("customer")
            || normalized.contains("property")
            || normalized.contains("account");
    }

    private boolean isSourceOfTruthQuestion(String lowerQuestion) {
        if (lowerQuestion == null || lowerQuestion.isBlank()) {
            return false;
        }
        boolean countQuestion = lowerQuestion.contains("count") || lowerQuestion.contains("how many");
        boolean detailQuestion = lowerQuestion.contains(" detail ")
            || lowerQuestion.contains(" details ")
            || lowerQuestion.contains(" list ")
            || lowerQuestion.contains(" show ")
            || lowerQuestion.contains(" email ")
            || lowerQuestion.contains(" country ")
            || lowerQuestion.contains(" name ");
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(lowerQuestion);
        boolean trendSummaryQuestion = lowerQuestion.contains(" summary ")
            || lowerQuestion.contains(" pattern ")
            || lowerQuestion.contains(" report ")
            || lowerQuestion.contains(" overview ");
        return (countQuestion || detailQuestion || commercialQuestion) && !trendSummaryQuestion;
    }

    private int temporalTableBonus(String lowerQuestion, TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return 0;
        }
        int bestTemporalScore = table.getColumns().stream()
            .filter(Objects::nonNull)
            .mapToInt(column -> scoreTemporalColumn(lowerQuestion, table.getName(), column))
            .max()
            .orElse(0);
        return Math.min(60, bestTemporalScore / 2);
    }

    private int resolvedContextBoost(int resolvedTableCount) {
        if (resolvedTableCount <= 0) {
            return 0;
        }
        if (resolvedTableCount == 1) {
            return 45;
        }
        if (resolvedTableCount <= 3) {
            return 20;
        }
        return 10;
    }

    private int tablePopulationBonus(TableMetadata table) {
        long rowCount = table.getRowCount() != null ? table.getRowCount() : 0L;
        if (rowCount <= 0) {
            return -35;
        }
        if (rowCount >= 1_000_000) {
            return 25;
        }
        if (rowCount >= 10_000) {
            return 18;
        }
        if (rowCount >= 100) {
            return 10;
        }
        return 4;
    }

    private String buildAggregationJoinGuard(String question, ResolvedContext resolvedContext) {
        String normalized = PromptIntentSignals.normalize(question);
        if (!PromptIntentSignals.isRankingQuestion(question)
            || PromptIntentSignals.requestsDescriptiveAttributes(question)
            || resolvedContext == null
            || resolvedContext.columns().isEmpty()) {
            return "";
        }

        Set<String> identifierColumns = new LinkedHashSet<>();
        resolvedContext.columns().values().forEach(columns -> columns.stream()
            .filter(Objects::nonNull)
            .filter(column -> {
                String lower = column.toLowerCase(Locale.ROOT);
                return "id".equals(lower) || lower.endsWith("_id");
            })
            .forEach(identifierColumns::add));
        if (identifierColumns.isEmpty()) {
            return "";
        }

        return "AGGREGATION GUARD: For ranked aggregates, group by identifier columns already present on the source-of-truth tables ("
            + String.join(", ", identifierColumns.stream().limit(6).toList())
            + "). Do not join descriptive or dimension tables unless the user explicitly asked for attributes like name, email, country, city, or address.";
    }

    private String buildJoinedDetailDirective(
        String question,
        ResolvedContext resolvedContext,
        JoinPathResolutionPolicy.Decision joinDecision
    ) {
        if (!PromptIntentSignals.requestsDescriptiveAttributes(question)
            || resolvedContext == null
            || resolvedContext.columns() == null
            || resolvedContext.columns().isEmpty()
            || resolvedContext.tables().size() < 2) {
            return "";
        }

        Set<String> prioritizedTables = new LinkedHashSet<>();
        if (joinDecision != null && joinDecision.hasEnhancement() && joinDecision.addedTables() != null) {
            prioritizedTables.addAll(joinDecision.addedTables());
        }
        prioritizedTables.addAll(resolvedContext.tables());

        List<String> preferredColumns = new ArrayList<>();
        for (String tableName : prioritizedTables) {
            preferredColumns.addAll(
                resolvedContext.columns().getOrDefault(tableName, List.of()).stream()
                    .filter(Objects::nonNull)
                    .filter(this::isDescriptiveColumn)
                    .map(column -> tableName + "." + column)
                    .toList()
            );
        }

        if (preferredColumns.isEmpty()) {
            return "";
        }

        StringBuilder directive = new StringBuilder("ENTITY ATTRIBUTE RESOLUTION: Prefer these validated entity-detail columns for the requested descriptive attributes: ")
            .append(String.join(", ", preferredColumns.stream().limit(6).toList()))
            .append(". Avoid denormalized duplicates from fact tables when an in-scope entity table already provides the attribute.");

        String normalizedQuestion = PromptIntentSignals.normalize(question);
        boolean contactRequest = normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" emails ")
            || normalizedQuestion.contains(" contact ")
            || normalizedQuestion.contains(" contacts ");
        boolean hasNameColumn = preferredColumns.stream()
            .map(column -> column.toLowerCase(Locale.ROOT))
            .anyMatch(column -> column.contains(".") && column.contains("name"));
        if (contactRequest && hasNameColumn) {
            directive.append(" When returning contact-style attributes for a person or entity, include the validated display/name column alongside the email or contact identifier whenever it is available from the same joined entity table.");
        }

        return directive.toString();
    }

    private boolean isDescriptiveColumn(String columnName) {
        if (columnName == null || columnName.isBlank()) {
            return false;
        }
        String normalized = columnName.toLowerCase(Locale.ROOT);
        return normalized.contains("email")
            || normalized.contains("name")
            || normalized.contains("country")
            || normalized.contains("city")
            || normalized.contains("state")
            || normalized.contains("address")
            || normalized.contains("contact");
    }

    private String buildRowPreviewGuard(String question, AgentTaskKind taskKind) {
        String normalized = PromptIntentSignals.normalize(question);
        if (taskKind != AgentTaskKind.LOOKUP
            || normalized.contains(" all ")
            || normalized.contains(" full ")
            || normalized.contains(" export ")
            || normalized.contains(" csv ")
            || normalized.contains(" every ")) {
            return "";
        }
        return "ROW PREVIEW GUARD: This is a row-level lookup. Unless the user explicitly asks for the full dataset, keep the result bounded with LIMIT 100 after applying filters and ordering.";
    }

    private boolean isEpochLikeTemporalColumn(String qualifiedColumnName, String dataType) {
        if (qualifiedColumnName == null || qualifiedColumnName.isBlank() || dataType == null || dataType.isBlank()) {
            return false;
        }
        String lowerType = dataType.toLowerCase(Locale.ROOT);
        if (!(lowerType.contains("int") || lowerType.contains("long") || lowerType.contains("number") || lowerType.contains("decimal"))) {
            return false;
        }
        String lowerColumn = qualifiedColumnName.toLowerCase(Locale.ROOT);
        return lowerColumn.contains("date")
            || lowerColumn.contains("time")
            || lowerColumn.contains("made_on")
            || lowerColumn.contains("created")
            || lowerColumn.contains("updated")
            || lowerColumn.contains("cancel");
    }

    private boolean looksLikeAggregateQuestion(String lowerQuestion) {
        if (lowerQuestion == null || lowerQuestion.isBlank()) {
            return false;
        }
        return lowerQuestion.contains("total")
            || lowerQuestion.contains("sum")
            || lowerQuestion.contains("average")
            || lowerQuestion.contains("avg")
            || lowerQuestion.contains("count")
            || lowerQuestion.contains("revenue")
            || lowerQuestion.contains("amount")
            || lowerQuestion.contains("sales")
            || lowerQuestion.contains("gmv")
            || lowerQuestion.contains("commission")
            || lowerQuestion.contains("metric")
            || lowerQuestion.startsWith("what is")
            || lowerQuestion.startsWith("how much");
    }

    private Set<String> extractMeaningfulTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }

        Set<String> stopWords = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "for", "from", "with", "and", "or", "to",
            "of", "in", "on", "by", "at", "this", "that", "these", "those", "what", "which", "how",
            "much", "many", "month", "months", "week", "weeks", "day", "days", "year", "years", "march"
        );

        Set<String> tokens = new LinkedHashSet<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String normalized = normalizeToken(part);
            if (normalized.length() < 3 || stopWords.contains(normalized)) {
                continue;
            }
            tokens.add(normalized);
        }
        return tokens;
    }

    private Set<String> extractIdentifierTokens(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Set.of();
        }

        Set<String> tokens = new LinkedHashSet<>();
        for (String part : identifier.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String normalized = normalizeToken(part);
            if (normalized.length() >= 3) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("ies") && normalized.length() > 4) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }
        if (normalized.endsWith("s") && normalized.length() > 3 && !normalized.endsWith("ss")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private boolean isIdentifierLikeColumn(ColumnMetadata column) {
        if (column == null || column.getName() == null) {
            return false;
        }
        String name = column.getName().toLowerCase(Locale.ROOT);
        return "id".equals(name)
            || name.endsWith("_id")
            || name.endsWith("uuid")
            || name.contains("key");
    }

    private boolean isTemporalLikeColumn(ColumnMetadata column) {
        if (column == null || column.getName() == null) {
            return false;
        }
        String name = column.getName().toLowerCase(Locale.ROOT);
        String dataType = column.getDataType() == null ? "" : column.getDataType().toLowerCase(Locale.ROOT);
        return isStrictTemporalColumn(name, dataType);
    }

    private boolean isStrictTemporalColumn(String normalizedName, String normalizedType) {
        if (normalizedName == null || normalizedName.isBlank()) {
            return false;
        }
        if (normalizedName.endsWith("_id") || "id".equals(normalizedName) || normalizedName.contains("timezone")
            || normalizedName.contains("birth") || normalizedName.contains("dob")) {
            return false;
        }
        if (normalizedType != null && (normalizedType.contains("date") || normalizedType.contains("time"))) {
            return true;
        }
        return normalizedName.contains("date")
            || normalizedName.contains("time")
            || normalizedName.contains("timestamp")
            || normalizedName.contains("created")
            || normalizedName.contains("updated")
            || normalizedName.contains("occurred")
            || normalizedName.contains("logged")
            || normalizedName.contains("access")
            || normalizedName.contains("visit")
            || normalizedName.contains("session")
            || normalizedName.contains("checkin")
            || normalizedName.contains("checkout")
            || normalizedName.endsWith("_on")
            || normalizedName.endsWith("_at");
    }

    private boolean isMeasureLikeColumn(ColumnMetadata column) {
        if (column == null || column.getName() == null) {
            return false;
        }
        if (isIdentifierLikeColumn(column)) {
            return false;
        }
        String name = column.getName().toLowerCase(Locale.ROOT);
        String dataType = column.getDataType() == null ? "" : column.getDataType().toLowerCase(Locale.ROOT);
        if (isStrictTemporalColumn(name, dataType)) {
            return false;
        }
        if (name.contains("lat") || name.contains("lng") || name.contains("longitude") || name.contains("latitude")) {
            return false;
        }

        boolean keywordMatch = name.contains("amount")
            || name.contains("total")
            || name.contains("price")
            || name.contains("cost")
            || name.contains("fee")
            || name.contains("count")
            || name.contains("qty")
            || name.contains("quantity")
            || name.contains("rate")
            || name.contains("score")
            || name.contains("value")
            || name.contains("revenue")
            || name.contains("commission")
            || name.contains("discount")
            || name.contains("saving")
            || name.contains("refund")
            || name.contains("tax")
            || name.contains("deposit")
            || name.contains("balance")
            || name.contains("payment")
            || name.contains("people")
            || name.contains("adult")
            || name.contains("child")
            || name.contains("room")
            || name.contains("night")
            || name.startsWith("num_");
        if (keywordMatch) {
            return true;
        }

        boolean numericType = dataType.contains("int")
            || dataType.contains("decimal")
            || dataType.contains("numeric")
            || dataType.contains("float")
            || dataType.contains("double")
            || dataType.contains("real");
        if (!numericType) {
            return false;
        }

        return name.contains("metric")
            || name.contains("ratio")
            || name.contains("percent")
            || name.contains("percentage");
    }

    private boolean isAggregateLikeMeasureColumn(ColumnMetadata column) {
        if (column == null || column.getName() == null) {
            return false;
        }
        String normalized = column.getName().toLowerCase(Locale.ROOT);
        return normalized.startsWith("total_")
            || normalized.startsWith("sum_")
            || normalized.startsWith("avg_")
            || normalized.startsWith("average_")
            || normalized.startsWith("count_")
            || normalized.startsWith("cnt_")
            || normalized.startsWith("max_")
            || normalized.startsWith("min_")
            || normalized.startsWith("ratio_")
            || normalized.startsWith("rate_")
            || normalized.endsWith("_total")
            || normalized.endsWith("_sum")
            || normalized.endsWith("_avg")
            || normalized.endsWith("_average")
            || normalized.endsWith("_count")
            || normalized.endsWith("_cnt")
            || normalized.endsWith("_ratio")
            || normalized.endsWith("_rate");
    }

    private boolean hasAggregateLikeMeasuresOnly(TableMetadata table) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return false;
        }
        boolean sawAggregateLikeMeasure = false;
        for (ColumnMetadata column : table.getColumns()) {
            if (!isMeasureLikeColumn(column)) {
                continue;
            }
            if (isAggregateLikeMeasureColumn(column)) {
                sawAggregateLikeMeasure = true;
                continue;
            }
            return false;
        }
        return sawAggregateLikeMeasure;
    }

    private boolean hasRawBusinessMeasure(TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return false;
        }
        return table.getColumns().stream()
            .filter(this::isMeasureLikeColumn)
            .anyMatch(column -> !isAggregateLikeMeasureColumn(column));
    }

    private int compareTableScore(TableScore left, TableScore right) {
        if (left == null || right == null) {
            return left == null ? -1 : 1;
        }
        int scoreCompare = Integer.compare(left.score(), right.score());
        if (scoreCompare != 0) {
            return scoreCompare;
        }
        long leftRows = left.table().getRowCount() != null ? left.table().getRowCount() : 0L;
        long rightRows = right.table().getRowCount() != null ? right.table().getRowCount() : 0L;
        int rowCompare = Long.compare(leftRows, rightRows);
        if (rowCompare != 0) {
            return rowCompare;
        }
        int leftColumns = left.table().getColumns() != null ? left.table().getColumns().size() : 0;
        int rightColumns = right.table().getColumns() != null ? right.table().getColumns().size() : 0;
        int columnCompare = Integer.compare(leftColumns, rightColumns);
        if (columnCompare != 0) {
            return columnCompare;
        }
        return String.CASE_INSENSITIVE_ORDER.compare(left.table().getName(), right.table().getName());
    }

    private int compareTableScoreDescending(TableScore left, TableScore right) {
        return compareTableScore(right, left);
    }

    private boolean isDataRetrievalQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT);
        if (PatternUtil.containsPattern(q, "(top|bottom|least|most|highest|lowest|worst|best|slowest|fastest)\\s+\\d+")) return true;
        if (PatternUtil.containsPattern(q, "(show me|list|give me|find|fetch|retrieve|get me|display|return)\\s+.*\\b(accounts?|users?|customers?|orders?|records?|rows?|entries?|data|results?|transactions?|bookings?|customers?|properties?)")) return true;
        if (PatternUtil.containsPattern(q, "which\\s+\\w+\\s+(are|have|do|did|has|were|is)")) return true;
        if (PatternUtil.containsPattern(q, "(how many|count of|number of)\\s+.*(rows?|records?|accounts?|users?|customers?|orders?|bookings?|sessions?|transactions?)")) return true;
        if (PatternUtil.containsPattern(q, "(report|summary|breakdown|overview|analysis)\\s+(of|on|for).*\\b(last|past|since|in the).*\\b(days?|weeks?|months?|years?)")) return true;
        if (PatternUtil.containsPattern(q, "in the (last|past)\\s+\\d+\\s+(days?|weeks?|months?)") &&
            PatternUtil.containsPattern(q, "(accounts?|users?|customers?|orders?|bookings?|queries?|transactions?|sessions?|customers?|properties?)")) return true;
        if (PatternUtil.containsPattern(q, "(what|which|show|list|give me).*(fees?|taxes|refunds?|cancellations?|services?|details?|amounts?|methods?)")) return true;
        return false;
    }

    private boolean isClarifyingQuestionResponse(String responseContent) {
        if (responseContent == null || responseContent.isBlank()) {
            return false;
        }
        if (SQL_CODE_BLOCK.matcher(responseContent).find()) {
            return false;
        }

        String lower = responseContent.toLowerCase(Locale.ROOT);
        boolean asksQuestion = lower.contains("?");
        boolean ambiguitySignal =
            lower.contains("which column") ||
                lower.contains("which table") ||
                lower.contains("confirm the exact") ||
                lower.contains("do you mean") ||
                lower.contains("can't safely choose") ||
                lower.contains("cannot safely choose") ||
                lower.contains("need one clarification") ||
                lower.contains("should define");

        return asksQuestion && ambiguitySignal;
    }

    private boolean hasUnresolvedSqlPlaceholder(String sql) {
        return sql != null && sql.matches("(?s).*(<\\s*[a-zA-Z0-9_]+\\s*>).*");
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String safeCap(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private record TableScore(TableMetadata table, int score) {}
    private record SourceOfTruthDecision(String tableName, String directive, List<String> alternatives, String rationale) {
        static SourceOfTruthDecision none() {
            return new SourceOfTruthDecision(null, null, List.of(), null);
        }

        boolean hasDirective() {
            return directive != null && !directive.isBlank();
        }
    }

    private record SemanticTemporalEntity(TableMetadata table, SemanticTableModel semanticTable) {
        private boolean hasTemporalCandidate() {
            if (table == null || table.getColumns() == null) {
                return false;
            }

            if (semanticTable != null && semanticTable.getTimeColumns() != null && !semanticTable.getTimeColumns().isEmpty()) {
                Set<String> normalizedTimeColumns = semanticTable.getTimeColumns().stream()
                    .filter(Objects::nonNull)
                    .map(SemanticTemporalEntity::normalizeName)
                    .collect(java.util.stream.Collectors.toSet());
                boolean semanticMatch = table.getColumns().stream()
                    .filter(Objects::nonNull)
                    .map(ColumnMetadata::getName)
                    .filter(Objects::nonNull)
                    .map(SemanticTemporalEntity::normalizeName)
                    .anyMatch(normalizedTimeColumns::contains);
                if (semanticMatch) {
                    return true;
                }
            }

            return table.getColumns().stream().anyMatch(column ->
                column != null
                    && column.getName() != null
                    && (column.getName().toLowerCase(Locale.ROOT).contains("date")
                        || column.getName().toLowerCase(Locale.ROOT).contains("time")
                        || column.getName().toLowerCase(Locale.ROOT).contains("created")
                        || column.getName().toLowerCase(Locale.ROOT).contains("updated")
                        || column.getName().toLowerCase(Locale.ROOT).endsWith("_on")
                        || column.getName().toLowerCase(Locale.ROOT).endsWith("_at"))
            );
        }

        private boolean isPreferredTimeColumn(String columnName) {
            if (semanticTable == null || semanticTable.getTimeColumns() == null || columnName == null) {
                return false;
            }
            String normalized = normalizeName(columnName);
            return semanticTable.getTimeColumns().stream()
                .filter(Objects::nonNull)
                .map(SemanticTemporalEntity::normalizeName)
                .anyMatch(normalized::equals);
        }

        private boolean isPrimaryPreferredTimeColumn(String columnName) {
            if (semanticTable == null || semanticTable.getTimeColumns() == null || semanticTable.getTimeColumns().isEmpty() || columnName == null) {
                return false;
            }
            return normalizeName(columnName).equals(normalizeName(semanticTable.getTimeColumns().getFirst()));
        }

        private boolean hasBusinessDescription() {
            return semanticTable != null
                && semanticTable.getBusinessDescription() != null
                && !semanticTable.getBusinessDescription().isBlank();
        }

        private String temporalSemanticLabel(String columnName) {
            if (semanticTable == null || semanticTable.getTemporalSemantics() == null || columnName == null) {
                return null;
            }
            String normalized = normalizeName(columnName);
            return semanticTable.getTemporalSemantics().stream()
                .filter(Objects::nonNull)
                .filter(item -> normalized.equals(normalizeName(safeValue(item.get("column")))))
                .map(item -> safeValue(item.get("label")))
                .filter(label -> label != null && !label.isBlank())
                .findFirst()
                .orElse(null);
        }

        private boolean isDerivedLike() {
            if (semanticTable == null) {
                return false;
            }
            String role = normalizeName(semanticTable.getTableRole());
            String tableName = normalizeName(table != null ? table.getName() : semanticTable.getTableName());
            return role.contains("aggregate")
                || role.contains("summary")
                || tableName.contains("trend")
                || tableName.contains("summary")
                || tableName.contains("rollup")
                || tableName.contains("report")
                || tableName.contains("insight");
        }

        private boolean isPreferredFilterColumn(String columnName) {
            if (semanticTable == null || semanticTable.getFilterColumns() == null || columnName == null) {
                return false;
            }
            String normalized = normalizeName(columnName);
            return semanticTable.getFilterColumns().stream()
                .filter(Objects::nonNull)
                .map(filter -> filter.get("column"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(SemanticTemporalEntity::normalizeName)
                .anyMatch(normalized::equals);
        }

        private List<String> preferredFilterColumns() {
            if (semanticTable == null || semanticTable.getFilterColumns() == null || semanticTable.getFilterColumns().isEmpty()) {
                return List.of();
            }
            return semanticTable.getFilterColumns().stream()
                .filter(Objects::nonNull)
                .map(filter -> filter.get("column"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .filter(column -> !column.isBlank())
                .distinct()
                .limit(4)
                .toList();
        }

        private static String normalizeName(String value) {
            if (value == null) {
                return "";
            }
            return value.trim().toLowerCase(Locale.ROOT);
        }

        private static String safeValue(Object value) {
            return value == null ? null : String.valueOf(value);
        }
    }

    private record TemporalResolution(
        String directive,
        String clarificationMessage,
        List<String> candidateColumns,
        String rationale,
        String chosenColumn,
        boolean ambiguous
    ) {
        private static TemporalResolution none() {
            return new TemporalResolution(null, null, List.of(), null, null, false);
        }

        private static TemporalResolution directive(
                String directive,
                List<String> candidateColumns,
                String rationale,
                String chosenColumn,
                boolean ambiguous) {
            return new TemporalResolution(
                directive,
                null,
                candidateColumns == null ? List.of() : candidateColumns,
                rationale,
                chosenColumn,
                ambiguous
            );
        }

        private static TemporalResolution clarify(
                String clarificationMessage,
                List<String> candidateColumns,
                String rationale,
                String chosenColumn,
                boolean ambiguous) {
            return new TemporalResolution(
                null,
                clarificationMessage,
                candidateColumns == null ? List.of() : candidateColumns,
                rationale,
                chosenColumn,
                ambiguous
            );
        }

        private boolean needsClarification() {
            return clarificationMessage != null && !clarificationMessage.isBlank();
        }

        private boolean isNone() {
            return !needsClarification() && (directive == null || directive.isBlank());
        }

        private boolean isAmbiguous() {
            return ambiguous;
        }
    }

    private record FilterResolution(String directive, String clarificationMessage, List<String> candidateColumns) {
        private static FilterResolution none() {
            return new FilterResolution(null, null, List.of());
        }

        private static FilterResolution directive(String directive) {
            return new FilterResolution(directive, null, List.of());
        }

        private static FilterResolution clarify(String clarificationMessage, List<String> candidateColumns) {
            return new FilterResolution(null, clarificationMessage, candidateColumns == null ? List.of() : candidateColumns);
        }

        private boolean needsClarification() {
            return clarificationMessage != null && !clarificationMessage.isBlank();
        }
    }

    private record FilterCandidate(String tableName, String columnName, int score) {
        private String qualifiedName() {
            return tableName + "." + columnName;
        }
    }
}
