package com.dbaagent.util;

import java.util.ArrayList;
import java.util.List;

/**
 * Extracts literal values from executable SQL and substitutes them into
 * parameterized SQL (with {@code ?} or {@code $N} placeholders).
 *
 * Used by both the optimization pipeline (at candidate creation time) and
 * the benchmark service (at benchmark time, when creation-time substitution
 * failed due to placeholder count mismatch).
 */
public final class SqlLiteralSubstitution {

    private SqlLiteralSubstitution() {}

    /**
     * Attempt to replace placeholders in {@code parameterizedSql} with literal
     * values extracted from {@code sourceSqlWithLiterals}.
     *
     * @return the literalized SQL, or the original {@code parameterizedSql}
     *         unchanged if substitution is not possible.
     */
    public static String substituteLiterals(String parameterizedSql, String sourceSqlWithLiterals) {
        if (parameterizedSql == null || parameterizedSql.isBlank()
                || sourceSqlWithLiterals == null || sourceSqlWithLiterals.isBlank()) {
            return parameterizedSql;
        }

        List<String> literals = extractLiteralValues(sourceSqlWithLiterals);
        if (literals.isEmpty()) {
            return parameterizedSql;
        }

        String replaced = replaceQuestionPlaceholders(parameterizedSql, literals);
        if (!replaced.equals(parameterizedSql)) {
            return replaced;
        }

        return replacePgPlaceholders(parameterizedSql, literals);
    }

    /**
     * Check whether SQL contains parameter placeholders ({@code ?} or {@code $N}).
     */
    public static boolean hasPlaceholders(String sql) {
        if (sql == null || sql.isBlank()) return false;
        if (sql.contains("?")) return true;
        return java.util.regex.Pattern.compile("\\$\\d+").matcher(sql).find();
    }

    // ── Placeholder replacement ───────────────────────────────────────────

    static String replaceQuestionPlaceholders(String sql, List<String> literals) {
        if (sql == null || !sql.contains("?")) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        int literalIdx = 0;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (!inDouble && !inBacktick && c == '\'') {
                out.append(c);
                if (inSingle && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && !inBacktick && c == '"') {
                inDouble = !inDouble;
                out.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '`') {
                inBacktick = !inBacktick;
                out.append(c);
                continue;
            }

            if (!inSingle && !inDouble && !inBacktick && c == '?') {
                if (literalIdx >= literals.size()) {
                    return sql;  // more placeholders than literals → bail
                }
                out.append(literals.get(literalIdx++));
                continue;
            }

            out.append(c);
        }

        return literalIdx == 0 ? sql : out.toString();
    }

    static String replacePgPlaceholders(String sql, List<String> literals) {
        if (sql == null || !sql.contains("$")) {
            return sql;
        }
        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (!inDouble && !inBacktick && c == '\'') {
                out.append(c);
                if (inSingle && i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                    out.append('\'');
                    i++;
                    continue;
                }
                inSingle = !inSingle;
                continue;
            }
            if (!inSingle && !inBacktick && c == '"') {
                inDouble = !inDouble;
                out.append(c);
                continue;
            }
            if (!inSingle && !inDouble && c == '`') {
                inBacktick = !inBacktick;
                out.append(c);
                continue;
            }

            if (!inSingle && !inDouble && !inBacktick && c == '$' && i + 1 < sql.length()
                    && Character.isDigit(sql.charAt(i + 1))) {
                int j = i + 1;
                while (j < sql.length() && Character.isDigit(sql.charAt(j))) {
                    j++;
                }
                String idxText = sql.substring(i + 1, j);
                int idx = Integer.parseInt(idxText);
                if (idx < 1 || idx > literals.size()) {
                    return sql;  // out-of-range → bail
                }
                out.append(literals.get(idx - 1));
                i = j - 1;
                continue;
            }

            out.append(c);
        }

        return out.toString();
    }

    /**
     * Parse a PostgreSQL {@code Query Parameters} value list emitted by
     * auto_explain / log_statement (extended query protocol) into an ordered
     * list of SQL literal tokens (list index 0 == {@code $1}).
     *
     * <p>Example input:
     * {@code $1 = '2026-05-15 04:00:00+00', $2 = 'CANCELED', $3 = NULL, $4 = 'f'}
     * yields {@code ["'2026-05-15 04:00:00+00'", "'CANCELED'", "NULL", "'f'"]}.
     *
     * <p>Quoted values keep their surrounding quotes (and doubled {@code ''}
     * escapes); {@code NULL} is preserved verbatim, so the result can be fed
     * straight into {@link #replacePgPlaceholders} to reconstruct the literal
     * query. Gaps (if any) are filled with {@code NULL}.
     */
    public static List<String> parsePgLogParameters(String paramsText) {
        List<String> out = new ArrayList<>();
        if (paramsText == null || paramsText.isBlank()) {
            return out;
        }
        java.util.Map<Integer, String> byIndex = new java.util.HashMap<>();
        int n = paramsText.length();
        int i = 0;
        int maxIdx = 0;
        while (i < n) {
            // Advance to the next "$<digits>" marker.
            if (paramsText.charAt(i) != '$') { i++; continue; }
            int j = i + 1;
            while (j < n && Character.isDigit(paramsText.charAt(j))) { j++; }
            if (j == i + 1) { i = j; continue; }  // "$" not followed by digits
            int idx = Integer.parseInt(paramsText.substring(i + 1, j));
            i = j;
            // Skip the " = " separator.
            while (i < n && (paramsText.charAt(i) == ' ' || paramsText.charAt(i) == '=')) { i++; }

            String value;
            if (i < n && paramsText.charAt(i) == '\'') {
                StringBuilder v = new StringBuilder("'");
                i++;
                while (i < n) {
                    char c = paramsText.charAt(i);
                    if (c == '\'') {
                        if (i + 1 < n && paramsText.charAt(i + 1) == '\'') {
                            v.append("''"); i += 2; continue;  // escaped quote
                        }
                        v.append('\''); i++; break;            // end of string
                    }
                    v.append(c); i++;
                }
                value = v.toString();
            } else {
                // Unquoted token (NULL, numbers) up to the next ", $" boundary.
                int start = i;
                while (i < n) {
                    if (paramsText.charAt(i) == ',') {
                        int k = i + 1;
                        while (k < n && paramsText.charAt(k) == ' ') { k++; }
                        if (k < n && paramsText.charAt(k) == '$') { break; }
                    }
                    i++;
                }
                value = paramsText.substring(start, i).trim();
            }
            if (idx >= 1) {
                byIndex.put(idx, value);
                if (idx > maxIdx) { maxIdx = idx; }
            }
        }
        for (int k = 1; k <= maxIdx; k++) {
            out.add(byIndex.getOrDefault(k, "NULL"));
        }
        return out;
    }

    /**
     * Reconstruct a literal-bearing SQL from a parameterized statement
     * (containing {@code $N} placeholders) and a PostgreSQL bind-value list as
     * logged by auto_explain ("Query Parameters:") or the extended query
     * protocol ("DETAIL: parameters:"). Returns {@code parameterizedSql}
     * unchanged when there is nothing to substitute or counts don't line up.
     */
    public static String substitutePgLogParameters(String parameterizedSql, String pgParamsText) {
        if (parameterizedSql == null || parameterizedSql.isBlank()
                || pgParamsText == null || pgParamsText.isBlank()
                || !hasPlaceholders(parameterizedSql)) {
            return parameterizedSql;
        }
        List<String> literals = parsePgLogParameters(pgParamsText);
        if (literals.isEmpty()) {
            return parameterizedSql;
        }
        return replacePgPlaceholders(parameterizedSql, literals);
    }

    // ── Literal extraction ────────────────────────────────────────────────

    /**
     * Extract ordered literal values (strings, numbers, booleans, null) from SQL.
     */
    public static List<String> extractLiteralValues(String sql) {
        List<String> values = new ArrayList<>();
        if (sql == null || sql.isBlank()) {
            return values;
        }

        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;
        StringBuilder current = null;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (inSingle) {
                if (c == '\\' && i + 1 < sql.length()) {
                    current.append(c).append(sql.charAt(i + 1));
                    i++;
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        current.append("''");
                        i++;
                        continue;
                    }
                    values.add("'" + current + "'");
                    current = null;
                    inSingle = false;
                    continue;
                }
                current.append(c);
                continue;
            }

            if (!inDouble && !inBacktick && c == '\'') {
                inSingle = true;
                current = new StringBuilder();
                continue;
            }
            if (!inSingle && !inBacktick && c == '"') {
                inDouble = !inDouble;
                continue;
            }
            if (!inSingle && !inDouble && c == '`') {
                inBacktick = !inBacktick;
                continue;
            }
            if (inDouble || inBacktick) {
                continue;
            }

            if (matchKeywordLiteral(sql, i, "true")) {
                values.add("true");
                i += 3;
                continue;
            }
            if (matchKeywordLiteral(sql, i, "false")) {
                values.add("false");
                i += 4;
                continue;
            }
            if (matchKeywordLiteral(sql, i, "null")) {
                values.add("null");
                i += 3;
                continue;
            }

            if (Character.isDigit(c) || (c == '-' && i + 1 < sql.length() && Character.isDigit(sql.charAt(i + 1)))) {
                char prev = i > 0 ? sql.charAt(i - 1) : ' ';
                if (Character.isLetterOrDigit(prev) || prev == '_' || prev == '.') {
                    continue;
                }
                int j = i + 1;
                boolean seenDot = false;
                if (c == '-') {
                    j = i + 2;
                }
                while (j < sql.length()) {
                    char cj = sql.charAt(j);
                    if (Character.isDigit(cj)) {
                        j++;
                        continue;
                    }
                    if (cj == '.' && !seenDot) {
                        seenDot = true;
                        j++;
                        continue;
                    }
                    break;
                }
                if (j < sql.length() && (sql.charAt(j) == 'e' || sql.charAt(j) == 'E')) {
                    int k = j + 1;
                    if (k < sql.length() && (sql.charAt(k) == '+' || sql.charAt(k) == '-')) {
                        k++;
                    }
                    while (k < sql.length() && Character.isDigit(sql.charAt(k))) {
                        k++;
                    }
                    if (k > j + 1) {
                        j = k;
                    }
                }
                values.add(sql.substring(i, j));
                i = j - 1;
            }
        }

        return values;
    }

    private static boolean matchKeywordLiteral(String sql, int idx, String keyword) {
        if (idx + keyword.length() > sql.length()) {
            return false;
        }
        String segment = sql.substring(idx, idx + keyword.length());
        if (!segment.equalsIgnoreCase(keyword)) {
            return false;
        }
        if (idx > 0) {
            char before = sql.charAt(idx - 1);
            if (Character.isLetterOrDigit(before) || before == '_') {
                return false;
            }
        }
        int endIdx = idx + keyword.length();
        if (endIdx < sql.length()) {
            char after = sql.charAt(endIdx);
            if (Character.isLetterOrDigit(after) || after == '_') {
                return false;
            }
        }
        return true;
    }
}
