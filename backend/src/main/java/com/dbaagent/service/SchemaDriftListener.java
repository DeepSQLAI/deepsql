package com.dbaagent.service;

import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.repository.SchemaDocumentationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SchemaDriftListener {

    private final SchemaDocumentationRepository schemaDocRepo;
    private final SchemaDescriptionService schemaDescriptionService;
    private final TrainingService trainingService;

    /**
     * Called when BrainService detects schema drift with new tables.
     * Note: sampleTablesAdded is capped at 5 items (DRIFT_SAMPLE_LIMIT).
     *
     * Uses canonicalizeName() to match drift names against stored
     * objectNames in canonical form. This avoids both false negatives
     * and false positives when multiple schemas share table names.
     */
    public void onTablesAdded(String connectionId, List<String> sampleNewTableNames) {
        if (sampleNewTableNames == null || sampleNewTableNames.isEmpty()) return;

        var existingDocs = schemaDocRepo.findByConnectionId(connectionId);
        // Documented set: stored objectNames are already in canonical form
        // (plain for public/dbo, schema-qualified for others — from buildObjectName())
        Set<String> documented = existingDocs.stream()
            .filter(d -> d.getObjectType() == SchemaDocumentation.DocumentationType.TABLE)
            .map(d -> d.getObjectName().toLowerCase(Locale.ROOT))
            .collect(Collectors.toSet());

        // Canonicalize drift names to same form as stored objectNames
        // "public.users" → "users", "sales.users" → "sales.users"
        List<String> undocumented = sampleNewTableNames.stream()
            .map(SchemaDriftListener::canonicalizeName)
            .filter(t -> !documented.contains(t.toLowerCase(Locale.ROOT)))
            .toList();

        if (undocumented.isEmpty()) return;

        log.info("Generating AI descriptions for {} new tables in connection {}",
            undocumented.size(), connectionId);
        // Pass canonical names — SchemaDescriptionService.generateDescriptions
        // matches them using buildObjectName(table) for consistent resolution
        Thread.startVirtualThread(() -> {
            try {
                schemaDescriptionService.generateDescriptions(connectionId, undocumented);
            } catch (Exception e) {
                log.error("Failed to generate AI descriptions for drift in {}: {}",
                    connectionId, e.getMessage(), e);
            }
        });
    }

    /**
     * Called when tables are removed from schema.
     * Deletes AI-generated docs for the table AND its columns.
     *
     * Uses canonicalizeName() for precise single-name lookup
     * instead of trying both variants (which caused cross-schema over-deletion).
     */
    public void onTablesRemoved(String connectionId, List<String> sampleRemovedTableNames) {
        if (sampleRemovedTableNames == null || sampleRemovedTableNames.isEmpty()) return;

        for (String qualifiedName : sampleRemovedTableNames) {
            // Canonical name matches exactly what buildObjectName() stored
            String canonical = canonicalizeName(qualifiedName);

            // Delete table-level doc (if AI-generated). Now also cascade
            // the embedding so the RAG store doesn't keep returning hits
            // for a table that no longer exists.
            // Iterate: the logical key was not unique before V116, so a legacy
            // install can hold more than one row here and an Optional finder
            // would throw instead of cleaning either of them up.
            schemaDocRepo.findByConnectionIdAndObjectTypeAndObjectName(
                connectionId, SchemaDocumentation.DocumentationType.TABLE, canonical)
                .stream()
                .filter(doc -> doc.getSource() == DocumentationSource.AI_GENERATED)
                .forEach(doc -> deleteWithEmbedding(doc, "dropped table " + canonical));

            // Delete column-level docs for this table (if AI-generated)
            var columnDocs = schemaDocRepo.findByConnectionIdAndParentObject(connectionId, canonical);
            columnDocs.stream()
                .filter(d -> d.getSource() == DocumentationSource.AI_GENERATED)
                .forEach(doc -> deleteWithEmbedding(doc, "column of dropped table " + canonical));
        }
    }

    /**
     * Called when {@link SchemaChangeTrackingService} detects new columns on
     * existing tables. Mirrors the {@link #onTablesAdded} pattern: groups by
     * parent table and queues an AI re-doc for that table on a virtual
     * thread. The existing tableFilter path in
     * {@code SchemaDescriptionService.generateDescriptions} upserts column
     * rows in place, so the new columns get descriptions and existing
     * AI-generated columns are refreshed against the latest schema. The
     * Unresolved panel surfaces the new columns automatically (via
     * {@code SchemaAmbiguityService.MISSING_COLUMN_DESCRIPTION}) until the
     * regen lands.
     */
    public void onColumnsAdded(String connectionId, List<ColumnRef> addedColumns) {
        if (addedColumns == null || addedColumns.isEmpty()) return;

        // Group by parent table; AI regen is table-driven for now (simpler,
        // and a column-targeted variant is a follow-up).
        Set<String> tablesToRegen = addedColumns.stream()
            .map(ColumnRef::tableName)
            .filter(Objects::nonNull)
            .map(SchemaDriftListener::canonicalizeName)
            .collect(Collectors.toCollection(LinkedHashSet::new));

        log.info("Queueing AI doc regen for {} table(s) due to {} new column(s) in connection {}",
            tablesToRegen.size(), addedColumns.size(), connectionId);
        Thread.startVirtualThread(() -> {
            try {
                schemaDescriptionService.generateDescriptions(connectionId, new ArrayList<>(tablesToRegen));
            } catch (Exception e) {
                log.error("Failed AI regen for new columns in {}: {}", connectionId, e.getMessage(), e);
            }
        });
    }

    /**
     * Called when {@link SchemaChangeTrackingService} detects a column was
     * removed. Deletes the matching CODE_DERIVED and AI_GENERATED doc rows
     * for that column AND cleans up the corresponding embedding so the RAG
     * store doesn't keep returning hits for a column that no longer exists.
     * USER-curated rows are preserved (the user may want to keep the note as
     * historical context).
     */
    public void onColumnsRemoved(String connectionId, List<ColumnRef> removedColumns) {
        if (removedColumns == null || removedColumns.isEmpty()) return;
        for (ColumnRef ref : removedColumns) {
            String table = canonicalizeName(ref.tableName());
            String column = ref.columnName();
            if (table == null || column == null) continue;

            var docs = schemaDocRepo.findByConnectionIdAndParentObject(connectionId, table).stream()
                .filter(d -> column.equalsIgnoreCase(d.getObjectName()))
                .filter(d -> d.getSource() == DocumentationSource.CODE_DERIVED
                          || d.getSource() == DocumentationSource.AI_GENERATED)
                .toList();
            for (SchemaDocumentation d : docs) {
                deleteWithEmbedding(d, "dropped column " + table + "." + column);
            }
        }
    }

    /**
     * Called when {@link SchemaChangeTrackingService} detects a column was
     * modified (type / nullability change). We don't auto-rewrite the doc
     * because the column still exists and the description may still be
     * accurate; the existing {@code BrainNoteResponse.stale} flag (computed
     * from snapshot timestamps) will surface the change in the editor and
     * the user decides whether to refresh.
     */
    public void onColumnsModified(String connectionId, List<ColumnRef> modifiedColumns) {
        if (modifiedColumns == null || modifiedColumns.isEmpty()) return;
        log.info("Schema change: {} column(s) modified on connection {} — staleness "
                + "will surface via the next snapshot comparison; no doc regen forced.",
            modifiedColumns.size(), connectionId);
    }

    private void deleteWithEmbedding(SchemaDocumentation doc, String reason) {
        try {
            trainingService.deleteDocumentationEmbedding(doc.getConnectionId(), doc.getId());
        } catch (Exception e) {
            log.warn("Embedding delete failed for doc {} ({}): {}", doc.getId(), reason, e.getMessage());
        }
        schemaDocRepo.delete(doc);
        log.info("Removed schema doc {} ({}) [{}]", doc.getId(), reason, doc.getSource());
    }

    /** Pair of (table, column) for column-level drift hooks. */
    public record ColumnRef(String tableName, String columnName) {}

    /**
     * Canonicalize a schema-qualified drift name to match the objectName
     * storage convention (mirrors SchemaDescriptionService.buildObjectName):
     *   - Default schemas (public, dbo) → strip prefix: "public.users" → "users"
     *   - Non-default schemas → keep qualified: "sales.users" → "sales.users"
     *   - No schema prefix → return as-is: "users" → "users"
     */
    static String canonicalizeName(String qualifiedName) {
        if (qualifiedName == null) return null;
        int dot = qualifiedName.lastIndexOf('.');
        if (dot < 0) return qualifiedName;
        String schema = qualifiedName.substring(0, dot);
        String name = qualifiedName.substring(dot + 1);
        if ("public".equalsIgnoreCase(schema) || "dbo".equalsIgnoreCase(schema)) {
            return name;  // strip default schema
        }
        return qualifiedName;  // keep non-default schema
    }
}
