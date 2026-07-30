package com.dbaagent.service.agent;

import com.dbaagent.service.ResolvedConversationContext;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Service
public class AnswerVerificationService {

    public VerificationReport verify(
        PromptIntent promptIntent,
        EvidenceBundle evidence,
        ResolvedConversationContext resolvedConversationContext
    ) {
        return verify(promptIntent, evidence, resolvedConversationContext, null);
    }

    public VerificationReport verify(
        PromptIntent promptIntent,
        EvidenceBundle evidence,
        ResolvedConversationContext resolvedConversationContext,
        MetadataRequestScope metadataRequestScope
    ) {
        if (promptIntent == null || evidence == null) {
            return new VerificationReport(
                false,
                false,
                "No intent or evidence was available to verify the answer.",
                0.0,
                0.0,
                VerificationReport.SourceStrength.LOW,
                VerificationReport.RecommendedFallback.CLARIFY,
                List.of("Verification could not run because required inputs were missing.")
            );
        }

        double intentMatchScore = scoreIntentMatch(promptIntent, evidence);
        double coverageScore = scoreCoverage(promptIntent, evidence);
        VerificationReport.SourceStrength sourceStrength = assessSourceStrength(promptIntent, evidence);
        boolean consistent = isConsistentWithConversation(promptIntent, evidence, resolvedConversationContext);
        MetadataScopeAssessment scopeAssessment = assessMetadataScope(promptIntent, evidence, metadataRequestScope);
        List<String> notes = buildNotes(promptIntent, evidence, intentMatchScore, coverageScore, sourceStrength, consistent);
        notes.addAll(scopeAssessment.notes());

        if (evidence.insufficiencyMessage() != null && !evidence.insufficiencyMessage().isBlank()) {
            boolean insufficiencyAccepted = intentMatchScore >= 0.55
                && sourceStrength != VerificationReport.SourceStrength.LOW;
            if (!scopeAssessment.accepted()) {
                insufficiencyAccepted = false;
            }
            return new VerificationReport(
                insufficiencyAccepted,
                insufficiencyAccepted,
                insufficiencyAccepted ? null : scopeAssessment.failureReason() != null
                    ? scopeAssessment.failureReason()
                    : "The insufficiency response was not grounded in the right evidence family.",
                intentMatchScore,
                Math.max(coverageScore, 0.7),
                sourceStrength,
                promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
                    ? VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR
                    : VerificationReport.RecommendedFallback.NONE,
                notes
            );
        }

        if (promptIntent.requiresSql() && evidence.source() != EvidenceBundle.Source.SQL_RESULT) {
            return new VerificationReport(
                false,
                false,
                "This request needs SQL-backed evidence, but no verified SQL result was produced.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.SQL_REPAIR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && promptIntent.isIndexFocused()
            && !performanceVaultSources().contains(evidence.source())) {
            return new VerificationReport(
                false,
                false,
                "Indexing questions must be answered from index, key-column, workload, slow-query, or live performance evidence.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        String answerType = evidence.answerType() == null ? "" : evidence.answerType().toLowerCase(Locale.ROOT);
        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && promptIntent.taskType() == PromptIntent.TaskType.RECOMMEND
            && evidence.insufficiencyMessage() == null
            && !(answerType.contains("recommend")
                || answerType.contains("action")
                || answerType.contains("index"))) {
            return new VerificationReport(
                false,
                false,
                "Performance recommendation questions must be answered with ranked actions or recommendation evidence, not generic metadata rows.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && promptIntent.taskType() == PromptIntent.TaskType.MONITOR
            && evidence.insufficiencyMessage() == null
            && !(answerType.contains("summary")
                || answerType.contains("health")
                || answerType.contains("change")
                || answerType.contains("regression")
                || answerType.contains("slow_query")
                || answerType.contains("active_query")
                || answerType.contains("table_usage")
                || answerType.contains("growth_risk")
                || answerType.contains("cardinality")
                || answerType.contains("workload"))) {
            return new VerificationReport(
                false,
                false,
                "Performance monitoring questions must be answered from monitoring or regression evidence, not generic metadata catalogs.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && promptIntent.subjectTypes().contains(PromptIntent.SubjectType.TUNING)
            && evidence.insufficiencyMessage() == null
            && !(answerType.contains("tuning")
                || answerType.contains("knob")
                || answerType.contains("workload"))) {
            return new VerificationReport(
                false,
                false,
                "Configuration tuning questions must be answered from workload or knob-ranking evidence, not generic slow-query matches.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && promptIntent.subjectTypes().contains(PromptIntent.SubjectType.WORKLOAD)
            && evidence.insufficiencyMessage() == null
            && !(answerType.contains("workload")
                || answerType.contains("tuning"))) {
            return new VerificationReport(
                false,
                false,
                "Workload-shape questions must be answered from workload characterization evidence, not generic performance snippets.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && promptIntent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN)
            && promptIntent.subjectTypes().contains(PromptIntent.SubjectType.QUERY)
            && evidence.insufficiencyMessage() == null
            && !(answerType.contains("column")
                || answerType.contains("index")
                || answerType.contains("lineage")
                || answerType.contains("anti_pattern")
                || answerType.contains("performance_action")
                || answerType.contains("cardinality"))) {
            return new VerificationReport(
                false,
                false,
                "Column-performance questions must be answered from key-column, anti-pattern, query-lineage, index, or performance-action evidence.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
            && answerType.contains("cardinality")
            && !Set.of(EvidenceBundle.Source.LIVE_METADATA, EvidenceBundle.Source.PERFORMANCE_VAULT).contains(evidence.source())) {
            return new VerificationReport(
                false,
                false,
                "Cardinality questions need Brain statistics or plan-history evidence.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR,
                notes
            );
        }

        if (promptIntent.domain() == PromptIntent.Domain.SCHEMA
            && promptIntent.isRelationshipFocused()
            && !Set.of(
                EvidenceBundle.Source.INFERRED_RELATIONSHIP,
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                EvidenceBundle.Source.LIVE_METADATA,
                EvidenceBundle.Source.COMPANY_KNOWLEDGE,
                EvidenceBundle.Source.SEMANTIC_MODEL
            ).contains(evidence.source())) {
            return new VerificationReport(
                false,
                false,
                "Relationship questions require relationship-aware schema evidence.",
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.LIVE_METADATA,
                notes
            );
        }

        if (!scopeAssessment.accepted()) {
            return new VerificationReport(
                false,
                false,
                scopeAssessment.failureReason(),
                intentMatchScore,
                coverageScore,
                sourceStrength,
                VerificationReport.RecommendedFallback.LIVE_METADATA,
                notes
            );
        }

        boolean passed = intentMatchScore >= 0.65
            && coverageScore >= 0.55
            && sourceStrength != VerificationReport.SourceStrength.LOW
            && consistent;

        return new VerificationReport(
            passed,
            false,
            passed ? null : failureReason(promptIntent, intentMatchScore, coverageScore, sourceStrength, consistent),
            intentMatchScore,
            coverageScore,
            sourceStrength,
            recommendedFallback(promptIntent),
            notes
        );
    }

    private MetadataScopeAssessment assessMetadataScope(
        PromptIntent promptIntent,
        EvidenceBundle evidence,
        MetadataRequestScope metadataRequestScope
    ) {
        if (promptIntent == null || promptIntent.domain() != PromptIntent.Domain.SCHEMA || metadataRequestScope == null) {
            return MetadataScopeAssessment.acceptedResult();
        }
        if (!metadataRequestScope.hasRequestedTables()) {
            return MetadataScopeAssessment.acceptedResult();
        }

        Object scopeSatisfied = evidence.structuredPayload().get("scopeSatisfied");
        if (scopeSatisfied instanceof Boolean bool && !bool) {
            String scopeGapReason = String.valueOf(evidence.structuredPayload().getOrDefault(
                "scopeGapReason",
                "The metadata evidence did not stay anchored on the requested schema objects."
            ));
            return MetadataScopeAssessment.rejectedResult(scopeGapReason);
        }

        Object matchedTablesObject = evidence.structuredPayload().get("matchedTables");
        Set<String> matchedTables = matchedTablesObject instanceof List<?> list
            ? list.stream().map(String::valueOf).map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet())
            : evidence.supportingObjectNames().stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
        Set<String> requestedTables = metadataRequestScope.requestedTables().stream()
            .map(value -> value.toLowerCase(Locale.ROOT))
            .collect(java.util.stream.Collectors.toSet());

        if (metadataRequestScope.pairScoped() && !matchedTables.containsAll(requestedTables)) {
            return MetadataScopeAssessment.rejectedResult(
                "The metadata evidence did not cover the requested table pair: "
                    + String.join(" and ", metadataRequestScope.requestedTables()) + "."
            );
        }

        if (metadataRequestScope.isSingleTableScoped() && !matchedTables.contains(metadataRequestScope.requestedTables().getFirst().toLowerCase(Locale.ROOT))) {
            return MetadataScopeAssessment.rejectedResult(
                "The metadata evidence was not anchored on the requested table `" + metadataRequestScope.requestedTables().getFirst() + "`."
            );
        }

        return MetadataScopeAssessment.acceptedResult();
    }

    private double scoreIntentMatch(PromptIntent promptIntent, EvidenceBundle evidence) {
        double score = 0.0;
        if (promptIntent.domain() == evidence.intentDomain()) {
            score += 0.45;
        }

        score += switch (promptIntent.domain()) {
            case SCHEMA -> evidence.source() == EvidenceBundle.Source.SCHEMA_SNAPSHOT
                || evidence.source() == EvidenceBundle.Source.TABLE_CLASSIFICATION
                || evidence.source() == EvidenceBundle.Source.KEY_COLUMN_ANALYSIS
                || evidence.source() == EvidenceBundle.Source.INFERRED_RELATIONSHIP
                || evidence.source() == EvidenceBundle.Source.LIVE_METADATA
                || ((promptIntent.isRelationshipFocused() || promptIntent.requiresDocs())
                    && (evidence.source() == EvidenceBundle.Source.COMPANY_KNOWLEDGE
                        || evidence.source() == EvidenceBundle.Source.SEMANTIC_MODEL)) ? 0.35 : 0.0;
            case PERFORMANCE -> evidence.source() == EvidenceBundle.Source.INDEX_RECOMMENDATION
                || evidence.source() == EvidenceBundle.Source.SLOW_QUERY
                || evidence.source() == EvidenceBundle.Source.LIVE_METADATA
                || performanceVaultSources().contains(evidence.source()) ? 0.35 : 0.0;
            case BI -> evidence.source() == EvidenceBundle.Source.SQL_RESULT ? 0.4 : 0.0;
            case GENERAL -> evidence.source() == EvidenceBundle.Source.COMPANY_KNOWLEDGE
                || evidence.source() == EvidenceBundle.Source.SEMANTIC_MODEL ? 0.35 : 0.0;
            case UNSUPPORTED -> 0.0;
        };

        String kind = evidence.evidenceKind() == null ? "" : evidence.evidenceKind().toLowerCase(Locale.ROOT);
        if (promptIntent.isIndexFocused() && kind.contains("index")) {
            score += 0.2;
        }
        if (promptIntent.isRelationshipFocused() && (kind.contains("relationship") || kind.contains("join"))) {
            score += 0.2;
        }
        if (promptIntent.subjectTypes().contains(PromptIntent.SubjectType.COLUMN) && kind.contains("column")) {
            score += evidence.source() == EvidenceBundle.Source.KEY_COLUMN_ANALYSIS
                || evidence.source() == EvidenceBundle.Source.COLUMN_ANTI_PATTERN
                || evidence.source() == EvidenceBundle.Source.QUERY_LINEAGE
                ? 0.2
                : 0.1;
        }
        return Math.min(score, 1.0);
    }

    private double scoreCoverage(PromptIntent promptIntent, EvidenceBundle evidence) {
        double score = Math.max(0.0, evidence.coverage());
        if (!evidence.primaryRows().isEmpty()) {
            score += 0.25;
        }
        if (!evidence.structuredPayload().isEmpty()) {
            score += 0.15;
        }
        if (promptIntent.requestedOutput() == PromptIntent.RequestedOutput.RANKING
            && evidence.answerType() != null
            && evidence.answerType().toLowerCase(Locale.ROOT).contains("ranking")) {
            score += 0.15;
        }
        if (promptIntent.requestedOutput() == PromptIntent.RequestedOutput.RECOMMENDATION
            && evidence.answerType() != null
            && evidence.answerType().toLowerCase(Locale.ROOT).contains("recommend")) {
            score += 0.2;
        }
        if (promptIntent.requestedOutput() == PromptIntent.RequestedOutput.SUMMARY
            && evidence.answerType() != null
            && evidence.answerType().toLowerCase(Locale.ROOT).contains("summary")) {
            score += 0.1;
        }
        return Math.min(score, 1.0);
    }

    private VerificationReport.SourceStrength assessSourceStrength(PromptIntent promptIntent, EvidenceBundle evidence) {
        return switch (evidence.source()) {
            case SQL_RESULT -> VerificationReport.SourceStrength.HIGH;
            case INDEX_RECOMMENDATION, COMPOSITE_INDEX_RECOMMENDATION, PERFORMANCE_ACTION, COLUMN_ANTI_PATTERN,
                 QUERY_LINEAGE, WORKLOAD_PROFILE, KNOB_RANKING, CAPACITY_FORECAST, ACTIVE_QUERY_SNAPSHOT,
                 PLAN_REGRESSION, PERFORMANCE_VAULT, LIVE_METADATA, SLOW_QUERY -> VerificationReport.SourceStrength.HIGH;
            case SCHEMA_SNAPSHOT, TABLE_CLASSIFICATION, KEY_COLUMN_ANALYSIS, INFERRED_RELATIONSHIP -> promptIntent.domain() == PromptIntent.Domain.SCHEMA
                || promptIntent.domain() == PromptIntent.Domain.PERFORMANCE
                ? VerificationReport.SourceStrength.HIGH
                : VerificationReport.SourceStrength.MEDIUM;
            case SEMANTIC_MODEL, COMPANY_KNOWLEDGE -> promptIntent.domain() == PromptIntent.Domain.GENERAL
                ? VerificationReport.SourceStrength.MEDIUM
                : (promptIntent.domain() == PromptIntent.Domain.SCHEMA
                    && (promptIntent.isRelationshipFocused() || promptIntent.requiresDocs()))
                    ? VerificationReport.SourceStrength.MEDIUM
                    : VerificationReport.SourceStrength.LOW;
        };
    }

    private Set<EvidenceBundle.Source> performanceVaultSources() {
        return Set.of(
            EvidenceBundle.Source.KEY_COLUMN_ANALYSIS,
            EvidenceBundle.Source.COLUMN_ANTI_PATTERN,
            EvidenceBundle.Source.INDEX_RECOMMENDATION,
            EvidenceBundle.Source.COMPOSITE_INDEX_RECOMMENDATION,
            EvidenceBundle.Source.PERFORMANCE_ACTION,
            EvidenceBundle.Source.QUERY_LINEAGE,
            EvidenceBundle.Source.WORKLOAD_PROFILE,
            EvidenceBundle.Source.KNOB_RANKING,
            EvidenceBundle.Source.CAPACITY_FORECAST,
            EvidenceBundle.Source.ACTIVE_QUERY_SNAPSHOT,
            EvidenceBundle.Source.PLAN_REGRESSION,
            EvidenceBundle.Source.PERFORMANCE_VAULT,
            EvidenceBundle.Source.SLOW_QUERY,
            EvidenceBundle.Source.LIVE_METADATA
        );
    }

    private boolean isConsistentWithConversation(
        PromptIntent promptIntent,
        EvidenceBundle evidence,
        ResolvedConversationContext resolvedConversationContext
    ) {
        if (resolvedConversationContext == null || !resolvedConversationContext.hasMatchedContext()) {
            return true;
        }
        Object resolvedTables = resolvedConversationContext.resolvedContext().get("tables");
        if (!(resolvedTables instanceof List<?> tables) || tables.isEmpty()) {
            return true;
        }
        if (evidence.supportingObjectNames().isEmpty()) {
            return promptIntent.domain() == PromptIntent.Domain.GENERAL
                || promptIntent.domain() == PromptIntent.Domain.PERFORMANCE;
        }
        return tables.stream()
            .filter(item -> item != null && !String.valueOf(item).isBlank())
            .map(String::valueOf)
            .map(value -> value.toLowerCase(Locale.ROOT))
            .anyMatch(table -> evidence.supportingObjectNames().stream()
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(name -> name.contains(table) || table.contains(name)));
    }

    private List<String> buildNotes(
        PromptIntent promptIntent,
        EvidenceBundle evidence,
        double intentMatchScore,
        double coverageScore,
        VerificationReport.SourceStrength sourceStrength,
        boolean consistent
    ) {
        List<String> notes = new ArrayList<>();
        notes.add("Intent domain: " + promptIntent.domain().name());
        notes.add("Evidence source: " + evidence.source().name());
        notes.add(String.format(Locale.ROOT, "Intent match score: %.2f", intentMatchScore));
        notes.add(String.format(Locale.ROOT, "Coverage score: %.2f", coverageScore));
        notes.add("Source strength: " + sourceStrength.name());
        if (!consistent) {
            notes.add("Conversation scope and evidence objects did not line up cleanly.");
        }
        return notes;
    }

    private String failureReason(
        PromptIntent promptIntent,
        double intentMatchScore,
        double coverageScore,
        VerificationReport.SourceStrength sourceStrength,
        boolean consistent
    ) {
        if (!consistent) {
            return "The candidate answer conflicts with the current conversation scope.";
        }
        if (intentMatchScore < 0.65) {
            return "The collected evidence does not match the user's requested intent closely enough.";
        }
        if (coverageScore < 0.55) {
            return "The collected evidence is too partial to answer the requested details safely.";
        }
        if (sourceStrength == VerificationReport.SourceStrength.LOW) {
            return "The answer was only supported by a weak evidence source for this prompt type.";
        }
        return "The answer did not pass verification for " + promptIntent.domain().name().toLowerCase(Locale.ROOT) + " prompts.";
    }

    private VerificationReport.RecommendedFallback recommendedFallback(PromptIntent promptIntent) {
        return switch (promptIntent.domain()) {
            case SCHEMA -> VerificationReport.RecommendedFallback.LIVE_METADATA;
            case PERFORMANCE -> VerificationReport.RecommendedFallback.PERFORMANCE_ADVISOR;
            case BI -> VerificationReport.RecommendedFallback.SQL_REPAIR;
            case GENERAL, UNSUPPORTED -> VerificationReport.RecommendedFallback.CLARIFY;
        };
    }

    private record MetadataScopeAssessment(boolean accepted, String failureReason, List<String> notes) {
        private static MetadataScopeAssessment acceptedResult() {
            return new MetadataScopeAssessment(true, null, List.of());
        }

        private static MetadataScopeAssessment rejectedResult(String failureReason) {
            return new MetadataScopeAssessment(false, failureReason, List.of("Metadata scope check failed: " + failureReason));
        }
    }
}
