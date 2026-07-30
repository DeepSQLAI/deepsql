package com.dbaagent.service.agent;

import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

@Service
public class MetadataExplanationService {

    public String buildPairRelationshipExplanation(MetadataRequestScope requestScope, List<Map<String, Object>> relationshipRows) {
        if (requestScope == null || requestScope.requestedTables().size() < 2 || relationshipRows == null || relationshipRows.isEmpty()) {
            return null;
        }

        Map<String, Object> firstRow = relationshipRows.getFirst();
        QualifiedRef source = parseQualified(firstRow.get("source"));
        QualifiedRef target = parseQualified(firstRow.get("target"));

        if (source == null || target == null) {
            return null;
        }

        String childTable = source.table();
        String parentTable = target.table();
        String joinCondition = source.qualified() + " = " + target.qualified();
        String relationSentence = inferRelationshipSentence(source, target, firstRow.get("type"));

        List<String> lines = new ArrayList<>();
        lines.add("`" + childTable + "` links to `" + parentTable + "` through `" + joinCondition + "`.");
        lines.add("");
        lines.add(relationSentence);
        lines.add("");
        lines.add("Use this join condition:");
        lines.add("- `" + joinCondition + "`");

        if (relationshipRows.size() > 1) {
            lines.add("");
            lines.add("Other verified relationship evidence:");
            relationshipRows.stream()
                .skip(1)
                .limit(5)
                .map(row -> {
                    QualifiedRef altSource = parseQualified(row.get("source"));
                    QualifiedRef altTarget = parseQualified(row.get("target"));
                    if (altSource == null || altTarget == null) {
                        return null;
                    }
                    return "- `" + altSource.qualified() + " = " + altTarget.qualified() + "`";
                })
                .filter(Objects::nonNull)
                .forEach(lines::add);
        }

        return String.join("\n", lines);
    }

    public String buildPairJoinColumnExplanation(MetadataRequestScope requestScope, List<Map<String, Object>> relationshipRows) {
        if (requestScope == null || requestScope.requestedTables().size() < 2 || relationshipRows == null || relationshipRows.isEmpty()) {
            return null;
        }

        List<String> joins = relationshipRows.stream()
            .limit(6)
            .map(row -> {
                QualifiedRef source = parseQualified(row.get("source"));
                QualifiedRef target = parseQualified(row.get("target"));
                if (source == null || target == null) {
                    return null;
                }
                return "`" + source.qualified() + " = " + target.qualified() + "`";
            })
            .filter(Objects::nonNull)
            .toList();

        if (joins.isEmpty()) {
            return null;
        }

        String left = requestScope.requestedTables().getFirst();
        String right = requestScope.requestedTables().get(1);
        List<String> lines = new ArrayList<>();
        lines.add("The verified join path between `" + left + "` and `" + right + "` uses these columns:");
        joins.forEach(join -> lines.add("- " + join));
        lines.add("");
        lines.add("Start with the first join condition above unless your query needs a different verified relationship.");
        return String.join("\n", lines);
    }

    private String inferRelationshipSentence(QualifiedRef source, QualifiedRef target, Object rawType) {
        String relationshipType = rawType == null ? "" : rawType.toString().toUpperCase(Locale.ROOT);
        if (relationshipType.contains("MANY_TO_ONE")) {
            return "This is a many-to-one relationship: many `" + source.table() + "` rows can point to one `" + target.table() + "` row.";
        }
        if (relationshipType.contains("ONE_TO_MANY")) {
            return "This is a one-to-many relationship: one `" + source.table() + "` row can link to many `" + target.table() + "` rows.";
        }
        if (looksLikeForeignKeyToPrimaryKey(source, target)) {
            return "This usually means `" + source.table() + "` is the child table and `" + target.table() + "` is the parent table, so one `" + target.table() + "` row can have many related `" + source.table() + "` rows.";
        }
        return "This is the verified direct relationship between the two tables in stored metadata.";
    }

    private boolean looksLikeForeignKeyToPrimaryKey(QualifiedRef source, QualifiedRef target) {
        String sourceColumn = source.column().toLowerCase(Locale.ROOT);
        String targetColumn = target.column().toLowerCase(Locale.ROOT);
        return (sourceColumn.endsWith("_id") || sourceColumn.equals("id"))
            && (targetColumn.equals("id") || targetColumn.endsWith("_id"));
    }

    private QualifiedRef parseQualified(Object raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.toString().trim();
        int separator = value.indexOf('.');
        if (separator <= 0 || separator >= value.length() - 1) {
            return null;
        }
        String table = value.substring(0, separator);
        String column = value.substring(separator + 1);
        return new QualifiedRef(table, column);
    }

    private record QualifiedRef(String table, String column) {
        private String qualified() {
            return table + "." + column;
        }
    }
}
