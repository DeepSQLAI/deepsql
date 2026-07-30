package com.dbaagent.service;

import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.model.SlowQuery;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.provider.api.DatabaseDialect;
import com.dbaagent.provider.api.SlowQueryProvider;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Service for collecting slow queries from multiple sources
 * Supports MySQL (FILE, TABLE, Performance Schema) and PostgreSQL (pg_stat_statements)
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class SlowQueryCollectorService {

    private final DatabaseProviderRegistry providerRegistry;
    private final ConnectionService connectionService;

    /**
     * Collect slow queries from all available sources and deduplicate.
     * Uses ConnectionService for JDBC access, which supports SSH tunneling and alias resolution.
     *
     * @param connectionId The connection ID for looking up the connection pool
     * @param connection The decrypted connection request with credentials
     * @param thresholdMs Minimum execution time in milliseconds to consider a query "slow"
     * @param limit Maximum number of slow queries to return
     * @param hoursBack How many hours back to search (for table/file sources)
     * @return List of slow queries sorted by performance impact
     */
    public List<SlowQuery> collectSlowQueries(
        String connectionId,
        ConnectionRequest connection,
        double thresholdMs,
        int limit,
        int hoursBack
    ) {
        // Use canonical name to support aliases (mariadb, aurora-mysql, aurora-postgres, etc.)
        String dbType = providerRegistry.getCanonicalName(connection.getDbType());
        DatabaseDialect dialect = providerRegistry.getDialect(dbType);
        SlowQueryProvider provider = dialect.slowQueries();
        if (provider == null) {
            throw new IllegalArgumentException("SlowQueryProvider not available for: " + dbType);
        }

        try (Connection jdbcConn = connectionService.getConnection(connectionId, connection)) {
            return provider.collectSlowQueries(
                jdbcConn,
                connection.getDatabase(),
                thresholdMs,
                limit
            );
        } catch (SQLException e) {
            log.error("Error collecting slow queries for {}", dbType, e);
            return new ArrayList<>();
        }
    }

}
