package com.dbaagent.service.codescan;

import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import com.dbaagent.repository.CodeKnowledgeSuggestionRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.CompanyKnowledgeService;
import com.dbaagent.service.SchemaDocumentationDeduplicator;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.TrainingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CodeSuggestionApplierTest {

    @Mock private CodeKnowledgeSuggestionRepository suggestionRepository;
    @Mock private SchemaDocumentationRepository schemaDocRepository;
    @Mock private CompanyKnowledgeService companyKnowledgeService;
    @Mock private TrainingService trainingService;
    @Mock private SchemaScannerService schemaScannerService;

    private CodeSuggestionApplier applier;
    private SchemaDocumentationDeduplicator deduplicator;

    @BeforeEach
    void setUp() {
        // Real deduplicator over the mocked repo: the collapse behaviour is the
        // thing under test in approveSchemaDocColumn_collapsesLegacyDuplicates.
        deduplicator = new SchemaDocumentationDeduplicator(schemaDocRepository, suggestionRepository, trainingService);
        applier = new CodeSuggestionApplier(
            suggestionRepository,
            schemaDocRepository,
            companyKnowledgeService,
            trainingService,
            schemaScannerService,
            deduplicator
        );
    }

    @Test
    void approveSchemaDocColumn_writesCodeDerivedRow() throws Exception {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("fct_ashram_visit.party_size");
        suggestion.setPayload(Map.of("objectKind", "COLUMN", "businessTerms", List.of("party size")));

        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDocRepository.findByConnectionIdAndObjectTypeAndObjectNameAndParentObjectAndSource(
            eq("conn-1"),
            eq(SchemaDocumentation.DocumentationType.COLUMN),
            eq("party_size"),
            eq("acme_erp.fct_ashram_visit"),
            eq(DocumentationSource.CODE_DERIVED)
        )).thenReturn(List.of());
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("acme_erp");
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.save(any())).thenAnswer(inv -> {
            SchemaDocumentation doc = inv.getArgument(0);
            doc.setId("doc-1");
            return doc;
        });

        CodeKnowledgeSuggestion approved = applier.approve("s1", "admin", null);

        assertThat(approved.getStatus()).isEqualTo(CodeKnowledgeSuggestion.Status.APPROVED);
        assertThat(approved.getAppliedDocId()).isEqualTo("doc-1");

        ArgumentCaptor<SchemaDocumentation> captor = ArgumentCaptor.forClass(SchemaDocumentation.class);
        verify(schemaDocRepository).save(captor.capture());
        SchemaDocumentation saved = captor.getValue();
        assertThat(saved.getSource()).isEqualTo(DocumentationSource.CODE_DERIVED);
        assertThat(saved.getObjectName()).isEqualTo("party_size");
        assertThat(saved.getParentObject()).isEqualTo("acme_erp.fct_ashram_visit");
        assertThat(saved.getBusinessTerms()).isEqualTo("party size");
        verify(trainingService).upsertDocumentationEmbedding(saved);
    }

    /**
     * Regression: two identical CODE_DERIVED rows (written by a bulk approve
     * submitted twice concurrently before V116's unique index existed) made the
     * Optional-returning finder throw "Query did not return a unique result",
     * which bulk-decide swallowed into "Approved 0 of N" forever.
     */
    @Test
    void approveSchemaDocColumn_collapsesLegacyDuplicates() throws Exception {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("fct_ashram_visit.party_size");
        suggestion.setPayload(Map.of("objectKind", "COLUMN"));

        SchemaDocumentation older = duplicateRow("doc-old", LocalDateTime.of(2026, 8, 13, 17, 11, 28));
        SchemaDocumentation newer = duplicateRow("doc-new", LocalDateTime.of(2026, 8, 13, 17, 11, 31));

        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDocRepository.findByConnectionIdAndObjectTypeAndObjectNameAndParentObjectAndSource(
            eq("conn-1"),
            eq(SchemaDocumentation.DocumentationType.COLUMN),
            eq("party_size"),
            eq("acme_erp.fct_ashram_visit"),
            eq(DocumentationSource.CODE_DERIVED)
        )).thenReturn(List.of(older, newer));
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("acme_erp");
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CodeKnowledgeSuggestion approved = applier.approve("s1", "admin", null);

        assertThat(approved.getStatus()).isEqualTo(CodeKnowledgeSuggestion.Status.APPROVED);
        // Newest survives — it is the row prior approvals linked via applied_doc_id.
        assertThat(approved.getAppliedDocId()).isEqualTo("doc-new");
        verify(schemaDocRepository).delete(older);
        verify(schemaDocRepository, never()).delete(newer);
        verify(trainingService).deleteDocumentationEmbedding("conn-1", "doc-old");
        // Another approval pointing at the deleted row must follow the survivor.
        verify(suggestionRepository).repointAppliedDocId("doc-new", List.of("doc-old"));

        ArgumentCaptor<SchemaDocumentation> captor = ArgumentCaptor.forClass(SchemaDocumentation.class);
        verify(schemaDocRepository).save(captor.capture());
        assertThat(captor.getValue().getId()).isEqualTo("doc-new");
        assertThat(captor.getValue().getDescription())
            .isEqualTo("Number of people in the visiting party.");
    }

    /** A failed embedding delete must not abort the approval it is cleaning up for. */
    @Test
    void approveSchemaDocColumn_collapseSurvivesEmbeddingDeleteFailure() throws Exception {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("fct_ashram_visit.party_size");
        suggestion.setPayload(Map.of("objectKind", "COLUMN"));

        SchemaDocumentation older = duplicateRow("doc-old", LocalDateTime.of(2026, 8, 13, 17, 11, 28));
        SchemaDocumentation newer = duplicateRow("doc-new", LocalDateTime.of(2026, 8, 13, 17, 11, 31));

        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDocRepository.findByConnectionIdAndObjectTypeAndObjectNameAndParentObjectAndSource(
            any(), any(), any(), any(), any())).thenReturn(List.of(older, newer));
        SchemaMetadata schema = new SchemaMetadata();
        schema.setDatabaseName("acme_erp");
        when(schemaScannerService.scanSchema("conn-1")).thenReturn(schema);
        when(schemaDocRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new RuntimeException("vector store down"))
            .when(trainingService).deleteDocumentationEmbedding("conn-1", "doc-old");

        CodeKnowledgeSuggestion approved = applier.approve("s1", "admin", null);

        assertThat(approved.getStatus()).isEqualTo(CodeKnowledgeSuggestion.Status.APPROVED);
        verify(schemaDocRepository).delete(older);
    }

    /** TABLE targets take the no-parent finder and must collapse the same way. */
    @Test
    void approveSchemaDocTable_collapsesLegacyDuplicates() throws Exception {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("fct_ashram_visit");
        suggestion.setPayload(Map.of("objectKind", "TABLE"));

        SchemaDocumentation older = duplicateRow("tbl-old", LocalDateTime.of(2026, 8, 13, 17, 11, 28));
        older.setObjectType(SchemaDocumentation.DocumentationType.TABLE);
        older.setParentObject(null);
        SchemaDocumentation newer = duplicateRow("tbl-new", LocalDateTime.of(2026, 8, 13, 17, 11, 31));
        newer.setObjectType(SchemaDocumentation.DocumentationType.TABLE);
        newer.setParentObject(null);

        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDocRepository.findByConnectionIdAndObjectTypeAndObjectNameAndSource(
            eq("conn-1"),
            eq(SchemaDocumentation.DocumentationType.TABLE),
            eq("fct_ashram_visit"),
            eq(DocumentationSource.CODE_DERIVED)
        )).thenReturn(List.of(older, newer));
        when(schemaDocRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        CodeKnowledgeSuggestion approved = applier.approve("s1", "admin", null);

        assertThat(approved.getAppliedDocId()).isEqualTo("tbl-new");
        verify(schemaDocRepository).delete(older);
    }

    /** Approve/reject must load under a row lock, else concurrent bulk decides re-create the duplicates. */
    @Test
    void approveAndReject_loadTheSuggestionForUpdate() {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setStatus(CodeKnowledgeSuggestion.Status.APPROVED);
        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));

        applier.approve("s1", "admin", null);
        verify(suggestionRepository, never()).findById(any());
    }

    private static SchemaDocumentation duplicateRow(String id, LocalDateTime createdAt) {
        SchemaDocumentation doc = new SchemaDocumentation();
        doc.setId(id);
        doc.setConnectionId("conn-1");
        doc.setObjectType(SchemaDocumentation.DocumentationType.COLUMN);
        doc.setObjectName("party_size");
        doc.setParentObject("acme_erp.fct_ashram_visit");
        doc.setSource(DocumentationSource.CODE_DERIVED);
        doc.setDescription("stale");
        doc.setCreatedAt(createdAt);
        return doc;
    }

    @Test
    void approveSchemaDoc_blankTarget_throwsInsteadOfSilentSkip() {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("  ");
        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> applier.approve("s1", "admin", null))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("no targetObject");
        verify(schemaDocRepository, never()).save(any());
        verify(suggestionRepository, never()).save(any());
    }

    @Test
    void approveRejectedSuggestion_isRejected() {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setStatus(CodeKnowledgeSuggestion.Status.REJECTED);
        when(suggestionRepository.findByIdForUpdate("s1")).thenReturn(Optional.of(suggestion));

        assertThatThrownBy(() -> applier.approve("s1", "admin", null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("cannot be approved");
    }

    private static CodeKnowledgeSuggestion baseSuggestion() {
        return CodeKnowledgeSuggestion.builder()
            .id("s1")
            .jobId("job-1")
            .connectionId("conn-1")
            .title("Visit party size")
            .content("Number of people in the visiting party.")
            .confidence(0.99)
            .status(CodeKnowledgeSuggestion.Status.PENDING)
            .sourceFiles(List.of(Map.of("path", "src/Seed.java", "startLine", 1, "endLine", 10)))
            .build();
    }
}
