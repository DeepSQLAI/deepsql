package com.dbaagent.service.codescan;

import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.code.CodeKnowledgeSuggestion;
import com.dbaagent.repository.CodeKnowledgeSuggestionRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.CompanyKnowledgeService;
import com.dbaagent.service.SchemaScannerService;
import com.dbaagent.service.TrainingService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
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

    @BeforeEach
    void setUp() {
        applier = new CodeSuggestionApplier(
            suggestionRepository,
            schemaDocRepository,
            companyKnowledgeService,
            trainingService,
            schemaScannerService
        );
    }

    @Test
    void approveSchemaDocColumn_writesCodeDerivedRow() throws Exception {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("fct_ashram_visit.party_size");
        suggestion.setPayload(Map.of("objectKind", "COLUMN", "businessTerms", List.of("party size")));

        when(suggestionRepository.findById("s1")).thenReturn(Optional.of(suggestion));
        when(suggestionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(schemaDocRepository.findByConnectionIdAndObjectTypeAndObjectNameAndParentObjectAndSource(
            eq("conn-1"),
            eq(SchemaDocumentation.DocumentationType.COLUMN),
            eq("party_size"),
            eq("acme_erp.fct_ashram_visit"),
            eq(DocumentationSource.CODE_DERIVED)
        )).thenReturn(Optional.empty());
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

    @Test
    void approveSchemaDoc_blankTarget_throwsInsteadOfSilentSkip() {
        CodeKnowledgeSuggestion suggestion = baseSuggestion();
        suggestion.setTargetKind(CodeKnowledgeSuggestion.TargetKind.SCHEMA_DOC);
        suggestion.setTargetObject("  ");
        when(suggestionRepository.findById("s1")).thenReturn(Optional.of(suggestion));

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
        when(suggestionRepository.findById("s1")).thenReturn(Optional.of(suggestion));

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
