package com.dbaagent.service;

import com.dbaagent.util.PatternUtil;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;

import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Exact-schema question helpers.
 *
 * <p>These utilities are intentionally stricter than the generic table mention
 * matcher used elsewhere in the product. They should be used when the user is
 * asking about a specific schema object and accuracy matters more than recall.
 */
public final class SchemaQuestionUtil {

    private SchemaQuestionUtil() {
    }

    public static boolean looksLikeExactTableColumnCountQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        return PatternUtil.containsPattern(lower, "\\b(how many|count|number of)\\b.*\\bcolumns?\\b");
    }

    public static boolean looksLikeExactTableColumnQuestion(String question) {
        return looksLikeExactTableColumnCountQuestion(question)
            || looksLikeExactTableColumnListQuestion(question);
    }

    public static boolean looksLikeExactTableRowCountQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!(lower.contains("row") || lower.contains("record"))) {
            return false;
        }
        return PatternUtil.containsPattern(lower, "\\b(how many|count|number of)\\b.*\\b(rows?|records?)\\b")
            || PatternUtil.containsPattern(lower, "\\b(rows?|records?)\\b.*\\b(in|for|of|on)\\b")
            || PatternUtil.containsPattern(lower, "\\brow count\\b");
    }

    public static boolean looksLikeExactTableIndexQuestion(String question) {
        return looksLikeExactTableIndexCountQuestion(question)
            || looksLikeExactTableIndexListQuestion(question);
    }

    public static boolean looksLikeExactTableIndexCountQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!(lower.contains("index") || lower.contains("indices"))) {
            return false;
        }
        return PatternUtil.containsPattern(lower, "\\b(how many|count|number of)\\b.*\\b(indexes?|indices)\\b");
    }

    public static boolean looksLikeExactTableIndexListQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (!(lower.contains("index") || lower.contains("indices"))) {
            return false;
        }
        return PatternUtil.containsPattern(lower, "\\b(what|which|show|list|display|describe)\\b.*\\b(indexes?|indices)\\b")
            || PatternUtil.containsPattern(lower, "\\b(indexes?|indices)\\b.*\\b(in|for|of|on)\\b");
    }

    public static boolean looksLikeExactTableColumnListQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (looksLikePairScopedJoinColumnQuestion(lower)) {
            return false;
        }
        if (looksLikeAnalyticColumnQuestion(lower)) {
            return false;
        }
        if (!lower.contains("column")) {
            return false;
        }
        return PatternUtil.containsPattern(lower, "\\b(what|which|show|list|display|describe|schema|structure)\\b.*\\bcolumns?\\b")
            || PatternUtil.containsPattern(lower, "\\bcolumns?\\b.*\\b(in|for|of|on)\\b")
            || PatternUtil.containsPattern(lower, "\\bdescribe\\b.*\\btable\\b");
    }

    public static boolean looksLikeExactTableKeyColumnQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        if (looksLikePairScopedJoinColumnQuestion(lower)) {
            return false;
        }
        if (!(lower.contains("key") || lower.contains("primary") || lower.contains("foreign") || lower.contains("join column"))) {
            return false;
        }
        return PatternUtil.containsPattern(lower, "\\b(how many|count|number of)\\b.*\\b(inferred keys?|key columns?|primary keys?|foreign keys?|join columns?)\\b")
            || PatternUtil.containsPattern(lower, "\\b(what|which|show|list|display|describe)\\b.*\\b(inferred keys?|key columns?|primary keys?|foreign keys?|join columns?)\\b")
            || PatternUtil.containsPattern(lower, "\\b(inferred keys?|key columns?|primary keys?|foreign keys?|join columns?)\\b.*\\b(in|for|of|on)\\b");
    }

    public static boolean looksLikePairScopedJoinColumnQuestion(String question) {
        if (question == null || question.isBlank()) {
            return false;
        }
        String lower = question.toLowerCase(Locale.ROOT);
        return (lower.contains("join column")
            || lower.contains("join columns")
            || lower.contains("columns are joined")
            || lower.contains("joined commonly")
            || lower.contains("commonly joined"))
            && (lower.contains(" between ") || lower.contains(" and "));
    }

    private static boolean looksLikeAnalyticColumnQuestion(String lower) {
        if (lower == null || lower.isBlank() || !lower.contains("column")) {
            return false;
        }
        String analyticSignals =
            "(impact|impactful|important|critical|top|most|least|frequent|frequently|commonly|query|queries|performance|slow|usage|used|join|filter|group by|order by)";
        return lower.matches(".*\\b" + analyticSignals + "\\b.*\\bcolumns?\\b.*")
            || lower.matches(".*\\bcolumns?\\b.*\\b" + analyticSignals + "\\b.*");
    }

    public static TableMetadata resolveExactSchemaTable(SchemaMetadata schema, String question) {
        List<TableMetadata> matches = resolveExactSchemaTablesDetailed(schema, question);
        return matches.isEmpty() ? null : matches.getFirst();
    }

    public static List<String> resolveExactSchemaTables(SchemaMetadata schema, String question) {
        return resolveExactSchemaTablesDetailed(schema, question).stream()
            .map(TableMetadata::getName)
            .filter(Objects::nonNull)
            .toList();
    }

    private static List<TableMetadata> resolveExactSchemaTablesDetailed(SchemaMetadata schema, String question) {
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty() || question == null || question.isBlank()) {
            return List.of();
        }

        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(question);
        List<TableMetadata> matchedTables = schema.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null)
            .filter(table -> mentionsExactTable(normalizedQuestion, table.getName()))
            .sorted(Comparator
                .comparingInt((TableMetadata table) -> exactCaseMentionStrength(question, table.getName()))
                .reversed()
                .thenComparing(Comparator.comparingLong((TableMetadata table) -> table.getRowCount() != null ? table.getRowCount() : -1L).reversed())
                .thenComparing(Comparator.comparingInt((TableMetadata table) -> table.getColumns() != null ? table.getColumns().size() : 0).reversed())
                .thenComparing(table -> table.getName(), String.CASE_INSENSITIVE_ORDER))
            .toList();

        if (matchedTables.isEmpty()) {
            return List.of();
        }

        Set<String> shadowed = new LinkedHashSet<>();
        for (TableMetadata outer : matchedTables) {
            for (TableMetadata inner : matchedTables) {
                if (outer == inner || outer.getName() == null || inner.getName() == null) {
                    continue;
                }
                if (containsCanonicalVariant(outer.getName(), inner.getName())) {
                    shadowed.add(inner.getName().toLowerCase(Locale.ROOT));
                }
            }
        }

        List<TableMetadata> filtered = matchedTables.stream()
            .filter(table -> !shadowed.contains(table.getName().toLowerCase(Locale.ROOT)))
            .toList();
        return filtered.isEmpty() ? matchedTables.subList(0, 1) : filtered;
    }

    public static boolean mentionsExactTable(String normalizedQuestion, String tableName) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank() || tableName == null || tableName.isBlank()) {
            return false;
        }
        return exactTableNameVariants(tableName).stream()
            .anyMatch(variant -> containsWholeTerm(normalizedQuestion, variant));
    }

    private static Set<String> exactTableNameVariants(String tableName) {
        String canonical = tableName.toLowerCase(Locale.ROOT);
        String spaced = canonical.replace('_', ' ');
        Set<String> variants = new LinkedHashSet<>();
        variants.add(canonical);
        variants.add(spaced);
        addPluralVariants(variants, canonical);
        addPluralVariants(variants, spaced);
        return variants;
    }

    private static void addPluralVariants(Set<String> variants, String value) {
        if (value == null || value.isBlank()) {
            return;
        }
        if (value.endsWith("ies") && value.length() > 3) {
            variants.add(value.substring(0, value.length() - 3) + "y");
            return;
        }
        if (value.endsWith("s") && value.length() > 1) {
            variants.add(value.substring(0, value.length() - 1));
        } else {
            variants.add(value + "s");
        }
    }

    private static boolean containsCanonicalVariant(String largerTable, String smallerTable) {
        if (largerTable == null || smallerTable == null) {
            return false;
        }
        Set<String> largerVariants = canonicalShadowingVariants(largerTable);
        Set<String> smallerVariants = canonicalShadowingVariants(smallerTable);
        return largerVariants.stream().anyMatch(larger ->
            smallerVariants.stream()
                .anyMatch(smaller -> !larger.equals(smaller) && containsWholeTerm(larger, smaller)));
    }

    private static Set<String> canonicalShadowingVariants(String tableName) {
        String canonical = tableName.toLowerCase(Locale.ROOT);
        String spaced = canonical.replace('_', ' ');
        Set<String> variants = new LinkedHashSet<>();
        variants.add(canonical);
        variants.add(spaced);
        return variants;
    }

    private static int exactCaseMentionStrength(String question, String tableName) {
        if (question == null || question.isBlank() || tableName == null || tableName.isBlank()) {
            return 0;
        }
        Pattern exactCasePattern = Pattern.compile("(^|[^A-Za-z0-9_])" + Pattern.quote(tableName) + "([^A-Za-z0-9_]|$)");
        if (exactCasePattern.matcher(question).find()) {
            return 3;
        }
        Pattern caseInsensitivePattern = Pattern.compile(
            "(^|[^A-Za-z0-9_])" + Pattern.quote(tableName) + "([^A-Za-z0-9_]|$)",
            Pattern.CASE_INSENSITIVE
        );
        return caseInsensitivePattern.matcher(question).find() ? 2 : 0;
    }

    private static boolean containsWholeTerm(String text, String candidate) {
        if (candidate == null || candidate.isBlank()) {
            return false;
        }
        int idx = text.indexOf(candidate);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(text.charAt(idx - 1));
            int end = idx + candidate.length();
            boolean endOk = end >= text.length() || !Character.isLetterOrDigit(text.charAt(end));
            if (startOk && endOk) {
                return true;
            }
            idx = text.indexOf(candidate, idx + 1);
        }
        return false;
    }
}
