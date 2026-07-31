package com.dbaagent.util;

import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Utility to align table identifier casing to match schema metadata (MySQL-friendly).
 */
public final class SqlIdentifierCaseAligner {

    private SqlIdentifierCaseAligner() {}

    public static String alignTableIdentifiers(String sql, SchemaMetadata schemaMetadata) {
        if (sql == null || sql.isBlank() || schemaMetadata == null || schemaMetadata.getTables() == null) {
            return sql;
        }

        Map<String, String> tableCaseMap = buildTableCaseMap(schemaMetadata);
        if (tableCaseMap.isEmpty()) {
            return sql;
        }

        return replaceIdentifiersOutsideQuotes(sql, tableCaseMap);
    }

    private static Map<String, String> buildTableCaseMap(SchemaMetadata schemaMetadata) {
        Map<String, String> tableCaseMap = new HashMap<>();
        // Track row counts to resolve case-conflicting table names (e.g., ORDER_ITEMS vs order_items).
        // When lower_case_table_names=0, MySQL treats these as separate tables. Prefer the one with data.
        Map<String, Long> tableRowCounts = new HashMap<>();

        for (TableMetadata table : schemaMetadata.getTables()) {
            if (table.getName() == null || table.getName().isBlank()) {
                continue;
            }
            String tableName = table.getName();
            String key = tableName.toLowerCase(Locale.ROOT);
            long rows = table.getRowCount() != null ? table.getRowCount() : 0;

            Long existingRows = tableRowCounts.get(key);
            if (existingRows == null || rows > existingRows) {
                tableCaseMap.put(key, tableName);
                tableRowCounts.put(key, rows);
            }

            if (table.getSchema() != null && !table.getSchema().isBlank()) {
                String qualified = table.getSchema() + "." + tableName;
                String qualifiedKey = qualified.toLowerCase(Locale.ROOT);
                Long existingQualifiedRows = tableRowCounts.get(qualifiedKey);
                if (existingQualifiedRows == null || rows > existingQualifiedRows) {
                    tableCaseMap.put(qualifiedKey, qualified);
                    tableRowCounts.put(qualifiedKey, rows);
                }
            }
        }
        return tableCaseMap;
    }

    private static String replaceIdentifiersOutsideQuotes(String sql, Map<String, String> replacements) {
        if (sql == null || sql.isEmpty() || replacements == null || replacements.isEmpty()) {
            return sql;
        }

        StringBuilder out = new StringBuilder(sql.length());
        boolean inSingle = false;
        boolean inDouble = false;
        boolean inBacktick = false;

        for (int i = 0; i < sql.length(); i++) {
            char c = sql.charAt(i);

            if (inSingle) {
                out.append(c);
                if (c == '\\' && i + 1 < sql.length()) {
                    out.append(sql.charAt(++i));
                    continue;
                }
                if (c == '\'') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '\'') {
                        out.append(sql.charAt(++i));
                    } else {
                        inSingle = false;
                    }
                }
                continue;
            }

            if (inDouble) {
                out.append(c);
                if (c == '\\' && i + 1 < sql.length()) {
                    out.append(sql.charAt(++i));
                    continue;
                }
                if (c == '"') {
                    if (i + 1 < sql.length() && sql.charAt(i + 1) == '"') {
                        out.append(sql.charAt(++i));
                    } else {
                        inDouble = false;
                    }
                }
                continue;
            }

            if (inBacktick) {
                out.append(c);
                if (c == '`') {
                    inBacktick = false;
                }
                continue;
            }

            if (c == '\'') {
                inSingle = true;
                out.append(c);
                continue;
            }
            if (c == '"') {
                inDouble = true;
                out.append(c);
                continue;
            }
            if (c == '`') {
                int j = i + 1;
                while (j < sql.length() && sql.charAt(j) != '`') {
                    j++;
                }
                String token = sql.substring(i + 1, Math.min(j, sql.length()));
                String replacement = replacements.get(token.toLowerCase(Locale.ROOT));
                out.append('`').append(replacement != null ? replacement : token);
                if (j < sql.length() && sql.charAt(j) == '`') {
                    out.append('`');
                    i = j;
                } else {
                    i = j - 1;
                }
                continue;
            }

            if (Character.isLetter(c) || c == '_') {
                int j = i + 1;
                while (j < sql.length()) {
                    char cj = sql.charAt(j);
                    if (Character.isLetterOrDigit(cj) || cj == '_' || cj == '.') {
                        j++;
                    } else {
                        break;
                    }
                }
                String token = sql.substring(i, j);
                String replacement = replacements.get(token.toLowerCase(Locale.ROOT));
                if (replacement == null && token.indexOf('.') > 0) {
                    replacement = alignQualifiedToken(token, replacements);
                }
                out.append(replacement != null ? replacement : token);
                i = j - 1;
                continue;
            }

            out.append(c);
        }

        return out.toString();
    }

    private static String alignQualifiedToken(String token, Map<String, String> replacements) {
        String[] parts = token.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        for (int i = parts.length - 1; i >= 1; i--) {
            String prefix = String.join(".", java.util.Arrays.copyOfRange(parts, 0, i));
            String replacement = replacements.get(prefix.toLowerCase(Locale.ROOT));
            if (replacement != null) {
                String suffix = String.join(".", java.util.Arrays.copyOfRange(parts, i, parts.length));
                return replacement + "." + suffix;
            }
        }
        return null;
    }
}
