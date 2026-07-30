package com.dbaagent.service;

import com.azure.core.credential.AzureKeyCredential;
import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.SearchClientBuilder;
import com.azure.search.documents.indexes.SearchIndexClient;
import com.azure.search.documents.indexes.SearchIndexClientBuilder;
import com.azure.search.documents.indexes.models.*;
import com.azure.search.documents.models.*;
import com.azure.search.documents.util.SearchPagedIterable;
import com.dbaagent.model.QualifiedTableName;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.dbaagent.provider.DatabaseProviderRegistry;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.*;
import java.util.Locale;
import java.util.stream.Collectors;

/**
 * Azure AI Search Service for advanced RAG capabilities.
 * Supports versioned index migration (v1 → v2) with MigrationPhase gating.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class AzureSearchService implements VectorSearchService {

    private static final String INDEX_V2_SUFFIX = "-v2";

    @Value("${azure.search.endpoint}")
    private String searchEndpoint;

    @Value("${azure.search.api-key}")
    private String searchApiKey;

    @Value("${azure.search.index-name}")
    private String indexName;

    @Value("${azure.search.enabled:false}")
    private boolean azureSearchEnabled;

    @Value("${azure.search.index.max-retries:3}")
    private int maxIndexRetries;

    @Value("${azure.search.index.base-backoff-ms:300}")
    private long baseBackoffMs;

    private SearchClient searchClient;
    private SearchClient v2Client;
    private SearchIndexClient indexClient;

    @Getter
    private volatile MigrationPhase migrationPhase = MigrationPhase.V1_ONLY;

    private final MeterRegistry meterRegistry;
    private final DatabaseProviderRegistry databaseProviderRegistry;

    /**
     * Sole source of truth for index migration state.
     * Controls write routing and Stage 2 availability.
     */
    public enum MigrationPhase {
        V1_ONLY,     // reads/writes v1 only, Stage 2 disabled
        DUAL_WRITE,  // writes to v1+v2, reads from v1, Stage 2 disabled
        V2_ACTIVE    // reads/writes v2 only, Stage 2 enabled, v1 kept for rollback
    }

    public boolean isStage2Enabled() {
        return migrationPhase == MigrationPhase.V2_ACTIVE;
    }

    @Override
    public String backendName() {
        return "Azure AI Search";
    }

    private boolean isDualWrite() {
        return migrationPhase == MigrationPhase.DUAL_WRITE;
    }

    private SearchClient activeReadClient() {
        return migrationPhase == MigrationPhase.V2_ACTIVE ? v2Client : searchClient;
    }

    /**
     * Returns the primary write target based on migration phase:
     * V1_ONLY → v1, DUAL_WRITE → v1 (v2 handled separately), V2_ACTIVE → v2.
     */
    private SearchClient activeWriteClient() {
        return migrationPhase == MigrationPhase.V2_ACTIVE ? v2Client : searchClient;
    }

    @PostConstruct
    public void initialize() {
        if (!azureSearchEnabled) {
            log.info("Azure AI Search is disabled. Using in-memory cache instead.");
            return;
        }

        try {
            AzureKeyCredential credential = new AzureKeyCredential(searchApiKey);

            // Initialize v1 search client
            searchClient = new SearchClientBuilder()
                    .endpoint(searchEndpoint)
                    .credential(credential)
                    .indexName(indexName)
                    .buildClient();

            // Initialize index client for management operations
            indexClient = new SearchIndexClientBuilder()
                    .endpoint(searchEndpoint)
                    .credential(credential)
                    .buildClient();

            // Create or update v1 index
            createOrUpdateIndex();

            // Startup reconciliation: probe v2 to derive correct phase
            reconcileMigrationPhase(credential);

            log.info("Azure AI Search initialized with index: {}, migrationPhase: {}",
                    indexName, migrationPhase);
        } catch (Exception e) {
            log.error("Failed to initialize Azure AI Search. Falling back to in-memory cache.", e);
            azureSearchEnabled = false;
        }
    }

    /**
     * Probe Azure for v2 index existence and derive the correct MigrationPhase.
     * v2 index is NOT created on startup — only by the backfill admin endpoint.
     */
    private void reconcileMigrationPhase(AzureKeyCredential credential) {
        boolean v1Exists = indexExists(indexName);
        boolean v2Exists = indexExists(indexName + INDEX_V2_SUFFIX);

        if (!v2Exists) {
            migrationPhase = MigrationPhase.V1_ONLY;
        } else {
            // v2 exists — initialize v2 client
            v2Client = new SearchClientBuilder()
                    .endpoint(searchEndpoint)
                    .credential(credential)
                    .indexName(indexName + INDEX_V2_SUFFIX)
                    .buildClient();

            if (v1Exists) {
                // Both exist — mid-migration or completed
                if (v2PassesParityCheck()) {
                    migrationPhase = MigrationPhase.V2_ACTIVE;
                } else {
                    migrationPhase = MigrationPhase.DUAL_WRITE;
                }
            } else {
                // v1 deleted (post-rollback-window) — v2 is the only index
                migrationPhase = MigrationPhase.V2_ACTIVE;
            }
        }

        log.info("Migration reconciliation: v1Exists={}, v2Exists={}, phase={}",
                v1Exists, v2Exists, migrationPhase);
    }

    private boolean indexExists(String name) {
        try {
            indexClient.getIndex(name);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    /**
     * Simple parity check: compare total document counts in v1 vs v2.
     * A more thorough check (sampling + content hash) runs during backfill.
     */
    private boolean v2PassesParityCheck() {
        try {
            long v1Count = countDocuments(searchClient);
            long v2Count = countDocuments(v2Client);
            boolean pass = v2Count >= v1Count;
            log.info("Parity check: v1={}, v2={}, pass={}", v1Count, v2Count, pass);
            return pass;
        } catch (Exception e) {
            log.warn("Parity check failed, assuming DUAL_WRITE", e);
            return false;
        }
    }

    private long countDocuments(SearchClient client) {
        SearchOptions options = new SearchOptions()
                .setIncludeTotalCount(true)
                .setTop(0);
        SearchPagedIterable results = client.search("*", options, Context.NONE);
        return results.getTotalCount() != null ? results.getTotalCount() : 0;
    }

    /**
     * Create or update the v1 search index.
     */
    private void createOrUpdateIndex() {
        try {
            SearchIndex index = new SearchIndex(indexName)
                    .setFields(buildV1Fields())
                    .setVectorSearch(buildVectorSearch());

            indexClient.createOrUpdateIndex(index);
            log.info("Search index created/updated successfully: {}", indexName);
        } catch (Exception e) {
            log.error("Failed to create/update search index", e);
            throw new RuntimeException("Index creation failed", e);
        }
    }

    /**
     * Create the v2 index with enhanced schema (objectName filterable, tableName filterable, type sortable).
     * Called ONLY by the backfill admin endpoint, never on startup.
     *
     * @return the v2 SearchClient
     */
    public SearchClient createV2Index() {
        String v2Name = indexName + INDEX_V2_SUFFIX;
        try {
            SearchIndex index = new SearchIndex(v2Name)
                    .setFields(buildV2Fields())
                    .setVectorSearch(buildVectorSearch());

            indexClient.createOrUpdateIndex(index);
            log.info("V2 index created: {}", v2Name);

            AzureKeyCredential credential = new AzureKeyCredential(searchApiKey);
            v2Client = new SearchClientBuilder()
                    .endpoint(searchEndpoint)
                    .credential(credential)
                    .indexName(v2Name)
                    .buildClient();

            migrationPhase = MigrationPhase.DUAL_WRITE;
            log.info("Migration phase set to DUAL_WRITE");

            return v2Client;
        } catch (Exception e) {
            log.error("Failed to create v2 index: {}", v2Name, e);
            throw new RuntimeException("V2 index creation failed", e);
        }
    }

    /**
     * Transition to V2_ACTIVE after parity check passes.
     */
    public void activateV2() {
        if (v2Client == null) {
            throw new IllegalStateException("v2 index not created yet");
        }
        migrationPhase = MigrationPhase.V2_ACTIVE;
        log.info("Migration phase set to V2_ACTIVE — reads now from v2");
    }

    private List<SearchField> buildV1Fields() {
        return Arrays.asList(
                new SearchField("id", SearchFieldDataType.STRING)
                        .setKey(true)
                        .setFilterable(true),
                new SearchField("connectionId", SearchFieldDataType.STRING)
                        .setFilterable(true),
                new SearchField("type", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setFilterable(true),
                new SearchField("content", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.EN_LUCENE),
                new SearchField("naturalLanguage", SearchFieldDataType.STRING)
                        .setSearchable(true),
                new SearchField("sql", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.EN_MICROSOFT),
                new SearchField("objectName", SearchFieldDataType.STRING)
                        .setSearchable(true),
                new SearchField("tableName", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setFilterable(true),
                new SearchField("description", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.EN_LUCENE),
                new SearchField("businessTerms", SearchFieldDataType.STRING)
                        .setSearchable(true),
                new SearchField("tablesUsed", SearchFieldDataType.STRING)
                        .setFilterable(true),
                new SearchField("dbType", SearchFieldDataType.STRING)
                        .setFilterable(true),
                new SearchField("contentVector", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                        .setSearchable(true)
                        .setVectorSearchDimensions(3072)
                        .setVectorSearchProfileName("vector-profile"),
                new SearchField("metadata", SearchFieldDataType.STRING),
                new SearchField("createdAt", SearchFieldDataType.STRING)
                        .setFilterable(true)
                        .setSortable(true),
                new SearchField("successful", SearchFieldDataType.BOOLEAN)
                        .setFilterable(true),
                new SearchField("executionTimeMs", SearchFieldDataType.INT64)
        );
    }

    private List<SearchField> buildV2Fields() {
        return Arrays.asList(
                new SearchField("id", SearchFieldDataType.STRING)
                        .setKey(true)
                        .setFilterable(true),
                new SearchField("connectionId", SearchFieldDataType.STRING)
                        .setFilterable(true),
                new SearchField("type", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setFilterable(true)
                        .setSortable(true),       // v2: NOW sortable
                new SearchField("content", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.EN_LUCENE),
                new SearchField("naturalLanguage", SearchFieldDataType.STRING)
                        .setSearchable(true),
                new SearchField("sql", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.EN_MICROSOFT),
                new SearchField("objectName", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setFilterable(true),      // v2: NOW filterable
                new SearchField("tableName", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setFilterable(true),      // v2: NEW field
                new SearchField("description", SearchFieldDataType.STRING)
                        .setSearchable(true)
                        .setAnalyzerName(LexicalAnalyzerName.EN_LUCENE),
                new SearchField("businessTerms", SearchFieldDataType.STRING)
                        .setSearchable(true),
                new SearchField("tablesUsed", SearchFieldDataType.STRING)
                        .setFilterable(true),
                new SearchField("dbType", SearchFieldDataType.STRING)
                        .setFilterable(true),
                new SearchField("contentVector", SearchFieldDataType.collection(SearchFieldDataType.SINGLE))
                        .setSearchable(true)
                        .setVectorSearchDimensions(3072)
                        .setVectorSearchProfileName("vector-profile"),
                new SearchField("metadata", SearchFieldDataType.STRING),
                new SearchField("createdAt", SearchFieldDataType.STRING)
                        .setFilterable(true)
                        .setSortable(true),
                new SearchField("successful", SearchFieldDataType.BOOLEAN)
                        .setFilterable(true),
                new SearchField("executionTimeMs", SearchFieldDataType.INT64)
        );
    }

    private VectorSearch buildVectorSearch() {
        return new VectorSearch()
                .setProfiles(Collections.singletonList(
                        new VectorSearchProfile("vector-profile", "vector-algorithm")
                ))
                .setAlgorithms(Collections.singletonList(
                        new HnswAlgorithmConfiguration("vector-algorithm")
                ));
    }

    // ── Write operations (with dual-write support) ────────────────────────

    /**
     * Index a document with vector embedding. Dual-writes to v2 when in DUAL_WRITE phase.
     */
    public void indexDocument(TrainingDataSearchDocument document) {
        if (!azureSearchEnabled) {
            log.debug("Azure Search disabled, skipping document indexing");
            return;
        }

        runWithRetry("indexDocument", document.getId(), () -> {
            TrainingDataSearchDocument timestamped = document.toBuilder()
                    .createdAt(LocalDateTime.now().toString())
                    .build();
            activeWriteClient().mergeOrUploadDocuments(Collections.singletonList(timestamped));
            if (isDualWrite()) {
                v2Client.mergeOrUploadDocuments(Collections.singletonList(timestamped));
            }
            log.debug("Indexed document: {}", timestamped.getId());
        });
    }

    /**
     * Batch index multiple documents. Dual-writes to v2 when in DUAL_WRITE phase.
     */
    public void indexDocuments(List<TrainingDataSearchDocument> documents) {
        if (!azureSearchEnabled || documents.isEmpty()) {
            return;
        }

        runWithRetry("indexDocuments", "batch", () -> {
            String now = LocalDateTime.now().toString();
            List<TrainingDataSearchDocument> timestamped = documents.stream()
                    .map(doc -> doc.toBuilder().createdAt(now).build())
                    .toList();
            activeWriteClient().mergeOrUploadDocuments(timestamped);
            if (isDualWrite()) {
                v2Client.mergeOrUploadDocuments(timestamped);
            }
            log.info("Indexed {} documents", timestamped.size());
        });
    }

    /**
     * Delete document by ID. Dual-deletes from v2 when in DUAL_WRITE phase.
     */
    public void deleteDocument(String documentId) {
        if (!azureSearchEnabled) {
            return;
        }

        try {
            Map<String, Object> doc = new HashMap<>();
            doc.put("id", documentId);
            activeWriteClient().deleteDocuments(Collections.singletonList(doc));
            if (isDualWrite()) {
                v2Client.deleteDocuments(Collections.singletonList(doc));
            }
            log.debug("Deleted document: {}", documentId);
        } catch (Exception e) {
            log.error("Failed to delete document: {}", documentId, e);
        }
    }

    /**
     * Delete all documents for a connection. Dual-deletes from v2 when in DUAL_WRITE phase.
     */
    public void deleteConnectionDocuments(String connectionId) {
        if (!azureSearchEnabled) {
            return;
        }

        try {
            String filter = String.format("connectionId eq '%s'", escapeOData(connectionId));

            // Delete from active write target
            deleteDocumentsByFilter(activeWriteClient(), filter);

            // Also delete from v2 if dual-writing (active target is v1 in DUAL_WRITE)
            if (isDualWrite() && v2Client != null) {
                deleteDocumentsByFilter(v2Client, filter);
            }
        } catch (Exception e) {
            log.error("Failed to delete documents for connection: {}", connectionId, e);
        }
    }

    private void deleteDocumentsByFilter(SearchClient client, String filter) {
        SearchOptions searchOptions = new SearchOptions()
                .setFilter(filter)
                .setSelect("id");

        SearchPagedIterable results = client.search("*", searchOptions, Context.NONE);

        List<Map<String, Object>> docsToDelete = results.stream()
                .map(result -> result.getDocument(TrainingDataSearchDocument.class).getId())
                .filter(Objects::nonNull)
                .map(id -> {
                    Map<String, Object> doc = new HashMap<>();
                    doc.put("id", id);
                    return doc;
                })
                .collect(Collectors.toList());

        if (!docsToDelete.isEmpty()) {
            client.deleteDocuments(docsToDelete);
            log.info("Deleted {} documents via filter", docsToDelete.size());
        }
    }

    // ── Search operations ────────────────────────────────────────────────

    /**
     * Hybrid search: combines vector search and keyword search.
     * Uses activeReadClient() to read from the correct index.
     */
    public List<TrainingDataSearchDocument> hybridSearch(
            String connectionId,
            String query,
            List<Float> queryVector,
            int topK
    ) {
        if (!azureSearchEnabled) {
            return List.of();
        }

        try {
            SearchOptions searchOptions = new SearchOptions()
                    .setFilter(String.format("connectionId eq '%s'", escapeOData(connectionId)))
                    .setTop(topK)
                    .setSelect("id", "connectionId", "type", "content", "naturalLanguage",
                            "sql", "objectName", "tableName", "description", "tablesUsed",
                            "metadata", "successful", "executionTimeMs");

            if (queryVector != null && !queryVector.isEmpty()) {
                VectorizedQuery vectorQuery = new VectorizedQuery(queryVector)
                        .setKNearestNeighborsCount(topK)
                        .setFields("contentVector");
                searchOptions.setVectorSearchOptions(
                        new VectorSearchOptions().setQueries(vectorQuery));
            }

            SearchClient readClient = activeReadClient();
            SearchPagedIterable results = readClient.search(query, searchOptions, Context.NONE);

            return results.stream()
                    .map(result -> {
                        TrainingDataSearchDocument doc = result.getDocument(TrainingDataSearchDocument.class);
                        doc.setSearchScore(result.getScore());
                        return doc;
                    })
                    .limit(topK)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Hybrid search failed", e);
            return List.of();
        }
    }

    /**
     * Vector-only search for semantic similarity.
     */
    public List<TrainingDataSearchDocument> vectorSearch(
            String connectionId,
            List<Float> queryVector,
            int topK,
            String typeFilter
    ) {
        if (!azureSearchEnabled) {
            return List.of();
        }

        try {
            String filter = String.format("connectionId eq '%s'", escapeOData(connectionId));
            if (typeFilter != null && !typeFilter.isEmpty()) {
                filter += String.format(" and type eq '%s'", escapeOData(typeFilter));
            }

            VectorizedQuery vectorQuery = new VectorizedQuery(queryVector)
                    .setKNearestNeighborsCount(topK)
                    .setFields("contentVector");

            SearchOptions searchOptions = new SearchOptions()
                    .setFilter(filter)
                    .setTop(topK)
                    .setVectorSearchOptions(
                            new VectorSearchOptions().setQueries(vectorQuery));

            SearchClient readClient = activeReadClient();
            SearchPagedIterable results = readClient.search(null, searchOptions, Context.NONE);

            return results.stream()
                    .map(result -> {
                        TrainingDataSearchDocument doc = result.getDocument(TrainingDataSearchDocument.class);
                        doc.setSearchScore(result.getScore());
                        return doc;
                    })
                    .limit(topK)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Vector search failed", e);
            return List.of();
        }
    }

    /**
     * Stage 2: Filter-based retrieval by resolved table names.
     * Excludes QUERY_EXAMPLE docs (handled by Stage 3).
     * Only available when V2_ACTIVE (tableName field must exist and be filterable).
     */
    public List<TrainingDataSearchDocument> filterByTables(
            String connectionId,
            Set<QualifiedTableName> resolvedTables,
            int maxResults
    ) {
        if (!azureSearchEnabled || !isStage2Enabled() || resolvedTables.isEmpty()) {
            return List.of();
        }

        try {
            String tableFilter = buildTableFilterPredicate(resolvedTables);
            String filter = String.format(
                    "connectionId eq '%s' and (%s) and type ne 'QUERY_EXAMPLE'",
                    escapeOData(connectionId), tableFilter
            );

            SearchOptions options = new SearchOptions()
                    .setFilter(filter)
                    .setTop(maxResults)
                    .setSelect("id", "connectionId", "type", "content", "objectName",
                            "tableName", "description", "tablesUsed", "metadata",
                            "successful", "executionTimeMs");

            SearchClient readClient = activeReadClient();
            SearchPagedIterable results = readClient.search("*", options, Context.NONE);

            return results.stream()
                    .map(result -> {
                        TrainingDataSearchDocument doc = result.getDocument(TrainingDataSearchDocument.class);
                        doc.setSearchScore(result.getScore());
                        return doc;
                    })
                    .limit(maxResults)
                    .collect(Collectors.toList());

        } catch (Exception e) {
            log.error("Stage 2 filter-by-tables failed for connection {}", connectionId, e);
            return List.of();
        }
    }

    /**
     * Build OData filter predicate for the tableName field from resolved tables.
     * Adds bare-name fallback only when the bare name is unambiguous within the set
     * (i.e., no two schemas share the same bare table name).
     */
    String buildTableFilterPredicate(Set<QualifiedTableName> resolvedTables) {
        Map<String, Long> bareNameCounts = resolvedTables.stream()
                .collect(Collectors.groupingBy(
                        qt -> qt.bare().toLowerCase(Locale.ROOT), Collectors.counting()));

        List<String> predicates = new ArrayList<>();
        for (QualifiedTableName qt : resolvedTables) {
            predicates.add("tableName eq '" + escapeOData(qt.storedForm()) + "'");
            if (qt.schemaQualified() != null
                    && bareNameCounts.getOrDefault(qt.bare().toLowerCase(Locale.ROOT), 0L) == 1) {
                predicates.add("tableName eq '" + escapeOData(qt.bare()) + "'");
            }
        }
        return String.join(" or ", predicates);
    }

    // ── Table name resolution ────────────────────────────────────────────

    /**
     * Resolve the tableName field for a search document.
     * PG: strips default schema (public) but preserves non-default (e.g., sales.orders).
     * MySQL: always strips to bare name (no schema concept — any dot-prefix is database name).
     */
    public String resolveTableName(String objectName, String parentObject,
                                    String objectType, String dbType) {
        String raw = "COLUMN".equals(objectType) ? parentObject : objectName;
        if (raw == null) return null;

        int dot = raw.lastIndexOf('.');
        if (dot < 0) return raw;  // already bare

        String schema = raw.substring(0, dot);
        String table = raw.substring(dot + 1);

        String defaultSchema;
        try {
            defaultSchema = databaseProviderRegistry.getDialect(dbType)
                    .introspection().getDefaultSchema();
        } catch (Exception e) {
            log.warn("Could not resolve default schema for dbType={}, treating as bare", dbType);
            return table;
        }

        // MySQL: getDefaultSchema() returns null — always strip to bare name
        if (defaultSchema == null) return table;
        // PG: strip default schema (public), preserve non-default (sales.orders)
        return schema.equalsIgnoreCase(defaultSchema) ? table : raw;
    }

    // ── Statistics ────────────────────────────────────────────────────────

    /**
     * Get statistics for a connection.
     */
    public Map<String, Long> getConnectionStats(String connectionId) {
        if (!azureSearchEnabled) {
            return Collections.emptyMap();
        }

        try {
            Map<String, Long> stats = new HashMap<>();
            SearchClient readClient = activeReadClient();

            for (String type : Arrays.asList("SCHEMA_DDL", "QUERY_EXAMPLE", "DOCUMENTATION",
                    "COLUMN_VALUES", "RELATIONSHIP", "BUSINESS_TERM", "COMPANY_KNOWLEDGE")) {
                SearchOptions searchOptions = new SearchOptions()
                        .setFilter(String.format("connectionId eq '%s' and type eq '%s'",
                                escapeOData(connectionId), escapeOData(type)))
                        .setIncludeTotalCount(true)
                        .setTop(0);

                SearchPagedIterable results = readClient.search("*", searchOptions, Context.NONE);
                stats.put(type.toLowerCase(), results.getTotalCount());
            }

            return stats;
        } catch (Exception e) {
            log.error("Failed to get connection stats", e);
            return Collections.emptyMap();
        }
    }

    public boolean isEnabled() {
        return azureSearchEnabled;
    }

    /**
     * Get the v1 search client for backfill reads.
     */
    public SearchClient getSearchClient() {
        return searchClient;
    }

    /**
     * Get the v2 client for direct backfill operations.
     */
    public SearchClient getV2Client() {
        return v2Client;
    }

    // ── OData safety ─────────────────────────────────────────────────────

    /**
     * Escape single quotes in OData filter values to prevent injection.
     */
    static String escapeOData(String value) {
        if (value == null) return "";
        return value.replace("'", "''");
    }

    // ── Retry infrastructure ─────────────────────────────────────────────

    private void runWithRetry(String operation, String target, Runnable action) {
        int attempt = 0;
        while (true) {
            try {
                long start = System.nanoTime();
                action.run();
                meterRegistry.counter("azure.search.index.success", "operation", operation).increment();
                meterRegistry.timer("azure.search.index.duration", "operation", operation)
                    .record(Duration.ofNanos(System.nanoTime() - start));
                return;
            } catch (Exception e) {
                attempt++;
                if (attempt > maxIndexRetries) {
                    meterRegistry.counter("azure.search.index.failure", "operation", operation).increment();
                    log.error("Failed to {} for {} after {} attempts", operation, target, attempt, e);
                    return;
                }

                meterRegistry.counter("azure.search.index.retry", "operation", operation).increment();
                long backoff = baseBackoffMs * (1L << (attempt - 1));
                log.warn("Retrying {} for {} after failure (attempt {}/{})",
                        operation, target, attempt, maxIndexRetries, e);
                try {
                    Thread.sleep(backoff);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    meterRegistry.counter("azure.search.index.failure", "operation", operation).increment();
                    log.error("Retry interrupted while running {} for {}", operation, target);
                    return;
                }
            }
        }
    }
}
