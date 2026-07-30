package com.dbaagent.provider.postgres;

import com.dbaagent.model.ConnectionTestResult;
import com.dbaagent.provider.api.PrivilegeCheckProvider;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

@Component
@Slf4j
public class PostgresPrivilegeCheckProvider implements PrivilegeCheckProvider {

    @Override
    public void checkPrivileges(
        Connection connection,
        String database,
        List<ConnectionTestResult.PrivilegeCheck> checks
    ) {
        checks.add(checkSelectOnTables(connection));
        checks.add(checkPrivilege(
            connection,
            "pg_stat_statements",
            "Extension",
            "Slow query analysis",
            "SELECT 1 FROM pg_stat_statements LIMIT 1"
        ));
        checks.add(checkPrivilege(
            connection,
            "pg_stat_user_tables",
            "System view",
            "Table statistics",
            "SELECT relname FROM pg_stat_user_tables LIMIT 1"
        ));
        checks.add(checkPrivilege(
            connection,
            "information_schema",
            "System schema",
            "Schema introspection",
            "SELECT column_name FROM information_schema.columns " +
                "WHERE table_schema = 'public' LIMIT 1"
        ));
    }

    private ConnectionTestResult.PrivilegeCheck checkSelectOnTables(Connection connection) {
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(10);

            String listTablesQuery =
                "SELECT table_name FROM information_schema.tables " +
                "WHERE table_schema = 'public' " +
                "AND table_type = 'BASE TABLE' LIMIT 5";

            ResultSet tablesRs = stmt.executeQuery(listTablesQuery);
            List<String> tables = new ArrayList<>();
            while (tablesRs.next()) {
                tables.add(tablesRs.getString(1));
            }
            tablesRs.close();

            if (tables.isEmpty()) {
                return ConnectionTestResult.PrivilegeCheck.builder()
                    .name("SELECT")
                    .scope("All tables")
                    .reason("Read data for analysis")
                    .granted(true)
                    .errorMessage(
                        "No tables found to verify SELECT access " +
                            "(database may be empty)"
                    )
                    .build();
            }

            StringBuilder failedTables = new StringBuilder();
            for (String tableName : tables) {
                try (Statement selectStmt = connection.createStatement()) {
                    selectStmt.setQueryTimeout(5);
                    String selectQuery = "SELECT 1 FROM \"" + tableName + "\" LIMIT 1";
                    ResultSet rs = selectStmt.executeQuery(selectQuery);
                    rs.close();
                    return ConnectionTestResult.PrivilegeCheck.builder()
                        .name("SELECT")
                        .scope("All tables")
                        .reason("Read data for analysis")
                        .granted(true)
                        .build();
                } catch (SQLException e) {
                    if (failedTables.length() > 0) {
                        failedTables.append(", ");
                    }
                    failedTables.append(tableName);
                    log.debug("SELECT failed on table {}: {}", tableName, e.getMessage());
                }
            }

            return ConnectionTestResult.PrivilegeCheck.builder()
                .name("SELECT")
                .scope("All tables")
                .reason("Read data for analysis")
                .granted(false)
                .errorMessage("Cannot SELECT from tables: " + failedTables)
                .build();

        } catch (SQLException e) {
            log.debug("Privilege check failed for SELECT on tables: {}", e.getMessage());
            return ConnectionTestResult.PrivilegeCheck.builder()
                .name("SELECT")
                .scope("All tables")
                .reason("Read data for analysis")
                .granted(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }

    private ConnectionTestResult.PrivilegeCheck checkPrivilege(
        Connection connection,
        String name,
        String scope,
        String reason,
        String testQuery
    ) {
        try (Statement stmt = connection.createStatement()) {
            stmt.setQueryTimeout(10);
            ResultSet rs = stmt.executeQuery(testQuery);
            rs.close();
            return ConnectionTestResult.PrivilegeCheck.builder()
                .name(name)
                .scope(scope)
                .reason(reason)
                .granted(true)
                .build();
        } catch (SQLException e) {
            log.debug("Privilege check failed for {}: {}", name, e.getMessage());
            return ConnectionTestResult.PrivilegeCheck.builder()
                .name(name)
                .scope(scope)
                .reason(reason)
                .granted(false)
                .errorMessage(e.getMessage())
                .build();
        }
    }
}
