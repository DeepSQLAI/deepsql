package com.dbaagent.service;

import org.springframework.stereotype.Service;

import java.util.Locale;
import java.util.regex.Pattern;

@Service
public class ChatScopeGuardService {

    private static final Pattern WEB_ACCESS_PATTERN = Pattern.compile(
        "(?:\\b(?:browse|search|look up|google|bing|find|check|read)\\b.{0,24}\\b(?:web|internet|online|website|site|news)\\b)"
            + "|(?:\\b(?:on the web|from the internet|online sources?|external sources?)\\b)"
            + "|(?:\\b(?:web search|internet search|browse online|look it up online)\\b)"
    );

    private static final Pattern LIVE_EXTERNAL_TOPIC_PATTERN = Pattern.compile(
        "\\b(?:weather|temperature|rain|sports?|score|match|game|news|current events|stock(?:\\s+price)?|share price"
            + "|crypto(?:\\s+price)?|bitcoin|ethereum|exchange rate|election|president|prime minister|traffic|flight status)\\b"
    );

    private static final Pattern CURRENT_EXTERNAL_SIGNAL_PATTERN = Pattern.compile(
        "\\b(?:latest|current|today|right now|now|news|headline|release notes?)\\b"
    );

    private static final Pattern COMPANY_OR_DATA_SCOPE_PATTERN = Pattern.compile(
        "\\b(?:schema|table|tables|column|columns|database|databases|join path|relationship|relationships|foreign key"
            + "|index|indexes|query plan|slow query|latency|performance|workload|tuning|classification|growth"
            + "|semantic model|schema docs|company knowledge"
            + "|customer|customers|account|accounts|booking|bookings|order|orders|payment|payments|invoice|invoices"
            + "|revenue|sales|gmv|arr|mrr|churn|ltv|aov|funnel|conversion|occupancy|adr|revpar|property|properties"
            + "|customer|customers|guest|guests)\\b"
    );

    private static final Pattern QUERY_REFERENCE_PATTERN = Pattern.compile(
        "\\b(?:this|that|my|our|the)\\s+query\\b"
    );

    private static final Pattern SQL_SNIPPET_PATTERN = Pattern.compile(
        "\\b(?:select|with|from|join|where|group\\s+by|order\\s+by|having|explain|limit)\\b"
    );

    private static final Pattern FOLLOW_UP_CLARIFICATION_PATTERN = Pattern.compile(
        "(?i)^(?:yes|no|use|using|with|without|include|exclude|instead|rather|switch|change|filter|group|sort|order|based on)\\b"
            + "|\\b(?:you asked|clarif(?:y|ied|ication))\\b"
            + "|\\b[a-z_][a-z0-9_]*\\.[a-z_][a-z0-9_]*\\b"
    );

    public ScopeDecision evaluate(
        String originalQuestion,
        String effectiveQuestion,
        ChatQuestionRoutingService.QuestionRoute route
    ) {
        String original = normalize(originalQuestion);
        String effective = normalize(effectiveQuestion);
        String combined = effective.isBlank() ? original : effective;

        if (combined.isBlank()) {
            return ScopeDecision.allow();
        }

        if (WEB_ACCESS_PATTERN.matcher(combined).find()
            || (LIVE_EXTERNAL_TOPIC_PATTERN.matcher(combined).find()
                && CURRENT_EXTERNAL_SIGNAL_PATTERN.matcher(combined).find()
                && !hasInScopeSignal(combined, route))) {
            return ScopeDecision.block(
                "external_context_blocked",
                "I can't browse the web or use outside sources. I can only answer from your company's connected data, schema, support context, and company knowledge already available here."
            );
        }

        return ScopeDecision.allow();
    }

    private boolean hasInScopeSignal(String normalizedQuestion, ChatQuestionRoutingService.QuestionRoute route) {
        if (route != null && route.type() != ChatQuestionRoutingService.RouteType.GENERAL) {
            return true;
        }
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return false;
        }
        return COMPANY_OR_DATA_SCOPE_PATTERN.matcher(normalizedQuestion).find()
            || QUERY_REFERENCE_PATTERN.matcher(normalizedQuestion).find()
            || SQL_SNIPPET_PATTERN.matcher(normalizedQuestion).find()
            || FOLLOW_UP_CLARIFICATION_PATTERN.matcher(normalizedQuestion).find();
    }

    private String normalize(String question) {
        if (question == null) {
            return "";
        }
        return question.toLowerCase(Locale.ROOT)
            .replaceAll("\\s+", " ")
            .trim();
    }

    public record ScopeDecision(
        boolean allowed,
        String reasonCode,
        String responseMessage
    ) {
        public static ScopeDecision allow() {
            return new ScopeDecision(true, null, null);
        }

        public static ScopeDecision block(String reasonCode, String responseMessage) {
            return new ScopeDecision(false, reasonCode, responseMessage);
        }
    }
}
