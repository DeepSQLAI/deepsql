package com.dbaagent.provider.api;

import com.dbaagent.model.ConnectionTestResult;

import java.sql.Connection;
import java.util.List;

/**
 * Provider interface for database-specific privilege checks.
 */
public interface PrivilegeCheckProvider {

    /**
     * Populate the privilege checks for the given connection.
     *
     * @param connection The JDBC connection
     * @param database   The database name (may be null)
     * @param checks     The list to populate with checks
     */
    void checkPrivileges(
        Connection connection,
        String database,
        List<ConnectionTestResult.PrivilegeCheck> checks
    );
}
