package com.dbaagent.service;

import com.dbaagent.dto.JoinRelationshipDetail;
import com.dbaagent.repository.InferredTableRelationshipRepository;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.repository.QueryLineageRepository;
import com.dbaagent.repository.SlowQueryHistoryRepository;
import com.dbaagent.service.brain.keycolumn.JoinRelationshipInferenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;

/**
 * Unit tests for JoinRelationshipInferenceService.
 * Tests SQL JOIN extraction and FK direction detection logic.
 */
@DisplayName("JoinRelationshipInferenceService Unit Tests")
class JoinRelationshipInferenceServiceTest {

    private JoinRelationshipInferenceService service;

    @BeforeEach
    void setUp() {
        service = new JoinRelationshipInferenceService(
            mock(InferredTableRelationshipRepository.class),
            mock(QueryLineageRepository.class),
            mock(KeyColumnAnalysisRepository.class),
            mock(SlowQueryHistoryRepository.class),
            new ObjectMapper(),
            mock(QueryExecutorService.class),
            mock(SchemaScannerService.class)
        );
    }

    // ==================== Simple JOIN Tests ====================

    @Test
    @DisplayName("Extract simple INNER JOIN relationship")
    void testSimpleInnerJoin() {
        String sql = "SELECT * FROM orders o JOIN users u ON o.user_id = u.id";

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(1, relationships.size());
        JoinRelationshipDetail rel = relationships.get(0);

        // user_id is FK pointing to users.id
        assertEquals("orders", rel.getSourceTable());
        assertEquals("user_id", rel.getSourceColumn());
        assertEquals("users", rel.getTargetTable());
        assertEquals("id", rel.getTargetColumn());
        assertFalse(rel.isSelfJoin());
    }

    @Test
    @DisplayName("Extract LEFT JOIN relationship")
    void testLeftJoin() {
        String sql = "SELECT * FROM products p LEFT JOIN categories c ON p.category_id = c.id";

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(1, relationships.size());
        JoinRelationshipDetail rel = relationships.get(0);

        assertEquals("products", rel.getSourceTable());
        assertEquals("category_id", rel.getSourceColumn());
        assertEquals("categories", rel.getTargetTable());
        assertEquals("id", rel.getTargetColumn());
    }

    @Test
    @DisplayName("Extract multiple JOINs from single query")
    void testMultipleJoins() {
        String sql = """
            SELECT o.id, u.name, p.name
            FROM orders o
            JOIN users u ON o.user_id = u.id
            JOIN products p ON o.product_id = p.id
            """;

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(2, relationships.size());

        // First relationship: orders.user_id -> users.id
        assertTrue(relationships.stream().anyMatch(r ->
            r.getSourceTable().equals("orders") &&
            r.getSourceColumn().equals("user_id") &&
            r.getTargetTable().equals("users")));

        // Second relationship: orders.product_id -> products.id
        assertTrue(relationships.stream().anyMatch(r ->
            r.getSourceTable().equals("orders") &&
            r.getSourceColumn().equals("product_id") &&
            r.getTargetTable().equals("products")));
    }

    // ==================== Self-Join Tests ====================

    @Test
    @DisplayName("Detect self-join relationship")
    void testSelfJoin() {
        String sql = "SELECT e.name, m.name as manager FROM employees e JOIN employees m ON e.manager_id = m.id";

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(1, relationships.size());
        JoinRelationshipDetail rel = relationships.get(0);

        assertTrue(rel.isSelfJoin());
        assertEquals("employees", rel.getSourceTable());
        assertEquals("manager_id", rel.getSourceColumn());
        assertEquals("employees", rel.getTargetTable());
        assertEquals("id", rel.getTargetColumn());
    }

    // ==================== FK Direction Detection Tests ====================

    @Test
    @DisplayName("FK direction: column with _id suffix is FK")
    void testFKDirectionWithIdSuffix() {
        String[] result = service.determineFKDirection("orders", "customer_id", "customers", "id");

        assertEquals("orders", result[0]);      // source table (FK side)
        assertEquals("customer_id", result[1]); // source column
        assertEquals("customers", result[2]);   // target table (PK side)
        assertEquals("id", result[3]);          // target column
    }

    @Test
    @DisplayName("FK direction: column with _key suffix is FK")
    void testFKDirectionWithKeySuffix() {
        String[] result = service.determineFKDirection("line_items", "product_key", "products", "id");

        assertEquals("line_items", result[0]);
        assertEquals("product_key", result[1]);
        assertEquals("products", result[2]);
        assertEquals("id", result[3]);
    }

    @Test
    @DisplayName("FK direction: column with _fk suffix is FK")
    void testFKDirectionWithFkSuffix() {
        String[] result = service.determineFKDirection("invoices", "order_fk", "orders", "id");

        assertEquals("invoices", result[0]);
        assertEquals("order_fk", result[1]);
        assertEquals("orders", result[2]);
        assertEquals("id", result[3]);
    }

    @Test
    @DisplayName("FK direction: PK column named 'id' is target")
    void testFKDirectionWithPKNamedId() {
        // Even if order is reversed in SQL, should detect correctly
        String[] result = service.determineFKDirection("users", "id", "orders", "user_id");

        assertEquals("orders", result[0]);     // FK side
        assertEquals("user_id", result[1]);
        assertEquals("users", result[2]);      // PK side
        assertEquals("id", result[3]);
    }

    @Test
    @DisplayName("FK direction: column name matching target table")
    void testFKDirectionWithTableNameMatch() {
        // user_id should point to users table
        String[] result = service.determineFKDirection("posts", "user_id", "users", "id");

        assertEquals("posts", result[0]);
        assertEquals("user_id", result[1]);
        assertEquals("users", result[2]);
        assertEquals("id", result[3]);
    }

    // ==================== Confidence Score Tests ====================

    @Test
    @DisplayName("Confidence score: single join gives base score")
    void testConfidenceScoreSingleJoin() {
        BigDecimal score = service.calculateConfidenceScore(1, 1);

        // 1 * 5 (join) + 1 * 10 (distinct) = 15
        assertEquals(0, score.compareTo(BigDecimal.valueOf(15.00)));
    }

    @Test
    @DisplayName("Confidence score: multiple joins increase score")
    void testConfidenceScoreMultipleJoins() {
        BigDecimal score = service.calculateConfidenceScore(5, 2);

        // 5 * 5 (join, max 50) + 2 * 10 (distinct) = 25 + 20 = 45
        assertEquals(0, score.compareTo(BigDecimal.valueOf(45.00)));
    }

    @Test
    @DisplayName("Confidence score: 3+ distinct queries adds consistency bonus")
    void testConfidenceScoreWithConsistencyBonus() {
        BigDecimal score = service.calculateConfidenceScore(5, 3);

        // 5 * 5 = 25 (join) + 3 * 10 = 30 (distinct) + 20 (bonus) = 75
        assertEquals(0, score.compareTo(BigDecimal.valueOf(75.00)));
    }

    @Test
    @DisplayName("Confidence score: capped at 100")
    void testConfidenceScoreCappedAt100() {
        BigDecimal score = service.calculateConfidenceScore(100, 100);

        // Should be capped at 100
        assertEquals(0, score.compareTo(BigDecimal.valueOf(100.00)));
    }

    @Test
    @DisplayName("Confidence score: high join frequency reaches max")
    void testConfidenceScoreMaxJoinComponent() {
        BigDecimal score = service.calculateConfidenceScore(20, 1);

        // 20 * 5 = 100, but capped at 50 (join) + 1 * 10 (distinct) = 60
        assertEquals(0, score.compareTo(BigDecimal.valueOf(60.00)));
    }

    // ==================== Edge Cases ====================

    @Test
    @DisplayName("Handle empty SQL gracefully")
    void testEmptySql() {
        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships("");
        assertTrue(relationships.isEmpty());
    }

    @Test
    @DisplayName("Handle null SQL gracefully")
    void testNullSql() {
        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(null);
        assertTrue(relationships.isEmpty());
    }

    @Test
    @DisplayName("Handle SELECT without JOIN")
    void testSelectWithoutJoin() {
        String sql = "SELECT * FROM users WHERE id = 1";

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertTrue(relationships.isEmpty());
    }

    @Test
    @DisplayName("Handle complex nested query")
    void testNestedQuery() {
        String sql = """
            SELECT * FROM orders o
            JOIN (SELECT id, name FROM users WHERE active = true) u
            ON o.user_id = u.id
            """;

        // Should handle gracefully even if it can't extract from subquery
        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        // May or may not extract depending on parser capability, but shouldn't throw
        assertNotNull(relationships);
    }

    @Test
    @DisplayName("Handle implicit join in WHERE clause")
    void testImplicitJoinInWhereClause() {
        String sql = "SELECT * FROM orders o, users u WHERE o.user_id = u.id";

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(1, relationships.size());
        JoinRelationshipDetail rel = relationships.get(0);
        assertEquals("orders", rel.getSourceTable());
        assertEquals("user_id", rel.getSourceColumn());
        assertEquals("users", rel.getTargetTable());
        assertEquals("id", rel.getTargetColumn());
    }

    @Test
    @DisplayName("Handle JOIN with compound condition")
    void testJoinWithCompoundCondition() {
        String sql = """
            SELECT * FROM orders o
            JOIN order_items oi ON o.id = oi.order_id AND o.tenant_id = oi.tenant_id
            """;

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        // Should extract both join conditions
        assertEquals(2, relationships.size());
    }

    // ==================== Real-world Query Patterns ====================

    @Test
    @DisplayName("Extract from typical e-commerce query")
    void testEcommerceQuery() {
        String sql = """
            SELECT
                o.order_id, o.order_date,
                c.customer_name, c.email,
                p.product_name, p.price,
                oi.quantity
            FROM orders o
            INNER JOIN customers c ON o.customer_id = c.id
            INNER JOIN order_items oi ON o.order_id = oi.order_id
            INNER JOIN products p ON oi.product_id = p.id
            WHERE o.order_date >= '2024-01-01'
            """;

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(3, relationships.size());

        // Verify customer relationship
        assertTrue(relationships.stream().anyMatch(r ->
            r.getSourceTable().equals("orders") &&
            r.getSourceColumn().equals("customer_id") &&
            r.getTargetTable().equals("customers")));

        // Verify order_items relationship
        assertTrue(relationships.stream().anyMatch(r ->
            r.getSourceTable().equals("order_items") &&
            r.getSourceColumn().equals("order_id") &&
            r.getTargetTable().equals("orders")));

        // Verify products relationship
        assertTrue(relationships.stream().anyMatch(r ->
            r.getSourceTable().equals("order_items") &&
            r.getSourceColumn().equals("product_id") &&
            r.getTargetTable().equals("products")));
    }

    @Test
    @DisplayName("Extract from analytics query with multiple dimension joins")
    void testAnalyticsQuery() {
        // Use more typical dimension key patterns where FK ends with _id
        String sql = """
            SELECT
                f.sale_amount, f.quantity,
                d.date, d.month, d.year,
                s.store_name, s.region,
                p.product_name, p.category
            FROM fact_sales f
            JOIN dim_date d ON f.date_id = d.id
            JOIN dim_store s ON f.store_id = s.id
            JOIN dim_product p ON f.product_id = p.id
            """;

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(3, relationships.size());

        // All should have fact_sales as source (FK side) since they have _id suffix
        assertTrue(relationships.stream().allMatch(r -> r.getSourceTable().equals("fact_sales")));
    }

    @Test
    @DisplayName("Handle ambiguous key columns (same name on both sides)")
    void testAmbiguousKeyColumns() {
        // When both columns have the same suffix pattern, direction is determined by other heuristics
        String sql = """
            SELECT * FROM fact_sales f
            JOIN dim_date d ON f.date_key = d.date_key
            """;

        List<JoinRelationshipDetail> relationships = service.extractJoinRelationships(sql);

        assertEquals(1, relationships.size());
        JoinRelationshipDetail rel = relationships.get(0);

        // Both tables and columns are extracted, direction may vary based on heuristics
        assertNotNull(rel.getSourceTable());
        assertNotNull(rel.getTargetTable());
        assertNotNull(rel.getSourceColumn());
        assertNotNull(rel.getTargetColumn());
        assertTrue(rel.isValid());
    }
}
