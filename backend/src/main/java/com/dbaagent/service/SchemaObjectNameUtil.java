package com.dbaagent.service;

import com.dbaagent.model.TableMetadata;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Shared schema object identity helpers.
 *
 * <p>The product stores metadata under a canonical table identity:
 * bare table names for default schemas and schema-qualified names for
 * non-default schemas. Runtime resolution also needs alias matching so
 * schema-qualified docs can still be found when chat/schema code only has
 * the bare runtime table name.
 */
public final class SchemaObjectNameUtil {

    private SchemaObjectNameUtil() {
    }

    public static String canonicalTableName(TableMetadata table) {
        if (table == null || blank(table.getName())) {
            return "";
        }
        return canonicalTableName(table.getSchema(), table.getName());
    }

    public static String canonicalTableName(String schema, String tableName) {
        String bare = sanitizeIdentifier(tableName);
        if (blank(bare)) {
            return "";
        }
        String normalizedSchema = sanitizeIdentifier(schema);
        if (isDefaultSchema(normalizedSchema)) {
            return bare;
        }
        return blank(normalizedSchema) ? bare : normalizedSchema + "." + bare;
    }

    public static String normalizedCanonicalTableName(TableMetadata table) {
        return normalize(canonicalTableName(table));
    }

    public static String normalizedCanonicalTableName(String reference) {
        return normalize(canonicalTableReference(reference));
    }

    public static String canonicalTableReference(String reference) {
        if (blank(reference)) {
            return "";
        }
        String sanitized = sanitizeReference(reference);
        if (blank(sanitized)) {
            return "";
        }

        int dot = sanitized.lastIndexOf('.');
        if (dot < 0) {
            return sanitized;
        }

        String schema = sanitized.substring(0, dot);
        String table = sanitized.substring(dot + 1);
        return canonicalTableName(schema, table);
    }

    public static String normalizedBareTableName(String reference) {
        if (blank(reference)) {
            return "";
        }
        String sanitized = sanitizeReference(reference);
        if (blank(sanitized)) {
            return "";
        }
        int dot = sanitized.lastIndexOf('.');
        String bare = dot >= 0 ? sanitized.substring(dot + 1) : sanitized;
        return normalize(bare);
    }

    public static Set<String> tableLookupAliases(TableMetadata table) {
        if (table == null) {
            return Set.of();
        }
        return tableLookupAliases(canonicalTableName(table));
    }

    public static Set<String> tableLookupAliases(String reference) {
        if (blank(reference)) {
            return Set.of();
        }
        String canonical = normalizedCanonicalTableName(reference);
        String bare = normalizedBareTableName(reference);
        Set<String> aliases = new LinkedHashSet<>();
        if (!blank(canonical)) {
            aliases.add(canonical);
        }
        if (!blank(bare)) {
            aliases.add(bare);
        }
        return aliases;
    }

    public static boolean referencesSameTable(String left, String right) {
        if (blank(left) || blank(right)) {
            return false;
        }
        Set<String> leftAliases = tableLookupAliases(left);
        if (leftAliases.isEmpty()) {
            return false;
        }
        for (String alias : tableLookupAliases(right)) {
            if (leftAliases.contains(alias)) {
                return true;
            }
        }
        return false;
    }

    public static int tableReferenceMatchStrength(String reference, TableMetadata table) {
        if (blank(reference) || table == null || blank(table.getName())) {
            return 0;
        }
        String canonicalReference = normalizedCanonicalTableName(reference);
        String canonicalTable = normalizedCanonicalTableName(table);
        if (!blank(canonicalReference) && canonicalReference.equals(canonicalTable)) {
            return 3;
        }
        String bareReference = normalizedBareTableName(reference);
        String bareTable = normalizedBareTableName(table.getName());
        if (!blank(bareReference) && bareReference.equals(bareTable)) {
            return 2;
        }
        return referencesSameTable(reference, canonicalTable) ? 1 : 0;
    }

    private static String sanitizeReference(String reference) {
        if (blank(reference)) {
            return "";
        }
        String sanitized = reference.trim()
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "");
        sanitized = sanitized.replaceAll("\\s+", "");
        if (sanitized.endsWith(".")) {
            sanitized = sanitized.substring(0, sanitized.length() - 1);
        }
        return sanitized;
    }

    private static String sanitizeIdentifier(String identifier) {
        if (blank(identifier)) {
            return "";
        }
        return identifier.trim()
            .replace("`", "")
            .replace("\"", "")
            .replace("[", "")
            .replace("]", "");
    }

    private static boolean isDefaultSchema(String schema) {
        return blank(schema)
            || "public".equalsIgnoreCase(schema)
            || "dbo".equalsIgnoreCase(schema);
    }

    private static String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private static boolean blank(String value) {
        return value == null || value.isBlank();
    }
}
