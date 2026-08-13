package com.dbaagent.service;

import com.dbaagent.model.ActiveQuery;
import com.dbaagent.model.ConnectionRequest;
import com.dbaagent.provider.DatabaseProviderRegistry;
import com.dbaagent.repository.ActiveQueryRepository;
import com.dbaagent.util.SessionKillSupport;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.sql.*;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class ActiveQueryService {

    private final ActiveQueryRepository activeQueryRepository;
    private final CredentialService credentialService;
    private final ConnectionService connectionService;
    private final DatabaseProviderRegistry providerRegistry;

    /**
     * Capture current active queries from the database
     */
    @Transactional
    public List<ActiveQuery> captureActiveQueries(String connectionId) {
        log.info("Capturing active queries for connection: {}", connectionId);

        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());
                List<ActiveQuery> queries;

                if ("postgres".equals(dbType)) {
                    queries = capturePostgreSQLQueries(connectionId, conn);
                } else if ("mysql".equals(dbType)) {
                    queries = captureMySQLQueries(connectionId, conn);
                } else {
                    log.warn("Active query monitoring not supported for database type: {}", dbType);
                    return Collections.emptyList();
                }

                // Save the snapshot
                if (!queries.isEmpty()) {
                    activeQueryRepository.saveAll(queries);
                    log.info("Captured {} active queries", queries.size());
                }

                return queries;
            }
        } catch (Exception e) {
            log.error("Error capturing active queries", e);
            throw new RuntimeException("Failed to capture active queries: " + e.getMessage(), e);
        }
    }

    /**
     * Capture PostgreSQL active queries using pg_stat_activity
     */
    private List<ActiveQuery> capturePostgreSQLQueries(String connectionId, Connection conn) throws SQLException {
        List<ActiveQuery> queries = new ArrayList<>();

        String query = """
            SELECT
                pid,
                usename AS user,
                datname AS database,
                application_name,
                client_addr::text AS client_address,
                client_port,
                state,
                query AS query_text,
                query_start,
                state_change,
                backend_start,
                xact_start AS transaction_start,
                wait_event_type,
                wait_event,
                EXTRACT(EPOCH FROM (NOW() - query_start)) AS duration_seconds,
                EXTRACT(EPOCH FROM (NOW() - xact_start)) AS transaction_duration_seconds,
                CASE WHEN wait_event IS NOT NULL AND wait_event_type = 'Lock' THEN true ELSE false END AS is_blocked
            FROM pg_stat_activity
            WHERE pid != pg_backend_pid()
                AND state IS NOT NULL
                AND datname IS NOT NULL
            ORDER BY query_start NULLS LAST
            """;

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                String queryText = rs.getString("query_text");
                String state = rs.getString("state");
                Long durationSeconds = getLongFromDouble(rs, "duration_seconds");

                ActiveQuery activeQuery = ActiveQuery.builder()
                    .connectionId(connectionId)
                    .pid(String.valueOf(rs.getInt("pid")))
                    .user(rs.getString("user"))
                    .database(rs.getString("database"))
                    .applicationName(rs.getString("application_name"))
                    .clientAddress(rs.getString("client_address"))
                    .clientPort(rs.getInt("client_port"))
                    .queryText(truncateQuery(queryText))
                    .state(state)
                    .queryStart(toLocalDateTime(rs.getTimestamp("query_start")))
                    .stateChange(toLocalDateTime(rs.getTimestamp("state_change")))
                    .backendStart(toLocalDateTime(rs.getTimestamp("backend_start")))
                    .transactionStart(toLocalDateTime(rs.getTimestamp("transaction_start")))
                    .durationSeconds(durationSeconds)
                    .transactionDurationSeconds(getLongFromDouble(rs, "transaction_duration_seconds"))
                    .waitEvent(rs.getString("wait_event"))
                    .waitEventType(rs.getString("wait_event_type"))
                    .isBlocked(rs.getBoolean("is_blocked"))
                    .queryType(ActiveQuery.classifyQueryType(queryText))
                    .priority(ActiveQuery.calculatePriority(durationSeconds, state))
                    .build();

                queries.add(activeQuery);
            }
        }

        return queries;
    }

    /**
     * Capture MySQL active queries using SHOW PROCESSLIST
     */
    private List<ActiveQuery> captureMySQLQueries(String connectionId, Connection conn) throws SQLException {
        List<ActiveQuery> queries = new ArrayList<>();

        String query = "SELECT * FROM information_schema.PROCESSLIST";

        try (Statement stmt = conn.createStatement();
             ResultSet rs = stmt.executeQuery(query)) {

            while (rs.next()) {
                String processId = String.valueOf(rs.getLong("ID"));
                String user = rs.getString("USER");
                String database = rs.getString("DB");
                String command = rs.getString("COMMAND");
                Long timeSeconds = rs.getLong("TIME");
                String state = rs.getString("STATE");
                String queryText = rs.getString("INFO");

                // Skip system processes
                if ("system user".equalsIgnoreCase(user) || "event_scheduler".equalsIgnoreCase(user)) {
                    continue;
                }

                // Map MySQL command to state
                String normalizedState = normalizeMySQLState(command, state);

                // Check if blocked (waiting on lock)
                boolean isBlocked = state != null && (
                    state.toLowerCase().contains("lock") ||
                    state.toLowerCase().contains("waiting for")
                );

                ActiveQuery activeQuery = ActiveQuery.builder()
                    .connectionId(connectionId)
                    .pid(processId)
                    .user(user)
                    .database(database)
                    .applicationName(rs.getString("HOST"))
                    .clientAddress(extractHost(rs.getString("HOST")))
                    .clientPort(extractPort(rs.getString("HOST")))
                    .queryText(truncateQuery(queryText))
                    .state(normalizedState)
                    .durationSeconds(timeSeconds)
                    .waitEvent(state)
                    .isBlocked(isBlocked)
                    .queryType(ActiveQuery.classifyQueryType(queryText))
                    .priority(ActiveQuery.calculatePriority(timeSeconds, normalizedState))
                    .build();

                queries.add(activeQuery);
            }
        }

        return queries;
    }

    /**
     * Kill a specific query by PID
     */
    @Transactional
    public void killQuery(String connectionId, String pid) {
        long backendPid = SessionKillSupport.requireNumericPid(pid);
        log.info("Killing query {} on connection {}", backendPid, connectionId);

        try {
            ConnectionRequest connRequest = credentialService.getDecryptedConnection(connectionId);
            try (Connection conn = connectionService.getConnection(connectionId, connRequest)) {
                String dbType = providerRegistry.getCanonicalName(connRequest.getDbType());

                if ("postgres".equals(dbType)) {
                    try (PreparedStatement ps = conn.prepareStatement("SELECT pg_terminate_backend(?)")) {
                        ps.setLong(1, backendPid);
                        ps.execute();
                    }
                } else if ("mysql".equals(dbType)) {
                    // MySQL KILL is not reliably parameterizable across drivers; pid is digit-only.
                    try (Statement stmt = conn.createStatement()) {
                        stmt.execute("KILL QUERY " + backendPid);
                    }
                } else {
                    throw new IllegalArgumentException("Kill query not supported for database type: " + dbType);
                }
                log.info("Successfully killed query {}", backendPid);
            }
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error killing query {}", backendPid, e);
            throw new RuntimeException("Failed to kill query: " + e.getMessage(), e);
        }
    }

    /**
     * Get the latest snapshot of active queries
     */
    public List<ActiveQuery> getLatestQueries(String connectionId) {
        return activeQueryRepository.findLatestSnapshot(connectionId);
    }

    /**
     * Get queries by filter criteria
     */
    public List<ActiveQuery> getQueriesByFilter(String connectionId, String filterType, String filterValue) {
        return switch (filterType.toUpperCase()) {
            case "STATE" -> activeQueryRepository.findByState(connectionId, filterValue);
            case "QUERY_TYPE" -> activeQueryRepository.findByQueryType(connectionId, filterValue);
            case "USER" -> activeQueryRepository.findByUser(connectionId, filterValue);
            case "DATABASE" -> activeQueryRepository.findByDatabase(connectionId, filterValue);
            case "PRIORITY" -> activeQueryRepository.findByPriority(connectionId, ActiveQuery.Priority.valueOf(filterValue));
            case "LONG_RUNNING" -> activeQueryRepository.findLongRunning(connectionId, Long.parseLong(filterValue));
            case "BLOCKED" -> activeQueryRepository.findBlocked(connectionId);
            default -> getLatestQueries(connectionId);
        };
    }

    /**
     * Get statistics about active queries
     */
    public Map<String, Object> getStatistics(String connectionId) {
        List<ActiveQuery> queries = activeQueryRepository.findLatestSnapshot(connectionId);

        Map<String, Object> stats = new HashMap<>();
        stats.put("total", queries.size());

        // Count by state
        Map<String, Long> stateCount = new HashMap<>();
        for (ActiveQuery q : queries) {
            stateCount.merge(q.getState(), 1L, Long::sum);
        }
        stats.put("byState", stateCount);

        // Count by query type
        Map<String, Long> typeCount = new HashMap<>();
        for (ActiveQuery q : queries) {
            typeCount.merge(q.getQueryType(), 1L, Long::sum);
        }
        stats.put("byQueryType", typeCount);

        // Count by priority
        Map<String, Long> priorityCount = new HashMap<>();
        for (ActiveQuery q : queries) {
            priorityCount.merge(q.getPriority().toString(), 1L, Long::sum);
        }
        stats.put("byPriority", priorityCount);

        // Active queries
        long activeCount = queries.stream().filter(q -> "active".equalsIgnoreCase(q.getState())).count();
        stats.put("active", activeCount);

        // Idle queries
        long idleCount = queries.stream().filter(q -> q.getState() != null && q.getState().toLowerCase().contains("idle")).count();
        stats.put("idle", idleCount);

        // Blocked queries
        long blockedCount = queries.stream().filter(q -> Boolean.TRUE.equals(q.getIsBlocked())).count();
        stats.put("blocked", blockedCount);

        // Long-running (>60s)
        long longRunningCount = queries.stream()
            .filter(q -> q.getDurationSeconds() != null && q.getDurationSeconds() > 60)
            .count();
        stats.put("longRunning", longRunningCount);

        // Average duration of active queries
        OptionalDouble avgDuration = queries.stream()
            .filter(q -> "active".equalsIgnoreCase(q.getState()) && q.getDurationSeconds() != null)
            .mapToLong(ActiveQuery::getDurationSeconds)
            .average();
        stats.put("avgDuration", avgDuration.isPresent() ? avgDuration.getAsDouble() : 0);

        // Max duration
        OptionalLong maxDuration = queries.stream()
            .filter(q -> q.getDurationSeconds() != null)
            .mapToLong(ActiveQuery::getDurationSeconds)
            .max();
        stats.put("maxDuration", maxDuration.isPresent() ? maxDuration.getAsLong() : 0);

        return stats;
    }

    /**
     * Get available filter values
     */
    public Map<String, List<String>> getFilterOptions(String connectionId) {
        Map<String, List<String>> options = new HashMap<>();

        options.put("users", activeQueryRepository.findDistinctUsers(connectionId));
        options.put("databases", activeQueryRepository.findDistinctDatabases(connectionId));

        List<ActiveQuery> queries = activeQueryRepository.findLatestSnapshot(connectionId);

        Set<String> states = new HashSet<>();
        Set<String> queryTypes = new HashSet<>();
        for (ActiveQuery q : queries) {
            if (q.getState() != null) states.add(q.getState());
            if (q.getQueryType() != null) queryTypes.add(q.getQueryType());
        }

        options.put("states", new ArrayList<>(states));
        options.put("queryTypes", new ArrayList<>(queryTypes));
        options.put("priorities", List.of("CRITICAL", "HIGH", "MEDIUM", "LOW"));

        return options;
    }

    /**
     * Cleanup old snapshots (keep only last 24 hours)
     */
    @Transactional
    public int cleanupOldSnapshots(String connectionId) {
        LocalDateTime cutoff = LocalDateTime.now().minusHours(24);
        int deleted = activeQueryRepository.deleteOldSnapshots(connectionId, cutoff);
        log.info("Deleted {} old query snapshots", deleted);
        return deleted;
    }

    // Helper methods

    private String normalizeMySQLState(String command, String state) {
        if (command == null) return "unknown";

        command = command.toLowerCase();
        if (command.equals("query")) {
            return "active";
        } else if (command.equals("sleep")) {
            return "idle";
        } else if (command.contains("connect")) {
            return "connecting";
        } else {
            return state != null ? state : command;
        }
    }

    private String extractHost(String hostPort) {
        if (hostPort == null) return null;
        int colonIndex = hostPort.lastIndexOf(':');
        return colonIndex > 0 ? hostPort.substring(0, colonIndex) : hostPort;
    }

    private Integer extractPort(String hostPort) {
        if (hostPort == null) return null;
        int colonIndex = hostPort.lastIndexOf(':');
        if (colonIndex > 0) {
            try {
                return Integer.parseInt(hostPort.substring(colonIndex + 1));
            } catch (NumberFormatException e) {
                return null;
            }
        }
        return null;
    }

    private String truncateQuery(String query) {
        if (query == null) {
            return "";
        }
        query = query.trim();
        if (query.length() > 1000) {
            return query.substring(0, 997) + "...";
        }
        return query;
    }

    private Long getLongFromDouble(ResultSet rs, String columnName) throws SQLException {
        double value = rs.getDouble(columnName);
        return rs.wasNull() ? null : Math.round(value);
    }

    private LocalDateTime toLocalDateTime(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        return timestamp.toInstant()
            .atZone(ZoneId.systemDefault())
            .toLocalDateTime();
    }
}
