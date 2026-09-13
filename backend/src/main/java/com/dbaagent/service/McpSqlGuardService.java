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

    /**
     * Functions that read or write outside the current read-only session.
     *
     * <p>Every check above classifies by statement <em>verb</em>, so a {@code SELECT} that calls
     * one of these passes cleanly: the allowlist sees SELECT and no forbidden verb appears.
     * {@code connection.setReadOnly(true)} does not stop them either — {@code dblink} opens a
     * <em>new outbound connection</em> whose transaction is not read-only, so the flag
     * constrains the session it is set on but never one the query dials out and creates.
     *
     * <p>Verified against a real PostgreSQL, not inferred: inside an explicitly
     * {@code BEGIN TRANSACTION READ ONLY}, {@code SELECT dblink_exec(..., 'DELETE FROM t')}
     * reported {@code DELETE 3} and the table went from three rows to zero;
     * {@code pg_read_file('/etc/hostname')} returned its contents from the database server's
     * filesystem. Both of the product's layers failed at once.
     *
     * <p>A denylist is the wrong shape in general, but it is the right shape here: the guard's
     * allowlist governs <em>verbs</em>, and there is no allowlist of functions to sit inside
     * a SELECT. Names are matched as calls (see {@link #DANGEROUS_FUNCTION_CALL}) so an
     * ordinary identifier of the same name still works.
     */
    private static final List<String> DANGEROUS_SQL_FUNCTIONS = List.of(
        // outbound connections — escape the read-only session entirely
        "dblink", "dblink_exec", "dblink_connect", "dblink_open", "dblink_send_query",
        // server-side file access
        "pg_read_file", "pg_read_binary_file", "pg_ls_dir", "pg_stat_file",
        "lo_import", "lo_export",
        // MySQL equivalents
        "load_file"
    );

    private static final String DANGEROUS_FUNCTION_ALTERNATION =
        String.join("|", DANGEROUS_SQL_FUNCTIONS);

    /**
     * A dangerous function being <em>called</em>: the name, optional whitespace, then an open
     * paren. Matching the bare name would reject ordinary identifiers — plenty of schemas have
     * a {@code dblink_audit} table or a {@code load_file_name} column, the same mistake
     * CLAUDE.md records for the old {@code \bCOMMENT\b} rule that rejected
     * {@code SELECT * FROM comment}. A leading word-boundary check keeps {@code my_dblink(} —
     * a different function — from matching.
     */
    private static final Pattern DANGEROUS_FUNCTION_CALL = Pattern.compile(
        "(?<![\\w$.])(" + DANGEROUS_FUNCTION_ALTERNATION + ")\\s*\\(",
        Pattern.CASE_INSENSITIVE);

    private static final Set<String> FORBIDDEN_SQL_KEYWORD_SET = Set.copyOf(FORBIDDEN_SQL_KEYWORDS);

    private static final String FORBIDDEN_ALTERNATION = String.join("|", FORBIDDEN_SQL_KEYWORDS);

    // WITH cte AS (DELETE ...) / AS MATERIALIZED (INSERT ...) — statement verb after AS (
    private static final Pattern CTE_MUTATION_PATTERN = Pattern.compile(
        "\\bAS(?:\\s+NOT)?(?:\\s+MATERIALIZED)?\\s*\\(\\s*(" + FORBIDDEN_ALTERNATION + ")\\b"
    );

    private static final Pattern FOR_UPDATE_PATTERN = Pattern.compile(
        "\\bFOR\\s+(?:NO\\s+KEY\\s+)?UPDATE\\b"
    );

    // Fail-closed fallback when the WITH-list scanner cannot parse: ") DELETE FROM ..."
    private static final Pattern TRAILING_DML_PATTERN = Pattern.compile(
        "\\)\\s*(" + FORBIDDEN_ALTERNATION + ")\\b"
    );

    private static final Pattern EXPLAIN_PREFIX_PATTERN = Pattern.compile(
        "^EXPLAIN(?:\\s*\\([^)]*\\))?"
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

        String dangerousFunction = containsDangerousFunction(statement);
        if (dangerousFunction != null) {
            return ValidationOutcome.invalid(
                "Blocked SQL function that reads or writes outside this session: "
                    + dangerousFunction + "."
            );
        }

        return ValidationOutcome.valid(stripTrailingSemicolons(sql), keyword);
    }

    /**
     * The first dangerous function call in the statement, or null.
     *
     * <p>Inspected with comments and string literals stripped, so neither
     * {@code /*x*} + {@code /dblink_exec(} nor a name mentioned inside a quoted literal can
     * hide or falsely trigger a match.
     */
    String containsDangerousFunction(String statement) {
        var matcher = DANGEROUS_FUNCTION_CALL.matcher(normalizeSqlForInspection(statement));
        return matcher.find() ? matcher.group(1).toLowerCase(Locale.ROOT) : null;
    }

    String normalizeSqlForInspection(String sql) {
        return compactWhitespace(stripSqlStringLiterals(stripSqlComments(sql)));
    }

    String firstKeyword(String sql) {
        var match = FIRST_KEYWORD_PATTERN.matcher(normalizeSqlForInspection(sql));
        return match.find() ? match.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Detect mutating <em>statements</em> nested inside an otherwise read-only wrapper
     * (WITH … DELETE, mutating CTEs, FOR UPDATE, EXPLAIN DELETE).
     *
     * <p>Do not use a bare {@code \bKEYWORD\b} scan: {@code COMMENT} and {@code CALL}
     * are common table/column names ({@code SELECT * FROM comment}), and
     * {@code REPLACE()} is a function. Match statement verbs only.
     */
    String containsForbiddenKeyword(String sql) {
        String inspect = normalizeSqlForInspection(sql).toUpperCase(Locale.ROOT);
        return findForbiddenMutation(inspect);
    }

    private String findForbiddenMutation(String sql) {
        if (FOR_UPDATE_PATTERN.matcher(sql).find()) {
            return "UPDATE";
        }

        var cteMutation = CTE_MUTATION_PATTERN.matcher(sql);
        if (cteMutation.find()) {
            return cteMutation.group(1);
        }

        String first = firstWord(sql);
        if ("EXPLAIN".equals(first)) {
            String inner = EXPLAIN_PREFIX_PATTERN.matcher(sql).replaceFirst("").trim();
            String innerFirst = firstWord(inner);
            if (innerFirst == null) {
                return null;
            }
            if (!ALLOWED_READ_ONLY_KEYWORDS.contains(innerFirst)) {
                return innerFirst;
            }
            return findForbiddenMutation(inner);
        }

        if ("WITH".equals(first)) {
            String main = remainderAfterWithClause(sql);
            if (main != null) {
                String mainFirst = firstWord(main);
                if (mainFirst != null && FORBIDDEN_SQL_KEYWORD_SET.contains(mainFirst)) {
                    return mainFirst;
                }
            } else {
                var trailing = TRAILING_DML_PATTERN.matcher(sql);
                if (trailing.find()) {
                    return trailing.group(1);
                }
            }
        }

        return null;
    }

    private static String firstWord(String sql) {
        if (sql == null || sql.isBlank()) {
            return null;
        }
        var match = FIRST_KEYWORD_PATTERN.matcher(sql.trim());
        return match.find() ? match.group(1).toUpperCase(Locale.ROOT) : null;
    }

    /**
     * Skip {@code WITH [RECURSIVE] name [ (cols) ] AS [NOT] [MATERIALIZED] (...), ...}
     * and return the main statement that follows the CTE list, or {@code null} if
     * the shape cannot be parsed.
     */
    String remainderAfterWithClause(String sql) {
        if (sql == null || !sql.startsWith("WITH")) {
            return null;
        }
        int i = skipWhitespace(sql, 4);
        if (regionMatches(sql, i, "RECURSIVE")) {
            i = skipWhitespace(sql, i + 9);
        }
        while (i < sql.length()) {
            int next = skipIdent(sql, i);
            if (next < 0) {
                return null;
            }
            i = skipWhitespace(sql, next);
            if (i < sql.length() && sql.charAt(i) == '(') {
                i = skipBalancedParens(sql, i);
                if (i < 0) {
                    return null;
                }
                i = skipWhitespace(sql, i);
            }
            if (!regionMatches(sql, i, "AS")) {
                return null;
            }
            i = skipWhitespace(sql, i + 2);
            if (regionMatches(sql, i, "NOT")) {
                i = skipWhitespace(sql, i + 3);
            }
            if (regionMatches(sql, i, "MATERIALIZED")) {
                i = skipWhitespace(sql, i + 12);
            }
            if (i >= sql.length() || sql.charAt(i) != '(') {
                return null;
            }
            i = skipBalancedParens(sql, i);
            if (i < 0) {
                return null;
            }
            i = skipWhitespace(sql, i);
            if (i < sql.length() && sql.charAt(i) == ',') {
                i = skipWhitespace(sql, i + 1);
                continue;
            }
            return i < sql.length() ? sql.substring(i) : "";
        }
        return null;
    }

    private static boolean regionMatches(String sql, int offset, String token) {
        return offset >= 0
            && offset + token.length() <= sql.length()
            && sql.startsWith(token, offset)
            && (offset + token.length() == sql.length()
                || !isIdentChar(sql.charAt(offset + token.length())));
    }

    private static boolean isIdentChar(char c) {
        return Character.isLetterOrDigit(c) || c == '_';
    }

    private static int skipWhitespace(String sql, int i) {
        while (i < sql.length() && Character.isWhitespace(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipIdent(String sql, int i) {
        if (i >= sql.length()) {
            return -1;
        }
        char c = sql.charAt(i);
        if (c == '"' || c == '`' || c == '\'') {
            char quote = c;
            i++;
            while (i < sql.length() && sql.charAt(i) != quote) {
                i++;
            }
            if (i >= sql.length()) {
                return -1;
            }
            return i + 1;
        }
        if (c == '.' ) {
            return -1;
        }
        if (!Character.isLetter(c) && c != '_') {
            return -1;
        }
        i++;
        while (i < sql.length() && isIdentChar(sql.charAt(i))) {
            i++;
        }
        return i;
    }

    private static int skipBalancedParens(String sql, int openAt) {
        if (openAt >= sql.length() || sql.charAt(openAt) != '(') {
            return -1;
        }
        int depth = 0;
        for (int i = openAt; i < sql.length(); i++) {
            char c = sql.charAt(i);
            if (c == '(') {
                depth++;
            } else if (c == ')') {
                depth--;
                if (depth == 0) {
                    return i + 1;
                }
            }
        }
        return -1;
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
