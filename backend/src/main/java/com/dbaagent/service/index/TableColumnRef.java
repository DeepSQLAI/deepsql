package com.dbaagent.service.index;

import java.util.Locale;
import java.util.Objects;

/**
 * Lower-cased, alias-resolved reference to a single column on a single table.
 *
 * The whole index pipeline canonicalises identifiers to lower-case so that
 * SQL like {@code WHERE Users.Email = ?} and {@code WHERE users.email = ?}
 * dedupe to the same candidate. Postgres folds unquoted identifiers to lower
 * case; MySQL is case-sensitive on Linux but we match {@code KeyColumnAnalysis}
 * which also normalises to lowercase.
 */
public final class TableColumnRef {

    private final String table;
    private final String column;

    public TableColumnRef(String table, String column) {
        this.table = normalize(table);
        this.column = normalize(column);
    }

    public String getTable() {
        return table;
    }

    public String getColumn() {
        return column;
    }

    /**
     * Whether the table and column components are both populated.
     * False for partial extractions where alias resolution failed.
     */
    public boolean isResolved() {
        return table != null && !table.isEmpty() && column != null && !column.isEmpty();
    }

    private static String normalize(String raw) {
        if (raw == null) return null;
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;
        // Strip surrounding identifier quotes/backticks that JSQLParser preserves.
        if (trimmed.length() >= 2) {
            char first = trimmed.charAt(0);
            char last = trimmed.charAt(trimmed.length() - 1);
            if ((first == '`' && last == '`') ||
                (first == '"' && last == '"') ||
                (first == '[' && last == ']')) {
                trimmed = trimmed.substring(1, trimmed.length() - 1);
            }
        }
        return trimmed.toLowerCase(Locale.ROOT);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof TableColumnRef)) return false;
        TableColumnRef other = (TableColumnRef) o;
        return Objects.equals(table, other.table) && Objects.equals(column, other.column);
    }

    @Override
    public int hashCode() {
        return Objects.hash(table, column);
    }

    @Override
    public String toString() {
        return (table == null ? "?" : table) + "." + (column == null ? "?" : column);
    }
}
