package com.dbaagent.provider.postgres;

import com.dbaagent.dto.TableFacts;
import com.dbaagent.service.migration.DdlStatementParser;
import org.junit.jupiter.api.*;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.*;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Testcontainers
class PostgresMigrationRiskVerificationTest {

    @Container
    static final PostgreSQLContainer<?> PG = new PostgreSQLContainer<>("postgres:18");

    private static final List<String> LOCK_STRENGTH = List.of(
            "AccessShareLock", "RowShareLock", "RowExclusiveLock", "ShareUpdateExclusiveLock",
            "ShareLock", "ShareRowExclusiveLock", "ExclusiveLock", "AccessExclusiveLock");

    private final DdlStatementParser parser = new DdlStatementParser();
    private final PostgresMigrationRiskProvider provider = new PostgresMigrationRiskProvider();

    private Connection conn;

    @BeforeEach
    void setUp() throws SQLException {
        conn = DriverManager.getConnection(PG.getJdbcUrl(), PG.getUsername(), PG.getPassword());
        try (Statement s = conn.createStatement()) {
            s.execute("DROP TABLE IF EXISTS child, t CASCADE");
            s.execute("CREATE TABLE t (id bigserial primary key, a text)");
            s.execute("INSERT INTO t (a) SELECT 'x' FROM generate_series(1,1000)");
            s.execute("CREATE TABLE child (id bigserial primary key, t_id bigint)");
            s.execute("INSERT INTO child (t_id) SELECT id FROM t LIMIT 500");
        }
    }

    @AfterEach
    void tearDown() throws SQLException { conn.close(); }

    @ParameterizedTest(name = "{0} -> rewrites={1}")
    @CsvSource({
        "ALTER TABLE t ADD COLUMN c text,                                  false",
        "ALTER TABLE t ADD COLUMN c text DEFAULT 'x',                      false",
        "ALTER TABLE t ADD COLUMN c timestamptz DEFAULT now(),             false",
        "ALTER TABLE t ADD COLUMN c uuid DEFAULT gen_random_uuid(),        true",
        "ALTER TABLE t ADD COLUMN c double precision DEFAULT random(),     true",
        "ALTER TABLE t ADD COLUMN c timestamptz DEFAULT clock_timestamp(), true",
        "ALTER TABLE t ALTER COLUMN a TYPE varchar(50),                    true",
        "ALTER TABLE t DROP COLUMN a,                                      false",
        "ALTER TABLE t ALTER COLUMN a SET NOT NULL,                        false"
    })
    void ruleTableMatchesEngineRewriteBehaviour(String sql, boolean expectedRewrite) throws SQLException {
        conn.setAutoCommit(false);
        long before = filenode("t");
        try (Statement s = conn.createStatement()) {
            s.execute(sql);
        }
        long after = filenode("t");
        conn.rollback();
        conn.setAutoCommit(true);

        boolean actuallyRewrote = before != after;
        assertThat(actuallyRewrote)
            .as("engine rewrite behaviour for: %s", sql)
            .isEqualTo(expectedRewrite);

        var facts = parser.parse(sql).orElseThrow();
        var report = provider.classify(facts, new TableFacts(1000L, 40_000L, false));
        assertThat(report.rewritesTable())
            .as("rule table claim vs engine for: %s", sql)
            .isEqualTo(actuallyRewrote);
    }

    // KNOWN RULE-TABLE LIMITATION (see task-3-report.md): widening within the same type
    // family (varchar(50) -> varchar(100)) does NOT rewrite in real Postgres, but
    // PostgresMigrationRiskProvider.alterType reports rewritesTable=true for every
    // ALTER COLUMN TYPE, because DdlFacts carries only the new type, never the old one,
    // so the provider cannot tell this case apart from a real rewrite. This test asserts
    // the engine truth and the current (over-conservative) rule claim side by side rather
    // than silently agreeing with a rule that cannot see what it would need to see.
    @Test
    void alterColumnType_wideningVarcharDoesNotRewrite_butRuleCannotKnowThat() throws SQLException {
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE t ALTER COLUMN a TYPE varchar(50)");
        }
        conn.setAutoCommit(false);
        long before = filenode("t");
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE t ALTER COLUMN a TYPE varchar(100)");
        }
        long after = filenode("t");
        conn.rollback();
        conn.setAutoCommit(true);

        assertThat(before).as("engine truth: widening varchar(50)->varchar(100) does not rewrite")
                .isEqualTo(after);

        var facts = parser.parse("ALTER TABLE t ALTER COLUMN a TYPE varchar(100)").orElseThrow();
        var report = provider.classify(facts, new TableFacts(1000L, 40_000L, false));
        assertThat(report.rewritesTable())
                .as("known limitation: DdlFacts has no old column type, so the rule cannot "
                        + "distinguish this widening from a real rewrite and conservatively "
                        + "claims true")
                .isTrue();
    }

    @Test
    void addForeignKeyLocksBothTables() throws SQLException {
        conn.setAutoCommit(false);
        try (Statement s = conn.createStatement()) {
            s.execute("ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id)");
        }
        assertThat(strongestLock("t")).isEqualTo("ShareRowExclusiveLock");
        assertThat(strongestLock("child")).isEqualTo("ShareRowExclusiveLock");
        conn.rollback();
        conn.setAutoCommit(true);

        var facts = parser.parse(
            "ALTER TABLE child ADD CONSTRAINT fk FOREIGN KEY (t_id) REFERENCES t(id)").orElseThrow();
        var report = provider.classify(facts, new TableFacts(1000L, 40_000L, false));
        assertThat(report.locks()).extracting(l -> l.table())
                .containsExactlyInAnyOrder("child", "t");
    }

    @Test
    void addColumnNotNullWithoutDefaultReallyFails() {
        assertThatThrownBy(() -> {
            try (Statement s = conn.createStatement()) {
                s.execute("ALTER TABLE t ADD COLUMN c5 text NOT NULL");
            }
        }).isInstanceOf(SQLException.class).hasMessageContaining("null values");
    }

    @Test
    void volatilityClassificationMatchesCatalog() throws SQLException {
        assertThat(volatility("now")).isEqualTo("s");
        assertThat(volatility("random")).isEqualTo("v");
        assertThat(volatility("gen_random_uuid")).isEqualTo("v");
        assertThat(volatility("clock_timestamp")).isEqualTo("v");
    }

    private long filenode(String table) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement("SELECT pg_relation_filenode(?::regclass)")) {
            p.setString(1, table);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getLong(1); }
        }
    }

    private String strongestLock(String table) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT mode FROM pg_locks WHERE relation = ?::regclass")) {
            p.setString(1, table);
            try (ResultSet rs = p.executeQuery()) {
                String strongest = null;
                while (rs.next()) {
                    String m = rs.getString(1);
                    if (strongest == null || LOCK_STRENGTH.indexOf(m) > LOCK_STRENGTH.indexOf(strongest)) {
                        strongest = m;
                    }
                }
                return strongest;
            }
        }
    }

    private String volatility(String fn) throws SQLException {
        try (PreparedStatement p = conn.prepareStatement(
                "SELECT provolatile FROM pg_proc WHERE proname = ? LIMIT 1")) {
            p.setString(1, fn);
            try (ResultSet rs = p.executeQuery()) { rs.next(); return rs.getString(1); }
        }
    }
}
