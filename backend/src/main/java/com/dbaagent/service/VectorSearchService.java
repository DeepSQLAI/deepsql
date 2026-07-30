package com.dbaagent.service;

import com.dbaagent.model.QualifiedTableName;
import com.dbaagent.model.TrainingDataSearchDocument;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Abstraction over the vector/hybrid search backend used for RAG.
 *
 * <p>Two implementations are provided:
 * <ul>
 *   <li>{@link AzureSearchService}  — default; uses Azure AI Search (cloud-hosted)</li>
 *   <li>{@link PgVectorSearchService} — self-hosted; stores embeddings in the vault
 *       PostgreSQL database using the pgvector extension. Activated by setting
 *       {@code vector.store.type=pgvector} in application properties.</li>
 * </ul>
 *
 * <p>When {@code vector.store.type=pgvector}, {@link PgVectorSearchService} is
 * declared {@code @Primary} and takes precedence for all injections of this interface.
 * {@link AzureSearchService} remains in the context but operates in no-op mode
 * ({@code azure.search.enabled=false}).
 */
public interface VectorSearchService {

    // ── Write operations ──────────────────────────────────────────────────────

    /** Index (upsert) a single document with its embedding vector. */
    void indexDocument(TrainingDataSearchDocument document);

    /** Batch-index (upsert) multiple documents. */
    void indexDocuments(List<TrainingDataSearchDocument> documents);

    /** Delete a document by its ID. */
    void deleteDocument(String documentId);

    /** Delete all documents for a given connection. */
    void deleteConnectionDocuments(String connectionId);

    // ── Search operations ─────────────────────────────────────────────────────

    /**
     * Hybrid search: combines vector similarity and keyword search.
     *
     * @param connectionId scopes results to a specific DB connection
     * @param query        natural-language query string (for keyword component)
     * @param queryVector  embedding of the query (for vector component); may be null
     * @param topK         maximum number of results to return
     */
    List<TrainingDataSearchDocument> hybridSearch(
            String connectionId, String query, List<Float> queryVector, int topK);

    /**
     * Pure vector similarity search, with optional type filter.
     *
     * @param typeFilter   document type to restrict results (e.g. "SCHEMA_DDL"); may be null
     */
    List<TrainingDataSearchDocument> vectorSearch(
            String connectionId, List<Float> queryVector, int topK, String typeFilter);

    /**
     * Stage 2 filter-based retrieval: returns documents for specific resolved table names.
     * Excludes QUERY_EXAMPLE documents (handled by Stage 3).
     */
    List<TrainingDataSearchDocument> filterByTables(
            String connectionId, Set<QualifiedTableName> resolvedTables, int maxResults);

    // ── Utilities ─────────────────────────────────────────────────────────────

    /**
     * Resolve the canonical {@code tableName} field value for a search document.
     * PostgreSQL strips the default schema ({@code public}); MySQL always returns the bare name.
     */
    String resolveTableName(String objectName, String parentObject,
                            String objectType, String dbType);

    /** Return per-type document counts for a connection. */
    Map<String, Long> getConnectionStats(String connectionId);

    /** Human-readable backend label for logs and diagnostics. */
    default String backendName() {
        return "Vector search";
    }

    // ── State ─────────────────────────────────────────────────────────────────

    /** Returns true if this implementation is available and operational. */
    boolean isEnabled();

    /**
     * Returns true when Stage 2 (filter-by-table) retrieval is supported.
     * Azure implementation: requires V2_ACTIVE migration phase.
     * pgvector implementation: always true.
     */
    boolean isStage2Enabled();
}
