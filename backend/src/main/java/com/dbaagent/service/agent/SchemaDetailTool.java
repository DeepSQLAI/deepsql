package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class SchemaDetailTool implements AgentTool {

    @Override
    public String name() {
        return "schema_detail_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        @SuppressWarnings("unchecked")
        Map<String, List<String>> categories = context.getMemory("accountsCandidateCategories");
        if (categories == null || categories.isEmpty()) {
            return new AgentToolResult(
                new AgentObservation("accounts_schema_details", "No shortlisted account tables were available for inspection", Map.of()),
                null,
                null,
                0.5
            );
        }

        Map<String, List<String>> details = new LinkedHashMap<>();
        for (List<String> tables : categories.values()) {
            for (String tableName : tables) {
                details.put(tableName, importantColumns(context.schema(), tableName));
            }
        }
        context.putMemory("accountsTableDetails", details);
        return new AgentToolResult(
            new AgentObservation(
                "accounts_schema_details",
                "Collected important columns for shortlisted account tables",
                Map.of("details", details)
            ),
            null,
            null,
            0.86
        );
    }

    private List<String> importantColumns(SchemaMetadata schema, String tableName) {
        if (schema == null || schema.getTables() == null) {
            return List.of();
        }
        return schema.getTables().stream()
            .filter(table -> tableName.equalsIgnoreCase(table.getName()))
            .findFirst()
            .map(TableMetadata::getColumns)
            .orElse(List.<ColumnMetadata>of())
            .stream()
            .sorted(Comparator.comparingInt((ColumnMetadata column) -> scoreColumn(column.getName())).reversed())
            .limit(6)
            .map(ColumnMetadata::getName)
            .collect(Collectors.toCollection(ArrayList::new));
    }

    private int scoreColumn(String columnName) {
        String lower = columnName == null ? "" : columnName.toLowerCase(Locale.ROOT);
        int score = 0;
        if (lower.endsWith("id") || lower.contains("_id")) score += 5;
        if (lower.contains("amount") || lower.contains("balance") || lower.contains("payment") || lower.contains("invoice")) score += 4;
        if (lower.contains("status") || lower.contains("type") || lower.contains("mode")) score += 3;
        if (lower.contains("email") || lower.contains("name") || lower.contains("user") || lower.contains("guest")) score += 2;
        if (lower.contains("date") || lower.contains("time")) score += 1;
        return score;
    }
}
