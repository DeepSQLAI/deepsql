package com.dbaagent.config;

import com.dbaagent.model.InitStage;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.util.Arrays;
import java.util.stream.Collectors;

/**
 * Self-hosted runtime schema compatibility bootstrap.
 *
 * <p>The self-hosted Docker image relies on Hibernate schema updates, not Flyway,
 * so enum-backed CHECK constraints do not evolve automatically. This initializer
 * keeps the Brain init stage constraints aligned with the current {@link InitStage}
 * values before scheduled Brain jobs begin.
 */
@Configuration
@Slf4j
public class BrainInitSchemaCompatibilityInitializer {

    @Bean("brainInitSchemaCompatibilityBootstrap")
    @DependsOn("entityManagerFactory")
    public Object brainInitSchemaCompatibilityBootstrap(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        String allowedStages = Arrays.stream(InitStage.values())
                .map(InitStage::name)
                .map(stage -> "'" + stage + "'")
                .collect(Collectors.joining(", "));

        ensureConstraint(
                jdbc,
                "connection_init_status",
                "current_stage",
                "connection_init_status_current_stage_check",
                allowedStages
        );

        ensureConstraint(
                jdbc,
                "connection_init_history",
                "final_stage",
                "connection_init_history_final_stage_check",
                allowedStages
        );

        return new Object();
    }

    private void ensureConstraint(
            JdbcTemplate jdbc,
            String tableName,
            String columnName,
            String constraintName,
            String allowedStages) {

        if (!tableExists(jdbc, tableName) || !columnExists(jdbc, tableName, columnName)) {
            return;
        }

        jdbc.execute("ALTER TABLE " + tableName + " DROP CONSTRAINT IF EXISTS " + constraintName);
        jdbc.execute(
                "ALTER TABLE " + tableName
                        + " ADD CONSTRAINT " + constraintName
                        + " CHECK (" + columnName + " IN (" + allowedStages + "))"
        );

        log.info("Ensured Brain init stage constraint {} on {}.{}", constraintName, tableName, columnName);
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
