package com.dbaagent.service.brain.keycolumn;

import com.dbaagent.model.ColumnValueCache;
import com.dbaagent.model.KeyColumnAnalysis;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.VectorSearchService;
import com.dbaagent.service.EmbeddingService;
import com.dbaagent.repository.ColumnValueCacheRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.*;
import java.util.stream.Collectors;

/**
 * Service for collecting and caching column values, especially for low-cardinality columns.
 * These values are cached in the database for display and context.
 *
 * Note: VectorStore embedding is currently disabled as the project uses Azure AI Search
 * directly for RAG functionality. Values are still collected and cached for display
 * and can be used in chat context building.
 *
 * Features:
 * - Auto-collect during Key Column Analysis
 * - Background sampling to keep values up to date
 * - Database-based caching for UI display
 */
@Service
@Slf4j
public class ColumnValueCollectionService {

    public record ColumnValueCollectionSummary(
        int candidateColumns,
        int processedColumns,
        int cachedColumns,
        int lowCardinalityColumns,
        int embeddedColumns,
        int remainingCandidates
    ) {}

    private final ConnectionService connectionService;
    private final KeyColumnAnalysisRepository keyColumnAnalysisRepository;
    private final ColumnValueCacheRepository columnValueCacheRepository;
    private final ObjectMapper objectMapper;
    private final EmbeddingService embeddingService;
    private final VectorSearchService azureSearchService;

    public ColumnValueCollectionService(
            ConnectionService connectionService,
            KeyColumnAnalysisRepository keyColumnAnalysisRepository,
            ColumnValueCacheRepository columnValueCacheRepository,
            EmbeddingService embeddingService,
            VectorSearchService azureSearchService,
            @Nullable ObjectMapper objectMapper) {
        this.connectionService = connectionService;
        this.keyColumnAnalysisRepository = keyColumnAnalysisRepository;
        this.columnValueCacheRepository = columnValueCacheRepository;
        this.embeddingService = embeddingService;
        this.azureSearchService = azureSearchService;
        this.objectMapper = objectMapper != null ? objectMapper : new ObjectMapper();
    }

    @Value("${spring.ai.column-values.low-cardinality-threshold:100}")
    private int lowCardinalityThreshold;

    @Value("${spring.ai.column-values.sample-size:20}")
    private int sampleSize;

    @Value("${spring.ai.column-values.background-sampling.enabled:true}")
    private boolean backgroundSamplingEnabled;

    /**
     * Analyze and cache column values for a connection.
     * Called after Key Column Analysis completes.
     *
     * @param connectionId The database connection ID
     * @param request HTTP request for connection context (unused, for API compatibility)
     */
    @Value("${spring.ai.column-values.batch-size:50}")
    private int batchSize;

    @Value("${spring.ai.column-values.delay-between-columns-ms:100}")
    private int delayBetweenColumnsMs;

    @Value("${spring.ai.column-values.delay-between-embeddings-ms:500}")
    private int delayBetweenEmbeddingsMs;

    @Transactional
    public ColumnValueCollectionSummary analyzeColumnValues(String connectionId, HttpServletRequest request) {
        log.info("Starting column value collection for connection: {}", connectionId);

        try {
            // Use the background job method which handles credential decryption internally
            JdbcTemplate jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
            String dbType = connectionService.getDbType(connectionId);

            // Get columns that might be low-cardinality from key_column_analysis
            List<KeyColumnAnalysis> potentialColumns = keyColumnAnalysisRepository
                .findByConnectionIdAndDistinctCountLessThan(connectionId, (long) lowCardinalityThreshold);

            log.info("Found {} potential low-cardinality columns to analyze (processing max {} per batch)",
                potentialColumns.size(), batchSize);

            // Limit to batch size to prevent CPU spikes
            List<KeyColumnAnalysis> columnsToProcess = potentialColumns.stream()
                .limit(batchSize)
                .toList();

            int collected = 0;
            int embedded = 0;
            int processed = 0;

            for (KeyColumnAnalysis column : columnsToProcess) {
                try {
                    processed++;
                    if (processed % 10 == 0) {
                        log.info("Processing column {}/{}: {}.{}",
                            processed, columnsToProcess.size(),
                            column.getTableName(), column.getColumnName());
                    }

                    ColumnValueCache cache = collectAndCacheValues(
                        jdbc, connectionId, column.getTableName(),
                        column.getColumnName(), column.getDistinctCount(), dbType
                    );

                    if (cache != null) {
                        collected++;

                        // Add delay between database queries to prevent CPU spikes
                        if (delayBetweenColumnsMs > 0) {
                            Thread.sleep(delayBetweenColumnsMs);
                        }

                        if (cache.getIsLowCardinality() && embedColumnValues(cache)) {
                            embedded++;
                            // Add longer delay between Azure OpenAI calls to prevent rate limiting
                            if (delayBetweenEmbeddingsMs > 0) {
                                Thread.sleep(delayBetweenEmbeddingsMs);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Column value collection interrupted for {}", connectionId);
                    break;
                } catch (Exception e) {
                    log.warn("Failed to collect values for {}.{}: {}",
                        column.getTableName(), column.getColumnName(), e.getMessage());
                }
            }

            log.info("Column value collection complete for {}: {} collected, {} embedded (processed {}/{})",
                connectionId, collected, embedded, processed, potentialColumns.size());

            if (potentialColumns.size() > batchSize) {
                log.info("Note: {} columns remain to be processed in future runs",
                    potentialColumns.size() - batchSize);
            }

            long lowCardinalityColumns = columnValueCacheRepository.countByConnectionIdAndIsLowCardinalityTrue(connectionId);
            int remainingCandidates = Math.max(0, potentialColumns.size() - processed);

            return new ColumnValueCollectionSummary(
                potentialColumns.size(),
                processed,
                collected,
                Math.toIntExact(lowCardinalityColumns),
                embedded,
                remainingCandidates
            );

        } catch (Exception e) {
            log.error("Error during column value collection for {}: {}", connectionId, e.getMessage(), e);
            throw new RuntimeException("Column value collection failed for " + connectionId, e);
        }
    }

    /**
     * Collect distinct values for a specific column.
     *
     * @param jdbc JdbcTemplate for the target database
     * @param connectionId Connection ID
     * @param tableName Table name
     * @param columnName Column name
     * @param knownDistinctCount Known distinct count (may be null)
     * @param dbType Database type (postgresql, mysql)
     * @return ColumnValueCache entity or null if collection failed
     */
    @Transactional
    public ColumnValueCache collectAndCacheValues(
            JdbcTemplate jdbc,
            String connectionId,
            String tableName,
            String columnName,
            Long knownDistinctCount,
            String dbType) {

        try {
            // Normalize names to lowercase
            String normalizedTable = tableName.toLowerCase();
            String normalizedColumn = columnName.toLowerCase();

            // Build query for distinct values
            String sql = buildDistinctValuesQuery(normalizedTable, normalizedColumn, dbType);

            List<String> values = jdbc.queryForList(sql, String.class);

            // Filter out nulls and empty strings
            values = values.stream()
                .filter(v -> v != null && !v.trim().isEmpty())
                .distinct()
                .sorted()
                .collect(Collectors.toList());

            long distinctCount = values.size();
            boolean isLowCardinality = distinctCount <= lowCardinalityThreshold;

            // Find or create cache entry
            ColumnValueCache cache = columnValueCacheRepository
                .findByConnectionIdAndTableNameAndColumnName(connectionId, normalizedTable, normalizedColumn)
                .orElse(ColumnValueCache.builder()
                    .connectionId(connectionId)
                    .tableName(normalizedTable)
                    .columnName(normalizedColumn)
                    .build());

            cache.setDistinctCount(distinctCount);
            cache.setIsLowCardinality(isLowCardinality);
            cache.setAnalyzedAt(LocalDateTime.now());

            // Store sample values (first N)
            List<String> sample = values.subList(0, Math.min(sampleSize, values.size()));
            cache.setSampleValues(toJson(sample));

            // Store all values only if low cardinality
            if (isLowCardinality) {
                cache.setAllValues(toJson(values));
            } else {
                cache.setAllValues(null);
            }

            // Mark as needing re-embedding if values changed
            String oldValues = cache.getAllValues();
            String newValues = isLowCardinality ? toJson(values) : null;
            if (!Objects.equals(oldValues, newValues)) {
                cache.setEmbedded(false);
            }

            return columnValueCacheRepository.save(cache);

        } catch (Exception e) {
            log.warn("Failed to collect values for {}.{}: {}", tableName, columnName, e.getMessage());
            return null;
        }
    }

    /**
     * Embed column values into Azure AI Search for RAG retrieval.
     * Creates a searchable document with the column's valid values to help
     * the chat agent generate accurate SQL filters.
     *
     * @param cache ColumnValueCache to embed
     * @return true if embedding succeeded
     */
    public boolean embedColumnValues(ColumnValueCache cache) {
        if (!cache.getIsLowCardinality() || cache.getAllValues() == null) {
            return false;
        }

        // Skip if Azure Search is not enabled
        if (!azureSearchService.isEnabled()) {
            log.debug("Azure Search disabled, skipping embedding for {}.{}",
                cache.getTableName(), cache.getColumnName());
            cache.setEmbedded(true);
            cache.setEmbeddedAt(LocalDateTime.now());
            columnValueCacheRepository.save(cache);
            return true;
        }

        try {
            // Parse the values from JSON
            List<String> values = parseJsonArray(cache.getAllValues());
            if (values.isEmpty()) {
                log.debug("No values to embed for {}.{}", cache.getTableName(), cache.getColumnName());
                return false;
            }

            // Build embedding text with context for better semantic search
            String embeddingText = buildEmbeddingText(cache, values);

            // Create embedding using Azure OpenAI
            List<Double> embedding = embeddingService.createEmbedding(embeddingText);
            if (embedding.isEmpty()) {
                log.warn("Failed to create embedding for {}.{}", cache.getTableName(), cache.getColumnName());
                return false;
            }

            // Convert to Float list for Azure Search
            List<Float> contentVector = embedding.stream()
                .map(Double::floatValue)
                .collect(Collectors.toList());

            // Build document ID (unique per connection + table + column)
            String documentId = buildDocumentId(cache.getConnectionId(), cache.getTableName(), cache.getColumnName());

            // Build metadata JSON
            Map<String, Object> metadata = buildMetadata(cache, values);

            // Create the search document
            TrainingDataSearchDocument document = TrainingDataSearchDocument.builder()
                .id(documentId)
                .connectionId(cache.getConnectionId())
                .type("COLUMN_VALUES")
                .content(embeddingText)
                .objectName(cache.getTableName() + "." + cache.getColumnName())
                .description(String.format("Valid values for column %s.%s (%d distinct values)",
                    cache.getTableName(), cache.getColumnName(), values.size()))
                .tablesUsed(cache.getTableName())
                .contentVector(contentVector)
                .metadata(toJson(metadata))
                .build();

            // Index in Azure Search
            azureSearchService.indexDocument(document);

            // Update cache with embedding info
            cache.setEmbedded(true);
            cache.setEmbeddedAt(LocalDateTime.now());
            cache.setEmbeddingId(documentId);
            columnValueCacheRepository.save(cache);

            log.info("Embedded column values for {}.{} ({} values) - document ID: {}",
                cache.getTableName(), cache.getColumnName(), values.size(), documentId);

            return true;

        } catch (Exception e) {
            log.error("Failed to embed column values for {}.{}: {}",
                cache.getTableName(), cache.getColumnName(), e.getMessage(), e);
            return false;
        }
    }

    /**
     * Build the text to be embedded for semantic search.
     * Structured to help match queries about column values and filtering.
     */
    private String buildEmbeddingText(ColumnValueCache cache, List<String> values) {
        StringBuilder sb = new StringBuilder();

        sb.append("Table: ").append(cache.getTableName());
        sb.append(", Column: ").append(cache.getColumnName());

        if (cache.getDataType() != null) {
            sb.append(", Type: ").append(cache.getDataType());
        }

        sb.append("\n");
        sb.append("Valid values: ").append(String.join(", ", values));
        sb.append("\n");
        sb.append("Use these exact values when filtering on ");
        sb.append(cache.getTableName()).append(".").append(cache.getColumnName());
        sb.append(" in WHERE clauses or JOIN conditions.");

        // Add contextual hints for common query patterns
        sb.append("\n");
        sb.append("Filter examples: ");
        sb.append(cache.getTableName()).append(".").append(cache.getColumnName());
        sb.append(" = '").append(values.get(0)).append("'");
        if (values.size() > 1) {
            sb.append(" or ");
            sb.append(cache.getTableName()).append(".").append(cache.getColumnName());
            sb.append(" IN (");
            sb.append(values.stream().limit(3).map(v -> "'" + v + "'").collect(Collectors.joining(", ")));
            if (values.size() > 3) {
                sb.append(", ...");
            }
            sb.append(")");
        }

        return sb.toString();
    }

    /**
     * Build unique document ID for Azure Search.
     */
    private String buildDocumentId(String connectionId, String tableName, String columnName) {
        return String.format("%s::COLUMN_VALUES::%s::%s", connectionId, tableName, columnName);
    }

    /**
     * Build metadata map for the search document.
     */
    private Map<String, Object> buildMetadata(ColumnValueCache cache, List<String> values) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("tableName", cache.getTableName());
        metadata.put("columnName", cache.getColumnName());
        metadata.put("dataType", cache.getDataType());
        metadata.put("distinctCount", values.size());
        metadata.put("values", values);
        metadata.put("isLowCardinality", true);
        metadata.put("analyzedAt", cache.getAnalyzedAt() != null ? cache.getAnalyzedAt().toString() : null);
        return metadata;
    }

    /**
     * Parse JSON array string to list.
     */
    private List<String> parseJsonArray(String json) {
        try {
            if (json == null || json.isEmpty()) {
                return Collections.emptyList();
            }
            return objectMapper.readValue(json, new TypeReference<List<String>>() {});
        } catch (JsonProcessingException e) {
            log.warn("Failed to parse JSON array: {}", e.getMessage());
            return Collections.emptyList();
        }
    }

    /**
     * Build query to get distinct values for a column.
     */
    private String buildDistinctValuesQuery(String tableName, String columnName, String dbType) {
        // Quote identifiers appropriately for the database type
        String quotedTable = quoteIdentifier(tableName, dbType);
        String quotedColumn = quoteIdentifier(columnName, dbType);

        return String.format(
            "SELECT DISTINCT %s FROM %s WHERE %s IS NOT NULL ORDER BY %s LIMIT %d",
            quotedColumn, quotedTable, quotedColumn, quotedColumn, lowCardinalityThreshold + 1
        );
    }

    /**
     * Quote identifier based on database type.
     */
    private String quoteIdentifier(String identifier, String dbType) {
        if (dbType != null && dbType.toLowerCase().contains("mysql")) {
            return "`" + identifier + "`";
        }
        // PostgreSQL and others use double quotes
        return "\"" + identifier + "\"";
    }

    /**
     * Convert object to JSON string.
     */
    private String toJson(Object obj) {
        try {
            return objectMapper.writeValueAsString(obj);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialize to JSON: {}", e.getMessage());
            return "[]";
        }
    }

    /**
     * Background job to refresh column values.
     * Runs at 3 AM daily (configurable).
     */
    public void backgroundRefreshColumnValues() {
        if (!backgroundSamplingEnabled) {
            log.debug("Background column value sampling is disabled");
            return;
        }

        log.info("Starting background column value refresh");

        try {
            // Find columns that need re-analysis (analyzed more than 7 days ago)
            LocalDateTime staleThreshold = LocalDateTime.now().minusDays(7);

            // Get all connections with cached columns
            List<String> connectionIds = columnValueCacheRepository.findAll().stream()
                .map(ColumnValueCache::getConnectionId)
                .distinct()
                .collect(Collectors.toList());

            for (String connectionId : connectionIds) {
                try {
                    List<ColumnValueCache> staleColumns = columnValueCacheRepository
                        .findStaleColumns(connectionId, staleThreshold);

                    if (!staleColumns.isEmpty()) {
                        log.info("Refreshing {} stale columns for connection {}",
                            staleColumns.size(), connectionId);

                        // Note: Background refresh doesn't have HttpServletRequest
                        // So we use a method that doesn't require it
                        refreshColumnsWithoutRequest(connectionId, staleColumns);
                    }
                } catch (Exception e) {
                    log.warn("Failed to refresh columns for connection {}: {}",
                        connectionId, e.getMessage());
                }
            }

            log.info("Background column value refresh complete");

        } catch (Exception e) {
            log.error("Error during background column value refresh", e);
        }
    }

    /**
     * Refresh column values without HTTP request context.
     * Used by background scheduler.
     */
    private void refreshColumnsWithoutRequest(String connectionId, List<ColumnValueCache> columns) {
        try {
            // Get connection info without HTTP request
            JdbcTemplate jdbc = connectionService.getJdbcTemplateForBackgroundJob(connectionId);
            String dbType = connectionService.getDbType(connectionId);

            // Limit to batch size to prevent CPU spikes
            List<ColumnValueCache> toProcess = columns.stream()
                .limit(batchSize)
                .toList();

            int processed = 0;
            int refreshed = 0;

            for (ColumnValueCache column : toProcess) {
                try {
                    processed++;
                    ColumnValueCache updated = collectAndCacheValues(
                        jdbc, connectionId, column.getTableName(),
                        column.getColumnName(), column.getDistinctCount(), dbType
                    );

                    // Add delay between database queries
                    if (delayBetweenColumnsMs > 0) {
                        Thread.sleep(delayBetweenColumnsMs);
                    }

                    if (updated != null && updated.getIsLowCardinality()) {
                        if (embedColumnValues(updated)) {
                            refreshed++;
                            // Add delay between embedding calls
                            if (delayBetweenEmbeddingsMs > 0) {
                                Thread.sleep(delayBetweenEmbeddingsMs);
                            }
                        }
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    log.warn("Background refresh interrupted for {}", connectionId);
                    break;
                } catch (Exception e) {
                    log.debug("Failed to refresh {}.{}: {}",
                        column.getTableName(), column.getColumnName(), e.getMessage());
                }
            }

            log.info("Background refresh for {}: processed {}/{}, refreshed {}",
                connectionId, processed, columns.size(), refreshed);

        } catch (Exception e) {
            log.warn("Failed to get JDBC connection for background refresh: {}", e.getMessage());
        }
    }

    /**
     * Embed all unembedded column values.
     * Called on startup or manually to ensure all values are in vector store.
     */
    @Transactional
    public int embedAllUnembedded() {
        List<ColumnValueCache> unembedded = columnValueCacheRepository.findByEmbeddedFalseOrderByCreatedAtAsc();

        log.info("Found {} unembedded column values (processing max {} per batch)",
            unembedded.size(), batchSize);

        // Limit to batch size to prevent CPU spikes
        List<ColumnValueCache> toProcess = unembedded.stream()
            .limit(batchSize)
            .toList();

        int count = 0;
        int processed = 0;

        for (ColumnValueCache cache : toProcess) {
            try {
                processed++;
                if (processed % 10 == 0) {
                    log.info("Embedding {}/{}: {}.{}",
                        processed, toProcess.size(),
                        cache.getTableName(), cache.getColumnName());
                }

                if (cache.getIsLowCardinality() && embedColumnValues(cache)) {
                    count++;
                    // Add delay between Azure OpenAI calls to prevent rate limiting and CPU spikes
                    if (delayBetweenEmbeddingsMs > 0) {
                        Thread.sleep(delayBetweenEmbeddingsMs);
                    }
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Embedding interrupted");
                break;
            } catch (Exception e) {
                log.warn("Failed to embed {}.{}: {}",
                    cache.getTableName(), cache.getColumnName(), e.getMessage());
            }
        }

        log.info("Embedded {} unembedded column value sets (processed {}/{})",
            count, processed, unembedded.size());

        if (unembedded.size() > batchSize) {
            log.info("Note: {} columns remain to be embedded in future runs",
                unembedded.size() - batchSize);
        }

        return count;
    }

    /**
     * Get cached column values for display.
     *
     * @param connectionId Connection ID
     * @param tableName Optional table name filter
     * @return List of cached columns
     */
    public List<ColumnValueCache> getCachedColumns(String connectionId, String tableName) {
        if (tableName != null && !tableName.isEmpty()) {
            return columnValueCacheRepository
                .findByConnectionIdAndTableNameOrderByColumnNameAsc(connectionId, tableName.toLowerCase());
        }
        return columnValueCacheRepository
            .findByConnectionIdOrderByTableNameAscColumnNameAsc(connectionId);
    }

    /**
     * Get statistics about cached column values.
     */
    public Map<String, Object> getStatistics(String connectionId) {
        Map<String, Object> stats = new HashMap<>();

        long totalCached = columnValueCacheRepository
            .findByConnectionIdOrderByTableNameAscColumnNameAsc(connectionId).size();
        long lowCardinality = columnValueCacheRepository
            .countByConnectionIdAndIsLowCardinalityTrue(connectionId);
        long embedded = columnValueCacheRepository
            .countByConnectionIdAndEmbeddedTrue(connectionId);

        stats.put("totalCached", totalCached);
        stats.put("lowCardinalityCount", lowCardinality);
        stats.put("embeddedCount", embedded);
        stats.put("threshold", lowCardinalityThreshold);

        return stats;
    }
}
