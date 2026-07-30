package com.dbaagent.service.brain.core;
import com.dbaagent.service.SqlUsageService;

import com.dbaagent.model.*;
import com.dbaagent.model.brain.*;
import com.dbaagent.repository.AnalysisHistoryRepository;
import com.dbaagent.repository.brain.BrainScoreSnapshotRepository;
import com.dbaagent.repository.ColumnDisambiguationRepository;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.TrainingJobHistoryRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class BrainService {
    private static final int MAX_NEEDS_INPUT = 20;
    private static final int USAGE_CAP = 10;
    private static final int AMBIGUITY_NOTICE_THRESHOLD = 3;
    private static final int SCORE_DROP_WARNING = 10;
    private static final int SCORE_DROP_CRITICAL = 20;
    private static final int DRIFT_SAMPLE_LIMIT = 5;
    private static final int BCNF_SCORE_LIKELY = 90;
    private static final int BCNF_SCORE_REVIEW = 60;
    private static final int BCNF_SCORE_UNKNOWN = 45;
    private static final int BCNF_MAX_ISSUES = 3;
    private static final Set<String> AUDIT_COLUMNS = Set.of(
        "created_at",
        "updated_at",
        "created_on",
        "updated_on",
        "modified_at",
        "modified_on",
        "deleted_at",
        "deleted_on",
        "created_by",
        "updated_by",
        "modified_by",
        "deleted_by",
        "status",
        "is_active",
        "active",
        "enabled"
    );
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final QueryExampleRepository queryExampleRepository;
    private final TrainingJobHistoryRepository trainingJobHistoryRepository;
    private final AnalysisHistoryRepository analysisHistoryRepository;
    private final BrainScoreSnapshotRepository brainScoreSnapshotRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final ColumnDisambiguationRepository columnDisambiguationRepository;
    private final QueryLineageRepository queryLineageRepository;
    private final SchemaSnapshotRepository schemaSnapshotRepository;
    private final ObjectMapper objectMapper;
    private final SqlUsageService sqlUsageService;
    private final com.dbaagent.service.SchemaDriftListener schemaDriftListener;

    @Value("${brain.usage.lookback-days:90}")
    private int usageLookbackDays;

    @Value("${brain.history.limit:20}")
    private int historyLimit;

    @Value("${brain.score.snapshot.min-interval-minutes:60}")
    private int snapshotIntervalMinutes;

    @Value("${brain.score.snapshot.min-delta:1}")
    private int snapshotMinDelta;

    public BrainUnderstandingResponse getUnderstanding(String connectionId) {
        SchemaSnapshot latestSnapshot = schemaSnapshotRepository
            .findTopByConnectionIdOrderByCapturedAtDesc(connectionId)
            .orElse(null);
        SchemaMetadata schema = null;
        boolean schemaFromSnapshot = false;

        if (latestSnapshot != null) {
            schema = parseSchemaSnapshot(latestSnapshot);
            schemaFromSnapshot = schema != null;
        }

        if (schema == null) {
            schema = new SchemaMetadata();
            schema.setTables(new ArrayList<>());
            schema.setRelationships(new ArrayList<>());
        }

        List<SchemaDocumentation> docs = schemaDocumentationRepository.findByConnectionId(connectionId);
        Map<String, SchemaDocumentation> tableDocs = buildTableDocMap(docs);
        Map<String, SchemaDocumentation> columnDocs = buildColumnDocMap(docs);
        Map<String, Integer> columnNameCounts = buildColumnNameCounts(schema);
        Map<String, List<String>> columnTableMap = buildColumnTableMap(schema);
        Map<String, Set<String>> foreignKeysByTable = buildForeignKeyMap(schema);
        Map<String, String> disambiguationMap = buildDisambiguationMap(connectionId);
        UsageSignals usageSignals = buildUsageSignals(connectionId, schema, disambiguationMap);
        Map<String, Integer> tableUsageCounts = usageSignals.tableUsage;
        Map<String, Integer> columnUsageCounts = usageSignals.columnUsage;
        Map<String, ColumnProfile> profiles = buildColumnProfileMap(connectionId);

        List<TableUnderstandingScore> tableScores = new ArrayList<>();
        List<ColumnUnderstandingScore> columnScores = new ArrayList<>();
        List<BrainInputRequest> needsInput = new ArrayList<>();

        for (TableMetadata table : schema.getTables()) {
            TableUnderstandingScore tableScore = buildTableScore(
                table,
                tableDocs,
                tableUsageCounts,
                profiles,
                foreignKeysByTable
            );
            tableScores.add(tableScore);

            if (Boolean.FALSE.equals(tableScore.getDocumented()) || tableScore.getScore() < 60) {
                needsInput.add(BrainInputRequest.builder()
                    .objectType("TABLE")
                    .tableName(table.getName())
                    .score(tableScore.getScore())
                    .reason(Boolean.TRUE.equals(tableScore.getDocumented())
                        ? "Low understanding score"
                        : "Missing documentation")
                    .build());
            }

            for (ColumnMetadata column : table.getColumns()) {
                ColumnProfile profile = profiles.get(keyFor(table.getName(), column.getName()));
                ColumnUnderstandingScore columnScore = buildColumnScore(
                    table,
                    column,
                    columnDocs,
                    columnNameCounts,
                    columnTableMap,
                    columnUsageCounts,
                    disambiguationMap,
                    profile
                );
                columnScores.add(columnScore);

                boolean resolvedAmbiguity = columnScore.getAmbiguityResolved() != null &&
                    columnScore.getAmbiguityResolved();
                if (!resolvedAmbiguity &&
                    columnScore.getAmbiguityCount() != null &&
                    columnScore.getAmbiguityCount() > AMBIGUITY_NOTICE_THRESHOLD) {
                    needsInput.add(BrainInputRequest.builder()
                        .objectType("COLUMN")
                        .tableName(table.getName())
                        .columnName(column.getName())
                        .score(columnScore.getScore())
                        .reason("Column name appears in " + columnScore.getAmbiguityCount() + " tables")
                        .ambiguousTables(columnScore.getAmbiguousTables())
                        .build());
                } else if (Boolean.FALSE.equals(columnScore.getDocumented()) && columnScore.getScore() < 50) {
                    needsInput.add(BrainInputRequest.builder()
                        .objectType("COLUMN")
                        .tableName(table.getName())
                        .columnName(column.getName())
                        .score(columnScore.getScore())
                        .reason("Missing documentation")
                        .ambiguousTables(columnScore.getAmbiguousTables())
                        .build());
                }
            }
        }

        int tableCount = tableScores.size();
        int columnCount = columnScores.size();
        int overallScore = computeOverallScore(tableScores);
        LocalDateTime lastTrainingAt = trainingJobHistoryRepository
            .findTopByConnectionIdAndStatusOrderByCompletedAtDesc(connectionId, TrainingJobStatus.Status.COMPLETED)
            .map(TrainingJobHistory::getCompletedAt)
            .orElse(null);

        needsInput = needsInput.stream()
            .sorted(Comparator.comparing(BrainInputRequest::getScore, Comparator.nullsLast(Comparator.naturalOrder())))
            .limit(MAX_NEEDS_INPUT)
            .toList();

        List<BrainScoreSnapshot> history = loadScoreHistory(connectionId, overallScore, tableCount, columnCount);
        BrainTrend trend = computeTrend(history);
        List<BrainAlert> alerts = buildAlerts(trend);
        BrainSchemaDrift schemaDrift = buildSchemaDrift(connectionId, schema, latestSnapshot, schemaFromSnapshot);

        // Trigger AI description generation/cleanup for schema drift
        if (schemaDrift != null && schemaDrift.getSampleTablesAdded() != null
                && !schemaDrift.getSampleTablesAdded().isEmpty()) {
            schemaDriftListener.onTablesAdded(connectionId, schemaDrift.getSampleTablesAdded());
        }
        if (schemaDrift != null && schemaDrift.getSampleTablesRemoved() != null
                && !schemaDrift.getSampleTablesRemoved().isEmpty()) {
            schemaDriftListener.onTablesRemoved(connectionId, schemaDrift.getSampleTablesRemoved());
        }

        return BrainUnderstandingResponse.builder()
            .connectionId(connectionId)
            .overallScore(overallScore)
            .tableCount(tableCount)
            .columnCount(columnCount)
            .lastTrainingAt(lastTrainingAt)
            .tables(tableScores)
            .columns(columnScores)
            .needsInput(needsInput)
            .scoreHistory(history)
            .trendDelta(trend.delta)
            .trendDirection(trend.direction)
            .alerts(alerts)
            .schemaDrift(schemaDrift)
            .build();
    }

    private Map<String, SchemaDocumentation> buildTableDocMap(List<SchemaDocumentation> docs) {
        return docs.stream()
            .filter(doc -> doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE)
            .collect(Collectors.toMap(
                doc -> doc.getObjectName().toLowerCase(Locale.ROOT),
                doc -> doc,
                (a, b) -> a
            ));
    }

    private Map<String, SchemaDocumentation> buildColumnDocMap(List<SchemaDocumentation> docs) {
        return docs.stream()
            .filter(doc -> doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN)
            .collect(Collectors.toMap(
                doc -> (doc.getParentObject() + "." + doc.getObjectName()).toLowerCase(Locale.ROOT),
                doc -> doc,
                (a, b) -> a
            ));
    }

    private Map<String, Integer> buildColumnNameCounts(SchemaMetadata schema) {
        Map<String, Integer> counts = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            for (ColumnMetadata column : table.getColumns()) {
                String key = column.getName().toLowerCase(Locale.ROOT);
                counts.merge(key, 1, Integer::sum);
            }
        }
        return counts;
    }

    private Map<String, List<String>> buildColumnTableMap(SchemaMetadata schema) {
        Map<String, List<String>> map = new HashMap<>();
        for (TableMetadata table : schema.getTables()) {
            for (ColumnMetadata column : table.getColumns()) {
                String key = column.getName().toLowerCase(Locale.ROOT);
                map.computeIfAbsent(key, value -> new ArrayList<>()).add(table.getName());
            }
        }
        return map;
    }

    private Map<String, Set<String>> buildForeignKeyMap(SchemaMetadata schema) {
        Map<String, Set<String>> map = new HashMap<>();
        if (schema == null || schema.getRelationships() == null) {
            return map;
        }
        for (RelationshipMetadata relationship : schema.getRelationships()) {
            if (relationship.getFromTable() == null || relationship.getFromColumn() == null) {
                continue;
            }
            String tableKey = relationship.getFromTable().toLowerCase(Locale.ROOT);
            map.computeIfAbsent(tableKey, value -> new HashSet<>())
                .add(relationship.getFromColumn().toLowerCase(Locale.ROOT));
        }
        return map;
    }

    private BcnfAssessment assessBcnf(TableMetadata table, Set<String> fkColumns) {
        if (table == null || table.getColumns() == null) {
            return new BcnfAssessment(BCNF_SCORE_UNKNOWN, "unknown", List.of("No column metadata"));
        }

        Set<String> primaryKeyColumns = buildPrimaryKeyColumns(table);
        List<Set<String>> candidateKeys = buildCandidateKeys(table, primaryKeyColumns);
        Set<String> allColumns = table.getColumns().stream()
            .map(column -> column.getName() != null ? column.getName().toLowerCase(Locale.ROOT) : "")
            .filter(name -> !name.isBlank())
            .collect(Collectors.toSet());
        Set<String> keyColumns = candidateKeys.stream()
            .flatMap(Set::stream)
            .collect(Collectors.toSet());
        Set<String> nonKeyColumns = new HashSet<>(allColumns);
        nonKeyColumns.removeAll(keyColumns);
        Set<String> nonAuditNonKeyColumns = stripAuditColumns(nonKeyColumns);

        List<String> issues = new ArrayList<>();
        if (candidateKeys.isEmpty()) {
            issues.add("No primary or unique key to confirm BCNF");
        }

        boolean hasCompositeKey = primaryKeyColumns.size() > 1;
        boolean joinTable = isJoinTable(primaryKeyColumns, fkColumns, allColumns);
        if (hasCompositeKey && !joinTable && !nonAuditNonKeyColumns.isEmpty()) {
            issues.add("Composite key with non-key attributes");
        }

        issues.addAll(detectRepeatingGroups(table.getColumns(), keyColumns, fkColumns));
        issues.addAll(detectForeignKeyShadowing(table.getColumns(), fkColumns, keyColumns));

        if (issues.size() > BCNF_MAX_ISSUES) {
            issues = issues.subList(0, BCNF_MAX_ISSUES);
        }

        if (candidateKeys.isEmpty()) {
            return new BcnfAssessment(BCNF_SCORE_UNKNOWN, "unknown", List.copyOf(issues));
        }
        if (issues.isEmpty()) {
            return new BcnfAssessment(BCNF_SCORE_LIKELY, "likely", List.of());
        }
        return new BcnfAssessment(BCNF_SCORE_REVIEW, "review", List.copyOf(issues));
    }

    private Set<String> buildPrimaryKeyColumns(TableMetadata table) {
        if (table == null || table.getColumns() == null) {
            return Set.of();
        }
        Set<String> keys = new HashSet<>();
        for (ColumnMetadata column : table.getColumns()) {
            if (Boolean.TRUE.equals(column.getPrimaryKey()) && column.getName() != null) {
                keys.add(column.getName().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    private List<Set<String>> buildCandidateKeys(TableMetadata table, Set<String> primaryKeyColumns) {
        List<Set<String>> keys = new ArrayList<>();
        if (primaryKeyColumns != null && !primaryKeyColumns.isEmpty()) {
            keys.add(new HashSet<>(primaryKeyColumns));
        }
        if (table != null && table.getIndexes() != null) {
            for (IndexMetadata index : table.getIndexes()) {
                if (!Boolean.TRUE.equals(index.getUnique()) || index.getColumns() == null) {
                    continue;
                }
                Set<String> columns = index.getColumns().stream()
                    .filter(Objects::nonNull)
                    .map(name -> name.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toSet());
                if (!columns.isEmpty() && keys.stream().noneMatch(columns::equals)) {
                    keys.add(columns);
                }
            }
        }
        return keys;
    }

    private boolean isJoinTable(Set<String> primaryKeyColumns, Set<String> fkColumns, Set<String> allColumns) {
        if (primaryKeyColumns == null || fkColumns == null || allColumns == null) {
            return false;
        }
        if (fkColumns.size() < 2) {
            return false;
        }
        for (String column : allColumns) {
            if (isAuditColumn(column)) {
                continue;
            }
            if (!primaryKeyColumns.contains(column) && !fkColumns.contains(column)) {
                return false;
            }
        }
        return true;
    }

    private List<String> detectRepeatingGroups(
        List<ColumnMetadata> columns,
        Set<String> keyColumns,
        Set<String> fkColumns
    ) {
        if (columns == null || columns.isEmpty()) {
            return List.of();
        }
        Set<String> excludedColumns = new HashSet<>();
        if (keyColumns != null) {
            excludedColumns.addAll(keyColumns);
        }
        if (fkColumns != null) {
            excludedColumns.addAll(fkColumns);
        }
        Map<String, List<ColumnMetadata>> groups = new LinkedHashMap<>();
        for (ColumnMetadata column : columns) {
            if (column.getName() == null) {
                continue;
            }
            String lowered = column.getName().toLowerCase(Locale.ROOT);
            if (excludedColumns.contains(lowered)) {
                continue;
            }
            String base = stripNumericSuffix(column.getName());
            if (base == null || base.length() < 3) {
                continue;
            }
            groups.computeIfAbsent(base, value -> new ArrayList<>()).add(column);
        }
        List<String> issues = new ArrayList<>();
        for (Map.Entry<String, List<ColumnMetadata>> entry : groups.entrySet()) {
            List<ColumnMetadata> group = entry.getValue();
            if (group.size() < 2) {
                continue;
            }
            Map<String, Long> typeCounts = group.stream()
                .map(column -> column.getDataType() != null ? column.getDataType().toLowerCase(Locale.ROOT) : "")
                .filter(type -> !type.isBlank())
                .collect(Collectors.groupingBy(type -> type, Collectors.counting()));
            boolean hasConsistentType = typeCounts.values().stream().anyMatch(count -> count >= 2);
            if (!hasConsistentType) {
                continue;
            }
            String sample = group.stream()
                .map(ColumnMetadata::getName)
                .filter(Objects::nonNull)
                .limit(3)
                .collect(Collectors.joining(", "));
            issues.add("Possible repeating group (" + entry.getKey() + "): " + sample);
        }
        return issues;
    }

    private List<String> detectForeignKeyShadowing(
        List<ColumnMetadata> columns,
        Set<String> fkColumns,
        Set<String> keyColumns
    ) {
        if (columns == null || columns.isEmpty() || fkColumns == null || fkColumns.isEmpty()) {
            return List.of();
        }
        List<String> issues = new ArrayList<>();
        Set<String> lowerColumns = columns.stream()
            .map(ColumnMetadata::getName)
            .filter(Objects::nonNull)
            .map(name -> name.toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());
        for (String fkColumn : fkColumns) {
            String prefix = stripIdSuffix(fkColumn);
            if (prefix == null || prefix.isBlank()) {
                continue;
            }
            String prefixValue = prefix + "_";
            List<String> matches = lowerColumns.stream()
                .filter(name -> name.startsWith(prefixValue))
                .filter(name -> !name.equalsIgnoreCase(fkColumn))
                .filter(name -> keyColumns == null || !keyColumns.contains(name))
                .filter(name -> !isAuditColumn(name))
                .limit(3)
                .toList();
            if (!matches.isEmpty()) {
                issues.add("Potential transitive dependency: " + prefix + " attributes alongside " + fkColumn);
            }
        }
        return issues;
    }

    private String stripNumericSuffix(String value) {
        if (value == null) {
            return null;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.matches(".*_\\d+$")) {
            return lowered.replaceFirst("_\\d+$", "");
        }
        if (lowered.matches(".*\\d+$")) {
            return lowered.replaceFirst("\\d+$", "");
        }
        return null;
    }

    private String stripIdSuffix(String value) {
        if (value == null) {
            return null;
        }
        String lowered = value.toLowerCase(Locale.ROOT);
        if (lowered.endsWith("_id")) {
            return lowered.substring(0, lowered.length() - 3);
        }
        if (lowered.endsWith("id") && lowered.length() > 2) {
            return lowered.substring(0, lowered.length() - 2);
        }
        return null;
    }

    private Set<String> stripAuditColumns(Set<String> columns) {
        if (columns == null || columns.isEmpty()) {
            return Set.of();
        }
        Set<String> filtered = new HashSet<>();
        for (String column : columns) {
            if (!isAuditColumn(column)) {
                filtered.add(column);
            }
        }
        return filtered;
    }

    private boolean isAuditColumn(String columnName) {
        if (columnName == null) {
            return false;
        }
        return AUDIT_COLUMNS.contains(columnName.toLowerCase(Locale.ROOT));
    }

    private static class BcnfAssessment {
        private final int score;
        private final String status;
        private final List<String> issues;

        private BcnfAssessment(int score, String status, List<String> issues) {
            this.score = score;
            this.status = status;
            this.issues = issues;
        }
    }

    private UsageSignals buildUsageSignals(
        String connectionId,
        SchemaMetadata schema,
        Map<String, String> disambiguationMap
    ) {
        Map<String, Integer> tableUsage = new HashMap<>();
        Map<String, Integer> columnUsage = new HashMap<>();
        LocalDateTime since = LocalDateTime.now().minusDays(usageLookbackDays);

        List<QueryLineage> lineage = queryLineageRepository.findByConnectionIdSince(connectionId, since);
        for (QueryLineage entry : lineage) {
            addUsageSignals(entry.getTablesUsed(), entry.getColumnsUsed(), entry.getQueryText(),
                schema, disambiguationMap, tableUsage, columnUsage);
        }

        List<QueryExample> examples = queryExampleRepository.findRecentSuccessful(connectionId, since);
        for (QueryExample example : examples) {
            addUsageSignals(example.getTablesUsed(), example.getColumnsUsed(), example.getSql(),
                schema, disambiguationMap, tableUsage, columnUsage);
        }

        List<AnalysisHistory> analyses = analysisHistoryRepository.findByConnectionIdSince(connectionId, since);
        for (AnalysisHistory analysis : analyses) {
            addUsageSignals(analysis.getTablesUsed(), analysis.getColumnsUsed(), analysis.getQuery(),
                schema, disambiguationMap, tableUsage, columnUsage);
        }

        return new UsageSignals(tableUsage, columnUsage);
    }

    private void addUsageSignals(
        String tablesUsed,
        String columnsUsed,
        String query,
        SchemaMetadata schema,
        Map<String, String> disambiguationMap,
        Map<String, Integer> tableUsage,
        Map<String, Integer> columnUsage
    ) {
        boolean addedTables = addTablesFromList(tablesUsed, tableUsage);
        boolean addedColumns = addColumnsFromList(columnsUsed, columnUsage);
        if (!addedTables && !addedColumns && query != null && !query.isBlank()) {
            try {
                SqlUsage usage = sqlUsageService.parseUsage(query, schema, disambiguationMap);
                addTablesFromSet(usage.getTables(), tableUsage);
                addColumnsFromSet(usage.getColumns(), columnUsage);
            } catch (Exception e) {
                log.debug("Unable to parse usage from query text: {}", e.getMessage());
            }
        }
    }

    private boolean addTablesFromList(String tablesUsed, Map<String, Integer> tableUsage) {
        if (tablesUsed == null || tablesUsed.isBlank()) {
            return false;
        }
        String[] tables = tablesUsed.split(",");
        boolean added = false;
        for (String table : tables) {
            String normalized = normalizeTableName(table);
            if (!normalized.isBlank()) {
                tableUsage.merge(normalized, 1, Integer::sum);
                added = true;
            }
        }
        return added;
    }

    private boolean addColumnsFromList(String columnsUsed, Map<String, Integer> columnUsage) {
        if (columnsUsed == null || columnsUsed.isBlank()) {
            return false;
        }
        String[] columns = columnsUsed.split(",");
        boolean added = false;
        for (String column : columns) {
            String normalized = normalizeColumnKey(column);
            if (!normalized.isBlank()) {
                columnUsage.merge(normalized, 1, Integer::sum);
                added = true;
            }
        }
        return added;
    }

    private void addTablesFromSet(Set<String> tables, Map<String, Integer> tableUsage) {
        if (tables == null || tables.isEmpty()) {
            return;
        }
        for (String table : tables) {
            String normalized = normalizeTableName(table);
            if (!normalized.isBlank()) {
                tableUsage.merge(normalized, 1, Integer::sum);
            }
        }
    }

    private void addColumnsFromSet(Set<String> columns, Map<String, Integer> columnUsage) {
        if (columns == null || columns.isEmpty()) {
            return;
        }
        for (String column : columns) {
            String normalized = normalizeColumnKey(column);
            if (!normalized.isBlank()) {
                columnUsage.merge(normalized, 1, Integer::sum);
            }
        }
    }

    private String normalizeTableName(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String cleaned = trimmed.replaceAll("[`\"]", "");
        int dotIndex = cleaned.lastIndexOf('.');
        if (dotIndex >= 0 && dotIndex < cleaned.length() - 1) {
            cleaned = cleaned.substring(dotIndex + 1);
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private String normalizeColumnKey(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return "";
        }
        String cleaned = trimmed.replaceAll("[`\"]", "");
        String[] parts = cleaned.split("\\.");
        if (parts.length >= 2) {
            String table = parts[parts.length - 2];
            String column = parts[parts.length - 1];
            return (table + "." + column).toLowerCase(Locale.ROOT);
        }
        return cleaned.toLowerCase(Locale.ROOT);
    }

    private TableUnderstandingScore buildTableScore(
        TableMetadata table,
        Map<String, SchemaDocumentation> tableDocs,
        Map<String, Integer> usageCounts,
        Map<String, ColumnProfile> profiles,
        Map<String, Set<String>> foreignKeysByTable
    ) {
        int score = 0;
        List<BrainScoreReason> reasons = new ArrayList<>();
        int columnCount = table.getColumns() != null ? table.getColumns().size() : 0;
        int indexCount = table.getIndexes() != null ? table.getIndexes().size() : 0;
        boolean hasPrimaryKey = table.getColumns() != null &&
            table.getColumns().stream().anyMatch(col -> Boolean.TRUE.equals(col.getPrimaryKey()));
        boolean hasTypes = table.getColumns() != null &&
            table.getColumns().stream().allMatch(col -> col.getDataType() != null && !col.getDataType().isBlank());

        int columnPoints = columnCount > 0 ? 12 : 0;
        score += columnPoints;
        addReason(reasons, "Columns discovered", columnPoints);

        int primaryKeyPoints = hasPrimaryKey ? 8 : 0;
        score += primaryKeyPoints;
        addReason(reasons, "Primary key present", primaryKeyPoints);

        int typePoints = table.getType() != null ? 5 : 0;
        score += typePoints;
        addReason(reasons, "Table type known", typePoints);

        int typeDetailPoints = hasTypes ? 10 : 0;
        score += typeDetailPoints;
        addReason(reasons, "Column types known", typeDetailPoints);

        int rowCountPoints = table.getRowCount() != null ? 10 : 0;
        score += rowCountPoints;
        addReason(reasons, "Row count available", rowCountPoints);

        int sizePoints = table.getSizeBytes() != null ? 8 : 0;
        score += sizePoints;
        addReason(reasons, "Table size known", sizePoints);

        int indexPoints = indexCount > 0 ? 12 : 0;
        score += indexPoints;
        addReason(reasons, "Indexes captured", indexPoints);

        Integer usageCount = usageCounts.getOrDefault(table.getName().toLowerCase(Locale.ROOT), 0);
        int usagePoints = Math.min(USAGE_CAP, usageCount) * 2;
        score += usagePoints;
        if (usagePoints > 0) {
            addReason(reasons, "Usage signals (" + usageCount + ")", usagePoints);
        }

        boolean documented = tableDocs.containsKey(table.getName().toLowerCase(Locale.ROOT));
        int docPoints = documented ? 10 : 0;
        score += docPoints;
        addReason(reasons, "Documentation added", docPoints);

        int profiledColumns = countProfiledColumns(table, profiles);
        int profilePoints = columnCount > 0
            ? (int) Math.round((double) profiledColumns / columnCount * 12)
            : 0;
        score += profilePoints;
        if (profilePoints > 0) {
            addReason(reasons, "Profiled columns (" + profiledColumns + ")", profilePoints);
        }

        Set<String> fkColumns = foreignKeysByTable.getOrDefault(
            table.getName() != null ? table.getName().toLowerCase(Locale.ROOT) : "",
            Set.of()
        );
        BcnfAssessment bcnfAssessment = assessBcnf(table, fkColumns);

        score = Math.min(score, 100);

        return TableUnderstandingScore.builder()
            .tableName(table.getName())
            .schemaName(table.getSchema())
            .type(table.getType())
            .score(score)
            .columnCount(columnCount)
            .indexCount(indexCount)
            .rowCount(table.getRowCount())
            .sizeBytes(table.getSizeBytes())
            .usageCount(usageCount)
            .documented(documented)
            .hasPrimaryKey(hasPrimaryKey)
            .profiledColumns(profiledColumns)
            .bcnfScore(bcnfAssessment.score)
            .bcnfStatus(bcnfAssessment.status)
            .bcnfIssues(bcnfAssessment.issues)
            .scoreReasons(reasons)
            .build();
    }

    private ColumnUnderstandingScore buildColumnScore(
        TableMetadata table,
        ColumnMetadata column,
        Map<String, SchemaDocumentation> columnDocs,
        Map<String, Integer> columnNameCounts,
        Map<String, List<String>> columnTableMap,
        Map<String, Integer> columnUsageCounts,
        Map<String, String> disambiguationMap,
        ColumnProfile profile
    ) {
        int score = 0;
        List<BrainScoreReason> reasons = new ArrayList<>();
        boolean documented = columnDocs.containsKey(
            (table.getName() + "." + column.getName()).toLowerCase(Locale.ROOT)
        );
        boolean indexed = table.getIndexes() != null && table.getIndexes().stream()
            .anyMatch(idx -> idx.getColumns().stream()
                .anyMatch(col -> col.equalsIgnoreCase(column.getName())));

        int dataTypePoints = column.getDataType() != null ? 25 : 0;
        score += dataTypePoints;
        addReason(reasons, "Data type known", dataTypePoints);

        int nullablePoints = column.getNullable() != null ? 10 : 0;
        score += nullablePoints;
        addReason(reasons, "Nullability known", nullablePoints);

        int defaultPoints = column.getDefaultValue() != null ? 5 : 0;
        score += defaultPoints;
        addReason(reasons, "Default value known", defaultPoints);

        int pkPoints = Boolean.TRUE.equals(column.getPrimaryKey()) ? 15 : 0;
        score += pkPoints;
        addReason(reasons, "Primary key column", pkPoints);

        int indexPoints = indexed ? 10 : 0;
        score += indexPoints;
        addReason(reasons, "Indexed column", indexPoints);

        int docPoints = documented ? 15 : 0;
        score += docPoints;
        addReason(reasons, "Documentation added", docPoints);

        String columnKey = column.getName().toLowerCase(Locale.ROOT);
        int ambiguityCount = columnNameCounts.getOrDefault(columnKey, 1);
        int ambiguityPoints = ambiguityScore(ambiguityCount);
        score += ambiguityPoints;
        addReason(reasons, "Column name uniqueness", ambiguityPoints);

        int usageCount = columnUsageCounts.getOrDefault(keyFor(table.getName(), column.getName()), 0);
        int usagePoints = Math.min(USAGE_CAP, usageCount);
        score += usagePoints;
        if (usagePoints > 0) {
            addReason(reasons, "Usage signals (" + usageCount + ")", usagePoints);
        }

        if (profile != null && profile.getProfiledAt() != null) {
            score += 10;
            addReason(reasons, "Profiled column", 10);
            int distinctPoints = profile.getDistinctCount() != null ? 4 : 0;
            score += distinctPoints;
            addReason(reasons, "Distinct count available", distinctPoints);
            int nullPoints = profile.getNullCount() != null ? 3 : 0;
            score += nullPoints;
            addReason(reasons, "Null count available", nullPoints);
        }

        List<String> ambiguousTables = columnTableMap.getOrDefault(columnKey, List.of());
        String preferredTable = disambiguationMap.get(columnKey);
        if (preferredTable != null && !ambiguousTables.isEmpty()) {
            for (String tableName : ambiguousTables) {
                if (tableName.equalsIgnoreCase(preferredTable)) {
                    preferredTable = tableName;
                    break;
                }
            }
        }
        boolean ambiguityResolved = preferredTable != null;
        if (ambiguityResolved && ambiguityCount > 1) {
            score += 5;
            addReason(reasons, "Ambiguity resolved", 5);
        }

        score = Math.min(score, 100);
        Double nullPercent = null;
        if (profile != null && profile.getTotalRows() != null && profile.getNullCount() != null &&
            profile.getTotalRows() > 0) {
            nullPercent = (profile.getNullCount() * 100.0) / profile.getTotalRows();
        }

        return ColumnUnderstandingScore.builder()
            .tableName(table.getName())
            .columnName(column.getName())
            .score(score)
            .dataType(column.getDataType())
            .nullable(column.getNullable())
            .primaryKey(column.getPrimaryKey())
            .indexed(indexed)
            .documented(documented)
            .usageCount(usageCount)
            .ambiguityCount(ambiguityCount)
            .ambiguousTables(ambiguityCount > 1 ? ambiguousTables : List.of())
            .ambiguityResolved(ambiguityResolved)
            .preferredTable(preferredTable)
            .totalRows(profile != null ? profile.getTotalRows() : null)
            .nullCount(profile != null ? profile.getNullCount() : null)
            .nullPercent(nullPercent)
            .distinctCount(profile != null ? profile.getDistinctCount() : null)
            .minValue(profile != null ? profile.getMinValue() : null)
            .maxValue(profile != null ? profile.getMaxValue() : null)
            .sampleValue(profile != null ? profile.getSampleValue() : null)
            .profiledAt(profile != null ? profile.getProfiledAt() : null)
            .scoreReasons(reasons)
            .build();
    }

    private int ambiguityScore(int count) {
        if (count <= 1) {
            return 20;
        }
        if (count <= 3) {
            return 12;
        }
        if (count <= 6) {
            return 6;
        }
        return 0;
    }

    private int computeOverallScore(List<TableUnderstandingScore> tables) {
        if (tables.isEmpty()) {
            return 0;
        }

        double weightedSum = 0;
        double weightTotal = 0;
        for (TableUnderstandingScore table : tables) {
            double weight = table.getRowCount() != null && table.getRowCount() > 0
                ? table.getRowCount()
                : 1;
            weightedSum += (table.getScore() != null ? table.getScore() : 0) * weight;
            weightTotal += weight;
        }
        if (weightTotal <= 0) {
            return 0;
        }
        return (int) Math.round(weightedSum / weightTotal);
    }

    private Map<String, ColumnProfile> buildColumnProfileMap(String connectionId) {
        List<ColumnProfile> profiles = columnProfileRepository.findByConnectionId(connectionId);
        Map<String, ColumnProfile> map = new HashMap<>();
        for (ColumnProfile profile : profiles) {
            map.put(keyFor(profile.getTableName(), profile.getColumnName()), profile);
        }
        return map;
    }

    private int countProfiledColumns(TableMetadata table, Map<String, ColumnProfile> profiles) {
        if (table.getColumns() == null) {
            return 0;
        }
        int count = 0;
        for (ColumnMetadata column : table.getColumns()) {
            if (profiles.containsKey(keyFor(table.getName(), column.getName()))) {
                count++;
            }
        }
        return count;
    }

    private void addReason(List<BrainScoreReason> reasons, String label, int points) {
        if (points == 0 || label == null || label.isBlank()) {
            return;
        }
        reasons.add(BrainScoreReason.builder()
            .label(label)
            .points(points)
            .build());
    }

    private String keyFor(String tableName, String columnName) {
        if (tableName == null || columnName == null) {
            return "";
        }
        return tableName.toLowerCase(Locale.ROOT) + "." + columnName.toLowerCase(Locale.ROOT);
    }

    private Map<String, String> buildDisambiguationMap(String connectionId) {
        return columnDisambiguationRepository.findByConnectionId(connectionId).stream()
            .filter(entry -> entry.getColumnName() != null && entry.getPreferredTable() != null)
            .collect(Collectors.toMap(
                entry -> entry.getColumnName().toLowerCase(Locale.ROOT),
                entry -> entry.getPreferredTable().toLowerCase(Locale.ROOT),
                (a, b) -> a
            ));
    }

    private BrainSchemaDrift buildSchemaDrift(
        String connectionId,
        SchemaMetadata currentSchema,
        SchemaSnapshot latestSnapshot,
        boolean schemaFromSnapshot
    ) {
        if (latestSnapshot == null) {
            return null;
        }

        if (schemaFromSnapshot) {
            List<SchemaSnapshot> snapshots = schemaSnapshotRepository
                .findTop2ByConnectionIdOrderByCapturedAtDesc(connectionId);
            if (snapshots.size() < 2) {
                return BrainSchemaDrift.builder()
                    .lastSnapshotAt(latestSnapshot.getCapturedAt())
                    .build();
            }

            SchemaMetadata previousSchema = parseSchemaSnapshot(snapshots.get(1));
            if (previousSchema == null) {
                return BrainSchemaDrift.builder()
                    .lastSnapshotAt(latestSnapshot.getCapturedAt())
                    .build();
            }
            return computeSchemaDrift(previousSchema, currentSchema, latestSnapshot.getCapturedAt());
        }

        SchemaMetadata snapshotSchema = parseSchemaSnapshot(latestSnapshot);
        if (snapshotSchema == null) {
            return BrainSchemaDrift.builder()
                .lastSnapshotAt(latestSnapshot.getCapturedAt())
                .build();
        }
        return computeSchemaDrift(snapshotSchema, currentSchema, latestSnapshot.getCapturedAt());
    }

    private BrainSchemaDrift computeSchemaDrift(
        SchemaMetadata baseSchema,
        SchemaMetadata currentSchema,
        LocalDateTime snapshotAt
    ) {
        Set<String> currentTables = buildTableKeys(currentSchema);
        Set<String> previousTables = buildTableKeys(baseSchema);

        Set<String> tablesAdded = new TreeSet<>(currentTables);
        tablesAdded.removeAll(previousTables);
        Set<String> tablesRemoved = new TreeSet<>(previousTables);
        tablesRemoved.removeAll(currentTables);

        Set<String> currentColumns = buildColumnKeys(currentSchema);
        Set<String> previousColumns = buildColumnKeys(baseSchema);

        Set<String> columnsAdded = new TreeSet<>(currentColumns);
        columnsAdded.removeAll(previousColumns);
        Set<String> columnsRemoved = new TreeSet<>(previousColumns);
        columnsRemoved.removeAll(currentColumns);

        int driftMagnitude = tablesAdded.size() + tablesRemoved.size() + columnsAdded.size() + columnsRemoved.size();
        int scoreImpact = driftMagnitude == 0 ? 0 : -Math.min(25, driftMagnitude);

        return BrainSchemaDrift.builder()
            .lastSnapshotAt(snapshotAt)
            .tablesAdded(tablesAdded.size())
            .tablesRemoved(tablesRemoved.size())
            .columnsAdded(columnsAdded.size())
            .columnsRemoved(columnsRemoved.size())
            .scoreImpact(scoreImpact)
            .sampleTablesAdded(limitSamples(tablesAdded, DRIFT_SAMPLE_LIMIT))
            .sampleTablesRemoved(limitSamples(tablesRemoved, DRIFT_SAMPLE_LIMIT))
            .sampleColumnsAdded(limitSamples(columnsAdded, DRIFT_SAMPLE_LIMIT))
            .sampleColumnsRemoved(limitSamples(columnsRemoved, DRIFT_SAMPLE_LIMIT))
            .build();
    }

    private SchemaMetadata parseSchemaSnapshot(SchemaSnapshot snapshot) {
        if (snapshot == null || snapshot.getSchemaJson() == null) {
            return null;
        }
        try {
            return objectMapper.readValue(snapshot.getSchemaJson(), SchemaMetadata.class);
        } catch (Exception e) {
            log.warn("Failed to parse schema snapshot {}", snapshot.getId(), e);
            return null;
        }
    }

    private Set<String> buildTableKeys(SchemaMetadata schema) {
        Set<String> keys = new HashSet<>();
        if (schema == null || schema.getTables() == null) {
            return keys;
        }
        for (TableMetadata table : schema.getTables()) {
            String tableKey = buildTableKey(table);
            if (!tableKey.isBlank()) {
                keys.add(tableKey);
            }
        }
        return keys;
    }

    private Set<String> buildColumnKeys(SchemaMetadata schema) {
        Set<String> keys = new HashSet<>();
        if (schema == null || schema.getTables() == null) {
            return keys;
        }
        for (TableMetadata table : schema.getTables()) {
            if (table.getColumns() == null) {
                continue;
            }
            String tableKey = buildTableKey(table);
            if (tableKey.isBlank()) {
                continue;
            }
            for (ColumnMetadata column : table.getColumns()) {
                if (column.getName() == null || column.getName().isBlank()) {
                    continue;
                }
                keys.add(tableKey + "." + column.getName().toLowerCase(Locale.ROOT));
            }
        }
        return keys;
    }

    private String buildTableKey(TableMetadata table) {
        if (table == null || table.getName() == null) {
            return "";
        }
        String schemaName = table.getSchema();
        String tableName = table.getName();
        String fullName = (schemaName != null && !schemaName.isBlank())
            ? schemaName + "." + tableName
            : tableName;
        return fullName.toLowerCase(Locale.ROOT);
    }

    private List<String> limitSamples(Set<String> items, int limit) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream().limit(limit).toList();
    }

    private List<BrainScoreSnapshot> loadScoreHistory(
        String connectionId,
        int overallScore,
        int tableCount,
        int columnCount
    ) {
        LocalDateTime now = LocalDateTime.now();
        Optional<BrainScoreSnapshot> latestOpt = brainScoreSnapshotRepository
            .findTopByConnectionIdOrderByCreatedAtDesc(connectionId);

        boolean shouldCreate = latestOpt.isEmpty();
        if (latestOpt.isPresent()) {
            BrainScoreSnapshot latest = latestOpt.get();
            long minutes = Duration.between(latest.getCreatedAt(), now).toMinutes();
            int delta = Math.abs(overallScore - latest.getOverallScore());
            boolean countDelta = !Objects.equals(latest.getTableCount(), tableCount) ||
                !Objects.equals(latest.getColumnCount(), columnCount);
            boolean enoughTime = minutes >= Math.max(0, snapshotIntervalMinutes);
            boolean meaningfulDelta = delta >= Math.max(0, snapshotMinDelta) || countDelta;
            shouldCreate = enoughTime && meaningfulDelta;
        }

        if (shouldCreate) {
            BrainScoreSnapshot snapshot = BrainScoreSnapshot.builder()
                .connectionId(connectionId)
                .overallScore(overallScore)
                .tableCount(tableCount)
                .columnCount(columnCount)
                .createdAt(now)
                .build();
            brainScoreSnapshotRepository.save(snapshot);
        }

        return brainScoreSnapshotRepository.findByConnectionIdOrderByCreatedAtDesc(
            connectionId,
            PageRequest.of(0, historyLimit)
        );
    }

    private BrainTrend computeTrend(List<BrainScoreSnapshot> history) {
        if (history == null || history.size() < 2) {
            return new BrainTrend(0, "steady");
        }
        BrainScoreSnapshot latest = history.get(0);
        BrainScoreSnapshot previous = history.get(1);
        int delta = latest.getOverallScore() - previous.getOverallScore();
        String direction = delta > 0 ? "up" : (delta < 0 ? "down" : "steady");
        return new BrainTrend(delta, direction);
    }

    private List<BrainAlert> buildAlerts(BrainTrend trend) {
        List<BrainAlert> alerts = new ArrayList<>();
        int delta = trend.delta();
        if ("down".equals(trend.direction())) {
            int drop = Math.abs(delta);
            if (drop >= SCORE_DROP_CRITICAL) {
                alerts.add(BrainAlert.builder()
                    .type("SCORE_DROP")
                    .severity("CRITICAL")
                    .message("Understanding score dropped by " + drop + " points.")
                    .build());
            } else if (drop >= SCORE_DROP_WARNING) {
                alerts.add(BrainAlert.builder()
                    .type("SCORE_DROP")
                    .severity("WARNING")
                    .message("Understanding score dropped by " + drop + " points.")
                    .build());
            }
        }
        return alerts;
    }

    private record UsageSignals(Map<String, Integer> tableUsage, Map<String, Integer> columnUsage) {}

    private record BrainTrend(int delta, String direction) {}
}
