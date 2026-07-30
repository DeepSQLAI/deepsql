package com.dbaagent.service.codescan;

import com.dbaagent.model.CompanyKnowledgeEntry;
import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import com.dbaagent.repository.CodeKnowledgeSuggestionRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.CompanyKnowledgeService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.TrainingService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Materializes an APPROVED suggestion into a real
 * {@link com.dbaagent.model.SchemaDocumentation} or
 * {@link com.dbaagent.model.CompanyKnowledgeEntry}, then triggers the existing
 * embedding upsert so the new content lands in RAG immediately.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CodeSuggestionApplier {

    private final CodeKnowledgeSuggestionRepository suggestionRepository;
    private final SchemaDocumentationRepository schemaDocRepository;
    private final CompanyKnowledgeService companyKnowledgeService;
    private final TrainingService trainingService;
    private final SchemaScannerService schemaScannerService;

    @Transactional
    public CodeKnowledgeSuggestion approve(String suggestionId, String decidedBy, String note) {
        CodeKnowledgeSuggestion suggestion = suggestionRepository.findById(suggestionId)
            .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + suggestionId));
        if (suggestion.getStatus() == CodeKnowledgeSuggestion.Status.APPROVED) {
            return suggestion;
        }
        if (suggestion.getStatus() == CodeKnowledgeSuggestion.Status.REJECTED
            || suggestion.getStatus() == CodeKnowledgeSuggestion.Status.SUPERSEDED) {
            throw new IllegalStateException("Suggestion cannot be approved from status " + suggestion.getStatus());
        }

        switch (suggestion.getTargetKind()) {
            case SCHEMA_DOC -> applySchemaDoc(suggestion, decidedBy);
            case KNOWLEDGE_ENTRY -> applyKnowledgeEntry(suggestion, decidedBy);
        }

        suggestion.setStatus(CodeKnowledgeSuggestion.Status.APPROVED);
        suggestion.setDecidedAt(LocalDateTime.now());
        suggestion.setDecidedBy(decidedBy);
        suggestion.setDecisionNote(note);
        return suggestionRepository.save(suggestion);
    }

    @Transactional
    public CodeKnowledgeSuggestion reject(String suggestionId, String decidedBy, String note) {
        CodeKnowledgeSuggestion suggestion = suggestionRepository.findById(suggestionId)
            .orElseThrow(() -> new IllegalArgumentException("Suggestion not found: " + suggestionId));
        if (suggestion.getStatus() == CodeKnowledgeSuggestion.Status.REJECTED) {
            return suggestion;
        }
        if (suggestion.getStatus() == CodeKnowledgeSuggestion.Status.APPROVED) {
            throw new IllegalStateException("Cannot reject an already-approved suggestion");
        }
        suggestion.setStatus(CodeKnowledgeSuggestion.Status.REJECTED);
        suggestion.setDecidedAt(LocalDateTime.now());
        suggestion.setDecidedBy(decidedBy);
        suggestion.setDecisionNote(note);
        return suggestionRepository.save(suggestion);
    }

    private void applySchemaDoc(CodeKnowledgeSuggestion suggestion, String decidedBy) {
        SchemaDocumentation.DocumentationType objectType = resolveObjectType(suggestion);
        String objectName;
        String parentObject = null;
        String target = suggestion.getTargetObject();
        if (target == null || target.isBlank()) {
            log.warn("SCHEMA_DOC suggestion {} has no targetObject; skipping", suggestion.getId());
            return;
        }
        if (objectType == SchemaDocumentation.DocumentationType.COLUMN) {
            int dot = target.indexOf('.');
            if (dot <= 0 || dot >= target.length() - 1) {
                log.warn("SCHEMA_DOC column suggestion {} has malformed target '{}'", suggestion.getId(), target);
                return;
            }
            parentObject = target.substring(0, dot);
            objectName = target.substring(dot + 1);
            // Match SchemaDescriptionService's convention: parent_object is
            // database-qualified (e.g. "idb_database.HOTEL_SERVICES") so the
            // upsert lookup unifies with AI_GENERATED rows for the same column
            // instead of orphaning a separate row per source.
            String dbName = resolveDatabaseName(suggestion.getConnectionId());
            if (dbName != null && !dbName.isBlank() && !parentObject.contains(".")) {
                parentObject = dbName + "." + parentObject;
            }
        } else {
            objectName = target;
        }

        // Scope the upsert to our own CODE_DERIVED rows AND, for columns,
        // include parent_object — same column name can exist in many tables,
        // so a (connection, COLUMN, "status", CODE_DERIVED) lookup would
        // otherwise return multiple rows and throw "Query did not return a
        // unique result".
        Optional<SchemaDocumentation> existing;
        if (objectType == SchemaDocumentation.DocumentationType.COLUMN) {
            existing = schemaDocRepository
                .findByConnectionIdAndObjectTypeAndObjectNameAndParentObjectAndSource(
                    suggestion.getConnectionId(),
                    objectType,
                    objectName,
                    parentObject,
                    DocumentationSource.CODE_DERIVED
                );
        } else {
            existing = schemaDocRepository
                .findByConnectionIdAndObjectTypeAndObjectNameAndSource(
                    suggestion.getConnectionId(),
                    objectType,
                    objectName,
                    DocumentationSource.CODE_DERIVED
                );
        }

        SchemaDocumentation doc = existing.orElseGet(SchemaDocumentation::new);
        doc.setConnectionId(suggestion.getConnectionId());
        doc.setObjectType(objectType);
        doc.setObjectName(objectName);
        doc.setParentObject(parentObject);
        doc.setDescription(suggestion.getContent());
        Object terms = suggestion.getPayload() == null ? null : suggestion.getPayload().get("businessTerms");
        if (terms instanceof List<?> list && !list.isEmpty()) {
            doc.setBusinessTerms(String.join(", ", list.stream().map(String::valueOf).toList()));
        }
        doc.setSource(DocumentationSource.CODE_DERIVED);
        doc.setConfidence(suggestion.getConfidence());
        // Carry the source-file provenance forward — the suggestion's
        // {path, startLine, endLine, rationale} list lands directly on the
        // documentation row so the editor can render "where did this come
        // from" and so future schema-drift code can spot orphans.
        doc.setSourceFiles(suggestion.getSourceFiles());
        if (doc.getCreatedBy() == null) doc.setCreatedBy(decidedBy);

        SchemaDocumentation saved = schemaDocRepository.save(doc);
        suggestion.setAppliedDocId(saved.getId());

        try {
            trainingService.upsertDocumentationEmbedding(saved);
        } catch (Exception e) {
            log.warn("Failed to embed schema doc {} after approval: {}", saved.getId(), e.getMessage());
        }
    }

    private void applyKnowledgeEntry(CodeKnowledgeSuggestion suggestion, String decidedBy) {
        CompanyKnowledgeEntry entry = CompanyKnowledgeEntry.builder()
            .connectionId(suggestion.getConnectionId())
            .title(suggestion.getTitle())
            .content(suggestion.getContent())
            .entryType(resolveEntryType(suggestion))
            .linkedTables(suggestion.getLinkedTables())
            .linkedColumns(suggestion.getLinkedColumns())
            .createdBy(decidedBy)
            .build();
        CompanyKnowledgeEntry saved = companyKnowledgeService.createEntry(entry);
        suggestion.setAppliedEntryId(saved.getId());
    }

    /** Returns the connection's database name (e.g. "idb_database") or null. */
    private String resolveDatabaseName(String connectionId) {
        try {
            var schema = schemaScannerService.scanSchema(connectionId);
            return schema == null ? null : schema.getDatabaseName();
        } catch (Exception e) {
            log.warn("could not resolve database name for {}: {}", connectionId, e.getMessage());
            return null;
        }
    }

    private static SchemaDocumentation.DocumentationType resolveObjectType(CodeKnowledgeSuggestion suggestion) {
        Map<String, Object> payload = suggestion.getPayload();
        Object objectKind = payload == null ? null : payload.get("objectKind");
        if (objectKind != null) {
            try {
                return SchemaDocumentation.DocumentationType.valueOf(objectKind.toString().toUpperCase());
            } catch (IllegalArgumentException ignore) {
                // fall through to inference
            }
        }
        // Heuristic: if targetObject contains a dot, treat as COLUMN.
        String target = suggestion.getTargetObject();
        if (target != null && target.contains(".")) {
            return SchemaDocumentation.DocumentationType.COLUMN;
        }
        return SchemaDocumentation.DocumentationType.TABLE;
    }

    private static CompanyKnowledgeEntry.EntryType resolveEntryType(CodeKnowledgeSuggestion suggestion) {
        Map<String, Object> payload = suggestion.getPayload();
        Object t = payload == null ? null : payload.get("entryType");
        if (t != null) {
            try {
                return CompanyKnowledgeEntry.EntryType.valueOf(t.toString().toUpperCase());
            } catch (IllegalArgumentException ignore) {
                // fall through
            }
        }
        return CompanyKnowledgeEntry.EntryType.BUSINESS_RULE;
    }
}
