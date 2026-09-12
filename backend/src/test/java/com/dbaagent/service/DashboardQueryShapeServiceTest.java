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

    // ── real artifacts assign the SQL to a variable first ────────────────────

    /**
     * Every {@code deepsql.query} call site in this database's real dashboards passes a
     * <em>variable</em>, never a literal — 18 of 18 when this was checked, across four
     * different names ({@code query}, {@code sql}, {@code trendQuery}, {@code totalQuery}).
     * The first version of this service matched only a literal argument, so it extracted
     * nothing from a real artifact and, failing closed on an empty set, refused every query
     * on every existing public dashboard. This fixture is copied from a stored
     * {@code dashboard_config} rather than written by hand, which is what the original tests
     * got wrong: they encoded the author's assumption about the agent's output instead of its
     * actual output.
     */
    private static final String REAL_ARTIFACT = """
        <script>
          async function loadSales(){
            const { from, to } = window.__dateRange;
            const sql = `
              SELECT
                COALESCE(SUM(public.orders.total_amount), 0) AS total_sales,
                COUNT(*) AS order_count
              FROM public.orders
              WHERE public.orders.created_at >= '${esc(from)}'
                AND public.orders.created_at < ('${esc(to)}'::date + INTERVAL '1 day')
            `;
            const { rows } = await deepsql.query(sql);
          }
        </script>
        """;

    @Test
    void extractsSqlAssignedToAVariableBeforeTheCall() {
        Set<String> shapes = shapesOf(REAL_ARTIFACT);

        assertThat(shapes).hasSize(1);
        assertThat(allows(shapes,
            "SELECT COALESCE(SUM(public.orders.total_amount), 0) AS total_sales, COUNT(*) AS order_count "
                + "FROM public.orders WHERE public.orders.created_at >= '2026-01-01' "
                + "AND public.orders.created_at < ('2026-03-01'::date + INTERVAL '1 day')"))
            .isTrue();
    }

    @Test
    void resolvesEveryVariableNameTheAgentUses() {
        Set<String> shapes = shapesOf("""
            <script>
              const query = `SELECT a FROM public.t1`;
              const r1 = await deepsql.query(query);
              const trendQuery = `SELECT b FROM public.t2`;
              const r2 = await deepsql.query(trendQuery);
              const totalQuery = `SELECT c FROM public.t3`;
              const r3 = await deepsql.query(totalQuery);
            </script>
            """);

        assertThat(shapes).hasSize(3);
        assertThat(allows(shapes, "SELECT a FROM public.t1")).isTrue();
        assertThat(allows(shapes, "SELECT b FROM public.t2")).isTrue();
        assertThat(allows(shapes, "SELECT c FROM public.t3")).isTrue();
    }

    @Test
    void stillRefusesExfiltrationFromAVariableBackedArtifact() {
        Set<String> shapes = shapesOf(REAL_ARTIFACT);

        assertThat(allows(shapes, "SELECT * FROM public.customers")).isFalse();
        assertThat(allows(shapes, "SELECT * FROM users")).isFalse();
    }

    /**
     * An argument that cannot be resolved to a literal must be reported, not skipped. Skipping
     * it would publish a shape set missing one of the dashboard's own queries, which then fails
     * closed at runtime — a widget broken for the audience only, with nothing to indicate why.
     */
    @Test
    void reportsAnArgumentItCannotResolveRatherThanSkippingIt() {
        String unresolvable = """
            <script>
              const parts = buildSql();
              const r = await deepsql.query(parts.join(' '));
            </script>
            """;

        assertThat(service.hasUnresolvableQuery(unresolvable)).isTrue();
        assertThat(service.hasUnresolvableQuery(REAL_ARTIFACT)).isFalse();
    }

    @Test
    void anArtifactWithNoQueriesAtAllHasNothingUnresolvable() {
        assertThat(service.hasUnresolvableQuery("<p>static</p>")).isFalse();
    }

    /**
     * Widgets are separate {@code <script>} blocks and overwhelmingly reuse the same variable
     * name: one real dashboard here has nine script blocks, eight of them declaring their own
     * {@code const sql = ...} with different SQL. Resolving declarations into one flat map
     * across the document makes those eight collide on the name and keeps only the last, so
     * seven widgets lose their shape and are refused at runtime — the same silent, partial
     * failure as extracting nothing, just harder to notice. Each block is its own scope.
     */
    @Test
    void resolvesDeclarationsPerScriptBlockSoWidgetsReusingTheSameNameDoNotCollide() {
        Set<String> shapes = shapesOf("""
            <script>
              const sql = `SELECT COUNT(*) FROM public.orders`;
              const a = await deepsql.query(sql);
            </script>
            <script>
              const sql = `SELECT COUNT(*) FROM public.customers`;
              const b = await deepsql.query(sql);
            </script>
            <script>
              const sql = `SELECT SUM(amount) FROM public.payments`;
              const c = await deepsql.query(sql);
            </script>
            """);

        assertThat(shapes).hasSize(3);
        assertThat(allows(shapes, "SELECT COUNT(*) FROM public.orders")).isTrue();
        assertThat(allows(shapes, "SELECT COUNT(*) FROM public.customers")).isTrue();
        assertThat(allows(shapes, "SELECT SUM(amount) FROM public.payments")).isTrue();
    }

    /**
     * {@code saved_dashboards.dashboard_config} does not hold raw HTML. It holds the broker's
     * envelope — {@code {"version":3,"renderMode":"artifact","title":...,"html":"<!doctype ..."}}
     * — with the document JSON-escaped inside the {@code html} field. Handed the envelope
     * verbatim, the patterns here see {@code \n} as a literal backslash-n and every quote
     * escaped, match nothing, and refuse every query on the dashboard.
     *
     * <p>This was missed by three rounds of green tests because the probe that checked real
     * artifacts unescaped the dump by hand first, so the harness was more forgiving than the
     * production path. Caught only by calling the real endpoint against the real row.
     */
    @Test
    void extractsFromTheStoredEnvelopeNotJustRawHtml() {
        String stored = """
            {"version":3,"renderMode":"artifact","title":"Sales overview",            "html":"<!doctype html>\n<script>\n  const sql = `SELECT COUNT(*) FROM public.orders`;\n              const r = await deepsql.query(sql);\n</script>"}
            """;

        Set<String> shapes = shapesOf(stored);

        assertThat(shapes).hasSize(1);
        assertThat(allows(shapes, "SELECT COUNT(*) FROM public.orders")).isTrue();
        assertThat(allows(shapes, "SELECT * FROM public.customers")).isFalse();
    }

    @Test
    void stillHandlesARawHtmlArtifactWithNoEnvelope() {
        assertThat(shapesOf("<script>const sql = `SELECT a FROM public.t`; deepsql.query(sql);</script>"))
            .hasSize(1);
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
