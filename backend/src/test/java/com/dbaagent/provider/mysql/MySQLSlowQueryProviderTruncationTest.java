package com.dbaagent.provider.mysql;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Mirror of PostgresSlowQueryProviderTruncationTest for MySQL. The
 * performance_schema digest text is bounded by
 * `performance_schema_max_sql_text_length` (default 1024B). Same
 * detection heuristic applies.
 */
class MySQLSlowQueryProviderTruncationTest {

    private final MySQLSlowQueryProvider provider = new MySQLSlowQueryProvider();

    private boolean looksTruncated(String queryText, int maxLength) throws Exception {
        Method m = MySQLSlowQueryProvider.class.getDeclaredMethod("looksTruncated", String.class, int.class);
        m.setAccessible(true);
        return (boolean) m.invoke(null, queryText, maxLength);
    }

    @Test
    void looksTruncated_flagsExactly1024CharDigestTextOnDefaultLimit() throws Exception {
        assertTrue(looksTruncated("x".repeat(1024), 1024));
    }

    @Test
    void looksTruncated_doesNotFlagShortDigestText() throws Exception {
        assertFalse(looksTruncated("SELECT 1", 1024));
    }

    @Test
    void looksTruncated_respectsACustomServerLimit() throws Exception {
        // performance_schema_max_sql_text_length is sometimes bumped to
        // 4096 or higher in production my.cnf.
        assertFalse(looksTruncated("x".repeat(1024), 4096));
        assertTrue(looksTruncated("x".repeat(4096), 4096));
    }

    @Test
    void looksTruncated_isNullSafe() throws Exception {
        assertFalse(looksTruncated(null, 1024));
        assertFalse(looksTruncated("anything", 0));
    }

    @Test
    void provider_returnsCorrectDatabaseType() {
        assertEquals("mysql", provider.getDatabaseType());
    }
}
