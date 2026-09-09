package com.dbaagent.service.brain.core;
import com.dbaagent.service.TrainingService;

import com.dbaagent.model.brain.BrainNoteRequest;
import com.dbaagent.model.brain.BrainNoteResponse;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaSnapshot;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class BrainNoteService {
    private static final int MAX_CONFIDENCE = 95;
    private static final int MIN_CONFIDENCE = 20;
    private static final int CONFIDENCE_BASE = 25;

    private final SchemaDocumentationRepository schemaDocumentationRepository;
    private final SchemaSnapshotRepository schemaSnapshotRepository;
    private final TrainingService trainingService;

    public List<BrainNoteResponse> getNotes(String connectionId, String scopeType, String tableName, String columnName) {
        LocalDateTime lastSnapshotAt = findLastSnapshotAt(connectionId);
        List<SchemaDocumentation> docs = schemaDocumentationRepository.findByConnectionId(connectionId);
        return docs.stream()
            .filter(doc -> matchesScope(doc, scopeType, tableName, columnName))
            .sorted(Comparator.comparing(BrainNoteService::touchedAt,
                Comparator.nullsLast(Comparator.reverseOrder())))
            .map(doc -> toResponse(doc, lastSnapshotAt))
            .collect(Collectors.toList());
    }

    /**
     * Recency for sorting: {@code updatedAt} falls back to {@code createdAt}.
     *
     * <p>{@code @PreUpdate} never fires on an insert, so a note that has only ever
     * been created has a null {@code updatedAt}. Sorting on {@code updatedAt} alone
     * with nulls last therefore pushed every brand-new note to the *bottom* — the
     * opposite of what "newest first" means, and why a freshly approved code-scan
     * suggestion did not show up at the top of the list. Same
     * {@code COALESCE(updatedAt, createdAt)} convention as
     * {@code CompanyKnowledgeEntryRepository.findByConnectionIdOrderByRecency}.
     */
    private static LocalDateTime touchedAt(SchemaDocumentation doc) {
        return doc.getUpdatedAt() != null ? doc.getUpdatedAt() : doc.getCreatedAt();
    }

    public BrainNoteResponse createNote(BrainNoteRequest request) {
        validateRequest(request, true);
        SchemaDocumentation doc = SchemaDocumentation.builder()
            .connectionId(request.getConnectionId())
            .objectType(parseScopeType(request.getScopeType()))
            .objectName(resolveObjectName(request))
            .parentObject(resolveParentObject(request))
            .description(request.getNoteText())
            .createdBy(request.getCreatedBy())
            .build();

        doc = schemaDocumentationRepository.save(doc);
        trainingService.upsertDocumentationEmbedding(doc);
        return toResponse(doc, findLastSnapshotAt(request.getConnectionId()));
    }

    public BrainNoteResponse updateNote(String noteId, BrainNoteRequest request) {
        validateRequest(request, false);
        SchemaDocumentation doc = schemaDocumentationRepository.findById(noteId)
            .orElseThrow(() -> new IllegalArgumentException("Note not found"));

        if (request.getNoteText() != null) {
            doc.setDescription(request.getNoteText());
        }
        if (request.getCreatedBy() != null) {
            doc.setCreatedBy(request.getCreatedBy());
        }

        doc = schemaDocumentationRepository.save(doc);
        trainingService.upsertDocumentationEmbedding(doc);
        return toResponse(doc, findLastSnapshotAt(doc.getConnectionId()));
    }

    public void deleteNote(String noteId) {
        SchemaDocumentation doc = schemaDocumentationRepository.findById(noteId)
            .orElseThrow(() -> new IllegalArgumentException("Note not found"));
        String connectionId = doc.getConnectionId();
        schemaDocumentationRepository.delete(doc);
        trainingService.deleteDocumentationEmbedding(connectionId, noteId);
    }

    public String getConnectionId(String noteId) {
        return schemaDocumentationRepository.findById(noteId)
            .map(SchemaDocumentation::getConnectionId)
            .orElseThrow(() -> new IllegalArgumentException("Note not found"));
    }

    private SchemaDocumentation.DocumentationType parseScopeType(String scopeType) {
        if (scopeType == null) {
            throw new IllegalArgumentException("scopeType is required");
        }
        SchemaDocumentation.DocumentationType type = SchemaDocumentation.DocumentationType
            .valueOf(scopeType.trim().toUpperCase(Locale.ROOT));
        if (type != SchemaDocumentation.DocumentationType.TABLE &&
            type != SchemaDocumentation.DocumentationType.COLUMN) {
            throw new IllegalArgumentException("scopeType must be TABLE or COLUMN");
        }
        return type;
    }

    private String resolveObjectName(BrainNoteRequest request) {
        if ("COLUMN".equalsIgnoreCase(request.getScopeType())) {
            return request.getColumnName();
        }
        return request.getTableName();
    }

    private String resolveParentObject(BrainNoteRequest request) {
        if ("COLUMN".equalsIgnoreCase(request.getScopeType())) {
            return request.getTableName();
        }
        return null;
    }

    private void validateRequest(BrainNoteRequest request, boolean requireConnection) {
        if (request == null) {
            throw new IllegalArgumentException("Request is required");
        }
        if (requireConnection && (request.getConnectionId() == null || request.getConnectionId().isBlank())) {
            throw new IllegalArgumentException("connectionId is required");
        }
        if (request.getScopeType() == null || request.getScopeType().isBlank()) {
            throw new IllegalArgumentException("scopeType is required");
        }
        if ("COLUMN".equalsIgnoreCase(request.getScopeType())) {
            if (request.getTableName() == null || request.getTableName().isBlank()) {
                throw new IllegalArgumentException("tableName is required for column notes");
            }
            if (request.getColumnName() == null || request.getColumnName().isBlank()) {
                throw new IllegalArgumentException("columnName is required for column notes");
            }
        } else if ("TABLE".equalsIgnoreCase(request.getScopeType())) {
            if (request.getTableName() == null || request.getTableName().isBlank()) {
                throw new IllegalArgumentException("tableName is required for table notes");
            }
        }
        if (request.getNoteText() == null || request.getNoteText().isBlank()) {
            throw new IllegalArgumentException("noteText is required");
        }
    }

    private boolean matchesScope(
        SchemaDocumentation doc,
        String scopeType,
        String tableName,
        String columnName
    ) {
        if (doc == null) {
            return false;
        }
        if (scopeType == null || scopeType.isBlank()) {
            if (doc.getObjectType() != SchemaDocumentation.DocumentationType.TABLE &&
                doc.getObjectType() != SchemaDocumentation.DocumentationType.COLUMN) {
                return false;
            }
        }
        if (scopeType != null && !scopeType.isBlank()) {
            SchemaDocumentation.DocumentationType desired = parseScopeType(scopeType);
            if (doc.getObjectType() != desired) {
                return false;
            }
        }
        if (tableName != null && !tableName.isBlank()) {
            if (doc.getObjectType() == SchemaDocumentation.DocumentationType.TABLE) {
                if (!tableName.equalsIgnoreCase(doc.getObjectName())) {
                    return false;
                }
            } else {
                if (!tableName.equalsIgnoreCase(doc.getParentObject())) {
                    return false;
                }
            }
        }
        if (columnName != null && !columnName.isBlank()) {
            if (doc.getObjectType() != SchemaDocumentation.DocumentationType.COLUMN) {
                return false;
            }
            if (!columnName.equalsIgnoreCase(doc.getObjectName())) {
                return false;
            }
        }
        return true;
    }

    private BrainNoteResponse toResponse(SchemaDocumentation doc, LocalDateTime lastSnapshotAt) {
        LocalDateTime updatedAt = doc.getUpdatedAt() != null ? doc.getUpdatedAt() : doc.getCreatedAt();
        boolean stale = lastSnapshotAt != null && updatedAt != null && updatedAt.isBefore(lastSnapshotAt);
        String staleReason = stale ? "Schema snapshot captured after this note" : null;
        int confidenceScore = computeConfidence(doc.getDescription(), updatedAt, stale);

        String tableName = doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN
            ? doc.getParentObject()
            : doc.getObjectName();
        String columnName = doc.getObjectType() == SchemaDocumentation.DocumentationType.COLUMN
            ? doc.getObjectName()
            : null;

        return BrainNoteResponse.builder()
            .id(doc.getId())
            .connectionId(doc.getConnectionId())
            .scopeType(doc.getObjectType() != null ? doc.getObjectType().name() : null)
            .tableName(tableName)
            .columnName(columnName)
            .noteText(doc.getDescription())
            .createdBy(doc.getCreatedBy())
            .createdAt(doc.getCreatedAt())
            .updatedAt(doc.getUpdatedAt())
            .confidenceScore(confidenceScore)
            .stale(stale)
            .staleReason(staleReason)
            .source(doc.getSource() != null ? doc.getSource().name() : "USER")
            .sourceFiles(doc.getSourceFiles())
            .build();
    }

    private LocalDateTime findLastSnapshotAt(String connectionId) {
        Optional<SchemaSnapshot> snapshot = schemaSnapshotRepository
            .findTopByConnectionIdOrderByCapturedAtDesc(connectionId);
        return snapshot.map(SchemaSnapshot::getCapturedAt).orElse(null);
    }

    private int computeConfidence(String noteText, LocalDateTime updatedAt, boolean stale) {
        int wordCount = 0;
        if (noteText != null && !noteText.isBlank()) {
            wordCount = noteText.trim().split("\\s+").length;
        }
        int lengthPoints = Math.min(50, wordCount);
        int recencyPoints = 0;
        if (updatedAt != null) {
            long daysOld = Math.max(0, Duration.between(updatedAt, LocalDateTime.now()).toDays());
            if (daysOld <= 30) {
                recencyPoints = 20;
            } else if (daysOld <= 90) {
                recencyPoints = 10;
            }
        }
        int score = CONFIDENCE_BASE + lengthPoints + recencyPoints;
        if (stale) {
            score -= 10;
        }
        return Math.max(MIN_CONFIDENCE, Math.min(MAX_CONFIDENCE, score));
    }
}
