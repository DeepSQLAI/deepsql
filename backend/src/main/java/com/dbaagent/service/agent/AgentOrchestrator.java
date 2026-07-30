package com.dbaagent.service.agent;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ConversationCarryoverDecision;
import com.dbaagent.service.ResolvedConversationContext;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

@Service
@Slf4j
public class AgentOrchestrator {

    private final AgentPromptClassifier promptClassifier;
    private final AgentPlanner planner;
    private final AgentToolRegistry toolRegistry;
    private final AgentAnswerComposer answerComposer;
    private final AgentRunService agentRunService;
    private final ApprovedWorkflowService approvedWorkflowService;
    private final LlmOrchestrationService llmOrchestrationService;
    private final int maxSteps;

    public AgentOrchestrator(
            AgentPromptClassifier promptClassifier,
            AgentPlanner planner,
            AgentToolRegistry toolRegistry,
            AgentAnswerComposer answerComposer,
            AgentRunService agentRunService,
            ApprovedWorkflowService approvedWorkflowService,
            LlmOrchestrationService llmOrchestrationService,
            @Value("${app.chat.agentic.max-steps:8}") int maxSteps) {
        this.promptClassifier = promptClassifier;
        this.planner = planner;
        this.toolRegistry = toolRegistry;
        this.answerComposer = answerComposer;
        this.agentRunService = agentRunService;
        this.approvedWorkflowService = approvedWorkflowService;
        this.llmOrchestrationService = llmOrchestrationService;
        this.maxSteps = maxSteps;
    }

    public Optional<AgentExecutionResult> maybeExecute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            SchemaMetadata schema,
            ChatQuestionRoutingService.QuestionRoute route) {
        return maybeExecute(agenticEnabled, connectionId, question, schema, route, PromptIntent.unsupported());
    }

    public Optional<AgentExecutionResult> maybeExecute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            SchemaMetadata schema,
            ChatQuestionRoutingService.QuestionRoute route,
            PromptIntent promptIntent) {
        if (!agenticEnabled) {
            return Optional.empty();
        }

        AgentDecision decision = previewDecision(agenticEnabled, question, route);
        return execute(
            agenticEnabled,
            connectionId,
            question,
            question,
            null,
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            schema,
            decision,
            ConversationCarryoverDecision.empty(),
            AgentProgressListener.noop()
        );
    }

    public AgentDecision previewDecision(
            boolean agenticEnabled,
            String question,
            ChatQuestionRoutingService.QuestionRoute route) {
        if (!agenticEnabled) {
            return AgentDecision.none();
        }
        return promptClassifier.classify(question, route);
    }

    public boolean requiresSchema(AgentIntent intent) {
        return intent == AgentIntent.METADATA_ANALYSIS
            || intent == AgentIntent.UNIVERSAL_CHAT;
    }

    public Optional<AgentExecutionResult> execute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            SchemaMetadata schema,
            AgentDecision decision) {
        return execute(agenticEnabled, connectionId, question, schema, decision, PromptIntent.unsupported(), AgentProgressListener.noop());
    }

    public Optional<AgentExecutionResult> execute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            SchemaMetadata schema,
            AgentDecision decision,
            PromptIntent promptIntent
    ) {
        return execute(
            agenticEnabled,
            connectionId,
            question,
            question,
            null,
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            schema,
            decision,
            ConversationCarryoverDecision.empty(),
            AgentProgressListener.noop()
        );
    }

    public Optional<AgentExecutionResult> execute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            SchemaMetadata schema,
            AgentDecision decision,
            AgentProgressListener progressListener) {
        return execute(agenticEnabled, connectionId, question, schema, decision, PromptIntent.unsupported(), progressListener);
    }

    public Optional<AgentExecutionResult> execute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            SchemaMetadata schema,
            AgentDecision decision,
            PromptIntent promptIntent,
            AgentProgressListener progressListener) {
        return execute(
            agenticEnabled,
            connectionId,
            question,
            question,
            null,
            List.of(),
            ResolvedConversationContext.empty(),
            promptIntent,
            schema,
            decision,
            ConversationCarryoverDecision.empty(),
            progressListener
        );
    }

    public Optional<AgentExecutionResult> execute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            String effectiveQuestion,
            String chatId,
            List<AgentExecutionContext.ConversationTurn> conversationHistory,
            ResolvedConversationContext resolvedConversationContext,
            SchemaMetadata schema,
            AgentDecision decision,
            ConversationCarryoverDecision carryoverDecision,
            AgentProgressListener progressListener) {
        return execute(
            agenticEnabled,
            connectionId,
            question,
            effectiveQuestion,
            chatId,
            conversationHistory,
            resolvedConversationContext,
            PromptIntent.unsupported(),
            schema,
            decision,
            carryoverDecision,
            progressListener
        );
    }

    public Optional<AgentExecutionResult> execute(
            boolean agenticEnabled,
            String connectionId,
            String question,
            String effectiveQuestion,
            String chatId,
            List<AgentExecutionContext.ConversationTurn> conversationHistory,
            ResolvedConversationContext resolvedConversationContext,
            PromptIntent promptIntent,
            SchemaMetadata schema,
            AgentDecision decision,
            ConversationCarryoverDecision carryoverDecision,
            AgentProgressListener progressListener) {
        if (!agenticEnabled) {
            return Optional.empty();
        }

        String runId = null;
        try {
            ThreadContextPack threadContextPack = ThreadContextPack.of(
                question,
                effectiveQuestion,
                orchestrationRouteTypeHint(promptIntent),
                orchestrationBrainTopicHint(promptIntent),
                promptIntent,
                carryoverDecision,
                resolvedConversationContext,
                conversationHistory
            );
            OrchestrationDecision orchestrationDecision = llmOrchestrationService.decideInitial(threadContextPack, promptIntent);
            orchestrationDecision = enforceThreadSourceOfTruth(orchestrationDecision, threadContextPack, promptIntent);
            PromptIntent resolvedPromptIntent = (promptIntent == null ? PromptIntent.unsupported() : promptIntent)
                .resolvedForDomain(orchestrationDecision.domain());
            AgentIntent executionIntent = mapAgentIntent(orchestrationDecision.domain(), AgentIntent.UNIVERSAL_CHAT);
            ConversationExecutionMode executionMode = applyThreadMode(
                orchestrationDecision.threadMode(),
                resolvedConversationContext,
                carryoverDecision,
                conversationHistory
            );

            progressListener.onEvent(new AgentProgressEvent(
                "planning",
                "Decide scope and strategy",
                "LLM is deciding intent, thread scope, and the first tool action",
                "active",
                null,
                null,
                null,
                null,
                null
            ));
            String planningQuestion = (effectiveQuestion != null && !effectiveQuestion.isBlank()) ? effectiveQuestion : question;
            AgentPlan plan = buildOrchestrationPlan(orchestrationDecision, executionIntent);
            Optional<ApprovedWorkflowMatch> approvedMatch = approvedWorkflowService.findBestMatch(connectionId, executionIntent, planningQuestion);
            if (approvedMatch.isPresent()) {
                AgentPlan workflowPlan = approvedWorkflowService.applyApprovedWorkflow(plan, approvedMatch.get());
                if (workflowPlan != null) {
                    plan = workflowPlan;
                }
            }
            var run = agentRunService.startRun(connectionId, chatId, question, plan);
            runId = run.getId();
            String planningDetail = approvedMatch
                .map(approvedWorkflowService::workflowHint)
                .orElse(orchestrationDecision.reason().isBlank() ? orchestrationDecision.summarize() : orchestrationDecision.reason());
            progressListener.onEvent(new AgentProgressEvent(
                "planning",
                "Decide scope and strategy",
                planningDetail,
                "completed",
                null,
                "llm_orchestration",
                run.getId(),
                orchestrationDecision.summarize(),
                orchestrationDecision.confidence()
            ));
            recordSyntheticStep(
                run.getId(),
                0,
                new AgentPlanStep(
                    "orchestration-decision",
                    "LLM orchestration decision",
                    "llm_orchestration",
                    Map.of(
                        "domain", orchestrationDecision.domain().name(),
                        "threadMode", orchestrationDecision.threadMode().name(),
                        "decisionSource", orchestrationDecision.decisionSource(),
                        "planOutline", orchestrationDecision.planOutline(),
                        "successCriteria", orchestrationDecision.successCriteria()
                    ),
                    "orchestration",
                    List.of(),
                    "orchestration"
                ),
                new AgentToolResult(
                    new AgentObservation(
                        "orchestration_decision",
                        orchestrationDecision.summarize(),
                        Map.of(
                            "reason", orchestrationDecision.reason(),
                            "confidence", orchestrationDecision.confidence(),
                            "nextAction", orchestrationDecision.nextAction() == null ? "" : orchestrationDecision.nextAction().tool()
                        )
                    ),
                    null,
                    null,
                    orchestrationDecision.confidence()
                )
            );
            AgentExecutionContext context = new AgentExecutionContext(
                connectionId,
                question,
                planningQuestion,
                chatId,
                executionMode.conversationHistory(),
                executionMode.resolvedConversationContext(),
                resolvedPromptIntent,
                schema,
                schema != null ? schema.getDbType() : null
            );
            context.putMemory("conversationCarryoverDecision", executionMode.carryoverDecision());
            context.putMemory("planTasks", plan.tasks());
            context.putMemory("planSummary", orchestrationDecision.summarize());
            context.putMemory("threadContextPack", threadContextPack);
            context.putMemory("orchestrationDecision", orchestrationDecision);
            EvidenceLedger ledger = EvidenceLedger.from(context);
            SourcePlan sourcePlan = sourcePlanFor(resolvedPromptIntent, orchestrationDecision);
            context.putMemory("sourcePlan", sourcePlan);
            if (approvedMatch.isPresent()) {
                context.putMemory("approvedWorkflowMatch", approvedMatch.get());
            }
            if (orchestrationDecision.needsClarification()
                && orchestrationDecision.clarificationQuestion() != null
                && !orchestrationDecision.clarificationQuestion().isBlank()
                && canClarifyNow(sourcePlan, ledger)) {
                AgentExecutionResult clarificationResult = buildClarificationResult(
                    run.getId(),
                    plan,
                    resolvedPromptIntent,
                    orchestrationDecision,
                    orchestrationDecision.clarificationQuestion()
                );
                agentRunService.completeRun(run.getId(), clarificationResult);
                return Optional.of(clarificationResult);
            }

            List<ToolEvidence> evidenceHistory = new java.util.ArrayList<>();
            VerificationDecision latestVerification = null;
            OrchestrationAction nextAction = orchestrationDecision.nextAction();
            boolean synthesisExecuted = false;
            int stepsExecuted = 0;
            while (stepsExecuted < maxSteps) {
                if (nextAction == null) {
                    nextAction = llmOrchestrationService.decideNextAction(
                        orchestrationDecision,
                        threadContextPack,
                        resolvedPromptIntent,
                        evidenceHistory,
                        latestVerification,
                        stepsExecuted,
                        synthesisExecuted
                    );
                }
                if (nextAction == null || (nextAction.shouldStop() && synthesisExecuted)) {
                    break;
                }

                AgentPlanStep step = buildDynamicStep(orchestrationDecision, nextAction, stepsExecuted, resolvedPromptIntent);
                if (step == null || step.toolName() == null || step.toolName().isBlank()) {
                    break;
                }
                AgentTool tool = toolRegistry.getRequired(step.toolName());
                progressListener.onEvent(new AgentProgressEvent(
                    "step-" + (stepsExecuted + 1),
                    step.title(),
                    "Running " + tool.name(),
                    "active",
                    stepsExecuted + 1,
                    tool.name(),
                    run.getId(),
                    orchestrationDecision.summarize(),
                    null
                ));
                int verificationCountBefore = context.verificationReports().size();
                AgentToolResult result = tool.execute(step, context);
                context.recordToolExecution(tool.name(), result);
                agentRunService.recordStep(run.getId(), stepsExecuted + 1, step, result);
                progressListener.onEvent(new AgentProgressEvent(
                    "step-" + (stepsExecuted + 1),
                    step.title(),
                    result.observation() != null ? result.observation().summary() : "Step completed",
                    "completed",
                    stepsExecuted + 1,
                    tool.name(),
                    run.getId(),
                    orchestrationDecision.summarize(),
                    result.confidence()
                ));
                VerificationReport latestDeterministicReport = latestVerificationReport(context, verificationCountBefore);
                ToolEvidence latestEvidence = ToolEvidence.from(step, result, latestDeterministicReport);
                evidenceHistory.add(latestEvidence);
                ledger.recordToolEvidence(latestEvidence);
                stepsExecuted++;
                VerificationDecision verificationBeforeStep = latestVerification;

                if (latestEvidence.material()) {
                    progressListener.onEvent(new AgentProgressEvent(
                        "verify-" + stepsExecuted,
                        "Verify evidence",
                        "Checking whether the latest evidence is relevant, complete, and thread-consistent",
                        "active",
                        stepsExecuted,
                        "answer_verification",
                        run.getId(),
                        orchestrationDecision.summarize(),
                        null
                    ));
                    if (isSynthesisTool(step.toolName())
                        && verificationBeforeStep != null
                        && verificationBeforeStep.readyForSynthesis()) {
                        latestVerification = new VerificationDecision(
                            true,
                            verificationBeforeStep.acceptedInsufficiency(),
                            true,
                            false,
                            false,
                            Math.max(verificationBeforeStep.confidence(), latestEvidence.confidence()),
                            "Verified evidence has been synthesized into the final answer.",
                            "",
                            verificationBeforeStep.deterministicReport()
                        );
                    } else {
                        latestVerification = llmOrchestrationService.verifyProgress(
                            threadContextPack,
                            resolvedPromptIntent,
                            evidenceHistory,
                            latestEvidence,
                            sourcePlan,
                            ledger
                        );
                    }
                    recordSyntheticStep(
                        run.getId(),
                        stepsExecuted + 1000,
                        new AgentPlanStep(
                            "verification-" + stepsExecuted,
                            "Verify evidence",
                            "answer_verification",
                            Map.of(
                                "tool", latestEvidence.toolName(),
                                "reason", latestVerification.reason(),
                                "readyForSynthesis", latestVerification.readyForSynthesis(),
                                "shouldClarify", latestVerification.shouldClarify(),
                                "shouldContinue", latestVerification.shouldContinue()
                            ),
                            "verification",
                            List.of(step.id()),
                            "verification"
                        ),
                        new AgentToolResult(
                            new AgentObservation(
                                "verification_decision",
                                latestVerification.reason(),
                                Map.of(
                                    "passed", latestVerification.passed(),
                                    "acceptedInsufficiency", latestVerification.acceptedInsufficiency(),
                                    "readyForSynthesis", latestVerification.readyForSynthesis(),
                                    "shouldClarify", latestVerification.shouldClarify(),
                                    "shouldContinue", latestVerification.shouldContinue(),
                                    "criticNotes", latestVerification.criticNotes()
                                )
                            ),
                            null,
                            null,
                            latestVerification.confidence()
                        )
                    );
                    progressListener.onEvent(new AgentProgressEvent(
                        "verify-" + stepsExecuted,
                        "Verify evidence",
                        latestVerification.reason(),
                        "completed",
                        stepsExecuted,
                        "answer_verification",
                        run.getId(),
                        orchestrationDecision.summarize(),
                        latestVerification.confidence()
                    ));
                    latestVerification = enforceSourcePlanBeforeStop(latestVerification, sourcePlan, ledger);
                    if (latestVerification.shouldClarify() && !latestVerification.shouldContinue()) {
                        AgentExecutionResult clarificationResult = buildClarificationResult(
                            run.getId(),
                            plan,
                            resolvedPromptIntent,
                            orchestrationDecision,
                            buildClarificationMessage(latestEvidence, latestVerification)
                        );
                        agentRunService.completeRun(run.getId(), clarificationResult);
                        return Optional.of(clarificationResult);
                    }
                }

                if (isSynthesisTool(step.toolName())) {
                    synthesisExecuted = true;
                    if (hasComposedAnswer(context)) {
                        break;
                    }
                }

                nextAction = llmOrchestrationService.decideNextAction(
                    orchestrationDecision,
                    threadContextPack,
                    resolvedPromptIntent,
                    evidenceHistory,
                    latestVerification,
                    stepsExecuted,
                    synthesisExecuted
                );
            }
            if (!synthesisExecuted) {
                OrchestrationAction forcedSynthesis = new OrchestrationAction(
                    "Move to final synthesis with the strongest collected evidence.",
                    synthesisToolFor(orchestrationDecision.domain()),
                    Map.of(),
                    "A final synthesis pass is required before returning the answer.",
                    "Final verified answer",
                    false,
                    false,
                    ""
                );
                AgentPlanStep synthesisStep = buildDynamicStep(orchestrationDecision, forcedSynthesis, stepsExecuted, resolvedPromptIntent);
                if (synthesisStep != null) {
                    AgentTool synthesisTool = toolRegistry.getRequired(synthesisStep.toolName());
                    progressListener.onEvent(new AgentProgressEvent(
                        "step-" + (stepsExecuted + 1),
                        synthesisStep.title(),
                        "Running " + synthesisTool.name(),
                        "active",
                        stepsExecuted + 1,
                        synthesisTool.name(),
                        run.getId(),
                        orchestrationDecision.summarize(),
                        null
                    ));
                    int verificationCountBefore = context.verificationReports().size();
                    AgentToolResult synthesisResult = synthesisTool.execute(synthesisStep, context);
                    context.recordToolExecution(synthesisTool.name(), synthesisResult);
                    agentRunService.recordStep(run.getId(), stepsExecuted + 1, synthesisStep, synthesisResult);
                    progressListener.onEvent(new AgentProgressEvent(
                        "step-" + (stepsExecuted + 1),
                        synthesisStep.title(),
                        synthesisResult.observation() != null ? synthesisResult.observation().summary() : "Step completed",
                        "completed",
                        stepsExecuted + 1,
                        synthesisTool.name(),
                        run.getId(),
                        orchestrationDecision.summarize(),
                        synthesisResult.confidence()
                    ));
                    VerificationReport synthesisReport = latestVerificationReport(context, verificationCountBefore);
                    ToolEvidence synthesisEvidence = ToolEvidence.from(synthesisStep, synthesisResult, synthesisReport);
                    evidenceHistory.add(synthesisEvidence);
                    ledger.recordToolEvidence(synthesisEvidence);
                    latestVerification = llmOrchestrationService.verifyProgress(
                        threadContextPack,
                        resolvedPromptIntent,
                        evidenceHistory,
                        synthesisEvidence,
                        sourcePlan,
                        ledger
                    );
                    latestVerification = enforceSourcePlanBeforeStop(latestVerification, sourcePlan, ledger);
                    recordSyntheticStep(
                        run.getId(),
                        stepsExecuted + 1001,
                        new AgentPlanStep(
                            "verification-final",
                            "Verify evidence",
                            "answer_verification",
                            Map.of(
                                "tool", synthesisEvidence.toolName(),
                                "reason", latestVerification.reason(),
                                "readyForSynthesis", latestVerification.readyForSynthesis(),
                                "shouldClarify", latestVerification.shouldClarify(),
                                "shouldContinue", latestVerification.shouldContinue()
                            ),
                            "verification",
                            List.of(synthesisStep.id()),
                            "verification"
                        ),
                        new AgentToolResult(
                            new AgentObservation(
                                "verification_decision",
                                latestVerification.reason(),
                                Map.of(
                                    "passed", latestVerification.passed(),
                                    "acceptedInsufficiency", latestVerification.acceptedInsufficiency(),
                                    "readyForSynthesis", latestVerification.readyForSynthesis(),
                                    "shouldClarify", latestVerification.shouldClarify(),
                                    "shouldContinue", latestVerification.shouldContinue(),
                                    "criticNotes", latestVerification.criticNotes()
                                )
                            ),
                            null,
                            null,
                            latestVerification.confidence()
                        )
                    );
                    if (latestVerification.shouldClarify() && !latestVerification.shouldContinue()) {
                        AgentExecutionResult clarificationResult = buildClarificationResult(
                            run.getId(),
                            plan,
                            resolvedPromptIntent,
                            orchestrationDecision,
                            buildClarificationMessage(synthesisEvidence, latestVerification)
                        );
                        agentRunService.completeRun(run.getId(), clarificationResult);
                        return Optional.of(clarificationResult);
                    }
                }
            }
            return Optional.of(composeAndCompleteRun(
                run.getId(),
                plan,
                resolvedPromptIntent,
                orchestrationDecision,
                context,
                progressListener
            ));
        } catch (Exception e) {
            if (runId != null) {
                agentRunService.failRun(runId, e);
            }
            log.warn("Agentic flow failed for question '{}': {}", question, e.getMessage(), e);
            return Optional.empty();
        }
    }

    private VerificationDecision enforceSourcePlanBeforeStop(
        VerificationDecision decision,
        SourcePlan sourcePlan,
        EvidenceLedger ledger
    ) {
        if (decision == null || sourcePlan == null || ledger == null) {
            return decision;
        }
        if (decision.shouldContinue() || decision.readyForSynthesis()) {
            return decision;
        }
        List<String> remaining = remainingSourceFamilies(sourcePlan, ledger);
        if (remaining.isEmpty()) {
            return decision;
        }
        return VerificationDecision.continueLoop(
            "Continuing because the latest evidence is insufficient and these planned source families have not been scouted yet: "
                + String.join(", ", remaining)
        );
    }

    private boolean canClarifyNow(SourcePlan sourcePlan, EvidenceLedger ledger) {
        return sourcePlan == null
            || sourcePlan.sourceFamilies().isEmpty()
            || remainingSourceFamilies(sourcePlan, ledger).isEmpty();
    }

    private List<String> remainingSourceFamilies(SourcePlan sourcePlan, EvidenceLedger ledger) {
        if (sourcePlan == null || sourcePlan.sourceFamilies().isEmpty()) {
            return List.of();
        }
        Set<String> attempted = ledger == null ? Set.of() : ledger.attemptedSourceFamilies();
        return sourcePlan.sourceFamilies().stream()
            .filter(source -> source != null && !source.isBlank())
            .filter(source -> !sourceSatisfiedByAttempt(source, attempted))
            .limit(6)
            .toList();
    }

    private boolean sourceSatisfiedByAttempt(String plannedSource, Set<String> attemptedSources) {
        if (attemptedSources == null || attemptedSources.isEmpty()) {
            return false;
        }
        String normalized = normalizeSourceFamily(plannedSource);
        return attemptedSources.stream()
            .map(this::normalizeSourceFamily)
            .anyMatch(attempted -> attempted.equals(normalized)
                || attempted.contains(normalized)
                || normalized.contains(attempted));
    }

    private String normalizeSourceFamily(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT)
            .replace('-', '_')
            .replace(' ', '_')
            .trim();
    }

    private AgentIntent mapAgentIntent(PromptIntent.Domain domain, AgentIntent fallback) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> AgentIntent.METADATA_ANALYSIS;
            case BI, GENERAL, UNSUPPORTED -> fallback == AgentIntent.NONE ? AgentIntent.UNIVERSAL_CHAT : fallback;
        };
    }

    private String orchestrationRouteTypeHint(PromptIntent promptIntent) {
        String explicit = stringConstraint(promptIntent, "routeType", null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        PromptIntent.Domain domain = promptIntent == null ? PromptIntent.Domain.UNSUPPORTED : promptIntent.domain();
        return routeTypeFor(domain);
    }

    private String orchestrationBrainTopicHint(PromptIntent promptIntent) {
        String explicit = stringConstraint(promptIntent, "brainTopic", null);
        if (explicit != null && !explicit.isBlank()) {
            return explicit;
        }
        if (promptIntent == null) {
            return "GENERAL";
        }
        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE) {
            if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.GROWTH)) {
                return "GROWTH";
            }
            if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.WORKLOAD)) {
                return "WORKLOAD";
            }
            if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.TUNING)) {
                return "TUNING";
            }
            return "PERFORMANCE";
        }
        if (promptIntent.domain() == PromptIntent.Domain.SCHEMA) {
            if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.RELATIONSHIP)) {
                return "RELATIONSHIPS";
            }
            if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN)) {
                return "KEY_COLUMNS";
            }
            return "SCHEMA";
        }
        return "GENERAL";
    }

    private String stringConstraint(PromptIntent promptIntent, String key, String fallback) {
        if (promptIntent == null || promptIntent.constraints() == null) {
            return fallback;
        }
        Object value = promptIntent.constraints().get(key);
        return value == null ? fallback : value.toString();
    }

    private ConversationExecutionMode applyThreadMode(
        OrchestrationDecision.ThreadMode threadMode,
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision,
        List<AgentExecutionContext.ConversationTurn> conversationHistory
    ) {
        if (threadMode == OrchestrationDecision.ThreadMode.REUSE_SCOPE
            || threadMode == OrchestrationDecision.ThreadMode.CLARIFICATION_REPLY) {
            ConversationCarryoverDecision scopedCarryover = carryoverDecision == null
                ? ConversationCarryoverDecision.empty()
                : carryoverDecision;
            if (!scopedCarryover.reusesPriorScope()) {
                scopedCarryover = new ConversationCarryoverDecision(
                    threadMode == OrchestrationDecision.ThreadMode.CLARIFICATION_REPLY
                        ? ConversationCarryoverDecision.ReuseMode.ANSWER_CLARIFICATION
                        : ConversationCarryoverDecision.ReuseMode.NARROW_EXISTING_SCOPE,
                    resolvedConversationContext != null ? resolvedConversationContext.matchedContextId() : null,
                    resolvedConversationContext != null ? resolvedConversationContext.matchedRouteType() : null,
                    resolvedConversationContext != null ? resolvedConversationContext.matchedStateStatus() : null,
                    resolvedConversationContext == null ? List.of() : listValue(resolvedConversationContext.resolvedContext().get("tables")),
                    resolvedConversationContext == null ? List.of() : listValue(resolvedConversationContext.resolvedContext().get("joinConditions")),
                    resolvedConversationContext == null ? null : stringValue(resolvedConversationContext.resolvedContext().get("chosenTemporalColumn")),
                    resolvedConversationContext == null ? null : stringValue(resolvedConversationContext.resolvedContext().get("metric")),
                    resolvedConversationContext == null ? List.of() : listValue(resolvedConversationContext.resolvedContext().get("filters")),
                    resolvedConversationContext == null ? 0.7d : Math.max(0.7d, resolvedConversationContext.relevanceScore()),
                    "LLM orchestration chose to preserve the prior scope for this turn."
                );
            }
            return new ConversationExecutionMode(
                resolvedConversationContext == null ? ResolvedConversationContext.empty() : resolvedConversationContext,
                scopedCarryover,
                conversationHistory == null ? List.of() : conversationHistory
            );
        }
        return new ConversationExecutionMode(
            ResolvedConversationContext.empty(),
            ConversationCarryoverDecision.empty(),
            List.of()
        );
    }

    private AgentPlan buildOrchestrationPlan(OrchestrationDecision orchestrationDecision, AgentIntent executionIntent) {
        AgentPlanStep primaryStep = orchestrationDecision.nextAction() != null && orchestrationDecision.nextAction().hasTool()
            ? buildDynamicStep(orchestrationDecision, orchestrationDecision.nextAction(), 0)
            : null;
        List<AgentPlanStep> steps = primaryStep == null
            ? List.of()
            : List.of(primaryStep);

        List<AgentPlanTask> tasks = new java.util.ArrayList<>();
        int ordinal = 1;
        for (String outline : orchestrationDecision.planOutline()) {
            if (outline == null || outline.isBlank()) {
                continue;
            }
            tasks.add(new AgentPlanTask(
                "task-" + ordinal,
                outline,
                taskKindFor(orchestrationDecision.domain()),
                List.of(),
                ordinal == 1 && orchestrationDecision.nextAction() != null ? orchestrationDecision.nextAction().tool() : "",
                orchestrationDecision.userGoal(),
                expectedOutputContract(orchestrationDecision.domain()),
                Map.of("plannedBy", "llm_orchestration")
            ));
            ordinal++;
        }
        if (tasks.isEmpty()) {
            tasks.add(new AgentPlanTask(
                "task-1",
                orchestrationDecision.userGoal() == null || orchestrationDecision.userGoal().isBlank()
                    ? "Answer the user's request"
                    : orchestrationDecision.userGoal(),
                taskKindFor(orchestrationDecision.domain()),
                List.of(),
                orchestrationDecision.nextAction() != null ? orchestrationDecision.nextAction().tool() : "",
                orchestrationDecision.userGoal(),
                expectedOutputContract(orchestrationDecision.domain()),
                Map.of("plannedBy", "llm_orchestration")
            ));
        }

        String goal = orchestrationDecision.userGoal() == null || orchestrationDecision.userGoal().isBlank()
            ? "Answer the user's request through unified orchestration"
            : orchestrationDecision.userGoal();
        return new AgentPlan(executionIntent, goal, List.copyOf(tasks), steps);
    }

    private AgentTaskKind taskKindFor(PromptIntent.Domain domain) {
        return switch (domain) {
            case BI -> AgentTaskKind.DATA_QUERY;
            case SCHEMA, PERFORMANCE, GENERAL, UNSUPPORTED -> AgentTaskKind.LOOKUP;
        };
    }

    private String expectedOutputContract(PromptIntent.Domain domain) {
        return switch (domain) {
            case BI -> "sql_results";
            case SCHEMA, PERFORMANCE -> "verified_metadata_evidence";
            case GENERAL, UNSUPPORTED -> "final_answer";
        };
    }

    private VerificationReport latestVerificationReport(AgentExecutionContext context, int verificationCountBefore) {
        if (context == null) {
            return null;
        }
        List<VerificationReport> reports = context.verificationReports();
        if (reports.size() <= verificationCountBefore) {
            return null;
        }
        return reports.get(reports.size() - 1);
    }

    private AgentPlanStep buildDynamicStep(
        OrchestrationDecision orchestrationDecision,
        OrchestrationAction action,
        int stepIndex
    ) {
        return buildDynamicStep(orchestrationDecision, action, stepIndex, null);
    }

    private AgentPlanStep buildDynamicStep(
        OrchestrationDecision orchestrationDecision,
        OrchestrationAction action,
        int stepIndex,
        PromptIntent promptIntent
    ) {
        if (action == null || !action.hasTool()) {
            return null;
        }
        java.util.LinkedHashMap<String, Object> params = new java.util.LinkedHashMap<>(action.toolArgs());
        params.putIfAbsent("routeType", routeTypeFor(orchestrationDecision.domain()));
        params.putIfAbsent("domain", orchestrationDecision.domain().name());
        params.putIfAbsent("brainTopic", promptIntent == null ? "GENERAL" : orchestrationBrainTopicHint(promptIntent));
        params.putIfAbsent("subjectTypes", promptIntent == null ? List.of() : promptIntent.subjectTypes().stream().map(Enum::name).toList());
        params.putIfAbsent("taskType", promptIntent == null ? "" : promptIntent.taskType().name());
        params.putIfAbsent("requestedOutput", promptIntent == null ? "" : promptIntent.requestedOutput().name());
        params.putIfAbsent("sourcePlan", sourcePlanFor(promptIntent, orchestrationDecision).sourceFamilies());
        params.putIfAbsent("dataRequest", orchestrationDecision.domain() == PromptIntent.Domain.BI);
        params.putIfAbsent("taskQuestion", "");
        params.putIfAbsent("questionSummary", orchestrationDecision.userGoal());
        params.putIfAbsent("taskTitle", action.whyThisTool());
        params.putIfAbsent("taskKind", orchestrationDecision.domain() == PromptIntent.Domain.BI ? AgentTaskKind.DATA_QUERY.name() : AgentTaskKind.LOOKUP.name());
        return new AgentPlanStep(
            "orchestration-step-" + (stepIndex + 1),
            humanStepTitle(action.tool()),
            action.tool(),
            Map.copyOf(params),
            "task-" + (stepIndex + 1),
            List.of(),
            "orchestration_tool"
        );
    }

    private SourcePlan sourcePlanFor(PromptIntent promptIntent, OrchestrationDecision orchestrationDecision) {
        PromptIntent.Domain domain = orchestrationDecision == null
            ? (promptIntent == null ? PromptIntent.Domain.UNSUPPORTED : promptIntent.domain())
            : orchestrationDecision.domain();
        if (domain == PromptIntent.Domain.PERFORMANCE) {
            if (promptIntent != null
                && promptIntent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN)
                && promptIntent.subjectTypes().contains(PromptIntent.SubjectType.QUERY)) {
                return new SourcePlan(List.of(
                    "key_column_analysis",
                    "column_anti_pattern",
                    "performance_action",
                    "query_lineage",
                    "index_recommendations",
                    "composite_index_recommendation",
                    "slow_query_history",
                    "live_advisor_fallback"
                ));
            }
            return new SourcePlan(List.of(
                "performance_action",
                "slow_query_history",
                "performance_snapshots",
                "query_regressions",
                "workload_profile",
                "knob_rankings",
                "capacity_forecasts",
                "active_query_snapshot",
                "live_advisor_fallback"
            ));
        }
        if (domain == PromptIntent.Domain.SCHEMA) {
            return new SourcePlan(List.of(
                "schema_snapshot",
                "schema_documentation",
                "semantic_model",
                "inferred_relationships",
                "table_classification",
                "live_metadata_fallback"
            ));
        }
        if (domain == PromptIntent.Domain.BI) {
            return new SourcePlan(List.of(
                "thread_context",
                "semantic_model",
                "schema_context",
                "sql_generation",
                "sql_validation",
                "read_only_sql_execution",
                "repair_retry"
            ));
        }
        return new SourcePlan(List.of("thread_context", "retrieval_context", "company_docs", "synthesis"));
    }

    private String routeTypeFor(PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> ChatQuestionRoutingService.RouteType.BRAIN_METADATA.name();
            case BI -> ChatQuestionRoutingService.RouteType.BI_QUERY.name();
            case GENERAL, UNSUPPORTED -> ChatQuestionRoutingService.RouteType.GENERAL.name();
        };
    }

    private OrchestrationDecision enforceThreadSourceOfTruth(
        OrchestrationDecision orchestrationDecision,
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent
    ) {
        if (orchestrationDecision == null
            || threadContextPack == null
            || promptIntent == null
            || !threadContextPack.hasMatchedContext()) {
            return orchestrationDecision;
        }
        String normalizedQuestion = (threadContextPack.effectiveQuestion() != null && !threadContextPack.effectiveQuestion().isBlank()
            ? threadContextPack.effectiveQuestion()
            : threadContextPack.rawQuestion());
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return orchestrationDecision;
        }
        String lower = normalizedQuestion.toLowerCase(Locale.ROOT);
        boolean priorQueryFollowUp = lower.contains("full query")
            || lower.contains("full sql")
            || lower.contains("query text")
            || lower.contains("full text")
            || lower.contains("sql text")
            || lower.matches(".*\\b(show|give|provide|return|share)\\b.*\\b(query|sql|statement|text)\\b.*")
            || mentionsSlowQueryOrdinal(lower) && (lower.contains("query")
                || lower.contains("sql")
                || lower.contains("statement")
                || lower.contains("text"))
            || lower.contains("scan")
            || lower.contains("rows")
            || lower.contains("slowness")
            || lower.contains("causing");
        if (!priorQueryFollowUp) {
            return orchestrationDecision;
        }
        String anchor = threadContextPack.matchedContext().anchorQuestion() == null
            ? ""
            : threadContextPack.matchedContext().anchorQuestion().toLowerCase(Locale.ROOT);
        String chainSummary = threadContextPack.matchedContext().chainSummary() == null
            ? ""
            : threadContextPack.matchedContext().chainSummary().toLowerCase(Locale.ROOT);
        boolean priorSlowQueryContext = promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            || anchor.contains("slow query")
            || chainSummary.contains("slow query")
            || anchor.contains("query performance")
            || chainSummary.contains("query performance");
        if (!priorSlowQueryContext) {
            return orchestrationDecision;
        }
        if (orchestrationDecision.domain() == PromptIntent.Domain.PERFORMANCE
            && orchestrationDecision.threadMode() != OrchestrationDecision.ThreadMode.FRESH) {
            return orchestrationDecision;
        }
        return new OrchestrationDecision(
            PromptIntent.Domain.PERFORMANCE,
            "Reuse the prior slow-query context, show the full SQL, and explain the scan behavior from cached performance evidence.",
            OrchestrationDecision.ThreadMode.REUSE_SCOPE,
            false,
            "",
            List.of(
                "Resolve metadata scope for the follow-up",
                "Reuse cached slow-query and vault conversation evidence",
                "Synthesize a DBA-style answer with the full query and scan explanation"
            ),
            new OrchestrationAction(
                "Start by resolving metadata scope for the prior-query follow-up.",
                "metadata_context_resolution_tool",
                Map.of(),
                "This turn references a prior verified slow-query artifact, so stay in the performance metadata path and reuse cached thread context first.",
                "Thread-scoped cached performance evidence",
                false,
                false,
                ""
            ),
            List.of(
                "Return the previously identified full SQL text",
                "Explain why the query is scanning so many rows from cached metrics",
                "Avoid broader retrieval or live metadata unless cached evidence is missing"
            ),
            Math.max(orchestrationDecision.confidence(), 0.86d),
            "THREAD_CONTEXT_OVERRIDE",
            "Prior verified slow-query context already contains the source SQL, so the runtime must stay on the cached performance path for this follow-up."
        );
    }

    private boolean mentionsSlowQueryOrdinal(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return false;
        }
        return normalizedQuestion.contains("first query")
            || normalizedQuestion.contains("1st query")
            || normalizedQuestion.contains("#1")
            || normalizedQuestion.contains("second query")
            || normalizedQuestion.contains("2nd query")
            || normalizedQuestion.contains("#2")
            || normalizedQuestion.contains("third query")
            || normalizedQuestion.contains("3rd query")
            || normalizedQuestion.contains("#3");
    }

    private String humanStepTitle(String toolName) {
        return switch (toolName) {
            case "metadata_context_resolution_tool" -> "Resolve metadata scope";
            case "metadata_evidence_lookup_tool" -> "Check cached metadata evidence";
            case "live_metadata_query_tool" -> "Query live metadata";
            case "metadata_result_synthesis_tool" -> "Synthesize metadata answer";
            case "context_resolution_tool" -> "Resolve shared context";
            case "universal_chat_tool" -> "Reason through the request";
            case "result_synthesis_tool" -> "Compose final answer";
            default -> "Run " + toolName;
        };
    }

    private boolean isSynthesisTool(String toolName) {
        return "metadata_result_synthesis_tool".equals(toolName)
            || "result_synthesis_tool".equals(toolName);
    }

    private String synthesisToolFor(PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> "metadata_result_synthesis_tool";
            case BI, GENERAL, UNSUPPORTED -> "result_synthesis_tool";
        };
    }

    private boolean hasComposedAnswer(AgentExecutionContext context) {
        return context.getMemory("metadataFinalAnswer") != null
            || context.getMemory("universalMessage") != null
            || context.getMemory("verifiedAnswerContract") != null;
    }

    private boolean shouldExecutePlannedSequence(OrchestrationDecision orchestrationDecision, AgentPlan plan) {
        if (orchestrationDecision == null || plan == null || plan.tasks() == null || plan.steps() == null) {
            return false;
        }
        long executableTaskCount = plan.tasks().stream()
            .filter(task -> task.kind() != AgentTaskKind.SYNTHESIS)
            .count();
        return orchestrationDecision.domain() == PromptIntent.Domain.BI && executableTaskCount > 1;
    }

    private AgentExecutionResult executePlannedSequence(
        String runId,
        AgentPlan plan,
        PromptIntent resolvedPromptIntent,
        OrchestrationDecision orchestrationDecision,
        AgentExecutionContext context,
        AgentProgressListener progressListener
    ) {
        List<AgentPlanStep> plannedSteps = plan.steps() == null ? List.of() : plan.steps();
        for (int index = 0; index < plannedSteps.size(); index++) {
            AgentPlanStep step = plannedSteps.get(index);
            AgentTool tool = toolRegistry.getRequired(step.toolName());
            progressListener.onEvent(new AgentProgressEvent(
                "step-" + (index + 1),
                step.title(),
                "Running " + tool.name(),
                "active",
                index + 1,
                tool.name(),
                runId,
                orchestrationDecision.summarize(),
                null
            ));
            AgentToolResult result = tool.execute(step, context);
            context.recordToolExecution(tool.name(), result);
            agentRunService.recordStep(runId, index + 1, step, result);
            progressListener.onEvent(new AgentProgressEvent(
                "step-" + (index + 1),
                step.title(),
                result.observation() != null ? result.observation().summary() : "Step completed",
                "completed",
                index + 1,
                tool.name(),
                runId,
                orchestrationDecision.summarize(),
                result.confidence()
            ));
        }
        return composeAndCompleteRun(runId, plan, resolvedPromptIntent, orchestrationDecision, context, progressListener);
    }

    private AgentExecutionResult composeAndCompleteRun(
        String runId,
        AgentPlan plan,
        PromptIntent resolvedPromptIntent,
        OrchestrationDecision orchestrationDecision,
        AgentExecutionContext context,
        AgentProgressListener progressListener
    ) {
        progressListener.onEvent(new AgentProgressEvent(
            "composing",
            "Composing response",
            "Analyzing tool output and assembling the final answer",
            "active",
            null,
            null,
            runId,
            orchestrationDecision.summarize(),
            null
        ));
        AgentExecutionResult composed = answerComposer.compose(plan, context);
        AgentExecutionResult persisted = new AgentExecutionResult(
            runId,
            composed.intent(),
            composed.message(),
            composed.primaryResult(),
            composed.planSummary(),
            composed.executedQueries(),
            composed.toolsUsed(),
            composed.confidence(),
            composed.taskResults(),
            composed.promptIntent() != null ? composed.promptIntent() : resolvedPromptIntent,
            composed.answerContract(),
            composed.verificationReport() != null
                ? composed.verificationReport()
                : context.verificationReports().stream().reduce((first, second) -> second).orElse(null)
        );
        agentRunService.completeRun(runId, persisted);
        progressListener.onEvent(new AgentProgressEvent(
            "composing",
            "Composing response",
            "Final answer ready",
            "completed",
            null,
            null,
            runId,
            orchestrationDecision.summarize(),
            composed.confidence()
        ));
        return persisted;
    }

    private AgentExecutionResult buildClarificationResult(
        String runId,
        AgentPlan plan,
        PromptIntent promptIntent,
        OrchestrationDecision orchestrationDecision,
        String clarificationQuestion
    ) {
        String normalizedClarification = normalizeClarificationQuestion(
            orchestrationDecision == null ? null : orchestrationDecision.userGoal(),
            clarificationQuestion
        );
        AnswerContract answerContract = new AnswerContract(
            "Clarification required",
            normalizedClarification,
            List.of(),
            List.of(),
            null,
            List.of(orchestrationDecision.reason()),
            List.of(),
            normalizedClarification
        );
        return new AgentExecutionResult(
            runId,
            mapAgentIntent(orchestrationDecision.domain(), plan.intent()),
            normalizedClarification,
            null,
            orchestrationDecision.summarize(),
            List.of(),
            List.of("llm_orchestration"),
            orchestrationDecision.confidence(),
            List.of(),
            promptIntent,
            answerContract,
            null
        );
    }

    private String normalizeClarificationQuestion(String userGoal, String clarificationQuestion) {
        if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
            return clarificationQuestion;
        }
        String normalizedGoal = userGoal == null ? "" : userGoal.toLowerCase(Locale.ROOT);
        String normalizedClarification = clarificationQuestion.toLowerCase(Locale.ROOT);
        boolean rankingPrompt = normalizedGoal.contains("top customer")
            || normalizedGoal.contains("rank")
            || normalizedGoal.contains("highest")
            || normalizedGoal.contains("best");
        if (rankingPrompt
            && (!normalizedClarification.contains("metric")
                || !normalizedClarification.contains("amount")
                || !normalizedClarification.contains("time"))) {
            return "Which metric or amount should define 'top customers', and what time period should I use? If you want, you can also tell me how many customers to show.";
        }
        return clarificationQuestion;
    }

    private String buildClarificationMessage(ToolEvidence latestEvidence, VerificationDecision latestVerification) {
        if (latestVerification != null && latestVerification.reason() != null && !latestVerification.reason().isBlank()) {
            return normalizeClarificationQuestion(latestVerification.reason(), latestVerification.reason());
        }
        if (latestEvidence != null && latestEvidence.summary() != null && !latestEvidence.summary().isBlank()) {
            return normalizeClarificationQuestion(latestEvidence.summary(), latestEvidence.summary());
        }
        return "I need one clarification before I can continue safely.";
    }

    private void recordSyntheticStep(String runId, int stepIndex, AgentPlanStep step, AgentToolResult result) {
        agentRunService.recordStep(runId, stepIndex, step, result);
    }

    private List<String> listValue(Object value) {
        if (value instanceof List<?> list) {
            return list.stream().map(String::valueOf).toList();
        }
        return List.of();
    }

    private String stringValue(Object value) {
        return value == null ? null : value.toString();
    }

    private record ConversationExecutionMode(
        ResolvedConversationContext resolvedConversationContext,
        ConversationCarryoverDecision carryoverDecision,
        List<AgentExecutionContext.ConversationTurn> conversationHistory
    ) {}
}
