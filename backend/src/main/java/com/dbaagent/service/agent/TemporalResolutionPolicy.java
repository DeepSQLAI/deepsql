package com.dbaagent.service.agent;

import com.dbaagent.util.PromptIntentSignals;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

final class TemporalResolutionPolicy {

    Decision resolve(
        String question,
        String entityTableName,
        List<Candidate> rawCandidates,
        String priorChosenTemporalColumn
    ) {
        if (rawCandidates == null || rawCandidates.isEmpty()) {
            return Decision.none();
        }

        String lowerQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);
        String normalizedPrior = normalizeQualified(priorChosenTemporalColumn);

        Map<String, Candidate> deduped = new LinkedHashMap<>();
        for (Candidate candidate : rawCandidates) {
            if (candidate == null || candidate.qualifiedName().isBlank()) {
                continue;
            }
            int score = candidate.score()
                + promptIntentBonus(lowerQuestion, candidate.semanticLabel())
                + priorChoiceBonus(normalizedPrior, candidate.qualifiedName())
                + (candidate.preferred() ? 18 : 0);
            Candidate rescored = new Candidate(
                candidate.tableName(),
                candidate.columnName(),
                score,
                candidate.preferred(),
                candidate.semanticLabel()
            );
            Candidate existing = deduped.get(normalizeQualified(candidate.qualifiedName()));
            if (existing == null || rescored.score() > existing.score()) {
                deduped.put(normalizeQualified(candidate.qualifiedName()), rescored);
            }
        }

        List<Candidate> ranked = deduped.values().stream()
            .sorted(Comparator.comparingInt(Candidate::score)
                .reversed()
                .thenComparing(Candidate::qualifiedName, String.CASE_INSENSITIVE_ORDER))
            .toList();
        if (ranked.isEmpty()) {
            return Decision.none();
        }

        Candidate best = ranked.getFirst();
        Candidate second = ranked.size() > 1 ? ranked.get(1) : null;
        int gap = second == null ? best.score() : best.score() - second.score();
        boolean clearWinner = best.score() >= 92 || (best.score() >= 76 && gap >= 10) || (best.preferred() && gap >= 4);
        boolean ambiguous = second != null && gap < 10;
        boolean canChoose = best.score() >= 66;
        if (!canChoose) {
            return Decision.unresolved(
                buildClarification(entityTableName, ranked),
                ranked.stream().limit(4).map(Candidate::qualifiedName).toList(),
                "No dominant business timestamp could be inferred from schema evidence"
            );
        }

        List<String> alternatives = ranked.stream()
            .skip(1)
            .limit(3)
            .map(Candidate::qualifiedName)
            .toList();
        String directive = buildDirective(entityTableName, best, alternatives, ambiguous);
        String rationale = clearWinner
            ? "Temporal policy selected the dominant business timestamp from ranked schema candidates"
            : "Temporal policy selected the most likely business timestamp and kept close alternatives for fallback";
        return new Decision(
            best.qualifiedName(),
            directive,
            ranked.stream().limit(4).map(Candidate::qualifiedName).toList(),
            alternatives,
            ambiguous,
            buildClarification(entityTableName, ranked),
            rationale
        );
    }

    private int promptIntentBonus(String lowerQuestion, String label) {
        if (label == null || label.isBlank()) {
            return 0;
        }
        String normalized = label.toLowerCase(Locale.ROOT);
        if (PromptIntentSignals.isActivityUsageQuestion(lowerQuestion) && normalized.contains("event/activity")) {
            return 26;
        }
        if (PromptIntentSignals.isBehavioralDeclineQuestion(lowerQuestion) && normalized.contains("event/activity")) {
            return 16;
        }
        if (PromptIntentSignals.isCommercialQuestion(lowerQuestion) && normalized.contains("transaction")) {
            return 20;
        }
        if (mentionsOnboarding(lowerQuestion) && normalized.contains("lifecycle/onboarding")) {
            return 30;
        }
        if (lowerQuestion.contains("refund") && normalized.contains("refund")) {
            return 18;
        }
        if (!mentionsUpdateSemantics(lowerQuestion) && normalized.contains("update")) {
            return -18;
        }
        return 0;
    }

    private int priorChoiceBonus(String normalizedPrior, String qualifiedName) {
        if (normalizedPrior.isBlank()) {
            return 0;
        }
        return normalizedPrior.equals(normalizeQualified(qualifiedName)) ? 40 : 0;
    }

    private String buildDirective(String entityTableName, Candidate chosen, List<String> alternatives, boolean ambiguous) {
        StringBuilder sb = new StringBuilder();
        if (entityTableName != null && !entityTableName.isBlank()) {
            sb.append("Treat `").append(entityTableName).append("` as the primary business entity and use `")
                .append(chosen.qualifiedName())
                .append("` as the business timestamp for this request.");
        } else {
            sb.append("Use `").append(chosen.qualifiedName()).append("` as the business timestamp for this request.");
        }
        if (chosen.semanticLabel() != null && !chosen.semanticLabel().isBlank()) {
            sb.append(" It best matches ").append(chosen.semanticLabel()).append(" semantics.");
        }
        if (ambiguous && alternatives != null && !alternatives.isEmpty()) {
            sb.append(" Keep these close alternatives in mind only if validation fails: ")
                .append(String.join(", ", alternatives))
                .append(".");
        }
        return sb.toString();
    }

    private String buildClarification(String entityTableName, List<Candidate> ranked) {
        List<String> topCandidates = ranked.stream()
            .limit(4)
            .map(Candidate::qualifiedName)
            .toList();
        String prefix = entityTableName != null && !entityTableName.isBlank()
            ? "for `" + entityTableName + "`"
            : "for this request";
        return "I need one clarification before I can trust the time window " + prefix
            + ": which business timestamp should define it? Likely candidates are "
            + String.join(", ", topCandidates) + ".";
    }

    private boolean mentionsOnboarding(String lowerQuestion) {
        return lowerQuestion.contains("onboard")
            || lowerQuestion.contains("activation")
            || lowerQuestion.contains("subscription")
            || lowerQuestion.contains("contract start");
    }

    private boolean mentionsUpdateSemantics(String lowerQuestion) {
        return lowerQuestion.contains("updated")
            || lowerQuestion.contains("modified")
            || lowerQuestion.contains("recently changed")
            || lowerQuestion.contains("last updated");
    }

    private String normalizeQualified(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    record Candidate(
        String tableName,
        String columnName,
        int score,
        boolean preferred,
        String semanticLabel
    ) {
        String qualifiedName() {
            if (tableName == null || columnName == null) {
                return "";
            }
            return tableName + "." + columnName;
        }
    }

    record Decision(
        String chosenQualifiedColumn,
        String directive,
        List<String> rankedCandidates,
        List<String> alternatives,
        boolean ambiguous,
        String clarificationMessage,
        String rationale
    ) {
        static Decision none() {
            return new Decision(null, null, List.of(), List.of(), false, null, null);
        }

        static Decision unresolved(String clarificationMessage, List<String> rankedCandidates, String rationale) {
            return new Decision(null, null, rankedCandidates, rankedCandidates, true, clarificationMessage, rationale);
        }

        boolean hasDirective() {
            return directive != null && !directive.isBlank();
        }

        boolean canAttemptWithoutClarification() {
            return chosenQualifiedColumn != null && !chosenQualifiedColumn.isBlank();
        }

        boolean shouldClarifyAfterFailure() {
            return clarificationMessage != null && !clarificationMessage.isBlank();
        }
    }
}
