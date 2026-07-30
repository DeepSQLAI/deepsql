package com.dbaagent.service.agent;

import com.dbaagent.model.QueryResult;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class MetadataAnswerSynthesisService {

    private final AnswerVerificationService answerVerificationService;
    private final MetadataExplanationService metadataExplanationService;

    public MetadataAnswerSynthesisService(
        AnswerVerificationService answerVerificationService,
        MetadataExplanationService metadataExplanationService
    ) {
        this.answerVerificationService = answerVerificationService;
        this.metadataExplanationService = metadataExplanationService;
    }

    public VerifiedAnswer synthesize(AgentExecutionContext context) {
        VerifiedAnswer cached = context.getMemory("metadataVerifiedAnswer");
        if (cached != null) {
            return cached;
        }

        MetadataRequestScope requestScope = context.getMemory("metadataRequestScope");
        QueryResult liveResult = context.getMemory("liveMetadataResult");
        String liveSql = context.getMemory("liveMetadataSql");
        String liveAnswerType = context.getMemory("liveMetadataAnswerType");
        String liveTableName = context.getMemory("liveMetadataTableName");

        if (liveResult == null) {
            return buildInsufficiency(context, requestScope, buildMetadataFailureMessage(context.promptIntent(), requestScope));
        }

        if (context.promptIntent() != null
            && context.promptIntent().domain() == PromptIntent.Domain.PERFORMANCE
            && !supportsPerformanceMetadataSynthesis(liveAnswerType)) {
            return buildInsufficiency(context, requestScope, buildMetadataFailureMessage(context.promptIntent(), requestScope));
        }

        MetadataEvidenceMatch evidenceMatch = resolveEvidenceMatch(requestScope, liveResult, liveAnswerType, liveTableName);
        EvidenceBundle evidence = buildLiveEvidence(context, requestScope, liveResult, liveSql, liveAnswerType, liveTableName, evidenceMatch);
        VerificationReport report = answerVerificationService.verify(
            context.promptIntent(),
            evidence,
            context.resolvedConversationContext(),
            requestScope
        );

        if (!report.accepted()) {
            return buildInsufficiency(
                context,
                requestScope,
                report.failureReason() != null ? report.failureReason() : buildNoRowsMessage(requestScope)
            );
        }

        AnswerContract answerContract = buildLiveAnswerContract(requestScope, liveResult, liveAnswerType, liveTableName, evidenceMatch, report, liveSql);
        return new VerifiedAnswer(
            context.promptIntent(),
            evidence,
            report,
            answerContract,
            "metadata_result_synthesis_tool",
            "Synthesize metadata answer",
            "synthesis"
        );
    }

    private VerifiedAnswer buildInsufficiency(AgentExecutionContext context, MetadataRequestScope requestScope, String message) {
        message = buildLedgerBackedInsufficiencyMessage(context, message);
        EvidenceBundle evidence = EvidenceBundle.insufficient(
            context.promptIntent().domain(),
            "metadata_insufficiency",
            EvidenceBundle.Source.LIVE_METADATA,
            requestScope != null ? requestScope.factType().name().toLowerCase(Locale.ROOT) : "general",
            requestScope != null ? requestScope.toMap() : Map.of(),
            0.6,
            0.6,
            null,
            requestScope != null ? Set.copyOf(requestScope.requestedTables()) : Set.of(),
            message
        );
        VerificationReport report = answerVerificationService.verify(
            context.promptIntent(),
            evidence,
            context.resolvedConversationContext(),
            requestScope
        );
        AnswerContract contract = new AnswerContract(
            "Metadata Analysis",
            message,
            List.of(),
            List.of("No verified metadata evidence satisfied the requested scope."),
            null,
            report.notes(),
            List.of(message),
            null
        );
        return new VerifiedAnswer(
            context.promptIntent(),
            evidence,
            report,
            contract,
            "metadata_result_synthesis_tool",
            "Synthesize metadata answer",
            "synthesis"
        );
    }

    private String buildLedgerBackedInsufficiencyMessage(AgentExecutionContext context, String fallbackMessage) {
        EvidenceLedger ledger = EvidenceLedger.from(context);
        SourcePlan sourcePlan = context.getMemory("sourcePlan");
        List<String> plannedSources = sourcePlan == null ? List.of() : sourcePlan.sourceFamilies();
        List<String> attemptedSources = ledger.attemptedSourceFamilies().stream().toList();
        if (plannedSources.isEmpty() && attemptedSources.isEmpty()) {
            return fallbackMessage;
        }
        StringBuilder sb = new StringBuilder();
        sb.append("I could not produce a verified answer from the available intelligence yet.\n\n");
        if (!attemptedSources.isEmpty()) {
            sb.append("**Sources checked**: ")
                .append(String.join(", ", attemptedSources.stream().limit(8).toList()))
                .append(".\n");
        }
        List<String> remaining = plannedSources.stream()
            .filter(source -> attemptedSources.stream().noneMatch(attempted -> attempted.equalsIgnoreCase(source)))
            .limit(6)
            .toList();
        if (!remaining.isEmpty()) {
            sb.append("**Sources still needed**: ")
                .append(String.join(", ", remaining))
                .append(".\n");
        }
        if (fallbackMessage != null && !fallbackMessage.isBlank()) {
            sb.append("\n").append(fallbackMessage);
        }
        return sb.toString().trim();
    }

    private MetadataEvidenceMatch resolveEvidenceMatch(
        MetadataRequestScope requestScope,
        QueryResult liveResult,
        String liveAnswerType,
        String liveTableName
    ) {
        List<String> requestedTables = requestScope == null ? List.of() : requestScope.requestedTables();
        List<String> matchedTables = new ArrayList<>();

        if (liveTableName != null && !liveTableName.isBlank()) {
            matchedTables.add(liveTableName);
        }
        if ("pair_relationships".equals(liveAnswerType) || "pair_join_columns".equals(liveAnswerType)) {
            for (List<Object> row : liveResult.getRows()) {
                if (row.size() > 0 && row.get(0) != null) {
                    matchedTables.add(String.valueOf(row.get(0)));
                }
                if (row.size() > 2 && row.get(2) != null) {
                    matchedTables.add(String.valueOf(row.get(2)));
                }
            }
        }

        List<String> distinctMatched = matchedTables.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .distinct()
            .toList();

        boolean scopeSatisfied;
        String scopeGapReason = "";
        if (requestScope == null || requestedTables.isEmpty()) {
            scopeSatisfied = true;
        } else if (requestScope.pairScoped()) {
            scopeSatisfied = distinctMatched.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(Collectors.toSet())
                .containsAll(requestedTables.stream().map(value -> value.toLowerCase(Locale.ROOT)).toList());
            if (!scopeSatisfied) {
                scopeGapReason = "I couldn't confirm a verified relationship for the requested table pair from the evidence gathered so far.";
            }
        } else {
            scopeSatisfied = distinctMatched.isEmpty()
                || distinctMatched.stream().anyMatch(value -> value.equalsIgnoreCase(requestedTables.getFirst()));
            if (!scopeSatisfied) {
                scopeGapReason = "Live metadata drifted away from the requested table scope.";
            }
        }

        return new MetadataEvidenceMatch(requestedTables, distinctMatched, scopeSatisfied, scopeGapReason);
    }

    private EvidenceBundle buildLiveEvidence(
        AgentExecutionContext context,
        MetadataRequestScope requestScope,
        QueryResult liveResult,
        String liveSql,
        String liveAnswerType,
        String liveTableName,
        MetadataEvidenceMatch evidenceMatch
    ) {
        Map<String, Object> payload = new LinkedHashMap<>();
        if (requestScope != null) {
            payload.putAll(requestScope.toMap());
        }
        payload.putAll(evidenceMatch.toMap());
        if (liveTableName != null && !liveTableName.isBlank()) {
            payload.put("tableName", liveTableName);
        }

        Set<String> supportingObjects = evidenceMatch.matchedTables().isEmpty()
            ? requestScope == null ? Set.of() : Set.copyOf(requestScope.requestedTables())
            : Set.copyOf(evidenceMatch.matchedTables());

        return liveResult.getRows().isEmpty()
            ? EvidenceBundle.insufficient(
                context.promptIntent().domain(),
                requestScope != null ? requestScope.factType().name().toLowerCase(Locale.ROOT) : "live_metadata",
                EvidenceBundle.Source.LIVE_METADATA,
                liveAnswerType != null ? liveAnswerType : "live_metadata",
                payload,
                0.7,
                0.86,
                null,
                supportingObjects,
                buildNoRowsMessage(requestScope)
            )
            : EvidenceBundle.sufficient(
                context.promptIntent().domain(),
                requestScope != null ? requestScope.factType().name().toLowerCase(Locale.ROOT) : "live_metadata",
                EvidenceBundle.Source.LIVE_METADATA,
                liveAnswerType != null ? liveAnswerType : "live_metadata",
                rowsAsMaps(liveResult),
                payload,
                evidenceMatch.scopeSatisfied() ? 0.92 : 0.45,
                0.9,
                null,
                liveSql,
                supportingObjects
            );
    }

    private AnswerContract buildLiveAnswerContract(
        MetadataRequestScope requestScope,
        QueryResult liveResult,
        String liveAnswerType,
        String liveTableName,
        MetadataEvidenceMatch evidenceMatch,
        VerificationReport report,
        String liveSql
    ) {
        String summary;
        List<String> supportingEvidence = new ArrayList<>();
        List<String> gaps = new ArrayList<>();

        if (liveResult.getRows().isEmpty()) {
            summary = buildNoRowsMessage(requestScope);
            gaps.add(summary);
            return new AnswerContract(
                "Metadata Analysis",
                summary,
                List.of(),
                supportingEvidence,
                liveSql,
                report.notes(),
                gaps,
                null
            );
        }

        if ("table_row_count".equals(liveAnswerType)) {
            Object rowCount = liveResult.getRows().getFirst().size() > 1
                ? liveResult.getRows().getFirst().get(1)
                : liveResult.getRows().getFirst().getFirst();
            summary = "Table `" + liveTableName + "` has an estimated **" + Objects.toString(rowCount, "unknown") + " rows** from the live database catalogs.";
            supportingEvidence.add("Source: Live database metadata catalogs.");
        } else if ("table_indexes".equals(liveAnswerType)) {
            summary = "Table `" + liveTableName + "` has **" + liveResult.getRows().size() + " indexes** in the live database catalogs.";
            supportingEvidence.addAll(liveResult.getRows().stream()
                .limit(12)
                .map(row -> "`" + Objects.toString(row.size() > 0 ? row.get(0) : "?", "?") + "` -> " + Objects.toString(row.size() > 1 ? row.get(1) : "-", "-"))
                .toList());
        } else if ("table_columns".equals(liveAnswerType)) {
            summary = "Table `" + liveTableName + "` has **" + liveResult.getRows().size() + " columns** in the live database catalogs.";
            supportingEvidence.addAll(liveResult.getRows().stream()
                .limit(20)
                .map(row -> "`" + Objects.toString(row.getFirst(), "?") + "` (`" + Objects.toString(row.size() > 1 ? row.get(1) : "?", "?") + "`)")
                .toList());
        } else if ("pair_relationships".equals(liveAnswerType) || "pair_join_columns".equals(liveAnswerType)) {
            List<Map<String, Object>> relationshipRows = rowsAsMaps(liveResult);
            boolean explanatory = requestScope != null && requestScope.prefersExplanation();
            if (explanatory && "pair_join_columns".equals(liveAnswerType)) {
                summary = metadataExplanationService.buildPairJoinColumnExplanation(requestScope, relationshipRows);
            } else if (explanatory) {
                summary = metadataExplanationService.buildPairRelationshipExplanation(requestScope, relationshipRows);
            } else {
                summary = null;
            }
            if (summary == null || summary.isBlank()) {
                summary = "Verified direct relationship metadata between `" + requestScope.requestedTables().getFirst()
                    + "` and `" + requestScope.requestedTables().get(1) + "`:";
            }
            supportingEvidence.addAll(liveResult.getRows().stream()
                .limit(12)
                .map(row -> "`" + Objects.toString(row.get(0), "?") + "." + Objects.toString(row.get(1), "?")
                    + "` -> `" + Objects.toString(row.get(2), "?") + "." + Objects.toString(row.get(3), "?") + "`")
                .toList());
        } else if ("table_key_columns".equals(liveAnswerType)) {
            summary = "Live metadata shows key/index-backed columns for `" + liveTableName + "`.";
            supportingEvidence.addAll(liveResult.getRows().stream()
                .limit(12)
                .map(row -> "`" + Objects.toString(row.get(1), "?") + "` via `" + Objects.toString(row.get(2), "?") + "`")
                .toList());
        } else {
            summary = "Verified metadata evidence is available for this request.";
            supportingEvidence.add("Source: Live database metadata catalogs.");
        }

        if (!evidenceMatch.scopeSatisfied() && !evidenceMatch.scopeGapReason().isBlank()) {
            gaps.add(evidenceMatch.scopeGapReason());
        }

        return new AnswerContract(
            "Metadata Analysis",
            summary,
            List.of(),
            supportingEvidence,
            liveSql,
            report.notes(),
            gaps,
            null
        );
    }

    private List<Map<String, Object>> rowsAsMaps(QueryResult liveResult) {
        List<String> columns = liveResult.getColumns() == null ? List.of() : liveResult.getColumns();
        return liveResult.getRows().stream()
            .map(row -> {
                Map<String, Object> values = new LinkedHashMap<>();
                for (int i = 0; i < columns.size(); i++) {
                    values.put(columns.get(i), i < row.size() ? row.get(i) : null);
                }
                return values;
            })
            .toList();
    }

    private String buildNoRowsMessage(MetadataRequestScope requestScope) {
        if (requestScope != null && requestScope.pairScoped() && requestScope.requestedTables().size() >= 2) {
            return "No verified direct relationship found between `"
                + requestScope.requestedTables().getFirst() + "` and `"
                + requestScope.requestedTables().get(1) + "` from the evidence gathered so far.";
        }
        if (requestScope != null && requestScope.isSingleTableScoped()) {
            return "No ledger-backed metadata answer is available for `" + requestScope.requestedTables().getFirst() + "` yet. The agent checked cached vault metadata and live metadata fallback, but the gathered evidence did not cover the requested object strongly enough.";
        }
        return "No ledger-backed metadata answer is available yet. The agent checked cached vault metadata and live metadata fallback, but the gathered evidence did not cover the request strongly enough.";
    }

    private boolean supportsPerformanceMetadataSynthesis(String liveAnswerType) {
        if (liveAnswerType == null || liveAnswerType.isBlank()) {
            return false;
        }
        String normalized = liveAnswerType.toLowerCase(Locale.ROOT);
        return normalized.contains("performance")
            || normalized.contains("regression")
            || normalized.contains("index")
            || normalized.contains("workload")
            || normalized.contains("cardinality")
            || normalized.contains("growth")
            || normalized.contains("active_query")
            || normalized.contains("table_usage")
            || normalized.contains("slow_query");
    }

    private String buildMetadataFailureMessage(PromptIntent promptIntent, MetadataRequestScope requestScope) {
        if (promptIntent == null || promptIntent.domain() != PromptIntent.Domain.PERFORMANCE) {
            return buildNoRowsMessage(requestScope);
        }
        if (promptIntent.isIndexFocused()) {
            return "No ledger-backed index answer is available yet. The agent checked cached index recommendations, key-column evidence, slow-query evidence, and live advisor fallback; the current sources were empty or too weak for a ranked DBA recommendation.";
        }
        if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.QUERY)) {
            return "No ledger-backed query-performance answer is available yet. The agent checked cached query performance evidence and live performance fallback; the current sources were empty or too weak to identify the requested query pressure safely.";
        }
        if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.WORKLOAD)) {
            return "No ledger-backed workload answer is available yet. The agent checked Brain workload profiles and tuning evidence, but those collectors are empty or stale for this connection.";
        }
        if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.GROWTH)) {
            return "No ledger-backed growth-risk answer is available yet. The agent checked capacity forecasts, growth anomalies, and Sentinel recommendations, but the available evidence is empty or stale.";
        }
        return "No ledger-backed performance answer is available yet. The agent checked cached vault performance evidence and live performance fallback; the current sources were empty or too weak for a safe DBA answer.";
    }
}
