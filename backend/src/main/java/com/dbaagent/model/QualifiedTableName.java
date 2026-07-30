package com.dbaagent.model;

import java.util.Locale;

/**
 * Represents a table name with optional schema qualification.
 * Equality is based on the stored form (the value used in Azure Search tableName field).
 *
 * Examples:
 *   bare="orders", schemaQualified=null       → storedForm="orders" (default schema)
 *   bare="orders", schemaQualified="sales.orders" → storedForm="sales.orders" (non-default)
 *   bare="orders", schemaQualified=null       → MySQL always bare (no schema concept)
 */
public record QualifiedTableName(String bare, String schemaQualified) {

    /**
     * The form stored in the Azure Search tableName field.
     * Used for filter queries and equality comparison.
     */
    public String storedForm() {
        return schemaQualified != null ? schemaQualified : bare;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof QualifiedTableName qt
                && storedForm().equalsIgnoreCase(qt.storedForm());
    }

    @Override
    public int hashCode() {
        return storedForm().toLowerCase(Locale.ROOT).hashCode();
    }

    @Override
    public String toString() {
        return storedForm();
    }
}
