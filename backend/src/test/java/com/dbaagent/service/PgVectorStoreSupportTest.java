package com.dbaagent.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class PgVectorStoreSupportTest {

    @Test
    void determineAnnIndexModeUsesVectorHnswForSmallEmbeddings() {
        assertEquals(
                PgVectorStoreSupport.AnnIndexMode.VECTOR_HNSW,
                PgVectorStoreSupport.determineAnnIndexMode(1536, false)
        );
    }

    @Test
    void determineAnnIndexModeUsesHalfvecHnswFor3072WhenSupported() {
        assertEquals(
                PgVectorStoreSupport.AnnIndexMode.HALFVEC_HNSW,
                PgVectorStoreSupport.determineAnnIndexMode(3072, true)
        );
    }

    @Test
    void determineAnnIndexModeFallsBackToNoAnnWhenHalfvecUnavailable() {
        assertEquals(
                PgVectorStoreSupport.AnnIndexMode.NONE,
                PgVectorStoreSupport.determineAnnIndexMode(3072, false)
        );
    }

    @Test
    void createAnnIndexSqlUsesHalfvecExpressionForHighDimensionalEmbeddings() {
        String sql = PgVectorStoreSupport.createAnnIndexSql(3072, true);

        assertTrue(sql.contains("halfvec(3072)"));
        assertTrue(sql.contains("halfvec_cosine_ops"));
    }

    @Test
    void distanceExpressionUsesHalfvecCastWhenNeeded() {
        String distanceExpression = PgVectorStoreSupport.distanceExpression(3072, true, "?");

        assertEquals("((embedding)::halfvec(3072)) <=> ?::halfvec(3072)", distanceExpression);
    }

    @Test
    void toVectorLiteralReturnsNullWhenDimensionsDoNotMatch() {
        assertNull(PgVectorStoreSupport.toVectorLiteral(List.of(1.0f, 2.0f), 3072));
    }

    @Test
    void toVectorLiteralSanitizesInvalidNumbers() {
        String literal = PgVectorStoreSupport.toVectorLiteral(List.of(1.0f, Float.NaN, Float.POSITIVE_INFINITY), 3);

        assertEquals("[1.0,0,0]", literal);
    }
}
