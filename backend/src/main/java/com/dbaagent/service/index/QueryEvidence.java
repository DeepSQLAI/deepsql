package com.dbaagent.service.index;

import lombok.Builder;
import lombok.Data;
import lombok.Singular;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Role-tagged column references extracted from a single SQL statement.
 *
 * The contract: every column ref has both a table and a column resolved
 * (aliases collapsed back to base table names). Refs we couldn't resolve
 * are dropped — recommendations on unknown tables are noise.
 */
@Data
@Builder
public class QueryEvidence {

    /** SHA-256 fingerprint of the normalised query, used to upsert evidence rows. */
    private final String queryFingerprint;

    /** Original SQL (for the human-readable "why" in the evidence payload). */
    private final String sampleSql;

    /** Tables referenced anywhere in the statement. Lower-cased. */
    @Singular("table")
    private final Set<String> tables;

    @Singular("whereEquality")
    private final Set<TableColumnRef> whereEquality;

    @Singular("whereRange")
    private final Set<TableColumnRef> whereRange;

    @Singular("whereIn")
    private final Set<TableColumnRef> whereIn;

    @Singular("whereLikePrefix")
    private final Set<TableColumnRef> whereLikePrefix;

    @Singular
    private final List<JoinEdge> joinEdges;

    @Singular("groupBy")
    private final Set<TableColumnRef> groupBy;

    @Singular("orderBy")
    private final Set<TableColumnRef> orderBy;

    @Singular("selectList")
    private final Set<TableColumnRef> selectList;

    /**
     * Predicates that wrap the column in a function — {@code WHERE LOWER(name) = ?}.
     * These need an expression index, not a plain B-tree on the column.
     */
    @Singular("functionWrapped")
    private final Set<TableColumnRef> functionWrapped;

    /**
     * Set to true when the parser failed and only the table list was extracted
     * via the regex fallback. Used to suppress concrete column-level candidates
     * from this query while still letting the schema walk see the touched tables.
     */
    @Builder.Default
    private final boolean parseFallback = false;

    /**
     * All column refs in any predicate role (excludes SELECT_LIST). Useful for
     * "does this query touch column X" lookups.
     */
    public Set<TableColumnRef> allPredicateRefs() {
        Set<TableColumnRef> all = new LinkedHashSet<>();
        Stream.of(whereEquality, whereRange, whereIn, whereLikePrefix, groupBy, orderBy, functionWrapped)
            .filter(s -> s != null)
            .forEach(all::addAll);
        if (joinEdges != null) {
            for (JoinEdge edge : joinEdges) {
                if (edge.getLeft() != null && edge.getLeft().isResolved()) all.add(edge.getLeft());
                if (edge.getRight() != null && edge.getRight().isResolved()) all.add(edge.getRight());
            }
        }
        return all;
    }

    /** Column refs for a specific table, role-tagged. */
    public Set<TableColumnRef> refsForTable(String table) {
        if (table == null) return Set.of();
        String tk = table.toLowerCase(java.util.Locale.ROOT);
        return allPredicateRefs().stream()
            .filter(r -> r.getTable() != null && r.getTable().equals(tk))
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }
}
