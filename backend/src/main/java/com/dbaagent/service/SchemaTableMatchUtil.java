package com.dbaagent.service;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

public final class SchemaTableMatchUtil {

    private SchemaTableMatchUtil() {
    }

    public static String normalizeQuestion(String question) {
        String normalized = question == null ? "" : question
            .toLowerCase(Locale.ROOT)
            .replace('`', ' ')
            .replaceAll("[^a-z0-9_ ]", " ")
            .replaceAll("\\s+", " ")
            .trim();
        return " " + normalized + " ";
    }

    public static boolean mentionsTable(String normalizedQuestion, String tableName) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()
                || tableName == null || tableName.isBlank()) {
            return false;
        }
        return tableNameVariants(tableName).stream()
            .anyMatch(variant -> containsWholeTerm(normalizedQuestion, variant));
    }

    private static Set<String> tableNameVariants(String tableName) {
        String canonical = tableName.toLowerCase(Locale.ROOT);
        String spaced = canonical.replace('_', ' ');
        var variants = new LinkedHashSet<String>();
        variants.add(canonical);
        variants.add(spaced);
        addPluralVariants(variants, canonical);
        addPluralVariants(variants, spaced);

        String suffixToken = suffixToken(canonical);
        if (suffixToken != null && suffixToken.length() >= 4) {
            variants.add(suffixToken);
            addPluralVariants(variants, suffixToken);
        }

        return variants;
    }

    private static String suffixToken(String tableName) {
        List<String> tokens = List.of(tableName.split("[_\\s]+"));
        return tokens.isEmpty() ? null : tokens.get(tokens.size() - 1);
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
