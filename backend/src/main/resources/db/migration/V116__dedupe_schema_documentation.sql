-- Collapse duplicate schema_documentation rows and make the logical key unique.
--
-- schema_documentation never carried a unique constraint on
-- (connection_id, object_type, object_name, parent_object, source), and
-- CodeSuggestionApplier.approve took no row lock — so a bulk approve submitted
-- twice concurrently wrote two identical rows per suggestion. Every later upsert
-- against such a key then threw
--   IncorrectResultSizeDataAccessException: Query did not return a unique result
-- which CodeScanService.bulkDecide swallowed into "Approved 0 of N", leaving the
-- whole Review queue permanently unapprovable.
--
-- NOTE: this repo has no Flyway runtime. Applied at startup, idempotently, by
-- SchemaDocumentationDedupeInitializer — this file is the changelog of record.

BEGIN;

CREATE TEMP TABLE schema_doc_dupe_losers ON COMMIT DROP AS
SELECT id, keep_id
FROM (
    SELECT id,
           first_value(id) OVER w AS keep_id,
           row_number()    OVER w AS rn
    FROM schema_documentation
    WINDOW w AS (
        PARTITION BY connection_id, object_type, object_name,
                     coalesce(parent_object, ''), source
        ORDER BY created_at DESC NULLS LAST, id DESC
    )
) ranked
WHERE rn > 1;

-- Keep the newest row: it is the one existing applied_doc_id references point at.
-- Repoint any that reference a loser before it disappears (applied_doc_id is a
-- loose reference, not a real FK, so a stale value would fail silently).
UPDATE code_knowledge_suggestion s
SET applied_doc_id = l.keep_id
FROM schema_doc_dupe_losers l
WHERE s.applied_doc_id = l.id;

-- RAG embeddings for documentation are keyed by the doc id, so the loser's
-- vector must go with it or retrieval keeps returning the orphan.
DELETE FROM rag_documents WHERE id IN (SELECT id FROM schema_doc_dupe_losers);

DELETE FROM schema_documentation WHERE id IN (SELECT id FROM schema_doc_dupe_losers);

-- coalesce(parent_object, '') because Postgres treats NULLs as distinct: without
-- it, TABLE rows (parent_object IS NULL) would never collide.
CREATE UNIQUE INDEX IF NOT EXISTS ux_schema_doc_target
    ON schema_documentation (
        connection_id, object_type, object_name, coalesce(parent_object, ''), source
    );

COMMIT;
