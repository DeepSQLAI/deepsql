package com.dbaagent.util;

import java.util.List;
import java.util.Locale;

public final class PromptIntentSignals {

    private static final List<String> ACTIVITY_TERMS = List.of(
        "usage", "activity", "activities", "event", "events", "log", "logs",
        "audit", "audits", "adoption", "engagement", "session", "sessions"
    );

    private static final List<String> DECLINE_TERMS = List.of(
        "churn", "drop", "dropped", "decline", "declined", "inactive",
        "went dark", "going dark", "steeply", "steep drop"
    );

    private static final List<String> COMMERCIAL_TERMS = List.of(
        "revenue", "booking", "bookings", "payment", "payments", "sales",
        "gmv", "commission", "amount", "orders", "order", "invoice", "billing"
    );

    private PromptIntentSignals() {
    }

    public static boolean isActivityUsageQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized, ACTIVITY_TERMS)
            || (containsAny(normalized, DECLINE_TERMS) && normalized.contains(" usage "));
    }

    public static boolean isBehavioralDeclineQuestion(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return containsAny(normalized, DECLINE_TERMS)
            && (containsAny(normalized, ACTIVITY_TERMS)
            || normalized.contains(" active ")
            || normalized.contains(" inactive "));
    }

    public static boolean isCommercialQuestion(String question) {
        return containsAny(normalize(question), COMMERCIAL_TERMS);
    }

    public static boolean isTrendQuestion(String question) {
        String normalized = normalize(question);
        return normalized.contains(" trend ")
            || normalized.contains(" over time ")
            || normalized.contains(" time series ");
    }

    public static boolean isRankingQuestion(String question) {
        String normalized = normalize(question);
        return normalized.contains(" top ")
            || normalized.contains(" bottom ")
            || normalized.contains(" highest ")
            || normalized.contains(" lowest ")
            || normalized.contains(" best ")
            || normalized.contains(" worst ")
            || normalized.contains(" leading ");
    }

    public static boolean isComparisonQuestion(String question) {
        String normalized = normalize(question);
        return normalized.contains(" compare ")
            || normalized.contains(" comparison ")
            || normalized.contains(" versus ")
            || normalized.contains(" vs ")
            || normalized.contains(" against ")
            || normalized.contains(" performance ");
    }

    public static boolean hasExplicitMetric(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return isCommercialQuestion(normalized)
            || normalized.contains(" count ")
            || normalized.contains(" volume ")
            || normalized.contains(" rate ")
            || normalized.contains(" trend ")
            || normalized.contains(" transaction ")
            || normalized.contains(" transactions ")
            || normalized.contains(" refund ")
            || normalized.contains(" refunds ")
            || normalized.contains(" cancellation ")
            || normalized.contains(" cancellations ")
            || normalized.contains(" success rate ")
            || normalized.contains(" conversion ")
            || normalized.contains(" bookings ");
    }

    public static boolean hasExplicitTimeWindow(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return PatternUtil.containsPattern(normalized, "\\b(last|past|previous|current|this|today|yesterday|tomorrow)\\b")
            || PatternUtil.containsPattern(normalized, "\\b\\d+\\s+(day|days|week|weeks|month|months|year|years|hour|hours)\\b")
            || PatternUtil.containsPattern(normalized, "\\b(january|february|march|april|may|june|july|august|september|october|november|december)\\b")
            || normalized.contains(" between ")
            || normalized.contains(" from ")
            || normalized.contains(" since ")
            || normalized.contains(" date range ")
            || normalized.contains(" period ");
    }

    public static boolean hasExplicitTimeGrain(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains(" daily ")
            || normalized.contains(" weekly ")
            || normalized.contains(" monthly ")
            || normalized.contains(" yearly ")
            || normalized.contains(" per day ")
            || normalized.contains(" per week ")
            || normalized.contains(" per month ")
            || normalized.contains(" per year ")
            || normalized.contains(" by day ")
            || normalized.contains(" by week ")
            || normalized.contains(" by month ")
            || normalized.contains(" by year ");
    }

    public static boolean requestsDescriptiveAttributes(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains(" name ")
            || normalized.contains(" names ")
            || normalized.contains(" email ")
            || normalized.contains(" emails ")
            || normalized.contains(" country ")
            || normalized.contains(" city ")
            || normalized.contains(" state ")
            || normalized.contains(" address ")
            || normalized.contains(" details ")
            || normalized.contains(" detail ");
    }

    public static boolean requestsContactAttributes(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains(" email ")
            || normalized.contains(" emails ")
            || normalized.contains(" contact ")
            || normalized.contains(" contacts ")
            || normalized.contains(" phone ")
            || normalized.contains(" phones ");
    }

    public static boolean requestsPersonEntity(String question) {
        String normalized = normalize(question);
        if (normalized.isBlank()) {
            return false;
        }
        return normalized.contains(" customer ")
            || normalized.contains(" customers ")
            || normalized.contains(" guest ")
            || normalized.contains(" guests ")
            || normalized.contains(" user ")
            || normalized.contains(" users ")
            || normalized.contains(" traveller ")
            || normalized.contains(" travellers ")
            || normalized.contains(" traveler ")
            || normalized.contains(" travelers ")
            || normalized.contains(" person ")
            || normalized.contains(" people ");
    }

    public static String normalize(String question) {
        if (question == null || question.isBlank()) {
            return "";
        }
        return " " + question.toLowerCase(Locale.ROOT)
            .replaceAll("[^a-z0-9_ ]", " ")
            .replaceAll("\\s+", " ")
            .trim() + " ";
    }

    private static boolean containsAny(String normalizedQuestion, List<String> terms) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return false;
        }
        for (String term : terms) {
            String normalizedTerm = normalize(term).trim();
            if (!normalizedTerm.isBlank() && normalizedQuestion.contains(" " + normalizedTerm + " ")) {
                return true;
            }
        }
        return false;
    }
}
