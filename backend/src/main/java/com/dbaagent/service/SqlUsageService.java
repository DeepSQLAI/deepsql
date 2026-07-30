package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SqlUsage;
import com.dbaagent.model.TableMetadata;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SqlUsageService {
    private static final Pattern TABLE_REF_PATTERN =
        Pattern.compile("(?i)\\b(from|join)\\s+([`\"\\w\\.]+)(?:\\s+(?:as\\s+)?([`\"\\w]+))?");
    private static final Pattern QUALIFIED_COLUMN_PATTERN =
        Pattern.compile("([`\"\\w\\.]+)\\s*\\.\\s*([`\"\\w]+)");

    private final SchemaScannerService schemaScannerService;

    public SqlUsage parseUsage(String connectionId, String sql) {
        SchemaMetadata schema;
        try {
            schema = schemaScannerService.scanSchema(connectionId);
        } catch (Exception e) {
            log.debug("Failed to load schema for usage parsing: {}", e.getMessage());
            return SqlUsage.builder().tables(new HashSet<>()).columns(new HashSet<>()).build();
        }
        return parseUsage(sql, schema, null);
    }

    public SqlUsage parseUsage(String connectionId, String sql, Map<String, String> disambiguationMap) {
        SchemaMetadata schema;
        try {
            schema = schemaScannerService.scanSchema(connectionId);
        } catch (Exception e) {
            log.debug("Failed to load schema for usage parsing: {}", e.getMessage());
            return SqlUsage.builder().tables(new HashSet<>()).columns(new HashSet<>()).build();
        }
        return parseUsage(sql, schema, disambiguationMap);
    }

    public SqlUsage parseUsage(String sql, SchemaMetadata schema, Map<String, String> disambiguationMap) {
        Set<String> tables = new HashSet<>();
        Set<String> columns = new HashSet<>();

        if (sql == null || sql.isBlank() || schema == null) {
            return SqlUsage.builder().tables(tables).columns(columns).build();
        }

        String normalizedQuery = sql;
        Map<String, String> aliasToTable = new HashMap<>();

        Matcher tableMatcher = TABLE_REF_PATTERN.matcher(normalizedQuery);
        while (tableMatcher.find()) {
            String tableToken = normalizeIdentifier(tableMatcher.group(2));
            if (tableToken.isBlank()) {
                continue;
            }
            String tableKey = tableToken.toLowerCase(Locale.ROOT);
            tables.add(tableKey);
            aliasToTable.put(tableKey, tableKey);

            String aliasToken = normalizeIdentifier(tableMatcher.group(3));
            if (!aliasToken.isBlank()) {
                aliasToTable.put(aliasToken.toLowerCase(Locale.ROOT), tableKey);
            }
        }

        Matcher columnMatcher = QUALIFIED_COLUMN_PATTERN.matcher(normalizedQuery);
        while (columnMatcher.find()) {
            String left = normalizeIdentifier(columnMatcher.group(1));
            String column = normalizeIdentifier(columnMatcher.group(2));
            if (left.isBlank() || column.isBlank()) {
                continue;
            }
            String tableKey = aliasToTable.get(left.toLowerCase(Locale.ROOT));
            if (tableKey == null && left.contains(".")) {
                tableKey = aliasToTable.get(normalizeIdentifier(left).toLowerCase(Locale.ROOT));
            }
            if (tableKey == null) {
                continue;
            }
            columns.add(keyFor(tableKey, column));
        }

        Map<String, Integer> columnNameCounts = buildColumnNameCounts(schema);
        Map<String, String> uniqueColumnToTable = buildUniqueColumnMap(schema, columnNameCounts);
        for (Map.Entry<String, String> entry : uniqueColumnToTable.entrySet()) {
            String columnName = entry.getKey();
            String tableName = entry.getValue();
            if (containsUnqualifiedColumn(normalizedQuery, columnName)) {
                columns.add(keyFor(tableName, columnName));
            }
        }

        if (disambiguationMap != null && !disambiguationMap.isEmpty()) {
            for (Map.Entry<String, String> entry : disambiguationMap.entrySet()) {
                String columnName = entry.getKey();
                String tableName = entry.getValue();
                if (containsUnqualifiedColumn(normalizedQuery, columnName)) {
                    columns.add(keyFor(tableName, columnName));
                }
            }
        }

        return SqlUsage.builder().tables(tables).columns(columns).build();
    }

    private Map<String, Integer> buildColumnNameCounts(SchemaMetadata schema) {
        Map<String, Integer> counts = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            if (table.getColumns() == null) {
                continue;
            }
            for (ColumnMetadata column : table.getColumns()) {
                if (column.getName() == null) {
                    continue;
                }
                String key = column.getName().toLowerCase(Locale.ROOT);
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    private Map<String, String> buildUniqueColumnMap(
        SchemaMetadata schema,
        Map<String, Integer> columnNameCounts
    ) {
        Map<String, String> map = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            if (table.getColumns() == null) {
                continue;
            }
            for (ColumnMetadata column : table.getColumns()) {
                if (column.getName() == null) {
                    continue;
                }
                String columnKey = column.getName().toLowerCase(Locale.ROOT);
                if (columnNameCounts.getOrDefault(columnKey, 0) == 1) {
                    map.put(columnKey, table.getName());
                }
            }
        }
        return map;
    }

    private boolean containsUnqualifiedColumn(String query, String columnName) {
        if (query == null || columnName == null) {
            return false;
        }
        String needle = columnName.toLowerCase(Locale.ROOT);
        String lowerQuery = query.toLowerCase(Locale.ROOT);
        Pattern pattern = Pattern.compile("(?<!\\.)\\b" + Pattern.quote(needle) + "\\b");
        return pattern.matcher(lowerQuery).find();
    }

    private String normalizeIdentifier(String identifier) {
        if (identifier == null) {
            return "";
        }
        String trimmed = identifier.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String cleaned = trimmed.replaceAll("[`\"]", "");
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < cleaned.length() - 1) {
            cleaned = cleaned.substring(dotIndex + 1);
        }
        return cleaned.trim();
    }

    private String keyFor(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return "";
        }
        return tableName.toLowerCase(Locale.ROOT) + "." + columnName.toLowerCase(Locale.ROOT);
    }
}
