package com.dbaagent.service;

import com.dbaagent.model.DocumentationSource;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.repository.SchemaDocumentationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class SchemaDriftListenerColumnsTest {

    private SchemaDocumentationRepository repo;
    private SchemaDescriptionService descriptions;
    private TrainingService training;
    private SchemaDriftListener listener;

    @BeforeEach
    void setUp() {
        repo = mock(SchemaDocumentationRepository.class);
        descriptions = mock(SchemaDescriptionService.class);
        training = mock(TrainingService.class);
        listener = new SchemaDriftListener(repo, descriptions, training);
    }

    @Test
    void onColumnsAddedQueuesAiRegenForParentTablesUniquely() {
        listener.onColumnsAdded("c1", List.of(
            new SchemaDriftListener.ColumnRef("PRODUCT_SERVICES", "tip_amount"),
            new SchemaDriftListener.ColumnRef("PRODUCT_SERVICES", "tip_percent"),
            new SchemaDriftListener.ColumnRef("BOOKINGS", "rebooking_id")
        ));
        // Background virtual thread should fire generateDescriptions exactly
        // once with the deduped table list.
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<String>> tablesCaptor = ArgumentCaptor.forClass(List.class);
        verify(descriptions, timeout(TimeUnit.SECONDS.toMillis(2)).times(1))
            .generateDescriptions(eq("c1"), tablesCaptor.capture());
        var tables = tablesCaptor.getValue();
        assertEquals(2, tables.size());
        assertTrue(tables.contains("PRODUCT_SERVICES"));
        assertTrue(tables.contains("BOOKINGS"));
    }

    @Test
    void onColumnsRemovedDeletesCodeDerivedAndAiGeneratedRowsAndCleansEmbeddings() {
        SchemaDocumentation codeDoc = doc("d1", DocumentationSource.CODE_DERIVED, "old_col", "BOOKINGS");
        SchemaDocumentation aiDoc = doc("d2", DocumentationSource.AI_GENERATED, "old_col", "BOOKINGS");
        SchemaDocumentation userDoc = doc("d3", DocumentationSource.USER, "old_col", "BOOKINGS");

        when(repo.findByConnectionIdAndParentObject("c1", "BOOKINGS"))
            .thenReturn(List.of(codeDoc, aiDoc, userDoc));

        listener.onColumnsRemoved("c1", List.of(
            new SchemaDriftListener.ColumnRef("BOOKINGS", "old_col")
        ));

        verify(training).deleteDocumentationEmbedding("c1", "d1");
        verify(training).deleteDocumentationEmbedding("c1", "d2");
        verify(training, never()).deleteDocumentationEmbedding("c1", "d3");
        verify(repo).delete(codeDoc);
        verify(repo).delete(aiDoc);
        verify(repo, never()).delete(userDoc);
    }

    @Test
    void onColumnsModifiedDoesNotMutate() {
        listener.onColumnsModified("c1", List.of(
            new SchemaDriftListener.ColumnRef("BOOKINGS", "status")
        ));
        verify(descriptions, never()).generateDescriptions(anyString(), any());
        verify(repo, never()).delete(any(SchemaDocumentation.class));
        verify(training, never()).deleteDocumentationEmbedding(anyString(), anyString());
    }

    @Test
    void onTablesRemovedNowCleansEmbeddings() {
        SchemaDocumentation tableDoc = doc("t1", DocumentationSource.AI_GENERATED, "OLD_TABLE", null);
        tableDoc.setObjectType(SchemaDocumentation.DocumentationType.TABLE);
        when(repo.findByConnectionIdAndObjectTypeAndObjectName(
                "c1", SchemaDocumentation.DocumentationType.TABLE, "OLD_TABLE"))
            .thenReturn(List.of(tableDoc));
        when(repo.findByConnectionIdAndParentObject("c1", "OLD_TABLE"))
            .thenReturn(List.of());

        listener.onTablesRemoved("c1", List.of("OLD_TABLE"));

        verify(training, atLeastOnce()).deleteDocumentationEmbedding("c1", "t1");
        verify(repo).delete(tableDoc);
    }

    private static SchemaDocumentation doc(String id, DocumentationSource src, String name, String parent) {
        SchemaDocumentation d = new SchemaDocumentation();
        d.setId(id == null ? UUID.randomUUID().toString() : id);
        d.setConnectionId("c1");
        d.setSource(src);
        d.setObjectType(SchemaDocumentation.DocumentationType.COLUMN);
        d.setObjectName(name);
        d.setParentObject(parent);
        d.setDescription("test");
        return d;
    }
}
