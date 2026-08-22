package com.dbaagent.service.brain.core;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Drafts a shared-brain note from an Agent turn and folds it into existing
 * context (schema documentation + business rules) so one table/column keeps a
 * single intent instead of a pile of overlapping recommendations.
 */
@Service
public class BrainNoteIntentService {

    private static final Pattern BACKTICK_IDENT = Pattern.compile("`([^`]+)`");
    private static final Set<String> STOP_TABLES = Set.of(
        "information_schema", "pg_catalog", "mysql", "sys", "performance_schema"
    );
    /**
     * User follow-ups that teach or correct. Phrase contains() — not a fat
     * regex — so a long Agent transcript cannot ReDoS the propose path.
     */
    private static final List<String> FEEDBACK_PHRASES = List.of(
        "that's wrong", "that is wrong", "that's not", "that is not",
        "incorrect", "actually ", "instead", "should be", "should use",
        "you should", "don't use", "do not use", "never use",
        "always use", "always filter", "always join", "always exclude",
        "we use", "we always", "we never", "not that", "not the ",
        "pin this", "pin that", "remember this", "remember:",
        "save this", "save that", "use this", "use that",
        "too high", "too low", "off by", "the right "
    );

    public record ContextItem(
        String id,
        String tableName,
        String columnName,
        String text,
        String source
    ) {}

    public record Proposal(
        String scopeType,
        String tableName,
        String columnName,
        String bubbleLabel,
        String excerpt,
        String proposedNoteText,
        String action,
        String existingNoteId,
        String existingNoteText,
        String overlapReason
    ) {}

    public Optional<Proposal> proposeFromTurn(String question, String answer, List<ContextItem> existing) {
        return proposeFromTurn(question, answer, existing, null);
    }

    /**
     * Only draft a note when the user just corrected or taught after a prior
     * Agent answer. A clean first-turn definition is not a recommendation.
     */
    public Optional<Proposal> proposeFromTurn(
        String question,
        String answer,
        List<ContextItem> existing,
        String priorAnswer
    ) {
        if (!isCorrectionTurn(question, priorAnswer)) {
            return Optional.empty();
        }
        String cleaned = stripMarkdownNoise(nvl(answer));
        String combined = nvl(question) + "\n" + cleaned;
        if (combined.trim().length() < 24) {
            return Optional.empty();
        }

        Optional<String[]> target = resolveTarget(combined);
        if (target.isEmpty()) {
            return Optional.empty();
        }
        String tableName = target.get()[0];
        String columnName = target.get()[1];
        String excerpt = excerpt(nvl(question) + (cleaned.isBlank() ? "" : " " + cleaned));
        String proposed = columnName != null
            ? "For " + tableName + "." + columnName + ": " + excerpt
            : "For " + tableName + ": " + excerpt;
        String label = columnName != null
            ? "Save correction: " + columnName
            : "Save correction: " + tableName;

        Proposal draft = new Proposal(
            columnName != null ? "COLUMN" : "TABLE",
            tableName,
            columnName,
            label,
            excerpt,
            proposed,
            "NEW",
            null,
            null,
            null
        );
        return Optional.of(resolveOverlap(draft, existing == null ? List.of() : existing));
    }

    public boolean isCorrectionTurn(String question, String priorAnswer) {
        if (priorAnswer == null || priorAnswer.isBlank()) {
            return false;
        }
        return looksLikeUserFeedback(question);
    }

    public boolean looksLikeUserFeedback(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String q = question.toLowerCase(Locale.ROOT).trim();
        if (q.startsWith("no,") || q.startsWith("no ") || q.startsWith("no-")
            || q.startsWith("no—") || q.startsWith("nope")) {
            return true;
        }
        for (String phrase : FEEDBACK_PHRASES) {
            if (q.contains(phrase)) {
                return true;
            }
        }
        return false;
    }

    public Proposal resolveOverlap(Proposal draft, List<ContextItem> existing) {
        if (draft == null) {
            return null;
        }
        ContextItem match = findOverlap(draft.tableName(), draft.columnName(), existing);
        if (match == null) {
            return draft;
        }
        if (sameIntent(match.text(), draft.proposedNoteText())) {
            return new Proposal(
                draft.scopeType(),
                draft.tableName(),
                draft.columnName(),
                draft.bubbleLabel(),
                draft.excerpt(),
                match.text(),
                "SKIP",
                match.id(),
                match.text(),
                "Already in " + match.source() + " with the same intent"
            );
        }
        String merged = mergeTexts(match.text(), draft.proposedNoteText());
        // mergeTexts already returns the existing text unchanged when the
        // incoming sentence is a subset. Do not call sameIntent(existing,
        // merged) here — merged always contains existing, so that check
        // would skip every real merge.
        if (normalizeText(merged).equals(normalizeText(match.text()))) {
            return new Proposal(
                draft.scopeType(),
                draft.tableName(),
                draft.columnName(),
                draft.bubbleLabel(),
                draft.excerpt(),
                match.text(),
                "SKIP",
                match.id(),
                match.text(),
                "Existing context already covers this intent"
            );
        }
        return new Proposal(
            draft.scopeType(),
            draft.tableName(),
            draft.columnName(),
            "Update definition: " + (draft.columnName() != null ? draft.columnName() : draft.tableName()),
            excerpt(merged),
            merged,
            "MERGE",
            match.id(),
            match.text(),
            "Overlaps existing " + match.source() + " — keep as one intent"
        );
    }

    public String mergeTexts(String existing, String incoming) {
        String left = existing == null ? "" : existing.trim();
        String right = incoming == null ? "" : incoming.trim();
        if (left.isEmpty()) {
            return right;
        }
        if (right.isEmpty() || sameIntent(left, right) || containsNormalized(left, right)) {
            return left;
        }
        if (containsNormalized(right, left)) {
            return right;
        }
        return left + (left.endsWith(".") ? " " : ". ") + right;
    }

    public boolean sameIntent(String left, String right) {
        Set<String> a = tokens(left);
        Set<String> b = tokens(right);
        if (a.isEmpty() || b.isEmpty()) {
            return false;
        }
        if (containsNormalized(left, right) || containsNormalized(right, left)) {
            return true;
        }
        int inter = 0;
        for (String token : a) {
            if (b.contains(token)) {
                inter++;
            }
        }
        int union = a.size() + b.size() - inter;
        return union > 0 && (inter * 1.0 / union) >= 0.72;
    }

    private ContextItem findOverlap(String tableName, String columnName, List<ContextItem> existing) {
        String table = normalizeName(tableName);
        String column = normalizeName(columnName);
        ContextItem tableMatch = null;
        for (ContextItem item : existing) {
            if (item == null || !tableMatches(table, normalizeName(item.tableName()))) {
                continue;
            }
            String itemCol = normalizeName(item.columnName());
            if (!column.isEmpty() && column.equals(itemCol)) {
                return item;
            }
            if (column.isEmpty() && itemCol.isEmpty()) {
                return item;
            }
            if (tableMatch == null && itemCol.isEmpty()) {
                tableMatch = item;
            }
        }
        return tableMatch;
    }

    /**
     * Collect every backtick ident first. Returning on the first dotted table
     * used to drop a metric already seen ({@code `meditator_count_current`} then
     * {@code `marts.dim_person`}). Prefer {@code schema.table.column}, then a
     * dotted table plus a distinct {@code _}-containing ident, then a dotted
     * table found in prose.
     */
    private Optional<String[]> resolveTarget(String text) {
        List<String> idents = new ArrayList<>();
        Matcher ticks = BACKTICK_IDENT.matcher(text);
        while (ticks.find()) {
            String ident = ticks.group(1).trim();
            if (!ident.isEmpty()) {
                idents.add(ident);
            }
        }
        for (String ident : idents) {
            String[] parts = ident.split("\\.");
            if (parts.length >= 3 && !STOP_TABLES.contains(parts[0].toLowerCase(Locale.ROOT))) {
                return Optional.of(new String[] { parts[0] + "." + parts[1], parts[2] });
            }
        }
        String table = null;
        String column = null;
        for (String ident : idents) {
            if (ident.contains(".")) {
                String[] parts = ident.split("\\.");
                if (parts.length >= 2 && !STOP_TABLES.contains(parts[0].toLowerCase(Locale.ROOT))) {
                    table = parts[0] + "." + parts[1];
                }
            } else if (column == null && ident.contains("_") && ident.length() > 3) {
                column = ident;
            }
        }
        if (table != null) {
            return Optional.of(new String[] { table, column });
        }
        String[] prose = findFirstQualifiedTable(text);
        if (prose != null) {
            return Optional.of(new String[] { prose[0] + "." + prose[1], column });
        }
        return Optional.empty();
    }

    /**
     * Linear scan for {@code schema.table} (not {@code a.b.c}). Avoids the
     * backtracking {@code [\w]*\.[\w]*} pattern CodeQL flags as ReDoS on
     * attacker-controlled Agent answers.
     */
    static String[] findFirstQualifiedTable(String text) {
        if (text == null || text.isEmpty()) {
            return null;
        }
        int n = text.length();
        int i = 0;
        while (i < n) {
            char c = text.charAt(i);
            if (!isIdentStart(c)) {
                i++;
                continue;
            }
            if (i > 0) {
                char prev = text.charAt(i - 1);
                if (isIdentPart(prev) || prev == '.') {
                    i++;
                    continue;
                }
            }
            int schemaStart = i;
            i++;
            while (i < n && isIdentPart(text.charAt(i))) {
                i++;
            }
            if (i >= n || text.charAt(i) != '.') {
                continue;
            }
            int schemaEnd = i;
            i++;
            if (i >= n || !isIdentStart(text.charAt(i))) {
                continue;
            }
            int nameStart = i;
            i++;
            while (i < n && isIdentPart(text.charAt(i))) {
                i++;
            }
            if (i < n) {
                char next = text.charAt(i);
                if (isIdentPart(next) || next == '.') {
                    continue;
                }
            }
            String schema = text.substring(schemaStart, schemaEnd);
            if (STOP_TABLES.contains(schema.toLowerCase(Locale.ROOT))) {
                continue;
            }
            return new String[] { schema, text.substring(nameStart, i) };
        }
        return null;
    }

    private static boolean isIdentStart(char c) {
        return (c >= 'A' && c <= 'Z') || (c >= 'a' && c <= 'z') || c == '_';
    }

    private static boolean isIdentPart(char c) {
        return isIdentStart(c) || (c >= '0' && c <= '9');
    }

    /**
     * Exact table match, or one side is a bare name of a schema-qualified
     * table. Two different schemas with the same local name stay distinct.
     */
    private boolean tableMatches(String left, String right) {
        if (left.isEmpty() || right.isEmpty()) {
            return false;
        }
        if (left.equals(right)) {
            return true;
        }
        boolean leftQualified = left.contains(".");
        boolean rightQualified = right.contains(".");
        if (leftQualified && !rightQualified) {
            return left.endsWith("." + right);
        }
        if (rightQualified && !leftQualified) {
            return right.endsWith("." + left);
        }
        return false;
    }

    private static String nvl(String value) {
        return value == null ? "" : value;
    }

    private String excerpt(String text) {
        String compact = text.replaceAll("\\s+", " ").trim();
        if (compact.length() <= 280) {
            return compact;
        }
        int cut = compact.lastIndexOf('.', 280);
        if (cut < 80) {
            cut = 280;
        }
        return compact.substring(0, cut).trim();
    }

    private String stripMarkdownNoise(String answer) {
        String withoutCode = answer.replaceAll("(?s)```.*?```", " ");
        withoutCode = withoutCode.replaceAll("(?m)^\\|.*\\|$", " ");
        withoutCode = withoutCode.replaceAll("\\*\\*|__", "");
        return withoutCode.replaceAll("\\s+", " ").trim();
    }

    private boolean containsNormalized(String haystack, String needle) {
        String a = normalizeText(haystack);
        String b = normalizeText(needle);
        return !b.isEmpty() && a.contains(b);
    }

    private Set<String> tokens(String text) {
        Set<String> out = new LinkedHashSet<>();
        for (String part : normalizeText(text).split(" ")) {
            if (part.length() >= 3) {
                out.add(part);
            }
        }
        return out;
    }

    private String normalizeText(String text) {
        if (text == null) {
            return "";
        }
        return text.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._\\s]", " ").replaceAll("\\s+", " ").trim();
    }

    private String normalizeName(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    public List<ContextItem> contextFromNotesAndRules(
        List<ContextItem> notes,
        List<ContextItem> rules
    ) {
        List<ContextItem> all = new ArrayList<>();
        if (notes != null) {
            all.addAll(notes);
        }
        if (rules != null) {
            all.addAll(rules);
        }
        return all;
    }
}
