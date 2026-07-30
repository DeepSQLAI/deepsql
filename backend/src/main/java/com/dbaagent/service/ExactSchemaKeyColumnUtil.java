package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

public final class ExactSchemaKeyColumnUtil {

    private ExactSchemaKeyColumnUtil() {
    }

    public static List<KeyColumnDescriptor> collectKeyColumns(SchemaMetadata schema, TableMetadata table) {
        if (schema == null || table == null || table.getName() == null || table.getName().isBlank()) {
            return List.of();
        }

        Map<String, LinkedHashSet<String>> rolesByColumn = new LinkedHashMap<>();
        Map<String, Integer> ordinalByColumn = new LinkedHashMap<>();

        List<ColumnMetadata> columns = table.getColumns() == null ? List.of() : table.getColumns();
        for (ColumnMetadata column : columns) {
            if (column == null || column.getName() == null || column.getName().isBlank()) {
                continue;
            }
            ordinalByColumn.put(column.getName(), column.getOrdinalPosition() != null ? column.getOrdinalPosition() : Integer.MAX_VALUE);
            if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                rolesByColumn.computeIfAbsent(column.getName(), ignored -> new LinkedHashSet<>())
                    .add("Primary key");
            }
        }

        List<RelationshipMetadata> relationships = schema.getRelationships() == null ? List.of() : schema.getRelationships();
        for (RelationshipMetadata relationship : relationships) {
            if (relationship == null) {
                continue;
            }
            if (SchemaObjectNameUtil.referencesSameTable(table.getName(), relationship.getFromTable())
                && relationship.getFromColumn() != null
                && !relationship.getFromColumn().isBlank()) {
                rolesByColumn.computeIfAbsent(relationship.getFromColumn(), ignored -> new LinkedHashSet<>())
                    .add("References " + qualifiedColumn(relationship.getToTable(), relationship.getToColumn()));
            }
            if (SchemaObjectNameUtil.referencesSameTable(table.getName(), relationship.getToTable())
                && relationship.getToColumn() != null
                && !relationship.getToColumn().isBlank()) {
                rolesByColumn.computeIfAbsent(relationship.getToColumn(), ignored -> new LinkedHashSet<>())
                    .add("Referenced by " + qualifiedColumn(relationship.getFromTable(), relationship.getFromColumn()));
            }
        }

        return rolesByColumn.entrySet().stream()
            .map(entry -> new KeyColumnDescriptor(entry.getKey(), List.copyOf(entry.getValue())))
            .sorted(Comparator
                .comparingInt((KeyColumnDescriptor descriptor) -> ordinalByColumn.getOrDefault(descriptor.columnName(), Integer.MAX_VALUE))
                .thenComparing(descriptor -> descriptor.columnName().toLowerCase(Locale.ROOT)))
            .toList();
    }

    public static List<KeyColumnDescriptor> mergeWithAnalyzedColumns(
        List<KeyColumnDescriptor> exactColumns,
        List<KeyColumnAnalysis> analyzedColumns
    ) {
        Map<String, LinkedHashSet<String>> merged = new LinkedHashMap<>();
        for (KeyColumnDescriptor descriptor : exactColumns == null ? List.<KeyColumnDescriptor>of() : exactColumns) {
            merged.computeIfAbsent(descriptor.columnName(), ignored -> new LinkedHashSet<>()).addAll(descriptor.roles());
        }
        for (KeyColumnAnalysis analysis : analyzedColumns == null ? List.<KeyColumnAnalysis>of() : analyzedColumns) {
            if (analysis == null || analysis.getColumnName() == null || analysis.getColumnName().isBlank()) {
                continue;
            }
            merged.computeIfAbsent(analysis.getColumnName(), ignored -> new LinkedHashSet<>())
                .add("High query importance");
        }
        return merged.entrySet().stream()
            .map(entry -> new KeyColumnDescriptor(entry.getKey(), List.copyOf(entry.getValue())))
            .toList();
    }

    private static String qualifiedColumn(String tableName, String columnName) {
        String resolvedTable = Objects.toString(tableName, "?");
        String resolvedColumn = Objects.toString(columnName, "?");
        return resolvedTable + "." + resolvedColumn;
    }

    public record KeyColumnDescriptor(String columnName, List<String> roles) {
        public String summary() {
            return String.join("; ", new ArrayList<>(roles));
        }
    }
}
