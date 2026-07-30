package com.dbaagent.service;

import com.azure.core.util.Context;
import com.azure.search.documents.SearchClient;
import com.azure.search.documents.models.SearchOptions;
import com.azure.search.documents.util.SearchPagedIterable;
import com.dbaagent.model.TrainingDataSearchDocument;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Orchestrates background backfill from v1 to v2 Azure Search index.
 * Enriches existing documents with the new {@code tableName} field
 * by resolving table names via {@link AzureSearchService#resolveTableName}.
 *
 * <p>Lifecycle: Called once by admin endpoint POST /api/training/backfill-v2.
 * Creates v2 index, reads all v1 docs, enriches, writes to v2,
 * runs parity check, then activates v2 (enabling Stage 2 retrieval).</p>
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class IndexBackfillService {

    private static final int BATCH_SIZE = 1000;
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {};

    /**
     * All 16 fields to retrieve from v1 for full document copy.
     */
    private static final String[] ALL_FIELDS = {
            "id", "connectionId", "type", "content", "naturalLanguage", "sql",
            "objectName", "tableName", "description", "businessTerms", "tablesUsed",
            "dbType", "contentVector", "metadata", "createdAt", "successful", "executionTimeMs"
    };

    private final AzureSearchService azureSearchService;
    private final ObjectMapper objectMapper;

    private final java.util.concurrent.atomic.AtomicReference<BackfillProgress> progressRef =
            new java.util.concurrent.atomic.AtomicReference<>(BackfillProgress.IDLE);

    public enum BackfillStatus {
        IDLE, RUNNING, PARITY_CHECK, COMPLETED, FAILED
    }

    public record BackfillProgress(
            BackfillStatus status,
            int processedDocuments,
            int totalDocuments,
            boolean parityPassed,
            String message
    ) {
        static final BackfillProgress IDLE = new BackfillProgress(
                BackfillStatus.IDLE, 0, 0, false, null);
    }

    /**
     * Start the v1→v2 backfill on a virtual thread.
     * Returns immediately with current status.
     */
    public BackfillProgress startBackfill() {
        if (!azureSearchService.isEnabled()) {
            var failed = new BackfillProgress(
                    BackfillStatus.FAILED, 0, 0, false, "Azure Search is disabled");
            progressRef.set(failed);
            return failed;
        }

        if (azureSearchService.getMigrationPhase() == AzureSearchService.MigrationPhase.V2_ACTIVE) {
            var failed = new BackfillProgress(
                    BackfillStatus.FAILED, 0, 0, false, "V2 is already active — backfill not needed");
            progressRef.set(failed);
            return failed;
        }

        BackfillProgress current = progressRef.get();
        if (current.status() == BackfillStatus.RUNNING
                || current.status() == BackfillStatus.PARITY_CHECK) {
            return current;
        }

        BackfillProgress starting = new BackfillProgress(BackfillStatus.RUNNING, 0, 0, false, "Starting backfill");
        if (!progressRef.compareAndSet(current, starting)) {
            return progressRef.get(); // another thread won the race
        }
        Thread.startVirtualThread(this::runBackfill);
        return starting;
    }

    public BackfillProgress getProgress() {
        return progressRef.get();
    }

    /**
     * Synchronous backfill for testing. Production code uses {@link #startBackfill()}.
     */
    void runBackfillSync() {
        runBackfill();
    }

    private void runBackfill() {
        try {
            log.info("Starting v1→v2 index backfill");

            // Step 1: Create v2 index (sets MigrationPhase to DUAL_WRITE)
            azureSearchService.createV2Index();
            log.info("V2 index created, phase set to DUAL_WRITE");

            // Step 2: Read all v1 docs
            SearchClient v1Client = azureSearchService.getSearchClient();
            List<TrainingDataSearchDocument> v1Docs = readAllDocuments(v1Client);
            int totalDocs = v1Docs.size();
            progressRef.set(new BackfillProgress(
                    BackfillStatus.RUNNING, 0, totalDocs, false,
                    "Read " + totalDocs + " documents from v1"));
            log.info("Read {} documents from v1 index", totalDocs);

            // Step 3: Enrich with tableName and write to v2 in batches
            SearchClient v2Client = azureSearchService.getV2Client();
            int processed = 0;

            for (int i = 0; i < v1Docs.size(); i += BATCH_SIZE) {
                int end = Math.min(i + BATCH_SIZE, v1Docs.size());
                List<TrainingDataSearchDocument> batch = v1Docs.subList(i, end);

                List<TrainingDataSearchDocument> enriched = batch.stream()
                        .map(this::enrichWithTableName)
                        .toList();

                v2Client.mergeOrUploadDocuments(enriched);
                processed += enriched.size();

                progressRef.set(new BackfillProgress(
                        BackfillStatus.RUNNING, processed, totalDocs, false,
                        "Processed " + processed + "/" + totalDocs + " documents"));
                log.info("Backfill progress: {}/{}", processed, totalDocs);
            }

            // Step 4: Parity check
            progressRef.set(new BackfillProgress(
                    BackfillStatus.PARITY_CHECK, processed, totalDocs, false,
                    "Running parity check"));
            boolean parity = runParityCheck(v1Client, v2Client);

            if (parity) {
                // Step 5: Activate v2
                azureSearchService.activateV2();
                progressRef.set(new BackfillProgress(
                        BackfillStatus.COMPLETED, processed, totalDocs, true,
                        "Backfill complete — V2 active, Stage 2 enabled"));
                log.info("Backfill complete. V2 activated. {} documents migrated.", processed);
            } else {
                progressRef.set(new BackfillProgress(
                        BackfillStatus.COMPLETED, processed, totalDocs, false,
                        "Backfill complete but parity check failed — staying in DUAL_WRITE"));
                log.warn("Backfill complete but parity check failed. Staying in DUAL_WRITE.");
            }

        } catch (Exception e) {
            log.error("Backfill failed", e);
            progressRef.set(new BackfillProgress(
                    BackfillStatus.FAILED, progressRef.get().processedDocuments(), progressRef.get().totalDocuments(),
                    false, "Backfill failed: " + e.getMessage()));
        }
    }

    /**
     * Resolve the tableName for a document based on its type and metadata.
     * Package-private for testing.
     *
     * @return resolved tableName, or null for QUERY_EXAMPLE docs
     */
    String resolveTableNameForDocument(TrainingDataSearchDocument doc) {
        String type = doc.getType();

        // QUERY_EXAMPLE docs have no tableName (excluded from Stage 2)
        if ("QUERY_EXAMPLE".equals(type)) {
            return null;
        }

        // DOCUMENTATION docs need metadata parsing for column-level docs
        if ("DOCUMENTATION".equals(type)) {
            return resolveTableNameFromDocumentation(doc);
        }

        // All other types: SCHEMA_DDL, COLUMN_VALUES, RELATIONSHIP, BUSINESS_TERM
        return azureSearchService.resolveTableName(
                doc.getObjectName(), null, type, doc.getDbType());
    }

    private String resolveTableNameFromDocumentation(TrainingDataSearchDocument doc) {
        String objectType = null;
        String parentObject = null;

        // Try to extract objectType and parentObject from metadata JSON
        if (doc.getMetadata() != null && !doc.getMetadata().isBlank()) {
            try {
                Map<String, Object> metadata = objectMapper.readValue(doc.getMetadata(), MAP_TYPE);
                objectType = (String) metadata.get("objectType");
                parentObject = (String) metadata.get("parentObject");
            } catch (Exception e) {
                log.debug("Could not parse metadata for doc {}, falling back to objectName", doc.getId());
            }
        }

        // For column docs, pass parentObject; for table docs, pass null
        if ("COLUMN".equals(objectType)) {
            return azureSearchService.resolveTableName(
                    doc.getObjectName(), parentObject, "COLUMN", doc.getDbType());
        }

        // Table-level or unknown: use objectType from metadata if available, else doc type
        String resolvedType = objectType != null ? objectType : "DOCUMENTATION";
        return azureSearchService.resolveTableName(
                doc.getObjectName(), null, resolvedType, doc.getDbType());
    }

    private TrainingDataSearchDocument enrichWithTableName(TrainingDataSearchDocument doc) {
        return doc.toBuilder()
                .tableName(resolveTableNameForDocument(doc))
                .build();
    }

    private List<TrainingDataSearchDocument> readAllDocuments(SearchClient client) {
        List<TrainingDataSearchDocument> allDocs = new ArrayList<>();
        int skip = 0;

        while (true) {
            SearchOptions options = new SearchOptions()
                    .setTop(BATCH_SIZE)
                    .setSkip(skip)
                    .setOrderBy("id")
                    .setSelect(ALL_FIELDS)
                    .setIncludeTotalCount(true);

            SearchPagedIterable results = client.search("*", options, Context.NONE);
            List<TrainingDataSearchDocument> page = results.stream()
                    .map(result -> result.getDocument(TrainingDataSearchDocument.class))
                    .toList();

            allDocs.addAll(page);
            log.info("Read page at skip={}: {} docs (total so far: {})", skip, page.size(), allDocs.size());

            if (page.size() < BATCH_SIZE) {
                break;
            }
            skip += BATCH_SIZE;
        }

        return allDocs;
    }

    /**
     * Compare document counts between v1 and v2 indices.
     * Returns true if v2 count matches or exceeds v1 count.
     */
    private boolean runParityCheck(SearchClient v1Client, SearchClient v2Client) {
        try {
            long v1Count = countDocuments(v1Client);
            long v2Count = countDocuments(v2Client);
            boolean pass = v2Count >= v1Count;
            log.info("Parity check: v1={}, v2={}, pass={}", v1Count, v2Count, pass);
            return pass;
        } catch (Exception e) {
            log.warn("Parity check failed with error", e);
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
}
