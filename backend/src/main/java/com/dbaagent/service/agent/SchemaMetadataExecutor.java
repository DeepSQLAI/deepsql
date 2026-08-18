package com.dbaagent.service.agent;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.IndexMetadata;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.SchemaChange;
import com.dbaagent.model.SchemaClassification;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.model.TableClassification;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.model.InferredTableRelationship;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.SchemaChangeRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.service.ChatContextAssembler;
import com.dbaagent.service.ChatQuestionRoutingService;
import com.dbaagent.service.ExactSchemaKeyColumnUtil;
import com.dbaagent.service.ResolvedConversationContext;
import com.dbaagent.service.SchemaChangeTrackingService;
import com.dbaagent.service.SchemaObjectNameUtil;
import com.dbaagent.service.SchemaQuestionUtil;
import com.dbaagent.service.SchemaTableMatchUtil;
import com.dbaagent.service.brain.classification.SchemaClassificationService;
import com.dbaagent.util.PatternUtil;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class SchemaMetadataExecutor {

    private static final Pattern RELATIVE_TIME_WINDOW_PATTERN = Pattern.compile(
        "\\b(?:last|past)\\s+(?:(\\d+)\\s+)?(hour|hours|day|days|week|weeks|month|months)\\b"
    );

    private final ChatContextAssembler contextAssembler;
    private final SchemaClassificationService schemaClassificationService;
    private final TableClassificationRepository tableClassificationRepository;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final SchemaChangeRepository schemaChangeRepository;
    private final SchemaSnapshotRepository schemaSnapshotRepository;
    private final SchemaChangeTrackingService schemaChangeTrackingService;
    private final AnswerVerificationService answerVerificationService;
    private final MetadataExplanationService metadataExplanationService;
    private final ObjectMapper objectMapper;

    @Autowired
    public SchemaMetadataExecutor(
        ChatContextAssembler contextAssembler,
        SchemaClassificationService schemaClassificationService,
        TableClassificationRepository tableClassificationRepository,
        KeyColumnAnalysisRepository keyColumnAnalysisRepository,
        InferredTableRelationshipRepository inferredTableRelationshipRepository,
        SchemaChangeRepository schemaChangeRepository,
        SchemaSnapshotRepository schemaSnapshotRepository,
        SchemaChangeTrackingService schemaChangeTrackingService,
        AnswerVerificationService answerVerificationService,
        MetadataExplanationService metadataExplanationService,
        ObjectMapper objectMapper
    ) {
        this.contextAssembler = contextAssembler;
        this.schemaClassificationService = schemaClassificationService;
        this.tableClassificationRepository = tableClassificationRepository;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
        this.inferredTableRelationshipRepository = inferredTableRelationshipRepository;
        this.schemaChangeRepository = schemaChangeRepository;
        this.schemaSnapshotRepository = schemaSnapshotRepository;
        this.schemaChangeTrackingService = schemaChangeTrackingService;
        this.answerVerificationService = answerVerificationService;
        this.metadataExplanationService = metadataExplanationService;
        this.objectMapper = objectMapper;
    }

    public SchemaMetadataExecutor(
        ChatContextAssembler contextAssembler,
        SchemaClassificationService schemaClassificationService,
        TableClassificationRepository tableClassificationRepository,
        KeyColumnAnalysisRepository keyColumnAnalysisRepository,
        InferredTableRelationshipRepository inferredTableRelationshipRepository,
        SchemaChangeRepository schemaChangeRepository,
        SchemaSnapshotRepository schemaSnapshotRepository,
        SchemaChangeTrackingService schemaChangeTrackingService,
        AnswerVerificationService answerVerificationService,
        MetadataExplanationService metadataExplanationService
    ) {
        this(
            contextAssembler,
            schemaClassificationService,
            tableClassificationRepository,
            keyColumnAnalysisRepository,
            inferredTableRelationshipRepository,
            schemaChangeRepository,
            schemaSnapshotRepository,
            schemaChangeTrackingService,
            answerVerificationService,
            metadataExplanationService,
            new ObjectMapper()
        );
    }

    public Optional<VerifiedAnswer> execute(
        PromptIntent promptIntent,
        ChatQuestionRoutingService.QuestionRoute route,
        String question,
        String connectionId,
        SchemaMetadata schema,
        ResolvedConversationContext resolvedConversationContext
    ) {
        MetadataRequestScope requestScope = MetadataRequestScope.empty(question);
        return execute(promptIntent, route, question, connectionId, schema, resolvedConversationContext, requestScope);
    }

    public Optional<VerifiedAnswer> execute(
        PromptIntent promptIntent,
        ChatQuestionRoutingService.QuestionRoute route,
        String question,
        String connectionId,
        SchemaMetadata schema,
        ResolvedConversationContext resolvedConversationContext,
        MetadataRequestScope requestScope
    ) {
        if (promptIntent == null || promptIntent.domain() != PromptIntent.Domain.SCHEMA || schema == null) {
            return Optional.empty();
        }

        String actualQuestion = question == null ? "" : question.trim();
        ChatQuestionRoutingService.BrainTopic topic = route != null ? route.brainTopic() : ChatQuestionRoutingService.BrainTopic.SCHEMA;

        return switch (topic) {
            case KEY_COLUMNS -> verify(promptIntent, resolvedConversationContext, requestScope, keyColumnEvidence(actualQuestion, connectionId, schema, requestScope));
            case RELATIONSHIPS -> verify(promptIntent, resolvedConversationContext, requestScope, relationshipEvidence(actualQuestion, connectionId, schema, requestScope));
            case CLASSIFICATION -> verify(promptIntent, resolvedConversationContext, requestScope, classificationEvidence(actualQuestion, connectionId, schema, requestScope));
            case SCHEMA, GENERAL, PERFORMANCE, GROWTH, WORKLOAD, TUNING -> verify(promptIntent, resolvedConversationContext, requestScope, schemaEvidence(actualQuestion, connectionId, schema, requestScope));
        };
    }

    private Optional<VerifiedAnswer> verify(
        PromptIntent promptIntent,
        ResolvedConversationContext resolvedConversationContext,
        MetadataRequestScope requestScope,
        DraftMetadataAnswer draft
    ) {
        if (draft == null) {
            return Optional.empty();
        }
        VerificationReport report = answerVerificationService.verify(promptIntent, draft.evidence(), resolvedConversationContext, requestScope);
        if (!report.accepted()) {
            return Optional.empty();
        }
        return Optional.of(new VerifiedAnswer(
            promptIntent,
            draft.evidence(),
            report,
            new AnswerContract(
                draft.title(),
                draft.message(),
                List.of(),
                draft.supportingEvidence(),
                null,
                report.notes(),
                draft.gapsOrCaveats(),
                draft.followUpPrompt()
            ),
            "schema_metadata_executor",
            draft.stepTitle(),
            "lookup"
        ));
    }

    private DraftMetadataAnswer schemaEvidence(String question, String connectionId, SchemaMetadata schema, MetadataRequestScope requestScope) {
        String lowerMessage = question.toLowerCase(Locale.ROOT).trim();
        DraftMetadataAnswer schemaDelta = schemaDeltaEvidence(lowerMessage, connectionId);
        if (schemaDelta != null) {
            return schemaDelta;
        }
        TableMetadata exactSchemaTable = exactSchemaTable(requestScope, schema, question);
        if (exactSchemaTable != null) {
            if (SchemaQuestionUtil.looksLikeExactTableColumnCountQuestion(lowerMessage)) {
                String message = String.format("Table `%s` has **%d columns**.", exactSchemaTable.getName(), columnCount(exactSchemaTable));
                return draft(
                    "Schema Metadata",
                    "Check cached schema snapshot",
                    message,
                    "table_columns",
                    EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                    List.of(row("tableName", exactSchemaTable.getName(), "columnCount", columnCount(exactSchemaTable))),
                    Map.of("tableName", exactSchemaTable.getName(), "columnCount", columnCount(exactSchemaTable)),
                    Set.of(exactSchemaTable.getName()),
                    List.of("Cached schema snapshot"),
                    0.95
                );
            }
            if (SchemaQuestionUtil.looksLikeExactTableColumnListQuestion(lowerMessage)) {
                String message = formatExactTableColumnListAnswer(exactSchemaTable);
                return draft(
                    "Schema Metadata",
                    "Check cached schema snapshot",
                    message,
                    "table_columns",
                    EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                    exactSchemaTable.getColumns().stream().filter(Objects::nonNull).map(column -> row(
                        "column", column.getName(),
                        "type", column.getDataType(),
                        "primaryKey", Boolean.TRUE.equals(column.getPrimaryKey()),
                        "nullable", column.getNullable()
                    )).toList(),
                    Map.of("tableName", exactSchemaTable.getName(), "columnCount", columnCount(exactSchemaTable)),
                    Set.of(exactSchemaTable.getName()),
                    List.of("Cached schema snapshot"),
                    0.98
                );
            }
            if (SchemaQuestionUtil.looksLikeExactTableRowCountQuestion(lowerMessage)) {
                if (exactSchemaTable.getRowCount() == null) {
                    return null;
                }
                String message = String.format(
                    "Table `%s` has an estimated **%s rows** in the current schema snapshot.",
                    exactSchemaTable.getName(),
                    contextAssembler.formatRowCount(exactSchemaTable.getRowCount())
                );
                return draft(
                    "Schema Metadata",
                    "Check cached schema snapshot",
                    message,
                    "table_row_count",
                    EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                    List.of(row("tableName", exactSchemaTable.getName(), "rowCount", exactSchemaTable.getRowCount())),
                    Map.of("tableName", exactSchemaTable.getName(), "rowCount", exactSchemaTable.getRowCount()),
                    Set.of(exactSchemaTable.getName()),
                    List.of("Cached schema snapshot"),
                    0.96
                );
            }
            if (SchemaQuestionUtil.looksLikeExactTableIndexQuestion(lowerMessage)) {
                String message = formatExactTableIndexAnswer(exactSchemaTable, lowerMessage);
                List<Map<String, Object>> indexRows = exactSchemaTable.getIndexes() == null ? List.<Map<String, Object>>of() : exactSchemaTable.getIndexes().stream()
                    .filter(Objects::nonNull)
                    .map(index -> row(
                        "index", index.getName(),
                        "columns", renderIndexColumns(index),
                        "unique", Boolean.TRUE.equals(index.getUnique()),
                        "type", index.getIndexType()
                    ))
                    .toList();
                return draft(
                    "Schema Metadata",
                    "Check cached schema snapshot",
                    message,
                    "table_indexes",
                    EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                    indexRows,
                    Map.of("tableName", exactSchemaTable.getName(), "indexCount", indexRows.size()),
                    Set.of(exactSchemaTable.getName()),
                    List.of("Cached schema snapshot"),
                    indexRows.isEmpty() ? 0.55 : 0.95
                );
            }
        }

        if (hasScopedOrTemporalQualifiers(lowerMessage)) {
            return null;
        }

        boolean asksForTableCount = PatternUtil.containsPattern(lowerMessage, "(how many|count|number of).*(tables?)") && !lowerMessage.contains("rows");
        boolean asksForLargestTables = PatternUtil.containsPattern(lowerMessage, "(largest|biggest|heaviest|top).*tables?")
            || PatternUtil.containsPattern(lowerMessage, "tables?.*(by size|sorted by size|largest|biggest)");

        if (asksForTableCount && asksForLargestTables) {
            long tableCount = resolveTableCount(schema);
            boolean sortBySize = lowerMessage.contains("size") || lowerMessage.contains("bytes") || lowerMessage.contains("storage");
            List<TableMetadata> ranked = rankedPhysicalTables(schema, sortBySize, 10);
            StringBuilder message = new StringBuilder();
            message.append(String.format("You have **%d tables** in the `%s` database.", tableCount, schemaDisplayName(schema)));
            if (ranked.isEmpty()) {
                message.append("\n\nI do not have row-count or size metadata in the cached schema snapshot to rank the largest tables yet.");
            } else {
                message.append(sortBySize ? "\n\n### Largest tables by size\n\n" : "\n\n### Largest tables by row count\n\n");
                message.append(sortBySize ? "| Table | Size | Rows |\n|-------|------|------|\n" : "| Table | Rows | Size |\n|-------|------|------|\n");
                ranked.forEach(table -> message.append(renderRankedTableRow(table, sortBySize)));
            }
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            rows.add(row("tableCount", tableCount, "databaseName", schemaDisplayName(schema)));
            rows.addAll(ranked.stream().map(table -> row(
                "tableName", table.getName(),
                "rowCount", table.getRowCount(),
                "sizeBytes", table.getSizeBytes()
            )).toList());
            return draft(
                "Schema Metadata",
                "Check cached schema snapshot",
                message.toString(),
                sortBySize ? "table_count_and_size_ranking" : "table_count_and_row_ranking",
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                rows,
                Map.of("tableCount", tableCount, "databaseName", schemaDisplayName(schema), "sortBySize", sortBySize),
                ranked.stream().map(TableMetadata::getName).collect(Collectors.toSet()),
                List.of("Cached schema snapshot"),
                ranked.isEmpty() ? 0.68 : 0.95
            );
        }

        if (asksForTableCount) {
            long tableCount = resolveTableCount(schema);
            String message = String.format("You have **%d tables** in the `%s` database.", tableCount, schemaDisplayName(schema));
            return draft(
                "Schema Metadata",
                "Check cached schema snapshot",
                message,
                "table_count",
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                List.of(row("tableCount", tableCount, "databaseName", schemaDisplayName(schema))),
                Map.of("tableCount", tableCount, "databaseName", schemaDisplayName(schema)),
                Set.of(),
                List.of("Cached schema snapshot"),
                0.92
            );
        }

        if (asksForLargestTables) {
            boolean sortBySize = lowerMessage.contains("size") || lowerMessage.contains("bytes") || lowerMessage.contains("storage");
            List<TableMetadata> ranked = rankedPhysicalTables(schema, sortBySize, 15);
            if (!ranked.isEmpty()) {
                StringBuilder sb = new StringBuilder(sortBySize ? "### Largest Tables by Size\n\n" : "### Largest Tables by Row Count\n\n");
                sb.append(sortBySize ? "| Table | Size | Rows |\n|-------|------|------|\n" : "| Table | Rows | Size |\n|-------|------|------|\n");
                ranked.forEach(table -> sb.append(renderRankedTableRow(table, sortBySize)));
                return draft(
                    "Schema Metadata",
                    "Check cached schema snapshot",
                    sb.toString(),
                    sortBySize ? "table_size_ranking" : "table_row_ranking",
                    EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                    ranked.stream().map(table -> row(
                        "tableName", table.getName(),
                        "rowCount", table.getRowCount(),
                        "sizeBytes", table.getSizeBytes()
                    )).toList(),
                    Map.of("sortBySize", sortBySize),
                    ranked.stream().map(TableMetadata::getName).collect(Collectors.toSet()),
                    List.of("Cached schema snapshot"),
                    0.94
                );
            }
        }

        return null;
    }

    private DraftMetadataAnswer schemaDeltaEvidence(String lowerMessage, String connectionId) {
        if (!looksLikeSchemaDeltaQuestion(lowerMessage)) {
            return null;
        }

        Optional<RelativeWindow> requestedWindow = parseRelativeWindow(lowerMessage);
        if (requestedWindow.isPresent()) {
            return schemaDeltaEvidenceForRequestedWindow(lowerMessage, connectionId, requestedWindow.get());
        }

        List<SchemaChange> matchingChanges = filterSchemaChanges(
            schemaChangeRepository.findTop50ByConnectionIdOrderByDetectedAtDesc(connectionId),
            lowerMessage
        );
        if (!matchingChanges.isEmpty()) {
            return buildSchemaDeltaAnswer(
                lowerMessage,
                matchingChanges,
                "Check schema change history",
                "Schema change history",
                null,
                null,
                null
            );
        }

        List<SchemaSnapshot> latestSnapshots = schemaSnapshotRepository.findTop2ByConnectionIdOrderByCapturedAtDesc(connectionId);
        if (latestSnapshots.size() < 2) {
            return insufficiency(
                "Schema Changes",
                "Check schema snapshot history",
                PromptIntent.Domain.SCHEMA,
                "schema_change_summary",
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                Set.of(),
                "I need at least two schema snapshots to compare schema changes for this connection."
            );
        }

        SchemaSnapshot latest = latestSnapshots.get(0);
        SchemaSnapshot previous = latestSnapshots.get(1);
        List<SchemaChange> diffedChanges = filterSchemaChanges(diffSnapshotPair(previous, latest, lowerMessage), lowerMessage);
        if (diffedChanges.isEmpty()) {
            String target = describeRequestedSchemaObject(lowerMessage);
            String message = "Comparing the latest schema snapshots from "
                + formatTimestamp(previous.getCapturedAt())
                + " and "
                + formatTimestamp(latest.getCapturedAt())
                + ", I did not detect any "
                + target
                + " changes that match this request.";
            return draft(
                "Schema Changes",
                "Compare latest schema snapshots",
                message,
                "schema_change_summary",
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                List.of(),
                Map.of(
                    "fromSnapshotId", previous.getId(),
                    "toSnapshotId", latest.getId(),
                    "fromCapturedAt", String.valueOf(previous.getCapturedAt()),
                    "toCapturedAt", String.valueOf(latest.getCapturedAt()),
                    "matchingChangeCount", 0
                ),
                Set.of(),
                List.of("Compared the two latest schema snapshots"),
                0.9
            );
        }

        return buildSchemaDeltaAnswer(
            lowerMessage,
            diffedChanges,
            "Compare latest schema snapshots",
            "Compared the two latest schema snapshots",
            null,
            previous,
            latest
        );
    }

    private DraftMetadataAnswer schemaDeltaEvidenceForRequestedWindow(String lowerMessage, String connectionId, RelativeWindow requestedWindow) {
        List<SchemaSnapshot> snapshots = schemaSnapshotRepository.findByConnectionIdOrderByCapturedAtDesc(connectionId);
        if (snapshots.size() < 2) {
            return insufficiency(
                "Schema Changes",
                "Check schema snapshot history",
                PromptIntent.Domain.SCHEMA,
                "schema_change_summary",
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                Set.of(),
                "I need at least two schema snapshots to compare schema changes for this connection."
            );
        }

        LocalDateTime cutoff = LocalDateTime.now().minus(requestedWindow.amount(), requestedWindow.unit());
        List<SchemaChange> windowChanges = filterSchemaChanges(
            collectSnapshotChangesSince(snapshots, cutoff, lowerMessage),
            lowerMessage
        );
        if (windowChanges.isEmpty()) {
            String target = describeRequestedSchemaObject(lowerMessage);
            String message = "Comparing schema snapshots captured in the last "
                + requestedWindow.label()
                + ", I did not detect any "
                + target
                + " changes that match this request.";
            return draft(
                "Schema Changes",
                "Compare schema snapshots within requested time window",
                message,
                "schema_change_summary",
                EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                List.of(),
                Map.of(
                    "windowLabel", requestedWindow.label(),
                    "windowStart", String.valueOf(cutoff),
                    "matchingChangeCount", 0
                ),
                Set.of(),
                List.of("Compared schema snapshots within the requested time window"),
                0.9
            );
        }

        return buildSchemaDeltaAnswer(
            lowerMessage,
            windowChanges,
            "Compare schema snapshots within requested time window",
            "Compared schema snapshots within the requested time window",
            "Comparing schema snapshots captured in the last " + requestedWindow.label() + ", ",
            null,
            null
        );
    }

    private DraftMetadataAnswer keyColumnEvidence(String question, String connectionId, SchemaMetadata schema, MetadataRequestScope requestScope) {
        String lowerMessage = question.toLowerCase(Locale.ROOT).trim();
        TableMetadata exactSchemaTable = SchemaQuestionUtil.looksLikeExactTableKeyColumnQuestion(question)
            ? exactSchemaTable(requestScope, schema, question)
            : null;

        if (exactSchemaTable != null) {
            List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> exactKeyColumns =
                ExactSchemaKeyColumnUtil.collectKeyColumns(schema, exactSchemaTable);
            List<KeyColumnAnalysis> analyzedColumns = keyColumnAnalysisRepository
                .findByConnectionIdOrderByImportanceScoreDesc(connectionId).stream()
                .filter(analysis -> SchemaObjectNameUtil.referencesSameTable(exactSchemaTable.getName(), analysis.getTableName()))
                .filter(this::isMeaningfulKeyColumn)
                .limit(8)
                .toList();
            List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> resolvedKeyColumns =
                ExactSchemaKeyColumnUtil.mergeWithAnalyzedColumns(exactKeyColumns, analyzedColumns);
            if (!resolvedKeyColumns.isEmpty()) {
                String message = formatExactTableKeyColumnAnswer(exactSchemaTable, resolvedKeyColumns, lowerMessage);
                return draft(
                    "Key Column Analysis",
                    "Check cached key-column analysis",
                    message,
                    "table_key_columns",
                    EvidenceBundle.Source.KEY_COLUMN_ANALYSIS,
                    resolvedKeyColumns.stream().map(descriptor -> row(
                        "column", descriptor.columnName(),
                        "summary", descriptor.summary(),
                        "roles", descriptor.roles()
                    )).toList(),
                    Map.of("tableName", exactSchemaTable.getName(), "columnCount", resolvedKeyColumns.size()),
                    Set.of(exactSchemaTable.getName()),
                    List.of("Cached key-column analysis", "Schema snapshot"),
                    0.96
                );
            }
        }

        List<String> mentionedTables = resolveScopedTables(requestScope, lowerMessage, schema);
        List<KeyColumnAnalysis> rankedColumns = keyColumnAnalysisRepository.findByConnectionIdOrderByImportanceScoreDesc(connectionId);
        if (!mentionedTables.isEmpty()) {
            rankedColumns = rankedColumns.stream()
                .filter(analysis -> matchesMentionedTable(mentionedTables, analysis.getTableName()))
                .toList();
        }
        List<KeyColumnAnalysis> meaningfulColumns = rankedColumns.stream().filter(this::isMeaningfulKeyColumn).toList();
        if (meaningfulColumns.isEmpty()) {
            return insufficiency(
                "Key Column Analysis",
                "Check cached key-column analysis",
                PromptIntent.Domain.SCHEMA,
                "key_columns",
                EvidenceBundle.Source.KEY_COLUMN_ANALYSIS,
                Set.copyOf(mentionedTables),
                "I do not have stored key-column rankings for this connection yet."
            );
        }

        List<KeyColumnAnalysis> topColumns = meaningfulColumns.stream().limit(8).toList();
        String topSummary = topColumns.stream()
            .map(col -> "`" + col.getTableName() + "." + col.getColumnName() + "`")
            .collect(Collectors.joining(", "));
        String message = !mentionedTables.isEmpty()
            ? "Top key columns across the mentioned tables (" + String.join(", ", mentionedTables) + "): " + topSummary + "."
            : "Top key columns from stored metadata: " + topSummary + ".";
        return draft(
            "Key Column Analysis",
            "Check cached key-column analysis",
            message,
            "key_columns",
            EvidenceBundle.Source.KEY_COLUMN_ANALYSIS,
            topColumns.stream().map(col -> row(
                "table", col.getTableName(),
                "column", col.getColumnName(),
                "importance", col.getImportanceScore(),
                "keyType", col.getKeyType()
            )).toList(),
            Map.of("mentionedTables", mentionedTables),
            topColumns.stream().map(KeyColumnAnalysis::getTableName).filter(Objects::nonNull).collect(Collectors.toSet()),
            List.of("Cached key-column analysis"),
            0.9
        );
    }

    private DraftMetadataAnswer relationshipEvidence(String question, String connectionId, SchemaMetadata schema, MetadataRequestScope requestScope) {
        String lowerMessage = question.toLowerCase(Locale.ROOT).trim();
        List<String> mentionedTables = resolveScopedTables(requestScope, lowerMessage, schema);
        boolean pairScoped = requestScope != null && requestScope.pairScoped() && mentionedTables.size() >= 2;
        List<InferredTableRelationship> inferred = inferredTableRelationshipRepository.findHighConfidenceRelationships(
            connectionId, BigDecimal.valueOf(25));
        if (!mentionedTables.isEmpty()) {
            List<InferredTableRelationship> filtered = inferred.stream()
                .filter(rel -> matchesRelationshipScope(mentionedTables, rel.getSourceTable(), rel.getTargetTable(), pairScoped))
                .toList();
            // Only apply the filter when it yields results; fall through to unfiltered on no match
            if (!filtered.isEmpty()) {
                inferred = filtered;
            }
        }
        if (!inferred.isEmpty()) {
            List<Map<String, Object>> rows = inferred.stream().map(rel -> row(
                "source", rel.getSourceTable() + "." + rel.getSourceColumn(),
                "target", rel.getTargetTable() + "." + rel.getTargetColumn(),
                "confidence", rel.getConfidenceScore(),
                "joinCount", rel.getJoinCount()
            )).toList();
            String message;
            if (requestScope != null && requestScope.prefersExplanation() && pairScoped) {
                message = metadataExplanationService.buildPairRelationshipExplanation(requestScope, rows);
            } else {
                List<String> lines = inferred.stream().limit(8).map(rel -> String.format(
                    "- `%s.%s` -> `%s.%s` (%s confidence, observed %dx)",
                    rel.getSourceTable(),
                    rel.getSourceColumn(),
                    rel.getTargetTable(),
                    rel.getTargetColumn(),
                    rel.getConfidenceScore() != null ? rel.getConfidenceScore().stripTrailingZeros().toPlainString() + "%" : "high",
                    rel.getJoinCount() != null ? rel.getJoinCount() : 0
                )).toList();
                message = "Stored relationship metadata:\n" + String.join("\n", lines);
            }
            return draft(
                "Relationship Analysis",
                "Check cached relationship metadata",
                message,
                "relationships",
                EvidenceBundle.Source.INFERRED_RELATIONSHIP,
                rows,
                Map.of("mentionedTables", mentionedTables),
                collectRelationshipTables(inferred),
                List.of("Cached inferred relationships"),
                0.9
            );
        }

        if (schema.getRelationships() != null && !schema.getRelationships().isEmpty()) {
            List<Map<String, Object>> rows = schema.getRelationships().stream()
                .filter(rel -> mentionedTables.isEmpty()
                    || matchesRelationshipScope(mentionedTables, rel.getFromTable(), rel.getToTable(), pairScoped))
                .limit(8)
                .map(rel -> row(
                    "source", rel.getFromTable() + "." + rel.getFromColumn(),
                    "target", rel.getToTable() + "." + rel.getToColumn(),
                    "type", rel.getRelationshipType()
                ))
                .toList();
            if (!rows.isEmpty()) {
                String message;
                if (requestScope != null && requestScope.prefersExplanation() && pairScoped) {
                    message = metadataExplanationService.buildPairRelationshipExplanation(requestScope, rows);
                } else {
                    message = "Cached schema relationships:\n" + rows.stream()
                        .map(row -> "- `" + row.get("source") + "` -> `" + row.get("target") + "`" + (row.get("type") != null ? " (" + row.get("type") + ")" : ""))
                        .collect(Collectors.joining("\n"));
                }
                return draft(
                    "Relationship Analysis",
                    "Check cached relationship metadata",
                    message,
                    "relationships",
                    EvidenceBundle.Source.SCHEMA_SNAPSHOT,
                    rows,
                    Map.of("mentionedTables", mentionedTables),
                    rows.stream()
                        .flatMap(row -> List.of(
                            tableNameFromQualifiedValue(String.valueOf(row.get("source"))),
                            tableNameFromQualifiedValue(String.valueOf(row.get("target")))
                        ).stream())
                        .filter(Objects::nonNull)
                        .collect(Collectors.toSet()),
                    List.of("Cached schema snapshot"),
                    0.85
                );
            }
        }

        // Derive table names from the question for the insufficiency message
        List<String> mentionedForMessage = !mentionedTables.isEmpty()
            ? mentionedTables
            : findMentionedTables(lowerMessage, schema);
        return insufficiency(
            "Relationship Analysis",
            "Check cached relationship metadata",
            PromptIntent.Domain.SCHEMA,
            "relationships",
            EvidenceBundle.Source.INFERRED_RELATIONSHIP,
            Set.copyOf(mentionedForMessage),
            mentionedForMessage.size() >= 2
                ? "I could not find a direct stored relationship between `" + mentionedForMessage.get(0) + "` and `" + mentionedForMessage.get(1) + "`."
                : "I do not have stored relationship metadata for this connection yet."
        );
    }

    private DraftMetadataAnswer classificationEvidence(String question, String connectionId, SchemaMetadata schema, MetadataRequestScope requestScope) {
        String lowerMessage = question.toLowerCase(Locale.ROOT).trim();
        Optional<SchemaClassification> classificationOpt = schemaClassificationService.getLatestClassification(connectionId);
        if (classificationOpt.isEmpty()) {
            return insufficiency(
                "Schema Classification",
                "Check cached classification metadata",
                PromptIntent.Domain.SCHEMA,
                "classification",
                EvidenceBundle.Source.TABLE_CLASSIFICATION,
                Set.of(),
                "I do not have stored schema classification for this connection yet."
            );
        }

        List<TableClassification> tables = tableClassificationRepository.findLatestByConnectionIdOrderByTableNameAsc(connectionId);
        if (tables.isEmpty()) {
            return insufficiency(
                "Schema Classification",
                "Check cached classification metadata",
                PromptIntent.Domain.SCHEMA,
                "classification",
                EvidenceBundle.Source.TABLE_CLASSIFICATION,
                Set.of(),
                "I have schema classification metadata, but no table role details are stored yet for this connection."
            );
        }

        boolean largestQuestion = PatternUtil.containsPattern(lowerMessage, "\\b(largest|biggest|top|heaviest)\\b");
        boolean patternSummaryQuestion = lowerMessage.contains("pattern")
            || (lowerMessage.contains("fact") && lowerMessage.contains("dimension"));
        if (largestQuestion) {
            if (lowerMessage.contains("fact")) {
                return rankedRoleAnswer("Largest FACT tables", tables, "FACT", schema, lowerMessage);
            }
            if (lowerMessage.contains("dimension")) {
                return rankedRoleAnswer("Largest DIMENSION tables", tables, "DIMENSION", schema, lowerMessage);
            }
        }

        if (!patternSummaryQuestion && lowerMessage.contains("fact")) {
            return roleAnswer("FACT tables", tables, "FACT");
        }
        if (!patternSummaryQuestion && lowerMessage.contains("dimension")) {
            return roleAnswer("DIMENSION tables", tables, "DIMENSION");
        }

        SchemaClassification classification = classificationOpt.get();
        List<String> factTables = tables.stream()
            .filter(table -> "FACT".equalsIgnoreCase(table.getTableRole()))
            .limit(5)
            .map(TableClassification::getTableName)
            .toList();
        List<String> dimensionTables = tables.stream()
            .filter(table -> "DIMENSION".equalsIgnoreCase(table.getTableRole()))
            .limit(5)
            .map(TableClassification::getTableName)
            .toList();
        String message = String.format(
            "Stored schema classification shows a **%s** PATTERN with **%d TABLES**. FACT tables: %s. DIMENSION tables: %s.",
            classification.getGlobalPattern(),
            classification.getTotalTables() != null ? classification.getTotalTables() : tables.size(),
            factTables.isEmpty() ? "none identified" : String.join(", ", factTables),
            dimensionTables.isEmpty() ? "none identified" : String.join(", ", dimensionTables)
        );
        return draft(
            "Schema Classification",
            "Check cached classification metadata",
            message,
            "classification_summary",
            EvidenceBundle.Source.TABLE_CLASSIFICATION,
            tables.stream().limit(10).map(table -> row(
                "table", table.getTableName(),
                "role", table.getTableRole(),
                "domain", table.getBusinessDomain()
            )).toList(),
            Map.of("globalPattern", classification.getGlobalPattern(), "tableCount", classification.getTotalTables()),
            tables.stream().map(TableClassification::getTableName).filter(Objects::nonNull).collect(Collectors.toSet()),
            List.of("Cached schema classification"),
            0.92
        );
    }

    private DraftMetadataAnswer roleAnswer(String label, List<TableClassification> tables, String role) {
        List<String> matchingTables = tables.stream()
            .filter(table -> role.equalsIgnoreCase(table.getTableRole()))
            .map(TableClassification::getTableName)
            .filter(Objects::nonNull)
            .distinct()
            .limit(10)
            .toList();
        if (matchingTables.isEmpty()) {
            return insufficiency(
                "Schema Classification",
                "Check cached classification metadata",
                PromptIntent.Domain.SCHEMA,
                "classification_role",
                EvidenceBundle.Source.TABLE_CLASSIFICATION,
                Set.of(),
                "No " + label.toLowerCase(Locale.ROOT) + " are currently identified in stored schema classification."
            );
        }
        String message = label + ": " + matchingTables.stream().map(name -> "`" + name + "`").collect(Collectors.joining(", "));
        return draft(
            "Schema Classification",
            "Check cached classification metadata",
            message,
            "classification_role",
            EvidenceBundle.Source.TABLE_CLASSIFICATION,
            matchingTables.stream().map(name -> row("table", name, "role", role)).toList(),
            Map.of("role", role),
            Set.copyOf(matchingTables),
            List.of("Cached schema classification"),
            0.9
        );
    }

    private DraftMetadataAnswer rankedRoleAnswer(String label, List<TableClassification> classifications, String role, SchemaMetadata schema, String lowerMessage) {
        Map<String, TableMetadata> schemaByTable = schema == null || schema.getTables() == null
            ? Map.of()
            : schema.getTables().stream()
                .filter(Objects::nonNull)
                .filter(table -> table.getName() != null)
                .collect(Collectors.toMap(
                    table -> table.getName().toLowerCase(Locale.ROOT),
                    table -> table,
                    (left, right) -> left
                ));
        boolean sortBySize = lowerMessage.contains("size") || lowerMessage.contains("storage") || lowerMessage.contains("bytes");
        List<TableClassification> matching = classifications.stream()
            .filter(table -> role.equalsIgnoreCase(table.getTableRole()))
            .filter(table -> table.getTableName() != null)
            .collect(Collectors.toMap(
                table -> table.getTableName().toLowerCase(Locale.ROOT),
                table -> table,
                (left, right) -> left,
                LinkedHashMap::new
            ))
            .values()
            .stream()
            .sorted((left, right) -> {
                TableMetadata leftSchema = schemaByTable.get(left.getTableName().toLowerCase(Locale.ROOT));
                TableMetadata rightSchema = schemaByTable.get(right.getTableName().toLowerCase(Locale.ROOT));
                long leftMetric = sortBySize ? safeLong(leftSchema != null ? leftSchema.getSizeBytes() : null) : resolveRowCount(left, leftSchema);
                long rightMetric = sortBySize ? safeLong(rightSchema != null ? rightSchema.getSizeBytes() : null) : resolveRowCount(right, rightSchema);
                int metricCompare = Long.compare(rightMetric, leftMetric);
                if (metricCompare != 0) {
                    return metricCompare;
                }
                return left.getTableName().compareToIgnoreCase(right.getTableName());
            })
            .limit(10)
            .toList();
        if (matching.isEmpty()) {
            return insufficiency(
                "Schema Classification",
                "Check cached classification metadata",
                PromptIntent.Domain.SCHEMA,
                "classification_ranking",
                EvidenceBundle.Source.TABLE_CLASSIFICATION,
                Set.of(),
                "No " + label.toLowerCase(Locale.ROOT) + " are currently identified in stored schema classification."
            );
        }
        StringBuilder response = new StringBuilder(label).append(":\n");
        matching.forEach(table -> {
            TableMetadata schemaTable = schemaByTable.get(table.getTableName().toLowerCase(Locale.ROOT));
            long rowCount = resolveRowCount(table, schemaTable);
            long sizeBytes = schemaTable != null ? safeLong(schemaTable.getSizeBytes()) : 0L;
            response.append("- `").append(table.getTableName()).append("`");
            if (rowCount > 0) {
                response.append(" — ").append(contextAssembler.formatRowCount(rowCount)).append(" rows");
            }
            if (sortBySize && sizeBytes > 0) {
                response.append(" — ").append(contextAssembler.formatBytes(sizeBytes));
            }
            response.append("\n");
        });
        return draft(
            "Schema Classification",
            "Check cached classification metadata",
            response.toString().trim(),
            "classification_ranking",
            EvidenceBundle.Source.TABLE_CLASSIFICATION,
            matching.stream().map(table -> row(
                "table", table.getTableName(),
                "role", table.getTableRole(),
                "rowCount", resolveRowCount(table, schemaByTable.get(table.getTableName().toLowerCase(Locale.ROOT))),
                "sizeBytes", schemaByTable.containsKey(table.getTableName().toLowerCase(Locale.ROOT)) ? schemaByTable.get(table.getTableName().toLowerCase(Locale.ROOT)).getSizeBytes() : null
            )).toList(),
            Map.of("role", role, "sortBySize", sortBySize),
            matching.stream().map(TableClassification::getTableName).collect(Collectors.toSet()),
            List.of("Cached schema classification", "Schema snapshot"),
            0.95
        );
    }

    private DraftMetadataAnswer draft(
        String title,
        String stepTitle,
        String message,
        String answerType,
        EvidenceBundle.Source source,
        List<Map<String, Object>> rows,
        Map<String, Object> payload,
        Set<String> supportingObjectNames,
        List<String> supportingEvidence,
        double confidence
    ) {
        EvidenceBundle evidence = EvidenceBundle.sufficient(
            PromptIntent.Domain.SCHEMA,
            answerType,
            source,
            answerType,
            rows,
            payload,
            0.8,
            confidence,
            "cached_metadata",
            null,
            supportingObjectNames
        );
        return new DraftMetadataAnswer(title, stepTitle, message, evidence, supportingEvidence, List.of(), null);
    }

    private DraftMetadataAnswer insufficiency(
        String title,
        String stepTitle,
        PromptIntent.Domain domain,
        String answerType,
        EvidenceBundle.Source source,
        Set<String> supportingObjectNames,
        String insufficiencyMessage
    ) {
        EvidenceBundle evidence = EvidenceBundle.insufficient(
            domain,
            answerType,
            source,
            answerType,
            Map.of("reason", insufficiencyMessage),
            0.7,
            0.75,
            "cached_metadata",
            supportingObjectNames,
            insufficiencyMessage
        );
        return new DraftMetadataAnswer(title, stepTitle, insufficiencyMessage, evidence, List.of("Cached metadata"), List.of(), null);
    }

    private DraftMetadataAnswer buildSchemaDeltaAnswer(
        String lowerMessage,
        List<SchemaChange> changes,
        String stepTitle,
        String supportingEvidence,
        String introOverride,
        SchemaSnapshot previousSnapshot,
        SchemaSnapshot latestSnapshot
    ) {
        List<SchemaChange> topChanges = changes.stream().limit(12).toList();
        String changeLabel = describeChangeSet(lowerMessage, topChanges);
        String intro = introOverride != null
            ? introOverride
            : previousSnapshot != null && latestSnapshot != null
            ? "Comparing the latest schema snapshots from " + formatTimestamp(previousSnapshot.getCapturedAt())
                + " and " + formatTimestamp(latestSnapshot.getCapturedAt()) + ", "
            : "From stored schema change history, ";
        String message = intro + "I found " + topChanges.size() + " " + changeLabel + ":\n"
            + topChanges.stream().map(this::formatSchemaChangeLine).collect(Collectors.joining("\n"));

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("changeCount", topChanges.size());
        payload.put("changeTypes", topChanges.stream().map(change -> change.getChangeType().name()).distinct().toList());
        if (previousSnapshot != null && latestSnapshot != null) {
            payload.put("fromSnapshotId", previousSnapshot.getId());
            payload.put("toSnapshotId", latestSnapshot.getId());
            payload.put("fromCapturedAt", String.valueOf(previousSnapshot.getCapturedAt()));
            payload.put("toCapturedAt", String.valueOf(latestSnapshot.getCapturedAt()));
        } else {
            LocalDateTime latestDetectedAt = topChanges.stream()
                .map(SchemaChange::getDetectedAt)
                .filter(Objects::nonNull)
                .max(LocalDateTime::compareTo)
                .orElse(null);
            if (latestDetectedAt != null) {
                payload.put("latestDetectedAt", String.valueOf(latestDetectedAt));
            }
        }

        return draft(
            "Schema Changes",
            stepTitle,
            message,
            "schema_change_summary",
            EvidenceBundle.Source.SCHEMA_SNAPSHOT,
            topChanges.stream().map(change -> row(
                "changeType", change.getChangeType().name(),
                "objectType", change.getObjectType().name(),
                "objectName", change.getObjectName(),
                "tableName", change.getTableName(),
                "severity", change.getSeverity() != null ? change.getSeverity().name() : null,
                "detectedAt", change.getDetectedAt() != null ? String.valueOf(change.getDetectedAt()) : null
            )).toList(),
            payload,
            topChanges.stream()
                .flatMap(change -> java.util.stream.Stream.of(change.getTableName(), change.getObjectName()))
                .filter(Objects::nonNull)
                .collect(Collectors.toSet()),
            List.of(supportingEvidence),
            0.94
        );
    }

    private List<SchemaChange> filterSchemaChanges(List<SchemaChange> changes, String lowerMessage) {
        if (changes == null || changes.isEmpty()) {
            return List.of();
        }

        Set<SchemaChange.ObjectType> objectTypes = requestedObjectTypes(lowerMessage);
        Set<SchemaChange.ChangeType> changeTypes = requestedChangeTypes(lowerMessage, objectTypes);

        return changes.stream()
            .filter(Objects::nonNull)
            .filter(change -> objectTypes.isEmpty() || objectTypes.contains(change.getObjectType()))
            .filter(change -> changeTypes.isEmpty() || changeTypes.contains(change.getChangeType()))
            .sorted(Comparator
                .comparing((SchemaChange change) -> change.getDetectedAt() != null ? change.getDetectedAt() : LocalDateTime.MIN)
                .reversed()
                .thenComparing(SchemaChange::getObjectName, Comparator.nullsLast(String.CASE_INSENSITIVE_ORDER)))
            .toList();
    }

    private boolean looksLikeSchemaDeltaQuestion(String lowerMessage) {
        return containsAny(lowerMessage, "latest", "recent", "new", "added", "created", "removed", "dropped", "change", "changes", "changed", "modified", "updated", "altered", "delta", "drift")
            && containsAny(lowerMessage, "schema", "table", "tables", "column", "columns", "field", "fields", "index", "indexes", "constraint", "foreign key");
    }

    private Set<SchemaChange.ObjectType> requestedObjectTypes(String lowerMessage) {
        if (containsAny(lowerMessage, "foreign key", "foreign keys")) {
            return Set.of(SchemaChange.ObjectType.FOREIGN_KEY);
        }
        if (containsAny(lowerMessage, "constraint", "constraints")) {
            return Set.of(SchemaChange.ObjectType.CONSTRAINT);
        }
        if (containsAny(lowerMessage, "index", "indexes", "indices")) {
            return Set.of(SchemaChange.ObjectType.INDEX);
        }
        if (containsAny(lowerMessage, "column", "columns", "field", "fields")) {
            return Set.of(SchemaChange.ObjectType.COLUMN);
        }
        if (containsAny(lowerMessage, "table", "tables")) {
            return Set.of(SchemaChange.ObjectType.TABLE);
        }
        return Set.of();
    }

    private Set<SchemaChange.ChangeType> requestedChangeTypes(String lowerMessage, Set<SchemaChange.ObjectType> objectTypes) {
        boolean wantsAdded = containsAny(lowerMessage, "added", "new", "latest", "recent");
        boolean wantsRemoved = containsAny(lowerMessage, "removed", "dropped", "deleted");
        boolean wantsModified = containsAny(lowerMessage, "changed", "modified", "updated", "altered", "delta", "drift");

        if (!wantsAdded && !wantsRemoved && !wantsModified) {
            return Set.of();
        }

        Set<SchemaChange.ChangeType> changeTypes = new java.util.LinkedHashSet<>();
        Set<SchemaChange.ObjectType> effectiveObjectTypes = objectTypes.isEmpty()
            ? Set.of(
                SchemaChange.ObjectType.TABLE,
                SchemaChange.ObjectType.COLUMN,
                SchemaChange.ObjectType.INDEX,
                SchemaChange.ObjectType.CONSTRAINT,
                SchemaChange.ObjectType.FOREIGN_KEY
            )
            : objectTypes;

        for (SchemaChange.ObjectType objectType : effectiveObjectTypes) {
            if (wantsAdded) {
                addChangeType(changeTypes, objectType, "ADDED");
            }
            if (wantsRemoved) {
                addChangeType(changeTypes, objectType, "REMOVED");
            }
            if (wantsModified && objectType == SchemaChange.ObjectType.COLUMN) {
                changeTypes.add(SchemaChange.ChangeType.COLUMN_MODIFIED);
            }
        }
        return Set.copyOf(changeTypes);
    }

    private void addChangeType(Set<SchemaChange.ChangeType> changeTypes, SchemaChange.ObjectType objectType, String suffix) {
        try {
            changeTypes.add(SchemaChange.ChangeType.valueOf(objectType.name() + "_" + suffix));
        } catch (IllegalArgumentException ignored) {
            // Some object types do not currently have all change variants.
        }
    }

    private String formatSchemaChangeLine(SchemaChange change) {
        String objectRef = switch (change.getObjectType()) {
            case TABLE -> "`" + change.getObjectName() + "`";
            case COLUMN -> "`" + change.getTableName() + "." + change.getObjectName() + "`";
            case INDEX, CONSTRAINT, FOREIGN_KEY -> change.getTableName() != null
                ? "`" + change.getObjectName() + "` on `" + change.getTableName() + "`"
                : "`" + change.getObjectName() + "`";
        };
        String when = change.getDetectedAt() != null ? " (" + formatTimestamp(change.getDetectedAt()) + ")" : "";
        return switch (change.getChangeType()) {
            case TABLE_ADDED -> "- Added table " + objectRef + when;
            case TABLE_REMOVED -> "- Removed table " + objectRef + when;
            case COLUMN_ADDED -> "- Added column " + objectRef + when;
            case COLUMN_REMOVED -> "- Removed column " + objectRef + when;
            case COLUMN_MODIFIED -> "- Modified column " + objectRef + formatColumnChangeDetails(change) + when;
            case INDEX_ADDED -> "- Added index " + objectRef + when;
            case INDEX_REMOVED -> "- Removed index " + objectRef + when;
            case INDEX_MODIFIED -> "- Modified index " + objectRef + when;
            case CONSTRAINT_ADDED -> "- Added constraint " + objectRef + when;
            case CONSTRAINT_REMOVED -> "- Removed constraint " + objectRef + when;
            case FOREIGN_KEY_ADDED -> "- Added foreign key " + objectRef + when;
            case FOREIGN_KEY_REMOVED -> "- Removed foreign key " + objectRef + when;
        };
    }

    private String formatColumnChangeDetails(SchemaChange change) {
        if (change.getChangeDetails() == null) {
            return "";
        }
        Object changes = change.getChangeDetails().get("changes");
        if (!(changes instanceof List<?> diffs) || diffs.isEmpty()) {
            return "";
        }
        String detail = diffs.stream().filter(Objects::nonNull).map(String::valueOf).limit(2).collect(Collectors.joining(", "));
        return detail.isBlank() ? "" : " [" + detail + "]";
    }

    private String describeChangeSet(String lowerMessage, List<SchemaChange> changes) {
        String objectLabel = describeRequestedSchemaObject(lowerMessage);
        boolean singular = changes.size() == 1;
        if (containsAny(lowerMessage, "added", "new", "latest", "recent")) {
            return singular ? objectLabel.substring(0, objectLabel.length() - 1) + " addition" : objectLabel + " additions";
        }
        if (containsAny(lowerMessage, "removed", "dropped", "deleted")) {
            return singular ? objectLabel.substring(0, objectLabel.length() - 1) + " removal" : objectLabel + " removals";
        }
        if (containsAny(lowerMessage, "changed", "modified", "updated", "altered", "delta", "drift")) {
            return singular ? objectLabel.substring(0, objectLabel.length() - 1) + " change" : objectLabel + " changes";
        }
        return singular ? "schema change" : "schema changes";
    }

    private String describeRequestedSchemaObject(String lowerMessage) {
        if (containsAny(lowerMessage, "foreign key", "foreign keys")) {
            return "foreign keys";
        }
        if (containsAny(lowerMessage, "constraint", "constraints")) {
            return "constraints";
        }
        if (containsAny(lowerMessage, "index", "indexes", "indices")) {
            return "indexes";
        }
        if (containsAny(lowerMessage, "column", "columns", "field", "fields")) {
            return "columns";
        }
        if (containsAny(lowerMessage, "table", "tables")) {
            return "tables";
        }
        return "schema objects";
    }

    private String formatTimestamp(LocalDateTime timestamp) {
        if (timestamp == null) {
            return "unknown time";
        }
        return timestamp.format(DateTimeFormatter.ofPattern("MMM d, yyyy h:mm a", Locale.ENGLISH));
    }

    private boolean containsAny(String lowerMessage, String... tokens) {
        for (String token : tokens) {
            if (lowerMessage.contains(token)) {
                return true;
            }
        }
        return false;
    }

    private List<SchemaChange> collectSnapshotChangesSince(List<SchemaSnapshot> snapshotsDescending, LocalDateTime cutoff, String lowerMessage) {
        if (snapshotsDescending == null || snapshotsDescending.size() < 2) {
            return List.of();
        }

        List<SchemaSnapshot> snapshots = snapshotsDescending.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(SchemaSnapshot::getCapturedAt, Comparator.nullsLast(LocalDateTime::compareTo)))
            .toList();

        int firstRelevantIndex = -1;
        for (int i = 0; i < snapshots.size(); i++) {
            LocalDateTime capturedAt = snapshots.get(i).getCapturedAt();
            if (capturedAt != null && !capturedAt.isBefore(cutoff)) {
                firstRelevantIndex = i;
                break;
            }
        }

        if (firstRelevantIndex < 0) {
            return List.of();
        }

        int comparisonStartIndex = Math.max(1, firstRelevantIndex);
        List<SchemaChange> aggregated = new java.util.ArrayList<>();
        Set<String> seenKeys = new LinkedHashSet<>();

        for (int i = comparisonStartIndex; i < snapshots.size(); i++) {
            SchemaSnapshot previous = snapshots.get(i - 1);
            SchemaSnapshot current = snapshots.get(i);
            for (SchemaChange change : diffSnapshotPair(previous, current, lowerMessage)) {
                SchemaChange enriched = enrichSnapshotDiff(change, previous, current);
                String dedupeKey = String.join("|",
                    enriched.getChangeType().name(),
                    String.valueOf(enriched.getObjectType()),
                    String.valueOf(enriched.getObjectName()),
                    String.valueOf(enriched.getTableName()),
                    String.valueOf(enriched.getFromSnapshotId()),
                    String.valueOf(enriched.getToSnapshotId())
                );
                if (seenKeys.add(dedupeKey)) {
                    aggregated.add(enriched);
                }
            }
        }

        return aggregated;
    }

    private List<SchemaChange> diffSnapshotPair(SchemaSnapshot previous, SchemaSnapshot current, String lowerMessage) {
        List<SchemaChange> rawChanges = schemaChangeTrackingService.detectChanges(previous, current);
        if (!wantsColumnFocusedSchemaChanges(lowerMessage)) {
            return rawChanges;
        }

        List<SchemaChange> enriched = new java.util.ArrayList<>(rawChanges);
        enriched.addAll(synthesizeColumnAdditionsFromNewTables(rawChanges, current));
        return enriched;
    }

    private boolean wantsColumnFocusedSchemaChanges(String lowerMessage) {
        return lowerMessage != null && containsAny(lowerMessage, "column", "columns", "field", "fields");
    }

    private List<SchemaChange> synthesizeColumnAdditionsFromNewTables(List<SchemaChange> changes, SchemaSnapshot currentSnapshot) {
        if (changes == null || changes.isEmpty() || currentSnapshot == null || currentSnapshot.getSchemaJson() == null) {
            return List.of();
        }

        Map<String, Map<String, Object>> tablesByName = parseSnapshotTables(currentSnapshot.getSchemaJson());
        if (tablesByName.isEmpty()) {
            return List.of();
        }

        List<SchemaChange> syntheticChanges = new java.util.ArrayList<>();
        for (SchemaChange change : changes) {
            if (change == null || change.getChangeType() != SchemaChange.ChangeType.TABLE_ADDED || change.getObjectName() == null) {
                continue;
            }

            Map<String, Object> table = tablesByName.get(change.getObjectName());
            if (table == null) {
                continue;
            }

            for (Map<String, Object> column : extractNamedObjects(table.get("columns"))) {
                String columnName = stringValue(column.get("name"));
                if (columnName == null || columnName.isBlank()) {
                    continue;
                }
                syntheticChanges.add(SchemaChange.builder()
                    .id(change.getId() + "::column::" + columnName)
                    .connectionId(change.getConnectionId())
                    .fromSnapshotId(change.getFromSnapshotId())
                    .toSnapshotId(change.getToSnapshotId())
                    .changeType(SchemaChange.ChangeType.COLUMN_ADDED)
                    .objectType(SchemaChange.ObjectType.COLUMN)
                    .objectName(columnName)
                    .tableName(change.getObjectName())
                    .changeDetails(Map.of("column", column, "source", "table_added"))
                    .severity(SchemaChange.Severity.INFO)
                    .isBreakingChange(false)
                    .isAcknowledged(false)
                    .detectedAt(currentSnapshot.getCapturedAt())
                    .build());
            }
        }

        return syntheticChanges;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> parseSnapshotTables(String schemaJson) {
        if (schemaJson == null || schemaJson.isBlank()) {
            return Map.of();
        }
        try {
            Map<String, Object> schema = objectMapper.readValue(schemaJson, new TypeReference<Map<String, Object>>() {});
            Object rawTables = schema.get("tables");
            Map<String, Map<String, Object>> tablesByName = new LinkedHashMap<>();

            if (rawTables instanceof Map<?, ?> tableMap) {
                for (Map.Entry<?, ?> entry : tableMap.entrySet()) {
                    if (entry.getKey() == null || !(entry.getValue() instanceof Map<?, ?> valueMap)) {
                        continue;
                    }
                    tablesByName.put(String.valueOf(entry.getKey()), new LinkedHashMap<>((Map<String, Object>) valueMap));
                }
                return tablesByName;
            }

            if (rawTables instanceof List<?> tableList) {
                for (Object tableObj : tableList) {
                    if (!(tableObj instanceof Map<?, ?> tableMapEntry)) {
                        continue;
                    }
                    String tableName = stringValue(tableMapEntry.get("name"));
                    if (tableName == null || tableName.isBlank()) {
                        continue;
                    }
                    tablesByName.put(tableName, new LinkedHashMap<>((Map<String, Object>) tableMapEntry));
                }
            }

            return tablesByName;
        } catch (Exception ignored) {
            return Map.of();
        }
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> extractNamedObjects(Object rawObjects) {
        if (!(rawObjects instanceof List<?> items)) {
            return List.of();
        }
        List<Map<String, Object>> namedObjects = new java.util.ArrayList<>();
        for (Object item : items) {
            if (item instanceof Map<?, ?> mapItem) {
                namedObjects.add(new LinkedHashMap<>((Map<String, Object>) mapItem));
            }
        }
        return namedObjects;
    }

    private String stringValue(Object value) {
        return value == null ? null : String.valueOf(value);
    }

    private SchemaChange enrichSnapshotDiff(SchemaChange change, SchemaSnapshot previous, SchemaSnapshot current) {
        if (change == null) {
            return null;
        }
        return SchemaChange.builder()
            .id(change.getId())
            .connectionId(change.getConnectionId())
            .fromSnapshotId(previous != null ? previous.getId() : change.getFromSnapshotId())
            .toSnapshotId(current != null ? current.getId() : change.getToSnapshotId())
            .changeType(change.getChangeType())
            .objectType(change.getObjectType())
            .objectName(change.getObjectName())
            .tableName(change.getTableName())
            .changeDetails(change.getChangeDetails())
            .severity(change.getSeverity())
            .isBreakingChange(change.getIsBreakingChange())
            .isAcknowledged(change.getIsAcknowledged())
            .acknowledgedBy(change.getAcknowledgedBy())
            .acknowledgedAt(change.getAcknowledgedAt())
            .detectedAt(current != null && current.getCapturedAt() != null ? current.getCapturedAt() : change.getDetectedAt())
            .build();
    }

    private Optional<RelativeWindow> parseRelativeWindow(String lowerMessage) {
        if (lowerMessage == null || lowerMessage.isBlank()) {
            return Optional.empty();
        }

        Matcher matcher = RELATIVE_TIME_WINDOW_PATTERN.matcher(lowerMessage);
        if (!matcher.find()) {
            return Optional.empty();
        }

        String rawAmount = matcher.group(1);
        long amount = rawAmount == null || rawAmount.isBlank() ? 1L : Long.parseLong(rawAmount);
        String unitToken = matcher.group(2);
        if (unitToken == null || unitToken.isBlank()) {
            return Optional.empty();
        }

        java.time.temporal.ChronoUnit unit = switch (unitToken) {
            case "hour", "hours" -> java.time.temporal.ChronoUnit.HOURS;
            case "day", "days" -> java.time.temporal.ChronoUnit.DAYS;
            case "week", "weeks" -> java.time.temporal.ChronoUnit.WEEKS;
            case "month", "months" -> java.time.temporal.ChronoUnit.MONTHS;
            default -> null;
        };
        if (unit == null) {
            return Optional.empty();
        }

        String label = amount + " " + unitToken;
        return Optional.of(new RelativeWindow(amount, unit, label));
    }

    private record RelativeWindow(long amount, java.time.temporal.ChronoUnit unit, String label) {
    }

    private TableMetadata exactSchemaTable(MetadataRequestScope requestScope, SchemaMetadata schema, String question) {
        if (requestScope != null && requestScope.isSingleTableScoped() && requestScope.exact()) {
            String requestedTable = requestScope.requestedTables().getFirst();
            if (schema != null && schema.getTables() != null) {
                return schema.getTables().stream()
                    .filter(Objects::nonNull)
                    .filter(table -> requestedTable.equalsIgnoreCase(table.getName()))
                    .findFirst()
                    .orElse(null);
            }
        }
        return SchemaQuestionUtil.resolveExactSchemaTable(schema, question);
    }

    private List<String> resolveScopedTables(MetadataRequestScope requestScope, String question, SchemaMetadata schema) {
        if (requestScope != null && requestScope.hasRequestedTables()) {
            return requestScope.requestedTables();
        }
        return findMentionedTables(question, schema);
    }

    private boolean matchesRelationshipScope(List<String> requestedTables, String sourceTable, String targetTable, boolean pairScoped) {
        if (requestedTables == null || requestedTables.isEmpty()) {
            return true;
        }
        if (!pairScoped) {
            return matchesMentionedTable(requestedTables, sourceTable)
                || matchesMentionedTable(requestedTables, targetTable);
        }
        return requestedTables.stream().allMatch(requested ->
            requested.equalsIgnoreCase(sourceTable) || requested.equalsIgnoreCase(targetTable));
    }

    private Set<String> collectRelationshipTables(List<InferredTableRelationship> relationships) {
        return relationships.stream()
            .flatMap(rel -> List.of(rel.getSourceTable(), rel.getTargetTable()).stream())
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
    }

    private String tableNameFromQualifiedValue(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        int idx = value.indexOf('.');
        return idx > 0 ? value.substring(0, idx) : value;
    }

    private List<String> findMentionedTables(String question, SchemaMetadata schema) {
        if (question == null || schema == null || schema.getTables() == null || schema.getTables().isEmpty()) {
            return List.of();
        }
        String normalizedQuestion = SchemaTableMatchUtil.normalizeQuestion(question);
        return schema.getTables().stream()
            .map(TableMetadata::getName)
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(String::length).reversed())
            .filter(tableName -> SchemaTableMatchUtil.mentionsTable(normalizedQuestion, tableName))
            .distinct()
            .toList();
    }

    private boolean matchesMentionedTable(List<String> mentionedTables, String candidateTableName) {
        if (candidateTableName == null || mentionedTables == null || mentionedTables.isEmpty()) {
            return false;
        }
        return mentionedTables.stream().anyMatch(table -> table.equalsIgnoreCase(candidateTableName));
    }

    /**
     * Returns true when {@code word} appears as a whole word inside {@code paddedText}.
     * Both arguments should already be lowercased. The text should be padded with spaces
     * on both ends so boundary checks work at the start/end.
     * A word boundary is any character that is not a letter or digit.
     */
    private boolean containsWholeWord(String paddedText, String word) {
        if (word == null || word.isBlank() || paddedText == null) return false;
        int idx = paddedText.indexOf(word);
        while (idx >= 0) {
            boolean startOk = idx == 0 || !Character.isLetterOrDigit(paddedText.charAt(idx - 1));
            int end = idx + word.length();
            boolean endOk = end >= paddedText.length() || !Character.isLetterOrDigit(paddedText.charAt(end));
            if (startOk && endOk) return true;
            idx = paddedText.indexOf(word, idx + 1);
        }
        return false;
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

    private int columnCount(TableMetadata table) {
        return table.getColumns() == null ? 0 : table.getColumns().size();
    }

    private String formatExactTableColumnListAnswer(TableMetadata table) {
        int columnCount = columnCount(table);
        StringBuilder sb = new StringBuilder();
        sb.append("Table `").append(table.getName()).append("` has **").append(columnCount).append(" columns**.\n\n");
        if (columnCount == 0) {
            sb.append("I don’t have column details for this table in the current schema snapshot.");
            return sb.toString();
        }
        sb.append("Columns:\n");
        table.getColumns().stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparingInt(column -> column.getOrdinalPosition() != null ? column.getOrdinalPosition() : Integer.MAX_VALUE))
            .forEach(column -> sb.append("- ").append(formatColumnLine(column)).append("\n"));
        return sb.toString();
    }

    private String formatExactTableIndexAnswer(TableMetadata table, String lowerQuestion) {
        List<IndexMetadata> indexes = table.getIndexes() != null ? table.getIndexes().stream().filter(Objects::nonNull).toList() : List.of();
        boolean countQuestion = SchemaQuestionUtil.looksLikeExactTableIndexCountQuestion(lowerQuestion);
        if (countQuestion) {
            return String.format("Table `%s` has **%d indexes** in the current schema snapshot.", table.getName(), indexes.size());
        }
        StringBuilder sb = new StringBuilder();
        sb.append("Table `").append(table.getName()).append("` has **").append(indexes.size())
            .append(" indexes** in the current schema snapshot.\n\n");
        if (indexes.isEmpty()) {
            sb.append("I don’t have any index entries recorded for this table.");
            return sb.toString();
        }
        sb.append("Indexes:\n");
        indexes.stream()
            .sorted(Comparator.comparing(index -> index.getName() != null ? index.getName() : "", String.CASE_INSENSITIVE_ORDER))
            .forEach(index -> sb.append("- ").append(formatIndexLine(index)).append("\n"));
        return sb.toString();
    }

    private String formatExactTableKeyColumnAnswer(TableMetadata table, List<ExactSchemaKeyColumnUtil.KeyColumnDescriptor> keyColumns, String lowerQuestion) {
        boolean countQuestion = PatternUtil.containsPattern(lowerQuestion, "(how many|count|number of).*(key columns?|primary keys?|foreign keys?|join columns?)");
        if (countQuestion) {
            return String.format(
                "Table `%s` has **%d key columns**: %s.",
                table.getName(),
                keyColumns.size(),
                keyColumns.stream().map(descriptor -> "`" + descriptor.columnName() + "`").collect(Collectors.joining(", "))
            );
        }
        StringBuilder sb = new StringBuilder();
        sb.append("The most relevant key columns in `")
            .append(table.getName())
            .append("` are:\n");
        keyColumns.forEach(descriptor -> sb.append("- `")
            .append(descriptor.columnName())
            .append("` — ")
            .append(descriptor.summary())
            .append("\n"));
        return sb.toString();
    }

    private String formatColumnLine(ColumnMetadata column) {
        StringBuilder attributes = new StringBuilder();
        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
            attributes.append("primary key");
        }
        if (column.getNullable() != null) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(Boolean.TRUE.equals(column.getNullable()) ? "nullable" : "not null");
        }
        return "`" + Objects.toString(column.getName(), "?") + "` — `"
            + Objects.toString(column.getDataType(), "?") + "`"
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private String formatIndexLine(IndexMetadata index) {
        StringBuilder attributes = new StringBuilder();
        if (Boolean.TRUE.equals(index.getUnique())) {
            attributes.append("unique");
        }
        if (index.getIndexType() != null && !index.getIndexType().isBlank()) {
            if (attributes.length() > 0) {
                attributes.append("; ");
            }
            attributes.append(index.getIndexType().toLowerCase(Locale.ROOT));
        }
        return "`" + Objects.toString(index.getName(), "?") + "` — columns: " + renderIndexColumns(index)
            + (attributes.length() > 0 ? "; " + attributes : "");
    }

    private String renderIndexColumns(IndexMetadata index) {
        List<String> columns = index.getColumns() == null ? List.of() : index.getColumns().stream().filter(Objects::nonNull).toList();
        if (columns.isEmpty()) {
            return "unspecified columns";
        }
        return columns.stream().map(column -> "`" + column + "`").collect(Collectors.joining(", "));
    }

    private boolean hasScopedOrTemporalQualifiers(String lowerMessage) {
        if (PatternUtil.containsPattern(lowerMessage, "\\b(in schema|in the .* schema|schema\\s+\\w+)\\b")) {
            return true;
        }
        if (PatternUtil.containsPattern(lowerMessage, "\\b(on|in|for|of)\\s+(the\\s+)?\\w+\\s*(table)?\\b")
            && (lowerMessage.contains("index") || lowerMessage.contains("column") || lowerMessage.contains("constraint") || lowerMessage.contains("foreign key"))) {
            return true;
        }
        if (PatternUtil.containsPattern(lowerMessage, "\\b(today|yesterday|last week|last month|this week|this month|since|after|before|created|added|modified|updated|recent|new)\\b")) {
            return true;
        }
        if (PatternUtil.containsPattern(lowerMessage, "\\b(where|with|that have|that are|containing|larger than|smaller than|more than|less than|greater|empty|non-empty)\\b")) {
            return true;
        }
        if (PatternUtil.containsPattern(lowerMessage, "\\b(like|starting with|ending with|matching|named|called)\\b")) {
            return true;
        }
        return false;
    }

    private long resolveTableCount(SchemaMetadata schema) {
        if (schema.getTotalTables() != null) {
            return schema.getTotalTables();
        }
        if (schema.getTables() == null) {
            return 0;
        }
        return schema.getTables().stream().filter(this::isPhysicalTable).count();
    }

    private List<TableMetadata> rankedPhysicalTables(SchemaMetadata schema, boolean sortBySize, int limit) {
        if (schema == null || schema.getTables() == null) {
            return List.of();
        }
        return schema.getTables().stream()
            .filter(this::isPhysicalTable)
            .filter(table -> sortBySize ? table.getSizeBytes() != null : table.getRowCount() != null)
            .sorted((left, right) -> Long.compare(
                sortBySize ? safeLong(right.getSizeBytes()) : safeLong(right.getRowCount()),
                sortBySize ? safeLong(left.getSizeBytes()) : safeLong(left.getRowCount())
            ))
            .limit(limit)
            .toList();
    }

    private String renderRankedTableRow(TableMetadata table, boolean sortBySize) {
        if (sortBySize) {
            return String.format(
                "| `%s` | %s | %s |\n",
                table.getName(),
                contextAssembler.formatBytes(table.getSizeBytes()),
                table.getRowCount() != null ? contextAssembler.formatRowCount(table.getRowCount()) : "?"
            );
        }
        return String.format(
            "| `%s` | %s | %s |\n",
            table.getName(),
            contextAssembler.formatRowCount(table.getRowCount()),
            table.getSizeBytes() != null ? contextAssembler.formatBytes(table.getSizeBytes()) : "?"
        );
    }

    private boolean isPhysicalTable(TableMetadata table) {
        return table != null && !isView(table);
    }

    private boolean isView(TableMetadata table) {
        return table != null && "view".equalsIgnoreCase(table.getType());
    }

    private String schemaDisplayName(SchemaMetadata schema) {
        return schema.getDatabaseName() != null && !schema.getDatabaseName().isBlank() ? schema.getDatabaseName() : "database";
    }

    private long resolveRowCount(TableClassification classification, TableMetadata schemaTable) {
        if (schemaTable != null && schemaTable.getRowCount() != null && schemaTable.getRowCount() > 0) {
            return schemaTable.getRowCount();
        }
        if (classification != null && classification.getRowCount() != null && classification.getRowCount() > 0) {
            return classification.getRowCount();
        }
        return 0L;
    }

    private long safeLong(Long value) {
        return value != null ? value : 0L;
    }

    private int safeInt(Integer value) {
        return value != null ? value : 0;
    }

    private Map<String, Object> row(Object... values) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int i = 0; i + 1 < values.length; i += 2) {
            row.put(String.valueOf(values[i]), values[i + 1]);
        }
        return row;
    }

    private record DraftMetadataAnswer(
        String title,
        String stepTitle,
        String message,
        EvidenceBundle evidence,
        List<String> supportingEvidence,
        List<String> gapsOrCaveats,
        String followUpPrompt
    ) {
    }
}
