package com.dbaagent.service.index;

import java.util.Objects;

/**
 * A directed JOIN predicate {@code left.column = right.column}.
 *
 * Direction is the order JSQLParser saw the expression in; for ranking
 * purposes we treat both endpoints as index-worthy. Equi-joins are by far
 * the most common pattern and the only one a B-tree index helps with;
 * theta-joins (range / inequality joins) are flagged but downweighted —
 * indexing rarely fixes them.
 */
public final class JoinEdge {

    public enum Kind {
        /** {@code a.x = b.y} — equality predicate, indexable. */
        EQUI,
        /** {@code a.x < b.y} or similar — theta-join, rarely indexable. */
        THETA
    }

    private final TableColumnRef left;
    private final TableColumnRef right;
    private final Kind kind;

    public JoinEdge(TableColumnRef left, TableColumnRef right, Kind kind) {
        this.left = left;
        this.right = right;
        this.kind = kind == null ? Kind.EQUI : kind;
    }

    public TableColumnRef getLeft() {
        return left;
    }

    public TableColumnRef getRight() {
        return right;
    }

    public Kind getKind() {
        return kind;
    }

    /**
     * True when both sides resolved to a (table, column) pair. Unresolved
     * edges happen on aliased subselects or odd ON clauses; we keep them so
     * the planner can still see "there's a JOIN here" but skip them when
     * emitting concrete candidates.
     */
    public boolean isFullyResolved() {
        return left != null && left.isResolved() && right != null && right.isResolved();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof JoinEdge)) return false;
        JoinEdge other = (JoinEdge) o;
        return kind == other.kind &&
            Objects.equals(left, other.left) &&
            Objects.equals(right, other.right);
    }

    @Override
    public int hashCode() {
        return Objects.hash(left, right, kind);
    }

    @Override
    public String toString() {
        return left + " " + kind + " " + right;
    }
}
