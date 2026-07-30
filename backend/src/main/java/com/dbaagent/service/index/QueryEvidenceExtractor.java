package com.dbaagent.service.index;

import lombok.extern.slf4j.Slf4j;
import net.sf.jsqlparser.expression.Alias;
import net.sf.jsqlparser.expression.Expression;
import net.sf.jsqlparser.expression.ExpressionVisitorAdapter;
import net.sf.jsqlparser.expression.Function;
import net.sf.jsqlparser.expression.StringValue;
import net.sf.jsqlparser.expression.operators.relational.Between;
import net.sf.jsqlparser.expression.operators.relational.EqualsTo;
import net.sf.jsqlparser.expression.operators.relational.GreaterThan;
import net.sf.jsqlparser.expression.operators.relational.GreaterThanEquals;
import net.sf.jsqlparser.expression.operators.relational.InExpression;
import net.sf.jsqlparser.expression.operators.relational.IsNullExpression;
import net.sf.jsqlparser.expression.operators.relational.LikeExpression;
import net.sf.jsqlparser.expression.operators.relational.MinorThan;
import net.sf.jsqlparser.expression.operators.relational.MinorThanEquals;
import net.sf.jsqlparser.expression.operators.relational.NotEqualsTo;
import net.sf.jsqlparser.parser.CCJSqlParserUtil;
import net.sf.jsqlparser.schema.Column;
import net.sf.jsqlparser.schema.Table;
import net.sf.jsqlparser.statement.Statement;
import net.sf.jsqlparser.statement.delete.Delete;
import net.sf.jsqlparser.statement.update.Update;
import net.sf.jsqlparser.statement.select.FromItem;
import net.sf.jsqlparser.statement.select.Join;
import net.sf.jsqlparser.statement.select.OrderByElement;
import net.sf.jsqlparser.statement.select.ParenthesedSelect;
import net.sf.jsqlparser.statement.select.PlainSelect;
import net.sf.jsqlparser.statement.select.Select;
import net.sf.jsqlparser.statement.select.SelectItem;
import net.sf.jsqlparser.statement.select.SetOperationList;
import net.sf.jsqlparser.statement.select.WithItem;
import org.springframework.stereotype.Component;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Walks a parsed SQL statement and emits {@link QueryEvidence} — role-tagged
 * column references the index advisor can score and rank.
 *
 * Replaces the legacy regex extractors in {@code IndexRecommendationService}.
 * The regex layer (a) only caught {@code =}, {@code <}, {@code >} predicates,
 * (b) confused qualified names like {@code t.col} with columns, (c) returned
 * a function name ({@code LOWER}) as a column when the predicate wrapped one,
 * and (d) handled exactly zero subqueries, CTEs, or UNIONs. JSQLParser solves
 * all of that; on the failure path we fall back to extracting the table list
 * only and {@code parseFallback=true} so the planner can suppress concrete
 * column candidates from this query.
 */
@Component
@Slf4j
public class QueryEvidenceExtractor {

    /** Last-resort regex used only when JSQLParser cannot parse the statement. */
    private static final Pattern FALLBACK_TABLE_PATTERN =
        Pattern.compile("(?:from|join|update|into)\\s+([\\w.\"`\\[\\]]+)", Pattern.CASE_INSENSITIVE);

    /**
     * Parse {@code sql} and emit role-tagged column refs. Always returns a
     * non-null evidence object — on a failed parse the {@code parseFallback}
     * flag is set and the only signal is the table list.
     */
    public QueryEvidence extract(String sql) {
        QueryEvidence.QueryEvidenceBuilder b = QueryEvidence.builder()
            .queryFingerprint(fingerprint(sql))
            .sampleSql(sql);

        if (sql == null || sql.isBlank()) {
            return b.parseFallback(true).build();
        }

        try {
            Statement stmt = CCJSqlParserUtil.parse(sql);
            if (stmt instanceof Select) {
                Walker walker = new Walker(b);
                walker.walkSelect((Select) stmt);
                return b.build();
            }
            // UPDATE and DELETE benefit from WHERE-clause indexes too:
            // {@code UPDATE orders SET ... WHERE id = ?} needs an index on id
            // exactly like a SELECT would. INSERT has no WHERE clause to
            // index, so it's left as parseFallback.
            if (stmt instanceof Update update) {
                Walker walker = new Walker(b);
                walker.walkUpdate(update);
                return b.build();
            }
            if (stmt instanceof Delete delete) {
                Walker walker = new Walker(b);
                walker.walkDelete(delete);
                return b.build();
            }
            return b.parseFallback(true).build();
        } catch (Exception e) {
            log.debug("Falling back to regex table extraction; parser rejected: {}", e.getMessage());
            extractTablesViaFallback(sql, b);
            return b.parseFallback(true).build();
        }
    }

    /**
     * Stable fingerprint used to upsert evidence rows in V96. We hash the
     * normalised SQL (literals collapsed) so the same query pattern from
     * different cycles maps to the same row.
     */
    public static String fingerprint(String sql) {
        if (sql == null) return "";
        String normalized = sql.replaceAll("'[^']*'", "?")
            .replaceAll("\\b\\d+\\b", "?")
            .replaceAll("\\s+", " ")
            .trim()
            .toLowerCase(Locale.ROOT);
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(normalized.getBytes());
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte by : digest) sb.append(String.format("%02x", by));
            return sb.substring(0, 16); // 16-char short hash, plenty for unique-on-(rec_id, fp)
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(normalized.hashCode());
        }
    }

    private void extractTablesViaFallback(String sql, QueryEvidence.QueryEvidenceBuilder b) {
        Matcher m = FALLBACK_TABLE_PATTERN.matcher(sql);
        while (m.find()) {
            String raw = m.group(1);
            String tableName = stripQualifiers(raw);
            if (tableName != null && !tableName.isEmpty() && !isReservedTablePosition(tableName)) {
                b.table(tableName.toLowerCase(Locale.ROOT));
            }
        }
    }

    /** Drop schema qualifier and quoting: {@code "public"."Orders"} → {@code Orders}. */
    private static String stripQualifiers(String raw) {
        if (raw == null) return null;
        String s = raw.trim();
        if (s.isEmpty()) return null;
        int dot = s.lastIndexOf('.');
        if (dot >= 0) s = s.substring(dot + 1);
        if (s.length() >= 2) {
            char first = s.charAt(0);
            char last = s.charAt(s.length() - 1);
            if ((first == '`' && last == '`') ||
                (first == '"' && last == '"') ||
                (first == '[' && last == ']')) {
                s = s.substring(1, s.length() - 1);
            }
        }
        return s;
    }

    private static boolean isReservedTablePosition(String token) {
        // Defensive: skip tokens the regex caught that are clearly not table names.
        String t = token.toLowerCase(Locale.ROOT);
        return t.equals("select") || t.equals("(") || t.equals("lateral");
    }

    // ---------------------------------------------------------------------
    // AST walker: alias-resolved, role-tagged extraction.
    // ---------------------------------------------------------------------

    /**
     * Per-statement walker. JSQLParser visitor adapters don't naturally pass
     * alias context across nested SELECTs, so this class manages the alias
     * map ("u" → "users") manually as the walk descends.
     */
    private class Walker {

        private final QueryEvidence.QueryEvidenceBuilder out;

        /** Alias → real table name. Lower-cased. Built per nesting level. */
        private final Map<String, String> aliasToTable = new HashMap<>();

        /** Real table names seen at any depth. */
        private final Set<String> tables = new LinkedHashSet<>();

        Walker(QueryEvidence.QueryEvidenceBuilder out) {
            this.out = out;
        }

        void walkUpdate(Update update) {
            if (update == null) return;
            if (update.getTable() != null) collectFromItem(update.getTable());
            if (update.getJoins() != null) {
                for (Join j : update.getJoins()) {
                    collectFromItem(j.getFromItem());
                    if (j.getOnExpressions() != null) {
                        for (Expression on : j.getOnExpressions()) {
                            collectJoinPredicate(on);
                            on.accept(new PredicateVisitor(false), null);
                        }
                    }
                }
            }
            if (update.getWhere() != null) {
                update.getWhere().accept(new PredicateVisitor(true), null);
            }
            for (String t : tables) out.table(t);
        }

        void walkDelete(Delete delete) {
            if (delete == null) return;
            if (delete.getTable() != null) collectFromItem(delete.getTable());
            if (delete.getJoins() != null) {
                for (Join j : delete.getJoins()) {
                    collectFromItem(j.getFromItem());
                    if (j.getOnExpressions() != null) {
                        for (Expression on : j.getOnExpressions()) {
                            collectJoinPredicate(on);
                            on.accept(new PredicateVisitor(false), null);
                        }
                    }
                }
            }
            if (delete.getWhere() != null) {
                delete.getWhere().accept(new PredicateVisitor(true), null);
            }
            for (String t : tables) out.table(t);
        }

        void walkSelect(Select select) {
            if (select == null) return;

            if (select instanceof PlainSelect) {
                walkPlain((PlainSelect) select);
            } else if (select instanceof SetOperationList) {
                for (Select s : ((SetOperationList) select).getSelects()) {
                    walkSelect(s);
                }
            } else if (select instanceof ParenthesedSelect) {
                walkSelect(((ParenthesedSelect) select).getSelect());
            }
            // WithItem is not a Select in JSQLParser 5.x — CTEs are handled
            // inside walkPlain via PlainSelect.getWithItemsList().

            // Tables flow into the evidence builder once, lower-cased.
            for (String t : tables) {
                out.table(t);
            }
        }

        private void walkPlain(PlainSelect ps) {
            // CTEs first — their aliases resolve to inner queries.
            if (ps.getWithItemsList() != null) {
                for (WithItem<?> with : ps.getWithItemsList()) {
                    Object body = with.getParenthesedStatement();
                    if (body instanceof Select) {
                        walkSelect((Select) body);
                    }
                    // CTE alias: we don't recommend indexes on CTE-defined names,
                    // but we register them so subsequent column refs that look
                    // qualified against the CTE alias aren't mistaken for base
                    // tables.
                    if (with.getAlias() != null && with.getAlias().getName() != null) {
                        aliasToTable.put(with.getAlias().getName().toLowerCase(Locale.ROOT), null);
                    }
                }
            }

            collectFromItem(ps.getFromItem());
            if (ps.getJoins() != null) {
                for (Join join : ps.getJoins()) {
                    collectFromItem(join.getFromItem());
                }
            }

            // JOIN predicates — ON clauses + USING clauses.
            if (ps.getJoins() != null) {
                for (Join join : ps.getJoins()) {
                    if (join.getOnExpressions() != null) {
                        for (Expression onExpr : join.getOnExpressions()) {
                            collectJoinPredicate(onExpr);
                            // ON predicates can also carry WHERE-like filters
                            // (e.g., LEFT JOIN ... ON a.x = b.y AND b.status = 'X');
                            // run the predicate visitor across the whole expression too.
                            onExpr.accept(new PredicateVisitor(false), null);
                        }
                    }
                    if (join.getUsingColumns() != null) {
                        for (Column c : join.getUsingColumns()) {
                            // USING(col) — join on identically-named columns across
                            // both sides. Emit a synthetic equi-edge for each side.
                            TableColumnRef left  = resolveColumn(c, leftSideOfJoin(ps, join));
                            TableColumnRef right = resolveColumn(c, join.getFromItem());
                            if (left != null) out.whereEquality(left);
                            if (right != null) out.whereEquality(right);
                        }
                    }
                }
            }

            // WHERE clause — the densest signal.
            if (ps.getWhere() != null) {
                ps.getWhere().accept(new PredicateVisitor(true), null);
                // Implicit JOIN: FROM t1, t2 WHERE t1.a = t2.b. We've already
                // collected both tables; the PredicateVisitor will see the
                // equality and emit it as WHERE_EQ on both sides — but if the
                // two refs are on different tables it's actually a JOIN.
                // Promote those during planning rather than here so we don't
                // double-count.
            }

            // GROUP BY
            if (ps.getGroupBy() != null && ps.getGroupBy().getGroupByExpressionList() != null) {
                // getGroupByExpressionList() returns the raw ExpressionList<T>;
                // iterate via getExpressions() to keep the type parameter inferred.
                for (Object item : ps.getGroupBy().getGroupByExpressionList().getExpressions()) {
                    if (item instanceof Expression) {
                        addColumnsFromExpression((Expression) item, ColumnRole.GROUP_BY);
                    }
                }
            }

            // ORDER BY
            if (ps.getOrderByElements() != null) {
                for (OrderByElement obe : ps.getOrderByElements()) {
                    if (obe.getExpression() != null) {
                        addColumnsFromExpression(obe.getExpression(), ColumnRole.ORDER_BY);
                    }
                }
            }

            // SELECT list — for INCLUDE / covering candidates (planner uses this
            // in V2; we just persist now).
            if (ps.getSelectItems() != null) {
                for (SelectItem<?> si : ps.getSelectItems()) {
                    Expression expr = si.getExpression();
                    if (expr != null) {
                        addColumnsFromExpression(expr, ColumnRole.SELECT_LIST);
                    }
                }
            }
        }

        /** Returns the left side of a JOIN — the FromItem immediately preceding {@code join}. */
        private FromItem leftSideOfJoin(PlainSelect ps, Join join) {
            // JSQLParser's Join.fromItem is the right side; the left side is the
            // previous join's right (or the PlainSelect's FROM if first).
            FromItem prev = ps.getFromItem();
            if (ps.getJoins() == null) return prev;
            for (Join j : ps.getJoins()) {
                if (j == join) return prev;
                prev = j.getFromItem();
            }
            return prev;
        }

        private void collectFromItem(FromItem item) {
            if (item == null) return;
            if (item instanceof Table) {
                Table t = (Table) item;
                String name = t.getName();
                if (name != null) {
                    String lower = stripQualifiersLower(name);
                    if (lower == null) return;
                    // If this name is already a registered CTE alias, it's not
                    // a base table — skip adding to `tables`. The CTE body has
                    // already contributed its real tables.
                    boolean isCteAlias = aliasToTable.containsKey(lower)
                        && aliasToTable.get(lower) == null;
                    if (!isCteAlias) {
                        tables.add(lower);
                    }
                    Alias alias = t.getAlias();
                    if (alias != null && alias.getName() != null) {
                        aliasToTable.put(alias.getName().toLowerCase(Locale.ROOT), isCteAlias ? null : lower);
                    } else if (!isCteAlias) {
                        // Bare table name is its own "alias".
                        aliasToTable.put(lower, lower);
                    }
                }
            } else if (item instanceof ParenthesedSelect) {
                ParenthesedSelect inner = (ParenthesedSelect) item;
                walkSelect(inner.getSelect());
                // Subselect's alias does not point to a base table — register
                // null so column refs against it are gracefully dropped.
                Alias alias = inner.getAlias();
                if (alias != null && alias.getName() != null) {
                    aliasToTable.put(alias.getName().toLowerCase(Locale.ROOT), null);
                }
            }
        }

        private void collectJoinPredicate(Expression onExpr) {
            if (onExpr instanceof EqualsTo) {
                EqualsTo eq = (EqualsTo) onExpr;
                TableColumnRef left = columnRefOf(eq.getLeftExpression());
                TableColumnRef right = columnRefOf(eq.getRightExpression());
                if (left != null && right != null
                    && left.isResolved() && right.isResolved()
                    && !left.equals(right)) {
                    out.joinEdge(new JoinEdge(left, right, JoinEdge.Kind.EQUI));
                }
            } else if (onExpr instanceof MinorThan || onExpr instanceof MinorThanEquals
                || onExpr instanceof GreaterThan || onExpr instanceof GreaterThanEquals
                || onExpr instanceof NotEqualsTo) {
                // Theta JOIN — rarely indexable but recorded.
                Expression left  = leftOf(onExpr);
                Expression right = rightOf(onExpr);
                TableColumnRef lref = columnRefOf(left);
                TableColumnRef rref = columnRefOf(right);
                if (lref != null && rref != null && lref.isResolved() && rref.isResolved()
                    && !lref.equals(rref)) {
                    out.joinEdge(new JoinEdge(lref, rref, JoinEdge.Kind.THETA));
                }
            }
            // Other AND/OR shapes get descended via the predicate visitor that
            // walkPlain runs over the same expression.
        }

        /**
         * Walk an arbitrary expression and emit every Column it touches under
         * a specific role. Used for GROUP BY / ORDER BY / SELECT-list where
         * the role is uniform across the subtree.
         */
        private void addColumnsFromExpression(Expression expr, ColumnRole role) {
            if (expr == null) return;
            expr.accept(new ExpressionVisitorAdapter<Void>() {
                @Override
                public <S> Void visit(Column column, S context) {
                    emit(columnRefOf(column), role);
                    return null;
                }
            }, null);
        }

        private void emit(TableColumnRef ref, ColumnRole role) {
            if (ref == null || !ref.isResolved()) return;
            switch (role) {
                case WHERE_EQ:        out.whereEquality(ref); break;
                case WHERE_RANGE:     out.whereRange(ref); break;
                case WHERE_IN:        out.whereIn(ref); break;
                case WHERE_LIKE_PREFIX: out.whereLikePrefix(ref); break;
                case GROUP_BY:        out.groupBy(ref); break;
                case ORDER_BY:        out.orderBy(ref); break;
                case SELECT_LIST:     out.selectList(ref); break;
                case FUNCTION_WRAPPED: out.functionWrapped(ref); break;
                case JOIN:            /* edges only — handled separately */ break;
            }
        }

        private TableColumnRef columnRefOf(Expression e) {
            if (e instanceof Column) {
                return resolveColumn((Column) e, null);
            }
            return null;
        }

        /**
         * Resolve {@code Column} to a base-table ref, collapsing aliases and
         * defaulting to the only-known table when the column is unqualified
         * in a single-table query.
         */
        private TableColumnRef resolveColumn(Column col, FromItem hintedSide) {
            if (col == null || col.getColumnName() == null) return null;
            String colName = col.getColumnName();

            Table table = col.getTable();
            String qualifier = null;
            if (table != null && table.getName() != null) {
                qualifier = table.getName().toLowerCase(Locale.ROOT);
            }

            String baseTable;
            if (qualifier != null) {
                baseTable = aliasToTable.get(qualifier);
                if (baseTable == null) {
                    // Unknown alias — could be a CTE or subselect alias. Drop
                    // the ref rather than fabricate a candidate against a
                    // non-existent base table.
                    return null;
                }
            } else if (hintedSide instanceof Table) {
                baseTable = stripQualifiersLower(((Table) hintedSide).getName());
            } else if (tables.size() == 1) {
                baseTable = tables.iterator().next();
            } else {
                // Unqualified column in a multi-table query — ambiguous.
                return null;
            }

            return new TableColumnRef(baseTable, colName);
        }

        private static String stripQualifiersLower(String raw) {
            String s = stripQualifiers(raw);
            return s == null ? null : s.toLowerCase(Locale.ROOT);
        }

        /** Visits WHERE / ON predicates, classifying each leaf by role. */
        private class PredicateVisitor extends ExpressionVisitorAdapter<Void> {
            private final boolean fromWhere;

            PredicateVisitor(boolean fromWhere) {
                this.fromWhere = fromWhere;
            }

            @Override
            public <S> Void visit(EqualsTo e, S c) {
                handleBinary(e.getLeftExpression(), e.getRightExpression(), ColumnRole.WHERE_EQ);
                return null;
            }

            @Override
            public <S> Void visit(NotEqualsTo e, S c) {
                handleBinary(e.getLeftExpression(), e.getRightExpression(), ColumnRole.WHERE_RANGE);
                return null;
            }

            @Override
            public <S> Void visit(GreaterThan e, S c) {
                handleBinary(e.getLeftExpression(), e.getRightExpression(), ColumnRole.WHERE_RANGE);
                return null;
            }

            @Override
            public <S> Void visit(GreaterThanEquals e, S c) {
                handleBinary(e.getLeftExpression(), e.getRightExpression(), ColumnRole.WHERE_RANGE);
                return null;
            }

            @Override
            public <S> Void visit(MinorThan e, S c) {
                handleBinary(e.getLeftExpression(), e.getRightExpression(), ColumnRole.WHERE_RANGE);
                return null;
            }

            @Override
            public <S> Void visit(MinorThanEquals e, S c) {
                handleBinary(e.getLeftExpression(), e.getRightExpression(), ColumnRole.WHERE_RANGE);
                return null;
            }

            @Override
            public <S> Void visit(Between b, S c) {
                TableColumnRef ref = columnRefOf(b.getLeftExpression());
                if (ref != null && ref.isResolved()) emit(ref, ColumnRole.WHERE_RANGE);
                return null;
            }

            @Override
            public <S> Void visit(InExpression in, S c) {
                TableColumnRef ref = columnRefOf(in.getLeftExpression());
                if (ref != null && ref.isResolved()) emit(ref, ColumnRole.WHERE_IN);
                return null;
            }

            @Override
            public <S> Void visit(IsNullExpression isNull, S c) {
                TableColumnRef ref = columnRefOf(isNull.getLeftExpression());
                if (ref != null && ref.isResolved()) emit(ref, ColumnRole.WHERE_EQ);
                return null;
            }

            @Override
            public <S> Void visit(LikeExpression like, S c) {
                TableColumnRef ref = columnRefOf(like.getLeftExpression());
                if (ref == null || !ref.isResolved()) return null;
                Expression rhs = like.getRightExpression();
                if (rhs instanceof StringValue) {
                    String value = ((StringValue) rhs).getValue();
                    if (value != null && !value.isEmpty()
                        && value.charAt(0) != '%' && value.charAt(0) != '_') {
                        emit(ref, ColumnRole.WHERE_LIKE_PREFIX);
                        return null;
                    }
                }
                // Leading wildcard — no index helps; ignore.
                return null;
            }

            @Override
            public <S> Void visit(Function fn, S c) {
                // A column wrapped in a function ({@code WHERE LOWER(name) = ?})
                // needs an expression index, not a plain one. Tag every column
                // inside the function as FUNCTION_WRAPPED instead of WHERE_*.
                if (fn.getParameters() != null) {
                    for (Expression p : fn.getParameters()) {
                        if (p instanceof Column) {
                            TableColumnRef ref = columnRefOf(p);
                            if (ref != null && ref.isResolved()) {
                                emit(ref, ColumnRole.FUNCTION_WRAPPED);
                            }
                        } else if (p != null) {
                            p.accept(this, c);
                        }
                    }
                }
                return null;
            }

            @Override
            public <S> Void visit(ParenthesedSelect ps, S c) {
                // Subquery in WHERE: walk it as its own query so its columns
                // get categorised in their own clauses.
                Walker inner = new Walker(out);
                inner.aliasToTable.putAll(Walker.this.aliasToTable);
                inner.walkSelect(ps.getSelect());
                return null;
            }

            private void handleBinary(Expression left, Expression right, ColumnRole role) {
                TableColumnRef l = columnRefOf(left);
                TableColumnRef r = columnRefOf(right);
                boolean leftIsCol  = (l != null && l.isResolved());
                boolean rightIsCol = (r != null && r.isResolved());

                // Implicit JOIN: WHERE t1.a = t2.b — both sides are columns on
                // different tables. Emit a synthetic JOIN edge in addition to
                // the WHERE_EQ entries; both signals are useful (the planner
                // will dedupe).
                if (fromWhere && role == ColumnRole.WHERE_EQ
                    && leftIsCol && rightIsCol
                    && !l.getTable().equals(r.getTable())) {
                    out.joinEdge(new JoinEdge(l, r, JoinEdge.Kind.EQUI));
                    return;
                }

                if (leftIsCol) emit(l, role);
                else if (left instanceof Function) tagColumnsInside((Function) left);
                if (rightIsCol) emit(r, role);
                else if (right instanceof Function) tagColumnsInside((Function) right);
            }

            /**
             * Tag every Column inside a function expression as FUNCTION_WRAPPED.
             * {@code WHERE LOWER(name) = ?} needs an expression index, not a
             * plain B-tree on {@code name}.
             */
            private void tagColumnsInside(Function fn) {
                if (fn == null || fn.getParameters() == null) return;
                for (Expression p : fn.getParameters()) {
                    if (p instanceof Column) {
                        TableColumnRef ref = columnRefOf(p);
                        if (ref != null && ref.isResolved()) emit(ref, ColumnRole.FUNCTION_WRAPPED);
                    } else if (p instanceof Function) {
                        tagColumnsInside((Function) p);
                    }
                }
            }
        }

        private Expression leftOf(Expression e) {
            try {
                if (e instanceof MinorThan)        return ((MinorThan) e).getLeftExpression();
                if (e instanceof MinorThanEquals)  return ((MinorThanEquals) e).getLeftExpression();
                if (e instanceof GreaterThan)      return ((GreaterThan) e).getLeftExpression();
                if (e instanceof GreaterThanEquals) return ((GreaterThanEquals) e).getLeftExpression();
                if (e instanceof NotEqualsTo)      return ((NotEqualsTo) e).getLeftExpression();
            } catch (Exception ignored) { /* fall through */ }
            return null;
        }

        private Expression rightOf(Expression e) {
            try {
                if (e instanceof MinorThan)        return ((MinorThan) e).getRightExpression();
                if (e instanceof MinorThanEquals)  return ((MinorThanEquals) e).getRightExpression();
                if (e instanceof GreaterThan)      return ((GreaterThan) e).getRightExpression();
                if (e instanceof GreaterThanEquals) return ((GreaterThanEquals) e).getRightExpression();
                if (e instanceof NotEqualsTo)      return ((NotEqualsTo) e).getRightExpression();
            } catch (Exception ignored) { /* fall through */ }
            return null;
        }
    }
}
