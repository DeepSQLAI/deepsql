package com.dbaagent.service;

import com.dbaagent.dto.KeyCustomerInfo;
import com.dbaagent.dto.KeyCustomerResult;
import com.dbaagent.model.*;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.TableClassificationRepository;
import com.dbaagent.service.EnhancedSqlParserService.ColumnLiteralPair;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KeyCustomerServiceTest {

    private KeyCustomerService service;
    private SlowQueryHistoryService historyService;
    private EnhancedSqlParserService sqlParserService;
    private KeyColumnAnalysisRepository keyColumnRepo;
    private TableClassificationRepository classificationRepo;

    @BeforeEach
    void setUp() {
        historyService = mock(SlowQueryHistoryService.class);
        sqlParserService = mock(EnhancedSqlParserService.class);
        keyColumnRepo = mock(KeyColumnAnalysisRepository.class);
        classificationRepo = mock(TableClassificationRepository.class);
        service = new KeyCustomerService(historyService, sqlParserService, keyColumnRepo, classificationRepo);
    }

    // ==================== Masking Policy ====================

    @Test
    void masksSensitiveTableLevelColumns() {
        // Table with REGULATED sensitivity -> all values masked
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM users WHERE email = 'test@example.com'",
            "SELECT * FROM users WHERE email = ?", SlowQuery.Severity.CRITICAL, 500.0, 10L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("users").columnName("email").value("test@example.com").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1"))
            .thenReturn(List.of()); // No key column data -> include all columns

        TableClassification tc = new TableClassification();
        tc.setTableName("users");
        tc.setSensitivityLevel("REGULATED");
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1"))
            .thenReturn(List.of(tc));

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().getKeyCustomers().size());

        KeyCustomerInfo customer = result.get().getKeyCustomers().get(0);
        assertTrue(customer.getDisplayValue().startsWith("***("), "Should be masked: " + customer.getDisplayValue());
        assertFalse(customer.getDisplayValue().contains("test@example.com"));
    }

    @Test
    void masksColumnLevelSensitiveData() {
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM orders WHERE phone = '555-1234'",
            "SELECT * FROM orders WHERE phone = ?", SlowQuery.Severity.HIGH, 300.0, 5L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("orders").columnName("phone").value("555-1234").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1"))
            .thenReturn(List.of());

        TableClassification tc = new TableClassification();
        tc.setTableName("orders");
        tc.setSensitivityLevel("PUBLIC");
        tc.setSensitiveColumns(List.of(Map.of("column", "phone", "type", "PII_MEDIUM")));
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1"))
            .thenReturn(List.of(tc));

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        KeyCustomerInfo customer = result.get().getKeyCustomers().get(0);
        assertTrue(customer.getDisplayValue().startsWith("***("));
    }

    @Test
    void masksByColumnNamePattern() {
        // Column named "email" should be masked even without TableClassification
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM t WHERE email = 'foo@bar.com'",
            "SELECT * FROM t WHERE email = ?", SlowQuery.Severity.LOW, 100.0, 1L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("t").columnName("email").value("foo@bar.com").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        KeyCustomerInfo customer = result.get().getKeyCustomers().get(0);
        assertTrue(customer.getDisplayValue().startsWith("***("));
    }

    @Test
    void passesNonSensitiveNumericIds() {
        // Numeric customer_id in PUBLIC table -> NOT masked
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM bookings WHERE customer_id = 42",
            "SELECT * FROM bookings WHERE customer_id = ?", SlowQuery.Severity.MEDIUM, 200.0, 3L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("bookings").columnName("customer_id").value("42").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        KeyCustomerInfo customer = result.get().getKeyCustomers().get(0);
        assertEquals("42", customer.getDisplayValue());
    }

    @Test
    void truncatesLongValues() {
        String longValue = "a".repeat(120);
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM t WHERE val = '" + longValue + "'",
            "SELECT * FROM t WHERE val = ?", SlowQuery.Severity.LOW, 100.0, 1L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("t").columnName("val").value(longValue).operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        KeyCustomerInfo customer = result.get().getKeyCustomers().get(0);
        assertTrue(customer.getDisplayValue().contains("... (120 chars total)"));
        assertEquals(50 + "... (120 chars total)".length(), customer.getDisplayValue().length());
    }

    // ==================== Filtering ====================

    @Test
    void nullTableExcludedWhenTableNameFilterActive() {
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM bookings WHERE customer_id = 1 AND status = 'active'",
            "SELECT * FROM bookings WHERE customer_id = ? AND status = ?", SlowQuery.Severity.HIGH, 400.0, 2L)));

        // Two literals: one with table, one without
        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(
                ColumnLiteralPair.builder().tableName("bookings").columnName("customer_id").value("1").operation("=").build(),
                ColumnLiteralPair.builder().tableName(null).columnName("status").value("active").operation("=").build()
            ));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        // Filter by tableName "bookings" -> null-table row should be excluded
        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, "bookings");
        assertTrue(result.isPresent());
        assertEquals(1, result.get().getKeyCustomers().size());
        assertEquals("customer_id", result.get().getKeyCustomers().get(0).getColumnName());
    }

    // ==================== Deduplication ====================

    @Test
    void sameLiteralInOneQueryCountedOnce() {
        // Self-join or redundant WHERE: customer_id = 42 appears twice in one query
        setupAnalysis(List.of(buildQuery("q1",
            "SELECT * FROM bookings b JOIN customers h ON b.customer_id = h.id WHERE b.customer_id = 42 AND h.customer_id = 42",
            "SELECT * FROM bookings b JOIN customers h ON b.customer_id = h.id WHERE b.customer_id = ? AND h.customer_id = ?",
            SlowQuery.Severity.CRITICAL, 800.0, 5L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(
                ColumnLiteralPair.builder().tableName("bookings").columnName("customer_id").value("42").operation("=").build(),
                ColumnLiteralPair.builder().tableName("bookings").columnName("customer_id").value("42").operation("=").build()
            ));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        assertEquals(1, result.get().getKeyCustomers().size());
        // Should be counted once, not twice
        assertEquals(1, result.get().getKeyCustomers().get(0).getSlowQueryCount());
    }

    @Test
    void sameLiteralInTwoQueriesCountedTwice() {
        setupAnalysis(List.of(
            buildQuery("q1", "SELECT * FROM bookings WHERE customer_id = 42",
                "SELECT * FROM bookings WHERE customer_id = ?", SlowQuery.Severity.HIGH, 300.0, 2L),
            buildQuery("q2", "SELECT * FROM rooms WHERE customer_id = 42",
                "SELECT * FROM rooms WHERE customer_id = ?", SlowQuery.Severity.MEDIUM, 200.0, 1L)
        ));

        when(sqlParserService.extractWhereClauseLiterals("SELECT * FROM bookings WHERE customer_id = 42"))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("bookings").columnName("customer_id").value("42").operation("=").build()));
        when(sqlParserService.extractWhereClauseLiterals("SELECT * FROM rooms WHERE customer_id = 42"))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("bookings").columnName("customer_id").value("42").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        // Same bucket key (bookings|customer_id|42) -> 2 queries
        assertEquals(1, result.get().getKeyCustomers().size());
        assertEquals(2, result.get().getKeyCustomers().get(0).getSlowQueryCount());
    }

    // ==================== Empty/Missing ====================

    @Test
    void returnsEmptyWhenNoHistory() {
        when(historyService.getLatestHistory("conn1")).thenReturn(Optional.empty());
        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isEmpty());
    }

    @Test
    void matchingQueryIdsInCappedSet() {
        // Verify matchingQueryIds only contains IDs from capped queries
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM t WHERE id = 1",
            "SELECT * FROM t WHERE id = ?", SlowQuery.Severity.LOW, 100.0, 1L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("t").columnName("id").value("1").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        assertEquals(List.of("q1"), result.get().getKeyCustomers().get(0).getMatchingQueryIds());
    }

    @Test
    void worstQueryPreviewUsesNormalizedQuery() {
        setupAnalysis(List.of(buildQuery("q1", "SELECT * FROM users WHERE email = 'literal@test.com'",
            "SELECT * FROM users WHERE email = ?", SlowQuery.Severity.CRITICAL, 5000.0, 1L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(ColumnLiteralPair.builder()
                .tableName("users").columnName("email").value("literal@test.com").operation("=").build()));

        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1")).thenReturn(List.of());
        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        String preview = result.get().getKeyCustomers().get(0).getWorstQueryPreview();
        assertNotNull(preview);
        // Preview should use normalized (parameterized) query, not the sample with literals
        assertFalse(preview.contains("literal@test.com"), "Preview should not contain literal values");
        assertTrue(preview.contains("?") || preview.contains("email"), "Preview should be normalized");
    }

    // ==================== Key Column Filtering ====================

    @Test
    void filtersByKeyColumnAnalysis() {
        setupAnalysis(List.of(buildQuery("q1",
            "SELECT * FROM bookings WHERE customer_id = 42 AND temp_flag = 'x'",
            "SELECT * FROM bookings WHERE customer_id = ? AND temp_flag = ?",
            SlowQuery.Severity.MEDIUM, 200.0, 1L)));

        when(sqlParserService.extractWhereClauseLiterals(anyString()))
            .thenReturn(List.of(
                ColumnLiteralPair.builder().tableName("bookings").columnName("customer_id").value("42").operation("=").build(),
                ColumnLiteralPair.builder().tableName("bookings").columnName("temp_flag").value("x").operation("=").build()
            ));

        // Only customer_id is a key column
        KeyColumnAnalysis kca = new KeyColumnAnalysis();
        kca.setTableName("bookings");
        kca.setColumnName("customer_id");
        kca.setKeyType("TRUE_KEY");
        kca.setImportanceScore(BigDecimal.valueOf(90));
        when(keyColumnRepo.findByConnectionIdOrderByImportanceScoreDesc("conn1"))
            .thenReturn(List.of(kca));

        when(classificationRepo.findLatestByConnectionIdOrderByTableNameAsc("conn1")).thenReturn(List.of());

        Optional<KeyCustomerResult> result = service.analyze("conn1", 20, null);
        assertTrue(result.isPresent());
        // Only customer_id included; temp_flag filtered out
        assertEquals(1, result.get().getKeyCustomers().size());
        assertEquals("customer_id", result.get().getKeyCustomers().get(0).getColumnName());
    }

    // ==================== Helpers ====================

    private void setupAnalysis(List<SlowQuery> queries) {
        SlowQueryHistory history = new SlowQueryHistory();
        history.setConnectionId("conn1");
        history.setCreatedAt(LocalDateTime.now());

        SlowQueryAnalysis analysis = new SlowQueryAnalysis();
        analysis.setTopSlowQueries(queries);
        analysis.setTotalSlowQueries((long) queries.size());

        when(historyService.getLatestHistory("conn1")).thenReturn(Optional.of(history));
        when(historyService.getAnalysisData(history)).thenReturn(analysis);
    }

    private SlowQuery buildQuery(String queryId, String sampleQuery, String normalizedQuery,
                                  SlowQuery.Severity severity, double avgMs, long callCount) {
        SlowQuery q = new SlowQuery();
        q.setQueryId(queryId);
        q.setSampleQuery(sampleQuery);
        q.setNormalizedQuery(normalizedQuery);
        q.setSeverity(severity);
        q.setAvgExecutionTimeMs(avgMs);
        q.setCallCount(callCount);
        return q;
    }
}
