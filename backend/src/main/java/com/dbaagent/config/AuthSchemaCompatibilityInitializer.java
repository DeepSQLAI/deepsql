package com.dbaagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Keeps self-hosted auth schema upgrades compatible with databases created by
 * older images that relied on Hibernate schema updates.
 */
@Configuration
@Slf4j
public class AuthSchemaCompatibilityInitializer {

    @Bean("authSchemaCompatibilityBootstrap")
    @DependsOn("entityManagerFactory")
    public Object authSchemaCompatibilityBootstrap(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        if (!tableExists(jdbc, "users") || !columnExists(jdbc, "users", "account_status")) {
            return new Object();
        }

        jdbc.execute("""
                UPDATE users
                SET account_status = 'ACTIVE'
                WHERE account_status IS NULL OR btrim(account_status) = ''
                """);
        jdbc.execute("ALTER TABLE users ALTER COLUMN account_status SET DEFAULT 'ACTIVE'");
        jdbc.execute("ALTER TABLE users ALTER COLUMN account_status SET NOT NULL");

        log.info("Ensured users.account_status default and NOT NULL constraint");
        return new Object();
    }

    private boolean tableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }

    private boolean columnExists(JdbcTemplate jdbc, String tableName, String columnName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """, Integer.class, tableName, columnName);
        return count != null && count > 0;
    }
}
