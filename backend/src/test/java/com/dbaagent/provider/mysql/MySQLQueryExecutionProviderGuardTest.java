package com.dbaagent.provider.mysql;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * The guardrails added after a production incident: an
 * {@code ALTER TABLE customers MODIFY ... NOT NULL} queued every write on the table for
 * four minutes, until the Editor's 240s client timeout killed it.
 */
class MySQLQueryExecutionProviderGuardTest {

    private final MySQLQueryExecutionProvider provider = new MySQLQueryExecutionProvider();

    // ---- online DDL ----------------------------------------------------------

    @Test
    void anAlterWithNoAlgorithmTriesInstantThenInplaceWithoutLocks() {
        List<String> attempts = provider.onlineDdlAttempts(
            "ALTER TABLE customers MODIFY email varchar(512) NOT NULL;");

        assertThat(attempts).containsExactly(
            "ALTER TABLE customers MODIFY email varchar(512) NOT NULL\n, ALGORITHM=INSTANT",
            "ALTER TABLE customers MODIFY email varchar(512) NOT NULL\n, ALGORITHM=INPLACE, LOCK=NONE");
    }

    @Test
    void theAppendedClauseCannotBeSwallowedByATrailingLineComment() {
        // Appended on the same line, ", ALGORITHM=INSTANT" would sit inside the
        // comment and the ALTER would run with MySQL's default (possibly blocking)
        // algorithm — a silent bypass of the whole guard.
        List<String> attempts = provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN c INT -- for reports");

        assertThat(attempts.getFirst()).endsWith("-- for reports\n, ALGORITHM=INSTANT");
    }

    @Test
    void inplaceWithoutALockClauseGetsLockNone() {
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD INDEX i (c), ALGORITHM=INPLACE"))
            .containsExactly("ALTER TABLE t ADD INDEX i (c), ALGORITHM=INPLACE\n, LOCK=NONE");
    }

    @Test
    void explicitlyOnlineStatementsRunAsWritten() {
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN c INT, ALGORITHM=INSTANT"))
            .containsExactly("ALTER TABLE t ADD COLUMN c INT, ALGORITHM=INSTANT");
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD INDEX i (c), ALGORITHM=INPLACE, LOCK=NONE"))
            .containsExactly("ALTER TABLE t ADD INDEX i (c), ALGORITHM=INPLACE, LOCK=NONE");
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD INDEX i (c), LOCK = NONE"))
            .containsExactly("ALTER TABLE t ADD INDEX i (c), LOCK = NONE");
    }

    @Test
    void blockingAlgorithmsAndLocksAreRefused() {
        for (String sql : List.of(
            "ALTER TABLE t MODIFY c varchar(512), ALGORITHM=COPY",
            "ALTER TABLE t MODIFY c varchar(512), ALGORITHM COPY",
            "ALTER TABLE t MODIFY c varchar(512), ALGORITHM=DEFAULT",
            "ALTER TABLE t MODIFY c varchar(512), LOCK=SHARED",
            "ALTER TABLE t MODIFY c varchar(512), LOCK=EXCLUSIVE",
            "ALTER TABLE t MODIFY c varchar(512), ALGORITHM=INPLACE, LOCK=DEFAULT")) {
            IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
                () -> provider.onlineDdlAttempts(sql), sql);
            assertThat(e.getMessage()).contains("gh-ost");
        }
    }

    @Test
    void wordsInsideLiteralsAndIdentifiersAreNotClauses() {
        // A comment string or a column named `lock` must not read as a LOCK clause.
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t COMMENT 'LOCK=SHARED please'"))
            .hasSize(2);
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN `lock` INT"))
            .hasSize(2);
    }

    @Test
    void nonAlterStatementsPassThrough() {
        assertThat(provider.onlineDdlAttempts("CREATE TABLE t (id INT)")).containsExactly("CREATE TABLE t (id INT)");
        assertThat(provider.onlineDdlAttempts("UPDATE t SET a = 1 WHERE id = 2"))
            .containsExactly("UPDATE t SET a = 1 WHERE id = 2");
    }

    @Test
    void onlyAlgorithmNotSupportedErrorsTriggerTheFallback() {
        assertThat(provider.isOnlineDdlUnsupported(new SQLException(
            "ALGORITHM=INSTANT is not supported. Reason: Need to rebuild the table. Try ALGORITHM=COPY/INPLACE.",
            "0A000", 1846))).isTrue();
        assertThat(provider.isOnlineDdlUnsupported(new SQLException("Lock wait timeout exceeded", "HY000", 1205)))
            .isFalse();
    }

    // ---- session guards ------------------------------------------------------

    @Test
    void everyPooledConnectionGivesUpOnLocksAfterFiveSeconds() {
        assertThat(provider.connectionGuardSql())
            .isEqualTo("SET SESSION lock_wait_timeout = 5, innodb_lock_wait_timeout = 5");
    }

    @Test
    void perStatementGuardCapsReadsAtThirtySecondsOnlyOnAWritableServer() {
        // innodb_read_only too: Aurora readers report read_only=0.
        assertThat(provider.statementGuardSql(240, "8.0.36")).isEqualTo(
            "SET SESSION lock_wait_timeout = 5, innodb_lock_wait_timeout = 5, sql_safe_updates = 1, "
                + "max_execution_time = IF(@@global.read_only OR @@global.innodb_read_only, 240000, LEAST(240000, 30000))");
        assertThat(provider.statementGuardSql(0, null))
            .contains("IF(@@global.read_only OR @@global.innodb_read_only, 30000, LEAST(30000, 30000))");
    }

    @Test
    void clauseDetectionStaysLinearOnUnterminatedCommentsAndQuotes() {
        // A lazy /\*.*?\*/ rescans to the end from every unterminated opener.
        for (String tail : List.of("/*" + "a/*".repeat(32_000), "'" + "a\\'".repeat(32_000), "`" + "a".repeat(64_000))) {
            long startedAt = System.currentTimeMillis();
            provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN x INT " + tail);
            assertThat(System.currentTimeMillis() - startedAt).as("must not backtrack").isLessThan(5_000L);
        }
    }

    @Test
    void aClauseInsideACommentIsNotAClause() {
        // Read as real, this ran the MODIFY with MySQL's default (COPY) algorithm, unguarded.
        assertThat(provider.onlineDdlAttempts(
            "ALTER TABLE bookings MODIFY status VARCHAR(20) NOT NULL -- , ALGORITHM=INPLACE, LOCK=NONE"))
            .hasSize(2);
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN x INT /* LOCK=SHARED */")).hasSize(2);
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN x INT # ALGORITHM=COPY")).hasSize(2);
        // A quote inside a comment must not desynchronise literal stripping: the real
        // COPY clause on the next line is still seen, and refused.
        assertThrows(IllegalArgumentException.class,
            () -> provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN x INT -- don't\n, ALGORITHM=COPY"));
    }

    @Test
    void mariaDbGetsNoMaxExecutionTimeBecauseItDoesNotHaveOne() {
        // The MySQL provider also serves "mariadb" connections. An unknown variable fails the
        // SET, and the executor fails closed — every query on the connection would be refused.
        assertThat(provider.statementGuardSql(240, "10.11.6-MariaDB-log")).isEqualTo(
            "SET SESSION lock_wait_timeout = 5, innodb_lock_wait_timeout = 5, sql_safe_updates = 1");
        assertThat(provider.statementGuardResetSql("10.11.6-MariaDB-log")).doesNotContain("max_execution_time");
    }

    @Test
    void theResetRestoresThePoolBaselineSoNothingLeaksToTheNextBorrower() {
        // Also the settings a preamble could use to weaken the next borrower's writes.
        assertThat(provider.statementGuardResetSql("8.0.36")).isEqualTo(
            "SET SESSION lock_wait_timeout = 5, innodb_lock_wait_timeout = 5, sql_safe_updates = DEFAULT, "
                + "sql_mode = DEFAULT, foreign_key_checks = DEFAULT, unique_checks = DEFAULT, time_zone = DEFAULT, "
                + "max_execution_time = DEFAULT");
    }

    @Test
    void serversWithoutInstantFallBackToInplace() {
        // MySQL 5.7 / Aurora MySQL 2 reject INSTANT as a syntax error at the word we appended.
        assertThat(provider.isOnlineDdlUnsupported(new SQLException(
            "You have an error in your SQL syntax; check the manual ... near 'INSTANT' at line 2", "42000", 1064)))
            .isTrue();
        // A genuine syntax error elsewhere is not an excuse to retry.
        assertThat(provider.isOnlineDdlUnsupported(new SQLException(
            "You have an error in your SQL syntax; ... near 'COLUMNN c INT' at line 1", "42000", 1064)))
            .isFalse();
    }

    @Test
    void aPartitionChangeMustStateItsAlgorithmExplicitly() {
        // Partition options come last in an ALTER, so an appended clause is a syntax error.
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class,
            () -> provider.onlineDdlAttempts("ALTER TABLE t ADD PARTITION (PARTITION p3 VALUES LESS THAN (2030))"));
        assertThat(e.getMessage()).contains("ALGORITHM=INPLACE, LOCK=NONE");
        assertThat(provider.onlineDdlAttempts(
            "ALTER TABLE t ALGORITHM=INPLACE, LOCK=NONE, ADD PARTITION (PARTITION p3 VALUES LESS THAN (2030))"))
            .hasSize(1);
        // A column merely named like it is not a partition clause.
        assertThat(provider.onlineDdlAttempts("ALTER TABLE t ADD COLUMN partition_key INT")).hasSize(2);
    }

    // ---- errors --------------------------------------------------------------

    @Test
    void guardTripsAreExplainedInPlainWords() {
        assertThat(provider.guardrailMessage(new SQLException("Lock wait timeout exceeded", "HY000", 1205)))
            .contains("5 seconds");
        assertThat(provider.guardrailMessage(new SQLException("You are using safe update mode", "HY000", 1175)))
            .contains("indexed");
        assertThat(provider.guardrailMessage(new SQLException("maximum statement execution time exceeded", "HY000", 3024)))
            .contains("replica");
        assertThat(provider.guardrailMessage(new SQLException("not supported. Try ALGORITHM=COPY", "0A000", 1846)))
            .contains("gh-ost");
        assertThat(provider.guardrailMessage(new SQLException("Duplicate entry", "23000", 1062))).isNull();
    }
}
