package com.dbaagent.service;

import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.QualifiedTableName;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TrainingDataEmbedding;
import com.dbaagent.service.security.AccessControlService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class ChatRetrievalContextService {

    private final TrainingService trainingService;
    private final CompanyKnowledgeService companyKnowledgeService;
    private final ObjectMapper objectMapper;
    private final UserDataAccessPolicyService userDataAccessPolicyService;
    private final AccessControlService accessControlService;

    @Value("${app.chat.rag.general-top-k:20}")
    private int ragGeneralTopK;

    @Value("${app.chat.rag.intent-top-k:25}")
    private int ragIntentTopK;

    public RetrievalIntent detectRetrievalIntent(String userQuestion) {
        if (userQuestion == null || userQuestion.isBlank()) {
            return RetrievalIntent.GENERAL;
        }
        String q = userQuestion.toLowerCase(Locale.ROOT);
        if (q.matches(".*(valid values|possible values|allowed values|status values|enum|picklist|dropdown|what values|which values).*")) {
            return RetrievalIntent.VALUE_LOOKUP;
        }
        if (q.matches(".*(what does|meaning of|define|definition|business meaning|what is an? .*|mrr|arr|revpar|adr|occupancy|glossary|metric definition).*")) {
            return RetrievalIntent.BUSINESS_MEANING;
        }
        if (q.matches(".*(sql|query example|example query|show me sql|write sql).*")) {
            return RetrievalIntent.SQL_EXAMPLE;
        }
        return RetrievalIntent.GENERAL;
    }

    public RetrievedContextResult buildContext(
        String connectionId,
        String actualUserQuestion,
        SchemaMetadata schema
    ) {
        SchemaMetadata scopedSchema = scopeSchema(connectionId, schema);
        RetrievalIntent retrievalIntent = detectRetrievalIntent(actualUserQuestion);
        if (isSimpleSchemaQuestion(actualUserQuestion)) {
            log.debug("Skipping RAG for simple schema question");
            // Simple schema questions don't trigger RAG, so there are no embedding hits
            // to feed the unified selector. Pass an empty hit list — the link-floor pass
            // is also a no-op without focus tables, so the section ends up empty (which
            // is the right behavior for "list all tables"-style queries).
            CompanyKnowledgeService.RelevantKnowledgeContext knowledgeContext =
                companyKnowledgeService.selectFromRagHits(connectionId, List.of(), Set.of(), scopedSchema);
            return new RetrievedContextResult(
                "",
                knowledgeContext.hintContext(),
                knowledgeContext.diagnosticsContext(),
                knowledgeContext.entries(),
                new LinkedHashSet<>(),
                retrievalIntent,
                0,
                0,
                0,
                Map.of(),
                true,
                "simple_schema_question"
            );
        }

        long ragStart = System.currentTimeMillis();
        int retrievalTopK = resolveRetrievalTopK(retrievalIntent);

        List<TrainingDataEmbedding> ragResults = scopeEmbeddings(connectionId, prioritizeTrainingDataByIntent(
            trainingService.cachedRetrieveRelevant(connectionId, actualUserQuestion, retrievalTopK),
            retrievalIntent
        ));

        String dbType = scopedSchema != null ? scopedSchema.getDbType() : null;
        Set<QualifiedTableName> resolvedTables = scopedSchema == null
            ? Set.of()
            : trainingService.resolveRelevantTables(scopedSchema, actualUserQuestion, ragResults, dbType);

        List<TrainingDataEmbedding> stage2Results = scopeEmbeddings(
            connectionId,
            trainingService.retrieveTargetedByTables(connectionId, resolvedTables, 100)
        );
        List<TrainingDataEmbedding> stage3Results = scopeEmbeddings(
            connectionId,
            trainingService.deduplicateAgainstTargeted(ragResults, stage2Results)
        );

        List<TrainingDataEmbedding> allResults = new ArrayList<>(stage2Results);
        allResults.addAll(stage3Results);
        // Unified selector: company knowledge is now sourced from the same RAG pipeline
        // as everything else. The selector re-ranks COMPANY_KNOWLEDGE-typed hits with
        // link/coverage boosts and merges a targeted link-floor pass — no separate
        // load-all-then-keyword-score call.
        CompanyKnowledgeService.RelevantKnowledgeContext knowledgeContext =
            companyKnowledgeService.selectFromRagHits(
                connectionId,
                allResults,
                extractTableNamesFromRag(allResults),
                scopedSchema
            );

        Set<String> ragTableNames = new LinkedHashSet<>(extractTableNamesFromRag(allResults));
        ragTableNames.addAll(extractFocusTablesFromKnowledge(knowledgeContext.entries()));
        // CK embeddings are stripped from trainingContext to avoid duplicating the
        // dedicated companyKnowledgeContext block above.
        String trainingContext = trainingService.buildTrainingContext(
            filterOutCompanyKnowledge(stage2Results),
            filterOutCompanyKnowledge(stage3Results)
        );

        long durationMs = System.currentTimeMillis() - ragStart;
        Map<TrainingDataEmbedding.TrainingDataType, Long> typeCounts = allResults.stream()
            .filter(Objects::nonNull)
            .collect(Collectors.groupingBy(TrainingDataEmbedding::getType, Collectors.counting()));

        return new RetrievedContextResult(
            trainingContext,
            knowledgeContext.hintContext(),
            knowledgeContext.diagnosticsContext(),
            knowledgeContext.entries(),
            ragTableNames,
            retrievalIntent,
            retrievalTopK,
            allResults.size(),
            durationMs,
            typeCounts,
            false,
            null
        );
    }

    public RetrievedContextResult buildScopedContext(
        String connectionId,
        String actualUserQuestion,
        SchemaMetadata schema,
        java.util.Collection<String> requestedTables
    ) {
        if (schema == null || requestedTables == null || requestedTables.isEmpty()) {
            return buildContext(connectionId, actualUserQuestion, schema);
        }

        SchemaMetadata scopedSchema = scopeSchema(connectionId, schema);
        RetrievalIntent retrievalIntent = detectRetrievalIntent(actualUserQuestion);
        int retrievalTopK = resolveRetrievalTopK(retrievalIntent);
        String dbType = scopedSchema.getDbType();
        Set<QualifiedTableName> resolvedTables = resolveScopedTables(scopedSchema, requestedTables, dbType);

        String augmentedQuestion = actualUserQuestion + " tables: " + String.join(", ", requestedTables);
        List<TrainingDataEmbedding> ragResults = scopeEmbeddings(connectionId, prioritizeTrainingDataByIntent(
            trainingService.cachedRetrieveRelevant(connectionId, augmentedQuestion, retrievalTopK),
            retrievalIntent
        ));

        List<TrainingDataEmbedding> stage2Results = resolvedTables.isEmpty()
            ? List.of()
            : scopeEmbeddings(connectionId, trainingService.retrieveTargetedByTables(connectionId, resolvedTables, 100));
        List<TrainingDataEmbedding> stage3Results = scopeEmbeddings(
            connectionId,
            trainingService.deduplicateAgainstTargeted(ragResults, stage2Results)
        );

        List<TrainingDataEmbedding> allResults = new ArrayList<>(stage2Results);
        allResults.addAll(stage3Results);
        // Scoped path: focus tables are explicit in {@code requestedTables}, so feed
        // those directly to the unified selector alongside any tables surfaced by RAG.
        Set<String> selectorFocus = new LinkedHashSet<>(requestedTables);
        selectorFocus.addAll(extractTableNamesFromRag(allResults));
        CompanyKnowledgeService.RelevantKnowledgeContext knowledgeContext =
            companyKnowledgeService.selectFromRagHits(
                connectionId,
                allResults,
                selectorFocus,
                scopedSchema
            );

        Set<String> ragTableNames = new LinkedHashSet<>(requestedTables);
        ragTableNames.addAll(extractTableNamesFromRag(allResults));
        ragTableNames.addAll(extractFocusTablesFromKnowledge(knowledgeContext.entries()));
        String trainingContext = trainingService.buildTrainingContext(
            filterOutCompanyKnowledge(stage2Results),
            filterOutCompanyKnowledge(stage3Results)
        );

        return new RetrievedContextResult(
            trainingContext,
            knowledgeContext.hintContext(),
            knowledgeContext.diagnosticsContext(),
            knowledgeContext.entries(),
            ragTableNames,
            retrievalIntent,
            retrievalTopK,
            allResults.size(),
            0L,
            allResults.stream()
                .filter(Objects::nonNull)
                .collect(Collectors.groupingBy(TrainingDataEmbedding::getType, Collectors.counting())),
            false,
            null
        );
    }

    int resolveRetrievalTopK(RetrievalIntent intent) {
        return intent == RetrievalIntent.GENERAL ? ragGeneralTopK : ragIntentTopK;
    }

    List<TrainingDataEmbedding> prioritizeTrainingDataByIntent(
        List<TrainingDataEmbedding> retrieved,
        RetrievalIntent intent
    ) {
        if (retrieved == null || retrieved.isEmpty()) {
            return List.of();
        }

        Map<TrainingDataEmbedding.TrainingDataType, Integer> priority = new java.util.HashMap<>();
        if (intent == RetrievalIntent.VALUE_LOOKUP) {
            priority.put(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES, 1);
            priority.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, 2);
            priority.put(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM, 3);
            priority.put(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE, 4);
            priority.put(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE, 5);
            priority.put(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL, 6);
            priority.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, 7);
        } else if (intent == RetrievalIntent.BUSINESS_MEANING) {
            priority.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, 1);
            priority.put(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM, 2);
            priority.put(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE, 3);
            priority.put(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES, 4);
            priority.put(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL, 5);
            priority.put(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE, 6);
            priority.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, 7);
        } else if (intent == RetrievalIntent.SQL_EXAMPLE) {
            priority.put(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE, 1);
            priority.put(TrainingDataEmbedding.TrainingDataType.RELATIONSHIP, 2);
            priority.put(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION, 3);
            priority.put(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE, 4);
            priority.put(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES, 5);
            priority.put(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL, 6);
            priority.put(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM, 7);
        }

        return retrieved.stream()
            .sorted(
                Comparator
                    .comparingInt((TrainingDataEmbedding item) -> priority.getOrDefault(item.getType(), 99))
                    .thenComparing(
                        (TrainingDataEmbedding item) ->
                            item.getScore() != null ? item.getScore() : Double.NEGATIVE_INFINITY,
                        Comparator.reverseOrder()
                    )
            )
            .toList();
    }

    Set<String> extractTableNamesFromRag(List<TrainingDataEmbedding> ragResults) {
        Set<String> tables = new LinkedHashSet<>();
        if (ragResults == null) {
            return tables;
        }
        for (TrainingDataEmbedding embedding : ragResults) {
            if (embedding == null || embedding.getMetadata() == null || embedding.getMetadata().isBlank()) {
                continue;
            }
            try {
                var node = objectMapper.readTree(embedding.getMetadata());
                if (node.has("tablesUsed") && node.get("tablesUsed").isTextual()) {
                    for (String table : node.get("tablesUsed").asText("").split(",")) {
                        String trimmed = table.trim();
                        if (!trimmed.isBlank()) {
                            tables.add(trimmed);
                        }
                    }
                }
                if (node.has("objectName") && node.get("objectName").isTextual()) {
                    String objectName = node.get("objectName").asText("").trim();
                    if (!objectName.isBlank()) {
                        int lastDot = objectName.lastIndexOf('.');
                        tables.add(lastDot >= 0 ? objectName.substring(0, lastDot) : objectName);
                    }
                }
                if (node.has("tableName") && node.get("tableName").isTextual()) {
                    String tableName = node.get("tableName").asText("").trim();
                    if (!tableName.isBlank()) {
                        tables.add(tableName);
                    }
                }
                if (node.has("linkedTables") && node.get("linkedTables").isArray()) {
                    for (var linkedTable : node.get("linkedTables")) {
                        String tableName = linkedTable.asText("").trim();
                        if (!tableName.isBlank()) {
                            tables.add(tableName);
                        }
                    }
                }
                if (node.has("linkedColumns") && node.get("linkedColumns").isArray()) {
                    for (var linkedColumn : node.get("linkedColumns")) {
                        String columnReference = linkedColumn.asText("").trim();
                        int lastDot = columnReference.lastIndexOf('.');
                        if (lastDot > 0) {
                            tables.add(columnReference.substring(0, lastDot));
                        }
                    }
                }
            } catch (Exception ignored) {
                // Best-effort metadata parsing only.
            }
        }
        return tables;
    }

    private List<TrainingDataEmbedding> filterOutCompanyKnowledge(List<TrainingDataEmbedding> items) {
        if (items == null || items.isEmpty()) {
            return List.of();
        }
        return items.stream()
            .filter(Objects::nonNull)
            .filter(item -> item.getType() != TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE)
            .toList();
    }

    private Set<String> extractFocusTablesFromKnowledge(List<CompanyKnowledgeEntry> entries) {
        Set<String> tables = new LinkedHashSet<>();
        if (entries == null || entries.isEmpty()) {
            return tables;
        }
        for (CompanyKnowledgeEntry entry : entries) {
            if (entry == null) {
                continue;
            }
            if (entry.getLinkedTables() != null) {
                entry.getLinkedTables().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(tables::add);
            }
            if (entry.getMentionedTables() != null) {
                entry.getMentionedTables().stream()
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(tables::add);
            }
            if (entry.getLinkedColumns() != null) {
                entry.getLinkedColumns().stream()
                    .map(this::tableReferenceFromColumn)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(tables::add);
            }
            if (entry.getMentionedColumns() != null) {
                entry.getMentionedColumns().stream()
                    .map(this::tableReferenceFromColumn)
                    .filter(Objects::nonNull)
                    .map(String::trim)
                    .filter(value -> !value.isBlank())
                    .forEach(tables::add);
            }
        }
        return tables;
    }

    private String tableReferenceFromColumn(String columnReference) {
        if (columnReference == null || columnReference.isBlank()) {
            return "";
        }
        int lastDot = columnReference.lastIndexOf('.');
        if (lastDot <= 0) {
            return "";
        }
        return columnReference.substring(0, lastDot);
    }

    private boolean isSimpleSchemaQuestion(String message) {
        if (message == null || message.isBlank()) {
            return true;
        }
        String lower = message.toLowerCase(Locale.ROOT);
        boolean exactTableColumnQuestion = lower.contains("column")
            && (lower.matches(".*\\b(how many|count|number of)\\b.*\\bcolumns?\\b.*")
            || lower.matches(".*\\b(what|which|show|list|display|describe|schema|structure)\\b.*\\bcolumns?\\b.*")
            || lower.matches(".*\\bcolumns?\\b.*\\b(in|for|of|on)\\b.*"));
        if (exactTableColumnQuestion) {
            return true;
        }
        boolean hasScopedQualifier = lower.matches(".*\\b(last|past|today|yesterday|month|week|day|year|active|inactive|for|from|where|between)\\b.*");
        if (hasScopedQualifier) {
            return false;
        }
        return lower.matches(".*(show|list|what).*(tables?|columns?|schema|views?).*")
            || lower.matches(".*(how many|count).*(tables?|rows?|records?).*")
            || lower.matches(".*(describe|structure|definition).*")
            || lower.matches(".*(largest|biggest|smallest|size).*table.*");
    }

    private Set<QualifiedTableName> resolveScopedTables(
        SchemaMetadata schema,
        java.util.Collection<String> requestedTables,
        String dbType
    ) {
        Set<QualifiedTableName> resolved = new LinkedHashSet<>();
        if (schema == null || schema.getTables() == null || requestedTables == null || requestedTables.isEmpty()) {
            return resolved;
        }
        for (String requestedTable : requestedTables) {
            if (requestedTable == null || requestedTable.isBlank()) {
                continue;
            }
            schema.getTables().stream()
                .filter(Objects::nonNull)
                .filter(table -> SchemaObjectNameUtil.referencesSameTable(table.getName(), requestedTable)
                    || SchemaObjectNameUtil.referencesSameTable(
                        table.getSchema() != null && !table.getSchema().isBlank() ? table.getSchema() + "." + table.getName() : table.getName(),
                        requestedTable
                    ))
                .findFirst()
                .ifPresent(table -> resolved.add(trainingService.toQualifiedTableName(table, dbType)));
        }
        return resolved;
    }

    private SchemaMetadata scopeSchema(String connectionId, SchemaMetadata schema) {
        return userDataAccessPolicyService.filterSchemaMetadata(
            connectionId,
            accessControlService.getCurrentUsername(),
            accessControlService.isCurrentUserAdmin(),
            schema
        );
    }

    private List<TrainingDataEmbedding> scopeEmbeddings(String connectionId, List<TrainingDataEmbedding> embeddings) {
        return userDataAccessPolicyService.filterRagEmbeddings(
            connectionId,
            accessControlService.getCurrentUsername(),
            accessControlService.isCurrentUserAdmin(),
            embeddings
        );
    }
}
