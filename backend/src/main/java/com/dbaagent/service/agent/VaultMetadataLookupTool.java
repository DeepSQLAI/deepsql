package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.DatabaseConnection;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.SlowQueryHistory;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableRelationshipClassification;
import com.dbaagent.repository.CredentialRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.repository.TableRelationshipClassificationRepository;
import com.dbaagent.repository.GrowthAnomalyRepository;
import com.dbaagent.repository.brain.WorkloadProfileRepository;
import com.dbaagent.repository.brain.KnobRankingRepository;
import com.dbaagent.model.GrowthAnomaly;
import com.dbaagent.model.SemanticJoinModel;
import com.dbaagent.model.SemanticTableModel;
import com.dbaagent.model.brain.WorkloadProfile;
import com.dbaagent.model.brain.KnobRanking;
import com.dbaagent.service.ExactSchemaKeyColumnUtil;
import com.dbaagent.service.SchemaQuestionUtil;
import com.dbaagent.service.SemanticModelService;
import com.dbaagent.service.SchemaObjectNameUtil;
import com.dbaagent.service.SchemaTableMatchUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Agent tool that queries vault DB cached metadata tables.
 * This runs as the first step in METADATA_ANALYSIS workflows,
 * providing cached intelligence before any live DB queries.
 */
@Service
@Slf4j
public class VaultMetadataLookupTool implements AgentTool {

    private final InferredTableRelationshipRepository inferredRelRepo;
    private final TableRelationshipClassificationRepository relClassRepo;
    private final KeyColumnAnalysisRepository keyColumnRepo;
    private final SlowQueryHistoryRepository slowQueryRepo;
    private final TableClassificationRepository tableClassRepo;
    private final GrowthAnomalyRepository growthRepo;
    private final WorkloadProfileRepository workloadRepo;
    private final KnobRankingRepository knobRepo;
    private final SemanticModelService semanticModelService;
    private final CredentialRepository credentialRepository;
    private final SchemaSnapshotRepository schemaSnapshotRepository;

    public VaultMetadataLookupTool(
            InferredTableRelationshipRepository inferredRelRepo,
            TableRelationshipClassificationRepository relClassRepo,
            KeyColumnAnalysisRepository keyColumnRepo,
            SlowQueryHistoryRepository slowQueryRepo,
            TableClassificationRepository tableClassRepo,
            GrowthAnomalyRepository growthRepo,
            WorkloadProfileRepository workloadRepo,
            KnobRankingRepository knobRepo,
            SemanticModelService semanticModelService,
            CredentialRepository credentialRepository,
            SchemaSnapshotRepository schemaSnapshotRepository) {
        this.inferredRelRepo = inferredRelRepo;
        this.relClassRepo = relClassRepo;
        this.keyColumnRepo = keyColumnRepo;
        this.slowQueryRepo = slowQueryRepo;
        this.tableClassRepo = tableClassRepo;
        this.growthRepo = growthRepo;
        this.workloadRepo = workloadRepo;
        this.knobRepo = knobRepo;
        this.semanticModelService = semanticModelService;
        this.credentialRepository = credentialRepository;
        this.schemaSnapshotRepository = schemaSnapshotRepository;
    }

    @Override
    public String name() {
        return "vault_metadata_lookup_tool";
    }

    @Override
    public AgentToolResult execute(AgentPlanStep step, AgentExecutionContext context) {
        String connectionId = context.connectionId();
        String brainTopic = (String) step.params().getOrDefault("brainTopic", "GENERAL");
        @SuppressWarnings("unchecked")
        List<String> mentionedTables = (List<String>) step.params().getOrDefault("mentionedTables", List.of());

        AgentToolResult result = switch (brainTopic.toUpperCase(Locale.ROOT)) {
            case "SCHEMA" -> lookupSchema(connectionId, mentionedTables, context);
            case "RELATIONSHIPS" -> lookupRelationships(connectionId, mentionedTables);
            case "KEY_COLUMNS" -> lookupKeyColumns(connectionId, mentionedTables, context);
            case "PERFORMANCE" -> lookupPerformance(connectionId, mentionedTables);
            case "GROWTH" -> lookupGrowth(connectionId, mentionedTables);
            case "CLASSIFICATION" -> lookupClassification(connectionId, mentionedTables, context);
            case "WORKLOAD" -> lookupWorkload(connectionId);
            case "TUNING" -> lookupTuning(connectionId);
            default -> lookupGeneral(connectionId, mentionedTables, context);
        };

        // Store sufficiency flag so the orchestrator can skip the live fallback step
        boolean sufficient = result.observation() != null
            && result.observation().data() != null
            && Boolean.TRUE.equals(result.observation().data().get("sufficient"));
        context.putMemory("vaultDataSufficient", sufficient);

        return result;
    }

    private AgentToolResult lookupRelationships(String connectionId, List<String> mentionedTables) {
        List<SemanticJoinModel> semanticJoins = semanticModelService.getSemanticJoins(connectionId, mentionedTables);
        List<InferredTableRelationship> inferred = inferredRelRepo
            .findHighConfidenceRelationships(connectionId, BigDecimal.valueOf(25));

        if (!mentionedTables.isEmpty()) {
            inferred = inferred.stream()
                .filter(rel -> matchesAny(mentionedTables, rel.getSourceTable())
                    || matchesAny(mentionedTables, rel.getTargetTable()))
                .toList();
        }

        List<TableRelationshipClassification> classified = relClassRepo
            .findLatestByConnectionIdOrderBySourceTableAsc(connectionId);
        if (!mentionedTables.isEmpty()) {
            classified = classified.stream()
                .filter(rel -> matchesAny(mentionedTables, rel.getSourceTable())
                    || matchesAny(mentionedTables, rel.getTargetTable()))
                .toList();
        }

        boolean sufficient = !semanticJoins.isEmpty() || !inferred.isEmpty() || !classified.isEmpty();
        StringBuilder summary = new StringBuilder();
        Map<String, Object> data = new LinkedHashMap<>();

        if (!semanticJoins.isEmpty()) {
            summary.append("Found ").append(semanticJoins.size()).append(" semantic join paths");
            if (!mentionedTables.isEmpty()) {
                summary.append(" involving ").append(String.join(", ", mentionedTables));
            }
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var join : semanticJoins.stream().limit(15).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("join", join.getJoinExpression());
                row.put("relationshipType", join.getRelationshipType());
                row.put("evidenceSource", join.getEvidenceSource());
                row.put("preferred", join.getPreferred());
                row.put("confidence", join.getConfidenceScore());
                rows.add(row);
            }
            data.put("semanticJoins", rows);
        }

        if (!inferred.isEmpty()) {
            if (!summary.isEmpty()) {
                summary.append("; ");
            }
            summary.append("Found ").append(inferred.size()).append(" inferred relationships");
            if (!mentionedTables.isEmpty()) {
                summary.append(" involving ").append(String.join(", ", mentionedTables));
            }

            List<Map<String, Object>> rows = new ArrayList<>();
            for (var rel : inferred.stream().limit(15).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", rel.getSourceTable() + "." + rel.getSourceColumn());
                row.put("target", rel.getTargetTable() + "." + rel.getTargetColumn());
                row.put("joinCount", rel.getJoinCount());
                row.put("confidence", rel.getConfidenceScore());
                row.put("cardinality", rel.getCardinality());
                row.put("status", rel.getStatus());
                rows.add(row);
            }
            data.put("inferredRelationships", rows);
        }

        if (!classified.isEmpty()) {
            List<Map<String, Object>> classRows = new ArrayList<>();
            for (var rel : classified.stream().limit(15).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("source", rel.getSourceTable() + "." + rel.getSourceColumn());
                row.put("target", rel.getTargetTable() + "." + rel.getTargetColumn());
                row.put("type", rel.getRelationshipType());
                row.put("strength", rel.getStrength());
                row.put("joinFrequency", rel.getJoinFrequency());
                row.put("dataIntegrity", rel.getDataIntegrityPct());
                classRows.add(row);
            }
            data.put("classifiedRelationships", classRows);
        }

        data.put("sufficient", sufficient);

        return new AgentToolResult(
            new AgentObservation(
                "vault_relationships",
                sufficient ? summary.toString() : "No cached relationship data found",
                data
            ),
            null,
            null,
            sufficient ? 0.9 : 0.1
        );
    }

    private AgentToolResult lookupKeyColumns(String connectionId, List<String> mentionedTables, AgentExecutionContext context) {
        String question = context != null ? context.question() : "";
        SchemaMetadata schema = context != null ? context.schema() : null;

        if (SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(question) && schema != null) {
            TableMetadata exactTable = SchemaQuestionUtil.resolveExactSchemaTable(schema, question);
            if (exactTable != null) {
                List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> exactKeyColumns =
                    ExactSchemaKeyColumnUtil.collectKeyColumns(schema, exactTable);
                List<KeyColumnAnalysis> analyzedColumns = keyColumnRepo
                    .findByConnectionIdOrderByImportanceScoreDesc(connectionId).stream()
                    .filter(col -> SchemaObjectNameUtil.referencesSameTable(exactTable.getName(), col.getTableName()))
                    .filter(this::isMeaningfulKeyColumn)
                    .limit(8)
                    .toList();
                List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> resolvedKeyColumns =
                    ExactSchemaKeyColumnUtil.mergeWithAnalyzedColumns(exactKeyColumns, analyzedColumns);
                Map<String, Object> exactData = new LinkedHashMap<>();
                exactData.put("answerType", "table_key_columns");
                exactData.put("tableName", exactTable.getName());
                exactData.put("columnCount", resolvedKeyColumns.size());
                exactData.put("keyColumns", resolvedKeyColumns.stream().map(descriptor -> {
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("column", descriptor.columnName());
                    row.put("roles", descriptor.roles());
                    row.put("summary", descriptor.summary());
                    return row;
                }).toList());
                exactData.put("sufficient", !resolvedKeyColumns.isEmpty());

                return new AgentToolResult(
                    new AgentObservation(
                        "vault_key_columns",
                        !resolvedKeyColumns.isEmpty()
                            ? "Found " + resolvedKeyColumns.size() + " exact key columns for " + exactTable.getName()
                            : "No exact cached key column metadata found for " + exactTable.getName(),
                        exactData
                    ),
                    null,
                    null,
                    !resolvedKeyColumns.isEmpty() ? 0.96 : 0.1
                );
            }
        }

        List<KeyColumnAnalysis> columns = keyColumnRepo
            .findByConnectionIdOrderByImportanceScoreDesc(connectionId);

        if (!mentionedTables.isEmpty()) {
            columns = columns.stream()
                .filter(col -> matchesAny(mentionedTables, col.getTableName()))
                .toList();
        }

        boolean sufficient = !columns.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();

        if (sufficient) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var col : columns.stream().limit(15).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table", col.getTableName());
                row.put("column", col.getColumnName());
                row.put("importance", col.getImportanceScore());
                rows.add(row);
            }
            data.put("keyColumns", rows);
        }
        data.put("sufficient", sufficient);

        return new AgentToolResult(
            new AgentObservation(
                "vault_key_columns",
                sufficient ? "Found " + columns.size() + " key columns" : "No cached key column data",
                data
            ),
            null,
            null,
            sufficient ? 0.85 : 0.1
        );
    }

    private AgentToolResult lookupPerformance(String connectionId, List<String> mentionedTables) {
        List<SlowQueryHistory> slowQueries = slowQueryRepo.findByConnectionIdOrderByCreatedAtDesc(connectionId);
        boolean sufficient = slowQueries != null && !slowQueries.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("slowQueryCount", slowQueries != null ? slowQueries.size() : 0);
        data.put("sufficient", sufficient);

        return new AgentToolResult(
            new AgentObservation(
                "vault_performance",
                sufficient ? "Found " + slowQueries.size() + " slow query records" : "No cached performance data",
                data
            ),
            null,
            null,
            sufficient ? 0.8 : 0.1
        );
    }

    private AgentToolResult lookupGrowth(String connectionId, List<String> mentionedTables) {
        List<GrowthAnomaly> anomalies = growthRepo.findRecentAnomalies(
            connectionId, LocalDateTime.now().minusDays(90));

        if (!mentionedTables.isEmpty()) {
            anomalies = anomalies.stream()
                .filter(a -> matchesAny(mentionedTables, a.getTableName()))
                .toList();
        }

        boolean sufficient = !anomalies.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("anomalyCount", anomalies.size());
        data.put("sufficient", sufficient);

        return new AgentToolResult(
            new AgentObservation(
                "vault_growth",
                sufficient ? "Found " + anomalies.size() + " growth anomalies" : "No cached growth data",
                data
            ),
            null,
            null,
            sufficient ? 0.85 : 0.1
        );
    }

    private AgentToolResult lookupClassification(String connectionId, List<String> mentionedTables, AgentExecutionContext context) {
        List<TableClassification> classifications = tableClassRepo
            .findLatestByConnectionIdOrderByTableNameAsc(connectionId);
        String question = context != null ? context.question() : "";
        SchemaMetadata schema = context != null ? context.schema() : null;
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);

        if (!mentionedTables.isEmpty()) {
            classifications = classifications.stream()
                .filter(c -> matchesAny(mentionedTables, c.getTableName()))
                .toList();
        }

        boolean sufficient = !classifications.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();

        if (sufficient) {
            List<Map<String, Object>> rows = new ArrayList<>();
            for (var cl : rankClassificationRows(classifications, normalizedQuestion, schema).stream().limit(20).toList()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("table", cl.getTableName());
                row.put("role", cl.getTableRole());
                row.put("businessDomain", cl.getBusinessDomain());
                row.put("accessPattern", cl.getAccessPattern());
                Long resolvedRowCount = resolveRowCount(cl, schema);
                if (resolvedRowCount != null) {
                    row.put("rowCount", resolvedRowCount);
                }
                rows.add(row);
            }
            data.put("classifications", rows);
        }
        data.put("sufficient", sufficient);

        return new AgentToolResult(
            new AgentObservation(
                "vault_classification",
                sufficient ? "Found classifications for " + classifications.size() + " tables" : "No cached classification data",
                data
            ),
            null,
            null,
            sufficient ? 0.9 : 0.1
        );
    }

    private AgentToolResult lookupWorkload(String connectionId) {
        var profileOpt = workloadRepo.findByConnectionId(connectionId);
        boolean sufficient = profileOpt.isPresent();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sufficient", sufficient);

        if (sufficient) {
            var profile = profileOpt.get();
            data.put("workloadType", profile.getWorkloadType());
            data.put("confidence", profile.getClassificationConfidence());
        }

        return new AgentToolResult(
            new AgentObservation(
                "vault_workload",
                sufficient ? "Found workload profile" : "No cached workload data",
                data
            ),
            null,
            null,
            sufficient ? 0.9 : 0.1
        );
    }

    private AgentToolResult lookupTuning(String connectionId) {
        List<KnobRanking> knobs = knobRepo.findByConnectionIdOrderByTargetMetricAscRankAsc(connectionId);
        boolean sufficient = knobs != null && !knobs.isEmpty();
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sufficient", sufficient);
        data.put("knobCount", knobs != null ? knobs.size() : 0);

        return new AgentToolResult(
            new AgentObservation(
                "vault_tuning",
                sufficient ? "Found " + knobs.size() + " knob rankings" : "No cached tuning data",
                data
            ),
            null,
            null,
            sufficient ? 0.85 : 0.1
        );
    }

    private AgentToolResult lookupSchema(String connectionId, List<String> mentionedTables, AgentExecutionContext context) {
        String question = context != null ? context.question() : "";
        SchemaMetadata schema = context != null ? context.schema() : null;
        if (looksLikeSchemaSnapshotQuestion(question)) {
            return lookupSchemaSnapshots(connectionId, question);
        }
        if (schema != null) {
            TableMetadata table = resolveExactSchemaTable(schema, question);
            if (table != null) {
                if (SchemaQuestionUtil.looksLikeExactTableColumnQuestion(question)) {
                    List<Map<String, Object>> columnRows = table.getColumns() == null
                        ? List.of()
                        : table.getColumns().stream()
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparingInt(column -> column.getOrdinalPosition() != null ? column.getOrdinalPosition() : Integer.MAX_VALUE))
                            .map(this::toColumnRow)
                            .toList();

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sufficient", true);
                    data.put("answerType", "table_columns");
                    data.put("tableName", table.getName());
                    data.put("columnCount", columnRows.size());
                    data.put("columns", columnRows);
                    if (table.getRowCount() != null) {
                        data.put("rowCount", table.getRowCount());
                    }

                    return new AgentToolResult(
                        new AgentObservation(
                            "vault_schema",
                            "Found " + columnRows.size() + " columns for " + table.getName(),
                            data
                        ),
                        null,
                        null,
                        0.96
                    );
                }

                if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(question)) {
                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sufficient", table.getRowCount() != null);
                    data.put("answerType", "table_row_count");
                    data.put("tableName", table.getName());
                    data.put("rowCount", table.getRowCount());

                    return new AgentToolResult(
                        new AgentObservation(
                            "vault_schema",
                            table.getRowCount() != null
                                ? "Found cached row count for " + table.getName()
                                : "Cached schema is missing row count for " + table.getName(),
                            data
                        ),
                        null,
                        null,
                        table.getRowCount() != null ? 0.96 : 0.1
                    );
                }

                if (SchemaQuestionUtil.looksLikeExactTableIndexQuestion(question)) {
                    List<Map<String, Object>> indexRows = table.getIndexes() == null
                        ? List.of()
                        : table.getIndexes().stream()
                            .filter(Objects::nonNull)
                            .sorted(Comparator.comparing(index -> index.getName() != null ? index.getName() : "", String.CASE_INSENSITIVE_ORDER))
                            .map(this::toIndexRow)
                            .toList();

                    Map<String, Object> data = new LinkedHashMap<>();
                    data.put("sufficient", !indexRows.isEmpty());
                    data.put("answerType", "table_indexes");
                    data.put("tableName", table.getName());
                    data.put("indexCount", indexRows.size());
                    data.put("indexes", indexRows);

                    return new AgentToolResult(
                        new AgentObservation(
                            "vault_schema",
                            !indexRows.isEmpty()
                                ? "Found " + indexRows.size() + " indexes for " + table.getName()
                                : "Cached schema is missing index metadata for " + table.getName(),
                            data
                        ),
                        null,
                        null,
                        !indexRows.isEmpty() ? 0.96 : 0.1
                    );
                }
            }
        }

        return lookupGeneral(connectionId, mentionedTables, context);
    }

    private AgentToolResult lookupSchemaSnapshots(String activeConnectionId, String question) {
        Optional<DatabaseConnection> targetConnection = resolveTargetConnection(activeConnectionId, question);
        if (targetConnection.isEmpty()) {
            return new AgentToolResult(
                new AgentObservation(
                    "vault_schema",
                    "I could not match that snapshot question to a saved connection",
                    Map.of(
                        "sufficient", false,
                        "answerType", "schema_snapshot_count",
                        "connectionId", activeConnectionId
                    )
                ),
                null,
                null,
                0.15
            );
        }

        DatabaseConnection resolved = targetConnection.get();
        long snapshotCount = schemaSnapshotRepository.countByConnectionId(resolved.getId());
        Optional<SchemaSnapshot> latestSnapshot = schemaSnapshotRepository.findTopByConnectionIdOrderByCapturedAtDesc(resolved.getId());

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sufficient", true);
        data.put("answerType", "schema_snapshot_count");
        data.put("connectionId", resolved.getId());
        data.put("connectionName", resolved.getConnectionName());
        data.put("snapshotCount", snapshotCount);
        latestSnapshot.map(SchemaSnapshot::getCapturedAt).ifPresent(capturedAt -> data.put("latestCapturedAt", capturedAt));
        latestSnapshot.map(SchemaSnapshot::getSnapshotType).ifPresent(snapshotType -> data.put("latestSnapshotType", snapshotType.name()));

        return new AgentToolResult(
            new AgentObservation(
                "vault_schema",
                "Found " + snapshotCount + " schema snapshots for " + resolved.getConnectionName(),
                data
            ),
            null,
            null,
            0.98
        );
    }

    private AgentToolResult lookupGeneral(String connectionId, List<String> mentionedTables, AgentExecutionContext context) {
        String question = context != null ? context.question() : "";
        SchemaMetadata schema = context != null ? context.schema() : null;
        String normalizedQuestion = question == null ? "" : question.toLowerCase(Locale.ROOT);

        List<SemanticTableModel> semanticTables = semanticModelService.getSemanticTables(connectionId, mentionedTables);
        List<TableClassification> classifications = tableClassRepo
            .findLatestByConnectionIdOrderByTableNameAsc(connectionId);

        boolean taskModuleQuestion = looksLikeTaskModuleQuestion(normalizedQuestion);
        boolean tableCountQuestion = looksLikeTableCountQuestion(normalizedQuestion);

        if (tableCountQuestion) {
            Map<String, Object> countData = new LinkedHashMap<>();
            long tableCount = !semanticTables.isEmpty() ? semanticTables.size() : classifications.size();
            countData.put("sufficient", tableCount > 0);
            countData.put("tableCount", tableCount);
            countData.put("answerType", "table_count");
            return new AgentToolResult(
                new AgentObservation(
                    "vault_general",
                    "Found metadata for " + tableCount + " tables",
                    countData
                ),
                null,
                null,
                tableCount > 0 ? 0.9 : 0.1
            );
        }

        List<Map<String, Object>> directMatches = new ArrayList<>();
        for (TableClassification classification : classifications) {
            if (matchesAny(mentionedTables, classification.getTableName())) {
                directMatches.add(toClassificationRow(classification));
            }
        }

        List<Map<String, Object>> suggestedExistingTables = new ArrayList<>();
        if (taskModuleQuestion) {
            suggestedExistingTables.addAll(findExistingTaskAnchors(schema));
        }

        List<Map<String, Object>> proposedTables = new ArrayList<>();
        if (taskModuleQuestion) {
            proposedTables.addAll(defaultTaskModuleTables());
        }

        List<Map<String, Object>> sampleClassifications = classifications.stream()
            .filter(cl -> isUsefulRole(cl.getTableRole()))
            .limit(12)
            .map(this::toClassificationRow)
            .toList();

        boolean sufficient = !directMatches.isEmpty()
            || !suggestedExistingTables.isEmpty()
            || !proposedTables.isEmpty()
            || !sampleClassifications.isEmpty();

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("sufficient", sufficient);
        data.put("tableCount", !semanticTables.isEmpty() ? semanticTables.size() : classifications.size());
        if (!semanticTables.isEmpty()) {
            data.put("semanticTables", semanticTables.stream().limit(12).map(this::toSemanticTableRow).toList());
        }
        if (!directMatches.isEmpty()) {
            data.put("directMatches", directMatches);
        }
        if (!suggestedExistingTables.isEmpty()) {
            data.put("suggestedExistingTables", suggestedExistingTables);
        }
        if (!proposedTables.isEmpty()) {
            data.put("proposedTables", proposedTables);
            data.put("noDirectModuleTablesFound", true);
        }
        if (!sampleClassifications.isEmpty()) {
            data.put("classifications", sampleClassifications);
        }
        if (taskModuleQuestion) {
            data.put("moduleHint", "task_management");
        }

        return new AgentToolResult(
            new AgentObservation(
                "vault_general",
                sufficient
                    ? "Found metadata for " + classifications.size() + " tables"
                    : "No cached metadata",
                data
            ),
            null,
            null,
            sufficient ? 0.82 : 0.1
        );
    }

    private boolean looksLikeTaskModuleQuestion(String normalizedQuestion) {
        return normalizedQuestion.contains("task")
            || normalizedQuestion.contains("to do")
            || normalizedQuestion.contains("todo")
            || normalizedQuestion.contains("task management")
            || normalizedQuestion.contains("workflow");
    }

    private boolean looksLikeSchemaSnapshotQuestion(String question) {
        String normalized = question == null ? "" : question.toLowerCase(Locale.ROOT);
        return normalized.contains("schema snapshot")
            || normalized.contains("schema snapshots")
            || normalized.contains("snapshot history");
    }

    private Optional<DatabaseConnection> resolveTargetConnection(String activeConnectionId, String question) {
        List<DatabaseConnection> connections = credentialRepository.findAllByOrderByCreatedAtDesc();
        if (connections.isEmpty()) {
            return Optional.empty();
        }

        String normalizedQuestion = normalizeConnectionToken(question);
        Optional<DatabaseConnection> explicitMatch = connections.stream()
            .filter(Objects::nonNull)
            .filter(connection -> connection.getConnectionName() != null && !connection.getConnectionName().isBlank())
            .filter(connection -> normalizedQuestion.contains(normalizeConnectionToken(connection.getConnectionName())))
            .findFirst();
        if (explicitMatch.isPresent()) {
            return explicitMatch;
        }

        return connections.stream()
            .filter(Objects::nonNull)
            .filter(connection -> Objects.equals(connection.getId(), activeConnectionId))
            .findFirst();
    }

    private String normalizeConnectionToken(String value) {
        if (value == null || value.isBlank()) {
            return "";
        }
        return value.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "");
    }

    private boolean looksLikeTableCountQuestion(String normalizedQuestion) {
        return normalizedQuestion.contains("how many tables")
            || normalizedQuestion.contains("number of tables")
            || normalizedQuestion.contains("table count")
            || normalizedQuestion.contains("count of tables");
    }

    private List<Map<String, Object>> findExistingTaskAnchors(SchemaMetadata schema) {
        if (schema == null || schema.getTables() == null) {
            return List.of();
        }

        List<Map<String, Object>> rows = new ArrayList<>();
        addAnchorIfPresent(rows, schema, "INTELL_USERS", "best existing assignee/owner table if staff users live here");
        addAnchorIfPresent(rows, schema, "USER", "alternate user/owner table if the app still relies on the legacy user table");
        addAnchorIfPresent(rows, schema, "HOTEL", "useful scope table if tasks are hotel-level operational work");
        addAnchorIfPresent(rows, schema, "USER_BOOKINGS", "attach tasks to bookings when the work item is reservation-driven");
        addAnchorIfPresent(rows, schema, "ROOM_RESERVATIONS", "attach tasks to stay/reservation operations when room-level workflow matters");
        addAnchorIfPresent(rows, schema, "BOOKING_NOTES", "closest existing notes/history table if you need free-form task context");
        addAnchorIfPresent(rows, schema, "CUSTOMER_NOTES", "candidate notes table if tasks are customer-service oriented");
        addAnchorIfPresent(rows, schema, "BOOKING_CONVERSATION", "candidate activity/comment stream if tasks need threaded discussion");
        addAnchorIfPresent(rows, schema, "BOOKING_TAGS", "lightweight categorization table if tasks need labels/status grouping");
        addAnchorIfPresent(rows, schema, "GUEST_MAPPING", "bridge table if task ownership or follow-ups are guest-specific");
        return rows;
    }

    private void addAnchorIfPresent(List<Map<String, Object>> rows, SchemaMetadata schema, String tableName, String reason) {
        TableMetadata match = schema.getTables().stream()
            .filter(table -> table != null && tableName.equalsIgnoreCase(table.getName()))
            .findFirst()
            .orElse(null);
        if (match == null) {
            return;
        }

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", match.getName());
        row.put("reason", reason);
        row.put("rowCount", match.getRowCount());
        row.put("columns", importantColumns(match));
        rows.add(row);
    }

    private List<Map<String, Object>> defaultTaskModuleTables() {
        return List.of(
            proposedTable("TASKS", "core task record with title, description, priority, due date, status, owner, and optional booking/hotel links"),
            proposedTable("TASK_ASSIGNEES", "supports multiple owners/watchers per task and keeps assignment history clean"),
            proposedTable("TASK_COMMENTS", "discussion thread or internal notes on each task"),
            proposedTable("TASK_ACTIVITY", "immutable audit/activity stream for status changes, assignment changes, and reminders"),
            proposedTable("TASK_TAGS", "labels or queues such as housekeeping, finance, guest-follow-up, maintenance"),
            proposedTable("TASK_DEPENDENCIES", "optional blocker/predecessor relationships between tasks"),
            proposedTable("TASK_REMINDERS", "scheduled reminders / SLA alerts for overdue or upcoming work")
        );
    }

    private Map<String, Object> proposedTable(String table, String purpose) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table);
        row.put("purpose", purpose);
        return row;
    }

    private Map<String, Object> toClassificationRow(TableClassification classification) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", classification.getTableName());
        row.put("role", classification.getTableRole());
        row.put("businessDomain", classification.getBusinessDomain());
        row.put("rowCount", classification.getRowCount());
        return row;
    }

    private List<TableClassification> rankClassificationRows(
            List<TableClassification> classifications,
            String normalizedQuestion,
            SchemaMetadata schema) {
        if (classifications == null || classifications.isEmpty()) {
            return List.of();
        }

        boolean largestQuestion = normalizedQuestion.contains("largest")
            || normalizedQuestion.contains("biggest")
            || normalizedQuestion.contains("top");
        String preferredRole = preferredRole(normalizedQuestion);

        return classifications.stream()
            .filter(cl -> preferredRole == null || preferredRole.equalsIgnoreCase(cl.getTableRole()))
            .sorted((left, right) -> {
                if (largestQuestion) {
                    Long rightRows = resolveRowCount(right, schema);
                    Long leftRows = resolveRowCount(left, schema);
                    int rowCompare = Long.compare(
                        rightRows != null ? rightRows : Long.MIN_VALUE,
                        leftRows != null ? leftRows : Long.MIN_VALUE
                    );
                    if (rowCompare != 0) {
                        return rowCompare;
                    }
                }
                return lower(left.getTableName()).compareTo(lower(right.getTableName()));
            })
            .toList();
    }

    private Long resolveRowCount(TableClassification classification, SchemaMetadata schema) {
        if (classification == null) {
            return null;
        }
        if (schema != null && schema.getTables() != null) {
            for (TableMetadata table : schema.getTables()) {
                if (table != null
                    && table.getName() != null
                    && classification.getTableName() != null
                    && table.getName().equalsIgnoreCase(classification.getTableName())
                    && table.getRowCount() != null) {
                    return table.getRowCount();
                }
            }
        }
        return classification.getRowCount();
    }

    private String preferredRole(String normalizedQuestion) {
        if (normalizedQuestion == null || normalizedQuestion.isBlank()) {
            return null;
        }
        if (normalizedQuestion.contains("fact")) {
            return "FACT";
        }
        if (normalizedQuestion.contains("dimension")) {
            return "DIMENSION";
        }
        if (normalizedQuestion.contains("bridge")) {
            return "BRIDGE";
        }
        if (normalizedQuestion.contains("lookup")) {
            return "LOOKUP";
        }
        if (normalizedQuestion.contains("orphaned")) {
            return "ORPHANED";
        }
        return null;
    }

    private String lower(String value) {
        return value == null ? "" : value.toLowerCase(Locale.ROOT);
    }

    private Map<String, Object> toSemanticTableRow(SemanticTableModel table) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("table", table.getTableName());
        row.put("role", table.getTableRole());
        row.put("businessDomain", table.getBusinessDomain());
        row.put("grain", table.getGrainDescription());
        row.put("timeColumns", table.getTimeColumns());
        row.put("keyColumns", table.getKeyColumns());
        return row;
    }

    private boolean isUsefulRole(String role) {
        if (role == null) {
            return false;
        }
        String upper = role.toUpperCase(Locale.ROOT);
        return "FACT".equals(upper) || "DIMENSION".equals(upper) || "BRIDGE".equals(upper) || "LOOKUP".equals(upper);
    }

    private List<String> importantColumns(TableMetadata tableMetadata) {
        if (tableMetadata == null || tableMetadata.getColumns() == null) {
            return List.of();
        }
        return tableMetadata.getColumns().stream()
            .map(column -> column.getName())
            .filter(name -> name != null && !name.isBlank())
            .sorted((a, b) -> Integer.compare(scoreColumn(b), scoreColumn(a)))
            .limit(5)
            .toList();
    }

    private int scoreColumn(String name) {
        String lower = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int score = 0;
        if (lower.endsWith("id") || lower.contains("_id")) score += 5;
        if (lower.contains("status") || lower.contains("type") || lower.contains("priority")) score += 4;
        if (lower.contains("date") || lower.contains("time") || lower.contains("due")) score += 3;
        if (lower.contains("name") || lower.contains("title") || lower.contains("description")) score += 2;
        return score;
    }

    private boolean matchesAny(List<String> mentionedTables, String candidateTable) {
        if (candidateTable == null || mentionedTables == null) {
            return false;
        }
        return mentionedTables.stream()
            .filter(Objects::nonNull)
            .anyMatch(t -> SchemaObjectNameUtil.referencesSameTable(t, candidateTable));
    }

    private boolean isMeaningfulKeyColumn(KeyColumnAnalysis analysis) {
        if (analysis == null) {
            return false;
        }
        if (analysis.getKeyType() != null && !"NON_KEY".equalsIgnoreCase(analysis.getKeyType())) {
            return true;
        }
        if (analysis.getImportanceScore() != null && analysis.getImportanceScore().compareTo(BigDecimal.ONE) >= 0) {
            return true;
        }
        return safeInt(analysis.getJoinCount()) > 0
            || safeInt(analysis.getWhereCount()) > 0
            || safeInt(analysis.getGroupByCount()) > 0
            || safeInt(analysis.getOrderByCount()) > 0
            || safeInt(analysis.getTotalUsageCount()) > 0;
    }

    private int safeInt(Integer value) {
        return value == null ? 0 : value;
    }

    private TableMetadata resolveExactSchemaTable(SchemaMetadata schema, String question) {
        return SchemaQuestionUtil.resolveExactSchemaTable(schema, question);
    }

    private Map<String, Object> toColumnRow(ColumnMetadata column) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("column", column.getName());
        row.put("type", column.getDataType());
        row.put("nullable", column.getNullable());
        row.put("primaryKey", column.getPrimaryKey());
        row.put("ordinalPosition", column.getOrdinalPosition());
        return row;
    }

    private Map<String, Object> toIndexRow(com.dbaagent.model.IndexMetadata index) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("index", index.getName());
        row.put("columns", index.getColumns());
        row.put("unique", index.getUnique());
        row.put("indexType", index.getIndexType());
        return row;
    }
}
