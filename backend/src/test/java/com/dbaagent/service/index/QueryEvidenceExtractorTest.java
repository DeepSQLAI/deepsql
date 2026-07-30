package com.dbaagent.service.index;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins down the SQL shapes the legacy regex extractor got wrong. Each case
 * has a comment naming the bug the old code shipped with — JSQLParser fixes
 * all of them.
 */
class QueryEvidenceExtractorTest {

    private final QueryEvidenceExtractor extractor = new QueryEvidenceExtractor();

    private TableColumnRef ref(String table, String col) {
        return new TableColumnRef(table, col);
    }

    @Test
    void extractsQualifiedColumnAsColumnNotTable() {
        // Old bug: regex `where\s+.*?(\w+)\s*[=<>]` against `WHERE u.email = ?`
        // matched "u" first, hit the keyword filter, dropped it. New parser
        // sees the Column AST node directly.
        QueryEvidence ev = extractor.extract("SELECT * FROM users u WHERE u.email = 'x'");
        assertThat(ev.isParseFallback()).isFalse();
        assertThat(ev.getWhereEquality()).contains(ref("users", "email"));
        assertThat(ev.getTables()).contains("users");
    }

    @Test
    void distinguishesEqualityRangeAndInPredicates() {
        QueryEvidence ev = extractor.extract(
            "SELECT * FROM orders WHERE status = 'open' AND created_at > '2025-01-01' AND priority IN (1, 2, 3)"
        );
        assertThat(ev.getWhereEquality()).contains(ref("orders", "status"));
        assertThat(ev.getWhereRange()).contains(ref("orders", "created_at"));
        assertThat(ev.getWhereIn()).contains(ref("orders", "priority"));
    }

    @Test
    void betweenIsClassifiedAsRange() {
        QueryEvidence ev = extractor.extract(
            "SELECT * FROM orders WHERE created_at BETWEEN '2025-01-01' AND '2025-12-31'"
        );
        assertThat(ev.getWhereRange()).contains(ref("orders", "created_at"));
    }

    @Test
    void isNullIsClassifiedAsEquality() {
        QueryEvidence ev = extractor.extract("SELECT * FROM users WHERE deleted_at IS NULL");
        assertThat(ev.getWhereEquality()).contains(ref("users", "deleted_at"));
    }

    @Test
    void notEqualsToIsClassifiedAsRange() {
        // Old regex `[=<>]` missed `!=` entirely.
        QueryEvidence ev = extractor.extract("SELECT * FROM orders WHERE status != 'closed'");
        assertThat(ev.getWhereRange()).contains(ref("orders", "status"));
    }

    @Test
    void leadingWildcardLikeIsIgnored() {
        // `WHERE name LIKE '%foo%'` cannot use a B-tree — no candidate emitted.
        QueryEvidence ev = extractor.extract("SELECT * FROM users WHERE name LIKE '%alice%'");
        assertThat(ev.getWhereLikePrefix()).isEmpty();
    }

    @Test
    void prefixLikeIsClassifiedAsLikePrefix() {
        QueryEvidence ev = extractor.extract("SELECT * FROM users WHERE name LIKE 'alice%'");
        assertThat(ev.getWhereLikePrefix()).contains(ref("users", "name"));
    }

    @Test
    void functionWrappedPredicateIsTaggedAsFunctionWrapped() {
        // Old bug: regex returned "LOWER" as a column name. Plain B-tree on
        // `name` doesn't help `WHERE LOWER(name) = ?` — needs an expression
        // index. New extractor tags it FUNCTION_WRAPPED, not WHERE_EQ.
        QueryEvidence ev = extractor.extract(
            "SELECT * FROM users WHERE LOWER(name) = 'alice'"
        );
        assertThat(ev.getFunctionWrapped()).contains(ref("users", "name"));
        assertThat(ev.getWhereEquality()).doesNotContain(ref("users", "name"));
    }

    @Test
    void explicitJoinOnEmitsJoinEdge() {
        QueryEvidence ev = extractor.extract(
            "SELECT * FROM orders o JOIN customers c ON o.customer_id = c.id WHERE c.region = 'EU'"
        );
        assertThat(ev.getJoinEdges())
            .extracting(JoinEdge::isFullyResolved)
            .contains(true);
        assertThat(ev.getJoinEdges())
            .anyMatch(j -> ref("orders", "customer_id").equals(j.getLeft())
                && ref("customers", "id").equals(j.getRight()));
        assertThat(ev.getWhereEquality()).contains(ref("customers", "region"));
    }

    @Test
    void joinUsingEmitsBothSidesAsEquality() {
        QueryEvidence ev = extractor.extract(
            "SELECT * FROM orders o JOIN order_items oi USING (order_id)"
        );
        // USING(order_id) ⇒ both o.order_id and oi.order_id are equi-join
        // candidates. Old regex skipped USING entirely.
        assertThat(ev.getWhereEquality())
            .anyMatch(r -> "order_id".equals(r.getColumn()));
    }

    @Test
    void implicitCommaJoinIsRecognised() {
        // Old regex required the word "JOIN" — comma-joins were invisible.
        QueryEvidence ev = extractor.extract(
            "SELECT * FROM orders o, customers c WHERE o.customer_id = c.id"
        );
        // The implicit JOIN materializes as a JOIN edge (different tables on
        // both sides of an equality) AND as WHERE_EQ refs.
        assertThat(ev.getJoinEdges()).isNotEmpty();
    }

    @Test
    void groupByAndOrderByAreCollected() {
        QueryEvidence ev = extractor.extract(
            "SELECT region, COUNT(*) FROM orders GROUP BY region ORDER BY region"
        );
        assertThat(ev.getGroupBy()).contains(ref("orders", "region"));
        assertThat(ev.getOrderBy()).contains(ref("orders", "region"));
    }

    @Test
    void cteSubqueryColumnsAreCollected() {
        // CTEs were completely opaque to the old regex.
        QueryEvidence ev = extractor.extract(
            "WITH active AS (SELECT * FROM users WHERE status = 'active') " +
            "SELECT * FROM active"
        );
        assertThat(ev.getTables()).contains("users");
        // 'active' is a CTE alias — should NOT appear as a base table.
        assertThat(ev.getTables()).doesNotContain("active");
        assertThat(ev.getWhereEquality()).contains(ref("users", "status"));
    }

    @Test
    void unparseableSqlFallsBackButFlagsParseFallback() {
        QueryEvidence ev = extractor.extract(":::: garbage SQL :::: but mentions FROM orders");
        assertThat(ev.isParseFallback()).isTrue();
        assertThat(ev.getWhereEquality()).isEmpty();
        // The regex fallback still recovers the table name.
        assertThat(ev.getTables()).contains("orders");
    }

    @Test
    void emptySqlFallsBackGracefully() {
        QueryEvidence ev = extractor.extract("");
        assertThat(ev.isParseFallback()).isTrue();
        assertThat(ev.getTables()).isEmpty();
    }

    @Test
    void updateStatementWhereColumnsAreExtracted() {
        // Most real-world slow queries are UPDATEs (per-row maintenance).
        // The old code dropped them entirely; the new path treats UPDATE WHERE
        // exactly like SELECT WHERE for index purposes.
        QueryEvidence ev = extractor.extract(
            "UPDATE orders SET status = 'shipped', updated_at = NOW() WHERE id = 42 AND customer_id = 7"
        );
        assertThat(ev.isParseFallback()).isFalse();
        assertThat(ev.getTables()).contains("orders");
        assertThat(ev.getWhereEquality()).contains(ref("orders", "id"));
        assertThat(ev.getWhereEquality()).contains(ref("orders", "customer_id"));
    }

    @Test
    void deleteStatementWhereColumnsAreExtracted() {
        QueryEvidence ev = extractor.extract(
            "DELETE FROM users WHERE deleted_at IS NOT NULL AND last_login < '2024-01-01'"
        );
        assertThat(ev.isParseFallback()).isFalse();
        assertThat(ev.getTables()).contains("users");
        assertThat(ev.getWhereRange()).contains(ref("users", "last_login"));
    }

    @Test
    void fingerprintIsStableAcrossLiteralChanges() {
        String a = extractor.extract("SELECT * FROM orders WHERE id = 42").getQueryFingerprint();
        String b = extractor.extract("SELECT * FROM orders WHERE id = 99999").getQueryFingerprint();
        // Literals are normalised; the fingerprint stays the same.
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSize(16);
    }
}
