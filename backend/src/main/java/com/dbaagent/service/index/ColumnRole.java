package com.dbaagent.service.index;

/**
 * How a column is used in a query. Drives index-shape choices: equality
 * predicates go first in composite indexes, range predicates last, ORDER BY
 * columns become a suffix, JOINs need FK-side coverage.
 *
 * Persisted as the {@code role} column on {@code index_recommendation_evidence}
 * (V96) so the API can show *why* a recommendation exists.
 */
public enum ColumnRole {
    /** {@code WHERE col = ?} or {@code WHERE col IS NULL} — best at the front of a composite. */
    WHERE_EQ,
    /** {@code WHERE col > ?}, {@code WHERE col BETWEEN ? AND ?} — one range column wins; goes last. */
    WHERE_RANGE,
    /** {@code WHERE col IN (?, ?, ?)} — equality-ish, treated like {@link #WHERE_EQ}. */
    WHERE_IN,
    /** {@code WHERE col LIKE 'prefix%'} — leading-wildcard patterns are never indexable; we only emit when the literal starts with a non-wildcard. */
    WHERE_LIKE_PREFIX,
    /** {@code JOIN ... ON l.a = r.b} — both sides may benefit from FK-side coverage. */
    JOIN,
    /** {@code GROUP BY col} — suffix candidate after WHERE equality columns. */
    GROUP_BY,
    /** {@code ORDER BY col} — suffix candidate after WHERE equality columns. */
    ORDER_BY,
    /** Column appears in SELECT list — only relevant for INCLUDE / covering hints. */
    SELECT_LIST,
    /** Predicate wraps the column in a function ({@code WHERE LOWER(name) = ?}) — needs an expression index, not a plain one. */
    FUNCTION_WRAPPED
}
