package com.dbaagent.service;

import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.repository.CodeKnowledgeSuggestionRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;

/**
 * Collapses duplicate {@link SchemaDocumentation} rows that share a logical key.
 *
 * <p>{@code schema_documentation} carried no unique constraint on
 * {@code (connection_id, object_type, object_name, parent_object, source)} until
 * {@code V116__dedupe_schema_documentation.sql}, and
 * {@link com.dbaagent.service.codescan.CodeSuggestionApplier#approve} took no row
 * lock — so a bulk approve submitted twice concurrently wrote two identical rows
 * per suggestion. Every later upsert against that key then threw
 * {@code IncorrectResultSizeDataAccessException: Query did not return a unique
 * result}, which {@code CodeScanService.bulkDecide} swallowed into
 * "Approved 0 of N". The duplicate never self-heals, so the suggestion stays
 * permanently unapprovable.
 *
 * <p>Repairing on read is what unwedges installs whose duplicates predate the
 * constraint: keep the newest row (the one existing {@code applied_doc_id}
 * references), drop the rest along with their RAG embeddings.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaDocumentationDeduplicator {

    private final SchemaDocumentationRepository schemaDocRepository;
    private final CodeKnowledgeSuggestionRepository suggestionRepository;
    private final TrainingService trainingService;

    /**
     * Reduces {@code matches} to at most one row, deleting any extras.
     *
     * @return the surviving row, or {@code null} when {@code matches} is empty
     *         (caller creates a fresh row).
     */
    @Transactional
    public SchemaDocumentation collapse(List<SchemaDocumentation> matches, String context) {
        if (matches == null || matches.isEmpty()) {
            return null;
        }
        if (matches.size() == 1) {
            return matches.get(0);
        }

        // Newest wins: it is the row a prior approval linked via applied_doc_id,
        // so keeping it preserves those references. id breaks ties on rows
        // written inside the same clock tick.
        List<SchemaDocumentation> ordered = matches.stream()
            .sorted(Comparator
                .comparing(SchemaDocumentation::getCreatedAt,
                    Comparator.nullsFirst(Comparator.<LocalDateTime>naturalOrder()))
                .thenComparing(SchemaDocumentation::getId,
                    Comparator.nullsFirst(Comparator.<String>naturalOrder())))
            .toList();

        SchemaDocumentation survivor = ordered.get(ordered.size() - 1);
        List<SchemaDocumentation> stale = ordered.subList(0, ordered.size() - 1);
        log.warn("Collapsing {} duplicate schema_documentation rows for {} — keeping {}",
            ordered.size(), context, survivor.getId());

        // Which row wins is a heuristic; leaving another approval pointing at a
        // deleted id is not acceptable either way, so repoint before deleting.
        int repointed = suggestionRepository.repointAppliedDocId(
            survivor.getId(), stale.stream().map(SchemaDocumentation::getId).toList());
        if (repointed > 0) {
            log.info("Repointed {} applied_doc_id reference(s) to {}", repointed, survivor.getId());
        }

        for (SchemaDocumentation doc : stale) {
            try {
                trainingService.deleteDocumentationEmbedding(doc.getConnectionId(), doc.getId());
            } catch (Exception e) {
                // A stranded embedding degrades retrieval; a failed delete must not
                // block the approval the caller is in the middle of.
                log.warn("Failed to delete embedding for duplicate doc {}: {}", doc.getId(), e.getMessage());
            }
            schemaDocRepository.delete(doc);
        }
        return survivor;
    }
}
