package com.dbaagent.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.DependsOn;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Drops the stale CHECK constraints Hibernate generated for
 * {@code role_permission_overrides} under the original two-role, twenty-permission
 * enums.
 *
 * <p>{@code ddl-auto=update} adds columns and tables but <em>never drops a constraint it
 * previously created</em>, so on any database created before the role model changed both
 * checks survive and reject every new value. Inserting an override for the DBA role, or
 * for any of the new section permissions, fails with:
 *
 * <pre>
 *   ERROR: new row for relation "role_permission_overrides" violates check constraint
 *          "role_permission_overrides_permission_code_check"
 * </pre>
 *
 * <p>That was observed against a live install, not inferred — the tables looked correct
 * and only an actual INSERT revealed it. There is no Flyway runtime in this repo
 * (see CLAUDE.md), so this initializer is what actually applies the matching statements
 * in {@code V117__create_dashboard_workspaces_and_custom_roles.sql}.
 *
 * <p>Dropping rather than rewriting the constraints is deliberate: the {@code Role} /
 * {@code Permission} enums plus {@code PermissionService} are the authority for these
 * values, and a database-level copy of an enum has to be re-migrated on every future
 * addition — which is precisely how this broke.
 */
@Configuration
@Slf4j
public class RolePermissionConstraintInitializer {

    private static final String TABLE = "role_permission_overrides";

    @Bean("rolePermissionConstraintBootstrap")
    @DependsOn("entityManagerFactory")
    public Object rolePermissionConstraintBootstrap(DataSource dataSource) {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);

        if (!tableExists(jdbc, TABLE)) {
            return new Object();
        }

        int dropped = 0;
        dropped += dropCheckIfPresent(jdbc, TABLE + "_role_check");
        dropped += dropCheckIfPresent(jdbc, TABLE + "_permission_code_check");

        // Widen the role column so a custom role code fits; harmless if already wide.
        try {
            jdbc.execute("ALTER TABLE " + TABLE + " ALTER COLUMN role TYPE VARCHAR(64)");
        } catch (RuntimeException e) {
            log.warn("Could not widen {}.role: {}", TABLE, e.getMessage());
        }

        if (dropped > 0) {
            log.info("Dropped {} stale CHECK constraint(s) on {} left over from the previous role model",
                dropped, TABLE);
        }
        return new Object();
    }

    private int dropCheckIfPresent(JdbcTemplate jdbc, String constraintName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.table_constraints
                WHERE table_schema = 'public' AND table_name = ? AND constraint_name = ?
                """, Integer.class, TABLE, constraintName);
        if (count == null || count == 0) {
            return 0;
        }
        try {
            jdbc.execute("ALTER TABLE " + TABLE + " DROP CONSTRAINT IF EXISTS " + constraintName);
            return 1;
        } catch (RuntimeException e) {
            log.warn("Could not drop stale constraint {}: {}", constraintName, e.getMessage());
            return 0;
        }
    }

    private boolean tableExists(JdbcTemplate jdbc, String tableName) {
        Integer count = jdbc.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name = ?
                """, Integer.class, tableName);
        return count != null && count > 0;
    }
}
