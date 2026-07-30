package com.dbaagent.service;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.ColumnProfile;
import com.dbaagent.model.ColumnValueCache;
import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.QueryExample;
import com.dbaagent.model.RelationshipMetadata;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.TableRelationshipClassification;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SemanticJoinModelRepository;
import com.dbaagent.repository.SemanticTableModelRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.TableRelationshipClassificationRepository;
import com.dbaagent.util.PromptIntentSignals;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SemanticModelService {

    private static final TypeReference<List<String>> STRING_LIST_TYPE = new TypeReference<>() {};
    private static final Set<String> METRIC_NAME_HINTS = Set.of(
        "amount", "revenue", "price", "total", "subtotal", "tax", "fee",
        "count", "qty", "quantity", "score", "rate", "cost", "balance"
    );
    private static final Set<String> DIMENSION_NAME_HINTS = Set.of(
        "status", "type", "category", "channel", "source", "country",
        "state", "city", "segment", "brand", "name"
    );
    private static final Set<String> TIME_NAME_HINTS = Set.of(
        "created", "updated", "modified", "date", "time", "timestamp",
        "occurred", "booked", "paid", "cancel", "processed"
    );

    private final SchemaScannerService schemaScannerService;
    private final TableClassificationRepository tableClassificationRepository;
    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final ColumnValueCacheRepository columnValueCacheRepository;
    private final CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final TableRelationshipClassificationRepository tableRelationshipClassificationRepository;
    private final QueryExampleRepository queryExampleRepository;
    private final SemanticTableModelRepository semanticTableModelRepository;
    private final SemanticJoinModelRepository semanticJoinModelRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public SemanticModelBuildSummary rebuildSemanticModel(String connectionId) {
        SchemaMetadata schema;
        try {
            schema = schemaScannerService.scanSchema(connectionId);
        } catch (SQLException e) {
            throw new RuntimeException("Failed to scan schema while building semantic model", e);
        }

        List<TableClassification> classifications = tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId);
        Map<String, TableClassification> classificationByTable = buildClassificationAliasIndex(classifications);

        List<SchemaDocumentation> docs = schemaDocumentationRepository.findByConnectionId(connectionId);
        List<CompanyKnowledgeEntry> companyKnowledgeEntries = companyKnowledgeEntryRepository.findByConnectionId(connectionId);
        List<SchemaDocumentation> tableDocs = docs.stream()
            .filter(doc -> doc != null && doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE)
            .sorted(Comparator.comparingInt(this::documentationPriority)
                .thenComparing(doc -> safeString(doc.getObjectName()), String.CASE_INSENSITIVE_ORDER))
            .toList();
        List<SchemaDocumentation> columnDocs = docs.stream()
            .filter(doc -> doc != null
                && doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN
                && notBlank(doc.getParentObject()))
            .sorted(Comparator.comparingInt(this::documentationPriority)
                .thenComparing(doc -> safeString(doc.getParentObject()), String.CASE_INSENSITIVE_ORDER)
                .thenComparing(doc -> safeString(doc.getObjectName()), String.CASE_INSENSITIVE_ORDER))
            .toList();

        Map<String, List<KeyColumnAnalysis>> keyColumnsByTable = keyColumnAnalysisRepository
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId)
            .stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(item -> normalize(item.getTableName())));

        Map<String, List<ColumnProfile>> profilesByTable = columnProfileRepository.findByConnectionId(connectionId)
            .stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(item -> normalize(item.getTableName())));

        Map<String, List<ColumnValueCache>> valuesByTable = columnValueCacheRepository
            .findByConnectionIdAndIsLowCardinalityTrue(connectionId)
            .stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(item -> normalize(item.getTableName())));

        List<SemanticTableModel> semanticTables = new ArrayList<>();
        int tablesWithDocs = 0;
        int tablesWithTimeColumns = 0;

        for (TableMetadata table : safeTables(schema)) {
            String normalizedTable = normalize(table.getName());
            TableClassification classification = resolveClassification(classificationByTable, table);
            SchemaDocumentation tableDoc = resolveBestTableDocumentation(tableDocs, table);
            List<SchemaDocumentation> matchedColumnDocs = resolveColumnDocumentation(columnDocs, table);
            List<KeyColumnAnalysis> keyColumns = keyColumnsByTable.getOrDefault(normalizedTable, List.of());
            List<ColumnProfile> profiles = profilesByTable.getOrDefault(normalizedTable, List.of());
            List<ColumnValueCache> valueCaches = valuesByTable.getOrDefault(normalizedTable, List.of());
            List<CompanyKnowledgeEntry> linkedKnowledge = resolveCompanyKnowledgeEntries(companyKnowledgeEntries, table);
            List<String> keyColumnNames = deriveKeyColumns(table, keyColumns);
            List<Map<String, Object>> temporalSemantics = deriveTemporalSemantics(table, classification, tableDoc, matchedColumnDocs);
            List<String> timeColumns = deriveTimeColumns(temporalSemantics);
            List<Map<String, Object>> filterColumns = deriveFilterColumns(table, classification, valueCaches, profiles, matchedColumnDocs);
            String businessDescription = deriveBusinessDescription(
                tableDoc, matchedColumnDocs, table, keyColumnNames, timeColumns, linkedKnowledge);
            String businessTerms = deriveBusinessTerms(
                tableDoc, matchedColumnDocs, timeColumns, filterColumns, linkedKnowledge);

            SemanticTableModel semanticTable = SemanticTableModel.builder()
                .connectionId(connectionId)
                .tableName(table.getName())
                .tableRole(classification != null ? classification.getTableRole() : null)
                .businessDomain(classification != null ? classification.getBusinessDomain() : null)
                .businessDescription(businessDescription)
                .grainDescription(deriveGrainDescription(table, classification, keyColumns))
                .keyColumns(keyColumnNames)
                .timeColumns(timeColumns)
                .temporalSemantics(temporalSemantics)
                .dimensionColumns(deriveDimensionColumns(table, profiles, valueCaches))
                .filterColumns(filterColumns)
                .metricColumns(deriveMetricColumns(table, keyColumns))
                .businessTerms(businessTerms)
                .confidenceScore(deriveConfidence(tableDoc, classification, keyColumns, valueCaches, linkedKnowledge))
                .sourceSummary(buildSourceSummary(tableDoc, classification, keyColumns, valueCaches, linkedKnowledge))
                .build();
            semanticTables.add(semanticTable);

            if (businessDescription != null && !businessDescription.isBlank()) {
                tablesWithDocs++;
            }
            if (semanticTable.getTimeColumns() != null && !semanticTable.getTimeColumns().isEmpty()) {
                tablesWithTimeColumns++;
            }
        }

        List<SemanticJoinModel> semanticJoins = buildSemanticJoins(connectionId, schema);
        long verifiedPatterns = queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue(connectionId).size();

        semanticJoinModelRepository.deleteByConnectionId(connectionId);
        semanticTableModelRepository.deleteByConnectionId(connectionId);
        semanticTableModelRepository.saveAll(semanticTables);
        semanticJoinModelRepository.saveAll(semanticJoins);

        log.info("Built semantic model for {}: {} tables, {} joins, {} verified patterns",
            connectionId, semanticTables.size(), semanticJoins.size(), verifiedPatterns);

        return new SemanticModelBuildSummary(
            semanticTables.size(),
            semanticJoins.size(),
            tablesWithDocs,
            tablesWithTimeColumns,
            (int) verifiedPatterns
        );
    }

    public String buildSemanticModelContext(String connectionId, String question, Set<String> focusTables) {
        List<SemanticTableModel> allTables = semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc(connectionId);
        if (allTables.isEmpty()) {
            return "";
        }

        List<SemanticTableModel> relevantTables = selectRelevantTables(connectionId, allTables, question, focusTables);
        List<SemanticJoinModel> joins = loadRelevantJoins(connectionId, relevantTables);
        List<QueryExample> queryPatterns = findRelevantQueryPatterns(connectionId, relevantTables);
        List<CompanyKnowledgeEntry> relevantKnowledge = findRelevantCompanyKnowledge(connectionId, question, relevantTables);

        StringBuilder sb = new StringBuilder();
        sb.append("=== SEMANTIC MODEL ===\n");
        sb.append("Relevant tables:\n");
        for (SemanticTableModel table : relevantTables) {
            sb.append("- ").append(table.getTableName());
            if (notBlank(table.getTableRole()) || notBlank(table.getBusinessDomain())) {
                sb.append(" [");
                List<String> tags = new ArrayList<>();
                if (notBlank(table.getTableRole())) {
                    tags.add("role=" + table.getTableRole());
                }
                if (notBlank(table.getBusinessDomain())) {
                    tags.add("domain=" + table.getBusinessDomain());
                }
                sb.append(String.join(", ", tags)).append("]");
            }
            sb.append("\n");
            if (notBlank(table.getGrainDescription())) {
                sb.append("  grain: ").append(table.getGrainDescription()).append("\n");
            }
            if (notBlank(table.getBusinessDescription())) {
                sb.append("  meaning: ").append(table.getBusinessDescription()).append("\n");
            }
            if (notBlank(table.getBusinessTerms())) {
                sb.append("  aliases: ").append(table.getBusinessTerms()).append("\n");
            }
            if (!isEmpty(table.getKeyColumns())) {
                sb.append("  key columns: ").append(String.join(", ", table.getKeyColumns())).append("\n");
            }
            if (!isEmpty(table.getTimeColumns())) {
                sb.append("  preferred time columns: ").append(String.join(", ", table.getTimeColumns())).append("\n");
            }
            if (!isEmpty(table.getTemporalSemantics())) {
                sb.append("  temporal meanings: ").append(formatTemporalSemantics(table.getTemporalSemantics())).append("\n");
            }
            if (!isEmpty(table.getMetricColumns())) {
                sb.append("  likely metrics: ").append(String.join(", ", table.getMetricColumns())).append("\n");
            }
            if (!isEmpty(table.getFilterColumns())) {
                sb.append("  common filters: ").append(formatFilterColumns(table.getFilterColumns())).append("\n");
            }
        }

        if (!joins.isEmpty()) {
            sb.append("Preferred joins:\n");
            for (SemanticJoinModel join : joins) {
                sb.append("- ").append(join.getJoinExpression());
                List<String> attrs = new ArrayList<>();
                if (notBlank(join.getRelationshipType())) {
                    attrs.add(join.getRelationshipType());
                }
                if (notBlank(join.getEvidenceSource())) {
                    attrs.add(join.getEvidenceSource());
                }
                if (join.getPreferred() != null && join.getPreferred()) {
                    attrs.add("preferred");
                }
                if (!attrs.isEmpty()) {
                    sb.append(" [").append(String.join(", ", attrs)).append("]");
                }
                sb.append("\n");
            }
        }

        if (!queryPatterns.isEmpty()) {
            sb.append("Approved query patterns:\n");
            for (QueryExample example : queryPatterns) {
                sb.append("- ").append(example.getNaturalLanguage()).append("\n");
            }
        }

        if (!relevantKnowledge.isEmpty()) {
            sb.append("Company knowledge:\n");
            for (CompanyKnowledgeEntry entry : relevantKnowledge) {
                sb.append("- ").append(entry.getTitle());
                if (entry.getEntryType() != null) {
                    sb.append(" [").append(entry.getEntryType().name()).append("]");
                }
                sb.append(": ").append(compact(entry.getContent(), 220)).append("\n");
                if (entry.getLinkedTables() != null && !entry.getLinkedTables().isEmpty()) {
                    sb.append("  linked tables: ").append(String.join(", ", entry.getLinkedTables())).append("\n");
                }
            }
        }

        return sb.toString();
    }

    public String buildSemanticHints(String connectionId, Collection<String> resolvedTables) {
        if (resolvedTables == null || resolvedTables.isEmpty()) {
            return "";
        }

        Set<String> normalizedTables = resolvedTables.stream()
            .filter(Objects::nonNull)
            .map(this::normalize)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (normalizedTables.isEmpty()) {
            return "";
        }

        List<SemanticTableModel> tables = semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc(connectionId)
            .stream()
            .filter(table -> normalizedTables.contains(normalize(table.getTableName())))
            .toList();
        if (tables.isEmpty()) {
            return "";
        }

        List<SemanticJoinModel> joins = loadRelevantJoins(connectionId, tables);
        StringBuilder sb = new StringBuilder();
        sb.append("Semantic hints:\n");
        for (SemanticTableModel table : tables.stream().limit(4).toList()) {
            if (notBlank(table.getGrainDescription())) {
                sb.append("- ").append(table.getTableName()).append(" grain: ")
                    .append(table.getGrainDescription()).append("\n");
            }
            if (notBlank(table.getBusinessDescription())) {
                sb.append("- ").append(table.getTableName()).append(" meaning: ")
                    .append(table.getBusinessDescription()).append("\n");
            }
            if (notBlank(table.getBusinessTerms())) {
                sb.append("- ").append(table.getTableName()).append(" aliases: ")
                    .append(table.getBusinessTerms()).append("\n");
            }
            if (!isEmpty(table.getTimeColumns())) {
                sb.append("- ").append(table.getTableName()).append(" time columns: ")
                    .append(String.join(", ", table.getTimeColumns())).append("\n");
            }
            if (!isEmpty(table.getTemporalSemantics())) {
                sb.append("- ").append(table.getTableName()).append(" temporal meanings: ")
                    .append(formatTemporalSemantics(table.getTemporalSemantics())).append("\n");
            }
        }
        for (SemanticJoinModel join : joins.stream().limit(4).toList()) {
            sb.append("- preferred join: ").append(join.getJoinExpression()).append("\n");
        }
        return sb.toString();
    }

    public List<SemanticJoinModel> getSemanticJoins(String connectionId, List<String> mentionedTables) {
        List<SemanticJoinModel> joins = semanticJoinModelRepository
            .findByConnectionIdOrderByPreferredDescConfidenceScoreDescSourceTableAsc(connectionId);
        if (mentionedTables == null || mentionedTables.isEmpty()) {
            return joins;
        }
        Set<String> normalized = mentionedTables.stream()
            .filter(Objects::nonNull)
            .map(this::normalize)
            .collect(Collectors.toSet());
        return joins.stream()
            .filter(join -> normalized.contains(normalize(join.getSourceTable()))
                || normalized.contains(normalize(join.getTargetTable())))
            .toList();
    }

    public List<SemanticTableModel> getSemanticTables(String connectionId, List<String> mentionedTables) {
        List<SemanticTableModel> tables = semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc(connectionId);
        if (mentionedTables == null || mentionedTables.isEmpty()) {
            return tables;
        }
        Set<String> normalized = mentionedTables.stream()
            .filter(Objects::nonNull)
            .map(this::normalize)
            .collect(Collectors.toSet());
        return tables.stream()
            .filter(table -> normalized.contains(normalize(table.getTableName())))
            .toList();
    }

    public List<SemanticTableModel> findRelevantTables(String connectionId, String question, Set<String> focusTables) {
        List<SemanticTableModel> allTables = semanticTableModelRepository.findByConnectionIdOrderByTableNameAsc(connectionId);
        if (allTables.isEmpty()) {
            return List.of();
        }
        return selectRelevantTables(connectionId, allTables, question, focusTables);
    }

    private List<SemanticJoinModel> buildSemanticJoins(String connectionId, SchemaMetadata schema) {
        Map<String, SemanticJoinModel> joins = new LinkedHashMap<>();

        for (RelationshipMetadata relationship : safeRelationships(schema)) {
            if (relationship == null || !notBlank(relationship.getFromTable()) || !notBlank(relationship.getToTable())
                    || !notBlank(relationship.getFromColumn()) || !notBlank(relationship.getToColumn())) {
                continue;
            }
            upsertJoin(joins, SemanticJoinModel.builder()
                .connectionId(connectionId)
                .sourceTable(relationship.getFromTable())
                .sourceColumn(relationship.getFromColumn())
                .targetTable(relationship.getToTable())
                .targetColumn(relationship.getToColumn())
                .relationshipType("FOREIGN_KEY")
                .evidenceSource("FOREIGN_KEY")
                .joinExpression(joinExpression(relationship.getFromTable(), relationship.getFromColumn(),
                    relationship.getToTable(), relationship.getToColumn()))
                .preferred(true)
                .confidenceScore(BigDecimal.valueOf(99))
                .build());
        }

        for (TableRelationshipClassification classification : tableRelationshipClassificationRepository
                .findLatestByConnectionIdOrderBySourceTableAsc(connectionId)) {
            if (classification == null || !notBlank(classification.getSourceTable()) || !notBlank(classification.getTargetTable())
                    || !notBlank(classification.getSourceColumn()) || !notBlank(classification.getTargetColumn())) {
                continue;
            }
            BigDecimal confidence = classification.getConfidenceScore() != null
                ? classification.getConfidenceScore()
                : new BigDecimal("75");
            String evidenceSource = "CLASSIFIED_" + safeUpper(classification.getStrength(), "UNKNOWN");
            upsertJoin(joins, SemanticJoinModel.builder()
                .connectionId(connectionId)
                .sourceTable(classification.getSourceTable())
                .sourceColumn(classification.getSourceColumn())
                .targetTable(classification.getTargetTable())
                .targetColumn(classification.getTargetColumn())
                .relationshipType(classification.getRelationshipType())
                .evidenceSource(evidenceSource)
                .joinExpression(joinExpression(classification.getSourceTable(), classification.getSourceColumn(),
                    classification.getTargetTable(), classification.getTargetColumn()))
                .preferred("STRONG".equalsIgnoreCase(classification.getStrength()))
                .confidenceScore(confidence)
                .build());
        }

        for (InferredTableRelationship inferred : inferredTableRelationshipRepository
                .findByConnectionIdOrderByConfidenceScoreDesc(connectionId)) {
            if (inferred == null || !notBlank(inferred.getSourceTable()) || !notBlank(inferred.getTargetTable())
                    || !notBlank(inferred.getSourceColumn()) || !notBlank(inferred.getTargetColumn())) {
                continue;
            }
            BigDecimal confidence = inferred.getConfidenceScore() != null
                ? inferred.getConfidenceScore()
                : new BigDecimal("60");
            upsertJoin(joins, SemanticJoinModel.builder()
                .connectionId(connectionId)
                .sourceTable(inferred.getSourceTable())
                .sourceColumn(inferred.getSourceColumn())
                .targetTable(inferred.getTargetTable())
                .targetColumn(inferred.getTargetColumn())
                .relationshipType(inferred.getCardinality())
                .evidenceSource("INFERRED")
                .joinExpression(joinExpression(inferred.getSourceTable(), inferred.getSourceColumn(),
                    inferred.getTargetTable(), inferred.getTargetColumn()))
                .preferred(false)
                .confidenceScore(confidence)
                .build());
        }

        return new ArrayList<>(joins.values());
    }

    private void upsertJoin(Map<String, SemanticJoinModel> joins, SemanticJoinModel candidate) {
        String key = normalize(candidate.getSourceTable()) + "|" + normalize(candidate.getSourceColumn())
            + "|" + normalize(candidate.getTargetTable()) + "|" + normalize(candidate.getTargetColumn());
        SemanticJoinModel existing = joins.get(key);
        if (existing == null || compareJoinPriority(candidate, existing) > 0) {
            joins.put(key, candidate);
        }
    }

    private int compareJoinPriority(SemanticJoinModel left, SemanticJoinModel right) {
        int leftPriority = evidencePriority(left.getEvidenceSource(), Boolean.TRUE.equals(left.getPreferred()));
        int rightPriority = evidencePriority(right.getEvidenceSource(), Boolean.TRUE.equals(right.getPreferred()));
        if (leftPriority != rightPriority) {
            return Integer.compare(leftPriority, rightPriority);
        }
        BigDecimal leftConfidence = left.getConfidenceScore() != null ? left.getConfidenceScore() : BigDecimal.ZERO;
        BigDecimal rightConfidence = right.getConfidenceScore() != null ? right.getConfidenceScore() : BigDecimal.ZERO;
        return leftConfidence.compareTo(rightConfidence);
    }

    private int evidencePriority(String evidenceSource, boolean preferred) {
        int base = switch (safeUpper(evidenceSource, "")) {
            case "FOREIGN_KEY" -> 4;
            case "CLASSIFIED_STRONG" -> 3;
            case "CLASSIFIED_INFERRED" -> 2;
            case "INFERRED" -> 1;
            default -> 0;
        };
        return preferred ? base + 1 : base;
    }

    private List<SemanticTableModel> selectRelevantTables(
            String connectionId,
            List<SemanticTableModel> allTables,
            String question,
            Set<String> focusTables) {
        Set<String> normalizedFocus = new LinkedHashSet<>();
        if (focusTables != null) {
            focusTables.stream().filter(Objects::nonNull).map(this::normalize).forEach(normalizedFocus::add);
        }

        String normalizedQuestion = normalizeQuestion(question);
        Map<String, Integer> queryExampleBoosts = buildQueryExampleBoosts(connectionId, question);
        Map<String, Integer> preferredJoinBoosts = buildPreferredJoinBoosts(connectionId, normalizedFocus);
        Map<String, SemanticTableModel> bestByCanonicalName = new LinkedHashMap<>();
        Map<String, Integer> bestScoresByCanonicalName = new HashMap<>();
        for (SemanticTableModel table : allTables) {
            int score = scoreRelevantTable(
                normalizedQuestion,
                table,
                normalizedFocus,
                queryExampleBoosts.getOrDefault(normalize(table.getTableName()), 0),
                preferredJoinBoosts.getOrDefault(normalize(table.getTableName()), 0)
            );
            if (score <= 0) {
                continue;
            }
            String canonicalName = normalize(table.getTableName());
            SemanticTableModel existing = bestByCanonicalName.get(canonicalName);
            int existingScore = bestScoresByCanonicalName.getOrDefault(canonicalName, Integer.MIN_VALUE);
            if (existing == null
                || score > existingScore
                || (score == existingScore && compareCanonicalSemanticVariants(table, existing) > 0)) {
                bestByCanonicalName.put(canonicalName, table);
                bestScoresByCanonicalName.put(canonicalName, score);
            }
        }

        if (!bestByCanonicalName.isEmpty()) {
            return bestByCanonicalName.values().stream()
                .sorted(relevantTableComparator(
                    normalizedQuestion,
                    normalizedFocus,
                    queryExampleBoosts,
                    preferredJoinBoosts
                ))
                .limit(5)
                .toList();
        }

        Comparator<SemanticTableModel> ranking = Comparator
            .comparing((SemanticTableModel model) -> rolePriority(model.getTableRole()))
            .thenComparing((SemanticTableModel model) -> model.getConfidenceScore() != null ? model.getConfidenceScore() : BigDecimal.ZERO)
            .reversed()
            .thenComparing(SemanticTableModel::getTableName, String.CASE_INSENSITIVE_ORDER);

        return allTables.stream()
            .sorted(ranking)
            .limit(5)
            .toList();
    }

    private Comparator<SemanticTableModel> relevantTableComparator(
            String normalizedQuestion,
            Set<String> normalizedFocus,
            Map<String, Integer> queryExampleBoosts,
            Map<String, Integer> preferredJoinBoosts) {
        return Comparator
            .comparingInt((SemanticTableModel model) -> scoreRelevantTable(
                normalizedQuestion,
                model,
                normalizedFocus,
                queryExampleBoosts.getOrDefault(normalize(model.getTableName()), 0),
                preferredJoinBoosts.getOrDefault(normalize(model.getTableName()), 0)
            ))
            .reversed()
            .thenComparing((SemanticTableModel model) -> promptAwareRolePriority(normalizedQuestion, model), Comparator.reverseOrder())
            .thenComparing((SemanticTableModel model) -> model.getConfidenceScore() != null ? model.getConfidenceScore() : BigDecimal.ZERO, Comparator.reverseOrder())
            .thenComparing(SemanticTableModel::getTableName, String.CASE_INSENSITIVE_ORDER);
    }

    private int scoreRelevantTable(
            String normalizedQuestion,
            SemanticTableModel table,
            Set<String> normalizedFocus,
            int queryExampleBoost,
            int preferredJoinBoost) {
        if (table == null || !notBlank(table.getTableName())) {
            return 0;
        }

        int score = 0;
        String normalizedTable = normalize(table.getTableName());
        boolean tableMentioned = mentionsTable(normalizedQuestion, table.getTableName());
        boolean businessTermMatch = matchesBusinessTerms(normalizedQuestion, table.getBusinessTerms());
        boolean descriptionMatch = matchesDocumentation(normalizedQuestion, table.getBusinessDescription());
        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(normalizedQuestion);
        boolean declineQuestion = PromptIntentSignals.isBehavioralDeclineQuestion(normalizedQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(normalizedQuestion);
        boolean sourceOfTruthQuestion = isSourceOfTruthQuestion(normalizedQuestion);
        boolean derivedLike = isDerivedRole(table.getTableRole()) || looksDerivedFromName(table.getTableName());
        boolean factLike = "FACT".equalsIgnoreCase(table.getTableRole())
            || "TRANSACTION".equalsIgnoreCase(table.getBusinessDomain())
            || hasCommercialSignals(table);

        if (normalizedFocus.contains(normalizedTable)) {
            if (sourceOfTruthQuestion && derivedLike) {
                score += 70;
            } else if (sourceOfTruthQuestion && factLike) {
                score += 320;
            } else {
                score += 280;
            }
        }
        if (tableMentioned) {
            score += 220;
        }
        if (businessTermMatch) {
            score += 160;
        }
        if (descriptionMatch) {
            score += 110;
        }

        score += entitySpecificityBonus(normalizedQuestion, normalizedTable);
        score += onboardingIntentBonus(
            normalizedQuestion,
            normalizedTable,
            table.getBusinessDescription(),
            table.getTimeColumns()
        );
        score += activityIntentBonus(normalizedQuestion, table, activityQuestion, declineQuestion, commercialQuestion);
        score += semanticRichnessBonus(table);
        score += queryExampleBoost;
        score += preferredJoinBoost;
        score += promptAwareRoleBonus(normalizedQuestion, table);
        score += sourceOfTruthBonus(normalizedQuestion, table, commercialQuestion);
        if (table.getConfidenceScore() != null) {
            score += table.getConfidenceScore().intValue() / 5;
        }
        return score;
    }

    private boolean isSourceOfTruthQuestion(String normalizedQuestion) {
        if (!notBlank(normalizedQuestion)) {
            return false;
        }
        boolean countQuestion = normalizedQuestion.contains(" count ") || normalizedQuestion.contains(" how many ");
        boolean detailQuestion = normalizedQuestion.contains(" detail ")
            || normalizedQuestion.contains(" details ")
            || normalizedQuestion.contains(" list ")
            || normalizedQuestion.contains(" show ")
            || normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" country ")
            || normalizedQuestion.contains(" name ");
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(normalizedQuestion);
        boolean trendSummaryQuestion = normalizedQuestion.contains(" trend ")
            || normalizedQuestion.contains(" summary ")
            || normalizedQuestion.contains(" pattern ")
            || normalizedQuestion.contains(" report ")
            || normalizedQuestion.contains(" overview ");
        return (countQuestion || detailQuestion || commercialQuestion) && !trendSummaryQuestion;
    }

    private int compareCanonicalSemanticVariants(SemanticTableModel left, SemanticTableModel right) {
        int compare = Integer.compare(semanticRichnessBonus(left), semanticRichnessBonus(right));
        if (compare != 0) {
            return compare;
        }

        compare = Integer.compare(rolePriority(left != null ? left.getTableRole() : null),
            rolePriority(right != null ? right.getTableRole() : null));
        if (compare != 0) {
            return compare;
        }

        BigDecimal leftConfidence = left != null && left.getConfidenceScore() != null ? left.getConfidenceScore() : BigDecimal.ZERO;
        BigDecimal rightConfidence = right != null && right.getConfidenceScore() != null ? right.getConfidenceScore() : BigDecimal.ZERO;
        compare = leftConfidence.compareTo(rightConfidence);
        if (compare != 0) {
            return compare;
        }

        compare = Integer.compare(identifierTokens(left != null ? left.getTableName() : "").size(),
            identifierTokens(right != null ? right.getTableName() : "").size());
        if (compare != 0) {
            return compare;
        }

        String leftName = left != null ? safeString(left.getTableName()) : "";
        String rightName = right != null ? safeString(right.getTableName()) : "";
        boolean leftUpper = !leftName.isBlank() && leftName.equals(leftName.toUpperCase(Locale.ROOT));
        boolean rightUpper = !rightName.isBlank() && rightName.equals(rightName.toUpperCase(Locale.ROOT));
        if (leftUpper != rightUpper) {
            return leftUpper ? 1 : -1;
        }

        return rightName.compareToIgnoreCase(leftName) * -1;
    }

    private int activityIntentBonus(
            String normalizedQuestion,
            SemanticTableModel table,
            boolean activityQuestion,
            boolean declineQuestion,
            boolean commercialQuestion) {
        if (table == null || !activityQuestion) {
            return 0;
        }

        boolean eventRole = isEventRole(table.getTableRole());
        boolean eventSignals = hasEventSignals(table);
        boolean commercialSignals = hasCommercialSignals(table);

        int score = 0;
        if (eventRole) {
            score += 210;
        }
        if (eventSignals) {
            score += 135;
        }
        if (declineQuestion && (eventRole || eventSignals)) {
            score += 60;
        }
        if (table.getTimeColumns() != null && !table.getTimeColumns().isEmpty()) {
            score += 18;
        }
        if (commercialSignals && !commercialQuestion) {
            score -= 170;
        }
        if (!commercialQuestion && table.getMetricColumns() != null && !table.getMetricColumns().isEmpty()) {
            score -= 45;
        }
        if (normalizedQuestion.contains(" usage ") || normalizedQuestion.contains(" activity ")) {
            if (matchesDocumentation(normalizedQuestion, table.getBusinessDescription())) {
                score += 24;
            }
            if (matchesBusinessTerms(normalizedQuestion, table.getBusinessTerms())) {
                score += 20;
            }
        }
        return score;
    }

    private int promptAwareRoleBonus(String normalizedQuestion, SemanticTableModel table) {
        if (table == null) {
            return 0;
        }
        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(normalizedQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(normalizedQuestion);
        if (activityQuestion) {
            if (isEventRole(table.getTableRole())) {
                return 72;
            }
            if (hasCommercialSignals(table) && !commercialQuestion) {
                return -36;
            }
            return rolePriority(table.getTableRole()) * 4;
        }
        return rolePriority(table.getTableRole()) * 12;
    }

    private int promptAwareRolePriority(String normalizedQuestion, SemanticTableModel table) {
        if (table == null) {
            return 0;
        }
        boolean activityQuestion = PromptIntentSignals.isActivityUsageQuestion(normalizedQuestion);
        boolean commercialQuestion = PromptIntentSignals.isCommercialQuestion(normalizedQuestion);
        if (activityQuestion) {
            if (isEventRole(table.getTableRole())) {
                return 8;
            }
            if (hasCommercialSignals(table) && !commercialQuestion) {
                return 1;
            }
        }
        return rolePriority(table.getTableRole());
    }

    private int sourceOfTruthBonus(String normalizedQuestion, SemanticTableModel table, boolean commercialQuestion) {
        if (table == null) {
            return 0;
        }
        boolean countQuestion = normalizedQuestion.contains(" count ") || normalizedQuestion.contains(" how many ");
        boolean detailQuestion = normalizedQuestion.contains(" detail ")
            || normalizedQuestion.contains(" details ")
            || normalizedQuestion.contains(" list ")
            || normalizedQuestion.contains(" show ")
            || normalizedQuestion.contains(" email ")
            || normalizedQuestion.contains(" country ")
            || normalizedQuestion.contains(" name ");
        boolean trendSummaryQuestion = normalizedQuestion.contains(" trend ")
            || normalizedQuestion.contains(" summary ")
            || normalizedQuestion.contains(" pattern ")
            || normalizedQuestion.contains(" report ")
            || normalizedQuestion.contains(" overview ");
        boolean derivedLike = isDerivedRole(table.getTableRole()) || looksDerivedFromName(table.getTableName());
        boolean factLike = "FACT".equalsIgnoreCase(table.getTableRole())
            || "TRANSACTION".equalsIgnoreCase(table.getBusinessDomain())
            || hasCommercialSignals(table);

        int score = 0;
        if ((countQuestion || detailQuestion || commercialQuestion) && !trendSummaryQuestion) {
            if (derivedLike) {
                score -= 135;
            }
            if (factLike) {
                score += 42;
            }
        }
        if (trendSummaryQuestion && derivedLike) {
            score += 55;
        }
        return score;
    }

    private int semanticRichnessBonus(SemanticTableModel table) {
        if (table == null) {
            return 0;
        }
        int score = 0;
        if (notBlank(table.getBusinessDescription())) {
            score += 36;
        }
        if (notBlank(table.getBusinessTerms())) {
            score += 28;
        }
        if (!isEmpty(table.getTimeColumns())) {
            score += 18 + Math.min(8, table.getTimeColumns().size() * 2);
        }
        if (!isEmpty(table.getMetricColumns())) {
            score += 8;
        }
        if (!isEmpty(table.getFilterColumns())) {
            score += 6;
        }
        if (identifierTokens(table.getTableName()).size() == 1) {
            score += 10;
        }
        return score;
    }

    private List<SemanticJoinModel> loadRelevantJoins(String connectionId, List<SemanticTableModel> relevantTables) {
        if (relevantTables == null || relevantTables.isEmpty()) {
            return List.of();
        }
        Set<String> tableNames = relevantTables.stream()
            .map(SemanticTableModel::getTableName)
            .collect(Collectors.toCollection(LinkedHashSet::new));
        if (tableNames.isEmpty()) {
            return List.of();
        }
        return semanticJoinModelRepository.findByConnectionIdAndTables(connectionId, tableNames).stream()
            .limit(8)
            .toList();
    }

    private List<QueryExample> findRelevantQueryPatterns(String connectionId, List<SemanticTableModel> relevantTables) {
        if (relevantTables == null || relevantTables.isEmpty()) {
            return List.of();
        }
        Set<String> relevantNames = relevantTables.stream()
            .map(SemanticTableModel::getTableName)
            .map(this::normalize)
            .collect(Collectors.toCollection(HashSet::new));

        List<QueryExample> verified = queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue(connectionId);
        if (verified.isEmpty()) {
            verified = queryExampleRepository.findByConnectionIdAndSuccessfulTrueOrderByCreatedAtDesc(connectionId);
        }

        return verified.stream()
            .filter(example -> tablesUsed(example).stream().anyMatch(relevantNames::contains))
            .limit(3)
            .toList();
    }

    private Set<String> tablesUsed(QueryExample example) {
        if (example == null || !notBlank(example.getTablesUsed())) {
            return Set.of();
        }
        return java.util.Arrays.stream(example.getTablesUsed().split(","))
            .map(String::trim)
            .filter(s -> !s.isBlank())
            .map(this::normalize)
            .collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private String deriveGrainDescription(TableMetadata table, TableClassification classification, List<KeyColumnAnalysis> keyColumns) {
        List<String> primaryKeys = safeColumns(table).stream()
            .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
            .map(ColumnMetadata::getName)
            .toList();
        String singular = singularize(table.getName());
        if ("BRIDGE".equalsIgnoreCase(classification != null ? classification.getTableRole() : null)) {
            return "One row per association in the " + singular + " bridge.";
        }
        if ("AGGREGATE".equalsIgnoreCase(classification != null ? classification.getTableRole() : null)) {
            return "One row per aggregated " + singular + " record.";
        }
        if (!primaryKeys.isEmpty()) {
            if (primaryKeys.size() == 1) {
                return "One row per " + singular + ", keyed by " + primaryKeys.getFirst() + ".";
            }
            return "One row per unique combination of " + String.join(", ", primaryKeys) + ".";
        }
        if (!keyColumns.isEmpty()) {
            return "One row per " + singular + " using key columns " + keyColumns.stream()
                .limit(2)
                .map(KeyColumnAnalysis::getColumnName)
                .collect(Collectors.joining(", ")) + ".";
        }
        return "One row per " + singular + " record.";
    }

    private List<String> deriveKeyColumns(TableMetadata table, List<KeyColumnAnalysis> keyColumns) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        keyColumns.stream()
            .limit(4)
            .map(KeyColumnAnalysis::getColumnName)
            .filter(Objects::nonNull)
            .forEach(values::add);
        safeColumns(table).stream()
            .filter(column -> Boolean.TRUE.equals(column.getPrimaryKey()))
            .map(ColumnMetadata::getName)
            .forEach(values::add);
        return List.copyOf(values);
    }

    private List<Map<String, Object>> deriveTemporalSemantics(
            TableMetadata table,
            TableClassification classification,
            SchemaDocumentation tableDoc,
            List<SchemaDocumentation> columnDocs) {
        Map<String, ColumnMetadata> columnsByName = safeColumns(table).stream()
            .filter(column -> column != null && notBlank(column.getName()))
            .collect(Collectors.toMap(
                column -> normalize(column.getName()),
                column -> column,
                (left, right) -> left,
                LinkedHashMap::new
            ));
        Map<String, SchemaDocumentation> docsByColumn = columnDocs == null ? Map.of() : columnDocs.stream()
            .filter(doc -> doc != null && notBlank(doc.getObjectName()))
            .collect(Collectors.toMap(
                doc -> normalize(doc.getObjectName()),
                doc -> doc,
                (left, right) -> documentationPriority(left) <= documentationPriority(right) ? left : right,
                LinkedHashMap::new
            ));
        LinkedHashMap<String, Map<String, Object>> semanticsByColumn = new LinkedHashMap<>();
        if (classification != null && classification.getTimestampColumns() != null) {
            classification.getTimestampColumns().stream()
                .map(item -> item.get("column"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .map(columnName -> columnsByName.get(normalize(columnName)))
                .filter(Objects::nonNull)
                .filter(this::isStrictTemporalColumn)
                .forEach(column -> semanticsByColumn.put(
                    normalize(column.getName()),
                    buildTemporalSemantic(column, classification, tableDoc, docsByColumn.get(normalize(column.getName())))
                ));
        }
        if (columnDocs != null) {
            columnDocs.stream()
                .filter(doc -> looksTemporalFromDocumentation(doc, tableDoc))
                .map(doc -> columnsByName.get(normalize(doc.getObjectName())))
                .filter(Objects::nonNull)
                .filter(this::isStrictTemporalColumn)
                .forEach(column -> semanticsByColumn.putIfAbsent(
                    normalize(column.getName()),
                    buildTemporalSemantic(column, classification, tableDoc, docsByColumn.get(normalize(column.getName())))
                ));
        }
        safeColumns(table).stream()
            .filter(this::isStrictTemporalColumn)
            .forEach(column -> semanticsByColumn.putIfAbsent(
                normalize(column.getName()),
                buildTemporalSemantic(column, classification, tableDoc, docsByColumn.get(normalize(column.getName())))
            ));

        return semanticsByColumn.values().stream()
            .sorted(Comparator
                .comparingInt((Map<String, Object> item) -> intValue(item.get("score")))
                .reversed()
                .thenComparing(item -> String.valueOf(item.get("column")), String.CASE_INSENSITIVE_ORDER))
            .limit(6)
            .toList();
    }

    private List<String> deriveTimeColumns(List<Map<String, Object>> temporalSemantics) {
        if (temporalSemantics == null || temporalSemantics.isEmpty()) {
            return List.of();
        }
        return temporalSemantics.stream()
            .map(item -> item.get("column"))
            .filter(Objects::nonNull)
            .map(String::valueOf)
            .filter(this::notBlank)
            .distinct()
            .limit(4)
            .toList();
    }

    private Map<String, Object> buildTemporalSemantic(
            ColumnMetadata column,
            TableClassification classification,
            SchemaDocumentation tableDoc,
            SchemaDocumentation columnDoc) {
        String columnName = column.getName();
        String normalizedColumn = normalize(columnName);
        String combinedDocumentation = normalizeQuestion(
            safeString(columnDoc != null ? columnDoc.getBusinessTerms() : null) + " "
                + safeString(columnDoc != null ? columnDoc.getDescription() : null) + " "
                + safeString(tableDoc != null ? tableDoc.getBusinessTerms() : null) + " "
                + safeString(tableDoc != null ? tableDoc.getDescription() : null)
        );
        String label = inferTemporalMeaning(normalizedColumn, classification, combinedDocumentation);
        int score = temporalPriority(normalizedColumn, classification);
        Map<String, Object> semantic = new LinkedHashMap<>();
        semantic.put("column", columnName);
        semantic.put("label", label);
        semantic.put("score", score);
        semantic.put("preferred", score >= 85);
        if (columnDoc != null && notBlank(columnDoc.getDescription())) {
            semantic.put("source", "documentation");
            semantic.put("description", compact(columnDoc.getDescription(), 140));
        } else {
            semantic.put("source", "schema");
        }
        return semantic;
    }

    private List<String> deriveDimensionColumns(
            TableMetadata table,
            List<ColumnProfile> profiles,
            List<ColumnValueCache> valueCaches) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        valueCaches.stream()
            .filter(cache -> cache.getColumnName() != null)
            .sorted(Comparator.comparing(cache -> safeString(cache.getColumnName())))
            .map(ColumnValueCache::getColumnName)
            .limit(5)
            .forEach(values::add);

        Map<String, ColumnProfile> profileByColumn = profiles.stream()
            .filter(profile -> profile.getColumnName() != null)
            .collect(Collectors.toMap(
                item -> normalize(item.getColumnName()),
                item -> item,
                (left, right) -> left
            ));

        safeColumns(table).stream()
            .filter(column -> isDimensionCandidate(column, profileByColumn.get(normalize(column.getName()))))
            .map(ColumnMetadata::getName)
            .limit(6)
            .forEach(values::add);
        return List.copyOf(values);
    }

    private List<Map<String, Object>> deriveFilterColumns(
            TableMetadata table,
            TableClassification classification,
            List<ColumnValueCache> valueCaches,
            List<ColumnProfile> profiles,
            List<SchemaDocumentation> columnDocs) {
        Map<String, ColumnProfile> profileByColumn = profiles.stream()
            .filter(profile -> profile.getColumnName() != null)
            .collect(Collectors.toMap(
                item -> normalize(item.getColumnName()),
                item -> item,
                (left, right) -> left
            ));
        List<Map<String, Object>> values = new ArrayList<>();
        for (ColumnValueCache cache : valueCaches.stream().limit(5).toList()) {
            Map<String, Object> row = new LinkedHashMap<>();
            row.put("column", cache.getColumnName());
            row.put("distinctCount", cache.getDistinctCount());
            row.put("values", truncateValues(parseValueList(cache.getAllValues()), 5));
            ColumnProfile profile = profileByColumn.get(normalize(cache.getColumnName()));
            if (profile != null && profile.getNullCount() != null && profile.getTotalRows() != null
                    && profile.getTotalRows() > 0) {
                row.put("nullRatio", round(profile.getNullCount() / (double) profile.getTotalRows()));
            }
            values.add(row);
        }

        if (table != null && table.getColumns() != null) {
            Map<String, SchemaDocumentation> docsByColumn = columnDocs == null ? Map.of() : columnDocs.stream()
                .filter(doc -> doc.getObjectName() != null)
                .collect(Collectors.toMap(
                    doc -> normalize(doc.getObjectName()),
                    doc -> doc,
                    (left, right) -> left
                ));
            Set<String> existingColumns = values.stream()
                .map(item -> normalize(String.valueOf(item.get("column"))))
                .collect(Collectors.toCollection(LinkedHashSet::new));

            for (ColumnMetadata column : table.getColumns()) {
                if (column == null || !notBlank(column.getName())) {
                    continue;
                }
                String normalizedColumn = normalize(column.getName());
                if (existingColumns.contains(normalizedColumn)) {
                    continue;
                }
                SchemaDocumentation columnDoc = docsByColumn.get(normalizedColumn);
                if (!isFilterLikeColumn(column, columnDoc, classification)) {
                    continue;
                }
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("column", column.getName());
                row.put("source", columnDoc != null && notBlank(columnDoc.getDescription()) ? "documentation" : "schema");
                if (columnDoc != null && notBlank(columnDoc.getBusinessTerms())) {
                    row.put("businessTerms", columnDoc.getBusinessTerms());
                }
                if (columnDoc != null && notBlank(columnDoc.getDescription())) {
                    row.put("description", columnDoc.getDescription());
                }
                values.add(row);
                existingColumns.add(normalizedColumn);
                if (values.size() >= 6) {
                    break;
                }
            }
        }
        return values;
    }

    private List<String> deriveMetricColumns(TableMetadata table, List<KeyColumnAnalysis> keyColumns) {
        Set<String> excluded = keyColumns.stream()
            .map(KeyColumnAnalysis::getColumnName)
            .filter(Objects::nonNull)
            .map(this::normalize)
            .collect(Collectors.toCollection(HashSet::new));
        return safeColumns(table).stream()
            .filter(column -> isMetricCandidate(column, excluded))
            .map(ColumnMetadata::getName)
            .limit(6)
            .toList();
    }

    private BigDecimal deriveConfidence(
            SchemaDocumentation tableDoc,
            TableClassification classification,
            List<KeyColumnAnalysis> keyColumns,
            List<ColumnValueCache> valueCaches,
            List<CompanyKnowledgeEntry> linkedKnowledge) {
        int score = 40;
        if (tableDoc != null && notBlank(tableDoc.getDescription())) {
            score += 20;
        }
        if (tableDoc != null && notBlank(tableDoc.getBusinessTerms())) {
            score += 10;
        }
        if (classification != null) {
            score += 15;
        }
        if (!keyColumns.isEmpty()) {
            score += 10;
        }
        if (!valueCaches.isEmpty()) {
            score += 5;
        }
        if (linkedKnowledge != null && !linkedKnowledge.isEmpty()) {
            score += Math.min(10, linkedKnowledge.size() * 4);
        }
        return BigDecimal.valueOf(Math.min(score, 99));
    }

    private String buildSourceSummary(
            SchemaDocumentation tableDoc,
            TableClassification classification,
            List<KeyColumnAnalysis> keyColumns,
            List<ColumnValueCache> valueCaches,
            List<CompanyKnowledgeEntry> linkedKnowledge) {
        List<String> parts = new ArrayList<>();
        parts.add("schema_scan");
        if (tableDoc != null) {
            parts.add("schema_documentation");
        }
        if (classification != null) {
            parts.add("table_classification");
        }
        if (!keyColumns.isEmpty()) {
            parts.add("key_column_analysis");
        }
        if (!valueCaches.isEmpty()) {
            parts.add("column_value_cache");
        }
        if (linkedKnowledge != null && !linkedKnowledge.isEmpty()) {
            parts.add("company_knowledge");
        }
        return String.join(", ", parts);
    }

    private int documentationPriority(SchemaDocumentation doc) {
        if (doc == null) {
            return Integer.MAX_VALUE;
        }
        if (doc.getSource() == com.dbaagent.model.DocumentationSource.USER
                || doc.getSource() == com.dbaagent.model.DocumentationSource.CSV_IMPORT) {
            return 0;
        }
        return 1;
    }

    private boolean looksTemporal(String columnName, String dataType) {
        return isStrictTemporalColumn(columnName, dataType);
    }

    private int temporalPriority(String columnName, TableClassification classification) {
        String normalized = normalize(columnName);
        boolean eventRole = isEventRole(classification != null ? classification.getTableRole() : null);
        if (normalized.contains("occurred")) return eventRole ? 125 : 108;
        if (normalized.contains("logged")) return eventRole ? 122 : 106;
        if (normalized.contains("access")) return eventRole ? 120 : 104;
        if (normalized.contains("visit")) return eventRole ? 118 : 102;
        if (normalized.contains("session")) return eventRole ? 116 : 100;
        if (normalized.contains("booked") || normalized.contains("booking")) return 112;
        if (normalized.contains("paid") || normalized.contains("payment")) return 108;
        if (normalized.contains("subscription")) return 106;
        if (normalized.contains("activation")) return 104;
        if (normalized.contains("start")) return 100;
        if (normalized.contains("created")) return eventRole ? 96 : 92;
        if (normalized.contains("processed")) return 90;
        if (normalized.contains("cancel") || normalized.contains("refund")) return 88;
        if (normalized.contains("checkin") || normalized.contains("checkout")) return 86;
        if (normalized.contains("updated") || normalized.contains("modified")) return 42;
        return 60;
    }

    private boolean isStrictTemporalColumn(ColumnMetadata column) {
        if (column == null) {
            return false;
        }
        return isStrictTemporalColumn(column.getName(), column.getDataType());
    }

    private boolean isStrictTemporalColumn(String columnName, String dataType) {
        String normalizedName = normalize(columnName);
        String normalizedType = normalize(dataType);
        if (!notBlank(normalizedName) || normalizedName.endsWith("_id") || "id".equals(normalizedName)) {
            return false;
        }
        if (normalizedName.contains("timezone") || normalizedName.contains("birth") || normalizedName.contains("dob")) {
            return false;
        }
        if (normalizedType.contains("date") || normalizedType.contains("time")) {
            return true;
        }
        return normalizedName.contains("date")
            || normalizedName.contains("time")
            || normalizedName.contains("timestamp")
            || normalizedName.contains("created")
            || normalizedName.contains("updated")
            || normalizedName.contains("occurred")
            || normalizedName.contains("logged")
            || normalizedName.contains("access")
            || normalizedName.contains("visit")
            || normalizedName.contains("session")
            || normalizedName.contains("checkin")
            || normalizedName.contains("checkout")
            || normalizedName.endsWith("_on")
            || normalizedName.endsWith("_at");
    }

    private String inferTemporalMeaning(
            String normalizedColumn,
            TableClassification classification,
            String normalizedDocumentation) {
        boolean eventRole = isEventRole(classification != null ? classification.getTableRole() : null);
        String documentation = normalizedDocumentation == null ? "" : normalizedDocumentation;
        if (normalizedColumn.contains("occurred") || normalizedColumn.contains("logged")
            || normalizedColumn.contains("session") || normalizedColumn.contains("visit")
            || normalizedColumn.contains("access")) {
            return "event/activity time";
        }
        if (normalizedColumn.contains("booking") || normalizedColumn.contains("booked")
            || normalizedColumn.contains("paid") || normalizedColumn.contains("payment")
            || normalizedColumn.contains("invoice") || normalizedColumn.contains("charge")
            || normalizedColumn.contains("processed")) {
            return "booking/transaction time";
        }
        if (normalizedColumn.contains("subscription") || normalizedColumn.contains("activation")
            || normalizedColumn.contains("onboard") || normalizedColumn.contains("contract")
            || (normalizedColumn.contains("start") && documentation.contains(" subscription "))) {
            return "lifecycle/onboarding start";
        }
        if (normalizedColumn.contains("cancel") || normalizedColumn.contains("refund")
            || documentation.contains(" refund ") || documentation.contains(" cancellation ")) {
            return "cancellation/refund time";
        }
        if (normalizedColumn.contains("updated") || normalizedColumn.contains("modified")) {
            return "update time";
        }
        if (normalizedColumn.contains("created") || documentation.contains(" created ") || eventRole) {
            return eventRole ? "event/activity time" : "creation time";
        }
        return "business date/time";
    }

    private String formatTemporalSemantics(List<Map<String, Object>> temporalSemantics) {
        if (temporalSemantics == null || temporalSemantics.isEmpty()) {
            return "";
        }
        return temporalSemantics.stream()
            .map(item -> String.valueOf(item.get("column")) + " (" + String.valueOf(item.get("label")) + ")")
            .filter(item -> item != null && !item.isBlank())
            .limit(4)
            .collect(Collectors.joining(", "));
    }

    private int intValue(Object value) {
        if (value instanceof Number number) {
            return number.intValue();
        }
        if (value == null) {
            return 0;
        }
        try {
            return Integer.parseInt(String.valueOf(value));
        } catch (NumberFormatException ignored) {
            return 0;
        }
    }

    private boolean isMetricCandidate(ColumnMetadata column, Set<String> excluded) {
        if (column == null || !notBlank(column.getName()) || !notBlank(column.getDataType())) {
            return false;
        }
        String normalizedName = normalize(column.getName());
        if (excluded.contains(normalizedName) || normalizedName.endsWith("_id") || "id".equals(normalizedName)) {
            return false;
        }
        String normalizedType = normalize(column.getDataType());
        boolean numericType = normalizedType.contains("int") || normalizedType.contains("decimal")
            || normalizedType.contains("numeric") || normalizedType.contains("double")
            || normalizedType.contains("float") || normalizedType.contains("real");
        if (!numericType) {
            return false;
        }
        return METRIC_NAME_HINTS.stream().anyMatch(normalizedName::contains) || !normalizedName.contains("version");
    }

    private boolean isDimensionCandidate(ColumnMetadata column, ColumnProfile profile) {
        if (column == null || !notBlank(column.getName())) {
            return false;
        }
        String normalizedName = normalize(column.getName());
        if (normalizedName.endsWith("_id") || "id".equals(normalizedName)) {
            return true;
        }
        if (DIMENSION_NAME_HINTS.stream().anyMatch(normalizedName::contains)) {
            return true;
        }
        return profile != null && profile.getDistinctCount() != null && profile.getDistinctCount() > 0
            && profile.getDistinctCount() <= 25;
    }

    private List<String> parseValueList(String json) {
        if (!notBlank(json)) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, STRING_LIST_TYPE);
        } catch (Exception e) {
            log.debug("Unable to parse cached column values: {}", e.getMessage());
            return List.of();
        }
    }

    private List<String> truncateValues(List<String> values, int limit) {
        if (values == null || values.isEmpty()) {
            return List.of();
        }
        return values.stream()
            .filter(Objects::nonNull)
            .map(String::trim)
            .filter(value -> !value.isBlank())
            .limit(limit)
            .toList();
    }

    private String formatFilterColumns(List<Map<String, Object>> filters) {
        return filters.stream()
            .map(filter -> {
                Object column = filter.get("column");
                Object values = filter.get("values");
                if (column == null) {
                    return null;
                }
                if (values instanceof List<?> list && !list.isEmpty()) {
                    return column + " " + list;
                }
                return String.valueOf(column);
            })
            .filter(Objects::nonNull)
            .collect(Collectors.joining(", "));
    }

    private Map<String, TableClassification> buildClassificationAliasIndex(List<TableClassification> classifications) {
        Map<String, TableClassification> aliasIndex = new LinkedHashMap<>();
        if (classifications == null) {
            return aliasIndex;
        }
        for (TableClassification classification : classifications) {
            if (classification == null || !notBlank(classification.getTableName())) {
                continue;
            }
            for (String alias : SchemaObjectNameUtil.tableLookupAliases(classification.getTableName())) {
                aliasIndex.putIfAbsent(alias, classification);
            }
        }
        return aliasIndex;
    }

    private TableClassification resolveClassification(Map<String, TableClassification> classificationByTable, TableMetadata table) {
        if (classificationByTable == null || classificationByTable.isEmpty() || table == null) {
            return null;
        }
        for (String alias : SchemaObjectNameUtil.tableLookupAliases(table)) {
            TableClassification classification = classificationByTable.get(alias);
            if (classification != null) {
                return classification;
            }
        }
        return classificationByTable.get(normalize(table.getName()));
    }

    private SchemaDocumentation resolveBestTableDocumentation(List<SchemaDocumentation> docs, TableMetadata table) {
        if (docs == null || docs.isEmpty() || table == null) {
            return null;
        }
        return docs.stream()
            .filter(doc -> SchemaObjectNameUtil.tableReferenceMatchStrength(doc.getObjectName(), table) > 0)
            .max(Comparator
                .comparingInt((SchemaDocumentation doc) -> SchemaObjectNameUtil.tableReferenceMatchStrength(doc.getObjectName(), table))
                .thenComparingInt(doc -> -documentationPriority(doc))
                .thenComparing(doc -> doc.getConfidence() != null ? doc.getConfidence() : 0d))
            .orElse(null);
    }

    private List<SchemaDocumentation> resolveColumnDocumentation(List<SchemaDocumentation> docs, TableMetadata table) {
        if (docs == null || docs.isEmpty() || table == null) {
            return List.of();
        }
        return docs.stream()
            .filter(doc -> SchemaObjectNameUtil.tableReferenceMatchStrength(doc.getParentObject(), table) > 0)
            .sorted(Comparator
                .comparingInt((SchemaDocumentation doc) -> SchemaObjectNameUtil.tableReferenceMatchStrength(doc.getParentObject(), table))
                .reversed()
                .thenComparingInt(this::documentationPriority)
                .thenComparing(doc -> safeString(doc.getObjectName()), String.CASE_INSENSITIVE_ORDER))
            .collect(Collectors.collectingAndThen(
                Collectors.toMap(
                    doc -> normalize(doc.getObjectName()),
                    doc -> doc,
                    (left, right) -> documentationPriority(left) <= documentationPriority(right) ? left : right,
                    LinkedHashMap::new
                ),
                map -> List.copyOf(map.values())
            ));
    }

    private Map<String, Integer> buildQueryExampleBoosts(String connectionId, String question) {
        if (!notBlank(connectionId)) {
            return Map.of();
        }
        List<QueryExample> examples = queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue(connectionId);
        if (examples.isEmpty()) {
            examples = queryExampleRepository.findByConnectionIdAndSuccessfulTrueOrderByCreatedAtDesc(connectionId);
        }
        if (examples.isEmpty()) {
            return Map.of();
        }

        Set<String> questionTokens = meaningfulTokens(question);
        Map<String, Integer> boosts = new HashMap<>();
        for (QueryExample example : examples.stream().limit(40).toList()) {
            Set<String> tables = tablesUsed(example);
            if (tables.isEmpty()) {
                continue;
            }
            int overlap = overlapScore(questionTokens, meaningfulTokens(example.getNaturalLanguage()));
            if (overlap <= 0) {
                continue;
            }
            int boost = 18 + Math.min(42, overlap * 14);
            for (String table : tables) {
                boosts.merge(table, boost, Math::max);
            }
        }
        return boosts;
    }

    private Map<String, Integer> buildPreferredJoinBoosts(String connectionId, Set<String> normalizedFocus) {
        if (!notBlank(connectionId) || normalizedFocus == null || normalizedFocus.isEmpty()) {
            return Map.of();
        }
        Map<String, Integer> boosts = new HashMap<>();
        for (SemanticJoinModel join : semanticJoinModelRepository
            .findByConnectionIdOrderByPreferredDescConfidenceScoreDescSourceTableAsc(connectionId)) {
            if (join == null || !Boolean.TRUE.equals(join.getPreferred())) {
                continue;
            }
            String source = normalize(join.getSourceTable());
            String target = normalize(join.getTargetTable());
            if (normalizedFocus.contains(source) && !normalizedFocus.contains(target)) {
                boosts.merge(target, 35, Math::max);
            }
            if (normalizedFocus.contains(target) && !normalizedFocus.contains(source)) {
                boosts.merge(source, 35, Math::max);
            }
        }
        return boosts;
    }

    private int overlapScore(Set<String> left, Set<String> right) {
        if (left == null || right == null || left.isEmpty() || right.isEmpty()) {
            return 0;
        }
        int overlap = 0;
        for (String token : left) {
            if (right.contains(token)) {
                overlap++;
            }
        }
        return overlap;
    }

    private boolean matchesBusinessTerms(String normalizedQuestion, String businessTerms) {
        if (!notBlank(normalizedQuestion) || !notBlank(businessTerms)) {
            return false;
        }
        for (String term : businessTerms.split(",")) {
            String normalizedTerm = normalize(term);
            if (!normalizedTerm.isBlank() && normalizedQuestion.contains(" " + normalizedTerm.replace('_', ' ') + " ")) {
                return true;
            }
        }
        return false;
    }

    private boolean looksTemporalFromDocumentation(SchemaDocumentation columnDoc, SchemaDocumentation tableDoc) {
        if (columnDoc == null || !notBlank(columnDoc.getObjectName())) {
            return false;
        }
        String normalizedColumn = normalize(columnDoc.getObjectName());
        if (TIME_NAME_HINTS.stream().anyMatch(normalizedColumn::contains)) {
            return true;
        }

        String combinedTerms = safeString(columnDoc.getBusinessTerms()) + " " + safeString(columnDoc.getDescription())
            + " " + safeString(tableDoc != null ? tableDoc.getBusinessTerms() : null)
            + " " + safeString(tableDoc != null ? tableDoc.getDescription() : null);
        String normalizedCombined = normalizeQuestion(combinedTerms);
        return normalizedCombined.contains(" created ")
            || normalizedCombined.contains(" booked ")
            || normalizedCombined.contains(" onboarding ")
            || normalizedCombined.contains(" onboarded ")
            || normalizedCombined.contains(" activation ")
            || normalizedCombined.contains(" activated ")
            || normalizedCombined.contains(" subscription ")
            || normalizedCombined.contains(" timestamp ")
            || normalizedCombined.contains(" date ")
            || normalizedCombined.contains(" time ");
    }

    private int columnDocumentationTemporalPriority(SchemaDocumentation columnDoc, SchemaDocumentation tableDoc) {
        if (columnDoc == null) {
            return Integer.MAX_VALUE;
        }
        String normalizedQuestion = normalizeQuestion(
            safeString(columnDoc.getBusinessTerms()) + " "
                + safeString(columnDoc.getDescription()) + " "
                + safeString(tableDoc != null ? tableDoc.getDescription() : null)
        );
        String normalizedColumn = normalize(columnDoc.getObjectName());
        int score = temporalPriority(normalizedColumn, null);
        if (normalizedQuestion.contains(" subscription ")) score -= 40;
        if (normalizedQuestion.contains(" activation ")) score -= 35;
        if (normalizedQuestion.contains(" onboarded ") || normalizedQuestion.contains(" onboarding ")) score -= 30;
        if (normalizedQuestion.contains(" created ")) score -= 20;
        return score;
    }

    private boolean isFilterLikeColumn(ColumnMetadata column, SchemaDocumentation columnDoc, TableClassification classification) {
        if (column == null || !notBlank(column.getName())) {
            return false;
        }
        String normalizedName = normalize(column.getName());
        if (normalizedName.endsWith("_id") || "id".equals(normalizedName)) {
            return false;
        }
        if (isEventRole(classification != null ? classification.getTableRole() : null)) {
            if (normalizedName.contains("actor")
                || normalizedName.contains("action")
                || normalizedName.contains("source")
                || normalizedName.contains("channel")
                || normalizedName.contains("status")
                || normalizedName.contains("state")
                || normalizedName.contains("device")
                || normalizedName.contains("platform")
                || normalizedName.contains("member")
                || normalizedName.contains("user")) {
                return true;
            }
        }
        if (DIMENSION_NAME_HINTS.stream().anyMatch(normalizedName::contains)) {
            return true;
        }
        if (normalizedName.contains("flag") || normalizedName.contains("enabled") || normalizedName.contains("active")) {
            return true;
        }
        if (columnDoc == null) {
            return false;
        }
        String normalizedDoc = normalizeQuestion(
            safeString(columnDoc.getBusinessTerms()) + " " + safeString(columnDoc.getDescription())
        );
        return normalizedDoc.contains(" status ")
            || normalizedDoc.contains(" lifecycle ")
            || normalizedDoc.contains(" state ")
            || normalizedDoc.contains(" stage ")
            || normalizedDoc.contains(" active ")
            || normalizedDoc.contains(" inactive ")
            || normalizedDoc.contains(" enabled ")
            || normalizedDoc.contains(" disabled ");
    }

    private boolean matchesDocumentation(String normalizedQuestion, String description) {
        if (!notBlank(normalizedQuestion) || !notBlank(description)) {
            return false;
        }
        Set<String> questionTokens = meaningfulTokens(normalizedQuestion);
        if (questionTokens.isEmpty()) {
            return false;
        }
        for (String token : meaningfulTokens(description)) {
            if (questionTokens.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private boolean mentionsTable(String normalizedQuestion, String tableName) {
        return SchemaTableMatchUtil.mentionsTable(normalizedQuestion, tableName);
    }

    private int entitySpecificityBonus(String normalizedQuestion, String normalizedTable) {
        if (!notBlank(normalizedQuestion) || !notBlank(normalizedTable)) {
            return 0;
        }

        List<String> tokens = identifierTokens(normalizedTable);
        if (tokens.isEmpty()) {
            return 0;
        }

        String baseToken = tokens.getFirst();
        boolean baseMentioned = containsWholeToken(normalizedQuestion, baseToken)
            || containsWholeToken(normalizedQuestion, singularize(baseToken))
            || containsWholeToken(normalizedQuestion, pluralize(baseToken));
        if (!baseMentioned) {
            return 0;
        }

        boolean baseEntity = tokens.size() == 1 || normalizedTable.equals(baseToken);
        if (baseEntity) {
            return 120;
        }

        int score = 0;
        boolean qualifierMentioned = false;
        for (int index = 1; index < tokens.size(); index++) {
            String qualifier = tokens.get(index);
            if (containsWholeToken(normalizedQuestion, qualifier)
                || containsWholeToken(normalizedQuestion, singularize(qualifier))
                || containsWholeToken(normalizedQuestion, pluralize(qualifier))) {
                qualifierMentioned = true;
                score += 35;
            }
        }
        if (!qualifierMentioned) {
            score -= 95;
        }
        return score;
    }

    private int onboardingIntentBonus(
            String normalizedQuestion,
            String normalizedTable,
            String businessDescription,
            List<String> timeColumns) {
        if (!isOnboardingQuestion(normalizedQuestion)) {
            return 0;
        }

        int score = 0;
        if (identifierTokens(normalizedTable).size() <= 1) {
            score += 65;
        } else {
            score -= 35;
        }

        String normalizedDescription = normalizeQuestion(businessDescription);
        if (normalizedDescription.contains(" subscription ")
            || normalizedDescription.contains(" activation ")
            || normalizedDescription.contains(" contract start ")
            || normalizedDescription.contains(" onboarded ")
            || normalizedDescription.contains(" onboarding ")) {
            score += 60;
        }

        if (timeColumns != null) {
            for (String timeColumn : timeColumns) {
                String normalizedColumn = normalize(timeColumn);
                if (normalizedColumn.contains("subscription")) {
                    score += 80;
                }
                if (normalizedColumn.contains("start")) {
                    score += 45;
                }
                if (normalizedColumn.contains("updated")) {
                    score -= 25;
                }
            }
        }
        return score;
    }

    private boolean isOnboardingQuestion(String normalizedQuestion) {
        return notBlank(normalizedQuestion) && (
            normalizedQuestion.contains(" onboard ")
                || normalizedQuestion.contains(" onboarded ")
                || normalizedQuestion.contains(" onboarding ")
                || normalizedQuestion.contains(" subscription ")
                || normalizedQuestion.contains(" activation ")
                || normalizedQuestion.contains(" activated ")
                || normalizedQuestion.contains(" contract started ")
                || normalizedQuestion.contains(" contract start ")
        );
    }

    private Set<String> meaningfulTokens(String text) {
        if (!notBlank(text)) {
            return Set.of();
        }
        Set<String> stopWords = Set.of(
            "the", "a", "an", "is", "are", "was", "were", "for", "from", "with", "and", "or", "to",
            "of", "in", "on", "by", "at", "this", "that", "these", "those", "what", "which", "how",
            "much", "many", "month", "months", "week", "weeks", "day", "days", "year", "years"
        );
        Set<String> tokens = new LinkedHashSet<>();
        for (String token : text.toLowerCase(Locale.ROOT).split("[^a-z0-9]+")) {
            if (token == null || token.isBlank()) {
                continue;
            }
            String normalizedToken = normalizeToken(token);
            if (normalizedToken.length() >= 3 && !stopWords.contains(normalizedToken)) {
                tokens.add(normalizedToken);
            }
        }
        return tokens;
    }

    private List<String> identifierTokens(String identifier) {
        if (!notBlank(identifier)) {
            return List.of();
        }
        return java.util.Arrays.stream(identifier.split("[_\\s]+"))
            .map(this::normalizeToken)
            .filter(token -> token.length() >= 3)
            .toList();
    }

    private boolean containsWholeToken(String normalizedQuestion, String token) {
        return notBlank(normalizedQuestion)
            && notBlank(token)
            && normalizedQuestion.contains(" " + token + " ");
    }

    private String normalizeToken(String token) {
        if (!notBlank(token)) {
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

    private String pluralize(String token) {
        if (!notBlank(token)) {
            return "";
        }
        if (token.endsWith("y") && token.length() > 1) {
            return token.substring(0, token.length() - 1) + "ies";
        }
        if (token.endsWith("s")) {
            return token;
        }
        return token + "s";
    }

    private String normalizeTableReference(String value) {
        if (!notBlank(value)) {
            return "";
        }
        String normalizedValue = value.trim();
        int lastDot = normalizedValue.lastIndexOf('.');
        if (lastDot >= 0 && lastDot < normalizedValue.length() - 1) {
            normalizedValue = normalizedValue.substring(lastDot + 1);
        }
        return normalize(normalizedValue);
    }

    private String deriveBusinessDescription(
        SchemaDocumentation tableDoc,
        List<SchemaDocumentation> columnDocs,
        TableMetadata table,
        List<String> keyColumns,
        List<String> timeColumns,
        List<CompanyKnowledgeEntry> linkedKnowledge
    ) {
        String tableDescription = tableDoc != null && notBlank(tableDoc.getDescription())
            ? tableDoc.getDescription()
            : null;
        String linkedDescription = buildCompanyKnowledgeDescription(linkedKnowledge, table);
        if (tableDescription != null && linkedDescription != null) {
            return compact(tableDescription + " Company context: " + linkedDescription, 420);
        }
        if (tableDescription != null) {
            return tableDescription;
        }
        if (linkedDescription != null) {
            return linkedDescription;
        }
        if (columnDocs == null || columnDocs.isEmpty()) {
            return null;
        }

        Set<String> highlightedColumns = new LinkedHashSet<>();
        if (timeColumns != null) {
            timeColumns.stream().filter(Objects::nonNull).map(this::normalize).forEach(highlightedColumns::add);
        }
        if (keyColumns != null) {
            keyColumns.stream().filter(Objects::nonNull).map(this::normalize).forEach(highlightedColumns::add);
        }

        List<String> snippets = columnDocs.stream()
            .filter(doc -> notBlank(doc.getDescription()))
            .sorted(Comparator
                .comparingInt((SchemaDocumentation doc) -> highlightedColumns.contains(normalize(doc.getObjectName())) ? 0 : 1)
                .thenComparingInt(this::documentationPriority))
            .limit(2)
            .map(doc -> {
                if (highlightedColumns.contains(normalize(doc.getObjectName()))) {
                    return doc.getObjectName() + ": " + doc.getDescription();
                }
                return doc.getDescription();
            })
            .toList();

        if (snippets.isEmpty()) {
            return null;
        }
        String base = "Key table semantics: " + String.join(" ", snippets);
        if (linkedDescription != null) {
            return compact(base + " Company context: " + linkedDescription, 420);
        }
        return base;
    }

    private String deriveBusinessTerms(
        SchemaDocumentation tableDoc,
        List<SchemaDocumentation> columnDocs,
        List<String> timeColumns,
        List<Map<String, Object>> filterColumns,
        List<CompanyKnowledgeEntry> linkedKnowledge
    ) {
        LinkedHashSet<String> values = new LinkedHashSet<>();
        if (tableDoc != null && notBlank(tableDoc.getBusinessTerms())) {
            for (String term : tableDoc.getBusinessTerms().split(",")) {
                if (notBlank(term)) {
                    values.add(term.trim());
                }
            }
        }
        if (values.isEmpty() && columnDocs != null) {
            Set<String> highlightedColumns = new LinkedHashSet<>();
            if (timeColumns != null) {
                timeColumns.stream().filter(Objects::nonNull).map(this::normalize).forEach(highlightedColumns::add);
            }
            columnDocs.stream()
                .filter(doc -> highlightedColumns.contains(normalize(doc.getObjectName())))
                .map(SchemaDocumentation::getBusinessTerms)
                .filter(this::notBlank)
                .forEach(terms -> {
                    for (String term : terms.split(",")) {
                        if (notBlank(term)) {
                            values.add(term.trim());
                        }
                    }
                });
        }
        if (filterColumns != null) {
            filterColumns.stream()
                .map(filter -> filter.get("businessTerms"))
                .filter(String.class::isInstance)
                .map(String.class::cast)
                .forEach(terms -> {
                    for (String term : terms.split(",")) {
                        if (notBlank(term)) {
                            values.add(term.trim());
                        }
                    }
                });
        }
        if (linkedKnowledge != null) {
            linkedKnowledge.forEach(entry -> collectKnowledgeTerms(values, entry));
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private List<CompanyKnowledgeEntry> resolveCompanyKnowledgeEntries(
            List<CompanyKnowledgeEntry> entries,
            TableMetadata table) {
        if (entries == null || entries.isEmpty() || table == null) {
            return List.of();
        }
        return entries.stream()
            .filter(entry -> entryLinksToTable(entry, table))
            .sorted(Comparator
                .comparing((CompanyKnowledgeEntry entry) -> entry.getEntryType() != null ? entry.getEntryType().name() : "ZZZ")
                .thenComparing(entry -> safeString(entry.getTitle()), String.CASE_INSENSITIVE_ORDER))
            .toList();
    }

    private boolean entryLinksToTable(CompanyKnowledgeEntry entry, TableMetadata table) {
        if (entry == null || table == null) {
            return false;
        }
        if (entry.getLinkedTables() != null && entry.getLinkedTables().stream()
            .anyMatch(linkedTable -> SchemaObjectNameUtil.tableReferenceMatchStrength(linkedTable, table) > 0)) {
            return true;
        }
        if (entry.getLinkedColumns() == null || entry.getLinkedColumns().isEmpty()) {
            return false;
        }
        return entry.getLinkedColumns().stream()
            .map(this::tableReferenceFromColumn)
            .anyMatch(linkedTable -> SchemaObjectNameUtil.tableReferenceMatchStrength(linkedTable, table) > 0);
    }

    private String tableReferenceFromColumn(String columnReference) {
        if (!notBlank(columnReference)) {
            return "";
        }
        int lastDot = columnReference.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return SchemaObjectNameUtil.canonicalTableReference(columnReference.substring(0, lastDot));
    }

    private String buildCompanyKnowledgeDescription(List<CompanyKnowledgeEntry> linkedKnowledge, TableMetadata table) {
        if (linkedKnowledge == null || linkedKnowledge.isEmpty()) {
            return null;
        }
        List<String> snippets = new ArrayList<>();
        for (CompanyKnowledgeEntry entry : linkedKnowledge) {
            String prefix = entry.getTitle();
            if (entry.getEntryType() != null) {
                prefix += " (" + entry.getEntryType().name().replace('_', ' ').toLowerCase(Locale.ROOT) + ")";
            }
            snippets.add(prefix + ": " + compact(entry.getContent(), 160));
            if (snippets.size() >= 2) {
                break;
            }
        }
        if (snippets.isEmpty()) {
            return null;
        }
        String baseEntity = table != null ? singularize(table.getName()) : "entity";
        return "Linked company knowledge for this " + baseEntity + ": " + String.join(" ", snippets);
    }

    private void collectKnowledgeTerms(Set<String> values, CompanyKnowledgeEntry entry) {
        if (values == null || entry == null) {
            return;
        }
        if (notBlank(entry.getTitle())) {
            values.add(entry.getTitle().trim());
        }
        for (String token : meaningfulTokens(entry.getTitle() + " " + entry.getContent())) {
            if (token.length() >= 4) {
                values.add(token);
            }
        }
    }

    private List<CompanyKnowledgeEntry> findRelevantCompanyKnowledge(
            String connectionId,
            String question,
            List<SemanticTableModel> relevantTables) {
        List<CompanyKnowledgeEntry> entries = companyKnowledgeEntryRepository.findByConnectionId(connectionId);
        if (entries.isEmpty()) {
            return List.of();
        }
        Set<String> focusTables = relevantTables == null ? Set.of() : relevantTables.stream()
            .map(SemanticTableModel::getTableName)
            .filter(Objects::nonNull)
            .map(SchemaObjectNameUtil::normalizedCanonicalTableName)
            .filter(value -> !value.isBlank())
            .collect(Collectors.toCollection(LinkedHashSet::new));
        Set<String> questionTokens = meaningfulTokens(question);

        return entries.stream()
            .map(entry -> Map.entry(entry, scoreCompanyKnowledgeEntry(entry, focusTables, questionTokens)))
            .filter(item -> item.getValue() > 0)
            .sorted(Map.Entry.<CompanyKnowledgeEntry, Integer>comparingByValue().reversed()
                .thenComparing(item -> safeString(item.getKey().getTitle()), String.CASE_INSENSITIVE_ORDER))
            .limit(4)
            .map(Map.Entry::getKey)
            .toList();
    }

    private int scoreCompanyKnowledgeEntry(
            CompanyKnowledgeEntry entry,
            Set<String> focusTables,
            Set<String> questionTokens) {
        if (entry == null) {
            return 0;
        }
        int score = 0;
        if (focusTables != null && !focusTables.isEmpty()) {
            if (entry.getLinkedTables() != null) {
                for (String linkedTable : entry.getLinkedTables()) {
                    if (focusTables.contains(SchemaObjectNameUtil.normalizedCanonicalTableName(linkedTable))) {
                        score += 90;
                    }
                }
            }
            if (entry.getLinkedColumns() != null) {
                for (String linkedColumn : entry.getLinkedColumns()) {
                    String tableReference = tableReferenceFromColumn(linkedColumn);
                    if (focusTables.contains(SchemaObjectNameUtil.normalizedCanonicalTableName(tableReference))) {
                        score += 60;
                    }
                }
            }
        }
        if (questionTokens != null && !questionTokens.isEmpty()) {
            String haystack = normalizeQuestion(safeString(entry.getTitle()) + " " + safeString(entry.getContent()));
            for (String token : questionTokens) {
                if (haystack.contains(" " + token + " ")) {
                    score += 18;
                }
            }
        }
        return score;
    }

    private String compact(String text, int maxLength) {
        if (!notBlank(text) || maxLength <= 0) {
            return "";
        }
        String collapsed = text.replaceAll("\\s+", " ").trim();
        if (collapsed.length() <= maxLength) {
            return collapsed;
        }
        return collapsed.substring(0, Math.max(0, maxLength - 1)).trim() + "…";
    }

    private boolean isEventRole(String role) {
        String normalizedRole = safeUpper(role, "");
        return "EVENT_LOG".equals(normalizedRole) || "AUDIT_LOG".equals(normalizedRole);
    }

    private boolean hasEventSignals(SemanticTableModel table) {
        if (table == null) {
            return false;
        }
        String haystack = normalizeQuestion(
            safeString(table.getTableName()) + " "
                + safeString(table.getBusinessDescription()) + " "
                + safeString(table.getBusinessTerms())
        );
        if (haystack.contains(" log ")
            || haystack.contains(" event ")
            || haystack.contains(" usage ")
            || haystack.contains(" activity ")
            || haystack.contains(" session ")
            || haystack.contains(" audit ")
            || haystack.contains(" visit ")
            || haystack.contains(" access ")) {
            return true;
        }
        return table.getFilterColumns() != null && table.getFilterColumns().stream()
            .map(item -> normalize(String.valueOf(item.get("column"))))
            .anyMatch(column -> column.contains("action")
                || column.contains("actor")
                || column.contains("source")
                || column.contains("device")
                || column.contains("platform")
                || column.contains("channel")
                || column.contains("user"));
    }

    private boolean hasCommercialSignals(SemanticTableModel table) {
        if (table == null) {
            return false;
        }
        String haystack = normalizeQuestion(
            safeString(table.getTableName()) + " "
                + safeString(table.getBusinessDescription()) + " "
                + safeString(table.getBusinessTerms())
        );
        return haystack.contains(" booking ")
            || haystack.contains(" payment ")
            || haystack.contains(" invoice ")
            || haystack.contains(" order ")
            || haystack.contains(" ledger ")
            || haystack.contains(" revenue ")
            || haystack.contains(" commission ")
            || haystack.contains(" refund ")
            || haystack.contains(" charge ")
            || haystack.contains(" billing ");
    }

    private boolean isDerivedRole(String role) {
        String normalizedRole = safeUpper(role, "");
        return "AGGREGATE".equals(normalizedRole)
            || "SUMMARY".equals(normalizedRole)
            || "ROLLUP".equals(normalizedRole);
    }

    private boolean looksDerivedFromName(String tableName) {
        String normalized = normalizeQuestion(tableName);
        return normalized.contains(" aggregate ")
            || normalized.contains(" aggregation ")
            || normalized.contains(" summary ")
            || normalized.contains(" rollup ")
            || normalized.contains(" trend ")
            || normalized.contains(" report ")
            || normalized.contains(" insight ")
            || normalized.contains(" analytics ")
            || normalized.contains(" snapshot ")
            || normalized.contains(" derived ")
            || normalized.contains(" stats ");
    }

    private int rolePriority(String role) {
        return switch (safeUpper(role, "")) {
            case "FACT" -> 5;
            case "EVENT_LOG", "AUDIT_LOG" -> 4;
            case "DIMENSION" -> 4;
            case "LOOKUP" -> 3;
            case "BRIDGE" -> 2;
            case "AGGREGATE" -> 1;
            default -> 0;
        };
    }

    private double round(double value) {
        return BigDecimal.valueOf(value).setScale(4, RoundingMode.HALF_UP).doubleValue();
    }

    private List<TableMetadata> safeTables(SchemaMetadata schema) {
        return schema != null && schema.getTables() != null ? schema.getTables() : List.of();
    }

    private List<RelationshipMetadata> safeRelationships(SchemaMetadata schema) {
        return schema != null && schema.getRelationships() != null ? schema.getRelationships() : List.of();
    }

    private List<ColumnMetadata> safeColumns(TableMetadata table) {
        return table != null && table.getColumns() != null ? table.getColumns() : List.of();
    }

    private String singularize(String tableName) {
        String normalized = tableName == null ? "record" : tableName.toLowerCase(Locale.ROOT).replace('_', ' ');
        if (normalized.endsWith("ies")) {
            return normalized.substring(0, normalized.length() - 3) + "y";
        }
        if (normalized.endsWith("s") && normalized.length() > 1) {
            return normalized.substring(0, normalized.length() - 1);
        }
        return normalized;
    }

    private String joinExpression(String sourceTable, String sourceColumn, String targetTable, String targetColumn) {
        return sourceTable + "." + sourceColumn + " = " + targetTable + "." + targetColumn;
    }

    private String normalizeQuestion(String question) {
        if (question == null || question.isBlank()) {
            return " ";
        }
        return SchemaTableMatchUtil.normalizeQuestion(question);
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    }

    private String safeUpper(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.toUpperCase(Locale.ROOT);
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private boolean notBlank(String value) {
        return value != null && !value.isBlank();
    }

    private boolean isEmpty(List<?> values) {
        return values == null || values.isEmpty();
    }

    public record SemanticModelBuildSummary(
        int tablesBuilt,
        int joinsBuilt,
        int tablesWithDocs,
        int tablesWithTimeColumns,
        int verifiedPatterns
    ) {}
}
