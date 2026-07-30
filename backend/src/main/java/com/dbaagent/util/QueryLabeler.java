package com.dbaagent.util;

import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Turns a SQL fingerprint into a short, human-readable label for compact list display.
 *
 * Signal priority (most distinctive first):
 *   1. Operation + primary table   — always present ("Room reservations …")
 *   2. First JOIN table            → "Room reservations + guests query"
 *   3. First meaningful WHERE col  → "Room reservations by hotel_id query"
 *   4. GROUP BY column             → "Room reservations by date aggregation"
 *   5. ORDER BY column             → "Room reservations ordered by created_at"
 *   6. Aggregate flag              → "Room reservations aggregation"
 *   7. Bare operation              → "Room reservations query"
 *
 * Pure heuristic — deterministic and cheap, no parsing dependency.
 *
 * Columns that are too generic to differentiate queries (e.g. "id", "created_at")
 * are skipped so the label reflects the column that actually shapes the query.
 */
public final class QueryLabeler {

    private QueryLabeler() {}

    // Columns that are near-universal and add no differentiation.
    // Multi-tenancy partition columns (tenant_id, company_id, …) appear on virtually
    // every query in a SaaS schema and reveal nothing about what the query does.
    private static final Set<String> NOISE_COLS = Set.of(
        "id", "uuid", "uid", "pk",
        "tenant_id", "company_id", "org_id", "organization_id", "account_id",
        "workspace_id", "team_id",
        "created_at", "updated_at", "deleted_at", "created", "updated",
        "timestamp", "ts", "date", "time",
        "active", "enabled", "is_deleted", "deleted",
        "1", "true", "false"
    );

    private static final Pattern AGGREGATE =
        Pattern.compile("\\b(count|sum|avg|min|max|group\\s+by)\\b", Pattern.CASE_INSENSITIVE);

    // Possibly-qualified, possibly-quoted name: `s`.`t`, "s"."t", s.t, t
    private static final String QUALIFIED_NAME =
        "([`\"']?\\w+[`\"']?(?:\\s*\\.\\s*[`\"']?\\w+[`\"']?)*)";

    private static final Pattern FROM_TABLE =
        Pattern.compile("\\bfrom\\s+" + QUALIFIED_NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_TABLE =
        Pattern.compile("\\bupdate\\s+" + QUALIFIED_NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern INSERT_TABLE =
        Pattern.compile("\\binsert\\s+into\\s+" + QUALIFIED_NAME, Pattern.CASE_INSENSITIVE);
    private static final Pattern DDL_TABLE =
        Pattern.compile("\\b(?:table|index|view)\\s+(?:if\\s+(?:not\\s+)?exists\\s+)?" + QUALIFIED_NAME,
            Pattern.CASE_INSENSITIVE);

    // First explicit JOIN of any kind
    private static final Pattern JOIN_TABLE =
        Pattern.compile("\\b(?:inner|left|right|full|cross)?\\s*join\\s+" + QUALIFIED_NAME,
            Pattern.CASE_INSENSITIVE);

    // Start of the WHERE clause (used to slice the SQL for column extraction)
    private static final Pattern WHERE_START =
        Pattern.compile("\\bwhere\\b", Pattern.CASE_INSENSITIVE);

    // Clause keywords that terminate the WHERE section
    private static final Pattern WHERE_END =
        Pattern.compile("\\b(?:group|order|having|limit|union|intersect|except)\\b",
            Pattern.CASE_INSENSITIVE);

    // A column comparison anywhere in a clause fragment:
    // optional table-alias prefix, then the column name, then a comparison operator
    private static final Pattern COMPARISON_COL =
        Pattern.compile(
            "(?:[`\"']?\\w+[`\"']?\\s*\\.\\s*)?([`\"']?[a-zA-Z_]\\w*[`\"']?)\\s*" +
            "(?:=|<>|!=|\\blike\\b|\\bin\\b|\\bbetween\\b|>=|<=|>|<)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern GROUP_BY_COL =
        Pattern.compile(
            "\\bgroup\\s+by\\s+(?:[`\"']?\\w+[`\"']?\\s*\\.\\s*)?([`\"']?\\w+[`\"']?)",
            Pattern.CASE_INSENSITIVE);

    private static final Pattern ORDER_BY_COL =
        Pattern.compile(
            "\\border\\s+by\\s+(?:[`\"']?\\w+[`\"']?\\s*\\.\\s*)?([`\"']?\\w+[`\"']?)",
            Pattern.CASE_INSENSITIVE);

    // ── public API ──────────────────────────────────────────────────────────

    /** A short, human-readable label for a SQL statement. Never null/blank. */
    public static String label(String sql) {
        if (sql == null || sql.isBlank()) {
            return "Query";
        }
        String s = sql.trim();
        String op = firstWord(s);
        String table = primaryTable(s, op);
        String human = table == null ? null : humanize(table);

        switch (op) {
            case "select":
            case "with": {
                boolean aggregate = AGGREGATE.matcher(s).find();
                if (human == null) {
                    return aggregate ? "Aggregation query" : "Select query";
                }
                String qualifier = selectQualifier(s, aggregate);
                if (qualifier != null) {
                    return aggregate
                        ? human + " " + qualifier + " aggregation"
                        : human + " " + qualifier;
                }
                return aggregate ? human + " aggregation" : human + " query";
            }
            case "update":
                return human == null ? "Update query" : human + " update";
            case "insert":
            case "replace":
            case "upsert":
            case "merge":
                return human == null ? "Insert query" : human + " insert";
            case "delete":
                return human == null ? "Delete query" : human + " delete";
            case "create":
            case "alter":
            case "drop":
            case "truncate":
                return human == null ? "Schema change" : human + " schema change";
            default:
                return human == null ? "Query" : human + " query";
        }
    }

    // ── signal extraction ───────────────────────────────────────────────────

    /**
     * Builds the qualifier that differentiates SELECT queries on the same table.
     *
     * Priority:
     *   JOIN table  → "+ guests"      (most structurally distinct)
     *   WHERE col   → "by hotel_id"  (filters shape the result set)
     *   GROUP BY    → "by date"      (for aggregations)
     *   ORDER BY    → "ordered by created_at"  (plain selects only)
     */
    private static String selectQualifier(String sql, boolean aggregate) {
        // 1. First explicit JOIN table — use title-case (it's a table name)
        String join = extractLastSegment(JOIN_TABLE.matcher(sql));
        if (join != null) {
            return "+ " + humanize(join);
        }

        // 2. First meaningful WHERE column — keep underscores (it's a column name)
        String whereCol = firstMeaningfulWhereCol(sql);
        if (whereCol != null) {
            return "by " + cleanCol(whereCol);
        }

        // 3. GROUP BY column — especially useful for aggregations
        String groupCol = extractColName(GROUP_BY_COL.matcher(sql));
        if (groupCol != null) {
            return "by " + cleanCol(groupCol);
        }

        // 4. ORDER BY column — last resort for plain selects only
        if (!aggregate) {
            String orderCol = extractColName(ORDER_BY_COL.matcher(sql));
            if (orderCol != null) {
                return "ordered by " + cleanCol(orderCol);
            }
        }

        return null;
    }

    /**
     * Extracts the first meaningful column from the WHERE clause.
     *
     * Slices the SQL at WHERE … (GROUP|ORDER|HAVING|LIMIT), then scans all
     * column comparisons within that fragment, skipping noise column names.
     * This handles multi-column predicates like:
     *   WHERE tenant_id = ? AND hotel_id = ?   →  "hotel_id"  (tenant_id is noise)
     */
    private static String firstMeaningfulWhereCol(String sql) {
        Matcher whereStart = WHERE_START.matcher(sql);
        if (!whereStart.find()) {
            return null;
        }
        // Slice from the end of "WHERE" to the start of the next major clause
        String afterWhere = sql.substring(whereStart.end());
        Matcher whereEnd = WHERE_END.matcher(afterWhere);
        if (whereEnd.find()) {
            afterWhere = afterWhere.substring(0, whereEnd.start());
        }

        return extractColName(COMPARISON_COL.matcher(afterWhere));
    }

    // ── low-level helpers ───────────────────────────────────────────────────

    /**
     * Returns the last dot-segment of the first match of {@code m} (group 1),
     * stripped of quotes and whitespace. Returns null if no match or blank.
     */
    private static String extractLastSegment(Matcher m) {
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        String[] parts = raw.split("\\.");
        String last = parts[parts.length - 1]
            .replace("`", "").replace("\"", "").replace("'", "").trim();
        return last.isBlank() ? null : last;
    }

    /**
     * Iterates all matches of {@code m} (group 1), returning the first column
     * name that is non-blank and not in {@link #NOISE_COLS}. Returns null if
     * none qualify.
     */
    private static String extractColName(Matcher m) {
        while (m.find()) {
            String col = m.group(1)
                .replace("`", "").replace("\"", "").replace("'", "").trim();
            if (!col.isBlank() && !NOISE_COLS.contains(col.toLowerCase(Locale.ROOT))) {
                return col;
            }
        }
        return null;
    }

    private static String firstWord(String sql) {
        int i = 0, n = sql.length();
        while (i < n && !Character.isLetter(sql.charAt(i))) i++;
        int j = i;
        while (j < n && Character.isLetter(sql.charAt(j))) j++;
        return sql.substring(i, j).toLowerCase(Locale.ROOT);
    }

    private static String primaryTable(String sql, String op) {
        Matcher m = switch (op) {
            case "update"               -> UPDATE_TABLE.matcher(sql);
            case "insert", "replace"    -> INSERT_TABLE.matcher(sql);
            case "create", "alter",
                 "drop",  "truncate"    -> DDL_TABLE.matcher(sql);
            default                     -> FROM_TABLE.matcher(sql); // select / with / delete
        };
        if (!m.find()) {
            return null;
        }
        String raw = m.group(1);
        String[] parts = raw.split("\\.");
        String last = parts[parts.length - 1];
        return last.replace("`", "").replace("\"", "").replace("'", "").trim();
    }

    /**
     * Cleans a column name for display — strips quotes but preserves underscores
     * so it stays recognisable as a column name ("hotel_id", not "Hotel id").
     */
    private static String cleanCol(String col) {
        return col.replace("`", "").replace("\"", "").replace("'", "").trim();
    }

    /** price_breakdown → "Price breakdown",  bookings → "Bookings" */
    private static String humanize(String name) {
        String cleaned = name.replace("`", "").replace("\"", "").replace("'", "")
            .replace('_', ' ').replace('-', ' ').trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        return Character.toUpperCase(cleaned.charAt(0)) + cleaned.substring(1);
    }
}
