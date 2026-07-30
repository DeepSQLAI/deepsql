package com.dbaagent.service.agent;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public class EvidenceLedger {
    public static final String MEMORY_KEY = "evidenceLedger";

    private final List<EvidenceItem> evidenceItems = new ArrayList<>();
    private final List<SourceAttempt> sourceAttempts = new ArrayList<>();
    private final List<ExhaustedSource> exhaustedSources = new ArrayList<>();

    public static EvidenceLedger from(AgentExecutionContext context) {
        if (context == null) {
            return new EvidenceLedger();
        }
        EvidenceLedger existing = context.getMemory(MEMORY_KEY);
        if (existing != null) {
            return existing;
        }
        EvidenceLedger ledger = new EvidenceLedger();
        context.putMemory(MEMORY_KEY, ledger);
        return ledger;
    }

    public void recordToolEvidence(ToolEvidence evidence) {
        if (evidence == null) {
            return;
        }
        String sourceFamily = sourceFamily(evidence);
        sourceAttempts.add(new SourceAttempt(
            evidence.toolName(),
            sourceFamily,
            evidence.status(),
            evidence.summary(),
            Instant.now(),
            Map.of("material", evidence.material(), "confidence", evidence.confidence())
        ));
        recordExpandedSourceAttempts(evidence);
        Object bundleObject = evidence.payload().get("evidenceBundle");
        if (bundleObject instanceof EvidenceBundle bundle) {
            evidenceItems.add(new EvidenceItem(
                bundle.intentDomain(),
                sourceFamily,
                bundle.source().name(),
                bundle.answerType(),
                bundle.primaryRows(),
                bundle.structuredPayload(),
                bundle.supportingObjectNames(),
                bundle.freshness(),
                bundle.confidence(),
                bundle.coverage(),
                bundle.insufficiencyMessage()
            ));
            if (!bundle.sufficient() || (bundle.insufficiencyMessage() != null && !bundle.insufficiencyMessage().isBlank())) {
                exhaustedSources.add(new ExhaustedSource(sourceFamily, bundle.insufficiencyMessage()));
            }
            return;
        }
        Object evidenceMapObject = evidence.payload().get("evidence");
        if (evidenceMapObject instanceof Map<?, ?> evidenceMap) {
            String failureReason = "";
            Object sufficient = evidenceMap.get("sufficient");
            Object insufficiency = evidenceMap.get("insufficiencyMessage");
            if (Boolean.FALSE.equals(sufficient) || (insufficiency != null && !String.valueOf(insufficiency).isBlank())) {
                failureReason = insufficiency == null ? evidence.summary() : String.valueOf(insufficiency);
            }
            evidenceItems.add(new EvidenceItem(
                parseDomain(evidenceMap.get("intentDomain")),
                sourceFamily,
                stringValue(evidenceMap.get("source"), "unknown"),
                stringValue(evidenceMap.get("answerType"), "unknown"),
                List.of(),
                evidence.payload(),
                Set.copyOf(supportingObjectNames(evidenceMap.get("supportingObjectNames")).stream()
                    .map(String::valueOf)
                    .collect(java.util.stream.Collectors.toSet())),
                stringValue(evidenceMap.get("freshness"), "unknown"),
                number(evidenceMap.get("confidence"), evidence.confidence()),
                number(evidenceMap.get("coverage"), 0.0d),
                failureReason
            ));
            if (!failureReason.isBlank()) {
                exhaustedSources.add(new ExhaustedSource(sourceFamily, failureReason));
            }
            return;
        }
        if (!evidence.material() || Boolean.FALSE.equals(evidence.payload().get("accepted"))) {
            exhaustedSources.add(new ExhaustedSource(sourceFamily, evidence.summary()));
        }
    }

    public Set<String> attemptedSourceFamilies() {
        return sourceAttempts.stream()
            .map(SourceAttempt::sourceFamily)
            .filter(source -> source != null && !source.isBlank())
            .collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
    }

    public boolean hasSufficientEvidence() {
        return evidenceItems.stream().anyMatch(EvidenceItem::sufficient);
    }

    public List<EvidenceItem> evidenceItems() {
        return List.copyOf(evidenceItems);
    }

    public List<SourceAttempt> sourceAttempts() {
        return List.copyOf(sourceAttempts);
    }

    public List<ExhaustedSource> exhaustedSources() {
        return List.copyOf(exhaustedSources);
    }

    public Map<String, Object> toTraceMap() {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("evidenceCount", evidenceItems.size());
        data.put("sourcesAttempted", sourceAttempts.stream().map(SourceAttempt::sourceFamily).distinct().toList());
        data.put("exhaustedSources", exhaustedSources.stream().map(ExhaustedSource::sourceFamily).distinct().toList());
        data.put("evidenceSummaries", evidenceItems.stream()
            .map(item -> Map.of(
                "sourceFamily", item.sourceFamily(),
                "sourceName", item.sourceName(),
                "answerType", item.answerType(),
                "confidence", item.confidence(),
                "coverage", item.coverage(),
                "supportingObjects", item.supportingObjects()
            ))
            .toList());
        return Map.copyOf(data);
    }

    private void recordExpandedSourceAttempts(ToolEvidence evidence) {
        Object sourcePlan = evidence.payload().get("sourcePlan");
        if (sourcePlan instanceof List<?> plannedSources) {
            plannedSources.stream()
                .map(String::valueOf)
                .filter(source -> !source.isBlank())
                .forEach(source -> sourceAttempts.add(new SourceAttempt(
                    evidence.toolName(),
                    source,
                    evidence.status(),
                    evidence.summary(),
                    Instant.now(),
                    Map.of("expandedFromTool", evidence.toolName())
                )));
        }

        Object sourcesAttempted = evidence.payload().get("sourcesAttempted");
        if (sourcesAttempted instanceof Map<?, ?> attempts) {
            attempts.forEach((source, count) -> {
                String sourceName = normalizeAttemptedSourceName(String.valueOf(source));
                if (sourceName.isBlank()) {
                    return;
                }
                sourceAttempts.add(new SourceAttempt(
                    evidence.toolName(),
                    sourceName,
                    evidence.status(),
                    evidence.summary(),
                    Instant.now(),
                    Map.of("rowCount", count == null ? 0 : count)
                ));
            });
        }
    }

    private String normalizeAttemptedSourceName(String raw) {
        if (raw == null || raw.isBlank()) {
            return "";
        }
        return switch (raw) {
            case "keyColumnRows" -> "key_column_analysis";
            case "antiPatternRows" -> "column_anti_pattern";
            case "indexActionRows" -> "performance_action";
            case "indexRecommendationRows" -> "index_recommendations";
            case "compositeIndexRows" -> "composite_index_recommendation";
            case "slowQueryRows" -> "slow_query_history";
            case "performanceActionRows" -> "performance_action";
            case "workloadRows" -> "workload_profile";
            case "knobRows" -> "knob_rankings";
            case "growthRows" -> "capacity_forecasts";
            default -> raw;
        };
    }

    private String sourceFamily(ToolEvidence evidence) {
        if (evidence.payload().containsKey("sourceFamily")) {
            return String.valueOf(evidence.payload().get("sourceFamily"));
        }
        if (evidence.payload().containsKey("factType")) {
            return String.valueOf(evidence.payload().get("factType"));
        }
        return switch (evidence.toolName()) {
            case "metadata_evidence_lookup_tool" -> "vault_cached_evidence";
            case "live_metadata_query_tool" -> "live_metadata";
            case "context_resolution_tool" -> "thread_and_schema_context";
            case "universal_chat_tool" -> "sql_or_retrieval";
            default -> evidence.toolName();
        };
    }

    private PromptIntent.Domain parseDomain(Object value) {
        if (value == null) {
            return PromptIntent.Domain.UNSUPPORTED;
        }
        try {
            return PromptIntent.Domain.valueOf(String.valueOf(value));
        } catch (Exception ignored) {
            return PromptIntent.Domain.UNSUPPORTED;
        }
    }

    private double number(Object value, double fallback) {
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value != null) {
            try {
                return Double.parseDouble(String.valueOf(value));
            } catch (NumberFormatException ignored) {
                return fallback;
            }
        }
        return fallback;
    }

    private String stringValue(Object value, String fallback) {
        return value == null ? fallback : String.valueOf(value);
    }

    private List<?> supportingObjectNames(Object value) {
        return value instanceof List<?> list ? list : List.of();
    }
}
