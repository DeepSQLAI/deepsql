package com.dbaagent.provider.mysql;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.Permission;
import com.dbaagent.model.QueryRequest;
import com.dbaagent.model.QueryResult;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.repository.KeyColumnAnalysisRepository;
import com.dbaagent.service.CacheMetricsService;
import com.dbaagent.service.ConnectionService;
import com.dbaagent.service.CredentialService;
import com.dbaagent.service.QueryExecutionContext;
import com.dbaagent.service.QueryExecutionPolicyException;
import com.dbaagent.service.QueryExecutionPolicyService;
import com.dbaagent.service.QueryExecutorService;
import com.dbaagent.service.QueryLineageService;
import com.dbaagent.service.RunningQueryRegistry;
import com.dbaagent.service.SchemaSnapshotService;
import com.dbaagent.service.UserDataAccessPolicyService;
import com.dbaagent.service.security.AccessControlService;
import com.dbaagent.service.telemetry.TelemetryCounters;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.cache.CacheManager;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The production guardrails against a real MySQL server, through the real executor and
 * policy. Mocks stand in only for credential lookup and Spring wiring.
 *
 * <p>Skipped unless {@code DEEPSQL_MYSQL_TEST_URL} points at a disposable server, e.g.
 * {@code jdbc:mysql://127.0.0.1:33084/?user=root&allowPublicKeyRetrieval=true&useSSL=false}.
 * It creates and drops its own schema, and toggles {@code @@global.read_only} on that
 * server, so never point it at a shared database.
 */
@EnabledIfEnvironmentVariable(named = "DEEPSQL_MYSQL_TEST_URL", matches = ".+")
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MySQLGuardrailLiveTest {

    private static final String SCHEMA = "deepsql_guardrail_test";
    private static final String CONN_ID = "live-mysql";

    private final MySQLQueryExecutionProvider provider = new MySQLQueryExecutionProvider();
    private String baseUrl;
    private HikariDataSource pool;
    private QueryExecutorService executor;

    @BeforeAll
    void setUp() throws Exception {
        baseUrl = System.getenv("DEEPSQL_MYSQL_TEST_URL");
        try (Connection c = DriverManager.getConnection(baseUrl); Statement s = c.createStatement()) {
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
            s.execute("CREATE DATABASE " + SCHEMA);
        }

        // One connection, so every run reuses it: what one run leaves behind, the next sees.
        HikariConfig config = new HikariConfig();
        config.setJdbcUrl(schemaUrl());
        config.setMaximumPoolSize(1);
        // The production pool config, not a copy of it: the eviction bug lived here.
        ConnectionService.applyGuardrails(config, provider);
        pool = new HikariDataSource(config);

        ConnectionRequest request = new ConnectionRequest();
        request.setDbType("mysql");
        CredentialService credentials = mock(CredentialService.class);
        when(credentials.getDecryptedConnection(CONN_ID)).thenReturn(request);
        ConnectionService connections = mock(ConnectionService.class);
        when(connections.getConnection(CONN_ID, request)).thenAnswer(inv -> pool.getConnection());

        DatabaseDialect dialect = mock(DatabaseDialect.class);
        when(dialect.queryExecution()).thenReturn(provider);
        DatabaseProviderRegistry registry = mock(DatabaseProviderRegistry.class);
        when(registry.getDialect(anyString())).thenReturn(dialect);
        when(registry.getCanonicalName(anyString())).thenReturn("mysql");

        AccessControlService access = mock(AccessControlService.class);
        when(access.hasPermission(Permission.EXECUTE_DDL)).thenReturn(true);
        UserDataAccessPolicyService dataPolicy = mock(UserDataAccessPolicyService.class);
        when(dataPolicy.redactResult(any(), any(), any())).thenAnswer(inv -> inv.getArgument(1));

        executor = new QueryExecutorService(
            connections, credentials, mock(SchemaSnapshotService.class), mock(CacheManager.class),
            mock(CacheMetricsService.class), mock(QueryLineageService.class), registry,
            new QueryExecutionPolicyService(registry, access), dataPolicy, mock(TelemetryCounters.class),
            mock(KeyColumnAnalysisRepository.class), new RunningQueryRegistry());
    }

    @AfterAll
    void tearDown() throws Exception {
        if (pool != null) {
            pool.close();
        }
        try (Connection c = DriverManager.getConnection(baseUrl); Statement s = c.createStatement()) {
            s.execute("SET GLOBAL read_only = 0");
            s.execute("DROP DATABASE IF EXISTS " + SCHEMA);
        }
    }

    @BeforeEach
    void freshTable() throws Exception {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.execute("DROP TABLE IF EXISTS customers");
            s.execute("CREATE TABLE customers (id INT PRIMARY KEY, email VARCHAR(255) NULL, "
                + "contact VARCHAR(255) NULL, note VARCHAR(50) NULL) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4");
            s.execute("INSERT INTO customers VALUES (1,'a@x.io','1',NULL),(2,'b@x.io','2',NULL),(3,'c@x.io','3',NULL)");
        }
    }

    // ---- helpers ---------------------------------------------------------------------

    private String schemaUrl() {
        int q = baseUrl.indexOf('?');
        return q < 0 ? baseUrl + SCHEMA : baseUrl.substring(0, q) + SCHEMA + baseUrl.substring(q);
    }

    private Connection raw() throws SQLException {
        return DriverManager.getConnection(schemaUrl());
    }

    private QueryResult run(String sql, QueryExecutionContext context) throws SQLException {
        return executor.executeQuery(CONN_ID, new QueryRequest(sql, null, 240), context);
    }

    private static QueryExecutionContext confirmedDba() {
        return QueryExecutionContext.editor("dba", true, true);
    }

    private static String cell(QueryResult result, int col) {
        return String.valueOf(result.getRows().get(0).get(col));
    }

    private String columnDefinition(String column) throws SQLException {
        try (Connection c = raw(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
            "SELECT COLUMN_TYPE, IS_NULLABLE FROM information_schema.COLUMNS WHERE TABLE_SCHEMA = '" + SCHEMA
                + "' AND TABLE_NAME = 'customers' AND COLUMN_NAME = '" + column + "'")) {
            rs.next();
            return rs.getString(1) + " " + rs.getString(2);
        }
    }

    // ---- the incident ----------------------------------------------------------------

    @Test
    void theServerDefaultIsToWaitAYearForAMetadataLock() throws Exception {
        // The root cause, measured rather than recalled.
        try (Connection c = DriverManager.getConnection(baseUrl); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT @@global.lock_wait_timeout")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(31_536_000L);
        }
    }

    @Test
    void aBlockedAlterGivesUpAfterFiveSecondsAndWritersQueuedBehindItGoThrough() throws Exception {
        // An open transaction holds a shared metadata lock, as an app session does.
        try (Connection holder = raw()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.executeQuery("SELECT * FROM customers WHERE id = 1").close();
            }

            long alterStart = System.nanoTime();
            CompletableFuture<Throwable> alter = CompletableFuture.supplyAsync(() -> {
                try {
                    run("ALTER TABLE customers MODIFY email VARCHAR(512) NOT NULL", confirmedDba());
                    return null;
                } catch (Throwable t) {
                    return t;
                }
            });

            // A writer arriving while the ALTER waits queues behind its pending exclusive
            // lock — the pile-up. It has no guard of its own (an app session).
            TimeUnit.SECONDS.sleep(1);
            long writerStart = System.nanoTime();
            CompletableFuture<Void> writer = CompletableFuture.runAsync(() -> {
                try (Connection w = raw(); Statement s = w.createStatement()) {
                    s.executeUpdate("INSERT INTO customers VALUES (4,'d@x.io','4',NULL)");
                } catch (SQLException e) {
                    throw new RuntimeException(e);
                }
            });

            Throwable alterError = alter.get(30, TimeUnit.SECONDS);
            double alterSeconds = (System.nanoTime() - alterStart) / 1e9;
            writer.get(30, TimeUnit.SECONDS);
            double writerSeconds = (System.nanoTime() - writerStart) / 1e9;
            holder.rollback();

            System.out.printf("[live] ALTER gave up after %.2fs; queued writer finished after %.2fs%n",
                alterSeconds, writerSeconds);
            assertThat(alterError).isInstanceOf(QueryExecutionPolicyException.class);
            assertThat(((QueryExecutionPolicyException) alterError).getErrorCode())
                .isEqualTo(QueryExecutionPolicyException.DB_GUARDRAIL_TRIPPED);
            assertThat(alterError.getMessage()).contains("5 seconds");
            assertThat(alterSeconds).isBetween(4.5, 9.0);
            // The writer waited (it really was queued) but only until the ALTER gave up.
            assertThat(writerSeconds).isBetween(2.0, 9.0);
            assertThat(columnDefinition("email")).isEqualTo("varchar(255) YES");
        }
    }

    @Test
    void theIncidentAlterRunsOnlineWhenNothingHoldsTheTable() throws Exception {
        // INSTANT cannot change NULL-ability: 1846, then INPLACE with LOCK=NONE succeeds.
        run("ALTER TABLE customers MODIFY email VARCHAR(512) NOT NULL", confirmedDba());
        assertThat(columnDefinition("email")).isEqualTo("varchar(512) NO");
    }

    @Test
    void anInstantChangeRunsInstant() throws Exception {
        run("ALTER TABLE customers ADD COLUMN tier TINYINT NULL", confirmedDba());
        assertThat(columnDefinition("tier")).isEqualTo("tinyint YES");
    }

    @Test
    void aChangeThatCannotRunOnlineChangesNothingAndSaysWhy() throws Exception {
        // INT -> BIGINT needs a table copy: refused by INSTANT and by INPLACE.
        QueryExecutionPolicyException e = assertThrows(QueryExecutionPolicyException.class,
            () -> run("ALTER TABLE customers MODIFY id BIGINT", confirmedDba()));
        System.out.println("[live] " + e.getMessage());
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.DB_GUARDRAIL_TRIPPED);
        assertThat(e.getMessage()).contains("gh-ost");
        assertThat(columnDefinition("id")).isEqualTo("int NO");
    }

    // ---- session guards --------------------------------------------------------------

    @Test
    void everyPooledConnectionStartsWithTheLockGuard() throws Exception {
        try (Connection c = pool.getConnection(); Statement s = c.createStatement();
             ResultSet rs = s.executeQuery("SELECT @@session.lock_wait_timeout, @@session.innodb_lock_wait_timeout")) {
            rs.next();
            assertThat(rs.getLong(1)).isEqualTo(5);
            assertThat(rs.getLong(2)).isEqualTo(5);
        }
    }

    @Test
    void aPreambleCannotLoosenTheGuardsForTheStatement() throws Exception {
        QueryResult result = run("SET SESSION lock_wait_timeout = 31536000; "
                + "SELECT @@session.lock_wait_timeout, @@session.sql_safe_updates, @@session.max_execution_time",
            QueryExecutionContext.editor("analyst", false, false));
        assertThat(cell(result, 0)).isEqualTo("5");
        assertThat(cell(result, 1)).isEqualTo("1");
        // A writable server: reads capped at 30s whatever the client asked for (240s).
        assertThat(cell(result, 2)).isEqualTo("30000");
    }

    @Test
    void nothingAUserSetLeaksToTheNextBorrower() throws Exception {
        run("SET SESSION foreign_key_checks = 0; SET SESSION sql_mode = ''; SELECT 1",
            QueryExecutionContext.editor("dba", true, false));

        try (Connection c = pool.getConnection(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
            "SELECT @@session.foreign_key_checks, @@session.sql_mode = @@global.sql_mode, "
                + "@@session.sql_safe_updates, @@session.max_execution_time, @@session.lock_wait_timeout")) {
            rs.next();
            assertThat(rs.getInt(1)).as("foreign_key_checks").isEqualTo(1);
            assertThat(rs.getInt(2)).as("sql_mode restored").isEqualTo(1);
            assertThat(rs.getInt(3)).as("sql_safe_updates").isEqualTo(0);
            assertThat(rs.getLong(4)).as("max_execution_time").isEqualTo(0);
            assertThat(rs.getLong(5)).as("lock_wait_timeout baseline").isEqualTo(5);
        }
    }

    @Test
    void aReplicaGetsTheClientTimeoutNotTheThirtySecondCap() throws Exception {
        try (Connection c = DriverManager.getConnection(baseUrl); Statement s = c.createStatement()) {
            s.execute("SET GLOBAL read_only = 1");
        }
        try {
            QueryResult result = run("SELECT @@session.max_execution_time",
                QueryExecutionContext.editor("analyst", false, false));
            assertThat(cell(result, 0)).isEqualTo("240000");
        } finally {
            try (Connection c = DriverManager.getConnection(baseUrl); Statement s = c.createStatement()) {
                s.execute("SET GLOBAL read_only = 0");
            }
        }
    }

    @Test
    void maxExecutionTimeReallyStopsAReadAndIsExplained() throws Exception {
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.execute("SET SESSION max_execution_time = 300");
            SQLException e = assertThrows(SQLException.class, () -> s.executeQuery(
                "SELECT COUNT(*) FROM information_schema.COLUMNS a, information_schema.COLUMNS b, "
                    + "information_schema.COLUMNS c").close());
            assertThat(e.getErrorCode()).isEqualTo(3024);
            assertThat(provider.guardrailMessage(e)).contains("time limit");
        }
    }

    @Test
    void safeUpdatesRefusesAWriteThatDoesNotFilterOnAKey() throws Exception {
        QueryExecutionPolicyException e = assertThrows(QueryExecutionPolicyException.class,
            () -> run("UPDATE customers SET note = 'x' WHERE note IS NULL", confirmedDba()));
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.DB_GUARDRAIL_TRIPPED);
        assertThat(e.getMessage()).contains("indexed");

        QueryResult keyed = run("UPDATE customers SET note = 'x' WHERE id = 1", confirmedDba());
        assertThat(keyed.getRowCount()).isEqualTo(1);
    }

    @Test
    void aRowLockWaitAlsoGivesUpAfterFiveSeconds() throws Exception {
        try (Connection holder = raw()) {
            holder.setAutoCommit(false);
            try (Statement s = holder.createStatement()) {
                s.executeUpdate("UPDATE customers SET note = 'held' WHERE id = 2");
            }
            long start = System.nanoTime();
            QueryExecutionPolicyException e = assertThrows(QueryExecutionPolicyException.class,
                () -> run("UPDATE customers SET note = 'mine' WHERE id = 2", confirmedDba()));
            double seconds = (System.nanoTime() - start) / 1e9;
            holder.rollback();
            System.out.printf("[live] row lock wait gave up after %.2fs%n", seconds);
            assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.DB_GUARDRAIL_TRIPPED);
            assertThat(seconds).isBetween(4.5, 9.0);
        }
    }

    // ---- what the policy refuses, and why it must ------------------------------------

    @Test
    void mysqlReallyExecutesTheBodyOfAnExecutableComment() throws Exception {
        // The threat model, proven: the server runs it, while a comment-stripping check sees nothing.
        try (Connection c = raw(); Statement s = c.createStatement()) {
            s.execute("/*!50000 CREATE TABLE exec_comment_proof (id INT) */");
            try (ResultSet rs = s.executeQuery("SELECT COUNT(*) FROM information_schema.TABLES WHERE TABLE_SCHEMA = '"
                + SCHEMA + "' AND TABLE_NAME = 'exec_comment_proof'")) {
                rs.next();
                assertThat(rs.getInt(1)).isEqualTo(1);
            }
            s.execute("DROP TABLE exec_comment_proof");
        }
        QueryExecutionPolicyException e = assertThrows(QueryExecutionPolicyException.class,
            () -> run("/*!50000 CREATE TABLE exec_comment_proof (id INT) */", confirmedDba()));
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.UNSAFE_MUTATION_BLOCKED);
    }

    @Test
    void setGlobalIsRefusedEvenForAConfirmedAdmin() {
        QueryExecutionPolicyException e = assertThrows(QueryExecutionPolicyException.class,
            () -> run("SET GLOBAL max_connections = 10", QueryExecutionContext.editor("admin", true, true)));
        assertThat(e.getErrorCode()).isEqualTo(QueryExecutionPolicyException.ADMIN_STATEMENT_BLOCKED);
    }

    @Test
    void theActorTagReachesTheServer() throws Exception {
        // What the slow log and Performance Insights would see.
        run("SELECT 'tag-probe' AS probe", QueryExecutionContext.editor("dba", true, false));
        try (Connection c = raw(); Statement s = c.createStatement(); ResultSet rs = s.executeQuery(
            // events_statements_history (last N per thread) is on by default; _long is not.
            "SELECT SQL_TEXT FROM performance_schema.events_statements_history "
                + "WHERE SQL_TEXT LIKE '%tag-probe%' AND SQL_TEXT NOT LIKE '%events_statements%' "
                + "ORDER BY EVENT_ID DESC LIMIT 1")) {
            assertThat(rs.next()).as("statement recorded").isTrue();
            assertThat(rs.getString(1)).startsWith("/* deepsql origin=editor user=dba */ SELECT 'tag-probe'");
        }
    }

    @Test
    void theOnlineAttemptsAreValidSqlOnThisServer() {
        // Guards against appended-clause syntax drift across MySQL versions.
        assertThat(provider.onlineDdlAttempts("ALTER TABLE customers ADD COLUMN y INT -- trailing"))
            .allSatisfy(sql -> assertThat(sql).contains("\n, ALGORITHM="));
        assertThat(List.of(provider.statementGuardSql(240, "8.4.11"), provider.statementGuardResetSql("8.4.11")))
            .allSatisfy(sql -> {
                try (Connection c = raw(); Statement s = c.createStatement()) {
                    s.execute(sql);
                } catch (SQLException ex) {
                    throw new AssertionError(sql + " -> " + ex.getMessage(), ex);
                }
            });
    }
}
