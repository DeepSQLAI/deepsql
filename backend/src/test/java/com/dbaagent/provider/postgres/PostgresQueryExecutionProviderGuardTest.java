package com.dbaagent.provider.postgres;

import org.junit.jupiter.api.Test;

import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;

class PostgresQueryExecutionProviderGuardTest {

    private final PostgresQueryExecutionProvider provider = new PostgresQueryExecutionProvider();

    @Test
    void everyPooledConnectionGivesUpOnLocksAfterFiveSeconds() {
        assertThat(provider.connectionGuardSql()).isEqualTo("SET lock_timeout = '5s'");
    }

    @Test
    void theResetClearsEverySessionSettingNotJustTheGuard() {
        // A preamble, or a read like SELECT set_config(...), can change any setting
        // (session_replication_role, search_path, ...); the pooled connection carries it on.
        assertThat(provider.statementGuardResetSql(null)).isEqualTo("RESET ALL; SET lock_timeout = '5s'");
    }

    @Test
    void aConcurrentIndexBuildIsNotCutOffByTheLockTimeout() {
        // Cut off midway it leaves an INVALID index; its locks block neither reads nor writes.
        assertThat(provider.ddlSessionSql("CREATE INDEX CONCURRENTLY idx ON orders (customer_id)"))
            .isEqualTo("SET lock_timeout = 0");
        assertThat(provider.ddlSessionSql("drop index concurrently idx")).isEqualTo("SET lock_timeout = 0");
        assertThat(provider.ddlSessionSql("ALTER TABLE orders ADD COLUMN x int")).isNull();
    }

    @Test
    void aLockTimeoutIsExplainedInPlainWords() {
        assertThat(provider.guardrailMessage(new SQLException("canceling statement due to lock timeout", "55P03")))
            .contains("5 seconds");
        assertThat(provider.guardrailMessage(new SQLException("duplicate key", "23505"))).isNull();
    }
}
