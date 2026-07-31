package com.dbaagent.service.codescan;

import com.dbaagent.model.ColumnMetadata;
import com.dbaagent.model.SchemaDocumentation;
import com.dbaagent.model.SchemaMetadata;
import com.dbaagent.model.TableMetadata;
import com.dbaagent.repository.ColumnAntiPatternRepository;
import com.dbaagent.repository.ColumnDisambiguationRepository;
import com.dbaagent.repository.SchemaDocumentationRepository;
import com.dbaagent.service.SchemaScannerService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class SchemaAmbiguityServiceTest {

    private SchemaScannerService scanner;
    private SchemaDocumentationRepository docRepo;
    private ColumnDisambiguationRepository disambigRepo;
    private ColumnAntiPatternRepository antiPatternRepo;
    private SchemaAmbiguityService service;

    @BeforeEach
    void setUp() throws Exception {
        scanner = mock(SchemaScannerService.class);
        docRepo = mock(SchemaDocumentationRepository.class);
        disambigRepo = mock(ColumnDisambiguationRepository.class);
        antiPatternRepo = mock(ColumnAntiPatternRepository.class);
        service = new SchemaAmbiguityService(scanner, docRepo, disambigRepo, antiPatternRepo);

        when(scanner.scanSchema(anyString())).thenReturn(threeTableSchema());
        when(docRepo.findByConnectionId(anyString())).thenReturn(List.of());
        when(disambigRepo.findByConnectionId(anyString())).thenReturn(List.of());
        when(antiPatternRepo.findByConnectionIdAndSeverityOrderByDetectedAtDesc(anyString(), any()))
            .thenReturn(List.of());
    }

    @Test
    void detectsSimilarTableNames() {
        var items = service.compute("c1");
        // Expect a SIMILAR_TABLE_NAMES item linking CUSTOMER_ORDERS and NR_ORDERS (share token "ORDERS").
        boolean foundSimilar = items.stream().anyMatch(i ->
            "SIMILAR_TABLE_NAMES".equals(i.kind()) &&
            ((i.title().contains("CUSTOMER_ORDERS") && i.title().contains("NR_ORDERS"))));
        assertTrue(foundSimilar, "expected CUSTOMER_ORDERS ↔ NR_ORDERS to be flagged similar; items=" + items);
    }

    @Test
    void emitsMissingTableDescriptionForUndocumentedTable() {
        var items = service.compute("c1");
        boolean hit = items.stream().anyMatch(i ->
            "MISSING_TABLE_DESCRIPTION".equals(i.kind()) && "CUSTOMER_ORDERS".equals(i.targetTable()));
        assertTrue(hit, "expected MISSING_TABLE_DESCRIPTION for CUSTOMER_ORDERS; items=" + items);
    }

    @Test
    void summariseForFocusReturnsCompactBulletList() {
        String summary = service.summariseForFocus("c1");
        assertFalse(summary.isBlank(), "summary should be non-empty when ambiguity items exist");
        assertTrue(summary.contains("[SIMILAR_TABLE_NAMES]") || summary.contains("[MISSING_TABLE_DESCRIPTION]"),
            "summary should reference at least one ambiguity kind: " + summary);
        assertTrue(summary.length() <= 3500, "summary should be capped: " + summary.length());
    }

    @Test
    void documentedTablesAreNotMissingTable() {
        when(docRepo.findByConnectionId("c1")).thenReturn(List.of(
            doc(SchemaDocumentation.DocumentationType.TABLE, "CUSTOMER_ORDERS", null)
        ));
        var items = service.compute("c1");
        boolean hit = items.stream().anyMatch(i ->
            "MISSING_TABLE_DESCRIPTION".equals(i.kind()) && "CUSTOMER_ORDERS".equals(i.targetTable()));
        assertFalse(hit, "CUSTOMER_ORDERS now has a doc, should not be missing");
    }

    private static SchemaMetadata threeTableSchema() {
        SchemaMetadata m = new SchemaMetadata();
        m.setDbType("postgres");
        m.setTables(new ArrayList<>(List.of(
            table("CUSTOMER_ORDERS", List.of("id", "status", "guest_id")),
            table("NR_ORDERS", List.of("id", "status", "rate")),
            table("ORDER_LINE_ITEMS", List.of("id", "checkin", "checkout"))
        )));
        return m;
    }

    private static TableMetadata table(String name, List<String> cols) {
        TableMetadata t = new TableMetadata();
        t.setName(name);
        List<ColumnMetadata> columnMetas = new ArrayList<>();
        for (String c : cols) {
            ColumnMetadata cm = new ColumnMetadata();
            cm.setName(c);
            cm.setDataType("varchar");
            columnMetas.add(cm);
        }
        t.setColumns(columnMetas);
        return t;
    }

    private static SchemaDocumentation doc(SchemaDocumentation.DocumentationType type,
                                           String objectName,
                                           String parentObject) {
        SchemaDocumentation d = new SchemaDocumentation();
        d.setObjectType(type);
        d.setObjectName(objectName);
        d.setParentObject(parentObject);
        d.setDescription("test doc");
        return d;
    }
}
