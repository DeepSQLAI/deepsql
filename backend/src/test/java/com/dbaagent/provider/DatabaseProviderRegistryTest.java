package com.dbaagent.provider;

import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.mysql.MySQLDialect;
import com.dbaagent.provider.postgres.PostgresDialect;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DatabaseProviderRegistryTest {

    private DatabaseProviderRegistry registry;
    private MySQLDialect mysqlDialect;
    private PostgresDialect postgresDialect;

    @BeforeEach
    void setUp() {
        mysqlDialect = mock(MySQLDialect.class);
        postgresDialect = mock(PostgresDialect.class);

        when(mysqlDialect.getCanonicalName()).thenReturn("mysql");
        when(mysqlDialect.getAliases()).thenReturn(new HashSet<>(Arrays.asList("mysql", "mariadb")));

        when(postgresDialect.getCanonicalName()).thenReturn("postgres");
        when(postgresDialect.getAliases()).thenReturn(new HashSet<>(Arrays.asList("postgres", "postgresql", "pg")));

        List<DatabaseDialect> dialects = Arrays.asList(mysqlDialect, postgresDialect);
        registry = new DatabaseProviderRegistry(dialects);
    }

    @Test
    void getDialect_withExactMatch_returnsDialect() {
        DatabaseDialect dialect = registry.getDialect("mysql");
        assertNotNull(dialect);
        assertEquals("mysql", dialect.getCanonicalName());
    }

    @Test
    void getDialect_withAlias_returnsDialect() {
        DatabaseDialect dialect = registry.getDialect("postgresql");
        assertNotNull(dialect);
        assertEquals("postgres", dialect.getCanonicalName());
    }

    @Test
    void getDialect_withPgAlias_returnsPostgres() {
        DatabaseDialect dialect = registry.getDialect("pg");
        assertNotNull(dialect);
        assertEquals("postgres", dialect.getCanonicalName());
    }

    @Test
    void getDialect_withMariaDbAlias_returnsMySQL() {
        DatabaseDialect dialect = registry.getDialect("mariadb");
        assertNotNull(dialect);
        assertEquals("mysql", dialect.getCanonicalName());
    }

    @Test
    void getDialect_caseInsensitive() {
        DatabaseDialect dialect1 = registry.getDialect("MySQL");
        DatabaseDialect dialect2 = registry.getDialect("POSTGRES");
        DatabaseDialect dialect3 = registry.getDialect("PostgreSQL");

        assertNotNull(dialect1);
        assertNotNull(dialect2);
        assertNotNull(dialect3);
    }

    @Test
    void getDialect_withUnsupportedType_throwsException() {
        assertThrows(UnsupportedDatabaseException.class, () -> {
            registry.getDialect("oracle");
        });
    }

    @Test
    void getDialect_withNullType_throwsException() {
        assertThrows(UnsupportedDatabaseException.class, () -> {
            registry.getDialect(null);
        });
    }

    @Test
    void getDialect_withEmptyType_throwsException() {
        assertThrows(UnsupportedDatabaseException.class, () -> {
            registry.getDialect("");
        });
    }

    @Test
    void getSupportedTypes_returnsAllTypes() {
        Set<String> types = registry.getSupportedTypes();

        assertNotNull(types);
        assertTrue(types.contains("mysql"));
        assertTrue(types.contains("postgres"));
    }

    @Test
    void isSupported_withSupportedType_returnsTrue() {
        assertTrue(registry.isSupported("mysql"));
        assertTrue(registry.isSupported("postgres"));
        assertTrue(registry.isSupported("postgresql"));
        assertTrue(registry.isSupported("mariadb"));
        assertTrue(registry.isSupported("pg"));
    }

    @Test
    void isSupported_withUnsupportedType_returnsFalse() {
        assertFalse(registry.isSupported("oracle"));
        assertFalse(registry.isSupported("sqlserver"));
        assertFalse(registry.isSupported("mongodb"));
    }

    @Test
    void isSupported_caseInsensitive() {
        assertTrue(registry.isSupported("MySQL"));
        assertTrue(registry.isSupported("POSTGRES"));
        assertTrue(registry.isSupported("PostgreSQL"));
    }

    @Test
    void getDialect_withAuroraMySqlAlias_returnsMySQL() {
        // Setup MySQL dialect with Aurora aliases
        MySQLDialect auroraMysqlDialect = mock(MySQLDialect.class);
        when(auroraMysqlDialect.getCanonicalName()).thenReturn("mysql");
        when(auroraMysqlDialect.getAliases()).thenReturn(new HashSet<>(Arrays.asList(
            "mysql", "mariadb", "aurora-mysql", "amazon-aurora-mysql"
        )));

        DatabaseProviderRegistry testRegistry = new DatabaseProviderRegistry(
            Collections.singletonList(auroraMysqlDialect)
        );

        // Test Aurora-MySQL aliases resolve to MySQL
        DatabaseDialect dialect = testRegistry.getDialect("aurora-mysql");
        assertNotNull(dialect);
        assertEquals("mysql", dialect.getCanonicalName());

        DatabaseDialect amazonDialect = testRegistry.getDialect("amazon-aurora-mysql");
        assertNotNull(amazonDialect);
        assertEquals("mysql", amazonDialect.getCanonicalName());
    }

    @Test
    void registerDialect_withAliasCollision_logsWarning() {
        // Create first dialect claiming alias "common"
        DatabaseDialect dialect1 = mock(DatabaseDialect.class);
        when(dialect1.getCanonicalName()).thenReturn("dialect1");
        when(dialect1.getAliases()).thenReturn(new HashSet<>(Arrays.asList("dialect1", "common")));
        when(dialect1.getDisplayName()).thenReturn("Dialect 1");

        // Create second dialect also claiming alias "common"
        DatabaseDialect dialect2 = mock(DatabaseDialect.class);
        when(dialect2.getCanonicalName()).thenReturn("dialect2");
        when(dialect2.getAliases()).thenReturn(new HashSet<>(Arrays.asList("dialect2", "common")));
        when(dialect2.getDisplayName()).thenReturn("Dialect 2");

        // Register both - second should overwrite first for "common" alias
        DatabaseProviderRegistry collisionRegistry = new DatabaseProviderRegistry(
            Arrays.asList(dialect1, dialect2)
        );

        // The alias "common" should now point to dialect2 (last one registered)
        DatabaseDialect resolved = collisionRegistry.getDialect("common");
        assertEquals("dialect2", resolved.getCanonicalName());

        // Individual canonical names should still work
        assertEquals("dialect1", collisionRegistry.getDialect("dialect1").getCanonicalName());
        assertEquals("dialect2", collisionRegistry.getDialect("dialect2").getCanonicalName());
    }

    @Test
    void getSupportedTypes_returnsCorrectCount() {
        // The registry should report 2 unique dialects, not count aliases
        Set<String> types = registry.getSupportedTypes();
        assertEquals(2, types.size());
        assertTrue(types.contains("mysql"));
        assertTrue(types.contains("postgres"));
    }
}
