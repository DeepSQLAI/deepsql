package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The security contract for public dashboard sharing.
 *
 * <p>{@code POST /api/public/dashboards/{token}/query} took the SQL to run as a body field and
 * never compared it against the dashboard being shared — only {@code validateReadOnlySql},
 * which asks whether a statement reads, not whether this dashboard was ever meant to run it.
 * A link published to show one chart therefore granted anonymous read of every table on the
 * connection.
 *
 * <p>An exact string match cannot be the fix: the agent writes interactive dashboards whose
 * SQL is interpolated at runtime (a date picker changes the string on every use), so exact
 * matching would break public links while the author's own view kept working. These tests pin
 * the shape-matching behaviour that closes the hole while keeping interactivity: literals vary
 * freely, structure does not.
 */
class DashboardQueryShapeServiceTest {

    private final DashboardQueryShapeService service = new DashboardQueryShapeService();

    private static final String PUBLISHED =
        "SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '2026-01-01' AND '2026-03-01'";

    private Set<String> shapesOf(String artifactHtml) {
        return service.extractShapes(artifactHtml);
    }

    private boolean allows(Set<String> shapes, String sql) {
        return service.matches(shapes, sql);
    }

    // ── extraction ────────────────────────────────────────────────────────────

    @Test
    void extractsQueriesFromTemplateLiteralsWithInterpolation() {
        Set<String> shapes = shapesOf("""
            <script>
              const { from, to } = window.__dateRange;
              const r = await deepsql.query(
                `SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '${from}' AND '${to}'`);
            </script>
            """);

        assertThat(shapes).hasSize(1);
        assertThat(allows(shapes, PUBLISHED)).isTrue();
    }

    @Test
    void extractsQueriesFromEveryQuotingStyleTheAgentEmits() {
        Set<String> shapes = shapesOf("""
            <script>
              await deepsql.query(`SELECT a FROM public.t1`);
              await deepsql.query("SELECT b FROM public.t2");
              await deepsql.query('SELECT c FROM public.t3');
            </script>
            """);

        assertThat(shapes).hasSize(3);
        assertThat(allows(shapes, "SELECT a FROM public.t1")).isTrue();
        assertThat(allows(shapes, "SELECT b FROM public.t2")).isTrue();
        assertThat(allows(shapes, "SELECT c FROM public.t3")).isTrue();
    }

    @Test
    void artifactWithNoQueriesYieldsNoShapes() {
        assertThat(shapesOf("<p>a static dashboard</p>")).isEmpty();
    }

    // ── interactivity must survive ────────────────────────────────────────────

    @Test
    void allowsTheSameQueryWithADifferentDateRange() {
        Set<String> shapes = Set.of(service.shapeOf(PUBLISHED));

        assertThat(allows(shapes,
            "SELECT COUNT(*) FROM public.properties p WHERE p.created_at BETWEEN '2025-06-01' AND '2025-09-30'"))
            .isTrue();
    }

    @Test
    void allowsWhitespaceAndCaseVariationsOfThePublishedQuery() {
        Set<String> shapes = Set.of(service.shapeOf(PUBLISHED));

        assertThat(allows(shapes,
            "select  count(*)\n  FROM public.PROPERTIES p\tWHERE p.created_at between '2026-01-01' and '2026-03-01'"))
            .isTrue();
    }

    // ── exfiltration must be refused ──────────────────────────────────────────

    @Test
    void refusesAnUnrelatedTableScan() {
        assertThat(allows(Set.of(service.shapeOf(PUBLISHED)), "SELECT * FROM users")).isFalse();
    }

    @Test
    void refusesTheSameShapeAgainstADifferentTable() {
        assertThat(allows(Set.of(service.shapeOf(PUBLISHED)),
            "SELECT COUNT(*) FROM public.users p WHERE p.created_at BETWEEN '2026-01-01' AND '2026-03-01'"))
            .isFalse();
    }

    @Test
    void refusesTheSameShapeSelectingADifferentColumn() {
        assertThat(allows(Set.of(service.shapeOf(PUBLISHED)),
            "SELECT p.password FROM public.properties p WHERE p.created_at BETWEEN '2026-01-01' AND '2026-03-01'"))
            .isFalse();
    }

    @Test
    void refusesAnAppendedPredicate() {
        assertThat(allows(Set.of(service.shapeOf(PUBLISHED)),
            PUBLISHED + " OR 1=1"))
            .isFalse();
    }

    /**
     * The normalizer's {@code '[^']*'} rule does not model SQL's {@code ''} escape, so a payload
     * smuggling structure inside a literal splits into a different number of placeholders than
     * the published query has. The shape changes and the query is refused — the imprecision
     * fails in the safe direction, which is the property worth pinning.
     */
    @Test
    void refusesStructureSmuggledInsideAStringLiteral() {
        assertThat(allows(Set.of(service.shapeOf(PUBLISHED)),
            "SELECT COUNT(*) FROM public.properties p WHERE p.created_at "
                + "BETWEEN '2026-01-01' AND '2026-03-01 '' UNION SELECT password FROM users --'"))
            .isFalse();
    }

    // ── fail closed ───────────────────────────────────────────────────────────

    @Test
    void refusesEveryQueryWhenNoShapesWereExtracted() {
        assertThat(allows(Set.of(), PUBLISHED)).isFalse();
    }

    @Test
    void refusesBlankAndNullSql() {
        Set<String> shapes = Set.of(service.shapeOf(PUBLISHED));

        assertThat(allows(shapes, null)).isFalse();
        assertThat(allows(shapes, "   ")).isFalse();
    }

    /**
     * Publish-time extraction and query-time normalization must agree, or every public
     * dashboard breaks. This is the seam between the two halves of the feature, so it is
     * asserted directly rather than only implied by the tests above.
     */
    @Test
    void extractedShapeEqualsTheShapeOfTheQueryActuallyIssuedAtRuntime() {
        Set<String> extracted = shapesOf(
            "<script>await deepsql.query(`SELECT COUNT(*) FROM public.properties p "
                + "WHERE p.created_at BETWEEN '${from}' AND '${to}'`);</script>");

        assertThat(extracted).containsExactly(service.shapeOf(PUBLISHED));
    }
}
