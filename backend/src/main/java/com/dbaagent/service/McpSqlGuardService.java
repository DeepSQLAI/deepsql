package com.dbaagent.service;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class McpSqlGuardService {

    private static final Pattern FIRST_KEYWORD_PATTERN = Pattern.compile("^([A-Za-z]+)");
    private static final Pattern EXPLAIN_ANALYZE_PATTERN = Pattern.compile("\\bANALYZ[EA]\\b", Pattern.CASE_INSENSITIVE);
    private static final Pattern BLOCK_COMMENT_PATTERN = Pattern.compile("/\\*[\\s\\S]*?\\*/");
    private static final Pattern LINE_COMMENT_PATTERN = Pattern.compile("(?m)--.*$");
    private static final Pattern SINGLE_QUOTE_STRING_PATTERN = Pattern.compile("'(?:''|[^'])*'");
    private static final Pattern DOUBLE_QUOTE_STRING_PATTERN = Pattern.compile("\"(?:[\"\"]|[^\"])*\"");
    private static final Pattern BACKTICK_STRING_PATTERN = Pattern.compile("`(?:``|[^`])*`");

    private static final Set<String> ALLOWED_READ_ONLY_KEYWORDS = Set.of(
        "SELECT",
        "WITH",
        "SHOW",
        "DESCRIBE",
        "DESC",
        "EXPLAIN"
    );

    private static final List<String> FORBIDDEN_SQL_KEYWORDS = List.of(
        "INSERT",
        "UPDATE",
        "DELETE",
        "ALTER",
        "DROP",
        "TRUNCATE",
        "CREATE",
        "MERGE",
        "REPLACE",
        "GRANT",
        "REVOKE",
        "CALL",
        "COPY",
        "VACUUM",
        "COMMENT"
    );

    public ValidationOutcome validateReadOnlySql(String sql, boolean allowExplain) {
        if (sql == null || sql.isBlank()) {
            return ValidationOutcome.invalid("Query is required.");
        }

        List<String> statements = splitStatements(sql);
        if (statements.size() != 1) {
            return ValidationOutcome.invalid("Phase 1 MCP only allows a single SQL statement.");
        }

        String statement = statements.getFirst();
        String keyword = firstKeyword(statement);
        if (keyword == null || !ALLOWED_READ_ONLY_KEYWORDS.contains(keyword)) {
            return ValidationOutcome.invalid(
                "Only read-only SQL is allowed (SELECT, WITH, SHOW, DESCRIBE, DESC, EXPLAIN)."
            );
        }

        if (!allowExplain && "EXPLAIN".equals(keyword)) {
            return ValidationOutcome.invalid("Pass the underlying SELECT/WITH query, not EXPLAIN itself.");
        }

        if ("EXPLAIN".equals(keyword) && EXPLAIN_ANALYZE_PATTERN.matcher(statement).find()) {
            return ValidationOutcome.invalid(
                "EXPLAIN ANALYZE is blocked in phase 1 MCP because it executes the query."
            );
        }

        String forbiddenKeyword = containsForbiddenKeyword(statement);
        if (forbiddenKeyword != null) {
            return ValidationOutcome.invalid(
                "Blocked potentially mutating SQL keyword: " + forbiddenKeyword + "."
            );
        }

        return ValidationOutcome.valid(stripTrailingSemicolons(sql), keyword);
    }

    String normalizeSqlForInspection(String sql) {
        return compactWhitespace(stripSqlStringLiterals(stripSqlComments(sql)));
    }

    String firstKeyword(String sql) {
        var match = FIRST_KEYWORD_PATTERN.matcher(normalizeSqlForInspection(sql));
        return match.find() ? match.group(1).toUpperCase(Locale.ROOT) : null;
    }

    String containsForbiddenKeyword(String sql) {
        String normalized = normalizeSqlForInspection(sql);
        for (String keyword : FORBIDDEN_SQL_KEYWORDS) {
            if (Pattern.compile("\\b" + Pattern.quote(keyword) + "\\b", Pattern.CASE_INSENSITIVE)
                .matcher(normalized).find()) {
                return keyword;
            }
        }
        return null;
    }

    List<String> splitStatements(String sql) {
        return List.of(normalizeSqlForInspection(sql).split(";")).stream()
            .map(String::trim)
            .filter(part -> !part.isEmpty())
            .toList();
    }

    private String stripSqlComments(String sql) {
        String input = String.valueOf(sql);
        return LINE_COMMENT_PATTERN.matcher(BLOCK_COMMENT_PATTERN.matcher(input).replaceAll(" ")).replaceAll(" ");
    }

    private String stripSqlStringLiterals(String sql) {
        String input = String.valueOf(sql);
        input = SINGLE_QUOTE_STRING_PATTERN.matcher(input).replaceAll("''");
        input = DOUBLE_QUOTE_STRING_PATTERN.matcher(input).replaceAll("\"\"");
        return BACKTICK_STRING_PATTERN.matcher(input).replaceAll("``");
    }

    private String compactWhitespace(String value) {
        return String.valueOf(value)
            .replaceAll("\\s+", " ")
            .trim();
    }

    private String stripTrailingSemicolons(String sql) {
        return String.valueOf(sql)
            .trim()
            .replaceAll(";+$", "")
            .trim();
    }

    public record ValidationOutcome(
        boolean ok,
        String reason,
        String normalizedQuery,
        String firstKeyword
    ) {
        static ValidationOutcome valid(String normalizedQuery, String firstKeyword) {
            return new ValidationOutcome(true, null, normalizedQuery, firstKeyword);
        }

        static ValidationOutcome invalid(String reason) {
            return new ValidationOutcome(false, reason, null, null);
        }
    }
}
