package com.dbaagent.service;

import com.dbaagent.util.QueryNormalizer;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds the queries a public dashboard may run to the ones its artifact actually contains.
 *
 * <p>{@code POST /api/public/dashboards/{token}/query} takes the SQL as a request body field.
 * Checking only that the statement reads is not enough: it answers "is this a select" when the
 * question is "is this a query this dashboard was published to run". Without the shape check
 * below, a link shared to show one chart granted anonymous read of the whole connection —
 * verified live against a real share token, which returned customer rows including
 * {@code email}, {@code password_hash} and {@code phone}.
 *
 * <p>Exact string matching cannot be the answer. Dashboards are interactive by design — a date
 * picker re-queries with new bounds on every change ({@code dashboard-design/SKILL.md}), so the
 * exact string is not knowable at publish time. Matching would then fail only on the public
 * link while the author's own view kept working, which is the worst shape a regression can take.
 *
 * <p>So queries are matched by <em>shape</em>: the statement with its literals replaced by
 * placeholders, via the same {@link QueryNormalizer} that backs {@link QueryFingerprintService}.
 * Two queries differing only in a date range share a shape; two naming different tables or
 * columns do not.
 *
 * <p><strong>Real artifacts assign the SQL to a variable first.</strong> An earlier version of
 * this class matched only a literal argument to {@code deepsql.query(...)}. Every call site in
 * the real dashboards checked — 18 of 18, across the names {@code query}, {@code sql},
 * {@code trendQuery} and {@code totalQuery} — instead does:
 *
 * <pre>{@code
 *   const sql = `SELECT ... WHERE created_at >= '${esc(from)}'`;
 *   const { rows } = await deepsql.query(sql);
 * }</pre>
 *
 * So extraction produced an empty set and, failing closed, refused every query on every
 * existing public dashboard. Declarations are resolved first and the call's argument is looked
 * up among them, which is why this does not key on particular variable names.
 *
 * <p>This is one layer, not the only one. {@code validateReadOnlySql},
 * {@code connection.setReadOnly(true)}, the row cap and the {@code is_public} re-check all still
 * apply. That matters because {@code QueryNormalizer} was written for analytics grouping, where
 * a collision is a cosmetic nuisance rather than a vulnerability.
 */
@Service
public class DashboardQueryShapeService {

    /** A string literal in any of the three quoting styles the agent emits. */
    private static final String LITERAL =
        "`(?:[^`\\\\]|\\\\.)*`|\"(?:[^\"\\\\]|\\\\.)*\"|'(?:[^'\\\\]|\\\\.)*'";

    /** {@code const|let|var <name> = <literal>} — how every real artifact holds its SQL. */
    private static final Pattern DECLARATION = Pattern.compile(
        "\\b(?:const|let|var)\\s+([A-Za-z_$][\\w$]*)\\s*=\\s*(" + LITERAL + ")",
        Pattern.DOTALL);

    /** The argument of a {@code deepsql.query(...)} call: a literal, or an identifier. */
    private static final Pattern QUERY_CALL = Pattern.compile(
        "deepsql\\s*\\.\\s*query\\s*\\(\\s*(" + LITERAL + "|[A-Za-z_$][\\w$]*)\\s*[,)]",
        Pattern.DOTALL);

    /** Any {@code deepsql.query(} call at all, used to spot arguments neither branch resolved. */
    private static final Pattern ANY_QUERY_CALL = Pattern.compile("deepsql\\s*\\.\\s*query\\s*\\(");

    /**
     * One {@code <script>} block. Each widget is its own block and its own scope: a real
     * dashboard here has nine blocks, eight declaring their own {@code const sql = ...} with
     * different SQL. Resolving across the whole document collapses those onto one name and
     * silently drops seven queries, so declarations are resolved per block.
     */
    private static final Pattern SCRIPT_BLOCK = Pattern.compile(
        "<script\\b[^>]*>(.*?)</script\\s*>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE);

    /**
     * A JS template interpolation. Replaced with a placeholder before normalizing, so
     * {@code '${esc(from)}'} yields the same shape as the {@code '2026-01-01'} it becomes at
     * runtime.
     */
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{[^}]*\\}");

    /** Extracts the shape of every query the artifact can issue. */
    public Set<String> extractShapes(String artifactHtml) {
        Set<String> shapes = new LinkedHashSet<>();
        if (artifactHtml == null || artifactHtml.isBlank()) {
            return shapes;
        }
        for (String scope : scopes(artifactHtml)) {
            Map<String, String> declared = declaredLiterals(scope);
            Matcher calls = QUERY_CALL.matcher(scope);
            while (calls.find()) {
                String sql = resolveArgument(calls.group(1), declared);
                if (sql == null) {
                    continue;
                }
                String shape = shapeOf(sql);
                if (!shape.isBlank()) {
                    shapes.add(shape);
                }
            }
        }
        return shapes;
    }

    /**
     * Whether the artifact issues a query whose SQL this class could not recover — for example
     * one built by concatenation or returned from a helper.
     *
     * <p>Such a call must be reported rather than skipped. Skipping it publishes a shape set
     * missing one of the dashboard's own queries, which then fails closed at runtime: a widget
     * broken for the audience only, with nothing on the authoring side to indicate why.
     */
    public boolean hasUnresolvableQuery(String artifactHtml) {
        if (artifactHtml == null || artifactHtml.isBlank()) {
            return false;
        }
        return totalQueryCalls(artifactHtml) > resolvedQueryCalls(artifactHtml);
    }

    /** The shape of one SQL statement: its literals replaced by placeholders. */
    public String shapeOf(String sql) {
        if (sql == null || sql.isBlank()) {
            return "";
        }
        return QueryNormalizer.normalize(sql);
    }

    /**
     * Whether {@code sql} matches a published shape. Fails closed: an empty shape set, a blank
     * statement, or any shape that was not extracted is refused.
     */
    public boolean matches(Set<String> publishedShapes, String sql) {
        if (publishedShapes == null || publishedShapes.isEmpty() || sql == null || sql.isBlank()) {
            return false;
        }
        String shape = shapeOf(sql);
        return !shape.isBlank() && publishedShapes.contains(shape);
    }

    /**
     * The artifact's scopes: each {@code <script>} block, or the whole document when it has
     * none, so a call outside a script tag is still seen.
     */
    private java.util.List<String> scopes(String artifactHtml) {
        java.util.List<String> scopes = new java.util.ArrayList<>();
        Matcher blocks = SCRIPT_BLOCK.matcher(artifactHtml);
        while (blocks.find()) {
            scopes.add(blocks.group(1));
        }
        if (scopes.isEmpty()) {
            scopes.add(artifactHtml);
        }
        return scopes;
    }

    private Map<String, String> declaredLiterals(String artifactHtml) {
        Map<String, String> declared = new HashMap<>();
        Matcher declarations = DECLARATION.matcher(artifactHtml);
        while (declarations.find()) {
            declared.put(declarations.group(1), unwrapJsLiteral(declarations.group(2)));
        }
        return declared;
    }

    /** A literal argument is used directly; an identifier is looked up among the declarations. */
    private String resolveArgument(String argument, Map<String, String> declared) {
        if (isLiteral(argument)) {
            return unwrapJsLiteral(argument);
        }
        return declared.get(argument);
    }

    private boolean isLiteral(String argument) {
        if (argument == null || argument.length() < 2) {
            return false;
        }
        char first = argument.charAt(0);
        return first == '`' || first == '"' || first == '\'';
    }

    private int totalQueryCalls(String artifactHtml) {
        return (int) ANY_QUERY_CALL.matcher(artifactHtml).results().count();
    }

    private int resolvedQueryCalls(String artifactHtml) {
        int resolved = 0;
        for (String scope : scopes(artifactHtml)) {
            Map<String, String> declared = declaredLiterals(scope);
            Matcher calls = QUERY_CALL.matcher(scope);
            while (calls.find()) {
                if (resolveArgument(calls.group(1), declared) != null) {
                    resolved++;
                }
            }
        }
        return resolved;
    }

    /**
     * Strips the surrounding quotes from a JS string literal and collapses interpolations.
     *
     * <p>An interpolation becomes {@code ?} so that {@code >= '${esc(from)}'} yields the same
     * shape as the runtime statement {@code >= '2026-01-01'}: the quotes around it are already
     * in the artifact, and the normalizer turns the quoted placeholder into its own {@code ?}.
     */
    private String unwrapJsLiteral(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        return unescape(INTERPOLATION.matcher(body).replaceAll("?"));
    }

    /**
     * Turns escape sequences into the characters they stand for.
     *
     * <p>{@code dashboard_config} stores the broker's JSON envelope, so the artifact arrives
     * with its newlines as a literal backslash-n and its quotes escaped. {@link QueryNormalizer}
     * collapses <em>real</em> whitespace, so without this the published shape keeps
     * {@code customer_count\n from} where the runtime statement has a space, and no query on
     * the dashboard ever matches.
     *
     * <p>Worth recording how this was found: an earlier probe unescaped the database dump by
     * hand before extracting, so the harness was more forgiving than the production path and
     * three rounds of green tests missed it. It surfaced only by calling the real endpoint
     * against the real stored row.
     */
    private String unescape(String text) {
        return text.replace("\\n", "\n")
                   .replace("\\r", "\r")
                   .replace("\\t", "\t")
                   .replace("\\\"", "\"")
                   .replace("\\'", "'")
                   .replace("\\`", "`")
                   .replace("\\\\", "\\");
    }
}
