package com.dbaagent.service.agent;

import com.dbaagent.service.ConversationCarryoverDecision;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.util.PatternUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@Slf4j
public class LlmOrchestrationService {

    private static final String INITIAL_SYSTEM_PROMPT = """
        You are the orchestration controller for DeepSQL chat.
        Decide how to solve the user's request before any tool executes.

        Rules:
        - Return valid JSON only. No markdown fences.
        - Treat deterministic route and intent hints as advisory, not authoritative.
        - Use thread context to decide whether the current turn continues the same scope or resets topic.
        - Prefer REUSE_SCOPE when the new turn is a refinement of the previous answer.
        - Prefer RESET_TOPIC only when the user clearly changes subject.
        - Choose exactly one next tool from the allowed tool list.
        - DeepSQL chat is read-only. Never plan destructive SQL.
        """;

    private static final String ACTION_SYSTEM_PROMPT = """
        You are the step controller for DeepSQL chat.
        Choose the next tool action based on the current request, prior tool evidence, and verification state.

        Rules:
        - Return valid JSON only. No markdown fences.
        - Use only one next tool from the allowed tool list.
        - If the latest evidence already satisfies the request, choose the appropriate synthesis tool.
        - If the request still lacks reliable evidence, continue gathering or refining evidence.
        - Ask for clarification only when the missing information must come from the user.
        - DeepSQL chat is read-only. Never choose a destructive action.
        """;

    private static final String CRITIC_SYSTEM_PROMPT = """
        You are the answer critic for DeepSQL chat.
        Judge whether the latest tool evidence is relevant, thread-consistent, and strong enough to move to final synthesis.

        Rules:
        - Return valid JSON only. No markdown fences.
        - Evaluate semantic relevance, thread consistency, and whether more evidence is needed.
        - Do not overrule deterministic safety. If evidence is weak or mismatched, require more work or clarification.
        """;

    private static final Map<String, String> TOOL_DESCRIPTIONS = Map.of(
        "metadata_context_resolution_tool", "Resolve the metadata request scope and requested schema objects.",
        "metadata_evidence_lookup_tool", "Fetch cached schema or performance evidence from the vault DB.",
        "live_metadata_query_tool", "Query live information_schema, performance_schema, or pg_catalog metadata.",
        "metadata_result_synthesis_tool", "Compose the final schema or performance answer from gathered metadata evidence.",
        "context_resolution_tool", "Retrieve shared schema, relationship docs, RAG, semantic, and company context before deeper reasoning.",
        "universal_chat_tool", "Reason through BI or general prompts, generate read-only SQL when needed, execute it safely, and return structured evidence.",
        "result_synthesis_tool", "Compose the final user-facing answer from the collected BI or general task outputs."
    );

    private final ChatClient chatClient;
    private final ObjectMapper objectMapper;

    public LlmOrchestrationService(ChatModel chatModel, ObjectMapper objectMapper) {
        this.chatClient = ChatClient.builder(chatModel).build();
        this.objectMapper = objectMapper;
    }

    public OrchestrationDecision decideInitial(
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent
    ) {
        List<String> allowedTools = allowedInitialTools(promptIntent.domain());
        try {
            List<Message> messages = List.of(
                new SystemMessage(INITIAL_SYSTEM_PROMPT),
                new UserMessage(buildInitialPrompt(threadContextPack, promptIntent, allowedTools))
            );
            String content = chatClient.prompt().messages(messages).call().content();
            JsonNode root = parseJson(content);
            if (root != null && root.isObject()) {
                return parseInitialDecision(root, threadContextPack, promptIntent, allowedTools, content);
            }
            log.warn("Initial orchestration decision returned invalid JSON; falling back");
        } catch (Exception e) {
            log.warn("Initial orchestration decision failed: {}", e.getMessage());
        }
        return fallbackInitialDecision(threadContextPack, promptIntent);
    }

    public OrchestrationAction decideNextAction(
        OrchestrationDecision decision,
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<ToolEvidence> evidenceHistory,
        VerificationDecision latestVerification,
        int stepIndex,
        boolean synthesisExecuted
    ) {
        List<String> allowedTools = allowedNextTools(decision, evidenceHistory, latestVerification, synthesisExecuted);
        if (allowedTools.isEmpty()) {
            return OrchestrationAction.stop("No remaining tools were available.");
        }
        Optional<OrchestrationAction> deterministicAction = deterministicNextAction(
            decision,
            threadContextPack,
            promptIntent,
            allowedTools,
            latestVerification,
            evidenceHistory,
            synthesisExecuted
        );
        if (deterministicAction.isPresent()) {
            return deterministicAction.get();
        }
        try {
            List<Message> messages = List.of(
                new SystemMessage(ACTION_SYSTEM_PROMPT),
                new UserMessage(buildActionPrompt(decision, threadContextPack, promptIntent, evidenceHistory, latestVerification, allowedTools, stepIndex, synthesisExecuted))
            );
            String content = chatClient.prompt().messages(messages).call().content();
            JsonNode root = parseJson(content);
            if (root != null && root.isObject()) {
                OrchestrationAction fallback = fallbackAction(
                    decision,
                    threadContextPack,
                    promptIntent,
                    allowedTools,
                    latestVerification,
                    evidenceHistory,
                    synthesisExecuted
                );
                return parseAction(root, allowedTools, fallback.tool());
            }
            log.warn("Next-action orchestration returned invalid JSON; falling back");
        } catch (Exception e) {
            log.warn("Next-action orchestration failed: {}", e.getMessage());
        }
        return fallbackAction(
            decision,
            threadContextPack,
            promptIntent,
            allowedTools,
            latestVerification,
            evidenceHistory,
            synthesisExecuted
        );
    }

    private Optional<OrchestrationAction> deterministicNextAction(
        OrchestrationDecision decision,
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<String> allowedTools,
        VerificationDecision latestVerification,
        List<ToolEvidence> evidenceHistory,
        boolean synthesisExecuted
    ) {
        if (decision == null || allowedTools.isEmpty()) {
            return Optional.empty();
        }

        if (latestVerification != null && latestVerification.readyForSynthesis() && !synthesisExecuted) {
            String synthesisTool = synthesisToolFor(decision.domain());
            if (allowedTools.contains(synthesisTool)) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is moving to final synthesis.",
                    synthesisTool,
                    Map.of(),
                    "The latest verified evidence is sufficient to compose the answer.",
                    "Final verified answer",
                    false,
                    false,
                    ""
                ));
            }
        }

        if (evidenceHistory.isEmpty()) {
            return Optional.empty();
        }

        ToolEvidence latest = evidenceHistory.getLast();
        String latestTool = latest.toolName();
        boolean shouldContinue = latestVerification == null || latestVerification.shouldContinue();
        boolean lowConfidenceDecision = "FALLBACK".equalsIgnoreCase(decision.decisionSource())
            || (decision.confidence() > 0 && decision.confidence() < 0.6d);

        if ("metadata_context_resolution_tool".equals(latestTool)
            && decision.domain() != null
            && (decision.domain() == PromptIntent.Domain.SCHEMA || decision.domain() == PromptIntent.Domain.PERFORMANCE)) {
            if (preferThreadContextEvidence(threadContextPack, promptIntent)
                && allowedTools.contains("metadata_evidence_lookup_tool")) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is checking cached thread-backed evidence before broader retrieval.",
                    "metadata_evidence_lookup_tool",
                    Map.of(),
                    "This follow-up references a prior verified query or finding, so vault-backed thread context should be reused before broader retrieval or live fallback.",
                    "Cached evidence grounded in the prior thread",
                    false,
                    false,
                    ""
                ));
            }
            if (allowedTools.contains("context_resolution_tool")) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is pulling retrieval context before evaluating cached metadata.",
                    "context_resolution_tool",
                    Map.of(),
                    "For schema and performance reasoning, vault-backed documentation and semantic context should be available before we decide cached evidence is insufficient.",
                    "Shared schema and documentation context",
                    false,
                    false,
                    ""
                ));
            }
            if (allowedTools.contains("metadata_evidence_lookup_tool")) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is checking cached metadata next.",
                    "metadata_evidence_lookup_tool",
                    Map.of(),
                    "Metadata scope has been resolved, so the next robust step is to inspect cached vault evidence.",
                    "Cached schema or performance evidence",
                    false,
                    false,
                    ""
                ));
            }
        }

        if ("context_resolution_tool".equals(latestTool)
            && decision.domain() != null
            && (decision.domain() == PromptIntent.Domain.SCHEMA || decision.domain() == PromptIntent.Domain.PERFORMANCE)
            && allowedTools.contains("metadata_evidence_lookup_tool")) {
            return Optional.of(new OrchestrationAction(
                "Deterministic orchestration is re-checking cached metadata with the retrieved relationship and documentation context in hand.",
                "metadata_evidence_lookup_tool",
                Map.of(),
                "Shared retrieval context is now available, so re-evaluate vault-backed schema or performance evidence before falling back to live catalogs.",
                "Documentation-backed cached evidence",
                false,
                false,
                ""
            ));
        }

        if ("context_resolution_tool".equals(latestTool)
            && allowedTools.contains("universal_chat_tool")
            && (!latest.material() || lowConfidenceDecision || shouldContinue)) {
            return Optional.of(new OrchestrationAction(
                "Deterministic orchestration is moving from shared context into direct query reasoning.",
                "universal_chat_tool",
                Map.of(),
                "Context resolution completed; the next robust step is to reason over schema and run safe read-only SQL if needed.",
                "Concrete query evidence or a scoped direct answer",
                false,
                false,
                ""
            ));
        }

        if ("metadata_evidence_lookup_tool".equals(latestTool)
            && shouldContinue) {
            if (allowedTools.contains("context_resolution_tool")) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is pulling retrieval context before escalating to live catalogs.",
                    "context_resolution_tool",
                    Map.of(),
                    "Cached metadata alone was not enough, so the next robust step is to inspect vault-backed documentation and semantic context before live fallback.",
                    "Shared schema and documentation context",
                    false,
                    false,
                    ""
                ));
            }
            if (allowedTools.contains("live_metadata_query_tool")) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is escalating to live metadata catalogs.",
                    "live_metadata_query_tool",
                    Map.of(),
                    "Cached metadata was not enough on its own, so the next robust step is to query live metadata safely.",
                    "Live metadata evidence",
                    false,
                    false,
                    ""
                ));
            }
        }

        if ("universal_chat_tool".equals(latestTool)) {
            if (allowedTools.contains("result_synthesis_tool") && !shouldContinue) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is composing the final answer from the gathered BI evidence.",
                    "result_synthesis_tool",
                    Map.of(),
                    "The latest BI evidence is strong enough to synthesize.",
                    "Final stitched answer",
                    false,
                    false,
                    ""
                ));
            }
            if (allowedTools.contains("universal_chat_tool") && shouldContinue) {
                return Optional.of(new OrchestrationAction(
                    "Deterministic orchestration is continuing BI reasoning with the latest evidence.",
                    "universal_chat_tool",
                    Map.of(),
                    "The latest BI evidence is still incomplete or needs refinement, so continue reasoning and query repair.",
                    "Improved query evidence",
                    false,
                    false,
                    ""
                ));
            }
        }

        if ("live_metadata_query_tool".equals(latestTool)
            && allowedTools.contains("metadata_result_synthesis_tool")
            && !shouldContinue) {
            return Optional.of(new OrchestrationAction(
                "Deterministic orchestration is composing the final metadata answer.",
                "metadata_result_synthesis_tool",
                Map.of(),
                "Live metadata evidence is sufficient to synthesize the answer.",
                "Final metadata answer",
                false,
                false,
                ""
            ));
        }

        return Optional.empty();
    }

    public VerificationDecision verifyProgress(
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<ToolEvidence> evidenceHistory,
        ToolEvidence latestEvidence
    ) {
        return verifyProgress(threadContextPack, promptIntent, evidenceHistory, latestEvidence, null, null);
    }

    public VerificationDecision verifyProgress(
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<ToolEvidence> evidenceHistory,
        ToolEvidence latestEvidence,
        SourcePlan sourcePlan,
        EvidenceLedger evidenceLedger
    ) {
        VerificationReport deterministicReport = latestEvidence.verificationReport();
        boolean deterministicPass = deterministicReport != null
            && (deterministicReport.passed() || deterministicReport.verifiedInsufficiency());
        VerificationReport strongestPriorVerifiedReport = strongestPriorVerifiedReport(evidenceHistory, latestEvidence);

        if ("CLARIFICATION".equalsIgnoreCase(latestEvidence.status())) {
            Object clarificationPayload = latestEvidence.payload() == null
                ? null
                : latestEvidence.payload().get("clarificationMessage");
            String clarificationMessage = clarificationPayload instanceof String value && !value.isBlank()
                ? value
                : latestEvidence.summary() == null || latestEvidence.summary().isBlank()
                    ? "I need one clarification before I can continue safely."
                    : latestEvidence.summary();
            return new VerificationDecision(
                false,
                deterministicReport != null && deterministicReport.verifiedInsufficiency(),
                false,
                true,
                false,
                Math.max(0.75d, latestEvidence.confidence()),
                clarificationMessage,
                "",
                deterministicReport
            );
        }

        if (!latestEvidence.material()) {
            return VerificationDecision.continueLoop("The latest step updated context but did not produce answerable evidence yet.");
        }

        if (isSynthesisEvidence(latestEvidence)
            && (deterministicPass
                || (strongestPriorVerifiedReport != null
                    && (strongestPriorVerifiedReport.passed() || strongestPriorVerifiedReport.verifiedInsufficiency())))) {
            VerificationReport acceptedReport = deterministicPass ? deterministicReport : strongestPriorVerifiedReport;
            return new VerificationDecision(
                true,
                acceptedReport != null && acceptedReport.verifiedInsufficiency(),
                true,
                false,
                false,
                Math.max(0.8d, latestEvidence.confidence()),
                "Verified evidence is already sufficient and has been synthesized into the final answer.",
                "",
                acceptedReport
            );
        }

        try {
            List<Message> messages = List.of(
                new SystemMessage(CRITIC_SYSTEM_PROMPT),
                new UserMessage(buildCriticPrompt(threadContextPack, promptIntent, evidenceHistory, latestEvidence, deterministicReport, sourcePlan, evidenceLedger))
            );
            String content = chatClient.prompt().messages(messages).call().content();
            JsonNode root = parseJson(content);
            if (root != null && root.isObject()) {
                boolean answerRelevant = root.path("answerRelevant").asBoolean(deterministicPass);
                boolean threadConsistent = root.path("threadConsistent").asBoolean(true);
                boolean readyForSynthesis = root.path("readyForSynthesis").asBoolean(deterministicPass);
                boolean shouldClarify = root.path("shouldClarify").asBoolean(false);
                boolean shouldContinue = root.path("shouldContinue").asBoolean(!(readyForSynthesis || shouldClarify));
                String reason = text(root, "reason", deterministicReport != null ? deterministicReport.failureReason() : "");
                String notes = text(root, "criticNotes", "");
                double confidence = bounded(root.path("confidence").asDouble(deterministicPass ? 0.82 : 0.58));

                if (!answerRelevant || !threadConsistent) {
                    readyForSynthesis = false;
                    if (!shouldClarify) {
                        shouldContinue = true;
                    }
                }
                if (!deterministicPass && deterministicReport != null && !deterministicReport.verifiedInsufficiency()) {
                    readyForSynthesis = false;
                    if (!shouldClarify) {
                        shouldContinue = true;
                    }
                }
                if (shouldContinue) {
                    shouldClarify = false;
                }

                return new VerificationDecision(
                    deterministicPass && answerRelevant && threadConsistent,
                    deterministicReport != null && deterministicReport.verifiedInsufficiency(),
                    deterministicPass && answerRelevant && threadConsistent && readyForSynthesis,
                    shouldClarify,
                    shouldContinue,
                    confidence,
                    reason == null || reason.isBlank()
                        ? (deterministicPass ? "The latest evidence is strong enough to move to synthesis." : "More evidence is required before synthesis.")
                        : reason,
                    notes,
                    deterministicReport
                );
            }
        } catch (Exception e) {
            log.warn("Verification critic failed: {}", e.getMessage());
        }

        if (deterministicReport == null) {
            return VerificationDecision.continueLoop("The latest step produced evidence, but verification needs another pass.");
        }
        return new VerificationDecision(
            deterministicPass,
            deterministicReport.verifiedInsufficiency(),
            deterministicPass,
            !deterministicPass && deterministicReport.recommendedFallback() == VerificationReport.RecommendedFallback.CLARIFY,
            !deterministicPass,
            deterministicPass ? 0.78 : 0.55,
            deterministicPass
                ? "Deterministic verification accepted the latest evidence."
                : (deterministicReport.failureReason() == null ? "Deterministic verification requires more work." : deterministicReport.failureReason()),
            "",
            deterministicReport
        );
    }

    private String buildInitialPrompt(
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<String> allowedTools
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadContext", threadContextPack.toPromptPayload());
        payload.put("promptIntentHint", Map.of(
            "domain", promptIntent.domain().name(),
            "taskType", promptIntent.taskType().name(),
            "requestedOutput", promptIntent.requestedOutput().name(),
            "subjectTypes", promptIntent.subjectTypes().stream().map(Enum::name).toList()
        ));
        payload.put("allowedTools", renderAllowedTools(allowedTools));
        payload.put("responseSchema", Map.of(
            "domain", "SCHEMA | PERFORMANCE | BI | GENERAL",
            "userGoal", "string",
            "threadMode", "FRESH | REUSE_SCOPE | RESET_TOPIC | CLARIFICATION_REPLY",
            "needsClarification", "boolean",
            "clarificationQuestion", "string",
            "planOutline", List.of("string"),
            "nextAction", Map.of(
                "thoughtSummary", "string",
                "tool", "allowed tool name",
                "toolArgs", Map.of("key", "value"),
                "whyThisTool", "string",
                "expectedEvidence", "string",
                "shouldStop", false,
                "needsReplan", false,
                "clarificationQuestion", "optional string"
            ),
            "successCriteria", List.of("string"),
            "confidence", 0.0,
            "reason", "string"
        ));
        return toJson(payload);
    }

    private String buildActionPrompt(
        OrchestrationDecision decision,
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<ToolEvidence> evidenceHistory,
        VerificationDecision latestVerification,
        List<String> allowedTools,
        int stepIndex,
        boolean synthesisExecuted
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("decision", Map.of(
            "domain", decision.domain().name(),
            "userGoal", decision.userGoal(),
            "threadMode", decision.threadMode().name(),
            "planOutline", decision.planOutline(),
            "successCriteria", decision.successCriteria()
        ));
        payload.put("threadContext", threadContextPack.toPromptPayload());
        payload.put("promptIntentHint", Map.of(
            "domain", promptIntent.domain().name(),
            "taskType", promptIntent.taskType().name(),
            "requestedOutput", promptIntent.requestedOutput().name()
        ));
        payload.put("stepIndex", stepIndex);
        payload.put("synthesisExecuted", synthesisExecuted);
        payload.put("latestVerification", latestVerification == null ? null : Map.of(
            "passed", latestVerification.passed(),
            "acceptedInsufficiency", latestVerification.acceptedInsufficiency(),
            "readyForSynthesis", latestVerification.readyForSynthesis(),
            "shouldClarify", latestVerification.shouldClarify(),
            "shouldContinue", latestVerification.shouldContinue(),
            "reason", latestVerification.reason(),
            "criticNotes", latestVerification.criticNotes()
        ));
        payload.put("evidenceHistory", evidenceHistory.stream().map(evidence -> Map.of(
            "tool", evidence.toolName(),
            "summary", evidence.summary(),
            "status", evidence.status(),
            "confidence", evidence.confidence(),
            "material", evidence.material(),
            "verification", evidence.verificationReport() == null ? null : Map.of(
                "passed", evidence.verificationReport().passed(),
                "verifiedInsufficiency", evidence.verificationReport().verifiedInsufficiency(),
                "failureReason", evidence.verificationReport().failureReason(),
                "recommendedFallback", evidence.verificationReport().recommendedFallback().name()
            )
        )).toList());
        payload.put("allowedTools", renderAllowedTools(allowedTools));
        payload.put("responseSchema", Map.of(
            "thoughtSummary", "string",
            "tool", "allowed tool name",
            "toolArgs", Map.of("key", "value"),
            "whyThisTool", "string",
            "expectedEvidence", "string",
            "shouldStop", false,
            "needsReplan", false,
            "clarificationQuestion", "optional string",
            "reason", "string"
        ));
        return toJson(payload);
    }

    private String buildCriticPrompt(
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<ToolEvidence> evidenceHistory,
        ToolEvidence latestEvidence,
        VerificationReport deterministicReport,
        SourcePlan sourcePlan,
        EvidenceLedger evidenceLedger
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("threadContext", threadContextPack.toPromptPayload());
        payload.put("promptIntentHint", Map.of(
            "domain", promptIntent.domain().name(),
            "taskType", promptIntent.taskType().name(),
            "requestedOutput", promptIntent.requestedOutput().name(),
            "subjectTypes", promptIntent.subjectTypes().stream().map(Enum::name).toList()
        ));
        payload.put("latestEvidence", Map.of(
            "tool", latestEvidence.toolName(),
            "summary", latestEvidence.summary(),
            "status", latestEvidence.status(),
            "confidence", latestEvidence.confidence(),
            "executedQueries", latestEvidence.executedQueries(),
            "payload", latestEvidence.payload()
        ));
        payload.put("recentEvidenceHistory", evidenceHistory.stream()
            .skip(Math.max(0, evidenceHistory.size() - 4))
            .map(ToolEvidence::compactSummary)
            .toList());
        payload.put("sourcePlan", sourcePlan == null ? List.of() : sourcePlan.sourceFamilies());
        payload.put("evidenceLedger", evidenceLedger == null ? Map.of() : evidenceLedger.toTraceMap());
        payload.put("deterministicVerification", deterministicReport == null ? null : Map.of(
            "passed", deterministicReport.passed(),
            "verifiedInsufficiency", deterministicReport.verifiedInsufficiency(),
            "failureReason", deterministicReport.failureReason(),
            "intentMatchScore", deterministicReport.intentMatchScore(),
            "coverageScore", deterministicReport.coverageScore(),
            "sourceStrength", deterministicReport.sourceStrength().name(),
            "recommendedFallback", deterministicReport.recommendedFallback().name(),
            "notes", deterministicReport.notes()
        ));
        payload.put("responseSchema", Map.of(
            "answerRelevant", true,
            "threadConsistent", true,
            "readyForSynthesis", false,
            "shouldClarify", false,
            "shouldContinue", true,
            "reason", "string",
            "criticNotes", "string",
            "confidence", 0.0
        ));
        return toJson(payload);
    }

    private OrchestrationDecision parseInitialDecision(
        JsonNode root,
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<String> allowedTools,
        String rawResponse
    ) {
        PromptIntent.Domain domain = parseDomain(text(root, "domain", promptIntent.domain().name()), promptIntent.domain());
        OrchestrationDecision.ThreadMode threadMode = parseThreadMode(text(root, "threadMode", fallbackThreadMode(threadContextPack).name()), fallbackThreadMode(threadContextPack));
        boolean needsClarification = root.path("needsClarification").asBoolean(false);
        String clarificationQuestion = normalizeClarificationQuestion(
            threadContextPack == null ? "" : threadContextPack.rawQuestion(),
            text(root, "clarificationQuestion", "")
        );
        OrchestrationAction nextAction = parseAction(root.path("nextAction"), allowedTools, defaultInitialTool(domain));
        return new OrchestrationDecision(
            domain,
            text(root, "userGoal", defaultUserGoal(threadContextPack.rawQuestion(), domain)),
            threadMode,
            needsClarification,
            clarificationQuestion,
            stringList(root.path("planOutline")),
            nextAction,
            stringList(root.path("successCriteria")),
            bounded(root.path("confidence").asDouble(0.78)),
            "LLM",
            text(root, "reason", cap(rawResponse, 320))
        );
    }

    private OrchestrationAction parseAction(
        JsonNode node,
        List<String> allowedTools,
        String defaultTool
    ) {
        if (node == null || !node.isObject()) {
            return new OrchestrationAction(
                "Fallback tool selection",
                defaultTool,
                Map.of(),
                "No valid action payload was returned by the orchestration model.",
                "",
                false,
                false,
                ""
            );
        }
        String tool = text(node, "tool", defaultTool);
        if (!allowedTools.contains(tool) && !allowedTools.isEmpty()) {
            tool = allowedTools.contains(defaultTool) ? defaultTool : allowedTools.getFirst();
        }
        return new OrchestrationAction(
            text(node, "thoughtSummary", "Continue with the next best tool"),
            tool,
            objectNodeToMap(node.path("toolArgs")),
            text(node, "whyThisTool", "This tool best closes the next evidence gap."),
            text(node, "expectedEvidence", ""),
            node.path("shouldStop").asBoolean(false),
            node.path("needsReplan").asBoolean(false),
            text(node, "clarificationQuestion", "")
        );
    }

    private OrchestrationDecision fallbackInitialDecision(ThreadContextPack threadContextPack, PromptIntent promptIntent) {
        PromptIntent.Domain domain = promptIntent.domain() == PromptIntent.Domain.UNSUPPORTED
            ? fallbackDomainFromHints(threadContextPack)
            : promptIntent.domain();
        return new OrchestrationDecision(
            domain,
            defaultUserGoal(threadContextPack.rawQuestion(), domain),
            fallbackThreadMode(threadContextPack),
            false,
            "",
            defaultPlanOutline(domain),
            new OrchestrationAction(
                "Fallback orchestration selected the safest first tool.",
                defaultInitialTool(domain),
                Map.of(),
                "Use the most reliable first tool for this domain.",
                "Initial scoped evidence",
                false,
                false,
                ""
            ),
            defaultSuccessCriteria(domain),
            0.46,
            "FALLBACK",
            "The orchestration model did not return a usable decision."
        );
    }

    private OrchestrationAction fallbackAction(
        OrchestrationDecision decision,
        ThreadContextPack threadContextPack,
        PromptIntent promptIntent,
        List<String> allowedTools,
        VerificationDecision latestVerification,
        List<ToolEvidence> evidenceHistory,
        boolean synthesisExecuted
    ) {
        if (latestVerification != null && latestVerification.readyForSynthesis() && !synthesisExecuted) {
            String synthesisTool = synthesisToolFor(decision.domain());
            if (allowedTools.contains(synthesisTool)) {
                return new OrchestrationAction(
                    "Fallback orchestration is moving to final synthesis.",
                    synthesisTool,
                    Map.of(),
                    "The latest verified evidence is sufficient to compose the answer.",
                    "Final verified answer",
                    false,
                    false,
                    ""
                );
            }
        }

        String tool = allowedTools.contains(defaultInitialTool(decision.domain()))
            ? defaultInitialTool(decision.domain())
            : allowedTools.getFirst();
        if (!evidenceHistory.isEmpty()) {
            ToolEvidence latest = evidenceHistory.getLast();
            if ("metadata_context_resolution_tool".equals(latest.toolName())
                && preferThreadContextEvidence(threadContextPack, promptIntent)
                && allowedTools.contains("metadata_evidence_lookup_tool")) {
                tool = "metadata_evidence_lookup_tool";
            } else if ("metadata_evidence_lookup_tool".equals(latest.toolName()) && allowedTools.contains("live_metadata_query_tool")) {
                tool = "live_metadata_query_tool";
            } else if ("context_resolution_tool".equals(latest.toolName()) && allowedTools.contains("universal_chat_tool")) {
                tool = "universal_chat_tool";
            }
        }
        return new OrchestrationAction(
            "Fallback orchestration selected the next allowed tool.",
            tool,
            Map.of(),
            "Continue with the most reliable next step for this state.",
            "Additional answer evidence",
            false,
            false,
            latestVerification != null && latestVerification.shouldClarify() ? latestVerification.reason() : ""
        );
    }

    private PromptIntent.Domain fallbackDomainFromHints(ThreadContextPack threadContextPack) {
        String routeType = threadContextPack.routeTypeHint() == null ? "" : threadContextPack.routeTypeHint().toUpperCase(Locale.ROOT);
        if ("BRAIN_METADATA".equals(routeType)) {
            String topic = threadContextPack.brainTopicHint() == null ? "" : threadContextPack.brainTopicHint().toUpperCase(Locale.ROOT);
            if (Set.of("PERFORMANCE", "WORKLOAD", "TUNING", "GROWTH").contains(topic)) {
                return PromptIntent.Domain.PERFORMANCE;
            }
            return PromptIntent.Domain.SCHEMA;
        }
        if ("BI_QUERY".equals(routeType)) {
            return PromptIntent.Domain.BI;
        }
        return PromptIntent.Domain.GENERAL;
    }

    private OrchestrationDecision.ThreadMode fallbackThreadMode(ThreadContextPack threadContextPack) {
        if (threadContextPack == null || threadContextPack.carryoverHint() == null) {
            return OrchestrationDecision.ThreadMode.FRESH;
        }
        return switch (threadContextPack.carryoverHint().reuseMode()) {
            case ANSWER_CLARIFICATION -> OrchestrationDecision.ThreadMode.CLARIFICATION_REPLY;
            case NARROW_EXISTING_SCOPE -> OrchestrationDecision.ThreadMode.REUSE_SCOPE;
            case NEW_INTENT -> OrchestrationDecision.ThreadMode.RESET_TOPIC;
            case NONE -> OrchestrationDecision.ThreadMode.FRESH;
        };
    }

    private List<String> allowedInitialTools(PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> List.of(
                "metadata_context_resolution_tool",
                "context_resolution_tool",
                "metadata_evidence_lookup_tool",
                "live_metadata_query_tool"
            );
            case BI, GENERAL, UNSUPPORTED -> List.of(
                "context_resolution_tool",
                "universal_chat_tool"
            );
        };
    }

    private List<String> allowedNextTools(
        OrchestrationDecision decision,
        List<ToolEvidence> evidenceHistory,
        VerificationDecision latestVerification,
        boolean synthesisExecuted
    ) {
        if (decision == null) {
            return List.of();
        }
        if (latestVerification != null && latestVerification.shouldClarify() && !latestVerification.shouldContinue()) {
            return List.of();
        }
        if (latestVerification != null && latestVerification.readyForSynthesis() && !synthesisExecuted) {
            return List.of(synthesisToolFor(decision.domain()));
        }

        boolean hasMetadataScope = evidenceHistory.stream().anyMatch(e -> "metadata_context_resolution_tool".equals(e.toolName()));
        boolean hasMetadataEvidence = evidenceHistory.stream().anyMatch(e -> "metadata_evidence_lookup_tool".equals(e.toolName()));
        boolean hasLiveMetadata = evidenceHistory.stream().anyMatch(e -> "live_metadata_query_tool".equals(e.toolName()));
        boolean hasContextResolution = evidenceHistory.stream().anyMatch(e -> "context_resolution_tool".equals(e.toolName()));
        boolean hasUniversal = evidenceHistory.stream().anyMatch(e -> "universal_chat_tool".equals(e.toolName()));

        return switch (decision.domain()) {
            case SCHEMA, PERFORMANCE -> {
                List<String> tools = new ArrayList<>();
                if (!hasMetadataScope) {
                    tools.add("metadata_context_resolution_tool");
                }
                if (!hasContextResolution) {
                    tools.add("context_resolution_tool");
                }
                if (!hasMetadataEvidence) {
                    tools.add("metadata_evidence_lookup_tool");
                }
                if (!hasLiveMetadata || (latestVerification != null && latestVerification.shouldContinue())) {
                    tools.add("live_metadata_query_tool");
                }
                if (hasMetadataEvidence || hasLiveMetadata) {
                    tools.add("metadata_result_synthesis_tool");
                }
                yield tools.stream().distinct().toList();
            }
            case BI, GENERAL, UNSUPPORTED -> {
                List<String> tools = new ArrayList<>();
                if (!hasContextResolution) {
                    tools.add("context_resolution_tool");
                }
                if (!hasUniversal || (latestVerification != null && latestVerification.shouldContinue())) {
                    tools.add("universal_chat_tool");
                }
                if (hasUniversal) {
                    tools.add("result_synthesis_tool");
                }
                yield tools.stream().distinct().toList();
            }
        };
    }

    private String synthesisToolFor(PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> "metadata_result_synthesis_tool";
            case BI, GENERAL, UNSUPPORTED -> "result_synthesis_tool";
        };
    }

    private boolean isSynthesisTool(String toolName) {
        return "result_synthesis_tool".equals(toolName)
            || "metadata_result_synthesis_tool".equals(toolName);
    }

    private boolean isSynthesisEvidence(ToolEvidence evidence) {
        if (evidence == null) {
            return false;
        }
        if (isSynthesisTool(evidence.toolName())) {
            return true;
        }
        if ("result_synthesis".equalsIgnoreCase(evidence.observationType())
            || "metadata_result_synthesis".equalsIgnoreCase(evidence.observationType())) {
            return true;
        }
        return evidence.payload().containsKey("completedTaskCount")
            && evidence.payload().containsKey("blockedTaskCount");
    }

    private boolean preferThreadContextEvidence(ThreadContextPack threadContextPack, PromptIntent promptIntent) {
        if (threadContextPack == null
            || promptIntent == null
            || !threadContextPack.hasMatchedContext()
            || threadContextPack.matchedContext().sourceSql() == null
            || threadContextPack.matchedContext().sourceSql().isBlank()) {
            return false;
        }
        String question = threadContextPack.effectiveQuestion() != null && !threadContextPack.effectiveQuestion().isBlank()
            ? threadContextPack.effectiveQuestion()
            : threadContextPack.rawQuestion();
        if (question == null || question.isBlank()) {
            return false;
        }
        String normalized = question.toLowerCase(Locale.ROOT);
        boolean priorQueryFollowUp = normalized.contains("full query")
            || normalized.contains("full sql")
            || normalized.contains("query text")
            || PatternUtil.containsPattern(normalized, "\\bshow\\b.*\\b(query|sql)\\b")
            || normalized.contains("scan")
            || normalized.contains("rows")
            || normalized.contains("slowness")
            || normalized.contains("causing");
        if (!priorQueryFollowUp) {
            return false;
        }
        boolean diagnosticFollowUp = normalized.contains("scan")
            || normalized.contains("rows")
            || normalized.contains("slowness")
            || normalized.contains("causing")
            || normalized.contains("waiting")
            || normalized.contains("latency")
            || normalized.contains("why");
        return promptIntent.domain() == PromptIntent.Domain.PERFORMANCE || diagnosticFollowUp;
    }

    private VerificationReport strongestPriorVerifiedReport(List<ToolEvidence> evidenceHistory, ToolEvidence latestEvidence) {
        if (evidenceHistory == null || evidenceHistory.isEmpty()) {
            return null;
        }
        for (int index = evidenceHistory.size() - 1; index >= 0; index--) {
            ToolEvidence candidate = evidenceHistory.get(index);
            if (candidate == null || candidate == latestEvidence) {
                continue;
            }
            VerificationReport report = candidate.verificationReport();
            if (report != null && (report.passed() || report.verifiedInsufficiency())) {
                return report;
            }
        }
        return null;
    }

    private String normalizeClarificationQuestion(String rawQuestion, String clarificationQuestion) {
        if (clarificationQuestion == null || clarificationQuestion.isBlank()) {
            return clarificationQuestion;
        }
        String normalizedQuestion = rawQuestion == null ? "" : rawQuestion.toLowerCase(Locale.ROOT);
        String normalizedClarification = clarificationQuestion.toLowerCase(Locale.ROOT);
        boolean rankingPrompt = normalizedQuestion.contains("top ")
            || normalizedQuestion.contains("rank ")
            || normalizedQuestion.contains("highest ")
            || normalizedQuestion.contains("best ");
        if (rankingPrompt
            && (!normalizedClarification.contains("metric")
                || !normalizedClarification.contains("amount")
                || !normalizedClarification.contains("time"))) {
            return "Which metric or amount should define 'top customers', and what time period should I use? If you want, you can also tell me how many customers to show.";
        }
        return clarificationQuestion;
    }

    private String defaultInitialTool(PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> "metadata_context_resolution_tool";
            case BI, GENERAL, UNSUPPORTED -> "context_resolution_tool";
        };
    }

    private String defaultUserGoal(String question, PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA -> "Answer the schema metadata question accurately and from verified schema evidence for: " + question;
            case PERFORMANCE -> "Answer the performance question accurately and from verified performance evidence for: " + question;
            case BI -> "Answer the business data question with verified read-only SQL and explain the result for: " + question;
            case GENERAL, UNSUPPORTED -> "Answer the user's database-related question reliably using the best available evidence for: " + question;
        };
    }

    private List<String> defaultPlanOutline(PromptIntent.Domain domain) {
        return switch (domain) {
            case SCHEMA, PERFORMANCE -> List.of("Resolve request scope", "Check cached metadata evidence", "Fallback to live metadata if needed", "Synthesize verified answer");
            case BI, GENERAL, UNSUPPORTED -> List.of("Resolve shared context", "Reason with the best available tool", "Verify answer coverage", "Synthesize verified response");
        };
    }

    private List<String> defaultSuccessCriteria(PromptIntent.Domain domain) {
        return switch (domain) {
            case BI -> List.of("Use verified read-only SQL when data is required", "Return the metric or analysis the user asked for", "Stay consistent with the active thread scope");
            case SCHEMA -> List.of("Use schema-grounded evidence only", "Answer the exact requested schema fact or explanation", "Avoid unrelated metadata digressions");
            case PERFORMANCE -> List.of("Use performance or index evidence only", "Return a recommendation or explanation grounded in observed data", "Avoid generic schema summaries");
            case GENERAL, UNSUPPORTED -> List.of("Use the strongest grounded evidence available", "Stay aligned with the user's intent", "Ask one precise clarification only if necessary");
        };
    }

    private List<Map<String, String>> renderAllowedTools(List<String> allowedTools) {
        return allowedTools.stream()
            .map(tool -> Map.of(
                "tool", tool,
                "description", TOOL_DESCRIPTIONS.getOrDefault(tool, "No description available.")
            ))
            .toList();
    }

    private JsonNode parseJson(String rawContent) {
        if (rawContent == null || rawContent.isBlank()) {
            return null;
        }
        String cleaned = rawContent.trim()
            .replaceAll("^```json\\s*", "")
            .replaceAll("^```\\s*", "")
            .replaceAll("\\s*```$", "")
            .trim();
        int start = cleaned.indexOf('{');
        int end = cleaned.lastIndexOf('}');
        if (start >= 0 && end >= start) {
            cleaned = cleaned.substring(start, end + 1);
        }
        try {
            return objectMapper.readTree(cleaned);
        } catch (Exception e) {
            log.debug("Failed to parse orchestration JSON: {}", cleaned, e);
            return null;
        }
    }

    private Map<String, Object> objectNodeToMap(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isObject()) {
            return Map.of();
        }
        try {
            return objectMapper.convertValue(node, objectMapper.getTypeFactory().constructMapType(LinkedHashMap.class, String.class, Object.class));
        } catch (IllegalArgumentException e) {
            return Map.of();
        }
    }

    private PromptIntent.Domain parseDomain(String value, PromptIntent.Domain fallback) {
        try {
            return PromptIntent.Domain.valueOf(value == null ? fallback.name() : value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return fallback == null ? PromptIntent.Domain.GENERAL : fallback;
        }
    }

    private OrchestrationDecision.ThreadMode parseThreadMode(String value, OrchestrationDecision.ThreadMode fallback) {
        try {
            return OrchestrationDecision.ThreadMode.valueOf(value == null ? fallback.name() : value.toUpperCase(Locale.ROOT));
        } catch (Exception e) {
            return fallback == null ? OrchestrationDecision.ThreadMode.FRESH : fallback;
        }
    }

    private List<String> stringList(JsonNode node) {
        if (node == null || node.isMissingNode() || !node.isArray()) {
            return List.of();
        }
        List<String> values = new ArrayList<>();
        node.forEach(item -> {
            if (item != null && !item.isNull()) {
                String value = item.asText();
                if (value != null && !value.isBlank()) {
                    values.add(value);
                }
            }
        });
        return List.copyOf(values);
    }

    private String text(JsonNode node, String field, String fallback) {
        if (node == null || node.isMissingNode()) {
            return fallback;
        }
        JsonNode child = node.path(field);
        if (child.isMissingNode() || child.isNull()) {
            return fallback;
        }
        String value = child.asText();
        return value == null || value.isBlank() ? fallback : value;
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
        } catch (Exception e) {
            return String.valueOf(value);
        }
    }

    private static double bounded(double value) {
        if (Double.isNaN(value)) {
            return 0.0;
        }
        return Math.max(0.0d, Math.min(1.0d, value));
    }

    private static String cap(String value, int maxLen) {
        if (value == null) {
            return "";
        }
        String normalized = value.replaceAll("\\s+", " ").trim();
        if (normalized.length() <= maxLen) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLen - 3)) + "...";
    }
}
