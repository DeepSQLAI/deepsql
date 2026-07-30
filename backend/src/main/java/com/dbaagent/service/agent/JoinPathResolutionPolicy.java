package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.service.SchemaTableMatchUtil;
import com.dbaagent.service.pipeline.ResolvedContext;
import com.dbaagent.util.PromptIntentSignals;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

final class JoinPathResolutionPolicy {

    Decision enhanceResolution(
        String question,
        SchemaMetadata schema,
        ResolvedContext resolvedContext,
        List<SemanticJoinModel> semanticJoins,
        Collection<String> priorJoinConditions
    ) {
        if (schema == null || resolvedContext == null || resolvedContext.tables().isEmpty()) {
            return Decision.none(resolvedContext);
        }

        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(question);
        if (!questionNeedsJoinCompletion(normalizedQuestion, resolvedContext) && resolvedContext.tables().size() >= 2) {
            return Decision.none(resolvedContext);
        }
        Map<String, TableMetadata> tablesByName = schema.getTables() == null
            ? Map.of()
            : schema.getTables().stream()
                .filter(Objects::nonNull)
                .filter(table -> table.getName() != null)
                .collect(java.util.stream.Collectors.toMap(
                    table -> table.getName().toLowerCase(Locale.ROOT),
                    table -> table,
                    (left, right) -> left,
                    LinkedHashMap::new
                ));
        Map<String, List<JoinEdge>> adjacency = buildAdjacency(schema, semanticJoins);
        if (adjacency.isEmpty()) {
            return Decision.none(resolvedContext);
        }

        Set<String> currentTables = new LinkedHashSet<>();
        resolvedContext.tables().stream()
            .filter(Objects::nonNull)
            .map(table -> table.toLowerCase(Locale.ROOT))
            .forEach(currentTables::add);
        priorJoinConditions = priorJoinConditions == null ? List.of() : priorJoinConditions;

        List<TableCandidate> candidates = new ArrayList<>();
        for (TableMetadata table : schema.getTables()) {
            if (table == null || table.getName() == null) {
                continue;
            }
            String normalizedTable = table.getName().toLowerCase(Locale.ROOT);
            if (currentTables.contains(normalizedTable)) {
                continue;
            }
            JoinPath joinPath = bestJoinPath(currentTables, normalizedTable, adjacency);
            if (joinPath == null || joinPath.joinConditions().isEmpty()) {
                continue;
            }
            int score = scoreCandidateTable(normalizedQuestion, table, joinPath);
            if (score <= 0) {
                continue;
            }
            candidates.add(new TableCandidate(table, joinPath, score));
        }

        if (candidates.isEmpty()) {
            return Decision.none(resolvedContext);
        }

        candidates.sort(Comparator.comparingInt(TableCandidate::score)
            .reversed()
            .thenComparing(candidate -> candidate.table().getName(), String.CASE_INSENSITIVE_ORDER));
        TableCandidate best = candidates.getFirst();
        TableCandidate second = candidates.size() > 1 ? candidates.get(1) : null;
        int gap = second == null ? best.score() : best.score() - second.score();

        if (!questionNeedsJoinCompletion(normalizedQuestion, resolvedContext) && best.score() < 78) {
            return Decision.none(resolvedContext);
        }
        if (second != null && best.score() >= 70 && gap < 8) {
            return Decision.ambiguous(
                resolvedContext,
                List.of(best.joinPath().joinConditionsAsText(), second.joinPath().joinConditionsAsText()),
                "I can answer this only after you confirm which join path should define the relationship. Likely options are "
                    + best.joinPath().joinConditionsAsText() + " or " + second.joinPath().joinConditionsAsText() + ".",
                "Multiple validated join paths fit the requested entities"
            );
        }
        if (best.score() < 62) {
            return Decision.none(resolvedContext);
        }

        LinkedHashSet<String> enhancedTables = new LinkedHashSet<>(resolvedContext.tables());
        enhancedTables.add(best.table().getName());
        LinkedHashSet<String> enhancedJoins = new LinkedHashSet<>(resolvedContext.joinConditions());
        enhancedJoins.addAll(best.joinPath().joinConditions());
        enhancedJoins.addAll(priorJoinConditions);
        Map<String, List<String>> enhancedColumns = extendResolvedColumns(normalizedQuestion, resolvedContext, best.table());

        ResolvedContext enhanced = new ResolvedContext(
            List.copyOf(enhancedTables),
            enhancedColumns,
            resolvedContext.filterColumns(),
            List.copyOf(enhancedJoins),
            resolvedContext.confidence()
        );
        return new Decision(
            enhanced,
            best.joinPath().joinConditions(),
            candidates.stream().skip(1).limit(3).map(candidate -> candidate.joinPath().joinConditionsAsText()).toList(),
            false,
            null,
            "Join-path policy expanded the answer to include all requested entities using the best validated path",
            List.of(best.table().getName())
        );
    }

    private Map<String, List<JoinEdge>> buildAdjacency(SchemaMetadata schema, List<SemanticJoinModel> semanticJoins) {
        Map<String, List<JoinEdge>> adjacency = new LinkedHashMap<>();
        Set<String> seen = new LinkedHashSet<>();
        if (schema != null && schema.getRelationships() != null) {
            for (RelationshipMetadata relationship : schema.getRelationships()) {
                if (relationship == null
                    || relationship.getFromTable() == null
                    || relationship.getToTable() == null
                    || relationship.getFromColumn() == null
                    || relationship.getToColumn() == null) {
                    continue;
                }
                String expression = relationship.getFromTable() + "." + relationship.getFromColumn()
                    + " = " + relationship.getToTable() + "." + relationship.getToColumn();
                addJoinEdge(adjacency, seen, relationship.getFromTable(), relationship.getToTable(), expression);
            }
        }
        if (semanticJoins != null) {
            for (SemanticJoinModel join : semanticJoins) {
                if (join == null || join.getSourceTable() == null || join.getTargetTable() == null || join.getJoinExpression() == null) {
                    continue;
                }
                addJoinEdge(adjacency, seen, join.getSourceTable(), join.getTargetTable(), join.getJoinExpression());
            }
        }
        addHeuristicForeignKeyEdges(schema, adjacency, seen);
        return adjacency;
    }

    private void addHeuristicForeignKeyEdges(
        SchemaMetadata schema,
        Map<String, List<JoinEdge>> adjacency,
        Set<String> seen
    ) {
        if (schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return;
        }

        List<TableMetadata> tables = schema.getTables().stream()
            .filter(Objects::nonNull)
            .filter(table -> table.getName() != null && table.getColumns() != null)
            .toList();
        if (tables.isEmpty()) {
            return;
        }

        Map<String, TableMetadata> tablesByName = new LinkedHashMap<>();
        for (TableMetadata table : tables) {
            tablesByName.putIfAbsent(table.getName().toLowerCase(Locale.ROOT), table);
        }

        for (TableMetadata sourceTable : tables) {
            for (ColumnMetadata column : sourceTable.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                String normalizedColumn = column.getName().toLowerCase(Locale.ROOT);
                if ("id".equals(normalizedColumn) || !normalizedColumn.endsWith("_id")) {
                    continue;
                }

                String referenceToken = normalizedColumn.substring(0, normalizedColumn.length() - 3);
                if (referenceToken.isBlank() || "group".equals(referenceToken) || "parent".equals(referenceToken) || "owner".equals(referenceToken)) {
                    continue;
                }

                List<TableMetadata> targetCandidates = tables.stream()
                    .filter(target -> !target.getName().equalsIgnoreCase(sourceTable.getName()))
                    .filter(this::hasPrimaryIdColumn)
                    .filter(target -> tableMatchesReferenceToken(target, referenceToken))
                    .toList();
                if (targetCandidates.size() != 1) {
                    continue;
                }

                TableMetadata targetTable = targetCandidates.getFirst();
                String expression = sourceTable.getName() + "." + column.getName()
                    + " = " + targetTable.getName() + ".id";
                addJoinEdge(adjacency, seen, sourceTable.getName(), targetTable.getName(), expression);
            }
        }
    }

    private boolean hasPrimaryIdColumn(TableMetadata table) {
        return table != null
            && table.getColumns() != null
            && table.getColumns().stream()
                .filter(Objects::nonNull)
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .anyMatch(name -> "id".equalsIgnoreCase(name));
    }

    private boolean tableMatchesReferenceToken(TableMetadata table, String referenceToken) {
        if (table == null || table.getName() == null || referenceToken == null || referenceToken.isBlank()) {
            return false;
        }
        Set<String> tokens = extractIdentifierTokens(table.getName());
        return tokens.contains(referenceToken) || tokens.stream().anyMatch(token -> token.endsWith(referenceToken) || referenceToken.endsWith(token));
    }

    private void addJoinEdge(
        Map<String, List<JoinEdge>> adjacency,
        Set<String> seen,
        String sourceTable,
        String targetTable,
        String joinExpression
    ) {
        String source = sourceTable.toLowerCase(Locale.ROOT);
        String target = targetTable.toLowerCase(Locale.ROOT);
        String key = source + "|" + target + "|" + joinExpression.toLowerCase(Locale.ROOT);
        if (!seen.add(key)) {
            return;
        }
        JoinEdge edge = new JoinEdge(source, target, joinExpression);
        adjacency.computeIfAbsent(source, ignored -> new ArrayList<>()).add(edge);
        adjacency.computeIfAbsent(target, ignored -> new ArrayList<>()).add(edge);
    }

    private JoinPath bestJoinPath(Set<String> currentTables, String targetTable, Map<String, List<JoinEdge>> adjacency) {
        ArrayDeque<JoinSearchState> queue = new ArrayDeque<>();
        Set<String> visited = new LinkedHashSet<>();
        currentTables.forEach(table -> queue.add(new JoinSearchState(table, List.of())));

        while (!queue.isEmpty()) {
            JoinSearchState state = queue.removeFirst();
            if (!visited.add(state.tableName())) {
                continue;
            }
            if (state.tableName().equals(targetTable) && !state.joinConditions().isEmpty()) {
                return new JoinPath(state.joinConditions());
            }
            for (JoinEdge join : adjacency.getOrDefault(state.tableName(), List.of())) {
                String next = state.tableName().equalsIgnoreCase(join.sourceTable())
                    ? join.targetTable()
                    : join.sourceTable();
                List<String> nextConditions = new ArrayList<>(state.joinConditions());
                nextConditions.add(join.joinExpression());
                if (nextConditions.size() > 3) {
                    continue;
                }
                queue.addLast(new JoinSearchState(next, List.copyOf(nextConditions)));
            }
        }
        return null;
    }

    private int scoreCandidateTable(String normalizedQuestion, TableMetadata table, JoinPath joinPath) {
        Set<String> questionTokens = extractMeaningfulTokens(normalizedQuestion);
        if (questionTokens.isEmpty()) {
            return 0;
        }
        int score = 0;
        Set<String> matchedTokens = new LinkedHashSet<>();

        for (String token : extractIdentifierTokens(table.getName())) {
            if (questionTokens.contains(token) && matchedTokens.add(token)) {
                score += 26;
            }
        }
        if (table.getColumns() != null) {
            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || column.getName() == null) {
                    continue;
                }
                for (String token : extractIdentifierTokens(column.getName())) {
                    if (questionTokens.contains(token) && matchedTokens.add(token)) {
                        score += 18;
                    }
                }
                if (mentionsDetail(normalizedQuestion) && isDetailColumn(column)) {
                    score += 10;
                }
                if (mentionsMetric(normalizedQuestion) && isMetricColumn(column)) {
                    score += 10;
                }
            }
        }
        if (questionNeedsJoinCompletion(normalizedQuestion, null)) {
            score += 18;
        }
        if (mentionsNamedEntity(normalizedQuestion, table.getName())) {
            score += 22;
        }
        score += requestedDetailCoverageBonus(normalizedQuestion, table);
        if (joinPath != null) {
            score += Math.max(0, 18 - (joinPath.joinConditions().size() * 3));
        }
        return score;
    }

    private boolean mentionsNamedEntity(String normalizedQuestion, String tableName) {
        if (normalizedQuestion == null || tableName == null) {
            return false;
        }
        Set<String> tableTokens = extractIdentifierTokens(tableName);
        return tableTokens.stream()
            .anyMatch(token -> normalizedQuestion.contains(" " + token + " "));
    }

    private boolean questionNeedsJoinCompletion(String normalizedQuestion, ResolvedContext resolvedContext) {
        boolean multiPartShape = normalizedQuestion.contains(" with ")
            || normalizedQuestion.contains(" along with ")
            || normalizedQuestion.contains(" alongside ")
            || normalizedQuestion.contains(" including ")
            || normalizedQuestion.contains(" details ")
            || normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" country ")
            || normalizedQuestion.contains(" amount ")
            || normalizedQuestion.contains(" name ");
        if (!multiPartShape) {
            return false;
        }
        return resolvedContext == null
            || resolvedContext.tables().size() <= 1
            || missingRequestedDetailCoverage(normalizedQuestion, resolvedContext);
    }

    private boolean mentionsDetail(String normalizedQuestion) {
        return normalizedQuestion.contains(" detail ")
            || normalizedQuestion.contains(" details ")
            || normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" country ")
            || normalizedQuestion.contains(" name ")
            || normalizedQuestion.contains(" hotel ");
    }

    private boolean mentionsMetric(String normalizedQuestion) {
        return normalizedQuestion.contains("amount")
            || normalizedQuestion.contains("revenue")
            || normalizedQuestion.contains("total")
            || normalizedQuestion.contains("count")
            || normalizedQuestion.contains("mrr")
            || normalizedQuestion.contains("booking");
    }

    private boolean isDetailColumn(ColumnMetadata column) {
        String normalized = column.getName().toLowerCase(Locale.ROOT);
        return normalized.contains("name")
            || normalized.contains("email")
            || normalized.contains("country")
            || normalized.contains("state")
            || normalized.contains("city")
            || normalized.contains("hotel");
    }

    private boolean isMetricColumn(ColumnMetadata column) {
        String normalized = column.getName().toLowerCase(Locale.ROOT);
        return normalized.contains("amount")
            || normalized.contains("revenue")
            || normalized.contains("count")
            || normalized.contains("price")
            || normalized.contains("fee")
            || normalized.contains("tax");
    }

    private boolean missingRequestedDetailCoverage(String normalizedQuestion, ResolvedContext resolvedContext) {
        if (resolvedContext == null || resolvedContext.columns() == null || resolvedContext.columns().isEmpty()) {
            return true;
        }
        boolean wantsName = normalizedQuestion.contains(" name ") || normalizedQuestion.contains(" names ");
        boolean wantsEmail = normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" emails ")
            || normalizedQuestion.contains(" contact ")
            || normalizedQuestion.contains(" contacts ");
        boolean wantsPersonEntity = PromptIntentSignals.requestsPersonEntity(normalizedQuestion);

        boolean hasName = false;
        boolean hasEmail = false;
        boolean hasPersonLikeTable = false;
        for (Map.Entry<String, List<String>> entry : resolvedContext.columns().entrySet()) {
            String tableName = entry.getKey();
            if (tableName != null && looksLikePersonEntity(tableName)) {
                hasPersonLikeTable = true;
            }
            for (String column : entry.getValue()) {
                String lower = column == null ? "" : column.toLowerCase(Locale.ROOT);
                if (lower.contains("name")) {
                    hasName = true;
                }
                if (lower.contains("email") || lower.contains("contact")) {
                    hasEmail = true;
                }
                if (looksLikePersonEntity(lower)) {
                    hasPersonLikeTable = true;
                }
            }
        }

        if (wantsName && !hasName) {
            return true;
        }
        if (wantsEmail && !hasEmail) {
            return true;
        }
        return wantsPersonEntity && !hasPersonLikeTable;
    }

    private int requestedDetailCoverageBonus(String normalizedQuestion, TableMetadata table) {
        if (table == null || table.getColumns() == null || table.getColumns().isEmpty()) {
            return 0;
        }
        boolean wantsDetails = PromptIntentSignals.requestsDescriptiveAttributes(normalizedQuestion)
            || PromptIntentSignals.requestsContactAttributes(normalizedQuestion)
            || PromptIntentSignals.requestsPersonEntity(normalizedQuestion);
        if (!wantsDetails) {
            return 0;
        }

        boolean wantsPerson = PromptIntentSignals.requestsPersonEntity(normalizedQuestion);
        boolean wantsName = normalizedQuestion.contains(" name ") || normalizedQuestion.contains(" names ");
        boolean wantsEmail = normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" emails ")
            || normalizedQuestion.contains(" contact ")
            || normalizedQuestion.contains(" contacts ");
        boolean hasName = table.getColumns().stream().filter(Objects::nonNull).map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(name -> name.contains("name"));
        boolean hasEmail = table.getColumns().stream().filter(Objects::nonNull).map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .anyMatch(name -> name.contains("email") || name.contains("contact"));
        boolean personLike = looksLikePersonEntity(table.getName())
            || table.getColumns().stream().filter(Objects::nonNull).map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .map(name -> name.toLowerCase(Locale.ROOT))
                .anyMatch(this::looksLikePersonEntity);

        int score = 0;
        if (wantsName && hasName) {
            score += 24;
        }
        if (wantsEmail && hasEmail) {
            score += 24;
        }
        if ((wantsName || wantsEmail) && hasName && hasEmail) {
            score += 28;
        }
        if (wantsPerson && personLike) {
            score += 48;
        }
        if (wantsPerson && !personLike && looksLikePropertyEntity(table.getName()) && !questionMentionsPropertyEntity(normalizedQuestion)) {
            score -= 55;
        }
        return score;
    }

    private boolean questionMentionsPropertyEntity(String normalizedQuestion) {
        return normalizedQuestion.contains(" hotel ")
            || normalizedQuestion.contains(" hotels ")
            || normalizedQuestion.contains(" property ")
            || normalizedQuestion.contains(" properties ")
            || normalizedQuestion.contains(" account ")
            || normalizedQuestion.contains(" accounts ");
    }

    private boolean looksLikePersonEntity(String identifier) {
        String normalized = identifier == null ? "" : identifier.toLowerCase(Locale.ROOT);
        return normalized.contains("user")
            || normalized.contains("guest")
            || normalized.contains("customer")
            || normalized.contains("traveler")
            || normalized.contains("traveller")
            || normalized.contains("person")
            || normalized.contains("contact");
    }

    private boolean looksLikePropertyEntity(String identifier) {
        String normalized = identifier == null ? "" : identifier.toLowerCase(Locale.ROOT);
        return normalized.contains("hotel")
            || normalized.contains("property")
            || normalized.contains("account");
    }

    private Map<String, List<String>> extendResolvedColumns(
        String normalizedQuestion,
        ResolvedContext resolvedContext,
        TableMetadata addedTable
    ) {
        Map<String, List<String>> columns = new LinkedHashMap<>();
        if (resolvedContext.columns() != null) {
            resolvedContext.columns().forEach((table, tableColumns) ->
                columns.put(table, tableColumns == null ? List.of() : new ArrayList<>(tableColumns)));
        }

        List<String> preferredColumns = suggestColumnsForAddedTable(normalizedQuestion, addedTable);
        if (!preferredColumns.isEmpty()) {
            columns.put(addedTable.getName(), preferredColumns);
            pruneOverlappingDetailColumns(columns, addedTable.getName(), preferredColumns);
        }
        return columns;
    }

    private List<String> suggestColumnsForAddedTable(String normalizedQuestion, TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return List.of();
        }
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        boolean detailQuestion = mentionsDetail(normalizedQuestion)
            || normalizedQuestion.contains(" guest ")
            || normalizedQuestion.contains(" customer ")
            || normalizedQuestion.contains(" user ");
        Set<String> questionTokens = extractMeaningfulTokens(normalizedQuestion);
        for (ColumnMetadata column : table.getColumns()) {
            if (column == null || column.getName() == null) {
                continue;
            }
            String lower = column.getName().toLowerCase(Locale.ROOT);
            if (detailQuestion && isDetailColumn(column)) {
                selected.add(column.getName());
                if (questionTokens.contains("guest") || questionTokens.contains("customer") || questionTokens.contains("user")) {
                    if (lower.contains("name")) {
                        selected.add(column.getName());
                    }
                }
            }
        }
        return selected.stream().limit(4).toList();
    }

    private void pruneOverlappingDetailColumns(
        Map<String, List<String>> columns,
        String preferredTable,
        List<String> preferredColumns
    ) {
        Set<String> preferredDetailKinds = preferredColumns.stream()
            .map(this::detailColumnKind)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toSet());
        if (preferredDetailKinds.isEmpty()) {
            return;
        }
        columns.replaceAll((tableName, tableColumns) -> {
            if (tableName == null || tableName.equalsIgnoreCase(preferredTable) || tableColumns == null) {
                return tableColumns;
            }
            return tableColumns.stream()
                .filter(column -> !preferredDetailKinds.contains(detailColumnKind(column)))
                .toList();
        });
    }

    private String detailColumnKind(String columnName) {
        if (columnName == null) {
            return null;
        }
        String normalized = columnName.toLowerCase(Locale.ROOT);
        if (normalized.contains("email")) {
            return "email";
        }
        if (normalized.contains("name")) {
            return "name";
        }
        if (normalized.contains("country")) {
            return "country";
        }
        if (normalized.contains("city")) {
            return "city";
        }
        if (normalized.contains("state")) {
            return "state";
        }
        if (normalized.contains("address")) {
            return "address";
        }
        return null;
    }

    private Set<String> extractMeaningfulTokens(String text) {
        if (text == null || text.isBlank()) {
            return Set.of();
        }
        Set<String> stopWords = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "for", "from", "with", "and", "or", "to",
            "of", "in", "on", "by", "at", "this", "that", "these", "those", "what", "which", "how",
            "much", "many", "month", "months", "week", "weeks", "day", "days", "year", "years"
        );
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String normalized = normalizeToken(part);
            if (normalized.length() < 3 || stopWords.contains(normalized)) {
                continue;
            }
            tokens.add(normalized);
        }
        return tokens;
    }

    private Set<String> extractIdentifierTokens(String identifier) {
        if (identifier == null || identifier.isBlank()) {
            return Set.of();
        }
        Set<String> tokens = new LinkedHashSet<>();
        for (String part : identifier.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (part == null || part.isBlank()) {
                continue;
            }
            String normalized = normalizeToken(part);
            if (normalized.length() >= 3) {
                tokens.add(normalized);
            }
        }
        return tokens;
    }

    private String normalizeToken(String token) {
        if (token == null) {
            return "";
        }
        String normalized = token.trim().toLowerCase(Locale.ROOT);
        if (normalized.endsWith("ies") && normalized.length() > 4) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }
        if (normalized.endsWith("s") && normalized.length() > 3 && !normalized.endsWith("ss")) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private record JoinSearchState(String tableName, List<String> joinConditions) {}
    private record JoinEdge(String sourceTable, String targetTable, String joinExpression) {}

    private record JoinPath(List<String> joinConditions) {
        String joinConditionsAsText() {
            return String.join(" ; ", joinConditions);
        }
    }

    private record TableCandidate(TableMetadata table, JoinPath joinPath, int score) {}

    record Decision(
        ResolvedContext resolvedContext,
        List<String> chosenJoinConditions,
        List<String> discardedAlternatives,
        boolean ambiguous,
        String clarificationMessage,
        String rationale,
        List<String> addedTables
    ) {
        static Decision none(ResolvedContext resolvedContext) {
            return new Decision(
                resolvedContext,
                List.of(),
                List.of(),
                false,
                null,
                null,
                List.of()
            );
        }

        static Decision ambiguous(
            ResolvedContext resolvedContext,
            List<String> discardedAlternatives,
            String clarificationMessage,
            String rationale
        ) {
            return new Decision(
                resolvedContext,
                List.of(),
                discardedAlternatives == null ? List.of() : discardedAlternatives,
                true,
                clarificationMessage,
                rationale,
                List.of()
            );
        }

        boolean hasEnhancement() {
            return resolvedContext != null && chosenJoinConditions != null && !chosenJoinConditions.isEmpty();
        }

        boolean shouldClarifyAfterFailure() {
            return clarificationMessage != null && !clarificationMessage.isBlank();
        }
    }
}
