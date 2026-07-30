package com.dbaagent.util;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Splits SQL text on semicolons while respecting string literals and comments.
 * Handles single-quoted strings (with \' and '' escaping), double-quoted identifiers,
 * backtick-quoted identifiers, single-line comments (--, and # for MySQL only),
 * and block comments.
 *
 * <p>{@code #} is only treated as a line-comment character in MySQL mode.
 * PostgreSQL uses {@code #>} / {@code #>>} as JSONB operators, so treating
 * {@code #} as a comment would silently truncate valid PG queries.
 */
public final class SqlStatementSplitter {

    private static final Pattern SET_ANY_PATTERN =
        Pattern.compile("^\\s*SET\\s+", Pattern.CASE_INSENSITIVE);

    private SqlStatementSplitter() {}

    /**
     * Split SQL text into individual statements (MySQL mode — # is a comment).
     */
    public static List<String> split(String sql) {
        return split(sql, true);
    }

    /**
     * Split SQL text into individual statements on semicolons,
     * respecting string literals and comments.
     *
     * @param hashIsComment true for MySQL (# starts a line comment),
     *                      false for PostgreSQL (# is a normal character)
     * @return list of trimmed, non-empty statements (without trailing semicolons)
     */
    public static List<String> split(String sql, boolean hashIsComment) {
        if (sql == null || sql.isBlank()) {
            return List.of();
        }

        List<String> statements = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        int len = sql.length();
        int i = 0;

        while (i < len) {
            char c = sql.charAt(i);

            // PostgreSQL dollar-quoted string: $tag$...$tag$ or $$...$$
            if (!hashIsComment && c == '$') {
                int end = skipDollarQuotedString(sql, i);
                if (end > i) {
                    current.append(sql, i, end);
                    i = end;
                    continue;
                }
                // Not a dollar-quote start — fall through to treat $ as normal char
            }

            // Single-quoted string literal
            if (c == '\'') {
                int end = skipSingleQuotedString(sql, i);
                current.append(sql, i, end);
                i = end;
                continue;
            }

            // Double-quoted identifier
            if (c == '"') {
                int end = skipQuotedIdentifier(sql, i, '"');
                current.append(sql, i, end);
                i = end;
                continue;
            }

            // Backtick-quoted identifier (MySQL)
            if (c == '`') {
                int end = skipQuotedIdentifier(sql, i, '`');
                current.append(sql, i, end);
                i = end;
                continue;
            }

            // Single-line comment: --
            if (c == '-' && i + 1 < len && sql.charAt(i + 1) == '-') {
                int end = skipLineComment(sql, i);
                current.append(sql, i, end);
                i = end;
                continue;
            }
            // MySQL-only: # starts a line comment
            if (hashIsComment && c == '#') {
                int end = skipLineComment(sql, i);
                current.append(sql, i, end);
                i = end;
                continue;
            }

            // Block comment: /* ... */
            if (c == '/' && i + 1 < len && sql.charAt(i + 1) == '*') {
                int end = skipBlockComment(sql, i);
                current.append(sql, i, end);
                i = end;
                continue;
            }

            // Semicolon: statement boundary
            if (c == ';') {
                String stmt = current.toString().trim();
                if (!stmt.isEmpty()) {
                    statements.add(stmt);
                }
                current.setLength(0);
                i++;
                continue;
            }

            current.append(c);
            i++;
        }

        // Remaining text after last semicolon (or entire text if no semicolons)
        String remainder = current.toString().trim();
        if (!remainder.isEmpty()) {
            statements.add(remainder);
        }

        return statements;
    }

    /**
     * Split SQL into SET preamble statements and the main body statement (MySQL mode).
     */
    public static String[] splitSetPreamble(String sql) {
        return splitSetPreamble(sql, true);
    }

    /**
     * Split SQL into SET preamble statements and the main body statement.
     * Recognises all SET forms: SET @var, SET SESSION, SET @@session, SET NAMES, etc.
     * Returns an array where the last element is the non-SET body (the SELECT),
     * and all preceding elements are SET statements.
     * If there are no SET statements, returns a single-element array with the original SQL.
     *
     * @param hashIsComment true for MySQL, false for PostgreSQL
     */
    public static String[] splitSetPreamble(String sql, boolean hashIsComment) {
        if (sql == null || sql.isBlank()) {
            return new String[]{sql};
        }

        List<String> parts = split(sql, hashIsComment);
        if (parts.isEmpty()) {
            return new String[]{""};
        }

        List<String> setStmts = new ArrayList<>();
        StringBuilder body = new StringBuilder();
        boolean foundBody = false;

        for (String part : parts) {
            if (!foundBody && SET_ANY_PATTERN.matcher(stripLeadingComments(part, hashIsComment)).find()) {
                setStmts.add(part);
            } else {
                foundBody = true;
                if (body.length() > 0) body.append("; ");
                body.append(part);
            }
        }

        if (setStmts.isEmpty()) {
            return new String[]{body.toString()};
        }

        String[] result = new String[setStmts.size() + 1];
        for (int i = 0; i < setStmts.size(); i++) {
            result[i] = setStmts.get(i);
        }
        result[setStmts.size()] = body.toString();
        return result;
    }

    /**
     * Strip a trailing top-level ORDER BY clause from a SQL statement so it can be safely
     * wrapped in a COUNT(*) subquery or appended with a LIMIT clause.
     * Scans right-to-left and ignores ORDER BY tokens inside parentheses.
     */
    public static String stripOrderBy(String sql) {
        if (sql == null || sql.isBlank()) return sql;
        String upper = sql.toUpperCase(java.util.Locale.ROOT);
        int depth = 0;
        for (int i = upper.length() - 1; i >= 8; i--) {
            char c = upper.charAt(i);
            if (c == ')') depth++;
            else if (c == '(') depth--;
            if (depth == 0) {
                // Check for "ORDER BY" starting at i (need at least "ORDER BY" = 8 chars)
                if (i + 8 <= upper.length() && "ORDER BY".equals(upper.substring(i, Math.min(i + 8, upper.length())))) {
                    return sql.substring(0, i).trim();
                }
            }
        }
        return sql;
    }

    /**
     * Check if a statement is a SET statement (any form).
     * Strips leading comments before checking.
     */
    public static boolean isSetStatement(String sql) {
        if (sql == null) return false;
        return SET_ANY_PATTERN.matcher(stripLeadingComments(sql)).find();
    }

    /**
     * Strip leading single-line (-- ...) and block comments from a statement.
     * MySQL mode also strips # comments.
     */
    static String stripLeadingComments(String sql) {
        return stripLeadingComments(sql, true);
    }

    static String stripLeadingComments(String sql, boolean hashIsComment) {
        String s = sql.trim();
        while (true) {
            if (s.startsWith("--")) {
                int nl = s.indexOf('\n');
                if (nl < 0) return "";
                s = s.substring(nl + 1).trim();
            } else if (s.startsWith("/*")) {
                int end = s.indexOf("*/");
                if (end < 0) return "";
                s = s.substring(end + 2).trim();
            } else if (hashIsComment && s.startsWith("#")) {
                int nl = s.indexOf('\n');
                if (nl < 0) return "";
                s = s.substring(nl + 1).trim();
            } else {
                break;
            }
        }
        return s;
    }

    // --- Internal scanning helpers ---

    /** Advance past a single-quoted string, handling \' and '' escape sequences. */
    private static int skipSingleQuotedString(String sql, int start) {
        int len = sql.length();
        int i = start + 1; // skip opening quote
        while (i < len) {
            char c = sql.charAt(i);
            if (c == '\\') {
                i += 2; // skip escaped character
                continue;
            }
            if (c == '\'') {
                if (i + 1 < len && sql.charAt(i + 1) == '\'') {
                    i += 2; // skip '' escape
                    continue;
                }
                return i + 1; // past closing quote
            }
            i++;
        }
        return len; // unterminated — consume to end
    }

    /** Advance past a quoted identifier (double-quote or backtick). */
    private static int skipQuotedIdentifier(String sql, int start, char quoteChar) {
        int len = sql.length();
        int i = start + 1;
        while (i < len) {
            if (sql.charAt(i) == quoteChar) {
                return i + 1;
            }
            i++;
        }
        return len;
    }

    /** Advance past a single-line comment (-- or #) to end of line. */
    private static int skipLineComment(String sql, int start) {
        int len = sql.length();
        int i = start;
        while (i < len && sql.charAt(i) != '\n') {
            i++;
        }
        return i < len ? i + 1 : len; // include the newline
    }

    /**
     * Advance past a PostgreSQL dollar-quoted string: $tag$...$tag$ or $$...$$.
     * Returns start if the position is not the beginning of a dollar-quote.
     */
    private static int skipDollarQuotedString(String sql, int start) {
        int len = sql.length();
        // Find the opening tag: $<optional_identifier>$
        int tagEnd = start + 1;
        while (tagEnd < len && (Character.isLetterOrDigit(sql.charAt(tagEnd)) || sql.charAt(tagEnd) == '_')) {
            tagEnd++;
        }
        if (tagEnd >= len || sql.charAt(tagEnd) != '$') {
            return start; // not a dollar-quote
        }
        String tag = sql.substring(start, tagEnd + 1); // e.g. "$$" or "$tag$"
        int bodyStart = tagEnd + 1;
        int closeIdx = sql.indexOf(tag, bodyStart);
        if (closeIdx < 0) {
            return len; // unterminated — consume to end
        }
        return closeIdx + tag.length();
    }

    /** Advance past a block comment. */
    private static int skipBlockComment(String sql, int start) {
        int len = sql.length();
        int i = start + 2; // skip /*
        while (i + 1 < len) {
            if (sql.charAt(i) == '*' && sql.charAt(i + 1) == '/') {
                return i + 2;
            }
            i++;
        }
        return len; // unterminated
    }
}
