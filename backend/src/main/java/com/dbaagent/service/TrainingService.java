package com.dbaagent.service;

import com.dbaagent.exception.TrainingCancelledException;
import com.dbaagent.model.*;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.ColumnProfileRepository;
import com.dbaagent.repository.CompanyKnowledgeEntryRepository;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.QueryExampleRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.util.CacheKeyUtil;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Training service for RAG (Retrieval-Augmented Generation).
 * Stores and retrieves training data to improve SQL generation accuracy
 * through the active {@link VectorSearchService} backend.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class TrainingService {

    private record EmbeddingBatchSummary(
        int documentsCreated,
        int embeddedDocuments,
        Map<String, Integer> documentCountsByType,
        Map<String, Integer> embeddedDocumentCountsByType
    ) {
        static EmbeddingBatchSummary empty() {
            return new EmbeddingBatchSummary(0, 0, Map.of(), Map.of());
        }

        static EmbeddingBatchSummary of(String type, boolean embedded) {
            return new EmbeddingBatchSummary(
                1,
                embedded ? 1 : 0,
                Map.of(type, 1),
                embedded ? Map.of(type, 1) : Map.of()
            );
        }

        EmbeddingBatchSummary plus(EmbeddingBatchSummary other) {
            if (other == null) {
                return this;
            }
            return new EmbeddingBatchSummary(
                documentsCreated + other.documentsCreated,
                embeddedDocuments + other.embeddedDocuments,
                mergeCounts(documentCountsByType, other.documentCountsByType),
                mergeCounts(embeddedDocumentCountsByType, other.embeddedDocumentCountsByType)
            );
        }
    }

    private final QueryExampleRepository queryExampleRepository;
    private final SchemaDocumentationRepository schemaDocRepository;
    private final CompanyKnowledgeEntryRepository companyKnowledgeEntryRepository;
    private final ColumnProfileRepository columnProfileRepository;
    private final InferredTableRelationshipRepository inferredTableRelationshipRepository;
    private final EmbeddingService embeddingService;
    private final SchemaScannerService schemaScannerService;
    private final VectorSearchService azureSearchService;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final RedisConnectionFactory redisConnectionFactory;
    private final CacheMetricsService cacheMetricsService;
    private final SqlUsageService sqlUsageService;
    private final DatabaseProviderRegistry databaseProviderRegistry;
    private final RagDocumentStateService ragDocumentStateService;

    @Value("${training.column-values.cardinality-threshold:50}")
    private int columnValuesCardinalityThreshold;

    // In-memory cache for embeddings (fallback when the external vector store is disabled)
    private final Map<String, List<TrainingDataEmbedding>> embeddingCache = new ConcurrentHashMap<>();

    public interface TrainingProgressListener {
        void onStart(int totalTables);
        void onProgress(int processedTables, int totalTables, String tableName);
        void onComplete();
        void onError(String message);
        default void onTableComplete(TableTrainingDetail detail) {
        }

        default void onRunSummary(TrainingRunSummary summary) {
        }

        default boolean isCancelled() {
            return false;
        }

        default void onCancelled(String message) {
        }
    }

    /**
     * Train with DDL (Data Definition Language) from schema.
     * Uses the active vector search backend for persistent retrieval storage when available.
     */
    @Transactional
    public void trainWithSchema(String connectionId) {
        trainWithSchema(connectionId, null);
    }

    @Transactional
    public void trainWithSchema(String connectionId, TrainingProgressListener progressListener) {
        log.info("Training with schema for connection: {}", connectionId);

        try {
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            List<TrainingDataSearchDocument> searchDocs = new ArrayList<>();
            Map<String, Integer> documentCountsByType = new LinkedHashMap<>();
            Map<String, Integer> embeddedDocumentCountsByType = new LinkedHashMap<>();
            boolean useDeterministicDelta = useLocalPgVectorDelta();
            String deterministicRunId = useDeterministicDelta ? UUID.randomUUID().toString() : null;
            int totalTables = schema.getTables().size();
            int indexedDocumentCount = 0;
            int embeddedDocumentCount = 0;
            int skippedSchemaDocs = 0;
            String dbType = schema.getDbType();
            Map<String, RagDocumentStateService.RagDocumentState> schemaDocStates = useDeterministicDelta
                ? ragDocumentStateService.findDocumentStates(
                    schema.getTables().stream()
                        .map(this::qualifyTableName)
                        .map(tableName -> createSchemaDocId(connectionId, tableName))
                        .toList()
                )
                : Map.of();

            if (progressListener != null) {
                progressListener.onStart(totalTables);
            }

            int processedTables = 0;
            int embeddingDimensions = 0;
            for (TableMetadata table : schema.getTables()) {
                try {
                    if (progressListener != null && progressListener.isCancelled()) {
                        progressListener.onCancelled("Cancelled");
                        throw new TrainingCancelledException("Schema training cancelled");
                    }

                    // Build DDL-like description
                    StringBuilder ddl = new StringBuilder();
                    ddl.append("Table: ").append(table.getName()).append("\n");
                    ddl.append("Columns:\n");

                    List<ColumnMetadata> columns = table.getColumns() != null ? table.getColumns() : List.of();
                    for (ColumnMetadata column : columns) {
                        ddl.append("  - ").append(column.getName())
                           .append(" (").append(column.getDataType()).append(")");

                        if (Boolean.TRUE.equals(column.getPrimaryKey())) {
                            ddl.append(" PRIMARY KEY");
                        }
                        if (Boolean.FALSE.equals(column.getNullable())) {
                            ddl.append(" NOT NULL");
                        }
                        ddl.append("\n");
                    }

                    long startTime = System.nanoTime();

                    // Create embedding for this table
                    String docId = createSchemaDocId(connectionId, qualifyTableName(table));
                    String contentHash = contentHash(ddl.toString());
                    boolean reuseExistingEmbedding = canReuseExistingEmbedding(schemaDocStates.get(docId), contentHash);
                    List<Double> embedding = reuseExistingEmbedding
                        ? List.of()
                        : createEmbeddingOrEmpty(
                            ddl.toString(),
                            connectionId,
                            docId,
                            "SCHEMA_DDL");
                    List<Float> embeddingFloat = reuseExistingEmbedding ? null : toFloatVector(embedding);
                    boolean usableEmbedding = reuseExistingEmbedding || !embedding.isEmpty();

                    if (embeddingDimensions == 0 && !embedding.isEmpty()) {
                        embeddingDimensions = embedding.size();
                    }
                    // Store in the active vector backend if enabled
                    if (azureSearchService.isEnabled()) {
                        TrainingDataSearchDocument searchDoc = TrainingDataSearchDocument.builder()
                                .id(docId)
                                .connectionId(connectionId)
                                .type("SCHEMA_DDL")
                                .content(ddl.toString())
                                .objectName(table.getName())
                                .tableName(azureSearchService.resolveTableName(
                                        qualifyTableName(table), null, "TABLE", schema.getDbType()))
                                .dbType(schema.getDbType())
                                .contentVector(embeddingFloat)
                                .metadata(withDeterministicMetadata(
                                    createMetadata(table),
                                    contentHash,
                                    deterministicRunId
                                ))
                                .build();
                        searchDocs.add(searchDoc);
                    } else {
                        // Fallback to in-memory cache
                        TrainingDataEmbedding trainingData = TrainingDataEmbedding.builder()
                            .id(docId)
                            .connectionId(connectionId)
                            .content(ddl.toString())
                            .type(TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL)
                            .embedding(embedding)
                            .metadata(createMetadata(table))
                            .build();
                        cacheEmbedding(connectionId, trainingData);
                        indexedDocumentCount++;
                    }
                    if (usableEmbedding) {
                        embeddedDocumentCount++;
                        incrementCount(embeddedDocumentCountsByType, "SCHEMA_DDL");
                    }
                    incrementCount(documentCountsByType, "SCHEMA_DDL");

                    long durationMs = Math.max(0, (System.nanoTime() - startTime) / 1_000_000);
                    if (progressListener != null) {
                        TableTrainingDetail detail = TableTrainingDetail.builder()
                            .tableName(table.getName())
                            .schema(table.getSchema())
                            .type(table.getType())
                            .columnCount(columns.size())
                            .indexCount(table.getIndexes() != null ? table.getIndexes().size() : 0)
                            .rowCount(table.getRowCount())
                            .sizeBytes(table.getSizeBytes())
                            .ddlLength(ddl.length())
                            .durationMs(durationMs)
                            .embeddingDimensions(embedding.isEmpty() ? 0 : embedding.size())
                            .build();
                        progressListener.onTableComplete(detail);
                    }
                } catch (TrainingCancelledException e) {
                    throw e;
                } catch (Exception tableError) {
                    skippedSchemaDocs++;
                    log.warn(
                        "Skipping schema training document for {} on connection {}: {}",
                        qualifyTableName(table),
                        connectionId,
                        tableError.getMessage(),
                        tableError
                    );
                } finally {
                    processedTables++;
                    if (progressListener != null) {
                        progressListener.onProgress(processedTables, totalTables, table.getName());
                    }
                }
            }

            if (progressListener != null) {
                progressListener.onComplete();
            }

            // Batch index to the active vector backend
            if (!searchDocs.isEmpty()) {
                indexedDocumentCount += indexDocumentsSafely(searchDocs, connectionId, "SCHEMA_DDL");
            }

            // Create RELATIONSHIP documents (one per table that has relationships)
            EmbeddingBatchSummary relationshipSummary = embedRelationshipDocuments(
                connectionId,
                schema,
                deterministicRunId
            );

            // Embed column values for low-cardinality columns (enables value-aware RAG)
            EmbeddingBatchSummary columnValueSummary = embedColumnValues(connectionId, deterministicRunId);

            // Embed schema/business documentation as part of normal Brain init, not only reindex.
            EmbeddingBatchSummary documentationSummary = embedDocumentationRecords(
                connectionId,
                dbType,
                deterministicRunId
            );

            indexedDocumentCount += relationshipSummary.documentsCreated()
                + columnValueSummary.documentsCreated()
                + documentationSummary.documentsCreated();
            embeddedDocumentCount += relationshipSummary.embeddedDocuments()
                + columnValueSummary.embeddedDocuments()
                + documentationSummary.embeddedDocuments();
            mergeCountsInto(documentCountsByType, relationshipSummary.documentCountsByType());
            mergeCountsInto(documentCountsByType, columnValueSummary.documentCountsByType());
            mergeCountsInto(documentCountsByType, documentationSummary.documentCountsByType());
            mergeCountsInto(embeddedDocumentCountsByType, relationshipSummary.embeddedDocumentCountsByType());
            mergeCountsInto(embeddedDocumentCountsByType, columnValueSummary.embeddedDocumentCountsByType());
            mergeCountsInto(embeddedDocumentCountsByType, documentationSummary.embeddedDocumentCountsByType());

            if (useDeterministicDelta) {
                ragDocumentStateService.deleteStaleDeterministicDocuments(
                    connectionId,
                    List.of("SCHEMA_DDL", "RELATIONSHIP", "COLUMN_VALUES", "DOCUMENTATION", "BUSINESS_TERM"),
                    deterministicRunId
                );
            }

            evictRagCacheForConnection(connectionId);

            if (progressListener != null) {
                TrainingRunSummary summary = TrainingRunSummary.builder()
                    .embeddingCount(embeddedDocumentCount)
                    .embeddedDocumentCount(embeddedDocumentCount)
                    .azureDocumentCount(indexedDocumentCount)
                    .embeddingDimensions(embeddingDimensions > 0 ? embeddingDimensions : null)
                    .documentCountsByType(Map.copyOf(documentCountsByType))
                    .embeddedDocumentCountsByType(Map.copyOf(embeddedDocumentCountsByType))
                    .build();
                progressListener.onRunSummary(summary);
            }
            log.info("Trained with {} tables (skipped {}), {} relationship docs, {} column value docs, {} documentation docs (vector backend {} enabled: {})",
                    schema.getTables().size(), skippedSchemaDocs, relationshipSummary.documentsCreated(), columnValueSummary.documentsCreated(),
                    documentationSummary.documentsCreated(), azureSearchService.backendName(), azureSearchService.isEnabled());
        } catch (TrainingCancelledException e) {
            log.info("Schema training cancelled for connection: {}", connectionId);
            throw e;
        } catch (Exception e) {
            if (progressListener != null && progressListener.isCancelled()) {
                Thread.currentThread().interrupt();
                throw new TrainingCancelledException("Schema training cancelled");
            }
            if (isInterrupted(e)) {
                Thread.currentThread().interrupt();
                throw new TrainingCancelledException("Schema training cancelled");
            }

            log.error("Error training with schema", e);
            if (progressListener != null) {
                progressListener.onError(e.getMessage());
            }
            throw new RuntimeException("Failed to train with schema", e);
        }
    }

    /**
     * Embed column profiling data (distinct values) for low-cardinality columns.
     * Groups columns by table, creates one COLUMN_VALUES document per table.
     * Only includes columns with distinctCount <= threshold and non-empty topValues.
     *
     * @return summary of created documents and how many received real embeddings
     */
    private EmbeddingBatchSummary embedColumnValues(String connectionId, String deterministicRunId) {
        try {
            List<ColumnProfile> profiles = columnProfileRepository.findByConnectionId(connectionId);
            if (profiles.isEmpty()) {
                log.debug("No column profiles found for connection {}, skipping COLUMN_VALUES embedding", connectionId);
                return EmbeddingBatchSummary.empty();
            }

            // Group by table, filter to low-cardinality with top values
            Map<String, List<ColumnProfile>> byTable = profiles.stream()
                .filter(p -> p.getDistinctCount() != null && p.getDistinctCount() <= columnValuesCardinalityThreshold)
                .filter(p -> p.getTopValues() != null && !p.getTopValues().isBlank())
                .collect(Collectors.groupingBy(ColumnProfile::getTableName));

            if (byTable.isEmpty()) {
                log.debug("No low-cardinality columns with top values for connection {}", connectionId);
                return EmbeddingBatchSummary.empty();
            }

            List<TrainingDataSearchDocument> searchDocs = new ArrayList<>();
            String dbType = getDbType(connectionId);
            int docsCreated = 0;
            int embeddedDocs = 0;
            Map<String, RagDocumentStateService.RagDocumentState> existingStates = useLocalPgVectorDelta() && deterministicRunId != null
                ? ragDocumentStateService.findDocumentStates(
                    byTable.keySet().stream()
                        .map(tableName -> createColumnValuesDocId(connectionId, tableName))
                        .toList()
                )
                : Map.of();

            for (var entry : byTable.entrySet()) {
                String tableName = entry.getKey();
                List<ColumnProfile> tableProfiles = entry.getValue();

                try {
                    StringBuilder content = new StringBuilder();
                    content.append("Table: ").append(tableName).append("\nColumn Values:\n");

                    for (ColumnProfile cp : tableProfiles) {
                        content.append("  - ").append(cp.getColumnName())
                            .append(" (").append(cp.getDataType()).append(")")
                            .append(" — ").append(cp.getDistinctCount()).append(" distinct values");
                        // Parse topValues JSON array: [{"value": "X", "count": N, "percentage": P}, ...]
                        try {
                            var topValues = objectMapper.readTree(cp.getTopValues());
                            if (topValues.isArray() && !topValues.isEmpty()) {
                                content.append(": ");
                                List<String> parts = new ArrayList<>();
                                for (var tv : topValues) {
                                    String val = tv.has("value") ? tv.get("value").asText() : "?";
                                    double pct = tv.has("percentage") ? tv.get("percentage").asDouble() : 0;
                                    parts.add(pct > 0 ? val + " (" + String.format("%.0f%%", pct) + ")" : val);
                                }
                                content.append(String.join(", ", parts));
                            }
                        } catch (Exception ignored) {
                            // topValues not parseable, skip inline values
                        }
                        content.append("\n");
                    }

                    String docId = createColumnValuesDocId(connectionId, tableName);
                    String contentHash = contentHash(content.toString());
                    boolean reuseExistingEmbedding = canReuseExistingEmbedding(existingStates.get(docId), contentHash);
                    List<Double> embedding = reuseExistingEmbedding
                        ? List.of()
                        : createEmbeddingOrEmpty(
                            content.toString(),
                            connectionId,
                            docId,
                            "COLUMN_VALUES");
                    boolean usableEmbedding = reuseExistingEmbedding || !embedding.isEmpty();
                    if (usableEmbedding) {
                        embeddedDocs++;
                    }

                    if (azureSearchService.isEnabled()) {
                        List<Float> embeddingFloat = reuseExistingEmbedding ? null : toFloatVector(embedding);
                        TrainingDataSearchDocument searchDoc = TrainingDataSearchDocument.builder()
                            .id(docId)
                            .connectionId(connectionId)
                            .type("COLUMN_VALUES")
                            .content(content.toString())
                            .objectName(tableName)
                            .tableName(azureSearchService.resolveTableName(
                                    tableName, null, "TABLE", dbType))
                            .contentVector(embeddingFloat)
                            .metadata(withDeterministicMetadata(
                                createColumnValuesMetadata(tableName, tableProfiles.size()),
                                contentHash,
                                deterministicRunId
                            ))
                            .build();
                        searchDocs.add(searchDoc);
                    } else {
                        TrainingDataEmbedding trainingData = TrainingDataEmbedding.builder()
                            .id(docId)
                            .connectionId(connectionId)
                            .content(content.toString())
                            .type(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES)
                            .embedding(embedding)
                            .metadata(createColumnValuesMetadata(tableName, tableProfiles.size()))
                            .build();
                        cacheEmbedding(connectionId, trainingData);
                    }
                    docsCreated++;
                } catch (Exception e) {
                    log.warn("Column values embedding failed for table {} (non-fatal): {}", tableName, e.getMessage());
                }
            }

            if (!searchDocs.isEmpty()) {
                docsCreated = indexDocumentsSafely(searchDocs, connectionId, "COLUMN_VALUES");
            }

            log.info("Embedded column values for {} tables ({} total low-cardinality columns)",
                docsCreated, profiles.stream()
                    .filter(p -> p.getDistinctCount() != null && p.getDistinctCount() <= columnValuesCardinalityThreshold)
                    .filter(p -> p.getTopValues() != null && !p.getTopValues().isBlank())
                    .count());
            return docsCreated == 0
                ? EmbeddingBatchSummary.empty()
                : new EmbeddingBatchSummary(
                    docsCreated,
                    Math.min(embeddedDocs, docsCreated),
                    Map.of("COLUMN_VALUES", docsCreated),
                    embeddedDocs > 0 ? Map.of("COLUMN_VALUES", Math.min(embeddedDocs, docsCreated)) : Map.of()
                );
        } catch (Exception e) {
            log.warn("Column values embedding failed (non-fatal): {}", e.getMessage());
            return EmbeddingBatchSummary.empty();
        }
    }

    private String createColumnValuesDocId(String connectionId, String tableName) {
        String raw = connectionId + "::COLUMN_VALUES::" + tableName;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String createColumnValuesMetadata(String tableName, int columnCount) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tableName", tableName);
            metadata.put("columnCount", columnCount);
            metadata.put("type", "COLUMN_VALUES");
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    /**
     * Create RELATIONSHIP documents — one per table that has FK or inferred relationships.
     * Content includes both explicit FKs (from schema) and inferred relationships (confidence >= 60, not REJECTED).
     *
     * @return summary of created documents and how many received real embeddings
     */
    private EmbeddingBatchSummary embedRelationshipDocuments(
        String connectionId,
        SchemaMetadata schema,
        String deterministicRunId
    ) {
        if (!azureSearchService.isEnabled()) {
            return EmbeddingBatchSummary.empty();
        }

        try {
            // Load inferred relationships (confidence >= 60, not REJECTED)
            List<InferredTableRelationship> inferred = inferredTableRelationshipRepository
                    .findByConnectionIdOrderByConfidenceScoreDesc(connectionId)
                    .stream()
                    .filter(r -> r.getConfidenceScore() != null
                            && r.getConfidenceScore().doubleValue() >= 60)
                    .filter(r -> !"REJECTED".equals(r.getStatus()))
                    .toList();

            // Build lookup: tableName → inferred relationships involving that table
            Map<String, List<InferredTableRelationship>> inferredByTable = new HashMap<>();
            for (InferredTableRelationship rel : inferred) {
                inferredByTable.computeIfAbsent(rel.getSourceTable(), k -> new ArrayList<>()).add(rel);
                if (!rel.getSourceTable().equals(rel.getTargetTable())) {
                    inferredByTable.computeIfAbsent(rel.getTargetTable(), k -> new ArrayList<>()).add(rel);
                }
            }

            // Build lookup: tableName → explicit FK relationships from schema
            Map<String, List<RelationshipMetadata>> fkByTable = new HashMap<>();
            if (schema.getRelationships() != null) {
                for (RelationshipMetadata fk : schema.getRelationships()) {
                    fkByTable.computeIfAbsent(fk.getFromTable(), k -> new ArrayList<>()).add(fk);
                    if (!fk.getFromTable().equals(fk.getToTable())) {
                        fkByTable.computeIfAbsent(fk.getToTable(), k -> new ArrayList<>()).add(fk);
                    }
                }
            }

            // Collect tables that have any relationships
            Set<String> tablesWithRelationships = new LinkedHashSet<>();
            tablesWithRelationships.addAll(fkByTable.keySet());
            tablesWithRelationships.addAll(inferredByTable.keySet());

            if (tablesWithRelationships.isEmpty()) {
                return EmbeddingBatchSummary.empty();
            }

            List<TrainingDataSearchDocument> relationshipDocs = new ArrayList<>();
            int embeddedDocs = 0;
            Map<String, RagDocumentStateService.RagDocumentState> existingStates = useLocalPgVectorDelta() && deterministicRunId != null
                ? ragDocumentStateService.findDocumentStates(
                    tablesWithRelationships.stream()
                        .map(tableName -> UUID.nameUUIDFromBytes(
                            (connectionId + "::RELATIONSHIP::" + tableName).getBytes(StandardCharsets.UTF_8)
                        ).toString())
                        .toList()
                )
                : Map.of();
            for (String tableName : tablesWithRelationships) {
                String content = buildRelationshipContent(tableName,
                        fkByTable.getOrDefault(tableName, List.of()),
                        inferredByTable.getOrDefault(tableName, List.of()));

                String docId = UUID.nameUUIDFromBytes(
                        (connectionId + "::RELATIONSHIP::" + tableName).getBytes(StandardCharsets.UTF_8)
                ).toString();

                String contentHash = contentHash(content);
                boolean reuseExistingEmbedding = canReuseExistingEmbedding(existingStates.get(docId), contentHash);
                List<Double> embedding = reuseExistingEmbedding
                    ? List.of()
                    : createEmbeddingOrEmpty(
                        content,
                        connectionId,
                        docId,
                        "RELATIONSHIP");
                if (reuseExistingEmbedding || !embedding.isEmpty()) {
                    embeddedDocs++;
                }
                List<Float> embeddingFloat = reuseExistingEmbedding ? null : toFloatVector(embedding);

                String resolvedTableName = azureSearchService.resolveTableName(
                        tableName, null, "RELATIONSHIP", schema.getDbType());

                TrainingDataSearchDocument doc = TrainingDataSearchDocument.builder()
                        .id(docId)
                        .connectionId(connectionId)
                        .type("RELATIONSHIP")
                        .content(content)
                        .objectName(tableName)
                        .tableName(resolvedTableName)
                        .dbType(schema.getDbType())
                        .contentVector(embeddingFloat)
                        .metadata(withDeterministicMetadata(null, contentHash, deterministicRunId))
                        .build();
                relationshipDocs.add(doc);
            }

            if (!relationshipDocs.isEmpty()) {
                int indexedDocs = indexDocumentsSafely(relationshipDocs, connectionId, "RELATIONSHIP");
                if (indexedDocs == 0) {
                    return EmbeddingBatchSummary.empty();
                }
                log.info("Created {} RELATIONSHIP documents for connection {}", indexedDocs, connectionId);
                return new EmbeddingBatchSummary(
                    indexedDocs,
                    Math.min(embeddedDocs, indexedDocs),
                    Map.of("RELATIONSHIP", indexedDocs),
                    embeddedDocs > 0 ? Map.of("RELATIONSHIP", Math.min(embeddedDocs, indexedDocs)) : Map.of()
                );
            }

            return EmbeddingBatchSummary.empty();
        } catch (Exception e) {
            log.warn("RELATIONSHIP document creation failed (non-fatal): {}", e.getMessage());
            return EmbeddingBatchSummary.empty();
        }
    }

    private EmbeddingBatchSummary embedDocumentationRecords(
        String connectionId,
        String dbType,
        String deterministicRunId
    ) {
        List<SchemaDocumentation> docs = schemaDocRepository.findByConnectionId(connectionId);
        if (docs.isEmpty()) {
            return EmbeddingBatchSummary.empty();
        }

        Map<String, RagDocumentStateService.RagDocumentState> docStates = useLocalPgVectorDelta() && deterministicRunId != null
            ? ragDocumentStateService.findDocumentStates(docs.stream().map(SchemaDocumentation::getId).toList())
            : Map.of();
        Map<String, RagDocumentStateService.RagDocumentState> businessTermStates = useLocalPgVectorDelta() && deterministicRunId != null
            ? ragDocumentStateService.findDocumentStates(
                docs.stream()
                    .filter(doc -> doc.getBusinessTerms() != null && !doc.getBusinessTerms().isBlank())
                    .map(this::businessTermDocId)
                    .toList()
            )
            : Map.of();

        EmbeddingBatchSummary summary = EmbeddingBatchSummary.empty();
        for (SchemaDocumentation doc : docs) {
            try {
                summary = summary.plus(storeDocumentationEmbeddings(
                    doc,
                    dbType,
                    false,
                    docStates.get(doc.getId()),
                    businessTermStates.get(businessTermDocId(doc)),
                    deterministicRunId
                ));
            } catch (Exception e) {
                log.warn("Failed to index documentation {} during schema training: {}", doc.getId(), e.getMessage());
            }
        }
        return summary;
    }

    private List<Double> createEmbeddingOrEmpty(
            String content,
            String connectionId,
            String documentId,
            String documentType) {
        try {
            return embeddingService.createEmbedding(content);
        } catch (RuntimeException e) {
            log.warn(
                    "Skipping embedding for {} document {} on connection {} due to transient/non-fatal error: {}",
                    documentType,
                    documentId,
                    connectionId,
                    e.getMessage());
            return List.of();
        }
    }

    private int indexDocumentsSafely(
        List<TrainingDataSearchDocument> documents,
        String connectionId,
        String documentType
    ) {
        if (!azureSearchService.isEnabled() || documents == null || documents.isEmpty()) {
            return 0;
        }

        try {
            azureSearchService.indexDocuments(documents);
            return documents.size();
        } catch (Exception batchError) {
            log.warn(
                "Batch indexing failed for {} {} documents on connection {}, retrying one-by-one: {}",
                documents.size(),
                documentType,
                connectionId,
                batchError.getMessage(),
                batchError
            );
        }

        int indexedCount = 0;
        for (TrainingDataSearchDocument document : documents) {
            try {
                azureSearchService.indexDocument(document);
                indexedCount++;
            } catch (Exception docError) {
                log.warn(
                    "Skipping {} document {} on connection {} after indexing failure: {}",
                    documentType,
                    document != null ? document.getId() : "unknown",
                    connectionId,
                    docError.getMessage(),
                    docError
                );
            }
        }
        return indexedCount;
    }

    private List<Float> toFloatVector(List<Double> embedding) {
        return embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());
    }

    private String buildRelationshipContent(String tableName,
                                             List<RelationshipMetadata> fks,
                                             List<InferredTableRelationship> inferred) {
        StringBuilder sb = new StringBuilder();
        sb.append("Table: ").append(tableName).append("\nRelationships:\n");

        // Deduplicate: track source.col → target.col pairs already added
        Set<String> seen = new HashSet<>();

        for (RelationshipMetadata fk : fks) {
            String key = fk.getFromTable() + "." + fk.getFromColumn()
                    + " → " + fk.getToTable() + "." + fk.getToColumn();
            if (seen.add(key)) {
                sb.append("  - ").append(key).append(" (FOREIGN KEY)\n");
            }
        }

        for (InferredTableRelationship rel : inferred) {
            String key = rel.getSourceTable() + "." + rel.getSourceColumn()
                    + " → " + rel.getTargetTable() + "." + rel.getTargetColumn();
            if (seen.add(key)) {
                sb.append("  - ").append(key)
                        .append(" (INFERRED, ")
                        .append(rel.getConfidenceScore().intValue()).append("% confidence");
                if (rel.getJoinCount() != null && rel.getJoinCount() > 0) {
                    sb.append(", ").append(rel.getJoinCount()).append(" queries");
                }
                sb.append(")\n");
            }
        }

        return sb.toString();
    }

    /**
     * Train with successful query example
     */
    @Transactional
    public QueryExample trainWithQueryExample(
        String connectionId,
        String naturalLanguage,
        String sql,
        QueryResult result,
        String userId
    ) {
        log.info("┌─────────────────────────────────────────────────────────");
        log.info("│ TRAINING - Query Example");
        log.info("│ Question: {}", naturalLanguage);
        log.info("│ SQL: {}", sql.length() > 100 ? sql.substring(0, 100) + "..." : sql);
        log.info("│ Storage: {}", azureSearchService.isEnabled() ? azureSearchService.backendName() : "Database + Cache");
        log.info("└─────────────────────────────────────────────────────────");

        try {
            // Extract tables and columns from SQL
            String tablesUsed = extractTablesFromSQL(sql);
            String columnsUsed = null;
            try {
                SqlUsage usage = sqlUsageService.parseUsage(connectionId, sql);
                if (usage.getColumns() != null && !usage.getColumns().isEmpty()) {
                    columnsUsed = String.join(",", usage.getColumns());
                }
            } catch (Exception e) {
                log.debug("Failed to capture query columns for training example: {}", e.getMessage());
            }

            // Save to database — auto-trained examples start as unverified.
            // Only thumbs-up feedback promotes them to verified.
            String normalizedSql = normalizeSql(sql);
            QueryExample example = QueryExample.builder()
                .connectionId(connectionId)
                .naturalLanguage(naturalLanguage)
                .sql(normalizedSql)
                .dbType(result != null ? getDbType(connectionId) : "unknown")
                .rowCount(result != null ? result.getRowCount() : null)
                .executionTimeMs(result != null ? result.getExecutionTimeMs() : null)
                .successful(result != null)
                .userId(userId)
                .tablesUsed(tablesUsed)
                .columnsUsed(columnsUsed)
                .verified(false)
                .rejected(false)
                .build();

            example = queryExampleRepository.save(example);
            log.info("  ✓ Saved to database: {}", example.getId());

            // Embeddings improve fuzzy recall, but a failed embedding call should never
            // discard a DB-backed approved example.
            String embeddingText = naturalLanguage + "\n" + sql;
            try {
                long embeddingStart = System.currentTimeMillis();
                List<Double> embedding = embeddingService.createEmbedding(embeddingText);
                long embeddingTime = System.currentTimeMillis() - embeddingStart;
                log.info("  ✓ Embedding created: {}ms", embeddingTime);

                // Store in the active vector backend or cache
                if (azureSearchService.isEnabled()) {
                    List<Float> embeddingFloat = embedding.stream()
                            .map(Double::floatValue)
                            .collect(Collectors.toList());

                    TrainingDataSearchDocument searchDoc = TrainingDataSearchDocument.builder()
                            .id(example.getId())
                            .connectionId(connectionId)
                            .type("QUERY_EXAMPLE")
                            .content(embeddingText)
                            .naturalLanguage(naturalLanguage)
                            .sql(sql)
                            .tablesUsed(tablesUsed)
                            .contentVector(embeddingFloat)
                            .successful(result != null)
                            .executionTimeMs(result != null ? result.getExecutionTimeMs() : null)
                            .build();

                    azureSearchService.indexDocument(searchDoc);
                    log.info("  ✓ Indexed to {}", azureSearchService.backendName());
                    log.info("  └─ Searchable via: Vector similarity + Keyword matching");
                } else {
                    TrainingDataEmbedding trainingData = TrainingDataEmbedding.builder()
                        .id(example.getId())
                        .connectionId(connectionId)
                        .content(embeddingText)
                        .type(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE)
                        .embedding(embedding)
                        .metadata("{\"sql\":\"" + sql.replace("\"", "\\\"") + "\"}")
                        .build();

                    cacheEmbedding(connectionId, trainingData);
                    log.info("  ✓ Cached in memory");
                }
            } catch (Exception embeddingError) {
                log.warn("  ! Query example embedding/indexing failed, keeping DB example {}: {}",
                    example.getId(), embeddingError.getMessage());
            }

            log.info("✓ Query example training completed: {}", example.getId());
            evictRagCacheForConnection(connectionId);
            return example;
        } catch (Exception e) {
            log.error("Error training with query example", e);
            throw new RuntimeException("Failed to train with query example", e);
        }
    }

    /**
     * Train with a query example only if no active example already exists for this SQL.
     * Used when recording thumbs-up feedback: avoids duplicates when the user previously
     * approved via "ok" (which already trained), and adds new examples when they only thumbs up.
     *
     * @return the created or existing example, or null if skipped
     */
    @Transactional
    public QueryExample trainWithQueryExampleIfNotExists(
        String connectionId,
        String naturalLanguage,
        String sql,
        QueryResult result,
        String userId
    ) {
        if (connectionId == null || sql == null || sql.isBlank()) {
            return null;
        }
        String normalizedSql = normalizeSql(sql);
        List<QueryExample> existing = queryExampleRepository.findActiveByConnectionAndSql(connectionId, normalizedSql);
        if (!existing.isEmpty()) {
            log.debug("Query example already exists for SQL, skipping train (thumbs-up will verify): {}", existing.get(0).getId());
            return existing.get(0);
        }
        return trainWithQueryExample(connectionId, naturalLanguage, sql, result, userId);
    }

    /**
     * Promote unverified query examples to verified status.
     * Called when a user gives thumbs-up feedback on a response containing SQL.
     */
    @Transactional
    public int verifyQueryExamplesBySql(String connectionId, String sql) {
        if (connectionId == null || sql == null || sql.isBlank()) {
            return 0;
        }
        List<QueryExample> unverified = queryExampleRepository.findUnverifiedByConnectionAndSql(connectionId, normalizeSql(sql));
        int count = 0;
        for (QueryExample example : unverified) {
            example.setVerified(true);
            example.setVerifiedAt(LocalDateTime.now());
            queryExampleRepository.save(example);
            count++;
        }
        if (count > 0) {
            log.info("Verified {} query example(s) for connection {} via thumbs-up", count, connectionId);
            evictRagCacheForConnection(connectionId);
        }
        return count;
    }

    /**
     * Mark matching query examples as rejected after explicit thumbs-down feedback.
     * Rejected examples are excluded from future retrieval and de-verified.
     */
    @Transactional
    public int rejectQueryExamplesBySql(String connectionId, String sql) {
        if (connectionId == null || sql == null || sql.isBlank()) {
            return 0;
        }

        String normalizedSql = normalizeSql(sql);
        List<QueryExample> activeExamples = queryExampleRepository.findActiveByConnectionAndSql(connectionId, normalizedSql);
        int count = 0;

        for (QueryExample example : activeExamples) {
            example.setRejected(true);
            example.setRejectedAt(LocalDateTime.now());
            example.setVerified(false);
            example.setVerifiedAt(null);
            queryExampleRepository.save(example);
            count++;

            if (azureSearchService.isEnabled()) {
                try {
                    azureSearchService.deleteDocument(example.getId());
                } catch (Exception e) {
                    log.debug("Failed to remove rejected query example {} from Azure Search: {}", example.getId(), e.getMessage());
                }
            }
        }

        if (count > 0) {
            log.info("Rejected {} query example(s) for connection {} via thumbs-down", count, connectionId);
            evictRagCacheForConnection(connectionId);
        }
        return count;
    }

    /**
     * Reject a single query example by ID and remove it from Azure Search.
     */
    @Transactional
    public boolean rejectQueryExampleById(String exampleId) {
        if (exampleId == null || exampleId.isBlank()) {
            return false;
        }
        return queryExampleRepository.findById(exampleId).map(example -> {
            example.setRejected(true);
            example.setRejectedAt(LocalDateTime.now());
            example.setVerified(false);
            example.setVerifiedAt(null);
            queryExampleRepository.save(example);
            if (azureSearchService.isEnabled()) {
                try {
                    azureSearchService.deleteDocument(example.getId());
                } catch (Exception e) {
                    log.debug("Failed to remove rejected query example {} from Azure Search: {}",
                        example.getId(), e.getMessage());
                }
            }
            evictRagCacheForConnection(example.getConnectionId());
            log.info("Rejected query example {} (question={})", exampleId,
                example.getNaturalLanguage() != null ? example.getNaturalLanguage().substring(0, Math.min(50, example.getNaturalLanguage().length())) : "null");
            return true;
        }).orElse(false);
    }

    public String getQueryExampleConnectionId(String exampleId) {
        return queryExampleRepository.findById(exampleId)
            .map(QueryExample::getConnectionId)
            .orElseThrow(() -> new IllegalArgumentException("Query example not found"));
    }

    /**
     * Train with documentation (business terms, table descriptions)
     */
    @Transactional
    public SchemaDocumentation trainWithDocumentation(
        String connectionId,
        SchemaDocumentation.DocumentationType type,
        String objectName,
        String parentObject,
        String description,
        String businessTerms,
        String examples,
        String createdBy
    ) {
        log.debug("Training with documentation for: {} - {}", type, objectName);

        try {
            SchemaDocumentation doc = SchemaDocumentation.builder()
                .connectionId(connectionId)
                .objectType(type)
                .objectName(objectName)
                .parentObject(parentObject)
                .description(description)
                .businessTerms(businessTerms)
                .examples(examples)
                .createdBy(createdBy)
                .build();

            doc = schemaDocRepository.save(doc);
            upsertDocumentationEmbedding(doc);
            return doc;
        } catch (Exception e) {
            log.error("Error training with documentation", e);
            throw new RuntimeException("Failed to train with documentation", e);
        }
    }

    /**
     * Retrieve relevant training data for a natural language question
     * using the active vector search backend for hybrid retrieval when available.
     */
    public List<TrainingDataEmbedding> retrieveRelevant(
        String connectionId,
        String question,
        int topK
    ) {
        long startTime = System.currentTimeMillis();
        log.info("╔════════════════════════════════════════════════════════════");
        log.info("║ RAG RETRIEVAL - Starting");
        log.info("║ Connection: {}", connectionId);
        log.info("║ Question: {}", question);
        log.info("║ Top-K: {}", topK);
        log.info("║ Data Source: {}", azureSearchService.isEnabled() ? azureSearchService.backendName() + " (Hybrid)" : "In-Memory Cache");
        log.info("╚════════════════════════════════════════════════════════════");

        try {
            // Create embedding for the question
            long embeddingStart = System.currentTimeMillis();
            List<Double> questionEmbedding = embeddingService.createEmbedding(question);
            long embeddingTime = System.currentTimeMillis() - embeddingStart;
            log.info("→ Embedding created: {}ms ({} dimensions)", embeddingTime, questionEmbedding.size());

            // Use the active vector backend if enabled (hybrid search with vector + keyword)
            if (azureSearchService.isEnabled()) {
                log.info("→ Using {} - Hybrid Search Mode", azureSearchService.backendName());
                log.info("  ├─ Vector Search: Enabled");
                log.info("  ├─ Keyword Search: Enabled (BM25 ranking)");
                log.info("  └─ Filtering: connectionId = '{}'", connectionId);

                long searchStart = System.currentTimeMillis();
                List<Float> queryVector = questionEmbedding.stream()
                        .map(Double::floatValue)
                        .collect(Collectors.toList());

                List<TrainingDataSearchDocument> searchResults =
                        azureSearchService.hybridSearch(connectionId, question, queryVector, topK);
                long searchTime = System.currentTimeMillis() - searchStart;

                log.info("→ {} completed: {}ms", azureSearchService.backendName(), searchTime);
                log.info("→ Results retrieved: {} documents", searchResults.size());

                if (!searchResults.isEmpty()) {
                    log.info("→ Top Results:");
                    int rank = 1;
                    for (TrainingDataSearchDocument doc : searchResults) {
                        String preview = doc.getContent().length() > 80
                            ? doc.getContent().substring(0, 80) + "..."
                            : doc.getContent();
                        log.info("  {}. [{}] {}", rank++, doc.getType(), preview);
                    }
                } else {
                    log.warn("⚠ No matching documents found in {}", azureSearchService.backendName());
                    log.info("  This is normal for:");
                    log.info("  - First queries (index is empty)");
                    log.info("  - Very unique questions (no similar examples yet)");
                }

                // Convert to TrainingDataEmbedding for backward compatibility.
                // Apply 0.5x score penalty for unverified query examples to
                // prevent auto-trained (potentially wrong) SQL from dominating retrieval.
                Set<String> verifiedIds = getVerifiedExampleIds(connectionId);
                Set<String> rejectedIds = getRejectedExampleIds(connectionId);
                List<TrainingDataEmbedding> results = searchResults.stream()
                        .map(doc -> {
                            double baseScore = doc.getSearchScore() != null ? doc.getSearchScore() : 0.9;
                            boolean isQueryExample = "QUERY_EXAMPLE".equalsIgnoreCase(doc.getType());
                            if (isQueryExample && rejectedIds.contains(doc.getId())) {
                                return null;
                            }
                            if (isQueryExample && !verifiedIds.contains(doc.getId())) {
                                baseScore *= 0.5;
                            }
                            // Merge top-level tablesUsed into metadata for QUERY_EXAMPLE docs
                            String meta = doc.getMetadata();
                            if (doc.getTablesUsed() != null && !doc.getTablesUsed().isBlank()) {
                                try {
                                    var metaNode = meta != null && !meta.isBlank()
                                        ? objectMapper.readTree(meta)
                                        : objectMapper.createObjectNode();
                                    ((com.fasterxml.jackson.databind.node.ObjectNode) metaNode)
                                        .put("tablesUsed", doc.getTablesUsed());
                                    meta = objectMapper.writeValueAsString(metaNode);
                                } catch (Exception ignored) { }
                            }
                            return TrainingDataEmbedding.builder()
                                .id(doc.getId())
                                .connectionId(doc.getConnectionId())
                                .content(doc.getContent())
                                .type(resolveTrainingDataType(doc))
                                .score(baseScore)
                                .metadata(meta)
                                .build();
                        })
                        .filter(Objects::nonNull)
                        .sorted(Comparator.comparingDouble(
                            (TrainingDataEmbedding item) ->
                                item.getScore() != null ? item.getScore() : Double.NEGATIVE_INFINITY
                        ).reversed())
                        .collect(Collectors.toList());

                long totalTime = System.currentTimeMillis() - startTime;
                log.info("╔════════════════════════════════════════════════════════════");
                log.info("║ RAG RETRIEVAL - Completed ({})", azureSearchService.backendName());
                log.info("║ Total Time: {}ms", totalTime);
                log.info("║ Results: {} relevant documents", results.size());
                log.info("║ Performance: {}ms embedding + {}ms search", embeddingTime, searchTime);
                log.info("╚════════════════════════════════════════════════════════════");

                return stripEmbeddings(results);
            }

            // Fallback: Use in-memory cache with manual similarity calculation
            log.info("→ Using In-Memory Cache (Azure Search disabled)");
            List<TrainingDataEmbedding> allData = embeddingCache.getOrDefault(
                connectionId,
                new ArrayList<>()
            );

            // If cache is empty, load from database
            if (allData.isEmpty()) {
                log.info("  ├─ Cache empty, loading from database...");
                loadTrainingDataIntoCache(connectionId);
                allData = embeddingCache.getOrDefault(connectionId, new ArrayList<>());
                log.info("  └─ Loaded {} documents into cache", allData.size());
            } else {
                log.info("  └─ Using cached data: {} documents", allData.size());
            }

            // Calculate similarity scores, applying 0.5x penalty for unverified query examples
            log.info("→ Calculating cosine similarity for {} documents...", allData.size());
            Set<String> verifiedIdsForCache = getVerifiedExampleIds(connectionId);
            Set<String> rejectedIdsForCache = getRejectedExampleIds(connectionId);
            long similarityStart = System.currentTimeMillis();
            List<TrainingDataEmbedding> scoredData = new ArrayList<>();
            for (TrainingDataEmbedding data : allData) {
                boolean isQueryExample = data.getType() == TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE;
                if (isQueryExample && rejectedIdsForCache.contains(data.getId())) {
                    continue;
                }
                double similarity = embeddingService.cosineSimilarity(
                    questionEmbedding,
                    data.getEmbedding()
                );
                if (isQueryExample && !verifiedIdsForCache.contains(data.getId())) {
                    similarity *= 0.5;
                }
                data.setScore(similarity);
                scoredData.add(data);
            }
            long similarityTime = System.currentTimeMillis() - similarityStart;
            log.info("  └─ Similarity calculation: {}ms", similarityTime);

            // Sort by similarity and take top K
            List<TrainingDataEmbedding> topResults = scoredData.stream()
                .sorted(Comparator.comparingDouble(TrainingDataEmbedding::getScore).reversed())
                .limit(topK)
                .collect(Collectors.toList());

            if (!topResults.isEmpty()) {
                log.info("→ Top {} Results by Similarity:", topK);
                int rank = 1;
                for (TrainingDataEmbedding result : topResults) {
                    String preview = result.getContent().length() > 80
                        ? result.getContent().substring(0, 80) + "..."
                        : result.getContent();
                    log.info("  {}. [{}] Score: {:.4f} - {}",
                        rank++, result.getType(), result.getScore(), preview);
                }
            }

            long totalTime = System.currentTimeMillis() - startTime;
            log.info("╔════════════════════════════════════════════════════════════");
            log.info("║ RAG RETRIEVAL - Completed (In-Memory Cache)");
            log.info("║ Total Time: {}ms", totalTime);
            log.info("║ Results: {} relevant documents", topResults.size());
            log.info("║ Performance: {}ms embedding + {}ms similarity", embeddingTime, similarityTime);
            log.info("╚════════════════════════════════════════════════════════════");

            return stripEmbeddings(topResults);
        } catch (Exception e) {
            log.error("╔════════════════════════════════════════════════════════════");
            log.error("║ RAG RETRIEVAL - FAILED");
            log.error("║ Error: {}", e.getMessage());
            log.error("╚════════════════════════════════════════════════════════════", e);
            return new ArrayList<>();
        }
    }

    public List<TrainingDataEmbedding> cachedRetrieveRelevant(
        String connectionId,
        String question,
        int topK
    ) {
        if (question == null || question.trim().isEmpty()) {
            return retrieveRelevant(connectionId, question, topK);
        }

        String source = azureSearchService.isEnabled() ? "azure" : "memory";
        String cacheKey = CacheKeyUtil.ragKey(connectionId, question, topK, source);
        Cache cache = cacheManager.getCache("ragRetrieval");
        if (cache != null) {
            List<TrainingDataEmbedding> cached = cache.get(cacheKey, List.class);
            if (cached != null) {
                cacheMetricsService.recordGet("ragRetrieval", true);
                return cached;
            }
            cacheMetricsService.recordGet("ragRetrieval", false);
        }

        List<TrainingDataEmbedding> results = retrieveRelevant(connectionId, question, topK);
        if (cache != null) {
            cache.put(cacheKey, results);
            cacheMetricsService.recordPut("ragRetrieval");
        }
        return results;
    }

    /**
     * Build enhanced context for ChatService from retrieved training data
     */
    /**
     * Single-source convenience delegate — used by stream path (no Phase 2).
     */
    public String buildTrainingContext(List<TrainingDataEmbedding> relevantData) {
        return buildTrainingContext(List.of(), relevantData);
    }

    /**
     * Two-source build: targeted (Stage 2) first, then fallback (Stage 3).
     * Targeted results appear first within each section for higher priority.
     */
    public String buildTrainingContext(
            List<TrainingDataEmbedding> targetedData,
            List<TrainingDataEmbedding> fallbackData) {

        List<TrainingDataEmbedding> merged = new ArrayList<>(
                targetedData != null ? targetedData : List.of());
        if (fallbackData != null) {
            merged.addAll(fallbackData);
        }
        if (merged.isEmpty()) {
            return "";
        }

        StringBuilder context = new StringBuilder();
        context.append("\n\nRelevant Retrieved Context:\n");

        List<TrainingDataEmbedding> valueHints = takeByType(merged,
                EnumSet.of(TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES), 15);
        List<TrainingDataEmbedding> businessHints = takeByType(merged,
                EnumSet.of(
                        TrainingDataEmbedding.TrainingDataType.DOCUMENTATION,
                        TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM,
                        TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE), 15);
        List<TrainingDataEmbedding> queryExamples = takeByType(merged,
                EnumSet.of(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE), 15);
        List<TrainingDataEmbedding> patternInsights = takeByType(merged,
                EnumSet.of(
                        TrainingDataEmbedding.TrainingDataType.QUERY_PATTERN,
                        TrainingDataEmbedding.TrainingDataType.WORKLOAD_INSIGHT,
                        TrainingDataEmbedding.TrainingDataType.CARDINALITY_INSIGHT), 15);
        List<TrainingDataEmbedding> schemaHints = takeByType(merged,
                EnumSet.of(
                        TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL,
                        TrainingDataEmbedding.TrainingDataType.RELATIONSHIP), 15);

        appendContextSection(context, "Known Column Values and Allowed Variants", valueHints, 4000);
        appendContextSection(context, "Business Definitions and Semantics", businessHints, 4000);
        appendContextSection(context, "Similar Query Examples", queryExamples, 4000);
        appendContextSection(context, "Query and Workload Insights", patternInsights, 4000);
        appendContextSection(context, "Related Schema and Relationships", schemaHints, 4000);

        return context.toString();
    }

    /**
     * Get training statistics
     */
    public Map<String, Object> getTrainingStats(String connectionId) {
        Map<String, Object> stats = new HashMap<>();

        long queryExamples = queryExampleRepository.countSuccessfulByConnection(connectionId);
        long documentation = schemaDocRepository.findByConnectionId(connectionId).size();
        int cachedEmbeddings = embeddingCache.getOrDefault(connectionId, new ArrayList<>()).size();

        stats.put("queryExamples", queryExamples);
        stats.put("documentation", documentation);
        stats.put("cachedEmbeddings", cachedEmbeddings);
        stats.put("connectionId", connectionId);
        stats.put("azureSearchEnabled", azureSearchService.isEnabled());
        if (azureSearchService.isEnabled()) {
            stats.put("azureSearch", azureSearchService.getConnectionStats(connectionId));
        }

        return stats;
    }

    /**
     * Debug endpoint payload for retrieval diagnostics.
     * Returns ranked retrieval results with compact previews and current index stats.
     */
    public Map<String, Object> debugRetrieve(String connectionId, String question, int topK) {
        int boundedTopK = Math.max(1, Math.min(topK, 100));
        long start = System.currentTimeMillis();
        List<TrainingDataEmbedding> retrieved = retrieveRelevant(connectionId, question, boundedTopK);

        List<Map<String, Object>> rows = retrieved.stream()
            .map(item -> {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("id", item.getId());
                row.put("type", item.getType() != null ? item.getType().name() : null);
                row.put("score", item.getScore());
                row.put("preview", truncateAndNormalize(item.getContent(), 260));
                row.put("metadata", item.getMetadata());
                return row;
            })
            .collect(Collectors.toList());

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("connectionId", connectionId);
        response.put("question", question);
        response.put("topK", boundedTopK);
        response.put("resultCount", rows.size());
        response.put("durationMs", System.currentTimeMillis() - start);
        response.put("azureSearchEnabled", azureSearchService.isEnabled());
        if (azureSearchService.isEnabled()) {
            response.put("azureSearch", azureSearchService.getConnectionStats(connectionId));
        }
        response.put("results", rows);

        return response;
    }

    /**
     * Clear cache for a connection
     * Also deletes from Azure Search if enabled
     */
    public void clearCache(String connectionId) {
        embeddingCache.remove(connectionId);

        // Also clear from Azure Search
        if (azureSearchService.isEnabled()) {
            azureSearchService.deleteConnectionDocuments(connectionId);
            log.info("Cleared cache and Azure Search documents for connection: {}", connectionId);
        } else {
            log.info("Cleared cache for connection: {}", connectionId);
        }

        evictRagCacheForConnection(connectionId);
    }

    /**
     * Re-index a connection: clear existing Azure Search documents and rebuild.
     * Rebuilds SCHEMA_DDL, COLUMN_VALUES, RELATIONSHIP via trainWithSchema(),
     * then re-embeds DOCUMENTATION and BUSINESS_TERM from SchemaDocumentation records.
     * Note: QUERY_EXAMPLE docs from chat history are NOT rebuilt (they're interaction-derived).
     */
    public void reindexConnection(String connectionId) {
        log.info("Starting reindex for connection: {}", connectionId);
        String dbType = getDbType(connectionId); // resolve once, reuse below
        clearCache(connectionId);
        trainWithSchema(connectionId);

        // Re-embed user documentation and business term docs from SchemaDocumentation table
        List<SchemaDocumentation> docs = schemaDocRepository.findByConnectionId(connectionId);
        int reindexed = 0;
        for (SchemaDocumentation doc : docs) {
            try {
                upsertDocumentationEmbeddingWithDbType(doc, dbType);
                reindexed++;
            } catch (Exception e) {
                log.warn("Failed to re-embed documentation {} during reindex: {}",
                        doc.getId(), e.getMessage());
            }
        }
        int reindexedKnowledge = 0;
        for (CompanyKnowledgeEntry entry : companyKnowledgeEntryRepository.findByConnectionId(connectionId)) {
            try {
                upsertCompanyKnowledgeEmbedding(entry);
                reindexedKnowledge++;
            } catch (Exception e) {
                log.warn("Failed to re-embed company knowledge {} during reindex: {}",
                    entry.getId(), e.getMessage());
            }
        }
        log.info("Reindex complete for connection: {} ({} docs re-embedded, {} company knowledge entries re-embedded)",
                connectionId, reindexed, reindexedKnowledge);
    }

    // Helper methods

    private List<TrainingDataEmbedding> stripEmbeddings(List<TrainingDataEmbedding> data) {
        if (data == null || data.isEmpty()) {
            return new ArrayList<>();
        }

        return data.stream()
            .map(item -> TrainingDataEmbedding.builder()
                .id(item.getId())
                .connectionId(item.getConnectionId())
                .content(item.getContent())
                .metadata(item.getMetadata())
                .type(item.getType())
                .score(item.getScore())
                .build())
            .collect(Collectors.toList());
    }

    private void cacheEmbedding(String connectionId, TrainingDataEmbedding embedding) {
        embeddingCache.computeIfAbsent(connectionId, k -> new ArrayList<>()).add(embedding);
    }

    private void evictRagCacheForConnection(String connectionId) {
        if (connectionId == null || connectionId.isBlank()) {
            return;
        }

        Cache cache = cacheManager.getCache("ragRetrieval");
        if (cache == null) {
            return;
        }

        String prefix = "dba-agent::ragRetrieval::" + connectionId + "::";
        ScanOptions options = ScanOptions.scanOptions()
            .match(prefix + "*")
            .count(1000)
            .build();

        long evicted = 0;
        try (RedisConnection connection = redisConnectionFactory.getConnection();
             Cursor<byte[]> cursor = connection.scan(options)) {
            while (cursor.hasNext()) {
                Long deleted = connection.del(cursor.next());
                if (deleted != null) {
                    evicted += deleted;
                }
            }
        } catch (Exception e) {
            log.warn("Failed to evict RAG cache for connection: {}", connectionId, e);
        }

        cacheMetricsService.recordEvict("ragRetrieval", evicted);
    }

    private void loadTrainingDataIntoCache(String connectionId) {
        try {
            // Load query examples
            List<QueryExample> examples = queryExampleRepository
                .findByConnectionIdAndSuccessfulTrue(connectionId);

            for (QueryExample example : examples) {
                String content = example.getNaturalLanguage() + "\n" + example.getSql();
                List<Double> embedding = embeddingService.createEmbedding(content);

                TrainingDataEmbedding data = TrainingDataEmbedding.builder()
                    .id(example.getId())
                    .connectionId(connectionId)
                    .content(content)
                    .type(TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE)
                    .embedding(embedding)
                    .build();

                cacheEmbedding(connectionId, data);
            }

            // Load documentation
            List<SchemaDocumentation> docs = schemaDocRepository.findByConnectionId(connectionId);
            for (SchemaDocumentation doc : docs) {
                String content = buildDocumentationContent(doc);
                List<Double> embedding = embeddingService.createEmbedding(content);

                TrainingDataEmbedding data = TrainingDataEmbedding.builder()
                    .id(doc.getId())
                    .connectionId(connectionId)
                    .content(content)
                    .type(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION)
                    .embedding(embedding)
                    .build();

                cacheEmbedding(connectionId, data);
            }

            List<CompanyKnowledgeEntry> knowledgeEntries =
                companyKnowledgeEntryRepository.findByConnectionId(connectionId);
            for (CompanyKnowledgeEntry entry : knowledgeEntries) {
                String content = buildCompanyKnowledgeContent(entry);
                List<Double> embedding = embeddingService.createEmbedding(content);

                TrainingDataEmbedding data = TrainingDataEmbedding.builder()
                    .id(entry.getId())
                    .connectionId(connectionId)
                    .content(content)
                    .metadata(createCompanyKnowledgeMetadata(entry))
                    .type(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE)
                    .embedding(embedding)
                    .build();

                cacheEmbedding(connectionId, data);
            }

            log.info("Loaded {} training examples into cache",
                embeddingCache.getOrDefault(connectionId, new ArrayList<>()).size());
        } catch (Exception e) {
            log.error("Error loading training data into cache", e);
        }
    }

    public void upsertDocumentationEmbedding(SchemaDocumentation doc) {
        upsertDocumentationEmbeddingWithDbType(doc, null);
    }

    /**
     * Internal: accepts pre-resolved dbType to avoid repeated schema scans during bulk reindex.
     */
    private void upsertDocumentationEmbeddingWithDbType(SchemaDocumentation doc, String cachedDbType) {
        storeDocumentationEmbeddings(doc, cachedDbType, true, null, null, null);
    }

    private EmbeddingBatchSummary storeDocumentationEmbeddings(
        SchemaDocumentation doc,
        String cachedDbType,
        boolean evictCache
    ) {
        return storeDocumentationEmbeddings(doc, cachedDbType, evictCache, null, null, null);
    }

    private EmbeddingBatchSummary storeDocumentationEmbeddings(
        SchemaDocumentation doc,
        String cachedDbType,
        boolean evictCache,
        RagDocumentStateService.RagDocumentState existingDocumentationState,
        RagDocumentStateService.RagDocumentState existingBusinessTermState,
        String deterministicRunId
    ) {
        if (doc == null || doc.getConnectionId() == null) {
            return EmbeddingBatchSummary.empty();
        }

        String content = buildDocumentationContent(doc);
        String contentHash = contentHash(content);
        boolean reuseExistingEmbedding = canReuseExistingEmbedding(existingDocumentationState, contentHash);
        List<Double> embedding = reuseExistingEmbedding
            ? List.of()
            : createEmbeddingOrEmpty(content, doc.getConnectionId(), doc.getId(), "DOCUMENTATION");
        boolean usableEmbedding = reuseExistingEmbedding || !embedding.isEmpty();
        List<Float> embeddingFloat = reuseExistingEmbedding ? null : toFloatVector(embedding);

        String docDbType = cachedDbType != null ? cachedDbType : getDbType(doc.getConnectionId());
        String documentationTableReference = documentationTableReference(doc);
        String searchObjectName = doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE
            ? documentationTableReference
            : doc.getObjectName();

        if (azureSearchService.isEnabled()) {
            try {
                String docObjectType = doc.getObjectType() != null ? doc.getObjectType().name() : "TABLE";
                TrainingDataSearchDocument searchDoc = TrainingDataSearchDocument.builder()
                    .id(doc.getId())
                    .connectionId(doc.getConnectionId())
                    .type("DOCUMENTATION")
                    .content(content)
                    .objectName(searchObjectName)
                    .tableName(azureSearchService.resolveTableName(
                            documentationTableReference, documentationTableReference, docObjectType, docDbType))
                    .description(doc.getDescription())
                    .businessTerms(doc.getBusinessTerms())
                    .contentVector(embeddingFloat)
                    .metadata(withDeterministicMetadata(
                        createDocumentationMetadata(doc),
                        contentHash,
                        deterministicRunId
                    ))
                    .build();
                azureSearchService.indexDocument(searchDoc);
            } catch (Exception e) {
                log.warn("Azure Search indexing failed for documentation {}: {}", doc.getId(), e.getMessage());
                return EmbeddingBatchSummary.empty();
            }
        } else {
            removeEmbeddingById(doc.getConnectionId(), doc.getId());
            TrainingDataEmbedding trainingData = TrainingDataEmbedding.builder()
                .id(doc.getId())
                .connectionId(doc.getConnectionId())
                .content(content)
                .type(TrainingDataEmbedding.TrainingDataType.DOCUMENTATION)
                .embedding(embedding)
                .build();
            cacheEmbedding(doc.getConnectionId(), trainingData);
        }

        log.info("Stored documentation embedding: {}", doc.getId());
        EmbeddingBatchSummary summary = EmbeddingBatchSummary.of("DOCUMENTATION", usableEmbedding);

        // Create BUSINESS_TERM doc if business terms exist (improves synonym ranking — design 2F)
        if (doc.getBusinessTerms() != null && !doc.getBusinessTerms().isBlank()) {
            summary = summary.plus(upsertBusinessTermDocument(
                doc,
                docDbType,
                existingBusinessTermState,
                deterministicRunId
            ));
        }

        if (evictCache) {
            evictRagCacheForConnection(doc.getConnectionId());
        }
        return summary;
    }

    private EmbeddingBatchSummary upsertBusinessTermDocument(
        SchemaDocumentation doc,
        String dbType,
        RagDocumentStateService.RagDocumentState existingBusinessTermState,
        String deterministicRunId
    ) {
        String objectName = documentationTableReference(doc);
        String connectionId = doc.getConnectionId();

        String businessTermBlock = buildBusinessTermContent(objectName,
                doc.getBusinessTerms(), doc.getDescription());

        String btId = createBusinessTermDocId(connectionId, objectName);

        try {
            String contentHash = contentHash(businessTermBlock);
            boolean reuseExistingEmbedding = canReuseExistingEmbedding(existingBusinessTermState, contentHash);
            List<Double> btEmbedding = reuseExistingEmbedding
                ? List.of()
                : createEmbeddingOrEmpty(businessTermBlock, connectionId, btId, "BUSINESS_TERM");
            boolean usableEmbedding = reuseExistingEmbedding || !btEmbedding.isEmpty();
            List<Float> btEmbeddingFloat = reuseExistingEmbedding ? null : btEmbedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            if (azureSearchService.isEnabled()) {
                TrainingDataSearchDocument searchDoc = TrainingDataSearchDocument.builder()
                        .id(btId)
                        .connectionId(connectionId)
                        .type("BUSINESS_TERM")
                        .content(businessTermBlock)
                        .objectName(objectName)
                        .tableName(azureSearchService.resolveTableName(
                                objectName, objectName, "TABLE", dbType))
                        .businessTerms(doc.getBusinessTerms())
                        .contentVector(btEmbeddingFloat)
                        .metadata(withDeterministicMetadata(
                            createDocumentationMetadata(doc),
                            contentHash,
                            deterministicRunId
                        ))
                        .build();
                azureSearchService.indexDocument(searchDoc);
            } else {
                removeEmbeddingById(connectionId, btId);
                TrainingDataEmbedding trainingData = TrainingDataEmbedding.builder()
                        .id(btId)
                        .connectionId(connectionId)
                        .content(businessTermBlock)
                        .type(TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM)
                        .embedding(btEmbedding)
                        .build();
                cacheEmbedding(connectionId, trainingData);
            }
            log.info("Stored BUSINESS_TERM embedding: {} for {}", btId, objectName);
            return EmbeddingBatchSummary.of("BUSINESS_TERM", usableEmbedding);
        } catch (Exception e) {
            log.warn("Failed to create BUSINESS_TERM doc for {}: {}", objectName, e.getMessage());
            return EmbeddingBatchSummary.empty();
        }
    }

    private String buildBusinessTermContent(String objectName, String businessTerms, String description) {
        StringBuilder sb = new StringBuilder();
        sb.append("Business Terms for: ").append(objectName).append("\n");
        sb.append("Aliases: ").append(businessTerms).append("\n");
        if (description != null && !description.isBlank()) {
            sb.append("Table Description: ").append(description);
        }
        return sb.toString();
    }

    private static Map<String, Integer> mergeCounts(Map<String, Integer> left, Map<String, Integer> right) {
        Map<String, Integer> merged = new LinkedHashMap<>();
        mergeCountsInto(merged, left);
        mergeCountsInto(merged, right);
        return merged;
    }

    private static void mergeCountsInto(Map<String, Integer> target, Map<String, Integer> source) {
        if (target == null || source == null || source.isEmpty()) {
            return;
        }
        source.forEach((key, value) -> {
            if (key != null && value != null && value > 0) {
                target.merge(key, value, Integer::sum);
            }
        });
    }

    private static void incrementCount(Map<String, Integer> counts, String key) {
        if (counts == null || key == null || key.isBlank()) {
            return;
        }
        counts.merge(key, 1, Integer::sum);
    }

    // --- Stage 1: Table Resolution (design doc section 2A) ---

    private static final int MAX_RESOLVED_TABLES = 15;

    /**
     * Stage 1: Resolve which tables are relevant to the user's question.
     * Three signals: (1) substring match, (2) RAG result table extraction, (3) 2-hop FK expansion.
     */
    public Set<QualifiedTableName> resolveRelevantTables(
            SchemaMetadata schema, String userQuestion,
            List<TrainingDataEmbedding> ragResults, String dbType) {

        String lowerQuestion = userQuestion.toLowerCase(Locale.ROOT);

        // Signal 1: Substring match
        Set<QualifiedTableName> substringMatched = new LinkedHashSet<>();
        for (TableMetadata table : schema.getTables()) {
            if (lowerQuestion.contains(table.getName().toLowerCase(Locale.ROOT))) {
                substringMatched.add(toQualifiedTableName(table, dbType));
            }
        }

        // Signal 2: RAG result table extraction
        Set<QualifiedTableName> ragTables = extractQualifiedTableNamesFromRag(
                ragResults, schema, dbType);

        // Combine signals 1 + 2
        Set<QualifiedTableName> resolvedTables = new LinkedHashSet<>(substringMatched);
        resolvedTables.addAll(ragTables);

        // Signal 3: 2-hop graph expansion with fan-out guard
        Set<QualifiedTableName> expanded = new LinkedHashSet<>(resolvedTables);
        for (QualifiedTableName qt : new ArrayList<>(resolvedTables)) {
            Set<QualifiedTableName> hop1 = getRelatedQualifiedTables(schema, qt, dbType);
            for (QualifiedTableName neighbor : hop1) {
                expanded.add(neighbor);
                // 2-hop: only if neighbor appeared in RAG results (prevents fan-out explosion)
                if (ragTables.contains(neighbor)) {
                    expanded.addAll(getRelatedQualifiedTables(schema, neighbor, dbType));
                }
            }
        }

        // Cap at MAX_RESOLVED_TABLES, prioritizing: substring > RAG > graph-expanded
        if (expanded.size() <= MAX_RESOLVED_TABLES) {
            return expanded;
        }
        Set<QualifiedTableName> capped = new LinkedHashSet<>();
        for (QualifiedTableName qt : substringMatched) {
            if (capped.size() >= MAX_RESOLVED_TABLES) break;
            capped.add(qt);
        }
        for (QualifiedTableName qt : ragTables) {
            if (capped.size() >= MAX_RESOLVED_TABLES) break;
            capped.add(qt);
        }
        for (QualifiedTableName qt : expanded) {
            if (capped.size() >= MAX_RESOLVED_TABLES) break;
            capped.add(qt);
        }
        return capped;
    }

    /**
     * Build a QualifiedTableName from a TableMetadata entry.
     * MySQL: always bare (no schema prefix concept).
     * PostgreSQL: bare if default schema, qualified if non-default.
     */
    QualifiedTableName toQualifiedTableName(TableMetadata table, String dbType) {
        String bare = table.getName();
        String defaultSchema;
        try {
            defaultSchema = databaseProviderRegistry.getDialect(dbType)
                    .introspection().getDefaultSchema();
        } catch (Exception e) {
            log.warn("Could not resolve default schema for dbType={}, treating as bare", dbType);
            return new QualifiedTableName(bare, null);
        }

        // MySQL: getDefaultSchema() returns null → always bare
        if (defaultSchema == null) {
            return new QualifiedTableName(bare, null);
        }

        String tableSchema = table.getSchema();
        if (tableSchema == null || tableSchema.equalsIgnoreCase(defaultSchema)) {
            return new QualifiedTableName(bare, null);
        }
        return new QualifiedTableName(bare, tableSchema + "." + bare);
    }

    /**
     * Construct schema-qualified name from TableMetadata for use with resolveTableName().
     * Ensures non-default schema tables (e.g., sales.orders) are preserved during indexing.
     */
    private String qualifyTableName(TableMetadata table) {
        return table.getSchema() != null && !table.getSchema().isBlank()
                ? table.getSchema() + "." + table.getName()
                : table.getName();
    }

    /**
     * Extract qualified table names from RAG results by parsing tablesUsed and objectName metadata.
     * Maps bare names back to SchemaMetadata for schema qualification.
     */
    Set<QualifiedTableName> extractQualifiedTableNamesFromRag(
            List<TrainingDataEmbedding> ragResults, SchemaMetadata schema, String dbType) {

        // Build lookup: bare name → list of matching TableMetadata
        Map<String, List<TableMetadata>> byBareName = schema.getTables().stream()
                .collect(Collectors.groupingBy(t -> t.getName().toLowerCase(Locale.ROOT)));

        Set<String> bareNames = new LinkedHashSet<>();
        for (TrainingDataEmbedding result : ragResults) {
            bareNames.addAll(extractBareTableNames(result));
        }

        Set<QualifiedTableName> qualified = new LinkedHashSet<>();
        for (String bareName : bareNames) {
            List<TableMetadata> matches = byBareName.get(bareName.toLowerCase(Locale.ROOT));
            if (matches == null) continue;
            if (matches.size() == 1) {
                qualified.add(toQualifiedTableName(matches.get(0), dbType));
            } else {
                // Ambiguous (e.g., sales.orders AND billing.orders) — include all
                for (TableMetadata match : matches) {
                    qualified.add(toQualifiedTableName(match, dbType));
                }
            }
        }
        return qualified;
    }

    /**
     * Extract bare table names from a single RAG result.
     * Checks: (1) tablesUsed in metadata, (2) objectName in metadata.
     */
    private Set<String> extractBareTableNames(TrainingDataEmbedding result) {
        Set<String> names = new LinkedHashSet<>();
        if (result.getMetadata() == null) return names;

        try {
            var node = objectMapper.readTree(result.getMetadata());

            // tablesUsed: comma-separated bare names (from QUERY_EXAMPLE docs)
            if (node.has("tablesUsed") && !node.get("tablesUsed").asText().isBlank()) {
                String tablesUsed = node.get("tablesUsed").asText();
                for (String table : tablesUsed.split(",")) {
                    String trimmed = table.trim().replaceAll("[^a-zA-Z0-9_]", "");
                    if (!trimmed.isEmpty()) {
                        names.add(trimmed);
                    }
                }
            }

            // objectName: bare or dot-qualified (from SCHEMA_DDL, DOCUMENTATION, etc.)
            if (node.has("objectName") && !node.get("objectName").asText().isBlank()) {
                String objectName = node.get("objectName").asText().trim();
                int dot = objectName.lastIndexOf('.');
                String bare = dot >= 0 ? objectName.substring(dot + 1) : objectName;
                if (!bare.isEmpty()) {
                    names.add(bare);
                }
            }
        } catch (Exception e) {
            log.debug("Failed to parse metadata for table extraction: {}", e.getMessage());
        }
        return names;
    }

    /**
     * Find tables related by FK to the given table (1-hop neighbors).
     */
    private Set<QualifiedTableName> getRelatedQualifiedTables(
            SchemaMetadata schema, QualifiedTableName qt, String dbType) {
        Set<QualifiedTableName> related = new LinkedHashSet<>();
        String storedForm = qt.storedForm().toLowerCase(Locale.ROOT);

        // Build lookup keyed by storedForm to avoid multi-schema collisions
        // (bare name for default schema, schema-qualified for non-default)
        Map<String, TableMetadata> tableByStoredForm = new HashMap<>();
        for (TableMetadata t : schema.getTables()) {
            String key = toQualifiedTableName(t, dbType).storedForm().toLowerCase(Locale.ROOT);
            tableByStoredForm.put(key, t);
        }

        List<RelationshipMetadata> relationships = schema.getRelationships() != null
                ? schema.getRelationships() : Collections.emptyList();
        for (RelationshipMetadata rel : relationships) {
            String from = rel.getFromTable() != null ? rel.getFromTable().toLowerCase(Locale.ROOT) : "";
            String to = rel.getToTable() != null ? rel.getToTable().toLowerCase(Locale.ROOT) : "";

            if (from.equals(storedForm) && tableByStoredForm.containsKey(to)) {
                related.add(toQualifiedTableName(tableByStoredForm.get(to), dbType));
            } else if (to.equals(storedForm) && tableByStoredForm.containsKey(from)) {
                related.add(toQualifiedTableName(tableByStoredForm.get(from), dbType));
            }
        }
        return related;
    }

    /**
     * Stage 2: Retrieve targeted documents filtered by resolved table names.
     * Returns TrainingDataEmbedding (not SearchDocument) for uniform handling.
     * Empty list if Stage 2 not enabled or tables empty.
     */
    public List<TrainingDataEmbedding> retrieveTargetedByTables(
            String connectionId, Set<QualifiedTableName> resolvedTables, int maxResults) {

        List<TrainingDataSearchDocument> searchDocs = azureSearchService.filterByTables(
                connectionId, resolvedTables, maxResults);

        return searchDocs.stream()
                .map(doc -> TrainingDataEmbedding.builder()
                        .id(doc.getId())
                        .connectionId(doc.getConnectionId())
                        .content(doc.getContent())
                        .type(resolveTrainingDataType(doc))
                        .score(doc.getSearchScore() != null ? doc.getSearchScore() : 0.9)
                        .metadata(doc.getMetadata())
                        .build())
                .toList();
    }

    /**
     * Stage 3: Dedup RAG results against Stage 2 results.
     * Removes documents already included in Stage 2 to avoid duplication.
     */
    public List<TrainingDataEmbedding> deduplicateAgainstTargeted(
            List<TrainingDataEmbedding> ragResults, List<TrainingDataEmbedding> stage2Results) {
        Set<String> stage2Ids = stage2Results.stream()
                .map(TrainingDataEmbedding::getId)
                .collect(Collectors.toSet());
        return ragResults.stream()
                .filter(doc -> !stage2Ids.contains(doc.getId()))
                .toList();
    }

    public void deleteDocumentationEmbedding(String connectionId, String docId) {
        if (docId == null || connectionId == null) {
            return;
        }
        if (azureSearchService.isEnabled()) {
            azureSearchService.deleteDocument(docId);
        } else {
            removeEmbeddingById(connectionId, docId);
        }
        evictRagCacheForConnection(connectionId);
    }

    public void upsertCompanyKnowledgeEmbedding(CompanyKnowledgeEntry entry) {
        if (entry == null || entry.getConnectionId() == null || entry.getConnectionId().isBlank()) {
            return;
        }

        String content = buildCompanyKnowledgeContent(entry);
        List<Double> embedding;
        try {
            embedding = embeddingService.createEmbedding(content);
        } catch (Exception e) {
            log.warn("Embedding failed for company knowledge {}: {}", entry.getId(), e.getMessage());
            return;
        }

        if (embedding.isEmpty()) {
            log.warn("No embedding returned for company knowledge {}", entry.getId());
            return;
        }

        List<Float> embeddingFloat = embedding.stream()
            .map(Double::floatValue)
            .collect(Collectors.toList());
        String dbType = getDbType(entry.getConnectionId());
        String primaryTable = resolvePrimaryCompanyKnowledgeTable(entry);

        if (azureSearchService.isEnabled()) {
            TrainingDataSearchDocument searchDoc = TrainingDataSearchDocument.builder()
                .id(entry.getId())
                .connectionId(entry.getConnectionId())
                .type("COMPANY_KNOWLEDGE")
                .content(content)
                .objectName(entry.getTitle())
                .tableName(primaryTable)
                .tablesUsed(buildCompanyKnowledgeTablesUsed(entry))
                .description(entry.getContent())
                .businessTerms(buildCompanyKnowledgeBusinessTerms(entry))
                .contentVector(embeddingFloat)
                .metadata(createCompanyKnowledgeMetadata(entry))
                .dbType(dbType)
                .build();
            azureSearchService.indexDocument(searchDoc);
        } else {
            removeEmbeddingById(entry.getConnectionId(), entry.getId());
            TrainingDataEmbedding trainingData = TrainingDataEmbedding.builder()
                .id(entry.getId())
                .connectionId(entry.getConnectionId())
                .content(content)
                .metadata(createCompanyKnowledgeMetadata(entry))
                .type(TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE)
                .embedding(embedding)
                .build();
            cacheEmbedding(entry.getConnectionId(), trainingData);
        }

        log.info("Stored company knowledge embedding: {}", entry.getId());
        evictRagCacheForConnection(entry.getConnectionId());
    }

    public void deleteCompanyKnowledgeEmbedding(String connectionId, String entryId) {
        if (entryId == null || connectionId == null) {
            return;
        }
        if (azureSearchService.isEnabled()) {
            azureSearchService.deleteDocument(entryId);
        } else {
            removeEmbeddingById(connectionId, entryId);
        }
        evictRagCacheForConnection(connectionId);
    }

    private boolean isInterrupted(Throwable error) {
        Throwable current = error;
        while (current != null) {
            if (current instanceof InterruptedException) {
                return true;
            }
            current = current.getCause();
        }
        return Thread.currentThread().isInterrupted();
    }

    private boolean useLocalPgVectorDelta() {
        return azureSearchService instanceof PgVectorSearchService && azureSearchService.isEnabled();
    }

    private boolean canReuseExistingEmbedding(
        RagDocumentStateService.RagDocumentState existingState,
        String contentHash
    ) {
        return existingState != null
            && existingState.hasEmbedding()
            && contentHash != null
            && contentHash.equals(existingState.contentHash());
    }

    private String contentHash(String content) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                .digest((content != null ? content : "").getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(String.format(Locale.ROOT, "%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to hash deterministic RAG content", e);
        }
    }

    private String withDeterministicMetadata(String metadata, String contentHash, String runId) {
        if (contentHash == null || runId == null || runId.isBlank()) {
            return metadata;
        }
        try {
            Map<String, Object> payload = metadata != null && !metadata.isBlank()
                ? objectMapper.readValue(metadata, objectMapper.getTypeFactory().constructMapType(HashMap.class, String.class, Object.class))
                : new HashMap<>();
            payload.put("_contentHash", contentHash);
            payload.put("_lastSeenRunId", runId);
            return objectMapper.writeValueAsString(payload);
        } catch (Exception e) {
            Map<String, Object> payload = new HashMap<>();
            if (metadata != null && !metadata.isBlank()) {
                payload.put("_baseMetadata", metadata);
            }
            payload.put("_contentHash", contentHash);
            payload.put("_lastSeenRunId", runId);
            try {
                return objectMapper.writeValueAsString(payload);
            } catch (Exception ignored) {
                return metadata;
            }
        }
    }

    private String createMetadata(TableMetadata table) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("tableName", table.getName());
            metadata.put("columnCount", table.getColumns().size());
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String createDocumentationMetadata(SchemaDocumentation doc) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("objectType", doc.getObjectType() != null ? doc.getObjectType().name() : null);
            metadata.put("objectName", doc.getObjectName());
            metadata.put("parentObject", doc.getParentObject());
            metadata.put("tableReference", documentationTableReference(doc));
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String createCompanyKnowledgeMetadata(CompanyKnowledgeEntry entry) {
        try {
            Map<String, Object> metadata = new HashMap<>();
            metadata.put("entryType", entry.getEntryType() != null ? entry.getEntryType().name() : null);
            metadata.put("title", entry.getTitle());
            metadata.put("linkedTables", entry.getLinkedTables());
            metadata.put("linkedColumns", entry.getLinkedColumns());
            metadata.put("tableName", resolvePrimaryCompanyKnowledgeTable(entry));
            return objectMapper.writeValueAsString(metadata);
        } catch (Exception e) {
            return "{}";
        }
    }

    private String createSchemaDocId(String connectionId, String tableName) {
        // tableName should be schema-qualified (e.g., sales.orders) to prevent
        // collisions between same-named tables in different schemas
        String raw = connectionId + "::SCHEMA_DDL::" + tableName;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String createBusinessTermDocId(String connectionId, String objectName) {
        String raw = connectionId + "::BUSINESS_TERM::" + objectName;
        return UUID.nameUUIDFromBytes(raw.getBytes(StandardCharsets.UTF_8)).toString();
    }

    private String businessTermDocId(SchemaDocumentation doc) {
        return createBusinessTermDocId(doc.getConnectionId(), documentationTableReference(doc));
    }

    private String buildDocumentationContent(SchemaDocumentation doc) {
        String objectName = doc.getObjectName() != null ? doc.getObjectName() : "";
        String description = doc.getDescription() != null ? doc.getDescription() : "";
        String content = objectName + "\n" + description;

        if (doc.getParentObject() != null && !doc.getParentObject().isBlank()) {
            content = doc.getParentObject() + "." + content;
        }
        if (doc.getBusinessTerms() != null && !doc.getBusinessTerms().isEmpty()) {
            content += "\nAliases: " + doc.getBusinessTerms();
        }
        if (doc.getExamples() != null && !doc.getExamples().isEmpty()) {
            content += "\nExamples: " + doc.getExamples();
        }
        return content;
    }

    private String buildCompanyKnowledgeContent(CompanyKnowledgeEntry entry) {
        StringBuilder sb = new StringBuilder();
        sb.append("Company Knowledge: ").append(entry.getTitle()).append("\n");
        if (entry.getEntryType() != null) {
            sb.append("Type: ").append(entry.getEntryType().name()).append("\n");
        }
        sb.append(entry.getContent());
        if (entry.getLinkedTables() != null && !entry.getLinkedTables().isEmpty()) {
            sb.append("\nLinked tables: ").append(String.join(", ", entry.getLinkedTables()));
        }
        if (entry.getLinkedColumns() != null && !entry.getLinkedColumns().isEmpty()) {
            sb.append("\nLinked columns: ").append(String.join(", ", entry.getLinkedColumns()));
        }
        return sb.toString();
    }

    private String buildCompanyKnowledgeBusinessTerms(CompanyKnowledgeEntry entry) {
        List<String> values = new ArrayList<>();
        if (entry.getTitle() != null && !entry.getTitle().isBlank()) {
            values.add(entry.getTitle().trim());
        }
        if (entry.getEntryType() != null) {
            values.add(entry.getEntryType().name().replace('_', ' ').toLowerCase(Locale.ROOT));
        }
        return values.isEmpty() ? null : String.join(", ", values);
    }

    private String resolvePrimaryCompanyKnowledgeTable(CompanyKnowledgeEntry entry) {
        if (entry == null) {
            return null;
        }
        if (entry.getLinkedTables() != null && !entry.getLinkedTables().isEmpty()) {
            return entry.getLinkedTables().getFirst();
        }
        if (entry.getLinkedColumns() != null && !entry.getLinkedColumns().isEmpty()) {
            String columnReference = entry.getLinkedColumns().getFirst();
            int lastDot = columnReference != null ? columnReference.lastIndexOf('.') : -1;
            if (lastDot > 0) {
                return columnReference.substring(0, lastDot);
            }
        }
        return null;
    }

    /**
     * Comma-separated list of every table this company-knowledge entry is linked to,
     * including tables derived from linked columns. Populated into {@code tablesUsed} on
     * the search document so Stage-2 table-targeted retrieval surfaces the entry whenever
     * any of its linked tables enters the focus set — not just the primary one.
     */
    private String buildCompanyKnowledgeTablesUsed(CompanyKnowledgeEntry entry) {
        if (entry == null) {
            return null;
        }
        java.util.LinkedHashSet<String> tables = new java.util.LinkedHashSet<>();
        if (entry.getLinkedTables() != null) {
            for (String table : entry.getLinkedTables()) {
                if (table != null && !table.isBlank()) {
                    tables.add(table.trim());
                }
            }
        }
        if (entry.getLinkedColumns() != null) {
            for (String columnReference : entry.getLinkedColumns()) {
                if (columnReference == null) continue;
                int lastDot = columnReference.lastIndexOf('.');
                if (lastDot > 0) {
                    String table = columnReference.substring(0, lastDot).trim();
                    if (!table.isBlank()) {
                        tables.add(table);
                    }
                }
            }
        }
        return tables.isEmpty() ? null : String.join(",", tables);
    }

    private String documentationTableReference(SchemaDocumentation doc) {
        if (doc == null) {
            return "";
        }
        if (doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN) {
            return SchemaObjectNameUtil.canonicalTableReference(doc.getParentObject());
        }
        return SchemaObjectNameUtil.canonicalTableReference(doc.getObjectName());
    }

    private String safeString(String value) {
        return value == null ? "" : value;
    }

    private void removeEmbeddingById(String connectionId, String docId) {
        List<TrainingDataEmbedding> cached = embeddingCache.get(connectionId);
        if (cached == null || cached.isEmpty()) {
            return;
        }
        cached.removeIf(item -> docId.equals(item.getId()));
    }

    /**
     * Normalize SQL for consistent storage and lookup: trim, collapse whitespace, strip trailing semicolons.
     */
    static String normalizeSql(String sql) {
        if (sql == null) return null;
        String normalized = sql.strip().replaceAll("\\s+", " ");
        while (normalized.endsWith(";")) {
            normalized = normalized.substring(0, normalized.length() - 1).strip();
        }
        return normalized;
    }

    private String extractTablesFromSQL(String sql) {
        // Simple extraction - in production, use SQL parser
        String upperSql = sql.toUpperCase();
        Set<String> tables = new HashSet<>();

        String[] words = sql.split("\\s+");
        boolean afterFrom = false;
        boolean afterJoin = false;

        for (int i = 0; i < words.length; i++) {
            String word = words[i].toUpperCase();

            if (word.equals("FROM") || word.contains("FROM")) {
                afterFrom = true;
                continue;
            }

            if (word.contains("JOIN")) {
                afterJoin = true;
                continue;
            }

            if (afterFrom || afterJoin) {
                String tableName = words[i]
                    .replaceAll("[^a-zA-Z0-9_]", "")
                    .trim();

                if (!tableName.isEmpty() && !tableName.equalsIgnoreCase("WHERE") &&
                    !tableName.equalsIgnoreCase("ORDER") && !tableName.equalsIgnoreCase("GROUP")) {
                    tables.add(tableName);
                    afterFrom = false;
                    afterJoin = false;
                }
            }
        }

        return String.join(",", tables);
    }

    private String getDbType(String connectionId) {
        try {
            SchemaMetadata schema = schemaScannerService.scanSchema(connectionId);
            return schema.getDbType();
        } catch (Exception e) {
            return "unknown";
        }
    }

    private TrainingDataEmbedding.TrainingDataType resolveTrainingDataType(TrainingDataSearchDocument doc) {
        if (doc == null) {
            return TrainingDataEmbedding.TrainingDataType.DOCUMENTATION;
        }

        String rawType = doc.getType();
        String normalized = rawType == null
            ? ""
            : rawType.trim().toUpperCase(Locale.ROOT).replace('-', '_').replace(' ', '_');

        return switch (normalized) {
            case "QUERY_EXAMPLE", "QUERY_EXAMPLES", "EXAMPLE_QUERY" ->
                TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE;
            case "SCHEMA_DDL", "DDL", "SCHEMA", "TABLE_SCHEMA" ->
                TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL;
            case "DOCUMENTATION", "DOC", "SCHEMA_DOC", "TABLE_DOC", "COLUMN_DOC" ->
                TrainingDataEmbedding.TrainingDataType.DOCUMENTATION;
            case "BUSINESS_TERM", "BUSINESS_TERMS", "GLOSSARY", "TERM" ->
                TrainingDataEmbedding.TrainingDataType.BUSINESS_TERM;
            case "COMPANY_KNOWLEDGE", "COMPANY_CONTEXT", "WORKFLOW_CONTEXT" ->
                TrainingDataEmbedding.TrainingDataType.COMPANY_KNOWLEDGE;
            case "COLUMN_VALUES", "COLUMN_VALUE", "ENUM_VALUES", "VALUE_SET", "DOMAIN_VALUES" ->
                TrainingDataEmbedding.TrainingDataType.COLUMN_VALUES;
            case "QUERY_PATTERN", "PLAN_PATTERN" ->
                TrainingDataEmbedding.TrainingDataType.QUERY_PATTERN;
            case "WORKLOAD_INSIGHT", "WORKLOAD_PROFILE", "WORKLOAD" ->
                TrainingDataEmbedding.TrainingDataType.WORKLOAD_INSIGHT;
            case "CARDINALITY_INSIGHT", "CARDINALITY" ->
                TrainingDataEmbedding.TrainingDataType.CARDINALITY_INSIGHT;
            case "RELATIONSHIP", "RELATIONSHIPS", "FK_RELATIONSHIP", "INFERRED_RELATIONSHIP" ->
                TrainingDataEmbedding.TrainingDataType.RELATIONSHIP;
            default -> {
                TrainingDataEmbedding.TrainingDataType inferred = inferTypeFromDocument(doc);
                log.warn("Unknown training document type '{}' for document {}, falling back to {}",
                    rawType, doc.getId(), inferred);
                yield inferred;
            }
        };
    }

    private TrainingDataEmbedding.TrainingDataType inferTypeFromDocument(TrainingDataSearchDocument doc) {
        if (doc.getSql() != null && !doc.getSql().isBlank()) {
            return TrainingDataEmbedding.TrainingDataType.QUERY_EXAMPLE;
        }
        if (doc.getObjectName() != null && !doc.getObjectName().isBlank() &&
            doc.getContent() != null && doc.getContent().contains("Columns:")) {
            return TrainingDataEmbedding.TrainingDataType.SCHEMA_DDL;
        }
        return TrainingDataEmbedding.TrainingDataType.DOCUMENTATION;
    }

    private List<TrainingDataEmbedding> takeByType(
        List<TrainingDataEmbedding> relevantData,
        Set<TrainingDataEmbedding.TrainingDataType> includedTypes,
        int limit
    ) {
        return relevantData.stream()
            .filter(d -> d.getType() != null && includedTypes.contains(d.getType()))
            .limit(limit)
            .collect(Collectors.toList());
    }

    private void appendContextSection(
        StringBuilder context,
        String title,
        List<TrainingDataEmbedding> data,
        int maxContentLength
    ) {
        if (data == null || data.isEmpty()) {
            return;
        }

        context.append("\n").append(title).append(":\n");
        for (TrainingDataEmbedding item : data) {
            context.append("- [").append(item.getType()).append("] ");
            context.append(truncateAndNormalize(item.getContent(), maxContentLength));
            if (item.getScore() != null) {
                context.append(" (relevance: ")
                    .append(String.format(Locale.ROOT, "%.2f", item.getScore()))
                    .append(")");
            }
            context.append("\n");
        }
    }

    private String truncateAndNormalize(String content, int maxLength) {
        if (content == null || content.isBlank()) {
            return "";
        }

        String normalized = content
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replaceAll("\\s+", " ")
            .trim();

        if (normalized.length() <= maxLength) {
            return normalized;
        }

        return normalized.substring(0, maxLength) + "...";
    }

    private Set<String> getVerifiedExampleIds(String connectionId) {
        try {
            return queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndVerifiedTrue(connectionId)
                .stream()
                .map(QueryExample::getId)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("Failed to load verified example IDs: {}", e.getMessage());
            return Set.of();
        }
    }

    private Set<String> getRejectedExampleIds(String connectionId) {
        try {
            return queryExampleRepository.findByConnectionIdAndSuccessfulTrueAndRejectedTrue(connectionId)
                .stream()
                .map(QueryExample::getId)
                .collect(Collectors.toSet());
        } catch (Exception e) {
            log.debug("Failed to load rejected example IDs: {}", e.getMessage());
            return Set.of();
        }
    }
}
