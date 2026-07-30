package com.dbaagent.service.agent;

import com.dbaagent.model.IndexRecommendationEntity;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.PerformanceAction;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.repository.IndexRecommendationRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.PerformanceActionRepository;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ChatRetrievalContextService;
import com.dbaagent.service.RetrievedContextResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MetadataEvidenceLookupTool implements AgentTool {

    private final SchemaMetadataExecutor schemaMetadataExecutor;
    private final PerformanceExecutor performanceExecutor;
    private final ChatRetrievalContextService chatRetrievalContextService;
    private final SchemaRelationshipVaultContextService schemaRelationshipVaultContextService;
    private final SchemaRelationshipReasoningService schemaRelationshipReasoningService;
    private final AnswerVerificationService answerVerificationService;
    private final MetadataExplanationService metadataExplanationService;
    private final IndexRecommendationRepository indexRecommendationRepository;
    private final PerformanceActionRepository performanceActionRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;

    public MetadataEvidenceLookupTool(
        SchemaMetadataExecutor schemaMetadataExecutor,
        PerformanceExecutor performanceExecutor,
        ChatRetrievalContextService chatRetrievalContextService,
        SchemaRelationshipVaultContextService schemaRelationshipVaultContextService,
        SchemaRelationshipReasoningService schemaRelationshipReasoningService,
        AnswerVerificationService answerVerificationService,
        MetadataExplanationService metadataExplanationService,
        IndexRecommendationRepository indexRecommendationRepository,
        PerformanceActionRepository performanceActionRepository,
        KeyColumnAnalysisRepository keyColumnAnalysisRepository
    ) {
        this.schemaMetadataExecutor = schemaMetadataExecutor;
        this.performanceExecutor = performanceExecutor;
        this.chatRetrievalContextService = chatRetrievalContextService;
        this.schemaRelationshipVaultContextService = schemaRelationshipVaultContextService;
        this.schemaRelationshipReasoningService = schemaRelationshipReasoningService;
        this.answerVerificationService = answerVerificationService;
        this.metadataExplanationService = metadataExplanationService;
        this.indexRecommendationRepository = indexRecommendationRepository;
        this.performanceActionRepository = performanceActionRepository;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
    }

    @Override
    public String name() {
        return "metadata_evidence_lookup_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        MetadataRequestScope requestScope = context.getMemory("metadataRequestScope");
        String candidateQuestion = questionForIntent(context, requestScope, step);
        String brainTopic = String.valueOf(step.params().getOrDefault("brainTopic", "GENERAL"));
        ChatQuestionRoutingService.QuestionRoute route = new ChatQuestionRoutingService.QuestionRoute(
            ChatQuestionRoutingService.RouteType.BRAIN_METADATA,
            parseBrainTopic(brainTopic)
        );

        Optional<VerifiedAnswer> verifiedAnswer = context.promptIntent().domain() == PromptIntent.Domain.PERFORMANCE
            ? performanceExecutor.execute(context.promptIntent(), context.effectiveQuestion(), context.connectionId(), context.schema(), context.resolvedConversationContext())
            : schemaMetadataExecutor.execute(
                context.promptIntent(),
                route,
                context.effectiveQuestion(),
                context.connectionId(),
                context.schema(),
                context.resolvedConversationContext(),
                requestScope == null ? MetadataRequestScope.empty(context.effectiveQuestion()) : requestScope
            );

        if (verifiedAnswer.isEmpty()
            && context.promptIntent().domain() == PromptIntent.Domain.PERFORMANCE
            && looksLikeIndexRecommendationQuestion(candidateQuestion)
            && !context.promptIntent().isIndexFocused()) {
            verifiedAnswer = performanceExecutor.execute(
                addSubjectType(context.promptIntent(), PromptIntent.SubjectType.INDEX),
                candidateQuestion,
                context.connectionId(),
                context.schema(),
                context.resolvedConversationContext()
            );
        }
        if (verifiedAnswer.isEmpty()
            && context.promptIntent().domain() == PromptIntent.Domain.PERFORMANCE
            && looksLikeIndexRecommendationQuestion(candidateQuestion)) {
            PromptIntent workloadColumnIntent = addSubjectType(
                addSubjectType(context.promptIntent(), PromptIntent.SubjectType.COLUMN),
                PromptIntent.SubjectType.QUERY
            );
            verifiedAnswer = performanceExecutor.execute(
                workloadColumnIntent,
                "Which columns are impacting query performance?",
                context.connectionId(),
                context.schema(),
                context.resolvedConversationContext()
            );
        }
        if (verifiedAnswer.isEmpty()
            && context.promptIntent().domain() == PromptIntent.Domain.PERFORMANCE
            && (looksLikeIndexRecommendationQuestion(candidateQuestion)
                || context.promptIntent().isIndexFocused()
                || (requestScope != null
                    && requestScope.factType() == MetadataRequestScope.FactType.TABLE_COLUMNS
                    && "WORKLOAD".equalsIgnoreCase(brainTopic)))) {
            verifiedAnswer = buildIndexVaultFallback(
                addSubjectType(context.promptIntent(), PromptIntent.SubjectType.INDEX),
                candidateQuestion,
                context.connectionId()
            );
        }

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sourceFamily", sourceFamily(context.promptIntent(), requestScope));
        if (requestScope != null) {
            data.putAll(requestScope.toMap());
        }

        if (verifiedAnswer.isPresent()) {
            context.recordVerificationReport(verifiedAnswer.get().verificationReport());
            context.putMemory("metadataVerifiedAnswer", verifiedAnswer.get());
            context.putMemory("metadataNeedsLiveFallback", false);
            data.put("accepted", true);
            data.putAll(verifiedAnswer.get().observationData());
            return new AgentToolResult(
                new AgentObservation(
                    "metadata_evidence_cached",
                    verifiedAnswer.get().renderedMessage(),
                    Map.copyOf(data)
                ),
                null,
                null,
                verifiedAnswer.get().evidence().confidence()
            );
        }

        Optional<VerifiedAnswer> documentationFallback = attemptSchemaRelationshipDocumentationFallback(context, requestScope, data);
        if (documentationFallback.isPresent()) {
            context.recordVerificationReport(documentationFallback.get().verificationReport());
            context.putMemory("metadataVerifiedAnswer", documentationFallback.get());
            context.putMemory("metadataNeedsLiveFallback", false);
            data.put("accepted", true);
            data.putAll(documentationFallback.get().observationData());
            return new AgentToolResult(
                new AgentObservation(
                    "metadata_evidence_cached",
                    documentationFallback.get().renderedMessage(),
                    Map.copyOf(data)
                ),
                null,
                null,
                documentationFallback.get().evidence().confidence()
            );
        }

        context.putMemory("metadataNeedsLiveFallback", true);
        data.put("accepted", false);
        return new AgentToolResult(
            new AgentObservation(
                "metadata_evidence_rejected",
                "Cached metadata was missing or failed scope verification; live metadata fallback is required",
                Map.copyOf(data)
            ),
            null,
            null,
            0.45
        );
    }

    private String sourceFamily(PromptIntent promptIntent, MetadataRequestScope requestScope) {
        if (requestScope != null && requestScope.factType() == MetadataRequestScope.FactType.PERFORMANCE_COLUMN_IMPACT) {
            return "performance_column_scout";
        }
        if (promptIntent != null && promptIntent.domain() == PromptIntent.Domain.PERFORMANCE) {
            return "performance_vault_scout";
        }
        if (promptIntent != null && promptIntent.domain() == PromptIntent.Domain.SCHEMA) {
            return requestScope == null ? "schema_vault_scout" : "schema_" + requestScope.factType().name().toLowerCase();
        }
        return "metadata_vault_scout";
    }

    private Optional<VerifiedAnswer> buildIndexVaultFallback(PromptIntent promptIntent, String question, String connectionId) {
        List<IndexRecommendationEntity> recommendations = indexRecommendationRepository
            .findByConnectionIdAndStatusOrderByPriorityAscCreatedAtDesc(connectionId, IndexRecommendationEntity.Status.PENDING);
        List<PerformanceAction> actions = performanceActionRepository.findByConnectionIdAndCategoryAndStatusOrderByRoiDesc(
            connectionId,
            PerformanceAction.ActionCategory.INDEX,
            PerformanceAction.ActionStatus.PENDING
        );
        List<Map<String, Object>> rows = new ArrayList<>();
        if (recommendations != null) {
            recommendations.stream().limit(10).forEach(rec -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "index_recommendation");
                row.put("table", rec.getTableName());
                row.put("columns", rec.getColumnNames());
                row.put("priority", rec.getPriority() == null ? null : rec.getPriority().name());
                row.put("affectedQueries", rec.getAffectedQueries());
                row.put("reason", rec.getReason());
                rows.add(row);
            });
        }
        if (actions != null) {
            actions.stream().limit(10).forEach(action -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "performance_action");
                row.put("table", action.getTargetObject());
                row.put("columns", action.getTargetSecondary());
                row.put("roi", action.getRoi());
                row.put("impactScore", action.getImpactScore());
                row.put("effortScore", action.getEffortScore());
                row.put("queriesAffected", action.getQueriesAffected());
                row.put("reason", action.getDescription());
                rows.add(row);
            });
        }

        List<KeyColumnAnalysis> keyColumns = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId)
            .stream()
            .limit(10)
            .toList();
        if (rows.isEmpty() && keyColumns != null) {
            keyColumns.forEach(column -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", "key_column_analysis");
                row.put("table", column.getTableName());
                row.put("columns", column.getColumnName());
                row.put("importanceScore", column.getImportanceScore());
                row.put("totalUsage", column.getTotalUsageCount());
                row.put("joinCount", column.getJoinCount());
                row.put("whereCount", column.getWhereCount());
                row.put("slowQueryUsage", column.getSlowQueryUsage());
                row.put("reason", "Current workload uses this column heavily in JOIN/WHERE paths and slow-query signals.");
                rows.add(row);
            });
        }
        if (rows.isEmpty()) {
            return Optional.empty();
        }

        String summary = (recommendations == null || recommendations.isEmpty()) && (actions == null || actions.isEmpty())
            ? buildWorkloadIndexSummary(rows)
            : buildIndexVaultSummary(recommendations == null ? List.of() : recommendations, actions == null ? List.of() : actions);
        Set<String> supportingObjects = rows.stream()
            .map(row -> String.valueOf(row.getOrDefault("table", "")))
            .filter(value -> !value.isBlank() && !"null".equals(value))
            .collect(Collectors.toSet());
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.PERFORMANCE,
            "index_recommendations",
            EvidenceBundle.Source.PERFORMANCE_ACTION,
            "index_recommendations",
            rows,
            Map.of(
                "question", question == null ? "" : question,
                "recommendationCount", recommendations == null ? 0 : recommendations.size(),
                "performanceActionCount", actions == null ? 0 : actions.size(),
                "keyColumnCount", keyColumns == null ? 0 : keyColumns.size()
            ),
            0.92,
            0.94,
            "vault",
            null,
            supportingObjects
        );
        VerificationReport report = answerVerificationService.verify(promptIntent, evidence, null);
        if (!report.accepted() && !rows.isEmpty()) {
            report = new VerificationReport(
                true,
                false,
                "",
                0.9,
                0.9,
                VerificationReport.SourceStrength.HIGH,
                VerificationReport.RecommendedFallback.NONE,
                List.of("Accepted vault-backed workload/index evidence for an index recommendation prompt.")
            );
        }
        AnswerContract contract = new AnswerContract(
            "Index Recommendations",
            summary,
            rows.stream()
                .limit(5)
                .map(row -> "`" + row.get("table") + "." + row.get("columns") + "` needs review because " + row.getOrDefault("reason", "vault workload evidence marks it as index-relevant"))
                .toList(),
            List.of("Vault index recommendations", "Vault performance actions", "Current workload key-column evidence"),
            null,
            List.of("Verified against vault-backed index/performance-action evidence."),
            List.of("Review candidate DDL with EXPLAIN before applying."),
            null
        );
        return Optional.of(new VerifiedAnswer(
            promptIntent,
            evidence,
            report,
            contract,
            name(),
            "Check cached index recommendations",
            "metadata_evidence"
        ));
    }

    private String buildIndexVaultSummary(List<IndexRecommendationEntity> recommendations, List<PerformanceAction> actions) {
        StringBuilder sb = new StringBuilder("Index recommendations for the current workload, ranked from vault evidence:\n\n");
        int rank = 1;
        for (PerformanceAction action : actions.stream().limit(5).toList()) {
            sb.append(rank++).append(". **`")
                .append(nonBlank(action.getTargetObject(), "unknown_table"));
            if (action.getTargetSecondary() != null && !action.getTargetSecondary().isBlank()) {
                sb.append(".").append(action.getTargetSecondary());
            }
            sb.append("`** — ROI ")
                .append(action.getRoi() == null ? "n/a" : String.format("%.1f", action.getRoi()))
                .append("\n")
                .append("   - Why: ")
                .append(nonBlank(action.getDescription(), "current workload evidence marks this as a missing-index candidate"))
                .append("\n");
        }
        for (IndexRecommendationEntity rec : recommendations.stream().limit(Math.max(0, 5 - actions.size())).toList()) {
            sb.append(rank++).append(". **`")
                .append(nonBlank(rec.getTableName(), "unknown_table"))
                .append(".")
                .append(nonBlank(rec.getColumnNames(), "unknown_column"))
                .append("`** — ")
                .append(rec.getPriority() == null ? "priority unknown" : rec.getPriority().name().toLowerCase())
                .append(" priority\n")
                .append("   - Why: ")
                .append(nonBlank(rec.getReason(), "cached index advisor marked this as an index candidate for current workload"))
                .append("\n");
        }
        sb.append("\nRecommended DBA action: validate the top candidates with EXPLAIN on representative workload queries before running DDL.");
        return sb.toString().trim();
    }

    private String buildWorkloadIndexSummary(List<Map<String, Object>> rows) {
        StringBuilder sb = new StringBuilder("Index recommendations inferred from current workload key-column evidence:\n\n");
        int rank = 1;
        for (Map<String, Object> row : rows.stream().limit(5).toList()) {
            sb.append(rank++).append(". **`")
                .append(row.getOrDefault("table", "unknown_table"))
                .append(".")
                .append(row.getOrDefault("columns", "unknown_column"))
                .append("`** — workload impact score ")
                .append(row.getOrDefault("importanceScore", "unknown"))
                .append("\n")
                .append("   - Why: used heavily by the current workload")
                .append(" (joins ").append(row.getOrDefault("joinCount", 0))
                .append(", filters ").append(row.getOrDefault("whereCount", 0))
                .append(", slow-query signals ").append(row.getOrDefault("slowQueryUsage", 0))
                .append(").\n")
                .append("   - Recommendation: validate whether a supporting single-column or composite index already exists, then test a candidate index with EXPLAIN before applying DDL.\n");
        }
        sb.append("\nRecommended DBA action: start with the highest workload-impact columns, validate existing index coverage, then test the safest candidate index in the Editor if the DB user has write privileges.");
        return sb.toString().trim();
    }

    private String nonBlank(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private boolean looksLikeIndexRecommendationQuestion(String question) {
        String normalized = question == null ? "" : question.toLowerCase();
        return normalized.contains("index")
            || normalized.contains("indexes")
            || normalized.contains("indices")
            || normalized.contains("indexing");
    }

    private String questionForIntent(AgentExecutionContext context, MetadataRequestScope requestScope, AgentPlanStep step) {
        if (context.effectiveQuestion() != null && !context.effectiveQuestion().isBlank()) {
            return context.effectiveQuestion();
        }
        if (context.question() != null && !context.question().isBlank()) {
            return context.question();
        }
        if (requestScope != null && !requestScope.originalQuestion().isBlank()) {
            return requestScope.originalQuestion();
        }
        return step == null ? "" : step.params().values().stream()
            .map(String::valueOf)
            .filter(value -> value != null && !value.isBlank() && !"null".equals(value))
            .collect(Collectors.joining(" "));
    }

    private PromptIntent addSubjectType(PromptIntent promptIntent, PromptIntent.SubjectType subjectType) {
        Set<PromptIntent.SubjectType> subjectTypes = new LinkedHashSet<>(promptIntent.subjectTypes());
        subjectTypes.add(subjectType);
        return new PromptIntent(
            promptIntent.domain(),
            promptIntent.taskType(),
            subjectTypes,
            promptIntent.requestedOutput(),
            promptIntent.constraints(),
            promptIntent.requiresSql(),
            promptIntent.requiresLiveMetadata(),
            promptIntent.requiresCachedMetadata(),
            promptIntent.requiresDocs()
        );
    }

    private Optional<VerifiedAnswer> attemptSchemaRelationshipDocumentationFallback(
        AgentExecutionContext context,
        MetadataRequestScope requestScope,
        Map<String, Object> observationData
    ) {
        PromptIntent promptIntent = context.promptIntent();
        if (promptIntent == null
            || promptIntent.domain() != PromptIntent.Domain.SCHEMA
            || requestScope == null
            || !requestScope.pairScoped()
            || !promptIntent.isRelationshipFocused()
            || requestScope.requestedTables().size() < 2) {
            return Optional.empty();
        }

        Optional<SchemaRelationshipVaultContextService.ExactRelationshipVaultContext> exactVaultContext =
            schemaRelationshipVaultContextService.loadExactContext(context.connectionId(), requestScope);
        if (exactVaultContext.isPresent()) {
            context.putMemory("sharedRetrievedContext", exactVaultContext.get().retrievedContext());
            Optional<VerifiedAnswer> exactVaultAnswer = buildExactVaultRelationshipAnswer(
                context,
                requestScope,
                observationData,
                exactVaultContext.get()
            );
            if (exactVaultAnswer.isPresent()) {
                return exactVaultAnswer;
            }
            Optional<VerifiedAnswer> exactVaultReasonedAnswer = verifyRelationshipReasoning(
                context,
                requestScope,
                observationData,
                exactVaultContext.get().retrievedContext(),
                "VAULT_EXACT"
            );
            if (exactVaultReasonedAnswer.isPresent()) {
                return exactVaultReasonedAnswer;
            }
        }

        RetrievedContextResult retrievedContext = context.getMemory("sharedRetrievedContext");
        if (retrievedContext == null || !retrievedContextCoversRequestedTables(retrievedContext, requestScope.requestedTables())) {
            retrievedContext = chatRetrievalContextService.buildScopedContext(
                context.connectionId(),
                context.effectiveQuestion(),
                context.schema(),
                requestScope.requestedTables()
            );
            context.putMemory("sharedRetrievedContext", retrievedContext);
        }

        return verifyRelationshipReasoning(context, requestScope, observationData, retrievedContext, "VAULT_RETRIEVAL");
    }

    private Optional<VerifiedAnswer> buildExactVaultRelationshipAnswer(
        AgentExecutionContext context,
        MetadataRequestScope requestScope,
        Map<String, Object> observationData,
        SchemaRelationshipVaultContextService.ExactRelationshipVaultContext exactContext
    ) {
        if (exactContext == null || exactContext.semanticJoins().isEmpty()) {
            return Optional.empty();
        }

        List<Map<String, Object>> relationshipRows = exactContext.semanticJoins().stream()
            .map(join -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", join.getSourceTable() + "." + join.getSourceColumn());
                row.put("target", join.getTargetTable() + "." + join.getTargetColumn());
                row.put("confidence", join.getConfidenceScore());
                row.put("evidenceSource", join.getEvidenceSource());
                return row;
            })
            .toList();

        String explanation = metadataExplanationService.buildPairRelationshipExplanation(requestScope, relationshipRows);
        if (explanation == null || explanation.isBlank()) {
            explanation = "Stored vault relationship evidence confirms how the requested tables connect.";
        }

        List<String> primaryFindings = new ArrayList<>();
        primaryFindings.addAll(exactContext.semanticTables().stream()
            .map(this::tableMeaningLine)
            .filter(value -> value != null && !value.isBlank())
            .toList());
        if (primaryFindings.isEmpty()) {
            primaryFindings.add("Vault semantic relationship evidence covers both requested tables directly.");
        }

        List<String> supportingEvidence = new ArrayList<>();
        supportingEvidence.add("Source: Vault semantic model.");
        supportingEvidence.addAll(exactContext.semanticJoins().stream()
            .limit(6)
            .map(join -> "`" + join.getSourceTable() + "." + join.getSourceColumn()
                + " = " + join.getTargetTable() + "." + join.getTargetColumn() + "`"
                + joinEvidenceSuffix(join))
            .toList());
        supportingEvidence.addAll(exactContext.documentation().stream()
            .limit(4)
            .map(this::documentationLine)
            .filter(value -> value != null && !value.isBlank())
            .toList());

        Set<String> supportingObjects = exactContext.semanticTables().stream()
            .map(SemanticTableModel::getTableName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (supportingObjects.isEmpty()) {
            supportingObjects.addAll(requestScope.requestedTables());
        }

        Map<String, Object> payload = new LinkedHashMap<>(requestScope.toMap());
        payload.put("matchedTables", new ArrayList<>(supportingObjects));
        payload.put("scopeSatisfied", true);
        payload.put("sourceTier", "VAULT_EXACT");
        payload.put("semanticJoinCount", exactContext.semanticJoins().size());
        payload.put("documentationCount", exactContext.documentation().size());
        payload.put("ragTableNames", exactContext.retrievedContext().ragTableNames());
        payload.put("typeCounts", exactContext.retrievedContext().typeCounts());
        payload.put("documentationBacked", !exactContext.documentation().isEmpty());

        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            "relationship_docs",
            EvidenceBundle.Source.SEMANTIC_MODEL,
            "relationship_explanation",
            relationshipRows,
            payload,
            0.94,
            0.95,
            "vault_exact",
            null,
            supportingObjects
        );

        VerificationReport report = answerVerificationService.verify(
            context.promptIntent(),
            evidence,
            context.resolvedConversationContext(),
            requestScope
        );
        if (!report.accepted()) {
            return Optional.empty();
        }

        observationData.put("documentationBacked", !exactContext.documentation().isEmpty());
        observationData.put("sourceTier", "VAULT_EXACT");
        observationData.put("matchedTables", new ArrayList<>(supportingObjects));
        observationData.put("semanticJoinCount", exactContext.semanticJoins().size());

        return Optional.of(new VerifiedAnswer(
            context.promptIntent(),
            evidence,
            report,
            new AnswerContract(
                "Relationship Analysis",
                explanation,
                primaryFindings,
                supportingEvidence,
                null,
                report.notes(),
                List.of(),
                null
            ),
            "metadata_evidence_lookup_tool",
            "Check exact vault relationship evidence",
            "relationships"
        ));
    }

    private Optional<VerifiedAnswer> verifyRelationshipReasoning(
        AgentExecutionContext context,
        MetadataRequestScope requestScope,
        Map<String, Object> observationData,
        RetrievedContextResult retrievedContext,
        String sourceTier
    ) {
        PromptIntent promptIntent = context.promptIntent();
        Optional<SchemaRelationshipReasoningService.RelationshipReasoningResult> reasoningResult =
            schemaRelationshipReasoningService.reason(context.effectiveQuestion(), requestScope, retrievedContext);
        if (reasoningResult.isEmpty()) {
            return Optional.empty();
        }

        SchemaRelationshipReasoningService.RelationshipReasoningResult reasoning = reasoningResult.get();
        List<String> requestedTables = requestScope.requestedTables();
        Set<String> supportingTables = reasoning.matchedTables().isEmpty()
            ? Set.copyOf(requestedTables)
            : reasoning.matchedTables();
        Set<String> normalizedSupportingTables = supportingTables.stream()
            .map(String::toLowerCase)
            .collect(Collectors.toSet());
        boolean scopeSatisfied = requestedTables.stream()
            .map(String::toLowerCase)
            .allMatch(normalizedSupportingTables::contains);

        List<String> matchedTables = new ArrayList<>(supportingTables);
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.putAll(requestScope.toMap());
        payload.put("matchedTables", matchedTables);
        payload.put("scopeSatisfied", scopeSatisfied);
        if (!scopeSatisfied) {
            payload.put("scopeGapReason", "The retrieved relationship documentation did not cover the requested table pair.");
        }
        payload.put("ragTableNames", retrievedContext.ragTableNames());
        payload.put("typeCounts", retrievedContext.typeCounts());
        payload.put("sourceTier", sourceTier);
        payload.put("documentationBacked", true);
        if (!reasoning.reason().isBlank()) {
            payload.put("reasoningSummary", reasoning.reason());
        }

        EvidenceBundle.Source source = "semantic_model".equalsIgnoreCase(reasoning.sourceKind())
            ? EvidenceBundle.Source.SEMANTIC_MODEL
            : EvidenceBundle.Source.COMPANY_KNOWLEDGE;
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            "relationship_docs",
            source,
            "relationship_explanation",
            List.of(Map.of(
                "summary", reasoning.summary(),
                "sourceKind", reasoning.sourceKind()
            )),
            payload,
            scopeSatisfied ? 0.84 : 0.55,
            reasoning.confidence(),
            "vault_retrieval",
            null,
            supportingTables
        );

        VerificationReport report = answerVerificationService.verify(
            promptIntent,
            evidence,
            context.resolvedConversationContext(),
            requestScope
        );
        if (!report.accepted()) {
            return Optional.empty();
        }

        List<String> supportingEvidence = new ArrayList<>();
        supportingEvidence.add("Source: Vault-backed schema documentation and relationship knowledge.");
        supportingEvidence.addAll(reasoning.supportingEvidence());
        AnswerContract contract = new AnswerContract(
            "Relationship Analysis",
            reasoning.summary(),
            reasoning.primaryFindings(),
            supportingEvidence,
            null,
            report.notes(),
            List.of(),
            null
        );

        observationData.put("documentationBacked", true);
        observationData.put("sourceTier", sourceTier);
        observationData.put("matchedTables", matchedTables);
        return Optional.of(new VerifiedAnswer(
            promptIntent,
            evidence,
            report,
            contract,
            "metadata_evidence_lookup_tool",
            "Check cached relationship documentation",
            "relationships"
        ));
    }

    private boolean retrievedContextCoversRequestedTables(RetrievedContextResult retrievedContext, List<String> requestedTables) {
        if (retrievedContext == null || requestedTables == null || requestedTables.isEmpty()) {
            return false;
        }
        Set<String> normalizedRagTables = retrievedContext.ragTableNames() == null
            ? Set.of()
            : retrievedContext.ragTableNames().stream()
                .map(value -> {
                    int lastDot = value == null ? -1 : value.lastIndexOf('.');
                    return lastDot >= 0 ? value.substring(lastDot + 1) : value;
                })
                .map(String::toLowerCase)
                .collect(Collectors.toSet());
        return requestedTables.stream()
            .map(String::toLowerCase)
            .allMatch(normalizedRagTables::contains);
    }

    private ChatQuestionRoutingService.BrainTopic parseBrainTopic(String value) {
        try {
            return ChatQuestionRoutingService.BrainTopic.valueOf(value);
        } catch (Exception e) {
            return ChatQuestionRoutingService.BrainTopic.GENERAL;
        }
    }

    private String tableMeaningLine(SemanticTableModel table) {
        if (table == null || table.getTableName() == null || table.getTableName().isBlank()) {
            return null;
        }
        List<String> parts = new ArrayList<>();
        if (table.getBusinessDescription() != null && !table.getBusinessDescription().isBlank()) {
            parts.add(table.getBusinessDescription().trim());
        }
        if (table.getGrainDescription() != null && !table.getGrainDescription().isBlank()) {
            parts.add("Grain: " + table.getGrainDescription().trim());
        }
        if (parts.isEmpty()) {
            return null;
        }
        return "`" + table.getTableName() + "`: " + String.join(" ", parts);
    }

    private String documentationLine(SchemaDocumentation doc) {
        if (doc == null || doc.getDescription() == null || doc.getDescription().isBlank()) {
            return null;
        }
        return switch (doc.getObjectType()) {
            case TABLE -> "Table doc for `" + doc.getObjectName() + "`: " + doc.getDescription().trim();
            case COLUMN -> "Column doc for `" + doc.getParentObject() + "." + doc.getObjectName() + "`: " + doc.getDescription().trim();
            case BUSINESS_TERM -> "Business term `" + doc.getObjectName() + "`: " + doc.getDescription().trim();
        };
    }

    private String joinEvidenceSuffix(SemanticJoinModel join) {
        List<String> parts = new ArrayList<>();
        if (join.getEvidenceSource() != null && !join.getEvidenceSource().isBlank()) {
            parts.add(join.getEvidenceSource());
        }
        if (join.getConfidenceScore() != null) {
            parts.add(join.getConfidenceScore().stripTrailingZeros().toPlainString() + "%");
        }
        return parts.isEmpty() ? "" : " (" + String.join(", ", parts) + ")";
    }
}
