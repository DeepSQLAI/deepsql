package com.dbaagent.service;

import com.dbaagent.util.QueryNormalizer;
import org.springframework.stereotype.Service;

import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Binds the queries a public dashboard may run to the ones its artifact actually contains.
 *
 * <p>{@code POST /api/public/dashboards/{token}/query} takes the SQL as a request body field.
 * Checking only that the statement reads is not enough: it answers "is this a select" when the
 * question is "is this a query this dashboard was published to run". Without that second check
 * a link shared to show one chart grants anonymous read of the whole connection.
 *
 * <p>Exact string matching cannot be the answer. Dashboards are interactive by design — a date
 * picker re-queries with new bounds on every change ({@code dashboard-design/SKILL.md}), so the
 * exact string is not knowable at publish time. Matching would then fail only on the public
 * link while the author's own view kept working, which is the worst shape a regression can take.
 *
 * <p>So queries are matched by <em>shape</em>: the statement with its literals replaced by
 * placeholders, via the same {@link QueryNormalizer} that backs
 * {@link QueryFingerprintService}. Two queries differing only in a date range share a shape;
 * two naming different tables or columns do not.
 *
 * <p>This is one layer, not the only one. {@code validateReadOnlySql},
 * {@code connection.setReadOnly(true)}, the row cap and the {@code is_public} re-check all still
 * apply. That matters because {@code QueryNormalizer} was written for analytics grouping, where
 * a collision is a cosmetic nuisance rather than a vulnerability.
 */
@Service
public class DashboardQueryShapeService {

    /**
     * The first argument of a {@code deepsql.query(...)} call, in each quoting style the agent
     * emits — backtick, double and single. Escaped quotes are consumed so a literal containing
     * the delimiter does not end the match early.
     */
    private static final Pattern QUERY_CALL = Pattern.compile(
        "deepsql\\s*\\.\\s*query\\s*\\(\\s*"
            + "(`(?:[^`\\\\]|\\\\.)*`"
            + "|\"(?:[^\"\\\\]|\\\\.)*\""
            + "|'(?:[^'\\\\]|\\\\.)*')",
        Pattern.DOTALL);

    /**
     * A JS template interpolation. Replaced with a quoted placeholder before normalizing, so the
     * interpolated value is treated as the literal it becomes at runtime: {@code '${from}'}
     * already sits inside quotes in the artifact, and a bare {@code ${n}} still has to normalize
     * to the same placeholder the runtime's numeric literal produces.
     */
    private static final Pattern INTERPOLATION = Pattern.compile("\\$\\{[^}]*\\}");

    /** Extracts the shape of every query the artifact can issue. */
    public Set<String> extractShapes(String artifactHtml) {
        Set<String> shapes = new LinkedHashSet<>();
        if (artifactHtml == null || artifactHtml.isBlank()) {
            return shapes;
        }
        Matcher calls = QUERY_CALL.matcher(artifactHtml);
        while (calls.find()) {
            String shape = shapeOf(unwrapJsLiteral(calls.group(1)));
            if (!shape.isBlank()) {
                shapes.add(shape);
            }
        }
        return shapes;
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
     * Strips the surrounding quotes from a JS string literal and collapses interpolations.
     *
     * <p>An interpolation becomes {@code '?'} — a quoted placeholder — so that
     * {@code BETWEEN '${from}' AND '${to}'} yields the same shape as the runtime statement
     * {@code BETWEEN '2026-01-01' AND '2026-03-01'}. The surrounding quotes already present in
     * the artifact are left in place and normalized away with it.
     */
    private String unwrapJsLiteral(String literal) {
        String body = literal.substring(1, literal.length() - 1);
        return INTERPOLATION.matcher(body).replaceAll("?");
    }
}
