package com.dbaagent.service.brain.core;

import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.brain.BrainNoteResponse;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.repository.SchemaSnapshotRepository;
import com.dbaagent.service.TrainingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

/**
 * The Write-notes list is "newest first", and a note approved from the code-scan
 * Review queue must land at the top of it.
 */
@ExtendWith(MockitoExtension.class)
class BrainNoteServiceOrderingTest {

    @Mock private SchemaDocumentationRepository schemaDocumentationRepository;
    @Mock private SchemaSnapshotRepository schemaSnapshotRepository;
    @Mock private TrainingService trainingService;

    private BrainNoteService service;

    @BeforeEach
    void setUp() {
        service = new BrainNoteService(
            schemaDocumentationRepository, schemaSnapshotRepository, trainingService);
    }

    /**
     * Regression: {@code @PreUpdate} does not fire on insert, so a just-created note
     * has a null {@code updatedAt}. Sorting on {@code updatedAt} with nulls last sent
     * every brand-new note to the bottom of the list.
     */
    @Test
    void freshlyCreatedNoteSortsAboveOlderEditedOnes() {
        SchemaDocumentation editedYesterday = doc(
            "old_col",
            LocalDateTime.of(2026, 8, 1, 9, 0),   // created
            LocalDateTime.of(2026, 8, 21, 9, 0)); // updated
        SchemaDocumentation createdJustNow = doc(
            "new_col",
            LocalDateTime.of(2026, 8, 22, 17, 0),
            null);                                 // never edited
        when(schemaDocumentationRepository.findByConnectionId("c1"))
            .thenReturn(List.of(editedYesterday, createdJustNow));

        List<BrainNoteResponse> notes = service.getNotes("c1", null, null, null);

        assertThat(notes).extracting(BrainNoteResponse::getColumnName)
            .containsExactly("new_col", "old_col");
    }

    @Test
    void editedNoteSortsAboveNewerButUntouchedOne() {
        SchemaDocumentation createdEarlier = doc(
            "edited_col",
            LocalDateTime.of(2026, 8, 1, 9, 0),
            LocalDateTime.of(2026, 8, 22, 18, 0)); // edited most recently
        SchemaDocumentation createdLater = doc(
            "untouched_col",
            LocalDateTime.of(2026, 8, 22, 17, 0),
            null);
        when(schemaDocumentationRepository.findByConnectionId("c1"))
            .thenReturn(List.of(createdLater, createdEarlier));

        List<BrainNoteResponse> notes = service.getNotes("c1", null, null, null);

        assertThat(notes).extracting(BrainNoteResponse::getColumnName)
            .containsExactly("edited_col", "untouched_col");
    }

    private static SchemaDocumentation doc(String columnName,
                                           LocalDateTime createdAt,
                                           LocalDateTime updatedAt) {
        SchemaDocumentation doc = new SchemaDocumentation();
        doc.setId(columnName);
        doc.setConnectionId("c1");
        doc.setObjectType(SchemaDocumentation.DocumentationType.COLUMN);
        doc.setObjectName(columnName);
        doc.setParentObject("analytics.orders");
        doc.setDescription("desc for " + columnName);
        doc.setSource(DocumentationSource.CODE_DERIVED);
        doc.setCreatedAt(createdAt);
        doc.setUpdatedAt(updatedAt);
        return doc;
    }
}
